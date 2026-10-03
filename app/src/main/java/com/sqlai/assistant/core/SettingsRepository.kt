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
    val agentMaxSteps: Int = 0,
    val screenVisionEnabled: Boolean = true,
    // ---- v1.2 : user memory / native voice ----
    val userName: String = "",
    val userPreferences: String = "",
    val userInstructions: String = "",
    val geminiLiveVoice: Boolean = true,
    val liveVoiceName: String = "",
    val liveModel: String = "gemini-live-2.5-flash-preview",
    // ---- v7 M3 : on-device wake engine ----
    val wakeEngine: String = "vosk",
    val wakeModelLang: String = "en"
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
        /** Immutable core prompts - kept in CorePromptBuilder so they can never drift. */
        val DEFAULT_SYSTEM_PROMPT: String
            get() = CorePromptBuilder.CORE_ACTION

        val AGENT_SYSTEM_PROMPT: String
            get() = CorePromptBuilder.CORE_AGENT
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
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_PREFERENCES = stringPreferencesKey("user_preferences")
        val USER_INSTRUCTIONS = stringPreferencesKey("user_instructions")
        val GEMINI_LIVE_VOICE = booleanPreferencesKey("gemini_live_voice")
        val LIVE_VOICE_NAME = stringPreferencesKey("live_voice_name")
        val LIVE_MODEL = stringPreferencesKey("live_model")
        val WAKE_ENGINE = stringPreferencesKey("wake_engine")
        val WAKE_MODEL_LANG = stringPreferencesKey("wake_model_lang")
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
            agentMaxSteps = (p[Keys.AGENT_STEPS] ?: 0).coerceIn(0, 20),
            screenVisionEnabled = p[Keys.SCREEN_VISION] ?: true,
            userName = p[Keys.USER_NAME] ?: "",
            userPreferences = p[Keys.USER_PREFERENCES] ?: "",
            userInstructions = p[Keys.USER_INSTRUCTIONS] ?: "",
            geminiLiveVoice = p[Keys.GEMINI_LIVE_VOICE] ?: true,
            liveVoiceName = p[Keys.LIVE_VOICE_NAME] ?: "",
            liveModel = p[Keys.LIVE_MODEL] ?: "gemini-live-2.5-flash-preview",
            wakeEngine = p[Keys.WAKE_ENGINE] ?: "vosk",
            wakeModelLang = p[Keys.WAKE_MODEL_LANG] ?: "en"
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
        it[Keys.AGENT_STEPS] = value.coerceIn(0, 20)
    }

    suspend fun setScreenVisionEnabled(value: Boolean) = dataStore.edit { it[Keys.SCREEN_VISION] = value }

    suspend fun setUserName(value: String) = dataStore.edit { it[Keys.USER_NAME] = value }

    suspend fun setUserPreferences(value: String) = dataStore.edit { it[Keys.USER_PREFERENCES] = value }

    suspend fun setUserInstructions(value: String) = dataStore.edit { it[Keys.USER_INSTRUCTIONS] = value }

    suspend fun setGeminiLiveVoice(value: Boolean) = dataStore.edit { it[Keys.GEMINI_LIVE_VOICE] = value }

    suspend fun setLiveVoiceName(value: String) = dataStore.edit { it[Keys.LIVE_VOICE_NAME] = value }

    suspend fun setLiveModel(value: String) = dataStore.edit { it[Keys.LIVE_MODEL] = value.trim() }

    /** v7 M3: "vosk" (offline wake, auto-fallback) or "speech" (old STT loop). */
    suspend fun setWakeEngine(value: String) = dataStore.edit {
        it[Keys.WAKE_ENGINE] = if (value.equals("speech", ignoreCase = true)) "speech" else "vosk"
    }

    /** v7 M3: Vosk acoustic model language - "en" or "hi". */
    suspend fun setWakeModelLang(value: String) = dataStore.edit {
        it[Keys.WAKE_MODEL_LANG] = if (value.equals("hi", ignoreCase = true)) "hi" else "en"
    }

    suspend fun resetPrompt() = dataStore.edit { it[Keys.SYSTEM_PROMPT] = AppSettings.DEFAULT_SYSTEM_PROMPT }
}
