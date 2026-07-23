package fr.fbing.boxdetector

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<View>(R.id.card_perime).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<View>(R.id.card_facture).setOnClickListener {
            startActivity(Intent(this, FactureActivity::class.java))
        }
        val comingSoon = View.OnClickListener {
            Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.card_more).setOnClickListener(comingSoon)

        // Retry any records still queued from previous offline sessions.
        UploadQueue.scheduleUpload(this)
        FactureUploadQueue.scheduleUpload(this)
    }
}
