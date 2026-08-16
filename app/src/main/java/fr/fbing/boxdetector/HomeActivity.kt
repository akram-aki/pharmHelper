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
    private lateinit var bannerMessage: TextView
    private lateinit var bannerMeta: TextView

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

        banner = findViewById(R.id.cnas_banner)
        bannerMessage = findViewById(R.id.cnas_banner_message)
        bannerMeta = findViewById(R.id.cnas_banner_meta)
        findViewById<View>(R.id.cnas_banner_dismiss).setOnClickListener { dismissAlert() }

        io = Executors.newSingleThreadExecutor()

        // Retry any records still queued from previous offline sessions.
        UploadQueue.scheduleUpload(this)
        FactureUploadQueue.scheduleUpload(this)

        // CNAS alerts: a daily poll, plus one right now so opening the app always
        // reflects reality even when MIUI has starved the periodic job.
        CnasAlertMonitor.scheduleDailyCheck(this)
        CnasAlertMonitor.checkNow(this)
        askForNotifications()
    }

    override fun onResume() {
        super.onResume()
        refreshAlertBanner()
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
