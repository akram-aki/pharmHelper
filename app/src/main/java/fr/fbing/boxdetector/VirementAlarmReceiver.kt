package fr.fbing.boxdetector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Wakes the app for the 10:00 virement check.
 *
 * The receiver deliberately does no network work of its own. A broadcast runs on
 * the main thread — a fetch here would throw `NetworkOnMainThreadException` — and
 * its process can be killed the moment it returns. The fetch belongs in
 * [VirementAlarmService], which the system lets us start from the background
 * precisely because an exact alarm we scheduled woke us.
 *
 * That exemption is conditional on actually holding the exact-alarm permission:
 * lose it and both halves fail at once, the alarm degrading to inexact *and* the
 * service start being refused. Hence the guard in [VirementAlarmScheduler].
 *
 * Reached only by an explicit PendingIntent, so it stays unexported. Re-arming
 * after reboots and updates is [VirementBootReceiver]'s job.
 */
class VirementAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) {
            Log.w(TAG, "ignoring unexpected action ${intent.action}")
            return
        }
        Log.i(TAG, "10:00 alarm fired")
        // Re-arm first: if starting the service fails, tomorrow is still booked.
        VirementAlarmScheduler.arm(context)
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VirementAlarmService::class.java)
                    .putExtra(VirementAlarmService.EXTRA_FORCE, false)
            )
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException when the exact-alarm
            // exemption isn't ours. Nothing to do but leave tomorrow armed.
            Log.w(TAG, "could not start the check (${e.javaClass.simpleName})")
        }
    }

    companion object {
        private const val TAG = "VirementAlarmRcv"
        const val ACTION_CHECK = "fr.fbing.boxdetector.CHECK_VIREMENTS"
    }
}
