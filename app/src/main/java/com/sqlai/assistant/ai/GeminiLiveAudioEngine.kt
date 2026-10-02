package com.sqlai.assistant.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.core.AiProvider
import com.sqlai.assistant.core.AudioManagerController
import com.sqlai.assistant.core.AppSettings
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.VoiceGender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicLong

/**
 * Native Gemini audio output over the Live API (bidi WebSocket).
 *
 * Replaces Android TTS for spoken replies when the Gemini provider is active:
 * text goes up as client content, the model answers with streamed PCM audio
 * chunks which are played through [AudioTrack] - routed to the phone speaker
 * or the connected Bluetooth headset automatically (media attributes outside
 * calls, voice-call attributes during calls).
 *
 * Wire format: BidiGenerateContentClientMessage / ServerMessage protobuf
 * frames over `wss://generativelanguage.googleapis.com/...`. Encoding and
 * decoding are implemented by hand (varint + length-delimited fields) so no
 * protobuf dependency is needed.
 */
object GeminiLiveAudioEngine {

    private const val TAG = "GeminiLive"

    enum class LiveState { IDLE, CONNECTING, READY, SPEAKING, LISTENING, ERROR }

    private const val WS_URL =
        "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    private const val INPUT_RATE = 16000
    private const val INPUT_MIME = "audio/pcm;rate=16000"
    private const val SETUP_TIMEOUT_MS = 15_000L

    private val _state = MutableStateFlow(LiveState.IDLE)
    val state: StateFlow<LiveState> = _state.asStateFlow()

    /** Optional listener for model transcriptions / text replies. */
    var onModelText: ((String) -> Unit)? = null

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = kotlinx.coroutines.sync.Mutex()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var callMode = false

    private var activeModel = ""
    private var activeVoice = ""
    private var activeKey = ""
    private var setupDone = false

    private val turnWaiters = CopyOnWriteArrayList<CompletableDeferred<Boolean>>()
    private val playQueue = LinkedBlockingQueue<ByteArray>(512)
    private var playThread: Thread? = null
    private var micJob: Job? = null
    private var outputRate = 24000
    private var track: AudioTrack? = null
    private val micRunning = AtomicBoolean(false)

    /**
     * Monotonic mic generation. Every startMic()/stopMic() bumps it, and a
     * micLoop only runs while its captured generation is still current - so a
     * fast stop->start cycle can NEVER leave an old AudioRecord loop alive
     * alongside a new one (the double-recording freeze from v1.2).
     */
    private val micGeneration = AtomicLong(0L)

    @Volatile private var lastStreamAt = 0L

    // ------------------------------------------------------------------ public

    /** True when the Gemini Live voice can be used with the current settings. */
    fun isUsable(settings: AppSettings): Boolean =
        settings.geminiLiveVoice &&
            settings.provider == AiProvider.GEMINI &&
            settings.apiKey.isNotBlank()

    /**
     * Speak [text] with Gemini's native voice. Returns false when Live is
     * unavailable or fails, so the caller can fall back to Android TTS.
     */
    suspend fun speakText(settings: AppSettings, text: String): Boolean {
        if (text.isBlank() || !isUsable(settings)) return false
        return try {
            if (!mutex.tryLock()) return false
            try {
                if (!ensureConnected(settings)) return false
                val waiter = CompletableDeferred<Boolean>()
                turnWaiters.add(waiter)
                val socket = webSocket ?: return false
                socket.sendBytes(clientContentMessage(text))
                _state.value = LiveState.SPEAKING
                withTimeoutOrNull(8_000L + text.length * 60L) {
                    waiter.await()
                } ?: false
            } finally {
                mutex.unlock()
                _state.value = if (connected) LiveState.IDLE else LiveState.ERROR
            }
        } catch (e: Exception) {
            Log.w(TAG, "speakText failed", e)
            LogBus.log("Gemini Live voice failed: ${e.message}", LogLevel.WARN)
            false
        }
    }

