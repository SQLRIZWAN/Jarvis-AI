package com.sqlai.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GeminiModel(
    val id: String,        // e.g. "gemini-1.5-flash"
    val displayName: String
)

/**
 * Pulls the live list of Gemini models that support generateContent straight
 * from the official endpoint, so the UI dropdown always matches the key.
 *
 * GET https://generativelanguage.googleapis.com/v1beta/models?key=KEY
 */
object GeminiModelFetcher {

    private const val PAGE_SIZE = 200
    private const val MAX_PAGES = 4

    /** Model ids that must never be auto-selected (specialized/preview builds). */
    private val EXCLUDED_TAGS = listOf("image", "thinking", "pro", "lite", "preview")

    /** major.minor[.patch] embedded in a Gemini model id. */
    private val VERSION_PATTERN = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""")

    /**
     * Picks the newest general-purpose flash model from a live model list.
     *
     * Rules: the id must contain "-flash"; ids tagged image / thinking / pro /
     * lite / preview are skipped; the highest major.minor(.patch) version wins
     * and equal versions break alphabetically (earlier id first).
     * Returns null when no id qualifies.
     */
    fun pickBestFlash(models: List<GeminiModel>): String? =
        models.map { it.id }
            .filter { id -> id.contains("-flash") }
            .filterNot { id -> EXCLUDED_TAGS.any { id.contains(it) } }
            .sortedWith(compareByDescending<String> { versionOf(it) }.thenBy { it })
            .firstOrNull()

    /** Sortable version key of [id]: major * 1e12 + minor * 1e6 + patch (0 if unversioned). */
    private fun versionOf(id: String): Long {
        val match = VERSION_PATTERN.find(id) ?: return 0L
        val major = match.groupValues[1].toLongOrNull()?.coerceAtMost(999L) ?: 0L
        val minor = match.groupValues[2].toLongOrNull()?.coerceAtMost(999_999L) ?: 0L
        val patch = match.groupValues[3].toLongOrNull()?.coerceAtMost(999_999L) ?: 0L
        return major * 1_000_000_000_000L + minor * 1_000_000L + patch
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(apiKey: String): List<GeminiModel> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AiException("Enter your Gemini API key first")

        val models = LinkedHashMap<String, GeminiModel>()
        var pageToken: String? = null
        var pages = 0

        while (pages < MAX_PAGES) {
            pages++
            val url = buildString {
                append("https://generativelanguage.googleapis.com/v1beta/models")
                append("?pageSize=").append(PAGE_SIZE)
                append("&key=").append(apiKey)
                pageToken?.let { append("&pageToken=").append(it) }
            }

            val request = Request.Builder().url(url).get().build()
            val next: String? = http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw AiException("Gemini models HTTP ${response.code}: ${body.take(200)}")
                }
                val json = JSONObject(body)
                val arr = json.optJSONArray("models") ?: org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val name = m.optString("name").removePrefix("models/")
                    if (name.isEmpty()) continue
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    val supports = methods != null && (0 until methods.length()).any {
                        methods.optString(it) == "generateContent"
                    }
                    if (!supports) continue
                    models[name] = GeminiModel(
                        id = name,
                        displayName = m.optString("displayName").ifBlank { name }
                    )
                }
                json.optString("pageToken").ifBlank { null }
            }

            if (next.isNullOrBlank()) break
            pageToken = next
        }

        if (models.isEmpty()) throw AiException("No Gemini models returned for this key")
        models.values.sortedBy { it.displayName.lowercase() }
    }
}
