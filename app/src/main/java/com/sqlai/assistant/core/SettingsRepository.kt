package com.sqlai.assistant.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sqlAiDataStore: DataStore<Preferences> by preferencesDataStore(name = "sql_ai_settings")

/**
 * Free / open LLM providers supported out of the box.
 * GEMINI speaks its own REST dialect, everything else is OpenAI-compatible.
 */
enum class AiProvider(val label: String, val defaultModel: String, val baseUrl: String, val keyHint: String) {
    GROQ(
        label = "Groq (Free)",
        defaultModel = "llama-3.1-8b-instant",
        baseUrl = "https://api.groq.com/openai/v1",
        keyHint = "gsk_..."
    ),
    GEMINI(
        label = "Google Gemini (Free)",
        defaultModel = "gemini-1.5-flash",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        keyHint = "AIza..."
    ),
    OPENROUTER(
        label = "OpenRouter (Free tiers)",
        defaultModel = "meta-llama/llama-3.1-8b-instruct:free",
        baseUrl = "https://openrouter.ai/api/v1",
        keyHint = "sk-or-..."
    ),
    TOGETHER(
        label = "Together AI (Free $)",
        defaultModel = "meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo",
        baseUrl = "https://api.together.xyz/v1",
        keyHint = "..."
    ),
    HUGGINGFACE(
        label = "Hugging Face",
        defaultModel = "meta-llama/Meta-Llama-3.1-8B-Instruct",
        baseUrl = "https://router.huggingface.co/v1",
        keyHint = "hf_..."
    ),
    DEEPSEEK(
        label = "DeepSeek",
        defaultModel = "deepseek-chat",
        baseUrl = "https://api.deepseek.com/v1",
        keyHint = "sk-..."
    ),
    OLLAMA(
        label = "Ollama (Local, free)",
        defaultModel = "llama3.2",
        baseUrl = "http://10.0.2.2:11434/v1",
        keyHint = "local = any value"
    );

    companion object {
        fun fromName(name: String?): AiProvider =
            entries.firstOrNull { it.name == name } ?: GROQ
    }
}

/** Assistant output / recognition language. */
enum class AssistantLanguage(val code: String, val label: String, val sttTag: String, val ttsTag: String) {
    HINDI("hi", "Hindi (हिंदी)", "hi-IN", "hi-IN"),
    ENGLISH("en", "English", "en-IN", "en-IN"),
    HINGLISH("hinglish", "Hinglish (Hindi+English)", "en-IN", "hi-IN");

    companion object {
        fun fromCode(code: String?): AssistantLanguage =
            entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}

enum class VoiceGender(val code: String, val label: String) {
    MALE("male", "Male"),
    FEMALE("female", "Female");

