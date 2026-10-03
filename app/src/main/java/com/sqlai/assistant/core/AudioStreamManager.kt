package com.sqlai.assistant.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private typealias MicOwner = AudioManagerController.MicOwner

/**
 * v6.0 - single source of truth for microphone ownership, audio focus,
 * record-open throttling and call audio routing.
 *
 * [AudioManagerController] is a thin facade over this object, so every
 * existing call site (and the nested `MicOwner` enum) keeps compiling
 * unchanged while the real state machine lives here.
 *
 * Why the mic freezes without this:
 *  - SpeechRecognizer (wake loop), GeminiLiveAudioEngine (duplex) and
 *    SpeechCapture (one-shot) all open an AudioRecord. Two openers racing
 *    means the loser retries forever -> classic infinite-reopen lockup.
 *  - Playing our own TTS while the wake-loop still owns the mic makes the
 *    recognizer hear us, latch onto garbage and never re-arm cleanly.
 *
 * v6.0 additions over the v5.4 controller:
 *  - yieldMicForPlayback(): clean STT hand-off BEFORE TTS / Gemini playback
 *    starts (ListeningService hook cancels the recognizer session; the
 *    existing 300 ms fastRearm re-arms after speech ends).
 *  - noteRecordAttempt(): cross-subsystem open throttle - max 6 attempts per
 *    10 s then a 10 s cooldown, killing reopen floods dead.
 */
object AudioStreamManager {

    private const val TAG = "AudioStreamMgr"

    private const val RECORD_WINDOW_MS = 10_000L
    private const val RECORD_MAX_ATTEMPTS = 6
    private const val RECORD_COOLDOWN_MS = 10_000L

    private val _micOwner = MutableStateFlow(MicOwner.NONE)
    val micOwner: StateFlow<MicOwner> = _micOwner.asStateFlow()

    @Volatile private var micSince = 0L
    @Volatile private var audioManager: AudioManager? = null
    @Volatile private var focusRequest: AudioFocusRequest? = null
    @Volatile private var callMode = false

    private val throttleLock = Any()
    private var attempts = 0
    private var windowStart = 0L
    private var cooldownUntil = 0L

    @Volatile private var yieldHook: (() -> Unit)? = null

    /** How long the current owner has held the mic (0 when free). */
    fun micHeldMs(): Long {
        val since = micSince
        return if (since == 0L || _micOwner.value == MicOwner.NONE) 0L
        else System.currentTimeMillis() - since
    }

    private fun am(context: Context): AudioManager? {
        audioManager?.let { return it }
        synchronized(this) {
            audioManager?.let { return it }
            audioManager = try {
                context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            } catch (e: Exception) {
                Log.w(TAG, "AudioManager unavailable", e)
                null
            }
        }
        return audioManager
    }

    // ------------------------------------------------------------------- mic

    /**
     * Try to take exclusive microphone ownership for [owner].
     * Returns true when the caller now owns the mic (or already did).
     * Never throws.
     */
    fun acquireMic(context: Context, owner: MicOwner): Boolean {
        if (owner == MicOwner.NONE) return false
        synchronized(this) {
            val current = _micOwner.value
            if (current == owner) return true
            if ((current == MicOwner.STT || current == MicOwner.VOSK) &&
                (owner == MicOwner.GEMINI_LIVE ||
                    owner == MicOwner.CALL_CAPTURE ||
                    owner == MicOwner.VOICE_NOTE)
            ) {
                // Voice-call paths preempt the background wake loops (STT
                // recognizer AND the v7 always-on Vosk engine); the owner
                // observes the change and cancels its session.
                _micOwner.value = owner
                micSince = System.currentTimeMillis()
                Log.w(TAG, "Mic preempted: ${current.name} -> ${owner.name}")
                requestFocus(context)
                return true
            }
            if (current != MicOwner.NONE) {
                Log.w(TAG, "Mic busy (${current.name}) - denying ${owner.name}")
                return false
            }
            _micOwner.value = owner
            micSince = System.currentTimeMillis()
        }
        requestFocus(context)
        Log.d(TAG, "Mic acquired by ${owner.name}")
        return true
    }

    /** Release the mic when (and only when) [owner] currently holds it. */
    fun releaseMic(owner: MicOwner) {
        synchronized(this) {
            if (_micOwner.value != owner) return
            _micOwner.value = MicOwner.NONE
            micSince = 0L
        }
        abandonFocus()
        Log.d(TAG, "Mic released by ${owner.name}")
    }

    /** Convenience: does [owner] currently hold the mic? */
    fun ownsMic(owner: MicOwner): Boolean = _micOwner.value == owner

    /** True when the mic is free for [owner] to start recording. */
    fun canStartRecording(context: Context, owner: MicOwner): Boolean {
        val current = _micOwner.value
        if (current == owner) return true
        // Delegates to acquireMic so the STT -> voice-call preemption rule
        // applies here too (was: GEMINI_LIVE/CALL_CAPTURE silently denied
        // while the wake-word loop held the mic -> call duplex never heard).
        return acquireMic(context, owner)
    }

    /** Force-release whatever holds the mic (call/STT handover, crash recovery). */
    fun forceRelease() {
        val previous = synchronized(this) {
            val p = _micOwner.value
            _micOwner.value = MicOwner.NONE
            micSince = 0L
            p
        }
        if (previous != MicOwner.NONE) {
            abandonFocus()
            Log.w(TAG, "Mic force-released from ${previous.name}")
        }
    }

    // ------------------------------------------------------- playback yield

