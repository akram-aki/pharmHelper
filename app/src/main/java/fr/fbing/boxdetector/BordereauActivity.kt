package fr.fbing.boxdetector

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * "Vérifier bordereau": loads the `bordereau` node of the Realtime Database and
 * lists every bordereau grouped by the year encoded in its `num_bord`, with the
 * état / dépôt FTP status as a badge and the virement amount alongside.
 *
 * The state filter is derived from `etat` + `date_depot_ftp` — see
 * [BordereauStatus] — so "à déposer" (closed but never sent) is one tap away,
 * which is the case the pharmacy actually has to chase.
 */
class BordereauActivity : AppCompatActivity() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var list: RecyclerView
    private lateinit var progress: View
    private lateinit var emptyState: View
    private lateinit var emptyText: TextView
    private lateinit var retryButton: MaterialButton
    private lateinit var searchInput: TextInputEditText
    private lateinit var chipGroup: ChipGroup
    private lateinit var summary: TextView

    private val adapter = BordereauAdapter()
    private lateinit var io: ExecutorService
    private lateinit var client: BordereauClient

    /** Everything the last successful load returned, newest bordereau first. */
    private var all: List<Bordereau> = emptyList()

    private var filter = Filter.VIREE

    private var loading = false

    /**
     * The three views of the list, all grouped by month of dépôt. [VIREE] leads
     * and is the default — the bordereaux the CNAS has actually paid are what
     * the pharmacy checks first — and [NON_VIREE] is its exact complement, so
     * the two together account for every record in [TOUT].
     *
     * The état itself is no longer a filter; it stays visible as the coloured
     * badge each card carries.
     */
    private enum class Filter {
        VIREE,
        NON_VIREE,
        TOUT;

        fun matches(bordereau: Bordereau): Boolean = when (this) {
            VIREE -> bordereau.isVire
            NON_VIREE -> !bordereau.isVire
            TOUT -> true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bordereau)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        swipeRefresh = findViewById(R.id.swipe_refresh)
        list = findViewById(R.id.list)
        progress = findViewById(R.id.progress)
        emptyState = findViewById(R.id.empty_state)
        emptyText = findViewById(R.id.empty_text)
        retryButton = findViewById(R.id.btn_retry)
        searchInput = findViewById(R.id.input_search)
        chipGroup = findViewById(R.id.chip_group)
        summary = findViewById(R.id.summary)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        swipeRefresh.setColorSchemeResources(R.color.pharma_green)
        swipeRefresh.setOnRefreshListener { load(showSpinner = false) }
        retryButton.setOnClickListener { load(showSpinner = true) }

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            filter = when (checkedIds.firstOrNull()) {
                R.id.chip_non_vire -> Filter.NON_VIREE
                R.id.chip_all -> Filter.TOUT
                else -> Filter.VIREE
            }
            render()
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render()
        })

        io = Executors.newSingleThreadExecutor()
        client = BordereauClient(this)

        load(showSpinner = true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun load(showSpinner: Boolean) {
        if (loading) {
            // Already fetching — don't fire a second request, but let the
            // pull-to-refresh spinner settle back.
            if (!showSpinner) swipeRefresh.isRefreshing = false
            return
        }
        if (!client.isConfigured()) {
            swipeRefresh.isRefreshing = false
            showMessage(getString(R.string.bordereau_not_configured), retry = false)
            return
        }

        loading = true
        if (showSpinner) {
            progress.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            list.visibility = View.GONE
        }

        io.execute {
            val result = runCatching { client.fetchAll() }
            runOnUiThread {
                loading = false
                progress.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                result
                    .onSuccess { records ->
                        all = records.sortedWith(NEWEST_FIRST)
                        render()
                    }
                    .onFailure { error ->
                        if (all.isEmpty()) {
                            showMessage(
                                getString(
                                    R.string.bordereau_load_failed,
                                    error.message ?: error.javaClass.simpleName
                                ),
                                retry = true
                            )
                        } else {
                            // Keep showing what we already have; a failed refresh
                            // shouldn't wipe the screen.
                            Toast.makeText(
                                this, R.string.bordereau_refresh_failed, Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
            }
        }
    }

    /** Applies the current view and the num_bord search, then regroups. */
    private fun render() {
        val query = searchInput.text?.toString()?.trim().orEmpty()
        val visible = all.filter { bordereau ->
            filter.matches(bordereau) &&
                (query.isEmpty() || bordereau.numBord.contains(query, ignoreCase = true))
        }

        summary.text = getString(
            R.string.bordereau_summary,
            visible.size,
            BordereauFormat.amount(visible.sumOf { it.amount ?: 0.0 })
        )

        if (visible.isEmpty()) {
            adapter.submit(emptyList())
            showMessage(
                if (all.isEmpty()) getString(R.string.bordereau_empty)
                else getString(R.string.bordereau_no_match),
                retry = false
            )
            return
        }

        adapter.submit(byMonth(visible))
        emptyState.visibility = View.GONE
        list.visibility = View.VISIBLE
    }

    /**
     * Grouped by the month of the FTP deposit, most recent month first, with the
     * bordereaux this export never dated trailing at the end — 88 of them carry
     * a virement with no date anywhere in the record, so they can't be placed on
     * the calendar without guessing.
     */
    private fun byMonth(visible: List<Bordereau>): List<BordereauAdapter.Row> {
        val rows = mutableListOf<BordereauAdapter.Row>()
        visible.groupBy { it.depositMonth }
            .entries
            // A null month sorts as "" — last, under descending order.
            .sortedByDescending { it.key ?: "" }
            .forEach { (month, group) ->
                val ordered = group.sortedByDescending { it.depositedAt }
                rows += BordereauAdapter.Row.Header(
                    label = month?.let(BordereauFormat::monthLabel)
                        ?: getString(R.string.bordereau_month_unknown),
                    count = ordered.size,
                    total = ordered.sumOf { it.amount ?: 0.0 }
                )
                ordered.forEach { rows += BordereauAdapter.Row.Item(it) }
            }
        return rows
    }

    private fun showMessage(message: String, retry: Boolean) {
        emptyText.text = message
        retryButton.visibility = if (retry) View.VISIBLE else View.GONE
        emptyState.visibility = View.VISIBLE
        list.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    companion object {
        /** Newest year first, then the highest sequence within that year. */
        private val NEWEST_FIRST = compareByDescending<Bordereau> { it.year ?: Int.MIN_VALUE }
            .thenByDescending { it.sequence }
    }
}
