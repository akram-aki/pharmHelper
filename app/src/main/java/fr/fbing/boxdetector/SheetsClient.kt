package fr.fbing.boxdetector

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class SheetsHttpException(val code: Int, message: String) : IOException("HTTP $code: $message")

/**
 * Uploads records to the pharmacy's Google Apps Script endpoint (deployed as
 * a Web App running as the owner's account). The script owns find-or-create
 * of the yearly "medicament perime <year>" spreadsheet — Google no longer
 * lets service accounts create Drive files, so creation must run as a human
 * account. Config: assets/sheets_config.json {"script_url": ..., "secret": ...}.
 */
class SheetsClient(private val context: Context) {

    private class Config(val scriptUrl: String, val secret: String)

    private val config: Config? by lazy { loadConfig() }

    fun isConfigured(): Boolean = config != null

    /** Appends all [records] in one call. Blocking; throws on any failure. */
    @Throws(IOException::class)
    fun appendRecords(records: List<ExpiredRecord>) {
        val cfg = config ?: throw IOException("Sheets not configured")
        val payload = JSONObject()
            .put("secret", cfg.secret)
            .put("records", JSONArray().apply { records.forEach { put(it.toJson()) } })

        val response = postJson(cfg.scriptUrl, payload.toString(), MAX_REDIRECTS)
        if (!response.optBoolean("ok", false)) {
            throw IOException("endpoint error: ${response.optString("error", "unknown")}")
        }
        Log.d(TAG, "appended ${records.size} record(s)")
    }

    /**
     * Uploads one scanned facture: base64-encoded PDF plus metadata. The script
     * (action "uploadFacture") stores it as a Drive file and appends a row to
     * the "Factures" index sheet. Blocking; throws on any failure.
     */
    @Throws(IOException::class)
    fun uploadFacture(record: FactureRecord, pdfBase64: String) {
        val cfg = config ?: throw IOException("Sheets not configured")
        val payload = JSONObject()
            .put("secret", cfg.secret)
            .put("action", "uploadFacture")
            .put("meta", record.toMeta())
            .put("pdfBase64", pdfBase64)

        val response = postJson(cfg.scriptUrl, payload.toString(), MAX_REDIRECTS)
        if (!response.optBoolean("ok", false)) {
            throw IOException("endpoint error: ${response.optString("error", "unknown")}")
        }
        Log.d(TAG, "uploaded facture ${record.id}")
    }

    private fun loadConfig(): Config? = try {
        val json = JSONObject(
            context.assets.open(CONFIG_ASSET).bufferedReader().readText()
        )
        Config(
            scriptUrl = json.getString("script_url"),
            secret = json.getString("secret")
        )
    } catch (e: Exception) {
        Log.i(TAG, "no usable sheets_config.json (${e.javaClass.simpleName}) — upload disabled")
        null
    }

    /**
     * POSTs [body]; Apps Script answers with a redirect to a one-time
     * googleusercontent URL that must be fetched with GET.
     */
    private fun postJson(url: String, body: String, redirectsLeft: Int): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw SheetsHttpException(code, "redirect without Location")
                if (redirectsLeft <= 0) throw SheetsHttpException(code, "too many redirects")
                return getJson(location, redirectsLeft - 1)
            }
            return readBody(conn, code)
        } finally {
            conn.disconnect()
        }
    }

    private fun getJson(url: String, redirectsLeft: Int): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
        }
        try {
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw SheetsHttpException(code, "redirect without Location")
                if (redirectsLeft <= 0) throw SheetsHttpException(code, "too many redirects")
                return getJson(location, redirectsLeft - 1)
            }
            return readBody(conn, code)
        } finally {
            conn.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection, code: Int): JSONObject {
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            Log.w(TAG, "HTTP $code from endpoint")
            throw SheetsHttpException(code, text.take(300))
        }
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            // An HTML error page instead of JSON (bad deployment, auth screen).
            throw IOException("unexpected response (not JSON): ${text.take(120)}")
        }
    }

    companion object {
        private const val TAG = "SheetsClient"
        private const val CONFIG_ASSET = "sheets_config.json"
        private const val TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 3
    }
}
