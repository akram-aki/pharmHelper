package fr.fbing.boxdetector

import android.content.Context
import android.util.Log

/**
 * In-memory index of the medication variants derived from the pharmacy
 * STOCK.mdb (assets/drug_db.tsv): brand name -> known (dosage,
 * conditionnement, forme) combinations.
 */
object DrugDb {

    data class Variant(val dosage: String, val cond: String, val forme: String)

    @Volatile private var byName: Map<String, List<Variant>>? = null

    /** Idempotent; safe to call from any thread. */
    fun load(context: Context) {
        if (byName != null) return
        synchronized(this) {
            if (byName != null) return
            val start = System.currentTimeMillis()
            val map = HashMap<String, MutableList<Variant>>()
            context.assets.open(DB_ASSET).bufferedReader().forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size < 4 || parts[0].isBlank()) return@forEachLine
                map.getOrPut(parts[0]) { mutableListOf() }
                    .add(Variant(parts[1], parts[2], parts[3]))
            }
            byName = map
            Log.d(TAG, "loaded ${map.size} names / ${map.values.sumOf { it.size }} variants " +
                "in ${System.currentTimeMillis() - start}ms")
        }
    }

    /** Empty for unknown or DCI-only names. Call [load] first. */
    fun variantsFor(name: String): List<Variant> = byName?.get(name).orEmpty()

    private const val TAG = "DrugDb"
    private const val DB_ASSET = "drug_db.tsv"
}
