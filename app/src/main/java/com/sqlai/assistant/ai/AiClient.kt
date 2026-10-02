package com.sqlai.assistant.ai

import android.util.Base64
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
 * Supports optional screenshot (vision) attachments for multimodal models.
 */
object AiClient {

    private const val MAX_HISTORY = 14

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun complete(
        settings: AppSettings,
        screenContext: String?,
        history: List<ChatMessage>,
        imageJpeg: ByteArray? = null,
        systemPromptOverride: String? = null
    ): String = withContext(Dispatchers.IO) {
        if (settings.apiKey.isBlank() && settings.provider != AiProvider.OLLAMA) {
            throw AiException("No API key set. Open the API tab and paste your ${settings.provider.label} key.")
        }

        try {
            execute(settings, screenContext, history, imageJpeg, systemPromptOverride)
        } catch (e: Exception) {
            // Model without image support - retry once with text only.
            if (imageJpeg != null) {
                execute(settings, screenContext, history, null, systemPromptOverride)
            } else {
                throw e
            }
        }
    }

    private fun execute(
        settings: AppSettings,
        screenContext: String?,
        history: List<ChatMessage>,
        imageJpeg: ByteArray?,
        systemPromptOverride: String?
    ): String {
        val request = when (settings.provider) {
            AiProvider.GEMINI -> geminiRequest(settings, screenContext, history, imageJpeg, systemPromptOverride)
            else -> openAiRequest(settings, screenContext, history, imageJpeg, systemPromptOverride)
        }

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw AiException("HTTP ${response.code}: ${body.take(240)}")
            }
            return extractText(settings.provider, body)
        }
    }

    /** Quick round-trip used by the "Test API" button. */
    suspend fun test(settings: AppSettings): String =
        complete(settings, null, listOf(ChatMessage("user", "Reply with exactly: OK")))

    // ---------------------------------------------------------------- helpers

    private fun systemContent(
        settings: AppSettings,
        screenContext: String?,
        systemPromptOverride: String?
    ): String {
        val sb = StringBuilder(systemPromptOverride ?: settings.systemPrompt)
        sb.append("\n\n").append(settings.languageInstruction())
        if (!screenContext.isNullOrBlank()) {
            sb.append("\n\nCURRENT SCREEN CONTEXT (live):\n").append(screenContext)
        }
        return sb.toString()
    }

    private fun imagePartOpenAi(imageJpeg: ByteArray): JSONObject {
        val encoded = Base64.encodeToString(imageJpeg, Base64.NO_WRAP)
        return JSONObject()
            .put("type", "image_url")
            .put(
                "image_url",
                JSONObject().put("url", "data:image/jpeg;base64,$encoded")
            )
    }

    private fun openAiRequest(
        settings: AppSettings,
        screenContext: String?,
        history: List<ChatMessage>,
        imageJpeg: ByteArray?,
        systemPromptOverride: String?
    ): Request {
        val messages = JSONArray()
        messages.put(
            JSONObject().put("role", "system")
                .put("content", systemContent(settings, screenContext, systemPromptOverride))
        )

        val lastUserIndex = history.indexOfLast { it.role != "assistant" }
        history.takeLast(MAX_HISTORY).forEachIndexed { offset, msg ->
            val role = if (msg.role == "assistant") "assistant" else "user"
            val absoluteIndex = history.size - minOf(MAX_HISTORY, history.size) + offset
            val isLastUser = absoluteIndex == lastUserIndex
            if (isLastUser && imageJpeg != null) {
                val content = JSONArray()
                content.put(JSONObject().put("type", "text").put("text", msg.content))
                content.put(imagePartOpenAi(imageJpeg))
                messages.put(JSONObject().put("role", role).put("content", content))
            } else {
                messages.put(JSONObject().put("role", role).put("content", msg.content))
            }
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
        history: List<ChatMessage>,
        imageJpeg: ByteArray?,
        systemPromptOverride: String?
    ): Request {
        val contents = JSONArray()
        val lastUserIndex = history.indexOfLast { it.role != "assistant" }
        val trimmed = history.takeLast(MAX_HISTORY)
        trimmed.forEachIndexed { offset, msg ->
            val role = if (msg.role == "assistant") "model" else "user"
            val absoluteIndex = history.size - trimmed.size + offset
            val isLastUser = absoluteIndex == lastUserIndex
            val parts = JSONArray()
            parts.put(JSONObject().put("text", msg.content))
            if (isLastUser && imageJpeg != null) {
                parts.put(
                    JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", Base64.encodeToString(imageJpeg, Base64.NO_WRAP))
                )
            }
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", parts)
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
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            systemContent(settings, screenContext, systemPromptOverride)
                        )
                    )
                )
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
