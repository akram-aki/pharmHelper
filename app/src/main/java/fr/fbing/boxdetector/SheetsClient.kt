package fr.fbing.boxdetector

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Calendar

class SheetsHttpException(val code: Int, message: String) : IOException("HTTP $code: $message")

/**
 * Minimal Google Sheets/Drive client authenticated as a service account
 * (assets/sheets_config.json, injected by CI). Finds or creates the yearly
 * "medicament perime <year>" spreadsheet, shares it with the pharmacy's
 * account, and appends records. No Google SDK — RS256 JWT via java.security
 * and plain HttpURLConnection.
 */
class SheetsClient(private val context: Context) {

    private class Config(
        val clientEmail: String,
        val privateKey: PrivateKey,
        val tokenUri: String,
        val shareEmail: String
    )

    private val config: Config? by lazy { loadConfig() }

    fun isConfigured(): Boolean = config != null

    /** Appends one record to the current year's spreadsheet. Blocking. */
    @Throws(IOException::class)
    fun appendRecord(record: ExpiredRecord) {
        val cfg = config ?: throw IOException("Sheets not configured")
        val year = Calendar.getInstance().get(Calendar.YEAR)
        try {
            appendRow(cfg, spreadsheetIdForYear(cfg, year), record)
        } catch (e: SheetsHttpException) {
            when (e.code) {
                401 -> {
                    // Token expired/revoked mid-flight: refresh once and retry.
                    invalidateToken()
                    appendRow(cfg, spreadsheetIdForYear(cfg, year), record)
                }
                404 -> {
                    // Spreadsheet deleted since we cached its id: recreate.
                    clearCachedId(year)
                    appendRow(cfg, spreadsheetIdForYear(cfg, year), record)
                }
                else -> throw e
            }
        }
    }

    private fun appendRow(cfg: Config, spreadsheetId: String, record: ExpiredRecord) {
        val body = JSONObject().put(
            "values",
            JSONArray().put(
                JSONArray()
                    .put(record.timestamp)
                    .put(record.nom)
                    .put(record.dosage)
                    .put(record.conditionnement)
                    .put(record.forme)
                    .put(record.ppa)
                    .put(record.datePeremption)
                    .put(record.quantite)
            )
        )
        httpJson(
            "POST",
            "$SHEETS_BASE/$spreadsheetId/values/A1:append?valueInputOption=USER_ENTERED",
            accessToken(cfg),
            body
        )
        Log.d(TAG, "appended ${record.nom} x${record.quantite}")
    }

    // ------------------------------------------------------- Spreadsheet id

    private fun spreadsheetIdForYear(cfg: Config, year: Int): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(cacheKey(year), null)?.let { return it }

        val title = "medicament perime $year"
        val token = accessToken(cfg)