    companion object {
        fun fromCode(code: String?): VoiceGender =
            entries.firstOrNull { it.code == code } ?: MALE
    }
}

data class AppSettings(
    val provider: AiProvider = AiProvider.GROQ,
    val apiKey: String = "",
    val model: String = AiProvider.GROQ.defaultModel,
    val baseUrlOverride: String = "",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val wakeWord: String = "sql",
    val assistantEnabled: Boolean = true,
    val listenServiceEnabled: Boolean = true,
    val ttsEnabled: Boolean = true,
    val screenContextEnabled: Boolean = true,
    val overlayEnabled: Boolean = true,
    val bootRestartEnabled: Boolean = true,
    val ttsSpeed: Float = 1.0f,
    // ---- v1.1 agent / automation ----
    val autoReplyEnabled: Boolean = false,
    val autoReplyTemplate: String = "Main abhi busy hoon, thodi der mein reply karunga. Dhanyavaad!",
    val callAssistantEnabled: Boolean = false,
    val voiceGender: VoiceGender = VoiceGender.MALE,
    val language: AssistantLanguage = AssistantLanguage.ENGLISH,
    val pitch: Float = 1.0f,
    val agentMaxSteps: Int = 8,
    val screenVisionEnabled: Boolean = true
) {
    fun effectiveBaseUrl(): String =
        baseUrlOverride.trim().ifEmpty { provider.baseUrl }

    fun effectiveModel(): String = model.trim().ifEmpty { provider.defaultModel }

    /** True when the configured model can accept image input. */
    fun supportsVision(): Boolean {
        if (!screenVisionEnabled) return false
        val m = effectiveModel().lowercase()
        return provider == AiProvider.GEMINI ||
            Regex("(vision|4v|llava|pixtral|vl-|multimodal)").containsMatchIn(m)
    }

    fun languageInstruction(): String = when (language) {
        AssistantLanguage.HINDI -> "Always reply in Hindi (Devanagari script)."
        AssistantLanguage.ENGLISH -> "Always reply in English."
        AssistantLanguage.HINGLISH -> "Always reply in Hinglish (Roman Hindi + English mix, like Indian street talk)."
    }

    companion object {
        val DEFAULT_SYSTEM_PROMPT = """
            You are SQL AI, a fast, precise Android phone assistant running fully on the user's device.
            The user speaks or types a command. Use the live screen context (current app, visible texts and
            buttons) plus the action list below to accomplish the command.

            ALWAYS answer with ONE raw JSON object and nothing else (no markdown fences, no commentary):
            {
              "reply": "<short spoken confirmation, max 15 words>",
              "actions": [
                {"type": "open_app", "app": "whatsapp"},
                {"type": "tap_text", "text": "Send"},
                {"type": "type_text", "text": "hello"},
                {"type": "press_key", "key": "back"}
              ]
            }

            Supported action types:
              open_app {app}                 close_app {app}
              tap_text {text}                tap {x, y}
              swipe {x1,y1,x2,y2,duration_ms} scroll {direction: up|down}
              type_text {text}               press_key {key: back|home|recents|enter}
              set_volume {value 0-15}        volume_up {}     volume_down {}
              set_brightness {value 0-255}   toggle_flashlight {on: true|false}
              toggle_wifi {}                 toggle_bluetooth {}
              open_settings {item: wifi|bluetooth|battery|display|sound|apps|accessibility}
              read_screen {}                 read_notifications {}
              wait {ms}

            Rules: pick the shortest action path, never invent text that is not on screen,
            confirm in "reply" before acting, and if the command needs no action return an empty actions array.
        """.trimIndent()

        /** System prompt for the multi-step ReAct agent loop. */
        val AGENT_SYSTEM_PROMPT = """
            You are SQL AI AGENT, an autonomous Android phone-control agent. You work in a
            THINK -> ACT -> VERIFY loop until the user's task is fully complete.

            Each turn you receive: the original task, your previous thoughts/actions and their
            verification results, plus the LIVE screen (every visible element with bounds "[x,y WxH]")
            and optionally a screenshot image.

            Reply with EXACTLY one raw JSON object:
            {
              "thought": "<your analysis of the current screen and next move>",
              "reply": "<very short spoken status, max 12 words>",
              "done": false,
              "actions": [ ...same action schema as before... ],
              "expect": {"type": "text_visible", "value": "Followers"}
            }

            Field rules:
              - thought: private reasoning, keep it sharp.
              - done: true ONLY when the user's full task is verified complete.
              - actions: the NEXT step only (1-3 actions), never the whole plan at once.
              - expect: what must be visible AFTER the actions run so you can verify progress.
                types: "text_visible" {value}, "app_foreground" {value = package name}, "none".

            Action types:
              open_app {app} close_app {app}
              tap_text {text} tap {x, y} swipe {x1,y1,x2,y2,duration_ms}
              scroll {direction: up|down} type_text {text}
              press_key {key: back|home|recents|enter}
              wait {ms} wait_for {text, ms}  (wait_for pauses until the text appears)
              set_volume {value} volume_up {} volume_down {}
              read_notifications {} open_settings {item}

            Hard rules:
              - Prefer tap_text / element bounds over blind coordinates.
              - If an action failed or expect did not verify, analyse the NEW screen and retry
                with a different approach (back, reopen, other button label).
              - For "like my latest reel": open app -> Profile tab -> first/latest reel -> tap the
                heart (text or content-description "Like"). Verify by expecting "Unlike" or "Liked".
              - Never ask the user for anything you can find on screen.
              - When done, set done=true with empty actions and a final reply.
        """.trimIndent()
    }
}

class SettingsRepository(private val context: Context) {

    private val dataStore = context.sqlAiDataStore

