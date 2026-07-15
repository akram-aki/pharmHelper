package fr.fbing.boxdetector

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class TextActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { finish() }

        val name = intent.getStringExtra(EXTRA_NAME)
        val confidence = intent.getIntExtra(EXTRA_NAME_CONFIDENCE, 0)
        findViewById<TextView>(R.id.field_name_value).text = when {
            name != null -> getString(R.string.name_with_confidence, name, confidence)
            confidence > 0 -> getString(R.string.name_not_recognized_guess, confidence)
            else -> getString(R.string.name_not_recognized)
        }

        findViewById<TextView>(R.id.field_dosage_value).text =
            intent.getStringExtra(EXTRA_DOSAGE) ?: PLACEHOLDER
        findViewById<TextView>(R.id.field_ppa_value).text =
            intent.getStringExtra(EXTRA_PPA) ?: PLACEHOLDER
        findViewById<TextView>(R.id.field_fab_value).text =
            intent.getStringExtra(EXTRA_FAB_DATE) ?: PLACEHOLDER
        findViewById<TextView>(R.id.field_exp_value).text =
            intent.getStringExtra(EXTRA_EXP_DATE) ?: PLACEHOLDER

        val raw = intent.getStringExtra(EXTRA_TEXT)
        findViewById<TextView>(R.id.extracted_text).text =
            if (raw.isNullOrBlank()) getString(R.string.no_text_yet) else raw
    }

    companion object {
        const val EXTRA_TEXT = "extracted_text"
        const val EXTRA_NAME = "name"
        const val EXTRA_NAME_CONFIDENCE = "name_confidence"
        const val EXTRA_DOSAGE = "dosage"
        const val EXTRA_PPA = "ppa"
        const val EXTRA_FAB_DATE = "fab_date"
        const val EXTRA_EXP_DATE = "exp_date"
        private const val PLACEHOLDER = "—"
    }
}
