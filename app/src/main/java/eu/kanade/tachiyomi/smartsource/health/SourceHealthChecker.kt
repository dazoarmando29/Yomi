package eu.kanade.tachiyomi.smartsource.health

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.smartsource.model.HealthRecord
import eu.kanade.tachiyomi.smartsource.model.HealthStatus
import kotlinx.coroutines.flow.first
import mihon.domain.extension.interactor.GetExtensionStores
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class HealthCheckReport(
    val checked: Int,
    val problems: Int,
    val updates: Int,
    val problemSummaries: List<String>,
)

/**
 * Observes extension, repository, and source health and records it in [SourceHealthStore].
 *
 * Checks are intentionally conservative: repositories are probed with a single light
 * request, sources are not hammered, and a single failure never marks anything broken.
 */
@Inject
class SourceHealthChecker(
    private val extensionManager: ExtensionManager,
    private val getExtensionStores: GetExtensionStores,
    private val networkHelper: NetworkHelper,
) {

    suspend fun runChecks(store: SourceHealthStore): HealthCheckReport {
        val now = System.currentTimeMillis()
        var checked = 0
        var problems = 0
        var updates = 0
        val problemSummaries = mutableListOf<String>()

        getExtensionStores.get().forEach { repo ->
            val key = HealthRecord.repoKey(repo.indexUrl)
            checked++
            try {
                probeUrl(repo.indexUrl)
                store.recordSuccess(key, now)
            } catch (e: IOException) {
                val classification = ErrorClassifier.classifyException(e, ErrorClassifier.Target.REPOSITORY)
                store.recordFailure(key, now, classification)
                val record = store.get(key)
                if (isProblem(record)) {
                    problems++
                    problemSummaries += "Repository ${repo.name}: ${classification.reason}"
                }
            }
        }

        runCatching { extensionManager.checkForUpdates() }

        val loaded = extensionManager.loadedExtensionsFlow.first()
        val notLoaded = extensionManager.notLoadedExtensionsFlow.first()

        loaded.forEach { extension ->
            val key = HealthRecord.extensionKey(extension.pkgName)
            checked++
            store.recordUpdateAvailable(key, extension.hasUpdate)
            store.recordSuccess(key, now)
            if (extension.hasUpdate) updates++
        }

        notLoaded.forEach { extension ->
            val key = HealthRecord.extensionKey(extension.pkgName)
            checked++
            when (val reason = extension.reason) {
                is Extension.NotLoaded.Reason.Untrusted -> store.recordUnknown(key, now, "Not trusted yet")
                Extension.NotLoaded.Reason.Filtered -> store.recordUnknown(key, now, "Hidden by content warning filter")
                Extension.NotLoaded.Reason.Unsigned -> store.recordIncompatible(key, now, "No signature to verify")
                Extension.NotLoaded.Reason.UnsupportedLibVersion -> {
                    store.recordIncompatible(key, now, "Unsupported extension API version")
                    problems++
                    problemSummaries += "${extension.name}: unsupported extension API version"
                }
                Extension.NotLoaded.Reason.Malformed -> {
                    store.recordFailure(
                        key,
                        now,
                        Classification(HealthStatus.BROKEN, retryable = false, reason = "Malformed extension package"),
                    )
                    problems++
                    problemSummaries += "${extension.name}: malformed extension package"
                }
                is Extension.NotLoaded.Reason.Failed -> {
                    store.recordFailure(
                        key,
                        now,
                        Classification(HealthStatus.TEMPORARILY_UNAVAILABLE, retryable = true, reason = reason.message),
                    )
                    if (isProblem(store.get(key))) {
                        problems++
                        problemSummaries += "${extension.name}: failed to load (${reason.message})"
                    }
                }
            }
        }

        return HealthCheckReport(
            checked = checked,
            problems = problems,
            updates = updates,
            problemSummaries = problemSummaries,
        )
    }

    fun isProblem(record: HealthRecord): Boolean {
        return record.status in setOf(
            HealthStatus.BROKEN,
            HealthStatus.INCOMPATIBLE,
            HealthStatus.REPOSITORY_MISSING,
            HealthStatus.AUTHENTICATION_REQUIRED,
            HealthStatus.OUTDATED,
        ) || (record.status == HealthStatus.TEMPORARILY_UNAVAILABLE && record.consecutiveFailures >= 2)
    }

    private fun probeUrl(url: String) {
        val request = Request.Builder().url(url).head().build()
        networkHelper.client.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful && response.code != 405) {
                    throw IOException("HTTP ${response.code}")
                }
            }
    }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 10L
    }
}
