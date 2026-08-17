package fr.fbing.boxdetector

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * Flat list of year headers followed by their bordereaux — the grouping the
 * "Vérifier bordereau" screen shows. [BordereauActivity] rebuilds the rows on
 * every filter change; this adapter only renders them.
 */
class BordereauAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        /** A year section, with how many bordereaux it holds and their total. */
        data class Header(val label: String, val count: Int, val total: Double) : Row()
        data class Item(val bordereau: Bordereau) : Row()
    }

    private var rows: List<Row> = emptyList()

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_bordereau_header, parent, false))
        } else {
            ItemHolder(inflater.inflate(R.layout.item_bordereau, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row)
            is Row.Item -> (holder as ItemHolder).bind(row.bordereau)
        }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.header_title)
        private val summary: TextView = view.findViewById(R.id.header_summary)

        fun bind(row: Row.Header) {
            val context = itemView.context
            title.text = row.label
            summary.text = context.getString(
                R.string.bordereau_group_summary,
                row.count,
                BordereauFormat.amount(row.total)
            )
        }
    }

    private class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val numBord: TextView = view.findViewById(R.id.num_bord)
        private val centre: TextView = view.findViewById(R.id.code_centre)
        private val badge: TextView = view.findViewById(R.id.status_badge)
        private val dateLabel: TextView = view.findViewById(R.id.date_label)
        private val depositDate: TextView = view.findViewById(R.id.deposit_date)
        private val amount: TextView = view.findViewById(R.id.mont_vir)

        fun bind(item: Bordereau) {
            val context = itemView.context
            numBord.text = item.numBord
            centre.text = context.getString(R.string.bordereau_centre, item.codeCentre)

            val (labelRes, textColorRes, backgroundColorRes) = when (item.status) {
                BordereauStatus.OUVERT ->
                    Triple(R.string.bordereau_status_open, R.color.status_open_text, R.color.status_open_bg)
                BordereauStatus.A_DEPOSER ->
                    Triple(R.string.bordereau_status_todo, R.color.status_todo_text, R.color.status_todo_bg)
                BordereauStatus.DEPOSE ->
                    Triple(R.string.bordereau_status_sent, R.color.status_sent_text, R.color.status_sent_bg)
            }
            badge.setText(labelRes)
            badge.setTextColor(ContextCompat.getColor(context, textColorRes))
            badge.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, backgroundColorRes))

            // Show whichever date the Virée view actually files this bordereau
            // under, labelled for what it is. An observed virement wins: it is
            // the real payment date, where the dépôt is only a proxy. Neither
            // present means the bordereau never reached the FTP — say so rather
            // than showing the 1900 sentinel the export ships.
            val virement = item.formatVirementDate()
            val deposited = item.formatDepositDate()
            val (dateLabelRes, dateValue, dateColorRes) = when {
                virement != null ->
                    Triple(R.string.bordereau_virement_label, virement, R.color.pharma_green_dark)
                deposited != null ->
                    Triple(R.string.bordereau_deposit_label, deposited, R.color.text_primary)
                else -> Triple(
                    R.string.bordereau_deposit_label,
                    context.getString(R.string.bordereau_not_deposited),
                    R.color.status_todo_text
                )
            }
            dateLabel.setText(dateLabelRes)
            depositDate.text = dateValue
            depositDate.setTextColor(ContextCompat.getColor(context, dateColorRes))

            amount.text = context.getString(R.string.bordereau_amount, item.formatAmount())
            amount.setTextColor(
                ContextCompat.getColor(
                    context,
                    if ((item.amount ?: 0.0) > 0.0) R.color.pharma_green_dark else R.color.text_secondary
                )
            )
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }
}
