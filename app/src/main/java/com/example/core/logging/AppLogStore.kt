package com.example.core.logging

import com.example.domain.model.LogEntry
import com.example.domain.model.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object AppLogStore {

    private const val MAX_ENTRIES = 400
    private val nextId = AtomicLong(1L)
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun info(tag: String, message: String) = append(LogLevel.INFO, tag, message)

    fun warn(tag: String, message: String) = append(LogLevel.WARN, tag, message)

    fun error(tag: String, message: String) = append(LogLevel.ERROR, tag, message)

    fun debug(tag: String, message: String) = append(LogLevel.DEBUG, tag, message)

    private fun append(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            id = nextId.getAndIncrement(),
            timestampMillis = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
        )
        _logs.update { current ->
            val updated = current + entry
            if (updated.size > MAX_ENTRIES) updated.takeLast(MAX_ENTRIES) else updated
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun exportAsText(): String {
        val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return _logs.value.joinToString(separator = "\n") { entry ->
            val time = formatter.format(Date(entry.timestampMillis))
            "[$time] [${entry.level.name}] [${entry.tag}] ${entry.message}"
        }
    }
}
