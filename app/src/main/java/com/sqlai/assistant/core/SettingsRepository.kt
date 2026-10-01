package com.sqlai.assistant.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
    val ttsSpeed: Float = 1.0f
) {
    fun effectiveBaseUrl(): String =
        baseUrlOverride.trim().ifEmpty { provider.baseUrl }

    fun effectiveModel(): String = model.trim().ifEmpty { provider.defaultModel }

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
            ttsSpeed = (p[Keys.TTS_SPEED] ?: 100) / 100f
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

    suspend fun resetPrompt() = dataStore.edit { it[Keys.SYSTEM_PROMPT] = AppSettings.DEFAULT_SYSTEM_PROMPT }
}
