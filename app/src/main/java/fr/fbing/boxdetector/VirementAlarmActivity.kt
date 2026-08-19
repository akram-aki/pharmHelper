package fr.fbing.boxdetector

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * The screen the pharmacist sees when the 10:00 alarm has found a payment —
 * launched by the alert's full-screen intent, so it appears over the lockscreen
 * the moment the phone is picked up.
 *
 * Its only job is to name what was paid and offer one button that stops the
 * noise. Dismissing here also clears the home banner, since both read the same
 * pending list in [VirementAlertStore].
 */
class VirementAlarmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockscreen()
        setContentView(R.layout.activity_virement_alarm)

        render()

        findViewById<MaterialButton>(R.id.btn_dismiss).setOnClickListener {
            VirementAlarmService.dismiss(this)
            finish()
        }
        findViewById<MaterialButton>(R.id.btn_open_list).setOnClickListener {
            VirementAlarmService.dismiss(this)
            startActivity(Intent(this, BordereauActivity::class.java))
            finish()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        render()
    }

    private fun render() {
        val paid = VirementAlertStore.pending(this)
        findViewById<TextView>(R.id.alarm_title).text = resources.getQuantityString(
            R.plurals.virement_alert_title, paid.size.coerceAtLeast(1), paid.size
        )
        findViewById<TextView>(R.id.alarm_list).text =
            VirementNotifications.summarise(this, paid)
    }

    /**
     * Turns the screen on and shows over the keyguard, the way an alarm clock
     * does. The pre-27 flags are still needed for minSdk 26.
     */
    @Suppress("DEPRECATION")
    private fun showOverLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Leaving without dismissing must not silence the alarm. */
    override fun onBackPressed() {
        moveTaskToBack(true)
    }
}
