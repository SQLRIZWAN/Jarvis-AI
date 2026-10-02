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
