package com.sqlai.assistant.ai

import android.util.Base64
import com.sqlai.assistant.core.AiProvider
import com.sqlai.assistant.core.AppSettings
import com.sqlai.assistant.core.CorePromptBuilder
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * Every call walks the [ProviderPool] candidate list, so a rate-limited or
 * failing provider is cooled down and transparently failed over to the next one.
 */
object AiClient {

    private const val MAX_HISTORY = 14

    /** v7.0.3: keyless public endpoint - zero-key installs still get a brain. */
    private const val KEYLESS_URL = "https://text.pollinations.ai/openai"
    private const val KEYLESS_MODEL = "openai"

    /** Longest a call may wait for a rate-limit token before skipping a provider. */
    private const val BUCKET_WAIT_MS = 10_000L

    /** Nudge appended (as an extra user turn) when [requireJson] validation fails. */
    private const val PARSE_REPAIR_PROMPT =
        "PARSE ERROR: your previous reply was not a valid JSON plan. " +
            "Respond with EXACTLY one raw JSON object: {\"reply\":...,\"actions\":[...]} - " +
            "no prose, no markdown fences."

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Sends one chat turn and returns the assistant text.
     * Failover happens inside: every candidate of [ProviderPool.candidates] is
     * tried in order until one answers. With [requireJson] the answer is checked
     * by [isValidJsonPlan] and repaired once (same failover, one extra user turn)
     * before [AiException] `PARSE_ERROR` is thrown.
     */
    suspend fun complete(
        settings: AppSettings,
        screenContext: String?,
        history: List<ChatMessage>,
        imageJpeg: ByteArray? = null,
        systemPromptOverride: String? = null,
        requireJson: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val hasUsableKey = AiProvider.entries.any {
            it != AiProvider.OLLAMA && settings.keyFor(it).isNotBlank()
        }
        if (!hasUsableKey && settings.provider != AiProvider.OLLAMA) {
            return@withContext keylessComplete(
                settings, screenContext, history, systemPromptOverride, requireJson
            )
        }

        val text = try {
            failover(settings, screenContext, history, imageJpeg, systemPromptOverride)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!hasUsableKey) {
                return@withContext keylessComplete(
                    settings, screenContext, history, systemPromptOverride, requireJson
                )
            }
            // Model without image support - retry once with text only.
            if (imageJpeg == null) throw e
            failover(settings, screenContext, history, null, systemPromptOverride)
        }

        if (!requireJson || isValidJsonPlan(text)) return@withContext text

        val repaired = try {
            failover(settings, screenContext, history, imageJpeg, systemPromptOverride, repair = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (imageJpeg == null) throw e
            failover(settings, screenContext, history, null, systemPromptOverride, repair = true)
        }
        if (isValidJsonPlan(repaired)) repaired else throw AiException("PARSE_ERROR")
    }

