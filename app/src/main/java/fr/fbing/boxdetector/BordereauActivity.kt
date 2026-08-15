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

    /** Null means "Tous". */
    private var statusFilter: BordereauStatus? = null

    private var loading = false

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
            statusFilter = when (checkedIds.firstOrNull()) {
                R.id.chip_open -> BordereauStatus.OUVERT
                R.id.chip_todo -> BordereauStatus.A_DEPOSER
                R.id.chip_sent -> BordereauStatus.DEPOSE
                else -> null
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

    /** Applies the état filter and the num_bord search, then regroups by year. */
    private fun render() {
        val query = searchInput.text?.toString()?.trim().orEmpty()
        val visible = all.filter { bordereau ->
            (statusFilter == null || bordereau.status == statusFilter) &&
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

        val rows = mutableListOf<BordereauAdapter.Row>()
        // Grouped by the year encoded in num_bord, most recent year first;
        // numbers that don't follow the "SSSSYY" shape land in a trailing group.
        visible.groupBy { it.year }
            .entries
            .sortedByDescending { it.key ?: Int.MIN_VALUE }
            .forEach { (year, group) ->
                rows += BordereauAdapter.Row.Header(
                    label = year?.toString() ?: getString(R.string.bordereau_year_unknown),
                    count = group.size,
                    total = group.sumOf { it.amount ?: 0.0 }
                )
                group.forEach { rows += BordereauAdapter.Row.Item(it) }
            }

        adapter.submit(rows)
        emptyState.visibility = View.GONE
        list.visibility = View.VISIBLE
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
