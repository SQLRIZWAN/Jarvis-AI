package com.sqlai.assistant.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AssistantState { DISABLED, IDLE, LISTENING, PROCESSING, ERROR }

/** Live engine state, drives the dashboard voice indicator and overlay bubble. */
object StateBus {

    private val _state = MutableStateFlow(AssistantState.DISABLED)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private val _lastCommand = MutableStateFlow("")
    val lastCommand: StateFlow<String> = _lastCommand.asStateFlow()

    private val _level = MutableStateFlow(0f)
    /** Mic RMS level 0f..1f for the voice activity bar. */
    val level: StateFlow<Float> = _level.asStateFlow()

    fun setState(value: AssistantState) {
        _state.value = value
    }

    fun setCommand(text: String) {
        _lastCommand.value = text
    }

    fun setLevel(value: Float) {
        _level.value = value.coerceIn(0f, 1f)
    }
}
