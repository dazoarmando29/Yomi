package eu.kanade.tachiyomi.smartsource

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.smartsource.health.SourceHealthChecker
import eu.kanade.tachiyomi.smartsource.health.SourceHealthStore
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.util.system.workManager
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import java.util.concurrent.TimeUnit

class SourceHealthWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject
    private lateinit var checker: SourceHealthChecker

    @Inject
    private lateinit var notifier: SmartSourceNotifier

    override suspend fun doWork(): Result {
        graph.inject(this)

        val store = SourceHealthStore(context)
        return try {
            val report = checker.runChecks(store)
            notifier.showHealthReport(report)
            Result.success()
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME_PERIODIC = "SourceHealth-periodic"
        private const val WORK_NAME_MANUAL = "SourceHealth-manual"
        const val TAG = "SourceHealth"

        fun startNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SourceHealthWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .addTag(TAG)
                .build()
            context.workManager.enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Schedules periodic checks. [intervalDays] 0 disables automatic checks.
         */
        fun setupTask(context: Context, intervalDays: Int) {
            if (intervalDays <= 0) {
                context.workManager.cancelUniqueWork(WORK_NAME_PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<SourceHealthWorker>(intervalDays.toLong(), TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.MINUTES,
                )
                .addTag(TAG)
                .build()
            context.workManager.enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
