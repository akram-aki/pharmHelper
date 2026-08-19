package fr.fbing.boxdetector

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-arms the 10:00 check whenever the schedule would otherwise be lost.
 *
 * Alarms do not survive a reboot, an app update wipes them too — and this app
 * ships updates through App Distribution regularly — and a clock or timezone
 * change moves where "10:00" actually falls. Losing the alarm is silent, so each
 * of these re-arms rather than assuming.
 *
 * Deliberately only re-arms. Starting the check service from BOOT_COMPLETED is
 * refused outright on Android 15 for a `mediaPlayback` service.
 */
class VirementBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                Log.i(TAG, "re-arming after ${intent.action}")
                VirementAlarmScheduler.arm(context)
            }
            // API 31+. Revoking the grant cancels every exact alarm we hold, so
            // the re-grant has to put ours back.
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                Log.i(TAG, "exact-alarm permission changed — re-arming")
                VirementAlarmScheduler.arm(context)
            }
            else -> Log.w(TAG, "ignoring unexpected action ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "VirementBootRcv"
    }
}
