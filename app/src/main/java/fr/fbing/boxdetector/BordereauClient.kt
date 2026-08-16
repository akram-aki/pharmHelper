package fr.fbing.boxdetector

import android.content.Context
import android.util.Log
import java.io.IOException

/**
 * Reads the `bordereau` node of the pharmacy's Firebase Realtime Database.
 * The REST plumbing and the credential live in [RtdbConfig].
 */
class BordereauClient(private val context: Context) {

    private val rtdb: RtdbConfig? by lazy { RtdbConfig.load(context) }

    fun isConfigured(): Boolean = rtdb != null

    /** Fetches every bordereau. Blocking; throws on any failure. */
    @Throws(IOException::class)
    fun fetchAll(): List<Bordereau> {
        val db = rtdb ?: throw IOException("Bordereau database not configured")
        val records = Bordereau.parseAll(db.get(NODE))
        Log.d(TAG, "loaded ${records.size} bordereau(x)")
        return records
    }

    companion object {
        private const val TAG = "BordereauClient"
        private const val NODE = "bordereau"
    }
}
