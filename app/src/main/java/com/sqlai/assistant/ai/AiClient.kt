package com.sqlai.assistant.ai

import com.sqlai.assistant.core.AiProvider
import com.sqlai.assistant.core.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChatMessage(val role: String, val content: String)

class AiException(message: String) : Exception(message)

/**
 * Unified chat handler for every supported provider.
 * GEMINI uses its native REST dialect, all other providers are OpenAI-compatible.
 */
object AiClient {

    private const val MAX_HISTORY = 12

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun complete(
        settings: AppSettings,
        screenContext: String?,
        history: List<ChatMessage>
    ): String = withContext(Dispatchers.IO) {
        if (settings.apiKey.isBlank() && settings.provider != AiProvider.OLLAMA) {
            throw AiException("No API key set. Open the API tab and paste your ${settings.provider.label} key.")
        }

        val request = when (settings.provider) {
            AiProvider.GEMINI -> geminiRequest(settings, screenContext, history)
            else -> openAiRequest(settings, screenContext, history)
        }

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw AiException("HTTP ${response.code}: ${body.take(240)}")
            }
            extractText(settings.provider, body)
        }
    }

    /** Quick round-trip used by the "Test API" button. */
    suspend fun test(settings: AppSettings): String =
        complete(settings, null, listOf(ChatMessage("user", "Reply with exactly: OK")))

    // ---------------------------------------------------------------- helpers

    private fun systemContent(settings: AppSettings, screenContext: String?): String {
        val sb = StringBuilder(settings.systemPrompt)
        if (!screenContext.isNullOrBlank()) {
            sb.append("\n\nCURRENT SCREEN CONTEXT (live):\n").append(screenContext)
        }
        return sb.toString()
    }

    private fun openAiRequest(
        settings: AppSettings,
        screenContext: String?,
        history: List<ChatMessage>
    ): Request {
        val messages = JSONArray()
        messages.put(
            JSONObject().put("role", "system").put("content", systemContent(settings, screenContext))
        )
        history.takeLast(MAX_HISTORY).forEach { msg ->
            val role = if (msg.role == "assistant") "assistant" else "user"
            messages.put(JSONObject().put("role", role).put("content", msg.content))
        }

        val payload = JSONObject()
            .put("model", settings.effectiveModel())
            .put("messages", messages)
            .put("temperature", 0.35)
            .put("max_tokens", 1024)
            .put("stream", false)

        val url = settings.effectiveBaseUrl().trimEnd('/') + "/chat/completions"
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .apply {
                if (settings.provider == AiProvider.OPENROUTER) {
                    addHeader("HTTP-Referer", "https://github.com/SQLRIZWAN/Jarvis-AI")
                    addHeader("X-Title", "SQL AI")
                }
            }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun geminiRequest(
        settings: AppSettings,
        screenContext: String?,
        history: List<ChatMessage>
    ): Request {
        val contents = JSONArray()
        history.takeLast(MAX_HISTORY).forEach { msg ->
            val role = if (msg.role == "assistant") "model" else "user"
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", msg.content)))
            )
        }
        if (contents.length() == 0) {
            val last = history.lastOrNull()?.content ?: "Hello"
            contents.put(
                JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", last)))
            )
        }

        val payload = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemContent(settings, screenContext))))
            )
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject().put("temperature", 0.35).put("maxOutputTokens", 1024)
            )

        val model = settings.effectiveModel()
        val url = "${settings.effectiveBaseUrl().trimEnd('/')}/models/$model:generateContent"
        return Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", settings.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun extractText(provider: AiProvider, body: String): String {
        try {
            val json = JSONObject(body)
            val text = when (provider) {
                AiProvider.GEMINI ->
                    json.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")

                else ->
                    json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
            }
            return text.trim()
        } catch (e: Exception) {
            throw AiException("Unexpected API response: ${body.take(240)}")
        }
    }
}
