package fr.fbing.boxdetector

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Shared REST access to the pharmacy's Firebase Realtime Database.
 *
 * REST rather than the Realtime Database SDK on purpose: the credential we hold
 * is a legacy *database secret*, which the Android SDK cannot use — it only
 * accepts Firebase Auth tokens. `GET <db>/<node>.json?auth=<secret>` does.
 *
 * Config: assets/bordereau_config.json {"database_url": ..., "auth": ...}. The
 * file is gitignored and written by CI, so the secret never lands in the repo.
 * One config serves every node the app reads — `bordereau` and `notifications`.
 */
class RtdbConfig private constructor(
    private val databaseUrl: String,
    private val auth: String
) {

    /**
     * GETs `<db>/<node>.json` and returns the raw body. [query] is appended as-is
     * and must already be URL-encoded, without a leading "&". Blocking; throws on
     * any non-2xx.
     */
    @Throws(IOException::class)
    fun get(node: String, query: String = ""): String {
        val url = buildString {
            append(databaseUrl.trimEnd('/'))
            append('/')
            append(node)
            append(".json?auth=")
            append(URLEncoder.encode(auth, "UTF-8"))
            if (query.isNotEmpty()) {
                append('&')
                append(query)
            }
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                // The body carries Firebase's reason ("Permission denied" when the
                // secret is wrong); keep it short so it stays loggable.
                Log.w(TAG, "HTTP $code reading $node")
                throw IOException("HTTP $code: ${text.take(300)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "RtdbConfig"
        private const val CONFIG_ASSET = "bordereau_config.json"
        private const val TIMEOUT_MS = 30_000

        /**
         * Null when the config asset is missing or unreadable — callers then
         * disable themselves rather than failing, so a build without the secret
         * still runs.
         */
        fun load(context: Context): RtdbConfig? = try {
            val json = JSONObject(
                context.assets.open(CONFIG_ASSET).bufferedReader().readText()
            )
            RtdbConfig(
                databaseUrl = json.getString("database_url"),
                auth = json.getString("auth")
            )
        } catch (e: Exception) {
            Log.i(TAG, "no usable $CONFIG_ASSET (${e.javaClass.simpleName}) — RTDB reads disabled")
            null
        }
    }
}