    /**
     * v6.0 - clean mic hand-off before WE play audio (TTS or Gemini live
     * playback). Releases only an STT-held mic: call/voice-note owners keep
     * it because they are actively recording. The [yieldHook] lets
     * ListeningService cancel its recognizer session so ownership bookkeeping
     * stays honest; fastRearm/watchdog re-arm the mic after speech ends.
     */
    fun yieldMicForPlayback(): Boolean {
        synchronized(this) {
            if (_micOwner.value != MicOwner.STT) return false
            _micOwner.value = MicOwner.NONE
            micSince = 0L
        }
        abandonFocus()
        Log.i(TAG, "Mic yielded for playback (STT released)")
        try {
            yieldHook?.invoke()
        } catch (t: Throwable) {
            Log.w(TAG, "yield hook failed", t)
        }
        return true
    }

    /** ListeningService registers here to cancel its session on yield. */
    fun setPlaybackYieldHook(hook: (() -> Unit)?) {
        yieldHook = hook
    }

    // -------------------------------------------------- record-open throttle

    /**
     * v6.0 - call before every AudioRecord / SpeechRecognizer open.
     * Returns false while in cooldown: caller must back off instead of
     * hammering the audio service (the classic mic-reopen lockup).
     */
    fun noteRecordAttempt(): Boolean {
        synchronized(throttleLock) {
            val now = System.currentTimeMillis()
            if (now < cooldownUntil) return false
            if (now - windowStart > RECORD_WINDOW_MS) {
                windowStart = now
                attempts = 0
            }
            attempts++
            if (attempts > RECORD_MAX_ATTEMPTS) {
                cooldownUntil = now + RECORD_COOLDOWN_MS
                attempts = 0
                Log.w(TAG, "record-open flood - ${RECORD_COOLDOWN_MS}ms cooldown")
                LogBus.log(
                    "[MIC] record-open flood - ${RECORD_COOLDOWN_MS}ms cooldown",
                    LogLevel.WARN
                )
                return false
            }
            return true
        }
    }

    /** Remaining cooldown in ms (0 when the throttle is open). */
    fun recordCooldownRemainingMs(): Long {
        val rem = cooldownUntil - System.currentTimeMillis()
        return if (rem > 0) rem else 0L
    }

    // --------------------------------------------------------------- focus

    private fun requestFocus(context: Context) {
        try {
            val manager = am(context) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { change ->
                        if (change == AudioManager.AUDIOFOCUS_LOSS ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        ) {
                            Log.w(TAG, "Audio focus lost (${_micOwner.value.name})")
                        }
                    }
                    .build()
                focusRequest = request
                manager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    { },
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestAudioFocus failed", e)
        }
    }

    private fun abandonFocus() {
        try {
            val manager = audioManager ?: return
            val request = focusRequest
            if (request != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.abandonAudioFocusRequest(request)
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus { }
            }
            focusRequest = null
        } catch (e: Exception) {
            Log.w(TAG, "abandonAudioFocus failed", e)
        }
    }

    // ------------------------------------------------- voice-note recording

    /**
     * G3 - temporarily raise media volume so WhatsApp's mic clearly picks up
     * the TTS being spoken into the voice note. Returns the previous volume
     * (-1 when unavailable) for [restoreMediaVolume].
     */
    fun boostMediaVolume(context: Context): Int {
        return try {
            val m = am(context) ?: return -1
            val cur = m.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = m.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (max * 7 / 10).coerceAtLeast(cur)
            if (target != cur) m.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            cur
        } catch (e: Exception) {
            Log.w(TAG, "boostMediaVolume failed", e)
            -1
        }
    }

    fun restoreMediaVolume(context: Context, oldVolume: Int) {
        if (oldVolume < 0) return
        try {
            am(context)?.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0)
        } catch (e: Exception) {
            Log.w(TAG, "restoreMediaVolume failed", e)
        }
    }

    // ----------------------------------------------------------- call audio

    /**
     * Route audio through the phone-call stream (earpiece / call Bluetooth
     * headset). Safe to call repeatedly; never throws.
     */
    fun enterCallAudioMode(context: Context, speakerphone: Boolean = false) {
        try {
            callMode = true
            val manager = am(context) ?: return
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            manager.isSpeakerphoneOn = speakerphone
        } catch (e: Exception) {
            Log.w(TAG, "enterCallAudioMode failed", e)
        }
    }

    fun exitCallAudioMode(context: Context) {
        try {
            callMode = false
            val manager = am(context) ?: return
            manager.isSpeakerphoneOn = false
            manager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.w(TAG, "exitCallAudioMode failed", e)
        }
    }

    fun isCallMode(): Boolean = callMode

    /**
     * BUG #2 - route diagnostics proving where call audio is going:
     * "in-comm|normal / earpiece|speaker / vol=N". Logged before+after every
     * on-call message so a failed uplink is visible instead of silent.
     */
    fun verifyCallRoute(context: Context): String {
        return try {
            val m = am(context) ?: return "no-audio-manager"
            val mode = when (m.mode) {
                AudioManager.MODE_IN_COMMUNICATION -> "in-comm"
                AudioManager.MODE_NORMAL -> "normal"
                AudioManager.MODE_IN_CALL -> "in-call"
                else -> "mode=${m.mode}"
            }
            val out = if (m.isSpeakerphoneOn) "speaker" else "earpiece"
            val vol = try {
                m.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            } catch (e: Exception) {
                -1
            }
            "$mode/$out/vol=$vol"
        } catch (e: Exception) {
            "route-error:${e.message}"
        }
    }

    /** Mute/unmute the mic at the system level (used while the model speaks). */
    fun setMicMuted(context: Context, muted: Boolean) {
        try {
            am(context)?.isMicrophoneMute = muted
        } catch (e: Exception) {
            Log.w(TAG, "setMicMuted failed", e)
        }
    }
}