    /**
     * Walks [ProviderPool.candidates] in order until one provider answers.
     * Each attempt is gated by the provider's token bucket, HTTP/transport
     * failures cool that provider down and move on, and the last error is
     * thrown once every candidate is exhausted.
     *
     * @param repair appends [PARSE_REPAIR_PROMPT] to a copy of [history]
     *               (the caller's list is never mutated).
     */
    private suspend fun failover(
        settings: AppSettings,
        screenContext: String?,
        history: List<ChatMessage>,
        imageJpeg: ByteArray?,
        systemPromptOverride: String?,
        repair: Boolean = false
    ): String {
        val messages =
            if (repair) history + ChatMessage("user", PARSE_REPAIR_PROMPT) else history
        var lastError: Exception? = null

        for (provider in ProviderPool.candidates(settings)) {
            if (System.currentTimeMillis() < ProviderPool.cooldownUntil(provider)) continue

            val bucket = ProviderPool.bucketFor(provider)
            if (bucket != null && !acquirePermit(bucket)) {
                lastError = AiException("${provider.label}: rate limited, no token within ${BUCKET_WAIT_MS}ms")
                continue
            }

            try {
                val route = routeFor(settings, provider)
                val request = when (route.provider) {
                    AiProvider.GEMINI -> geminiRequest(
                        route, settings, screenContext, messages, imageJpeg, systemPromptOverride
                    )

                    else -> openAiRequest(
                        route, settings, screenContext, messages, imageJpeg, systemPromptOverride
                    )
                }
                return executeRequest(route.provider, request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val status = (e as? HttpStatusException)?.status ?: 0
                val cooldown = ProviderPool.cooldownFor(status)
                ProviderPool.noteFailure(provider, status, System.currentTimeMillis())
                val reason = if (status != 0) "HTTP $status" else (e.message ?: e.javaClass.simpleName)
                LogBus.log(
                    "[ProviderPool] ${provider.name} $reason -> cooldown ${cooldown}ms",
                    LogLevel.WARN
                )
                lastError = e
            }
        }

        val error = lastError as? AiException
            ?: AiException(lastError?.message ?: "All providers unavailable. Try again shortly.")
        throw error
    }

    /**
     * Spins (capped by [BUCKET_WAIT_MS]) until [bucket] hands out a token.
     * Returns false when the wait times out, so the caller fails over.
     */
    private suspend fun acquirePermit(bucket: ProviderPool.Bucket): Boolean =
        withTimeoutOrNull(BUCKET_WAIT_MS) {
            var now = System.currentTimeMillis()
            while (!bucket.tryAcquire(now)) {
                delay(bucket.nextSlotIn(now).coerceIn(1L, 500L))
                now = System.currentTimeMillis()
            }
            true
        } ?: false

    /**
     * Primary keeps the user's key/URL override/model exactly as configured;
     * secondary candidates use their own key, endpoint and default model.
     */
    private fun routeFor(settings: AppSettings, provider: AiProvider): ProviderRoute =
        if (provider == settings.provider) {
            ProviderRoute(provider, settings.keyFor(provider), settings.effectiveBaseUrl(), settings.effectiveModel())
        } else {
            ProviderRoute(provider, settings.keyFor(provider), provider.baseUrl, provider.defaultModel)
        }

    private fun executeRequest(provider: AiProvider, request: Request): String {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, "HTTP ${response.code}: ${body.take(240)}")
            }
            return extractText(provider, body)
        }
    }

    /** Quick round-trip used by the "Test API" button. */
    suspend fun test(settings: AppSettings): String =
        complete(settings, null, listOf(ChatMessage("user", "Reply with exactly: OK")))

    private suspend fun keylessComplete(
        settings: AppSettings,
        screenContext: String?,
        history: List<ChatMessage>,
        systemPromptOverride: String?,
        requireJson: Boolean
    ): String {
        val reply = try {
            pollinationsRequest(settings, screenContext, history, systemPromptOverride)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AiException(
                "No API key set and the free fallback is unreachable " +
                    "(${e.message}). Add a key in the API tab for a stable connection."
            )
        }
        LogBus.log("Zero-key mode: answered via the free public model", LogLevel.SUCCESS)
        if (!requireJson || isValidJsonPlan(reply)) return reply
        val repaired = try {
            pollinationsRequest(
                settings, screenContext,
                history + ChatMessage("user", PARSE_REPAIR_PROMPT),
                systemPromptOverride
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AiException("PARSE_ERROR")
        }
        return if (isValidJsonPlan(repaired)) repaired else throw AiException("PARSE_ERROR")
    }

    private suspend fun pollinationsRequest(
        settings: AppSettings,
        screenContext: String?,
        history: List<ChatMessage>,
        systemPromptOverride: String?
    ): String = withContext(Dispatchers.IO) {
        val messages = JSONArray()
        messages.put(
            JSONObject().put("role", "system")
                .put("content", systemContent(settings, screenContext, systemPromptOverride))
        )
        history.takeLast(MAX_HISTORY).forEach { msg ->
            val role = if (msg.role == "assistant") "assistant" else "user"
            messages.put(JSONObject().put("role", role).put("content", msg.content))
        }
        val payload = JSONObject()
            .put("model", KEYLESS_MODEL)
            .put("messages", messages)
            .put("temperature", 0.35)
            .put("max_tokens", 1024)
            .put("stream", false)
        val request = Request.Builder()
            .url(KEYLESS_URL)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        executeRequest(AiProvider.GROQ, request)
    }

    // ---------------------------------------------------------------- helpers

    /**
     * True when [raw] holds a balanced JSON object that parses and carries a
     * non-null `actions` JSON array - the contract of a valid plan reply.
     */
    internal fun isValidJsonPlan(raw: String): Boolean {
        val jsonText = PlanParser.extractJsonObject(raw) ?: return false
        return try {
            JSONObject(jsonText).opt("actions") is JSONArray
        } catch (e: Exception) {
            false
        }
    }

    private fun systemContent(
        settings: AppSettings,
        screenContext: String?,
        systemPromptOverride: String?
    ): String {
        // Immutable core prompt + user context (systemPromptOverride wins for callers
        // that bring their own contract, e.g. the agent loop or auto-reply).
        val sb = StringBuilder(
            systemPromptOverride
                ?: CorePromptBuilder.action(settings)
        )
        if (systemPromptOverride != null) {
            sb.append("\n\n").append(settings.languageInstruction())
            sb.append(com.sqlai.assistant.core.CorePromptBuilder.userContext(settings))
        }
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
        route: ProviderRoute,
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
            .put("model", route.model)
            .put("messages", messages)
            .put("temperature", 0.35)
            .put("max_tokens", 1024)
            .put("stream", false)

        val url = route.baseUrl.trimEnd('/') + "/chat/completions"
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${route.apiKey}")
            .addHeader("Content-Type", "application/json")
            .apply {
                if (route.provider == AiProvider.OPENROUTER) {
                    addHeader("HTTP-Referer", "https://github.com/SQLRIZWAN/Jarvis-AI")
                    addHeader("X-Title", "SQL AI")
                }
            }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun geminiRequest(
        route: ProviderRoute,
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

        val model = route.model
        val url = "${route.baseUrl.trimEnd('/')}/models/$model:generateContent"
        return Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", route.apiKey)
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

    /** Fully resolved endpoint for one failover attempt. */
    private data class ProviderRoute(
        val provider: AiProvider,
        val apiKey: String,
        val baseUrl: String,
        val model: String
    )

    /** Non-2xx answer: carries the status so the failover loop can classify it. */
    private class HttpStatusException(val status: Int, message: String) : Exception(message)
}

/**
 * Pure failover bookkeeping: candidate order, per-provider cooldowns and
 * process-wide rate-limit buckets. No network, no Android - unit-testable.
 */
internal object ProviderPool {

    /** Fixed failover order after the user's primary provider. */
    val FALLBACK_ORDER = listOf(
        AiProvider.GEMINI,
        AiProvider.GROQ,
        AiProvider.OPENROUTER,
        AiProvider.DEEPSEEK,
        AiProvider.OLLAMA
    )

    /** Rate-limit window shared by every bucket. */
    private const val WINDOW_MS = 60_000L

    /** Providers currently cooling down, keyed by [AiProvider.name]. */
    private val cooldowns = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Shared rate-limit buckets, keyed by [AiProvider.name] so limits are global. */
    private val buckets = java.util.concurrent.ConcurrentHashMap<String, Bucket>()

    /**
     * Candidate list for [settings]: the primary provider first (always), then
     * [FALLBACK_ORDER] without it, skipping providers that have no usable key
     * (OLLAMA never needs one).
     */
    fun candidates(settings: AppSettings): List<AiProvider> {
        val primary = settings.provider
        val fallback = FALLBACK_ORDER
            .filter { it != primary }
            .filter { it == AiProvider.OLLAMA || settings.keyFor(it).isNotBlank() }
        return listOf(primary) + fallback
    }

    /** Cooldown in ms for an HTTP status: 429/5xx and unknowns -> 30s, 401/403 -> 5min. */
    fun cooldownFor(status: Int): Long = when (status) {
        401, 403 -> 300_000L
        else -> 30_000L
    }

    /**
     * Records a failure at [nowMs]: the provider is skipped until
     * [nowMs] + [cooldownFor] (pass status 0 for transport/parse failures).
     */
    fun noteFailure(provider: AiProvider, status: Int, nowMs: Long) {
        cooldowns[provider.name] = nowMs + cooldownFor(status)
    }

    /** Timestamp (epoch ms) until which [provider] must be skipped; 0 = not cooling. */
    fun cooldownUntil(provider: AiProvider): Long = cooldowns[provider.name] ?: 0L

    /**
     * Process-wide token bucket for [provider]: Gemini gets 10 permits per
     * minute, every other provider 30. The bucket is created once and shared,
     * so the limit is global for the whole process.
     */
    fun bucketFor(provider: AiProvider): Bucket? =
        buckets.computeIfAbsent(provider.name) {
            Bucket(if (provider == AiProvider.GEMINI) 10 else 30, WINDOW_MS)
        }

    /**
     * Fixed-window token bucket: [permitsPerWindow] acquisitions per [windowMs].
     * The window restarts as soon as it has fully elapsed.
     */
    class Bucket(val permitsPerWindow: Int, val windowMs: Long = 60_000L) {

        private var windowStartMs = 0L
        private var started = false
        private var used = 0

        /** Takes one permit; resets the window when it elapsed. True if one was taken. */
        @Synchronized
        fun tryAcquire(nowMs: Long): Boolean {
            rollWindow(nowMs)
            if (used >= permitsPerWindow) return false
            used++
            return true
        }

        /** Ms until a permit MAY be free (0 when one is available right now). */
        @Synchronized
        fun nextSlotIn(nowMs: Long): Long {
            rollWindow(nowMs)
            if (used < permitsPerWindow) return 0L
            return windowStartMs + windowMs - nowMs
        }

        private fun rollWindow(nowMs: Long) {
            if (!started || nowMs - windowStartMs >= windowMs) {
                windowStartMs = nowMs
                started = true
                used = 0
            }
        }
    }
}
