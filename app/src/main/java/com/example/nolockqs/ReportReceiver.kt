package com.example.nolockqs

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateFormat
import java.util.Date

/**
 * Receives the hooks' reports (see [Reports]): records each blocked attempt, posts an alert for
 * it when alerts are on, and keeps each process's last hook status for the diagnostics report.
 * It runs before the first unlock too, so nothing it touches may need the unlocked user storage.
 */
class ReportReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "blocked_attempts"
        private const val NOTIFICATION_ID = 1

        /** Creates the alerts channel: silent, so a thief hears nothing, and never shown on the lock screen. */
        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.alerts_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.alerts_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /** Takes the alert down, as the history it points to is being looked at. */
        fun cancelAlert(context: Context) =
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)

        /** The label of a [Reports] KIND_ value, such as "Power menu". */
        fun kindLabel(context: Context, kind: String): String = context.getString(
            when (kind) {
                Reports.KIND_POWER_MENU -> R.string.feature_power_title
                Reports.KIND_WRONG_PIN -> R.string.kind_wrong_pin
                Reports.KIND_WRONG_PATTERN -> R.string.kind_wrong_pattern
                Reports.KIND_WRONG_PASSWORD -> R.string.kind_wrong_password
                Reports.KIND_FAILED_FINGERPRINT -> R.string.kind_failed_fingerprint
                Reports.KIND_FAILED_FACE -> R.string.kind_failed_face
                else -> R.string.feature_qs_title
            },
        )

        /** The history icon of a [Reports] KIND_ value. */
        fun kindIcon(kind: String): Int = when (kind) {
            Reports.KIND_POWER_MENU -> R.drawable.ic_power_menu
            Reports.KIND_WRONG_PIN -> R.drawable.ic_kind_pin
            Reports.KIND_WRONG_PATTERN -> R.drawable.ic_kind_pattern
            Reports.KIND_WRONG_PASSWORD -> R.drawable.ic_kind_password
            Reports.KIND_FAILED_FINGERPRINT -> R.drawable.ic_kind_fingerprint
            Reports.KIND_FAILED_FACE -> R.drawable.ic_kind_face
            in Reports.FAILED_UNLOCK_KINDS -> R.drawable.ic_lock_failed
            else -> R.drawable.ic_quick_settings
        }

        /**
         * The bubble and icon colors of a [Reports] KIND_ value: amber for a face or fingerprint,
         * rose for a wrong PIN, pattern or password, violet for a blocked Quick Settings or power menu.
         */
        fun kindColors(kind: String): Pair<Int, Int> = when (kind) {
            Reports.KIND_FAILED_FINGERPRINT, Reports.KIND_FAILED_FACE -> R.color.kind_bio_bg to R.color.kind_bio_fg
            in Reports.FAILED_UNLOCK_KINDS -> R.color.kind_unlock_bg to R.color.kind_unlock_fg
            else -> R.color.kind_block_bg to R.color.kind_block_fg
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val log = AttemptLog(context)
        val time = intent.getLongExtra(Reports.EXTRA_TIME, System.currentTimeMillis())
        when (intent.action) {
            Reports.ACTION_BLOCKED -> {
                val kind = intent.getStringExtra(Reports.EXTRA_KIND) ?: return
                val unseen = log.add(AttemptLog.Attempt(time, kind))
                if (log.alertsEnabled) postAlert(context, kind, time, unseen)
            }
            Reports.ACTION_STATUS -> {
                val process = intent.getStringExtra(Reports.EXTRA_PROCESS) ?: return
                val layers = intent.getStringArrayListExtra(Reports.EXTRA_LAYERS).orEmpty()
                log.saveStatus(process, AttemptLog.Status(time, layers))
            }
        }
    }

    private fun postAlert(context: Context, kind: String, time: Long, unseen: Int) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        createChannel(context)
        val at = DateFormat.getTimeFormat(context).format(Date(time))
        val failedUnlock = kind in Reports.FAILED_UNLOCK_KINDS
        val title = if (failedUnlock) {
            context.getString(R.string.alert_title_unlock, kindLabel(context, kind))
        } else {
            context.getString(R.string.alert_title, kindLabel(context, kind))
        }
        val text = when {
            unseen > 1 -> context.resources.getQuantityString(R.plurals.alert_text_many, unseen, unseen, at)
            failedUnlock -> context.getString(R.string.alert_text_unlock, at)
            else -> context.getString(R.string.alert_text_one, at)
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            // The icon of what was tried, as in the history: the title names the same attempt.
            .setSmallIcon(kindIcon(kind))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setWhen(time)
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }
}
