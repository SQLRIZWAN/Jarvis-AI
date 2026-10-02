package com.sqlai.assistant.core

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for microphone ownership + audio focus.
 *
 * v6.0: implementation moved into [AudioStreamManager] (mic ownership state
 * machine, audio focus, record-open throttle, playback yield, call audio
 * routing). This object stays as a thin facade so every existing call site
 * and the nested [MicOwner] enum keep compiling unchanged.
 *
 * Rules enforced (in AudioStreamManager):
 *  - EXACTLY ONE owner may hold the mic at any time (exclusive, idempotent).
 *  - Audio focus is requested with AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE while
 *    recording and abandoned on release (never leaks).
 *  - All acquire/release paths are crash-safe: a failing OEM audio service
 *    can never throw out of this controller and kill the calling service.
 *  - Call mode flips AudioManager mode between MODE_IN_COMMUNICATION and
 *    MODE_NORMAL so voice traffic routes to the call stream / Bluetooth.
 *  - v6.0: mic is cleanly yielded before our own TTS/Gemini playback and
 *    record-open floods are throttled instead of spinning forever.
 */
object AudioManagerController {

    private const val TAG = "AudioMgrCtrl"

    enum class MicOwner { NONE, STT, GEMINI_LIVE, CALL_CAPTURE, VOICE_NOTE }

    val micOwner: StateFlow<MicOwner>
        get() = AudioStreamManager.micOwner

    /** How long the current owner has held the mic (0 when free). */
    fun micHeldMs(): Long = AudioStreamManager.micHeldMs()

    fun acquireMic(context: Context, owner: MicOwner): Boolean =
        AudioStreamManager.acquireMic(context, owner)

    fun releaseMic(owner: MicOwner) = AudioStreamManager.releaseMic(owner)

    fun ownsMic(owner: MicOwner): Boolean = AudioStreamManager.ownsMic(owner)

    fun canStartRecording(context: Context, owner: MicOwner): Boolean =
        AudioStreamManager.canStartRecording(context, owner)

    fun forceRelease() = AudioStreamManager.forceRelease()

    // ------------------------------------------------- v6.0 new (facade) ----

    /** Clean STT hand-off before TTS / Gemini playback starts. */
    fun yieldMicForPlayback(): Boolean = AudioStreamManager.yieldMicForPlayback()

    /** ListeningService hook: cancel the recognizer session on yield. */
    fun setPlaybackYieldHook(hook: (() -> Unit)?) =
        AudioStreamManager.setPlaybackYieldHook(hook)

    /** Record-open throttle: false = in cooldown, back off. */
    fun noteRecordAttempt(): Boolean = AudioStreamManager.noteRecordAttempt()

    fun recordCooldownRemainingMs(): Long = AudioStreamManager.recordCooldownRemainingMs()

    // --------------------------------------------------------------- focus

    fun boostMediaVolume(context: Context): Int =
        AudioStreamManager.boostMediaVolume(context)

    fun restoreMediaVolume(context: Context, oldVolume: Int) =
        AudioStreamManager.restoreMediaVolume(context, oldVolume)

    // ----------------------------------------------------------- call audio

    fun enterCallAudioMode(context: Context, speakerphone: Boolean = false) =
        AudioStreamManager.enterCallAudioMode(context, speakerphone)

    fun exitCallAudioMode(context: Context) =
        AudioStreamManager.exitCallAudioMode(context)

    fun isCallMode(): Boolean = AudioStreamManager.isCallMode()

    fun verifyCallRoute(context: Context): String =
        AudioStreamManager.verifyCallRoute(context)

    fun setMicMuted(context: Context, muted: Boolean) =
        AudioStreamManager.setMicMuted(context, muted)
}
