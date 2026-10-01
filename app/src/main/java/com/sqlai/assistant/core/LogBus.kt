package com.sqlai.assistant.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { INFO, SUCCESS, WARN, ERROR }

data class LogEntry(
    val time: String,
    val level: LogLevel,
    val message: String
)

/** In-memory event log surfaced live on the Dashboard. */
object LogBus {

    private const val MAX_ENTRIES = 250

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(message: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(timeFormat.format(Date()), level, message)
        synchronized(_logs) {
            val next = _logs.value + entry
            _logs.value = next.takeLast(MAX_ENTRIES)
        }
        println("SQLAI/${level.name}: $message")
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
