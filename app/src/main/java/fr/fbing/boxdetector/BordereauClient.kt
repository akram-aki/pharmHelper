package fr.fbing.boxdetector

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Reads the `bordereau` node of the pharmacy's Firebase Realtime Database over
 * the REST API.
 *
 * REST rather than the Realtime Database SDK on purpose: the credential we hold
 * is a legacy *database secret*, which the Android SDK cannot use — it only
 * accepts Firebase Auth tokens. `GET <db>/bordereau.json?auth=<secret>` does.
 *
 * Config: assets/bordereau_config.json {"database_url": ..., "auth": ...}. The
 * file is gitignored and written by CI, so the secret never lands in the repo.
 */
class BordereauClient(private val context: Context) {

    private class Config(val databaseUrl: String, val auth: String)

    private val config: Config? by lazy { loadConfig() }

    fun isConfigured(): Boolean = config != null

    /** Fetches every bordereau. Blocking; throws on any failure. */
    @Throws(IOException::class)
    fun fetchAll(): List<Bordereau> {
        val cfg = config ?: throw IOException("Bordereau database not configured")
        val url = buildString {
            append(cfg.databaseUrl.trimEnd('/'))
            append('/')
            append(NODE)
            append(".json?auth=")
            append(URLEncoder.encode(cfg.auth, "UTF-8"))
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        val body = try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                // The body carries Firebase's reason ("Permission denied" when the
                // secret is wrong); keep it short so it stays loggable.
                Log.w(TAG, "HTTP $code reading $NODE")
                throw IOException("HTTP $code: ${text.take(300)}")
            }
            text
        } finally {
            conn.disconnect()
        }

        val records = Bordereau.parseAll(body)
        Log.d(TAG, "loaded ${records.size} bordereau(x)")
        return records
    }

    private fun loadConfig(): Config? = try {
        val json = JSONObject(
            context.assets.open(CONFIG_ASSET).bufferedReader().readText()
        )
        Config(
            databaseUrl = json.getString("database_url"),
            auth = json.getString("auth")
        )
    } catch (e: Exception) {
        Log.i(TAG, "no usable $CONFIG_ASSET (${e.javaClass.simpleName}) — bordereau disabled")
        null
    }

    companion object {
        private const val TAG = "BordereauClient"
        private const val CONFIG_ASSET = "bordereau_config.json"
        private const val NODE = "bordereau"
        private const val TIMEOUT_MS = 30_000
    }
}
