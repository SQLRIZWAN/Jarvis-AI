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

/**
 * Single source of truth for microphone ownership + audio focus.
 *
 * Why this exists: SpeechRecognizer (wake-word loop), GeminiLiveAudioEngine
 * (duplex call audio) and SpeechCapture (one-shot) all want the mic. When two
 * of them record at once the second AudioRecord fails or the first one keeps
 * re-opening forever -> the classic "mic frozen / infinite reopen loop".
 *
 * Rules enforced here:
 *  - EXACTLY ONE owner may hold the mic at any time (exclusive, idempotent).
 *  - Audio focus is requested with AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE while
 *    recording and abandoned on release (never leaks).
 *  - All acquire/release paths are crash-safe: a failing OEM audio service
 *    can never throw out of this controller and kill the calling service.
 *  - Call mode flips AudioManager mode between MODE_IN_COMMUNICATION and
 *    MODE_NORMAL so voice traffic routes to the call stream / Bluetooth.
 */
object AudioManagerController {

    private const val TAG = "AudioMgrCtrl"

    enum class MicOwner { NONE, STT, GEMINI_LIVE, CALL_CAPTURE }

    private val _micOwner = MutableStateFlow(MicOwner.NONE)
    val micOwner: StateFlow<MicOwner> = _micOwner.asStateFlow()

    @Volatile private var audioManager: AudioManager? = null
    @Volatile private var focusRequest: AudioFocusRequest? = null
    @Volatile private var callMode = false

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
            if (current == MicOwner.STT &&
                (owner == MicOwner.GEMINI_LIVE || owner == MicOwner.CALL_CAPTURE)
            ) {
                // Voice-call paths preempt the background wake-word loop; the
                // STT owner observes the owner change and cancels its session.
                _micOwner.value = owner
                Log.w(TAG, "Mic preempted: STT -> ${owner.name}")
                requestFocus(context)
                return true
            }
            if (current != MicOwner.NONE) {
                Log.w(TAG, "Mic busy (${current.name}) - denying ${owner.name}")
                return false
            }
            _micOwner.value = owner
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
        }
        abandonFocus()
        Log.d(TAG, "Mic released by ${owner.name}")
    }

    /** Convenience: does [owner] currently hold the mic? */
    fun ownsMic(owner: MicOwner): Boolean = _micOwner.value == owner

    /** True when the mic is free for [owner] to start recording. */
    fun canStartRecording(context: Context, owner: MicOwner): Boolean {
        val current = _micOwner.value
        if (current == MicOwner.NONE) return acquireMic(context, owner)
        return current == owner
    }

    /** Force-release whatever holds the mic (call/STT handover, crash recovery). */
    fun forceRelease() {
        val previous = synchronized(this) {
            val p = _micOwner.value
            _micOwner.value = MicOwner.NONE
            p
        }
        if (previous != MicOwner.NONE) {
            abandonFocus()
            Log.w(TAG, "Mic force-released from ${previous.name}")
        }
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

    /** Mute/unmute the mic at the system level (used while the model speaks). */
    fun setMicMuted(context: Context, muted: Boolean) {
        try {
            am(context)?.isMicrophoneMute = muted
        } catch (e: Exception) {
            Log.w(TAG, "setMicMuted failed", e)
        }
    }
}
