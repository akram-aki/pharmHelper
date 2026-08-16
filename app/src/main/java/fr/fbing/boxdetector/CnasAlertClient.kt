package fr.fbing.boxdetector

import android.content.Context
import android.util.Log
import java.io.IOException

/**
 * Reads the `notifications` node — the alerts the pharmacy PC writes when CNAS
 * rejects the daily code. Shares [RtdbConfig] (and therefore the credential)
 * with [BordereauClient].
 */
class CnasAlertClient(private val context: Context) {

    private val rtdb: RtdbConfig? by lazy { RtdbConfig.load(context) }

    fun isConfigured(): Boolean = rtdb != null

    /**
     * Fetches the newest alerts, oldest-first. Blocking; throws on any failure.
     * Push keys sort chronologically, so `orderBy="$key"&limitToLast` gives the
     * most recent records without needing an index.
     */
    @Throws(IOException::class)
    fun fetchRecent(): List<CnasAlert> {
        val db = rtdb ?: throw IOException("CNAS alerts not configured")
        val alerts = CnasAlert.parseAll(db.get(NODE, RECENT_QUERY))
        Log.d(TAG, "loaded ${alerts.size} CNAS alert(s)")
        return alerts
    }

    companion object {
        private const val TAG = "CnasAlertClient"
        private const val NODE = "notifications"

        /** `orderBy="$key"&limitToLast=20`, pre-encoded. */
        private const val RECENT_QUERY = "orderBy=%22%24key%22&limitToLast=20"
    }
}
