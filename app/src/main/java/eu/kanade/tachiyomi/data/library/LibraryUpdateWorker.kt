package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.smartsource.health.SourceHealthStore
import eu.kanade.tachiyomi.smartsource.model.HealthRecord
import eu.kanade.tachiyomi.smartsource.model.HealthStatus
import eu.kanade.tachiyomi.smartupdate.LibraryUpdateStateStore
import eu.kanade.tachiyomi.smartupdate.MangaUpdateFacts
import eu.kanade.tachiyomi.smartupdate.RetryPolicy
import eu.kanade.tachiyomi.smartupdate.SmartUpdatePreferences
import eu.kanade.tachiyomi.smartupdate.UpdateEligibilityEngine
import eu.kanade.tachiyomi.smartupdate.UpdateEvent
import eu.kanade.tachiyomi.smartupdate.UpdateMode
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.app.di.appGraph
import mihon.core.metro.metroGraph
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_CHARGING
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_NETWORK_NOT_METERED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_HAS_UNREAD
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_COMPLETED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_READ
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_OUTSIDE_RELEASE_PERIOD
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.SourceNotInstalledException
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalAtomicApi::class)
class LibraryUpdateWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject private lateinit var sourceManager: SourceManager

    @Inject private lateinit var libraryPreferences: LibraryPreferences

    @Inject private lateinit var downloadManager: DownloadManager

    @Inject private lateinit var getLibraryManga: GetLibraryManga

    @Inject private lateinit var getManga: GetManga

    @Inject private lateinit var fetchInterval: FetchInterval

    @Inject private lateinit var smartUpdatePreferences: SmartUpdatePreferences

    private val updateStateStore by lazy { LibraryUpdateStateStore(context) }

    private val sourceHealthStore by lazy { SourceHealthStore(context) }

    @Inject private lateinit var filterChaptersForDownload: FilterChaptersForDownload

    @Inject private lateinit var updateMangaFromRemote: UpdateMangaFromRemote

    @Inject private lateinit var notifier: LibraryUpdateNotifier

    private var mangaToUpdate: List<LibraryManga> = mutableListOf()

    override suspend fun doWork(): Result {
        graph.inject(this)

        if (tags.contains(WORK_NAME_AUTO)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                val restrictions = libraryPreferences.autoUpdateDeviceRestrictions.get()
                if ((DEVICE_ONLY_ON_WIFI in restrictions) && !context.isConnectedToWifi()) {
                    return Result.retry()
                }
            }

            // Find a running manual worker. If exists, try again later
            if (context.workManager.isRunning(WORK_NAME_MANUAL)) {
                return Result.retry()
            }
        }

        setForegroundSafely()

        libraryPreferences.lastUpdatedTimestamp.set(Clock.System.now().toEpochMilliseconds())

        val categoryId = inputData.getLong(KEY_CATEGORY, -1L)
        addMangaToQueue(categoryId)

        return withContext(Dispatchers.IO) {
            try {
                updateChapterList()
                Result.success()
            } catch (e: Exception) {
                if (e is CancellationException) {
                    // Assume success although cancelled
                    Result.success()
                } else {
                    logcat(LogPriority.ERROR, e)
                    Result.failure()
                }
            } finally {
                notifier.cancelProgressNotification()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_LIBRARY_PROGRESS,
            notifier.progressNotificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    /**
     * Adds list of manga to be updated.
     *
     * @param categoryId the ID of the category to update, or -1 if no category specified.
     */
    private suspend fun addMangaToQueue(categoryId: Long) {
        val libraryManga = getLibraryManga.await()

        val listToUpdate = if (categoryId != -1L) {
            libraryManga.filter { categoryId in it.categories }
        } else {
            val includedCategories = libraryPreferences.updateCategories.get().map { it.toLong() }
            val excludedCategories = libraryPreferences.updateCategoriesExclude.get().map { it.toLong() }

            libraryManga.filter {
                val included = includedCategories.isEmpty() || it.categories.intersect(includedCategories).isNotEmpty()
                val excluded = it.categories.intersect(excludedCategories).isNotEmpty()
                included && !excluded
            }
        }

        val restrictions = libraryPreferences.autoUpdateMangaRestrictions.get()
        val skippedUpdates = mutableListOf<Pair<Manga, String?>>()
        val timeZone = TimeZone.currentSystemDefault()
        val fetchWindowUpperBound = fetchInterval.getWindow(
            Clock.System.now().toLocalDateTime(timeZone).date,
            timeZone,
        ).endInclusive
        val mode = smartUpdatePreferences.mode()
        val nowMillis = System.currentTimeMillis()
        val fetchWindowEndMillis = fetchWindowUpperBound.toEpochMilliseconds()

        mangaToUpdate = listToUpdate
            .filter {
                when {
                    mode != UpdateMode.SMART -> {
                        val facts = MangaUpdateFacts(
                            mangaId = it.manga.id,
                            title = it.manga.title,
                            isCompleted = it.manga.status.toInt() == SManga.COMPLETED,
                            totalChapters = it.totalChapters,
                            readCount = it.readCount,
                            nextUpdateAt = it.manga.nextUpdate?.toEpochMilliseconds(),
                            onlyFetchOnce = it.manga.updateStrategy == UpdateStrategy.ONLY_FETCH_ONCE,
                        )
                        val record = updateStateStore.get(it.manga.id)
                        val eligibility = UpdateEligibilityEngine.evaluate(
                            mode = mode,
                            facts = facts,
                            record = record,
                            now = nowMillis,
                            fetchWindowEnd = fetchWindowEndMillis,
                            thoroughIncludeCompleted = smartUpdatePreferences.thoroughIncludeCompleted.get(),
                        )
                        if (!eligibility.eligible) {
                            val text = skipReasonText(eligibility.reason)
                            skippedUpdates.add(it.manga to text)
                            updateStateStore.update(it.manga.id, it.manga.title) { r ->
                                r.afterSkip(nowMillis, eligibility.reason)
                            }
                            false
                        } else {
                            true
                        }
                    }

                    it.manga.updateStrategy == UpdateStrategy.ONLY_FETCH_ONCE && it.totalChapters > 0L -> {
                        skippedUpdates.add(
                            it.manga to context.stringResource(MR.strings.skipped_reason_not_always_update),
                        )
                        updateStateStore.update(it.manga.id, it.manga.title) { r ->
                            r.afterSkip(nowMillis, UpdateEligibilityEngine.REASON_ONLY_FETCH_ONCE)
                        }
                        false
                    }

                    MANGA_NON_COMPLETED in restrictions && it.manga.status.toInt() == SManga.COMPLETED -> {
                        skippedUpdates.add(it.manga to context.stringResource(MR.strings.skipped_reason_completed))
                        updateStateStore.update(it.manga.id, it.manga.title) { r ->
                            r.afterSkip(nowMillis, UpdateEligibilityEngine.REASON_COMPLETED)
                        }
                        false
                    }

                    MANGA_HAS_UNREAD in restrictions && it.unreadCount != 0L -> {
                        skippedUpdates.add(it.manga to context.stringResource(MR.strings.skipped_reason_not_caught_up))
                        updateStateStore.update(it.manga.id, it.manga.title) { r ->
                            r.afterSkip(nowMillis, UpdateEligibilityEngine.REASON_NOT_CAUGHT_UP)
                        }
                        false
                    }

                    MANGA_NON_READ in restrictions && it.totalChapters > 0L && !it.hasStarted -> {
                        skippedUpdates.add(it.manga to context.stringResource(MR.strings.skipped_reason_not_started))
                        updateStateStore.update(it.manga.id, it.manga.title) { r ->
                            r.afterSkip(nowMillis, UpdateEligibilityEngine.REASON_NOT_STARTED)
                        }
                        false
                    }

                    MANGA_OUTSIDE_RELEASE_PERIOD in restrictions &&
                        it.manga.nextUpdate.let { next -> next != null && next > fetchWindowUpperBound } -> {
                        skippedUpdates.add(
                            it.manga to context.stringResource(MR.strings.skipped_reason_not_in_release_period),
                        )
                        updateStateStore.update(it.manga.id, it.manga.title) { r ->
                            r.afterSkip(nowMillis, UpdateEligibilityEngine.REASON_NOT_IN_RELEASE_PERIOD)
                        }
                        false
                    }

                    else -> true
                }
            }
            .sortedBy { it.manga.title }

        notifier.showQueueSizeWarningNotificationIfNeeded(mangaToUpdate)

        if (skippedUpdates.isNotEmpty()) {
            // TODO: surface skipped reasons to user?
            logcat {
                skippedUpdates
                    .groupBy { it.second }
                    .map { (reason, entries) -> "$reason: [${entries.map { it.first.title }.sorted().joinToString()}]" }
                    .joinToString()
            }
        }
    }

    /**
     * Method that updates manga in [mangaToUpdate]. It's called in a background thread, so it's safe
     * to do heavy operations or network calls here.
     * For each manga it calls [updateManga] and updates the notification showing the current
     * progress.
     *
     * @return an observable delivering the progress of each update.
     */
    private suspend fun updateChapterList() {
        val semaphore = Semaphore(5)
        val progressCount = AtomicInt(0)
        val currentlyUpdatingManga = CopyOnWriteArrayList<Manga>()
        val newUpdates = CopyOnWriteArrayList<Pair<Manga, Array<Chapter>>>()
        val failedUpdates = CopyOnWriteArrayList<Pair<Manga, String?>>()
        val hasDownloads = AtomicBoolean(false)
        val timeZone = TimeZone.currentSystemDefault()
        val fetchWindow = fetchInterval.getWindow(Clock.System.now().toLocalDateTime(timeZone).date, timeZone)

        coroutineScope {
            mangaToUpdate.groupBy { it.manga.source }.values
                .sortedByDescending { it.size }
                .map { mangaInSource ->
                    async {
                        semaphore.withPermit {
                            mangaInSource.forEach { libraryManga ->
                                val manga = libraryManga.manga
                                ensureActive()

                                // Don't continue to update if manga is not in library
                                if (getManga.await(manga.id)?.favorite != true) {
                                    return@forEach
                                }

                                withUpdateNotification(
                                    currentlyUpdatingManga,
                                    progressCount,
                                    manga,
                                ) {
                                    try {
                                        val newChapters = updateManga(manga, fetchWindow)
                                            .sortedByDescending { it.sourceOrder }

                                        val successAt = System.currentTimeMillis()
                                        updateStateStore.update(manga.id, manga.title) {
                                            it.afterSuccess(successAt, newChapters.size)
                                        }
                                        updateStateStore.recordEvent(
                                            UpdateEvent(
                                                mangaId = manga.id,
                                                title = manga.title,
                                                at = successAt,
                                                success = true,
                                                newChapters = newChapters.size,
                                            ),
                                        )

                                        if (newChapters.isNotEmpty()) {
                                            val chaptersToDownload = filterChaptersForDownload.await(manga, newChapters)

                                            if (chaptersToDownload.isNotEmpty()) {
                                                downloadChapters(manga, chaptersToDownload)
                                                hasDownloads.store(true)
                                            }

                                            // Sources update concurrently, and getAndSet is a separate read and write
                                            synchronized(libraryPreferences.newUpdatesCount) {
                                                libraryPreferences.newUpdatesCount.getAndSet { it + newChapters.size }
                                            }

                                            // Convert to the manga that contains new chapters
                                            newUpdates.add(manga to newChapters.toTypedArray())
                                        }
                                    } catch (e: Throwable) {
                                        val errorMessage = when (e) {
                                            is NoChaptersException -> context.stringResource(
                                                MR.strings.no_chapters_error,
                                            )
                                            // failedUpdates will already have the source, don't need to copy it into the message
                                            is SourceNotInstalledException -> context.stringResource(
                                                MR.strings.loader_not_implemented_error,
                                            )
                                            else -> e.message
                                        }
                                        failedUpdates.add(manga to errorMessage)

                                        val failureAt = System.currentTimeMillis()
                                        val previous = updateStateStore.get(manga.id)
                                        val classification = RetryPolicy.classify(e)
                                        val permanent = RetryPolicy.isPermanent(classification.status)
                                        val nextEligible = if (permanent) {
                                            Long.MAX_VALUE
                                        } else {
                                            failureAt + RetryPolicy.backoffMillis(
                                                previous.consecutiveFailures,
                                                jitterSeed = failureAt,
                                            )
                                        }
                                        updateStateStore.update(manga.id, manga.title) {
                                            it.afterFailure(failureAt, errorMessage ?: classification.reason, nextEligible)
                                        }
                                        updateStateStore.recordEvent(
                                            UpdateEvent(
                                                mangaId = manga.id,
                                                title = manga.title,
                                                at = failureAt,
                                                success = false,
                                                detail = errorMessage ?: classification.reason,
                                            ),
                                        )

                                        if (permanent || previous.consecutiveFailures + 1 >= 3) {
                                            sourceHealthStore.recordFailure(
                                                key = HealthRecord.sourceKey(manga.source),
                                                now = failureAt,
                                                classification = classification,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .awaitAll()
        }

        notifier.cancelProgressNotification()

        if (newUpdates.isNotEmpty()) {
            val hour = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour
            val silent = smartUpdatePreferences.isQuietHours(hour)
            val fresh = newUpdates.map { (manga, chapters) ->
                val freshUrls = updateStateStore.markChaptersNotified(manga.id, chapters.map { it.url }).toSet()
                manga to chapters.filter { it.url in freshUrls }.toTypedArray()
            }.filter { it.second.isNotEmpty() }
            if (fresh.isNotEmpty() && smartUpdatePreferences.notifyNewChapters.get()) {
                notifier.showUpdateNotifications(fresh, silent = silent)
            }
            if (hasDownloads.load()) {
                downloadManager.startDownloads()
            }
        }

        if (failedUpdates.isNotEmpty()) {
            val errorFile = writeErrorFile(failedUpdates)
            notifier.showUpdateErrorNotification(
                failedUpdates.size,
                errorFile.getUriCompat(context),
            )
        }
    }

    private suspend fun downloadChapters(manga: Manga, chapters: List<Chapter>) {
        // We don't want to start downloading while the library is updating, because websites
        // may don't like it and they could ban the user.
        downloadManager.downloadChapters(manga, chapters, false)
    }

    private fun skipReasonText(reason: String): String = when (reason) {
        UpdateEligibilityEngine.REASON_ONLY_FETCH_ONCE ->
            context.stringResource(MR.strings.skipped_reason_not_always_update)
        UpdateEligibilityEngine.REASON_COMPLETED ->
            context.stringResource(MR.strings.skipped_reason_completed)
        UpdateEligibilityEngine.REASON_NOT_CAUGHT_UP ->
            context.stringResource(MR.strings.skipped_reason_not_caught_up)
        UpdateEligibilityEngine.REASON_NOT_STARTED ->
            context.stringResource(MR.strings.skipped_reason_not_started)
        UpdateEligibilityEngine.REASON_NOT_IN_RELEASE_PERIOD ->
            context.stringResource(MR.strings.skipped_reason_not_in_release_period)
        UpdateEligibilityEngine.REASON_RETRY_BACKOFF ->
            context.stringResource(MR.strings.yomi_skip_retry_backoff)
        UpdateEligibilityEngine.REASON_PERMANENT_FAILURE ->
            context.stringResource(MR.strings.yomi_skip_permanent_failure)
        UpdateEligibilityEngine.REASON_RECENTLY_CHECKED ->
            context.stringResource(MR.strings.yomi_skip_recently_checked)
        else -> reason
    }

    /**
     * Updates the chapters for the given manga and adds them to the database.
     *
     * @param manga the manga to update.
     * @return a pair of the inserted and removed chapters.
     */
    private suspend fun updateManga(manga: Manga, fetchWindow: ClosedRange<Instant>): List<Chapter> {
        val source = sourceManager.getOrStub(manga.source)

        val update = updateMangaFromRemote(
            source = source,
            manga = manga,
            fetchDetails = libraryPreferences.autoUpdateMetadata.get(),
            fetchChapters = true,
            fetchWindow = fetchWindow,
        )
            .getOrThrow()

        return if (update.manga.favorite) update.newChapters else emptyList()
    }

    private suspend fun withUpdateNotification(
        updatingManga: CopyOnWriteArrayList<Manga>,
        completed: AtomicInt,
        manga: Manga,
        block: suspend () -> Unit,
    ) = coroutineScope {
        ensureActive()

        updatingManga.add(manga)
        notifier.showProgressNotification(
            updatingManga,
            completed.load(),
            mangaToUpdate.size,
        )

        block()

        ensureActive()

        updatingManga.remove(manga)
        completed.incrementAndFetch()
        notifier.showProgressNotification(
            updatingManga,
            completed.load(),
            mangaToUpdate.size,
        )
    }

    /**
     * Writes basic file of update errors to cache dir.
     */
    private suspend fun writeErrorFile(errors: List<Pair<Manga, String?>>): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("mihon_update_errors.txt")
                file.bufferedWriter().use { out ->
                    out.write(context.stringResource(MR.strings.library_errors_help, ERROR_LOG_HELP_URL) + "\n\n")
                    // Error file format:
                    // ! Error
                    //   # Source
                    //     - Manga
                    errors.groupBy({ it.second }, { it.first }).forEach { (error, mangas) ->
                        out.write("\n! ${error}\n")
                        mangas.groupBy { it.source }.forEach { (srcId, mangas) ->
                            val source = sourceManager.getOrStub(srcId)
                            out.write("  # $source\n")
                            mangas.forEach { manga ->
                                out.write("    - ${manga.title}\n")
                            }
                        }
                    }
                }
                return file
            }
        } catch (_: Exception) {}
        return File("")
    }

    companion object {
        private const val TAG = "LibraryUpdate"
        private const val WORK_NAME_AUTO = "LibraryUpdate-auto"
        private const val WORK_NAME_MANUAL = "LibraryUpdate-manual"

        private const val ERROR_LOG_HELP_URL = "https://mihon.app/docs/guides/troubleshooting/"

        private const val MANGA_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 60

        /**
         * Key for category to update.
         */
        private const val KEY_CATEGORY = "category"

        fun setupTask(
            context: Context,
            prefInterval: Int? = null,
        ) {
            val preferences = context.appGraph.libraryPreferences
            val interval = prefInterval ?: preferences.autoUpdateInterval.get()
            if (interval > 0) {
                val restrictions = preferences.autoUpdateDeviceRestrictions.get()
                val networkType = if (DEVICE_NETWORK_NOT_METERED in restrictions) {
                    NetworkType.UNMETERED
                } else {
                    NetworkType.CONNECTED
                }
                val networkRequest = NetworkRequest.Builder().apply {
                    removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    if (DEVICE_ONLY_ON_WIFI in restrictions) {
                        addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    }
                    if (DEVICE_NETWORK_NOT_METERED in restrictions) {
                        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    }
                }
                    .build()
                val constraints = Constraints.Builder()
                    // 'networkRequest' only applies to Android 9+, otherwise 'networkType' is used
                    .setRequiredNetworkRequest(networkRequest, networkType)
                    .setRequiresCharging(DEVICE_CHARGING in restrictions)
                    .setRequiresBatteryNotLow(true)
                    .build()

                val request = PeriodicWorkRequestBuilder<LibraryUpdateWorker>(
                    interval.toLong(),
                    TimeUnit.HOURS,
                    10,
                    TimeUnit.MINUTES,
                )
                    .addTag(TAG)
                    .addTag(WORK_NAME_AUTO)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                    .build()

                context.workManager.enqueueUniquePeriodicWork(
                    WORK_NAME_AUTO,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            } else {
                context.workManager.cancelUniqueWork(WORK_NAME_AUTO)
            }
        }

        suspend fun startNow(
            workManager: WorkManager,
            category: Category? = null,
        ): Boolean {
            if (workManager.isRunning(TAG)) {
                // Already running either as a scheduled or manual job
                return false
            }

            val inputData = workDataOf(
                KEY_CATEGORY to category?.id,
            )
            val request = OneTimeWorkRequestBuilder<LibraryUpdateWorker>()
                .addTag(TAG)
                .addTag(WORK_NAME_MANUAL)
                .setInputData(inputData)
                .build()
            workManager.enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)

            return true
        }

        suspend fun stop(context: Context) {
            val workManager = context.workManager
            val workQuery = WorkQuery.Builder.fromTags(listOf(TAG))
                .addStates(listOf(WorkInfo.State.RUNNING))
                .build()
            workManager.getWorkInfosFlow(workQuery).first()
                // Should only return one work but just in case
                .forEach {
                    workManager.cancelWorkById(it.id)

                    // Re-enqueue cancelled scheduled work
                    if (it.tags.contains(WORK_NAME_AUTO)) {
                        setupTask(context)
                    }
                }
        }
    }
}
