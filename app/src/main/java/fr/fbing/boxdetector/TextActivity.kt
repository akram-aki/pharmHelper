package fr.fbing.boxdetector

import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class TextActivity : AppCompatActivity() {

    private lateinit var parser: VignetteParser
    private var rawText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { finish() }

        parser = VignetteParser(this)
        rawText = intent.getStringExtra(EXTRA_TEXT).orEmpty()

        val name = intent.getStringExtra(EXTRA_NAME)
        val confidence = intent.getIntExtra(EXTRA_NAME_CONFIDENCE, 0)
        val nameValue = findViewById<TextView>(R.id.field_name_value)
        nameValue.text = when {
            name != null -> getString(R.string.name_with_confidence, name, confidence)
            confidence > 0 -> getString(R.string.name_not_recognized_guess, confidence)
            else -> getString(R.string.name_not_recognized)
        }

        setupNameOptions(nameValue, initialName = name)
        rebuildFields(name)

        findViewById<TextView>(R.id.field_ppa_value).text =
            intent.getStringExtra(EXTRA_PPA) ?: PLACEHOLDER
        findViewById<TextView>(R.id.field_fab_value).text =
            intent.getStringExtra(EXTRA_FAB_DATE) ?: PLACEHOLDER
        findViewById<TextView>(R.id.field_exp_value).text =
            intent.getStringExtra(EXTRA_EXP_DATE) ?: PLACEHOLDER

        findViewById<TextView>(R.id.extracted_text).text =
            if (rawText.isBlank()) getString(R.string.no_text_yet) else rawText
    }

    /** Shows a chooser when the parser returned several plausible names. */
    private fun setupNameOptions(nameValue: TextView, initialName: String?) {
        val options = intent.getStringArrayListExtra(EXTRA_NAME_OPTIONS) ?: return
        val confs = intent.getIntArrayExtra(EXTRA_NAME_OPTION_CONFS) ?: return
        if (options.size < 2 || confs.size != options.size) return

        val group = findViewById<RadioGroup>(R.id.name_options)
        group.visibility = View.VISIBLE

        options.forEachIndexed { index, option ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(R.string.name_with_confidence, option, confs[index])
                tag = index
            }
            group.addView(button)
            if (index == 0 && initialName != null) group.check(button.id)
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val index = g.findViewById<RadioButton>(checkedId)?.tag as? Int
                ?: return@setOnCheckedChangeListener
            nameValue.text = getString(R.string.name_with_confidence, options[index], confs[index])
            // The other fields depend on the medicine's known variants.
            rebuildFields(options[index])
        }
    }

    /** Cross-references dosage / conditionnement / forme for the given name. */
    private fun rebuildFields(name: String?) {
        val (dosage, cond, forme) = parser.matchFields(rawText, name)
        bindField(R.id.field_dosage_value, R.id.dosage_options, dosage)
        bindField(R.id.field_cond_value, R.id.cond_options, cond)
        bindField(R.id.field_forme_value, R.id.forme_options, forme)
    }

    /**
     * Fills a field row. Confident values display directly; ambiguous or
     * unreadable ones show up to 3 catalog choices from the drug database.
     */
    private fun bindField(valueId: Int, groupId: Int, field: FieldResult) {
        val valueView = findViewById<TextView>(valueId)
        val group = findViewById<RadioGroup>(groupId)
        group.setOnCheckedChangeListener(null)
        group.removeAllViews()

        val confident = field.value != null && field.confidence >= VignetteParser.ACCEPT_CONFIDENCE
        val choices = field.needsChoice()

        valueView.text = when {
            confident && !choices -> field.value
            choices -> getString(R.string.field_choose)
            field.value != null -> field.value   // single low-confidence guess, nothing to pick
            else -> PLACEHOLDER
        }
        if (!choices) {
            group.visibility = View.GONE
            return
        }

        group.visibility = View.VISIBLE
        field.options.forEachIndexed { index, option ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = option
                tag = index
            }
            group.addView(button)
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val index = g.findViewById<RadioButton>(checkedId)?.tag as? Int
                ?: return@setOnCheckedChangeListener
            valueView.text = field.options[index]
        }
    }

    companion object {
        const val EXTRA_TEXT = "extracted_text"
        const val EXTRA_NAME = "name"
        const val EXTRA_NAME_CONFIDENCE = "name_confidence"
        const val EXTRA_NAME_OPTIONS = "name_options"
        const val EXTRA_NAME_OPTION_CONFS = "name_option_confs"
        const val EXTRA_PPA = "ppa"
        const val EXTRA_FAB_DATE = "fab_date"
        const val EXTRA_EXP_DATE = "exp_date"
        private const val PLACEHOLDER = "—"
    }
}
