package com.sqlai.assistant.ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.sqlai.assistant.core.AppCrashHandler
import com.sqlai.assistant.core.AppSettings
import com.sqlai.assistant.core.AudioManagerController
import com.sqlai.assistant.core.AiProvider
import com.sqlai.assistant.core.CorePromptBuilder
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct Gemini Live WebSocket NATIVE AUDIO streamer - Android TextToSpeech
 * is bypassed entirely for normal speech.
 *
 * Hardcoded MALE voice profile (Google's "Puck", fallback "Fenrir"): the
 * assistant voice is guaranteed male regardless of what TTS engine or locale
 * voice the device would otherwise pick (the "female default TTS" bug).
 *
 * Architecture (thread-pool isolation required by the v5 spec):
 *  - AI reasoning  -> Dispatchers.Default (agent engine, never here)
 *  - audio stream  -> Dispatchers.IO (this object: WS + [playThread])
 *  - speech output -> dedicated playback thread + AudioTrack (never blocks)
 *
 * Consecutive utterances reuse one warm socket (turn-based), so each spoken
 * line costs a single `client_content` message instead of a reconnect.
 */
object GeminiMaleVoiceStreamer {

    private const val TAG = "GeminiMaleVoice"
    private const val WS_URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="

    /** Enforced male profile. */
    const val MALE_VOICE = "Puck"

    /** Alternate male profile (used on reconnect retries). */
    const val MALE_VOICE_ALT = "Fenrir"

    private const val SETUP_TIMEOUT_MS = 10_000L
    private const val STALE_SOCKET_MS = 60_000L
    private const val INPUT_MIME = "audio/pcm;rate=16000"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + AppCrashHandler.coroutineHandler)
    private val mutex = Mutex()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var setupDone = false
    @Volatile private var activeKey = ""
    @Volatile private var activeModel = ""
    @Volatile private var activeVoice = MALE_VOICE
    @Volatile private var activePrompt = ""
    @Volatile private var activeLanguage = "en-US"
    @Volatile private var lastActivity = 0L

    private val turnWaiters = CopyOnWriteArrayList<CompletableDeferred<Boolean>>()

    // ------------------------------------------------------------- playback
    private val playQueue = LinkedBlockingQueue<ByteArray>(96)
    @Volatile private var outputRate = 24000
    @Volatile private var track: AudioTrack? = null
    private var playThread: Thread? = null
    private val playing = AtomicBoolean(false)

    /**
     * v7 M6 barge-in latch: false while an interrupt drains playback. Late
     * server audio is dropped and an aborted turn is never retried; the next
     * [speak] entry re-opens the gate.
     */
    @Volatile
    private var playEnabled = true

    /** True when the male Gemini streamer can serve speech for these settings. */
    fun isUsable(settings: AppSettings): Boolean =
        settings.geminiLiveVoice &&
            settings.provider == AiProvider.GEMINI &&
            settings.apiKey.isNotBlank()

    /**
     * Speak [text] with Gemini's native male voice. Returns false only when
     * the streamer cannot deliver (caller may then try Gemini Live, then TTS).
     */
    suspend fun speak(settings: AppSettings, text: String): Boolean {
        if (text.isBlank() || !isUsable(settings)) return false
        playEnabled = true // v7 M6: open the gate for this turn
        // Never talk over an active in-call / live duplex session - the Live
        // engine owns those audio routes.
        if (AudioManagerController.isCallMode() ||
            GeminiLiveAudioEngine.state.value == GeminiLiveAudioEngine.LiveState.SPEAKING ||
            GeminiLiveAudioEngine.state.value == GeminiLiveAudioEngine.LiveState.LISTENING
        ) {
            return false
        }
        if (!mutex.tryLock()) return false
        return try {
            val first = sendTurn(settings, text, retryVoice = MALE_VOICE)
            if (first) {
                true
            } else if (!playEnabled) {
                // v7 M6: barge-in aborted this turn - NEVER retry into it.
                false
            } else {
                // One full retry: fresh socket, alternate male voice.
                closeQuietly()
                sendTurn(settings, text, retryVoice = MALE_VOICE_ALT)
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun sendTurn(settings: AppSettings, text: String, retryVoice: String): Boolean {
        activeVoice = retryVoice
        if (!ensureConnected(settings)) return false
        val waiter = CompletableDeferred<Boolean>()
        turnWaiters.add(waiter)
        val ws = socket ?: return false
        lastActivity = System.currentTimeMillis()
        val ok = try {
            ws.send(okio.Buffer().write(clientContentMessage(text)).snapshot())
            withTimeoutOrNull(8_000L + text.length * 70L) { waiter.await() } ?: false
        } catch (e: Exception) {
            LogBus.log("Male voice stream send failed: ${e.message}", LogLevel.WARN)
            false
        }
        if (!ok && playEnabled) {
            // Server never completed the turn - the socket is untrustworthy.
            // (After a barge-in the socket is fine, just aborted - keep warm.)
            closeQuietly()
        }
        return ok
    }

    // ------------------------------------------------------------ connection

    private suspend fun ensureConnected(settings: AppSettings): Boolean {
        if (connected && setupDone && socket != null) {
            if (System.currentTimeMillis() - lastActivity < STALE_SOCKET_MS) return true
            closeQuietly() // warm but stale - rebuild
        }
        activeKey = settings.apiKey
        activeModel = settings.effectiveModel().ifBlank { "gemini-live-2.5-flash-preview" }
        activePrompt = CorePromptBuilder.voice(settings)
        activeLanguage = settings.language.ttsTag
        setupDone = false

        val waiter = CompletableDeferred<Boolean>()
        setupWaiters.add(waiter) // resolved by setup_complete in handleServerBytes
        val request = Request.Builder().url("$WS_URL$activeKey").build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(okio.Buffer().write(
                    setupMessage(activeModel, activeVoice, activePrompt, activeLanguage)
                ).snapshot())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerBytes(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("error", ignoreCase = true)) {
                    LogBus.log("Male voice server error: ${text.take(160)}", LogLevel.WARN)
                    waiter.complete(false)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                LogBus.log("Male voice WS failure: ${t.message}", LogLevel.WARN)
                connected = false
                setupDone = false
                resolveSetup(false)
                waiter.complete(false)
                turnWaiters.forEach { it.complete(false) }
                turnWaiters.clear()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                setupDone = false
            }
        }
        socket = http.newWebSocket(request, listener)
        val ok = withTimeoutOrNull(SETUP_TIMEOUT_MS) { waiter.await() } ?: false
        if (ok) {
            lastActivity = System.currentTimeMillis()
            LogBus.log("Gemini male voice connected ($activeVoice)", LogLevel.SUCCESS)
        }
        return ok
    }

    private fun closeQuietly() {
        turnWaiters.forEach { it.complete(false) }
        turnWaiters.clear()
        try {
            socket?.close(1000, "done")
        } catch (e: Exception) {
            // Ignore.
        }
        socket = null
        connected = false
        setupDone = false
    }

    /**
     * v7 M6 BARGE-IN: abort the current turn and drain all queued audio so
     * the user's microphone wins immediately. The WebSocket stays warm (no
     * reconnect cost for the next line) and [playEnabled] drops any late
     * server audio for this aborted turn.
     */
    fun interruptPlayback() {
        playEnabled = false
        turnWaiters.forEach { it.complete(false) }
        turnWaiters.clear()
        playQueue.clear()
        playing.set(false)
        try {
            playThread?.interrupt()
        } catch (e: Exception) {
            // Ignore.
        }
        playThread = null
        try {
            track?.pause()
            track?.flush()
        } catch (e: Exception) {
            // Ignore.
        }
        LogBus.log("[MaleVoice] playback interrupted (barge-in)", LogLevel.INFO)
    }

    fun shutdown() {
        closeQuietly()
        playing.set(false)
        playQueue.clear()
        try {
            playThread?.interrupt()
        } catch (e: Exception) {
            // Ignore.
        }
        playThread = null
        releaseTrack()
    }

    // ------------------------------------------------------- server parsing

    private fun handleServerBytes(bytes: ByteArray) {
        try {
            var i = 0
            while (i < bytes.size) {
                val key = readVarint(bytes, i)
                val field = (key.first shr 3).toInt()
                val wire = (key.first and 7L).toInt()
                i = key.second
                when (wire) {
                    0 -> { val v = readVarint(bytes, i); i = v.second }
                    1 -> i += 8
                    5 -> i += 4
                    2 -> {
                        val len = readVarint(bytes, i)
                        i = len.second
                        if (i + len.first > bytes.size) return
                        val payload = bytes.copyOfRange(i, i + len.first.toInt())
                        i += len.first.toInt()
                        when (field) {
                            2 -> { // setup_complete
                                connected = true
                                setupDone = true
                                resolveSetup(true)
                            }
                            3 -> handleServerContent(payload)
                        }
                    }
                    else -> return
                }
            }
        } catch (e: Exception) {
            LogBus.log("Male voice parse failed: ${e.message}", LogLevel.WARN)
        }
    }

    private val setupWaiters = CopyOnWriteArrayList<CompletableDeferred<Boolean>>()

    private fun resolveSetup(ok: Boolean) {
        setupWaiters.forEach { it.complete(ok) }
        setupWaiters.clear()
    }

    private fun handleServerContent(bytes: ByteArray) {
        var i = 0
        while (i < bytes.size) {
            val key = readVarint(bytes, i)
            val field = (key.first shr 3).toInt()
            val wire = (key.first and 7L).toInt()
            i = key.second
            when (wire) {
                0 -> {
                    val v = readVarint(bytes, i)
                    i = v.second
                    if (field == 2 && v.first != 0L) { // turn_complete
                        lastActivity = System.currentTimeMillis()
                        turnWaiters.forEach { it.complete(true) }
                        turnWaiters.clear()
                    }
                }
                1 -> i += 8
                5 -> i += 4
                2 -> {
                    val len = readVarint(bytes, i)
                    i = len.second
                    if (i + len.first > bytes.size) return
                    val payload = bytes.copyOfRange(i, i + len.first.toInt())
                    i += len.first.toInt()
                    if (field == 1) handleModelTurn(payload)
                }
                else -> return
            }
        }
    }

    private fun handleModelTurn(content: ByteArray) {
        var i = 0
        while (i < content.size) {
            val key = readVarint(content, i)
            val field = (key.first shr 3).toInt()
            val wire = (key.first and 7L).toInt()
            i = key.second
            when (wire) {
                1 -> i += 8
                5 -> i += 4
                0 -> { val v = readVarint(content, i); i = v.second }
                2 -> {
                    val len = readVarint(content, i)
                    i = len.second
                    if (i + len.first > content.size) return
                    val payload = content.copyOfRange(i, i + len.first.toInt())
                    i += len.first.toInt()
                    if (field == 1) handlePart(payload)
                }
                else -> return
            }
        }
    }

    private fun handlePart(part: ByteArray) {
        var i = 0
        while (i < part.size) {
            val key = readVarint(part, i)
            val field = (key.first shr 3).toInt()
            val wire = (key.first and 7L).toInt()
            i = key.second
            when (wire) {
                0 -> { val v = readVarint(part, i); i = v.second }
                1 -> i += 8
                5 -> i += 4
                2 -> {
                    val len = readVarint(part, i)
                    i = len.second
                    if (i + len.first > part.size) return
                    val payload = part.copyOfRange(i, i + len.first.toInt())
                    i += len.first.toInt()
                    if (field == 3) enqueueAudio(payload) // inline_data
                }
                else -> return
            }
        }
    }

    /** Blob { mime_type = 1, data = 2 } -> PCM chunk for playback. */
    private fun enqueueAudio(blob: ByteArray) {
        var i = 0
        var data: ByteArray? = null
        while (i < blob.size) {
            val key = readVarint(blob, i)
            val field = (key.first shr 3).toInt()
            val wire = (key.first and 7L).toInt()
            i = key.second
            when (wire) {
                0 -> { val v = readVarint(blob, i); i = v.second }
                1 -> i += 8
                5 -> i += 4
                2 -> {
                    val len = readVarint(blob, i)
                    i = len.second
                    if (i + len.first > blob.size) return
                    val payload = blob.copyOfRange(i, i + len.first.toInt())
                    i += len.first.toInt()
                    when (field) {
                        1 -> {
                            val mime = String(payload, StandardCharsets.UTF_8)
                            val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull()
                            if (rate != null && rate != outputRate) outputRate = rate
                        }
                        2 -> data = payload
                    }
                }
                else -> return
            }
        }
        if (!playEnabled) return // v7 M6: aborted turn - drop late server audio
        data?.let {
            if (playQueue.offer(it)) ensurePlayThread()
        }
    }

    // ------------------------------------------------------------ playback

    private fun ensurePlayThread() {
        if (playing.compareAndSet(false, true)) {
            playThread = Thread(::playLoop, "sqlai-male-voice-play").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun playLoop() {
        try {
            while (playing.get()) {
                val chunk = try {
                    playQueue.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                } ?: continue
                val t = ensureTrack() ?: continue
                try {
                    t.write(chunk, 0, chunk.size)
                    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                } catch (e: Exception) {
                    LogBus.log("Male voice playback error: ${e.message}", LogLevel.WARN)
                }
            }
        } finally {
            playing.set(false)
            try {
                track?.pause()
                track?.flush()
            } catch (e: Exception) {
                // Ignore.
            }
        }
    }

    private fun ensureTrack(): AudioTrack? {
        val existing = track
        if (existing != null) return existing
        synchronized(this) {
            track?.let { return it }
            val rate = outputRate
            val minBuf = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val built = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes((minBuf * 2).coerceAtLeast(8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = built
            return built
        }
    }

    private fun releaseTrack() {
        synchronized(this) {
            try {
                track?.release()
            } catch (e: Exception) {
                // Ignore.
            }
            track = null
        }
    }

    // ------------------------------------------------------------- protobuf

    private fun setupMessage(
        model: String,
        voice: String,
        systemPrompt: String,
        languageCode: String
    ): ByteArray {
        val resource = if (model.startsWith("models/")) model else "models/$model"
        val generation = Pb().apply {
            varintField(20, 3) // response_modalities = AUDIO
            val prebuilt = Pb().str(1, voice).toBytes()
            val voiceConfig = Pb().add(1, prebuilt).toBytes()
            val speech = Pb().add(1, voiceConfig).str(2, languageCode).toBytes()
            add(21, speech) // speech_config (MALE voice profile)
        }.toBytes()
        val systemContent = Pb().add(1, Pb().str(2, systemPrompt).toBytes()).toBytes()
        val setup = Pb()
            .str(1, resource)
            .add(2, generation)
            .add(3, systemContent)
            .add(11, ByteArray(0)) // output_audio_transcription {}
        return Pb().add(1, setup.toBytes()).toBytes()
    }

    private fun clientContentMessage(text: String): ByteArray {
        val part = Pb().str(2, text).toBytes()
        val content = Pb().add(1, part).str(2, "user").toBytes()
        val turn = Pb().add(1, content).varintField(2, 1).toBytes()
        return Pb().add(2, turn).toBytes()
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 63) break
        }
        return result to i
    }

    private class Pb {
        private val out = ByteArrayOutputStream()

        fun varintField(field: Int, value: Long): Pb {
            writeVarint(((field shl 3) or 0).toLong())
            writeVarint(value)
            return this
        }

        fun str(field: Int, value: String): Pb =
            add(field, value.toByteArray(StandardCharsets.UTF_8))

        fun add(field: Int, payload: ByteArray): Pb {
            writeVarint(((field shl 3) or 2).toLong())
            writeVarint(payload.size.toLong())
            out.write(payload)
            return this
        }

        private fun writeVarint(v: Long) {
            var value = v
            while (true) {
                val b = (value and 0x7F).toByte()
                value = value ushr 7
                if (value == 0L) {
                    out.write(b.toInt())
                    return
                }
                out.write((b.toInt() or 0x80))
            }
        }

        fun toBytes(): ByteArray = out.toByteArray()
    }
}
