package fr.fbing.boxdetector

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs the 10:00 virement check and, if the CNAS has paid something new, rings
 * until the pharmacist dismisses it.
 *
 * A foreground service rather than a worker because it makes noise: audio
 * started from the background is killed within seconds otherwise. The exact
 * alarm that woke [VirementAlarmReceiver] is what earns us the right to start
 * it from the background at all.
 *
 * The ringtone plays on the alarm stream, so it is heard through silent and
 * vibrate — the same reason a clock alarm is. It stops on dismissal, or by
 * itself after [RING_TIMEOUT_MS] so a phone left on a counter doesn't ring all
 * afternoon; the notification and the home banner stay behind either way.
 */
class VirementAlarmService : Service() {

    private lateinit var io: ExecutorService
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private val autoStop = Runnable {
        Log.i(TAG, "ring timeout — stopping the sound, leaving the notification")
        stopRinging()
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        io = Executors.newSingleThreadExecutor()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            dismiss()
            return START_NOT_STICKY
        }

        // Must happen within seconds of startForegroundService, before any work.
        promote(VirementNotifications.ID_CHECK, VirementNotifications.checking(this))

        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
        io.execute { check(force) }
        return START_NOT_STICKY
    }

    private fun check(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && VirementAlertStore.hasCheckedToday(this, now)) {
            Log.i(TAG, "already checked today — standing down")
            finish()
            return
        }

        val client = BordereauClient(this)
        if (!client.isConfigured()) {
            Log.i(TAG, "not configured — skipping virement check")
            finish()
            return
        }

        val sweep = try {
            VirementLog.record(this, client.fetchAll(), now)
        } catch (e: IOException) {
            Log.w(TAG, "virement check failed: ${e.message}")
            finish()
            return
        }

        // Only now is the day spent: a morning with no network stays open to
        // retry rather than being silently burned.
        VirementAlertStore.markCheckedToday(this, now)

        if (sweep.fresh.isEmpty()) {
            Log.d(TAG, "no new virement")
            finish()
            return
        }

        val paid = sweep.fresh.map(PaidBordereau::of)
        Log.i(TAG, "${paid.size} new virement(s) — raising the alarm")
        VirementAlertStore.addPending(this, paid)
        main.post { raise(paid) }
    }

    /** Swaps the quiet check notification for the alert, then starts the sound. */
    private fun raise(paid: List<PaidBordereau>) {
        val all = VirementAlertStore.pending(this).ifEmpty { paid }
        promote(VirementNotifications.ID_ALERT, VirementNotifications.alert(this, all))
        getSystemService(NotificationManager::class.java)
            ?.cancel(VirementNotifications.ID_CHECK)
        startRinging()
        main.postDelayed(autoStop, RING_TIMEOUT_MS)
    }

    private fun startRinging() {
        stopRinging()
        val uri = VirementAlertStore.ringtone(this) ?: defaultAlarm()
        if (uri == null) {
            Log.w(TAG, "no ringtone available — alerting silently")
            return
        }
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@VirementAlarmService, uri)
                setWakeMode(this@VirementAlarmService, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            // A chosen ringtone can vanish — a deleted file, an unmounted card.
            // Fall back once to the system alarm rather than failing silently.
            Log.w(TAG, "could not play $uri (${e.javaClass.simpleName})")
            playDefaultFallback(uri)
        }
    }

    private fun playDefaultFallback(failed: Uri): MediaPlayer? {
        val fallback = defaultAlarm() ?: return null
        if (fallback == failed) return null
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@VirementAlarmService, fallback)
                setWakeMode(this@VirementAlarmService, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fallback ringtone failed too (${e.javaClass.simpleName})")
            null
        }
    }

    private fun stopRinging() {
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        player = null
    }

    /** Dismissal from the alert screen or the notification action. */
    private fun dismiss() {
        main.removeCallbacks(autoStop)
        stopRinging()
        io.execute { VirementAlertStore.clearPending(this) }
        getSystemService(NotificationManager::class.java)
            ?.cancel(VirementNotifications.ID_ALERT)
        stopSelf()
    }

    /** Nothing to report: drop the foreground notification and go away. */
    private fun finish() {
        main.post {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun promote(id: Int, notification: android.app.Notification) {
        ServiceCompat.startForeground(
            this, id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    private fun defaultAlarm(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(autoStop)
        stopRinging()
        io.shutdown()
    }

    companion object {
        private const val TAG = "VirementAlarmSvc"

        /** Bypasses the once-a-day guard, for the test button in Paramètres. */
        const val EXTRA_FORCE = "force"
        const val ACTION_DISMISS = "fr.fbing.boxdetector.DISMISS_VIREMENT"

        /** Long enough to be heard, short enough not to be a nuisance. */
        private const val RING_TIMEOUT_MS = 5 * 60 * 1000L

        /** Silences the alarm from anywhere in the app. */
        fun dismiss(context: android.content.Context) {
            val intent = Intent(context, VirementAlarmService::class.java)
                .setAction(ACTION_DISMISS)
            runCatching { context.startService(intent) }
        }
    }
}