    private object Keys {
        val PROVIDER = stringPreferencesKey("provider")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val BASE_URL = stringPreferencesKey("base_url")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val WAKE_WORD = stringPreferencesKey("wake_word")
        val ASSISTANT_ENABLED = booleanPreferencesKey("assistant_enabled")
        val LISTEN_SERVICE = booleanPreferencesKey("listen_service")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val SCREEN_CONTEXT = booleanPreferencesKey("screen_context")
        val OVERLAY = booleanPreferencesKey("overlay")
        val BOOT_RESTART = booleanPreferencesKey("boot_restart")
        val TTS_SPEED = intPreferencesKey("tts_speed_x100")
        val AUTO_REPLY = booleanPreferencesKey("auto_reply")
        val AUTO_REPLY_TEMPLATE = stringPreferencesKey("auto_reply_template")
        val CALL_ASSISTANT = booleanPreferencesKey("call_assistant")
        val VOICE_GENDER = stringPreferencesKey("voice_gender")
        val LANGUAGE = stringPreferencesKey("language")
        val PITCH = intPreferencesKey("pitch_x100")
        val AGENT_STEPS = intPreferencesKey("agent_max_steps")
        val SCREEN_VISION = booleanPreferencesKey("screen_vision")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { p ->
        val provider = AiProvider.fromName(p[Keys.PROVIDER])
        AppSettings(
            provider = provider,
            apiKey = p[Keys.API_KEY] ?: "",
            model = p[Keys.MODEL] ?: provider.defaultModel,
            baseUrlOverride = p[Keys.BASE_URL] ?: "",
            systemPrompt = p[Keys.SYSTEM_PROMPT] ?: AppSettings.DEFAULT_SYSTEM_PROMPT,
            wakeWord = p[Keys.WAKE_WORD] ?: "sql",
            assistantEnabled = p[Keys.ASSISTANT_ENABLED] ?: true,
            listenServiceEnabled = p[Keys.LISTEN_SERVICE] ?: true,
            ttsEnabled = p[Keys.TTS_ENABLED] ?: true,
            screenContextEnabled = p[Keys.SCREEN_CONTEXT] ?: true,
            overlayEnabled = p[Keys.OVERLAY] ?: true,
            bootRestartEnabled = p[Keys.BOOT_RESTART] ?: true,
            ttsSpeed = (p[Keys.TTS_SPEED] ?: 100) / 100f,
            autoReplyEnabled = p[Keys.AUTO_REPLY] ?: false,
            autoReplyTemplate = p[Keys.AUTO_REPLY_TEMPLATE]
                ?: AppSettings().autoReplyTemplate,
            callAssistantEnabled = p[Keys.CALL_ASSISTANT] ?: false,
            voiceGender = VoiceGender.fromCode(p[Keys.VOICE_GENDER]),
            language = AssistantLanguage.fromCode(p[Keys.LANGUAGE]),
            pitch = (p[Keys.PITCH] ?: 100) / 100f,
            agentMaxSteps = (p[Keys.AGENT_STEPS] ?: 8).coerceIn(1, 20),
            screenVisionEnabled = p[Keys.SCREEN_VISION] ?: true
        )
    }

    suspend fun setProvider(provider: AiProvider) = dataStore.edit {
        it[Keys.PROVIDER] = provider.name
        if (it[Keys.MODEL].isNullOrBlank()) it[Keys.MODEL] = provider.defaultModel
    }

    suspend fun setApiKey(value: String) = dataStore.edit { it[Keys.API_KEY] = value.trim() }

    suspend fun setModel(value: String) = dataStore.edit { it[Keys.MODEL] = value.trim() }

    suspend fun setBaseUrl(value: String) = dataStore.edit { it[Keys.BASE_URL] = value.trim() }

    suspend fun setSystemPrompt(value: String) = dataStore.edit {
        it[Keys.SYSTEM_PROMPT] = value.ifBlank { AppSettings.DEFAULT_SYSTEM_PROMPT }
    }

    suspend fun setWakeWord(value: String) = dataStore.edit {
        it[Keys.WAKE_WORD] = value.trim().lowercase().ifBlank { "sql" }
    }

    suspend fun setAssistantEnabled(value: Boolean) = dataStore.edit { it[Keys.ASSISTANT_ENABLED] = value }

    suspend fun setListenServiceEnabled(value: Boolean) = dataStore.edit { it[Keys.LISTEN_SERVICE] = value }

    suspend fun setTtsEnabled(value: Boolean) = dataStore.edit { it[Keys.TTS_ENABLED] = value }

    suspend fun setScreenContextEnabled(value: Boolean) = dataStore.edit { it[Keys.SCREEN_CONTEXT] = value }

    suspend fun setOverlayEnabled(value: Boolean) = dataStore.edit { it[Keys.OVERLAY] = value }

    suspend fun setBootRestartEnabled(value: Boolean) = dataStore.edit { it[Keys.BOOT_RESTART] = value }

    suspend fun setTtsSpeed(value: Float) = dataStore.edit { it[Keys.TTS_SPEED] = (value * 100).toInt() }

    suspend fun setAutoReplyEnabled(value: Boolean) = dataStore.edit { it[Keys.AUTO_REPLY] = value }

    suspend fun setAutoReplyTemplate(value: String) = dataStore.edit {
        it[Keys.AUTO_REPLY_TEMPLATE] = value
    }

    suspend fun setCallAssistantEnabled(value: Boolean) = dataStore.edit { it[Keys.CALL_ASSISTANT] = value }

    suspend fun setVoiceGender(value: VoiceGender) = dataStore.edit { it[Keys.VOICE_GENDER] = value.code }

    suspend fun setLanguage(value: AssistantLanguage) = dataStore.edit { it[Keys.LANGUAGE] = value.code }

    suspend fun setPitch(value: Float) = dataStore.edit { it[Keys.PITCH] = (value * 100).toInt() }

    suspend fun setAgentMaxSteps(value: Int) = dataStore.edit {
        it[Keys.AGENT_STEPS] = value.coerceIn(1, 20)
    }

    suspend fun setScreenVisionEnabled(value: Boolean) = dataStore.edit { it[Keys.SCREEN_VISION] = value }

    suspend fun resetPrompt() = dataStore.edit { it[Keys.SYSTEM_PROMPT] = AppSettings.DEFAULT_SYSTEM_PROMPT }
}