    /**
     * Start streaming the microphone into the live session.
     * Idempotent, ownership-checked (AudioManagerController) and generation
     * guarded - safe to call repeatedly from call state callbacks.
     */
    fun startMic() {
        val context = try { SqlAiApp.instance } catch (e: Exception) { null } ?: return
        if (!AudioManagerController.canStartRecording(context, AudioManagerController.MicOwner.GEMINI_LIVE)) {
            LogBus.log("Mic busy (${AudioManagerController.micOwner.value.name}) - live input paused", LogLevel.WARN)
            return
        }
        val generation = micGeneration.incrementAndGet()
        if (!micRunning.compareAndSet(false, true)) {
            // Already streaming: the new generation invalidates the old loop,
            // keep the single runner.
            return
        }
        micJob = scope.launch { micLoop(generation) }
        _state.value = LiveState.LISTENING
    }

    fun stopMic() {
        micGeneration.incrementAndGet() // invalidate any running loop NOW
        micRunning.set(false)
        try {
            micJob?.cancel()
        } catch (e: Exception) {
            // Ignore.
        }
        micJob = null
        AudioManagerController.releaseMic(AudioManagerController.MicOwner.GEMINI_LIVE)
        if (_state.value == LiveState.LISTENING) _state.value = LiveState.IDLE
    }

    /**
     * Route audio through the voice-call path (phone earpiece / call Bluetooth
     * headset) and start mic streaming - used by the live call assistant.
     */
    fun enterCallMode(context: Context) {
        callMode = true
        AudioManagerController.enterCallAudioMode(context, speakerphone = false)
        recreateTrack()
        startMic()
    }

    fun exitCallMode(context: Context) {
        callMode = false
        stopMic()
        playQueue.clear()
        AudioManagerController.exitCallAudioMode(context)
        recreateTrack()
    }

    /** Drop the WebSocket + audio pipelines (app shutdown). */
    fun shutdown() {
        stopMic()
        turnWaiters.forEach { it.complete(false) }
        turnWaiters.clear()
        try {
            webSocket?.close(1000, "bye")
        } catch (e: Exception) {
            // Ignore.
        }
        webSocket = null
        connected = false
        setupDone = false
        playQueue.clear()
        playThread?.interrupt()
        playThread = null
        releaseTrack()
        _state.value = LiveState.IDLE
    }

    // -------------------------------------------------------------- connection

    private suspend fun ensureConnected(settings: AppSettings): Boolean {
        if (connected && webSocket != null && setupDone) return true
        val key = settings.apiKey
        val model = settings.effectiveModel().ifBlank { "gemini-live-2.5-flash-preview" }
        val voice = if (settings.liveVoiceName.isNotBlank()) {
            settings.liveVoiceName
        } else {
            if (settings.voiceGender == VoiceGender.FEMALE) "Kore" else "Puck"
        }
        val prompt = com.sqlai.assistant.core.CorePromptBuilder.voice(settings)

        activeKey = key
        activeModel = model
        activeVoice = voice
        setupDone = false
        _state.value = LiveState.CONNECTING

        val ok = withTimeoutOrNull(SETUP_TIMEOUT_MS) {
            openSocket(key, model, voice, prompt, settings.language.ttsTag)
        } ?: false
        if (!ok) {
            _state.value = LiveState.ERROR
            LogBus.log("Gemini Live connect failed", LogLevel.WARN)
        }
        return ok
    }

