package eu.kanade.tachiyomi.smartsource

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.smartsource.health.HealthCheckReport
import eu.kanade.tachiyomi.util.system.notificationBuilder
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

@Inject
class SmartSourceNotifier(private val context: Context) {

    fun showHealthReport(report: HealthCheckReport) {
        if (report.problems <= 0) return

        val summary = report.problemSummaries.joinToString("\n")
            .ifBlank {
                context.stringResource(MR.strings.yomi_smart_source_notification_text, report.problems, report.updates)
            }

        val notification = context.notificationBuilder(Notifications.CHANNEL_SMART_SOURCE) {
            setContentTitle(context.stringResource(MR.strings.yomi_smart_source_notification_title))
            setContentText(
                context.stringResource(MR.strings.yomi_smart_source_notification_text, report.problems, report.updates),
            )
            setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            priority = NotificationCompat.PRIORITY_DEFAULT
        }.build()

        NotificationManagerCompat.from(context).notify(Notifications.ID_SMART_SOURCE_REPORT, notification)
    }
}
