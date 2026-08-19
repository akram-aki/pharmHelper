package fr.fbing.boxdetector

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

/**
 * "Paramètres": choose the sound the 10:00 virement alarm plays, and see
 * honestly whether the phone will actually let that alarm work.
 *
 * The permission rows matter more than they look. An exact alarm and a
 * full-screen intent can both be refused — by Android 14, or by MIUI, which
 * additionally needs Autostart before any of this survives the app being
 * closed. Hiding that would leave a feature that silently never fires.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var ringtoneName: TextView
    private lateinit var exactStatus: TextView
    private lateinit var exactAction: MaterialButton
    private lateinit var fullScreenStatus: TextView
    private lateinit var fullScreenAction: MaterialButton

    private val pickRingtone = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data
            ?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        // Prove it can actually be opened now rather than discovering at 10:00
        // that it can't. MIUI's picker in particular can hand back a URI this app
        // has no right to read.
        if (uri != null && !isReadable(uri)) {
            Toast.makeText(this, R.string.settings_sound_unreadable, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        VirementAlertStore.setRingtone(this, uri)
        showRingtone()
    }

    private fun isReadable(uri: Uri): Boolean = try {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        ringtoneName = findViewById(R.id.ringtone_name)
        exactStatus = findViewById(R.id.exact_status)
        exactAction = findViewById(R.id.btn_exact)
        fullScreenStatus = findViewById(R.id.full_screen_status)
        fullScreenAction = findViewById(R.id.btn_full_screen)

        findViewById<MaterialButton>(R.id.btn_pick_ringtone).setOnClickListener { pick() }
        findViewById<MaterialButton>(R.id.btn_test).setOnClickListener { runCheckNow() }
        exactAction.setOnClickListener {
            VirementAlarmScheduler.exactAlarmSettings(this)?.let(::startSafely)
        }
        fullScreenAction.setOnClickListener { openFullScreenSettings() }

        showRingtone()
    }

    override fun onResume() {
        super.onResume()
        // The user may have just come back from a system settings screen.
        showPermissions()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun pick() {
        val current = VirementAlertStore.ringtone(this)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.settings_pick_sound))
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        startSafely(intent) { pickRingtone.launch(intent) }
    }

    private fun showRingtone() {
        val uri = VirementAlertStore.ringtone(this)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val title = uri?.let {
            runCatching { RingtoneManager.getRingtone(this, it)?.getTitle(this) }.getOrNull()
        }
        ringtoneName.text = title ?: getString(R.string.settings_sound_default)
    }

    private fun showPermissions() {
        val exact = VirementAlarmScheduler.canBeExact(this)
        exactStatus.text = getString(
            if (exact) R.string.settings_exact_ok else R.string.settings_exact_missing
        )
        exactStatus.setTextColor(statusColor(exact))
        exactAction.visibility =
            if (exact || VirementAlarmScheduler.exactAlarmSettings(this) == null) View.GONE
            else View.VISIBLE

        val fullScreen = VirementNotifications.canUseFullScreen(this)
        fullScreenStatus.text = getString(
            if (fullScreen) R.string.settings_fullscreen_ok else R.string.settings_fullscreen_missing
        )
        fullScreenStatus.setTextColor(statusColor(fullScreen))
        fullScreenAction.visibility =
            if (fullScreen || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) View.GONE
            else View.VISIBLE
    }

    private fun statusColor(ok: Boolean) = ContextCompat.getColor(
        this, if (ok) R.color.pharma_green_dark else R.color.alert_red_text
    )

    /** Runs the same check the 10:00 alarm runs, ignoring the once-a-day guard. */
    private fun runCheckNow() {
        val intent = Intent(this, VirementAlarmService::class.java)
            .putExtra(VirementAlarmService.EXTRA_FORCE, true)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, R.string.settings_test_started, Toast.LENGTH_SHORT).show()
    }

    private fun openFullScreenSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        startSafely(
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(Uri.parse("package:$packageName"))
        )
    }

    private fun startSafely(intent: Intent, launch: () -> Unit = { startActivity(intent) }) {
        try {
            launch()
        } catch (e: Exception) {
            // Some MIUI builds simply don't ship the screen the intent names.
            Toast.makeText(this, R.string.settings_no_screen, Toast.LENGTH_LONG).show()
        }
    }
}