    private suspend fun openSocket(
        key: String,
        model: String,
        voice: String,
        systemPrompt: String,
        languageCode: String
    ): Boolean {
        val waiter = CompletableDeferred<Boolean>()
        turnWaiters.add(waiter)
        val request = Request.Builder().url("$WS_URL?key=$key").build()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerBytes(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Servers occasionally send plain-text errors.
                if (text.contains("error", ignoreCase = true)) {
                    Log.w(TAG, "server text: ${text.take(200)}")
                    waiter.complete(false)
                }
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.sendBytes(
                    setupMessage(model, voice, systemPrompt, languageCode)
                )
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.message}")
                connected = false
                setupDone = false
                waiter.complete(false)
                turnWaiters.forEach { it.complete(false) }
                turnWaiters.clear()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                setupDone = false
            }
        }
        webSocket = http.newWebSocket(request, listener)
        return waiter.await()
    }

    // --------------------------------------------------------- server handling

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
                                resolveWaits(true)
                                LogBus.log("Gemini Live voice ready", LogLevel.SUCCESS)
                            }
                            3 -> handleServerContent(payload)
                            4 -> LogBus.log("Gemini Live tool call (ignored)", LogLevel.WARN)
                        }
                    }
                    else -> return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parse failed", e)
        }
    }

    private fun handleServerContent(bytes: ByteArray) {
        var i = 0
        var turnComplete = false
        var interrupted = false
        while (i < bytes.size) {
            val key = readVarint(bytes, i)
            val field = (key.first shr 3).toInt()
            val wire = (key.first and 7L).toInt()
            i = key.second
            when (wire) {
                0 -> {
                    val v = readVarint(bytes, i)
                    i = v.second
                    when (field) {
                        2 -> turnComplete = v.first != 0L
                        3 -> interrupted = v.first != 0L
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
                    when (field) {
                        1 -> handleModelTurn(payload)      // model_turn
                        6, 7 -> {                          // transcriptions
                            val text = extractStringField(payload, 1)
                            if (text.isNotBlank()) onModelText?.invoke(text)
                        }
                    }
                }
                else -> return
            }
        }
        if (interrupted) {
            playQueue.clear()
            trackFlush()
        }
        if (turnComplete) {
            resolveWaits(true)
            _state.value = if (connected) LiveState.IDLE else LiveState.ERROR
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
                    when (field) {
                        2 -> { // text
                            val text = String(payload, StandardCharsets.UTF_8)
                            if (text.isNotBlank()) onModelText?.invoke(text)
                        }
                        3 -> enqueueAudio(payload) // inline_data (Blob)
                    }
                }
                else -> return
            }
        }
    }

    /** Blob { mime_type = 1, data = 2 } - extract PCM and queue for playback. */
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
        data?.let {
            if (playQueue.offer(it)) {
                ensurePlayThread()
            } else {
                Log.w(TAG, "playback queue full - dropping chunk")
            }
        }
    }

    private fun resolveWaits(ok: Boolean) {
        turnWaiters.forEach { it.complete(ok) }
        turnWaiters.clear()
    }

    // --------------------------------------------------------------- protobuf

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
            add(21, speech) // speech_config
        }.toBytes()

        val systemContent = Pb().add(1, Pb().str(2, systemPrompt).toBytes()).toBytes()

        val setup = Pb()
            .str(1, resource)
            .add(2, generation)
            .add(3, systemContent)
            .add(10, ByteArray(0)) // input_audio_transcription {}
            .add(11, ByteArray(0)) // output_audio_transcription {}

        return Pb().add(1, setup.toBytes()).toBytes() // ClientMessage.setup = 1
    }

    private fun clientContentMessage(text: String): ByteArray {
        val part = Pb().str(2, text).toBytes()
        val content = Pb().add(1, part).str(2, "user").toBytes()
        val turn = Pb().add(1, content).varintField(2, 1).toBytes()
        return Pb().add(2, turn).toBytes() // ClientMessage.client_content = 2
    }

    private fun audioMessage(pcm: ByteArray): ByteArray {
        val blob = Pb().str(1, INPUT_MIME).add(2, pcm).toBytes()
        val realtime = Pb().add(2, blob).toBytes()
        return Pb().add(3, realtime).toBytes() // ClientMessage.realtime_input = 3
    }

    /** OkHttp WebSocket only accepts String/ByteString - wrap raw protobuf bytes. */
    private fun WebSocket.sendBytes(payload: ByteArray) {
        send(okio.Buffer().write(payload).snapshot())
    }

    private class Pb {
        private val out = ByteArrayOutputStream()
        fun varintField(field: Int, value: Long): Pb {
            writeVarint(((field shl 3) or 0).toLong())
            writeVarint(value)
            return this
        }
        fun str(field: Int, value: String): Pb = add(field, value.toByteArray(StandardCharsets.UTF_8))
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

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to (i + 1)
            shift += 7
            i++
            if (shift > 63) break
        }
        return result to bytes.size
    }

    private fun extractStringField(bytes: ByteArray, target: Int): String {
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
                    if (i + len.first > bytes.size) return ""
                    val payload = bytes.copyOfRange(i, i + len.first.toInt())
                    i += len.first.toInt()
                    if (field == target) return String(payload, StandardCharsets.UTF_8)
                }
                else -> return ""
            }
        }
        return ""
    }

    // ----------------------------------------------------------------- mic

    private suspend fun micLoop(generation: Long) {
        val record = openRecordWithRetry(generation)
        if (record == null) {
            // Give up cleanly instead of re-opening forever (freeze fix).
            micRunning.set(false)
            AudioManagerController.releaseMic(AudioManagerController.MicOwner.GEMINI_LIVE)
            LogBus.log("Mic could not be opened - live input stopped", LogLevel.WARN)
            return
        }
        try {
            record.startRecording()
            val chunk = ByteArray(INPUT_RATE / 10 * 2) // 100 ms of PCM16 mono
            while (micRunning.get() && micGeneration.get() == generation) {
                var read = 0
                while (read < chunk.size &&
                    micRunning.get() &&
                    micGeneration.get() == generation
                ) {
                    val n = record.read(chunk, read, chunk.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (micGeneration.get() != generation) break
                if (read > 0) {
                    lastStreamAt = System.currentTimeMillis()
                    val ws = webSocket
                    if (ws != null && connected) {
                        ws.sendBytes(audioMessage(chunk.copyOf(read)))
                    }
                }
                // Socket gone for >10 s? Release the mic instead of idling.
                if ((!connected || webSocket == null) &&
                    System.currentTimeMillis() - lastStreamAt > 10_000
                ) {
                    LogBus.log("Live socket idle - releasing mic", LogLevel.WARN)
                    break
                }
                delay(20) // gentle pacing, avoids busy spin
            }
        } catch (e: Exception) {
            Log.w(TAG, "mic loop ended", e)
        } finally {
            try {
                record.stop()
            } catch (e: Exception) {
                // Ignore.
            }
            record.release()
            if (micGeneration.get() == generation) {
                // Natural exit - hand the mic back.
                AudioManagerController.releaseMic(AudioManagerController.MicOwner.GEMINI_LIVE)
            }
        }
    }

    /** Max 2 attempts with a pause - never an infinite reopen loop. */
    private suspend fun openRecordWithRetry(generation: Long): AudioRecord? {
        repeat(2) { attempt ->
            if (micGeneration.get() != generation) return null
            val rec = openRecord()
            if (rec != null) return rec
            if (attempt == 0) delay(500)
        }
        return null
    }

    private fun openRecord(): AudioRecord? = try {
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }
        val minBuf = AudioRecord.getMinBufferSize(
            INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            source,
            INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, INPUT_RATE)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            null
        } else {
            rec
        }
    } catch (e: Exception) {
        Log.w(TAG, "AudioRecord open failed", e)
        null
    }

    // ------------------------------------------------------------- playback

    private fun ensurePlayThread() {
        if (playThread?.isAlive == true) return
        synchronized(this) {
            if (playThread?.isAlive == true) return
            playThread = Thread({
                try {
                    playLoop()
                } catch (e: Exception) {
                    Log.w(TAG, "play thread died", e)
                }
            }, "sqlai-live-play").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun playLoop() {
        while (true) {
            val chunk = playQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
            val audioTrack = ensureTrack() ?: continue
            try {
                audioTrack.write(chunk, 0, chunk.size)
            } catch (e: Exception) {
                Log.w(TAG, "track write failed", e)
                releaseTrack()
            }
        }
    }

    private fun ensureTrack(): AudioTrack? {
        val existing = track
        if (existing != null && existing.state == AudioTrack.STATE_INITIALIZED) return existing
        return recreateTrack()
    }

    private fun recreateTrack(): AudioTrack? {
        try {
            releaseTrack()
            val rate = outputRate
            val minBuf = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val attributes = AudioAttributes.Builder()
                .setUsage(
                    if (callMode) AudioAttributes.USAGE_VOICE_COMMUNICATION
                    else AudioAttributes.USAGE_MEDIA
                )
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val created = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, rate)) // >= 0.5 s of headroom
                .build()
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                track = null
                return null
            }
            created.play()
            track = created
            return created
        } catch (e: Exception) {
            Log.w(TAG, "create track failed", e)
            track = null
            return null
        }
    }

    private fun trackFlush() {
        try {
            track?.pause()
            track?.flush()
            track?.play()
        } catch (e: Exception) {
            // Ignore.
        }
    }

    private fun releaseTrack() {
        try {
            track?.stop()
        } catch (e: Exception) {
            // Ignore.
        }
        try {
            track?.release()
        } catch (e: Exception) {
            // Ignore.
        }
        track = null
    }
}
