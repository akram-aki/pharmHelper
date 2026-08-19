package fr.fbing.boxdetector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * The two notifications the virement alarm needs.
 *
 * Two channels, because a channel's importance and sound are frozen once it is
 * created and these two want opposite things: the check that runs at 10:00 has
 * to show *something* while the foreground service is alive but should be
 * silent, while the alert itself must be loud and carry a full-screen intent.
 * Neither can reuse the CNAS channel for the same reason.
 *
 * The alert deliberately has no sound of its own — [VirementAlarmService] plays
 * the chosen ringtone on the alarm stream so it loops and survives silent mode,
 * which a notification sound cannot do.
 */
object VirementNotifications {

    /** The quiet "working on it" notification the foreground service starts with. */
    fun checking(context: Context): Notification {
        createChannel(
            context, CHANNEL_CHECK, R.string.virement_channel_check_name,
            R.string.virement_channel_check_desc, NotificationManager.IMPORTANCE_LOW
        )
        return NotificationCompat.Builder(context, CHANNEL_CHECK)
            .setSmallIcon(R.drawable.ic_bordereau)
            .setContentTitle(context.getString(R.string.virement_checking))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * The alert.
     *
     * [setFullScreenIntent] fires the moment this is posted, not when the phone
     * is picked up — there is no API for the latter. If the screen is off or
     * locked at 10:00, [VirementAlarmActivity] launches straight away and stays
     * up, so it is what greets the pharmacist whenever they do pick the phone
     * up. If the phone is already in use, or the grant is missing on Android 14,
     * this degrades to a heads-up notification and `setContentIntent` carries
     * the same destination.
     */
    fun alert(context: Context, paid: List<PaidBordereau>): Notification {
        createChannel(
            context, CHANNEL_ALERT, R.string.virement_channel_alert_name,
            R.string.virement_channel_alert_desc, NotificationManager.IMPORTANCE_HIGH
        )

        val open = PendingIntent.getActivity(
            context, REQUEST_OPEN,
            Intent(context, VirementAlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismiss = PendingIntent.getService(
            context, REQUEST_DISMISS,
            Intent(context, VirementAlarmService::class.java)
                .setAction(VirementAlarmService.ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.resources.getQuantityString(
            R.plurals.virement_alert_title, paid.size, paid.size
        )
        val body = summarise(context, paid)

        return NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_bordereau)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(ContextCompat.getColor(context, R.color.pharma_green))
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, context.getString(R.string.virement_dismiss), dismiss)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** "071826 · 226 589,71 DA", one per line. */
    fun summarise(context: Context, paid: List<PaidBordereau>): String =
        paid.joinToString("\n") {
            context.getString(R.string.virement_alert_line, it.numBord, it.amount)
        }

    private fun createChannel(
        context: Context,
        id: String,
        nameRes: Int,
        descRes: Int,
        importance: Int
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(id, context.getString(nameRes), importance).apply {
            description = context.getString(descRes)
            if (importance == NotificationManager.IMPORTANCE_HIGH) {
                // The service owns the sound; silencing the channel stops the two
                // from overlapping into a mess.
                setSound(null, null)
                enableVibration(true)
            }
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Whether Android will honour our full-screen intent. Android 14 stopped
     * granting this to apps that aren't phones or clocks; below that it is a
     * normal install-time permission.
     */
    fun canUseFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canUseFullScreenIntent()
    }

    const val CHANNEL_CHECK = "virement_check"
    const val CHANNEL_ALERT = "virement_alert"

    /** Kept clear of CnasNotifications' 4201 and of each other. */
    const val ID_CHECK = 4301
    const val ID_ALERT = 4302

    private const val REQUEST_OPEN = 4311
    private const val REQUEST_DISMISS = 4312
}
