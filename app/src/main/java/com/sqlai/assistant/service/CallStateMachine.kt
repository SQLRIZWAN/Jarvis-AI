package com.sqlai.assistant.service

import android.util.Log
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FEATURE 3 - explicit call state machine:
 *
 *   IDLE -> DIALING -> RINGING -> CONNECTED -> SPEAKING -> ENDED -> IDLE
 *
 * Rules enforced app-wide:
 *  - TTS / spoken call message is allowed ONLY in CONNECTED or SPEAKING.
 *  - During DIALING/RINGING the normal speaker queue is DROPPED (nothing
 *    blasts out of the earpiece/speaker while the call is being set up).
 *  - On ENDED the pending speech queue is flushed so a queued message can
 *    NEVER play after the call has ended (the "spoke after call" bug).
 *  - No UI navigation should happen in CONNECTED/SPEAKING (the agent is
 *    blocked inside the wa_call step while these states are active).
 */
object CallStateMachine {

    enum class State { IDLE, DIALING, RINGING, CONNECTED, SPEAKING, ENDED }

    private const val TAG = "CallStateMachine"

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    fun current(): State = _state.value

    /** True while a call session owns the audio routes. */
    fun isCallActive(): Boolean = when (_state.value) {
        State.IDLE, State.ENDED -> false
        else -> true
    }

    /** True only in CONNECTED / SPEAKING - the ONLY states where TTS fires. */
    fun isTtsAllowed(): Boolean = when (_state.value) {
        State.CONNECTED, State.SPEAKING -> true
        else -> false
    }

    /**
     * Transition with logging. Illegal jumps (e.g. CONNECTED -> DIALING)
     * are ignored so a stale poll can never rewind the machine.
     */
    fun to(next: State) {
        val prev = _state.value
        if (prev == next) return
        val legal = when (next) {
            State.IDLE -> prev == State.ENDED || prev == State.IDLE
            State.DIALING -> prev == State.IDLE
            State.RINGING -> prev == State.DIALING || prev == State.IDLE
            State.CONNECTED -> prev == State.DIALING || prev == State.RINGING
            State.SPEAKING -> prev == State.CONNECTED
            State.ENDED -> prev != State.IDLE
        }
        if (!legal) {
            Log.d(TAG, "ignored illegal $prev -> $next")
            return
        }
        _state.value = next
        LogBus.log("Call state: $prev -> $next", LogLevel.INFO)
        if (next == State.ENDED) {
            // BUG #1/#5: nothing queued before the call may play afterwards.
            com.sqlai.assistant.engine.Speaker.flushQueued()
        }
    }

    /** Hard reset (new task / error recovery). */
    fun reset() {
        if (_state.value != State.IDLE) {
            _state.value = State.IDLE
            com.sqlai.assistant.engine.Speaker.flushQueued()
        }
    }
}
