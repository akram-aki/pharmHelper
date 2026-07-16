package fr.fbing.boxdetector

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TextActivity : AppCompatActivity() {

    private lateinit var parser: VignetteParser
    private var rawText: String = ""

    // User-validated values (radio selections included) — the value TextViews
    // can't be scraped: the name embeds "(87%)" and empty fields show placeholders.
    private var currentName: String? = null
    private var currentDosage: String? = null
    private var currentCond: String? = null
    private var currentForme: String? = null

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

        currentName = name
        setupNameOptions(nameValue, initialName = name)
        rebuildFields(name)

        findViewById<View>(R.id.btn_next).setOnClickListener { showQuantityDialog() }
        wireDateEdit(R.id.btn_edit_fab, R.id.field_fab_value, R.string.field_fab_date)
        wireDateEdit(R.id.btn_edit_exp, R.id.field_exp_value, R.string.field_exp_date)

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
            currentName = options[index]
            // The other fields depend on the medicine's known variants.
            rebuildFields(options[index])
        }
    }

    /** Cross-references dosage / conditionnement / forme for the given name. */
    private fun rebuildFields(name: String?) {
        val (dosage, cond, forme) = parser.matchFields(rawText, name)
        bindField(R.id.field_dosage_value, R.id.dosage_options, dosage) { currentDosage = it }
        bindField(R.id.field_cond_value, R.id.cond_options, cond) { currentCond = it }
        bindField(R.id.field_forme_value, R.id.forme_options, forme) { currentForme = it }
    }

    /**
     * Fills a field row. Confident values display directly; ambiguous or
     * unreadable ones show up to 3 catalog choices from the drug database.
     * [onValue] tracks the validated value (null until the user picks when
     * a choice is required).
     */
    private fun bindField(valueId: Int, groupId: Int, field: FieldResult, onValue: (String?) -> Unit) {
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
        onValue(if (choices) null else field.value)
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
            onValue(field.options[index])
        }
    }

    /** Lets the user type or correct a date the OCR missed or misread. */
    private fun wireDateEdit(buttonId: Int, valueId: Int, titleRes: Int) {
        val valueView = findViewById<TextView>(valueId)
        findViewById<View>(buttonId).setOnClickListener {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = getString(R.string.edit_date_hint)
                val current = valueView.text.toString()
                if (current != PLACEHOLDER) {
                    setText(current)
                    setSelection(current.length)
                }
            }
            val container = FrameLayout(this).apply {
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    val text = input.text.toString().trim()
                    valueView.text = text.ifEmpty { PLACEHOLDER }
                }
                .show()
        }
    }

    // ------------------------------------------------- Quantity + upload

    private fun showQuantityDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.quantity_hint)
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quantity_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val quantity = input.text.toString().trim().toIntOrNull()
                if (quantity == null || quantity <= 0) {
                    Toast.makeText(this, R.string.quantity_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    saveRecord(quantity)
                }
            }
            .show()
    }

    private fun saveRecord(quantity: Int) {
        val record = ExpiredRecord(
            timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
            nom = currentName.orEmpty(),
            dosage = currentDosage.orEmpty(),
            conditionnement = currentCond.orEmpty(),
            forme = currentForme.orEmpty(),
            ppa = fieldText(R.id.field_ppa_value),
            datePeremption = fieldText(R.id.field_exp_value),
            quantite = quantity
        )
        UploadQueue.enqueue(this, record)
        val msg = if (SheetsClient(this).isConfigured()) R.string.saved_uploading else R.string.not_configured
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun fieldText(id: Int): String {
        val text = findViewById<TextView>(id).text.toString()
        return if (text == PLACEHOLDER) "" else text
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