        val query = URLEncoder.encode(
            "name='$title' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false",
            "UTF-8"
        )
        val found = httpJson("GET", "$DRIVE_BASE/files?q=$query&fields=files(id)", token, null)
        val files = found.optJSONArray("files")
        val id = if (files != null && files.length() > 0) {
            files.getJSONObject(0).getString("id")
        } else {
            createSpreadsheet(cfg, token, title)
        }
        prefs.edit().putString(cacheKey(year), id).apply()
        return id
    }

    private fun createSpreadsheet(cfg: Config, token: String, title: String): String {
        val created = httpJson(
            "POST", SHEETS_BASE, token,
            JSONObject().put("properties", JSONObject().put("title", title))
        )
        val id = created.getString("spreadsheetId")

        val header = JSONArray()
        HEADERS.forEach { header.put(it) }
        httpJson(
            "POST",
            "$SHEETS_BASE/$id/values/A1:append?valueInputOption=USER_ENTERED",
            token,
            JSONObject().put("values", JSONArray().put(header))
        )

        httpJson(
            "POST",
            "$DRIVE_BASE/files/$id/permissions?sendNotificationEmail=false",
            token,
            JSONObject()
                .put("role", "writer")
                .put("type", "user")
                .put("emailAddress", cfg.shareEmail)
        )
        Log.i(TAG, "created spreadsheet '$title', shared with ${cfg.shareEmail}")
        return id
    }

    private fun clearCachedId(year: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(cacheKey(year)).apply()
    }

    private fun cacheKey(year: Int) = "sheet_id_$year"

    // ---------------------------------------------------------------- Auth

    private fun accessToken(cfg: Config): String {
        synchronized(tokenLock) {
            val now = System.currentTimeMillis() / 1000
            cachedToken?.let { (token, expiry) -> if (now < expiry) return token }

            val jwt = signJwt(cfg, now)
            val body = "grant_type=" + URLEncoder.encode(JWT_GRANT, "UTF-8") + "&assertion=$jwt"
            val response = httpForm(cfg.tokenUri, body)
            val token = response.getString("access_token")
            val expiresIn = response.optLong("expires_in", 3600)
            cachedToken = token to (now + expiresIn - 300)
            return token
        }
    }

    private fun invalidateToken() {
        synchronized(tokenLock) { cachedToken = null }
    }

    private fun signJwt(cfg: Config, nowSeconds: Long): String {
        val header = b64(JSONObject().put("alg", "RS256").put("typ", "JWT").toString())
        val claims = b64(
            JSONObject()
                .put("iss", cfg.clientEmail)
                .put("scope", SCOPES)
                .put("aud", cfg.tokenUri)
                .put("iat", nowSeconds)
                .put("exp", nowSeconds + 3600)
                .toString()
        )
        val signingInput = "$header.$claims"
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(cfg.privateKey)
            update(signingInput.toByteArray())
        }.sign()
        return "$signingInput." + Base64.encodeToString(signature, B64_FLAGS)
    }

    private fun b64(s: String): String = Base64.encodeToString(s.toByteArray(), B64_FLAGS)

    private fun loadConfig(): Config? = try {
        val json = JSONObject(
            context.assets.open(CONFIG_ASSET).bufferedReader().readText()
        )
        val sa = json.getJSONObject("service_account")
        val pem = sa.getString("private_key")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val key = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(pem, Base64.DEFAULT)))
        Config(
            clientEmail = sa.getString("client_email"),
            privateKey = key,
            tokenUri = sa.optString("token_uri", "https://oauth2.googleapis.com/token"),
            shareEmail = json.getString("share_email")
        )
    } catch (e: Exception) {
        Log.i(TAG, "no usable sheets_config.json (${e.javaClass.simpleName}) — upload disabled")
        null
    }

    // ---------------------------------------------------------------- HTTP

    private fun httpJson(method: String, url: String, token: String, body: JSONObject?): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return execute(conn, body?.toString())
    }

    private fun httpForm(url: String, formBody: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        return execute(conn, formBody)
    }

    private fun execute(conn: HttpURLConnection, body: String?): JSONObject {
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code from ${conn.url.path}")
                throw SheetsHttpException(code, text.take(300))
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "SheetsClient"
        private const val CONFIG_ASSET = "sheets_config.json"
        private const val PREFS = "sheets_prefs"
        private const val SHEETS_BASE = "https://sheets.googleapis.com/v4/spreadsheets"
        private const val DRIVE_BASE = "https://www.googleapis.com/drive/v3"
        private const val SCOPES =
            "https://www.googleapis.com/auth/spreadsheets https://www.googleapis.com/auth/drive"
        private const val JWT_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        private const val TIMEOUT_MS = 20_000
        private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

        private val HEADERS = listOf(
            "Horodatage", "Nom", "Dosage", "Conditionnement",
            "Forme", "PPA", "Date péremption", "Quantité"
        )

        private val tokenLock = Any()
        @Volatile private var cachedToken: Pair<String, Long>? = null
    }
}
