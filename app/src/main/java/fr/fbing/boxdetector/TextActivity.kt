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

        val text = intent.getStringExtra(EXTRA_TEXT)
        findViewById<TextView>(R.id.extracted_text).text =
            if (text.isNullOrBlank()) getString(R.string.no_text_yet) else text
    }

    companion object {
        const val EXTRA_TEXT = "extracted_text"
    }
}
