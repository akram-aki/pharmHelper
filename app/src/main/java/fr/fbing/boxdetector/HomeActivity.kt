package fr.fbing.boxdetector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HomeActivity : AppCompatActivity() {

    private lateinit var banner: View
    private lateinit var bannerTitle: TextView
    private lateinit var bannerDesc: TextView
    private lateinit var bannerMessage: TextView
    private lateinit var bannerMeta: TextView

    private lateinit var virementBanner: View
    private lateinit var virementList: TextView

    private lateinit var io: ExecutorService

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Refused is fine — the home banner still carries the alert. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<View>(R.id.card_perime).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<View>(R.id.card_facture).setOnClickListener {
            startActivity(Intent(this, FactureActivity::class.java))
        }
        findViewById<View>(R.id.card_bordereau).setOnClickListener {
            startActivity(Intent(this, BordereauActivity::class.java))
        }
        val comingSoon = View.OnClickListener {
            Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.card_more).setOnClickListener(comingSoon)

        findViewById<View>(R.id.card_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        banner = findViewById(R.id.cnas_banner)
        bannerTitle = findViewById(R.id.cnas_banner_title)
        bannerDesc = findViewById(R.id.cnas_banner_desc)
        bannerMessage = findViewById(R.id.cnas_banner_message)
        bannerMeta = findViewById(R.id.cnas_banner_meta)
        findViewById<View>(R.id.cnas_banner_dismiss).setOnClickListener { dismissAlert() }

        virementBanner = findViewById(R.id.virement_banner)
        virementList = findViewById(R.id.virement_banner_list)
        findViewById<View>(R.id.virement_banner_dismiss).setOnClickListener { dismissVirement() }

        io = Executors.newSingleThreadExecutor()

        // Retry any records still queued from previous offline sessions.
        UploadQueue.scheduleUpload(this)
        FactureUploadQueue.scheduleUpload(this)

        // CNAS alerts: a daily poll, plus one right now so opening the app always
        // reflects reality even when MIUI has starved the periodic job.
        CnasAlertMonitor.scheduleDailyCheck(this)
        CnasAlertMonitor.checkNow(this)

        // The 10:00 virement check. Armed on every start because an update or a
        // reboot MIUI didn't broadcast would otherwise leave it unscheduled, and
        // arming is idempotent.
        VirementAlarmScheduler.arm(this)

        askForNotifications()
    }

    override fun onResume() {
        super.onResume()
        refreshAlertBanner()
        refreshVirementBanner()
    }

    /**
     * Shows what the 10:00 alarm found and offers a way to silence it that does
     * not go through a notification — which matters when POST_NOTIFICATIONS was
     * refused, since then the alert notification never appears and its dismiss
     * action is unreachable.
     */
    private fun refreshVirementBanner() {
        if (io.isShutdown) return
        io.execute {
            val paid = VirementAlertStore.pending(this)
            runOnUiThread {
                if (paid.isEmpty()) {
                    virementBanner.visibility = View.GONE
                    return@runOnUiThread
                }
                virementList.text = VirementNotifications.summarise(this, paid)
                virementBanner.visibility = View.VISIBLE
            }
        }
    }

    private fun dismissVirement() {
        virementBanner.visibility = View.GONE
        // Goes through the service so the sound stops too, if it is still going.
        VirementAlarmService.dismiss(this)
    }

    /** Reads the cached alert off the worker's state file; no network here. */
    private fun refreshAlertBanner() {
        if (io.isShutdown) return
        io.execute {
            val alert = CnasAlertMonitor.latestUnacknowledged(this)
            runOnUiThread {
                if (alert == null) {
                    banner.visibility = View.GONE
                    return@runOnUiThread
                }
                bannerTitle.setText(alert.kindOf.titleRes)
                bannerDesc.setText(alert.kindOf.descRes)
                bannerMessage.text = alert.message
                bannerMeta.text = getString(R.string.cnas_banner_meta, alert.formatAt(), alert.host)
                bannerMeta.visibility =
                    if (alert.at.isEmpty() && alert.host.isEmpty()) View.GONE else View.VISIBLE
                banner.visibility = View.VISIBLE
            }
        }
    }

    private fun dismissAlert() {
        banner.visibility = View.GONE
        if (io.isShutdown) return
        io.execute { CnasAlertMonitor.acknowledge(this) }
    }

    /** Android 13+ only; below that posting notifications needs no grant. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }
}
