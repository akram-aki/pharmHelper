package fr.fbing.boxdetector

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * The phone notification raised when the pharmacy PC reports that CNAS rejected
 * the daily code.
 *
 * Always paired with the home-screen banner, never relied on alone: on MIUI a
 * background job can be delayed for hours or killed outright, and on Android 13+
 * the user can refuse notifications entirely. The banner is the surface that
 * always works; this is the one that reaches the pharmacist sooner.
 */
object CnasNotifications {

    /**
     * Shows [alert], replacing any previous CNAS notification — a fixed id, so
     * repeated failures update one entry instead of stacking up.
     */
    fun show(context: Context, alert: CnasAlert) {
        if (!canPost(context)) {
            Log.i(TAG, "notifications not permitted — the home banner still carries the alert")
            return
        }
        createChannel(context)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Title and explanation come from the kind, so cnas_error and
        // cnas_script_error don't both masquerade as a rejected code.
        val body = context.getString(
            R.string.cnas_alert_body,
            context.getString(alert.kindOf.descRes),
            alert.message
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alert_notification)
            .setContentTitle(context.getString(alert.kindOf.titleRes))
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setColor(ContextCompat.getColor(context, R.color.alert_red_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        try {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check above and here.
            Log.w(TAG, "notify refused: ${e.message}")
        }
    }

    /** minSdk is 26, so the channel is always required — no version guard needed. */
    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.cnas_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.cnas_channel_desc)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /** POST_NOTIFICATIONS is only required from Android 13; below that it is implicit. */
    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private const val TAG = "CnasNotifications"
    private const val CHANNEL_ID = "cnas_alerts"
    private const val NOTIFICATION_ID = 4201
}
