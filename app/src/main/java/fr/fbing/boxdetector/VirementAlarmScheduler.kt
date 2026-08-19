package fr.fbing.boxdetector

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.util.Calendar

/**
 * Arms the once-a-day 10:00 check.
 *
 * [AlarmManager] rather than WorkManager because this alarm has to make a noise
 * at a specific time: WorkManager batches its work and, on MIUI especially, a
 * periodic job can drift by hours or wait for the next unlock — fine for a
 * silent poll, useless for something meant to get attention at 10:00.
 *
 * Alarms do not survive a reboot, so [VirementAlarmReceiver] re-arms on
 * BOOT_COMPLETED, and each firing re-arms for the following day.
 */
object VirementAlarmScheduler {

    /** The hour the pharmacist expects the check. */
    const val HOUR_OF_DAY = 10

    /**
     * Schedules the next 10:00. Safe to call on every app start — it replaces
     * the existing alarm rather than stacking.
     */
    fun arm(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val at = nextOccurrence(System.currentTimeMillis())
        val pending = firePendingIntent(context)

        // canScheduleExactAlarms is API 31+. Below that, and whenever the grant
        // is in place, we get the exact time; otherwise we degrade to an inexact
        // alarm that still fires in Doze, just not to the minute.
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        try {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                Log.w(TAG, "no exact-alarm grant — falling back to an inexact alarm")
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (e: SecurityException) {
            // The grant can be revoked between the check and the call.
            Log.w(TAG, "exact alarm refused (${e.javaClass.simpleName}) — using inexact")
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
        Log.i(TAG, "virement check armed for ${java.util.Date(at)} (exact=$exact)")
    }

    /** True when the system will honour an exact alarm for us. */
    fun canBeExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
    }

    /**
     * The settings screen where the user grants exact alarms, or null when the
     * platform has no such screen. Android 12+ only.
     */
    fun exactAlarmSettings(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
    }

    /** The next 10:00 strictly in the future. */
    private fun nextOccurrence(now: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, HOUR_OF_DAY)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= now) calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis
    }

    private fun firePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, VirementAlarmReceiver::class.java)
            .setAction(VirementAlarmReceiver.ACTION_CHECK)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private const val TAG = "VirementAlarm"

    /** Distinct from any other PendingIntent in the app so they can't collide. */
    private const val REQUEST_CODE = 4310
}
