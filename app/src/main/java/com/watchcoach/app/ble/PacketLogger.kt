package com.watchcoach.app.ble

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

data class PacketLogLine(
    val id: Long,
    val epochMs: Long,
    val text: String,
)

/**
 * In-memory + on-disk BLE log.
 *
 * Default (non-verbose): drop all-zero RX frames, counting them and emitting
 * "N empty 34F2 frames suppressed"; collapse consecutive identical live-HR
 * lines into one with a repeat count. The shared file matches that cleaned
 * view. Verbose mode records every frame in both the UI and the file.
 */
class PacketLogger(context: Context) {
    private val zone = ZoneId.systemDefault()
    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone)
    private val prefs = context.getSharedPreferences("packet_log", Context.MODE_PRIVATE)
    private val ids = AtomicLong(1)

    private val logDir = File(context.filesDir, "logs").apply { mkdirs() }
    val logFile: File = File(logDir, "watch-sync-log.txt")

    private val _lines = MutableStateFlow<List<PacketLogLine>>(emptyList())
    val lines: StateFlow<List<PacketLogLine>> = _lines.asStateFlow()

    private val _verbose = MutableStateFlow(prefs.getBoolean("verbose", false))
    val verbose: StateFlow<Boolean> = _verbose.asStateFlow()

    @Volatile
    var lastSummary: String? = null

    private var suppressedZeros = 0
    private var suppressedChar = "34F2"
    private var collapseKey: String? = null
    private var collapseCount = 0
    private var collapseLineId: Long? = null
    private var collapseBase: String? = null

    fun isVerbose(): Boolean = _verbose.value

    @Synchronized
    fun setVerbose(enabled: Boolean) {
        flushPendingLocked()
        _verbose.value = enabled
        prefs.edit().putBoolean("verbose", enabled).apply()
        appendLocked("INFO  Verbose raw log ${if (enabled) "ON — every frame is kept" else "OFF — empty 34F2 and repeated live HR are collapsed"}")
    }

    @Synchronized
    fun append(text: String) {
        flushPendingLocked()
        appendLocked(text)
    }

    fun tx(characteristic: String, payload: ByteArray, note: String = "") {
        append("TX  $characteristic  ${payload.toHex()}  $note".trimEnd())
    }

    @Synchronized
    fun rx(characteristic: String, payload: ByteArray, note: String = "") {
        if (payload.isAllZero()) {
            if (_verbose.value) {
                flushPendingLocked()
                appendLocked(
                    "RX  $characteristic  ${payload.toHex()}  empty ${payload.size}-byte frame",
                )
            } else {
                suppressedZeros++
                suppressedChar = characteristic
            }
            return
        }
        flushZerosLocked()
        val text = "RX  $characteristic  ${payload.toHex()}  $note".trimEnd()
        if (!_verbose.value && isLiveHr(payload)) {
            val key = "$characteristic|${payload.toHex()}"
            if (collapseKey == key && collapseLineId != null && collapseBase != null) {
                collapseCount++
                val updated = "${collapseBase!!}  (×$collapseCount)"
                val id = collapseLineId!!
                _lines.update { list ->
                    list.map { if (it.id == id) it.copy(text = updated) else it }
                }
                return
            }
            flushCollapseLocked()
            val line = appendLocked(text, writeFile = false)
            collapseKey = key
            collapseCount = 1
            collapseLineId = line.id
            collapseBase = line.text // already timestamped
            return
        }
        flushCollapseLocked()
        appendLocked(text)
    }

    fun info(message: String) = append("INFO  $message")

    fun warn(message: String) = append("WARN  $message")

    @Synchronized
    fun flush() {
        flushPendingLocked()
    }

    @Synchronized
    fun clear() {
        flushPendingLocked()
        _lines.value = emptyList()
        lastSummary = null
        runCatching { logFile.writeText("") }
        appendLocked("INFO  Log cleared")
    }

    @Synchronized
    fun snapshotText(): String {
        flushPendingLocked()
        val fromMem = _lines.value.joinToString("\n") { it.text }
        if (fromMem.isNotBlank()) return fromMem
        return runCatching { logFile.readText() }.getOrDefault("")
    }

    @Synchronized
    fun summaryPlusLastLines(n: Int = 100): String {
        flushPendingLocked()
        val tail = _lines.value.takeLast(n).joinToString("\n") { it.text }
        val header = lastSummary?.let { it.trimEnd() + "\n\n--- last $n log lines ---\n" } ?: ""
        return header + tail
    }

    private fun flushPendingLocked() {
        flushZerosLocked()
        flushCollapseLocked()
    }

    private fun flushZerosLocked() {
        if (suppressedZeros <= 0) return
        val n = suppressedZeros
        suppressedZeros = 0
        appendLocked("INFO  $n empty $suppressedChar frames suppressed")
    }

    private fun flushCollapseLocked() {
        val id = collapseLineId
        val base = collapseBase
        val count = collapseCount
        collapseKey = null
        collapseCount = 0
        collapseLineId = null
        collapseBase = null
        if (id == null || base == null) return
        // collapseBase is the timestamped first line; add a count if repeated.
        val text = if (count > 1) "$base  (×$count)" else base
        runCatching { logFile.appendText(text + "\n") }
    }

    private fun appendLocked(text: String, writeFile: Boolean = true): PacketLogLine {
        val now = System.currentTimeMillis()
        val stamped = "${timeFmt.format(Instant.ofEpochMilli(now))}  $text"
        val line = PacketLogLine(ids.getAndIncrement(), now, stamped)
        _lines.update { (it + line).takeLast(MAX_MEMORY_LINES) }
        if (writeFile) {
            runCatching { logFile.appendText(stamped + "\n") }
        }
        return line
    }

    companion object {
        private const val MAX_MEMORY_LINES = 2500

        fun isLiveHr(payload: ByteArray): Boolean =
            payload.size >= 4 &&
                (payload[0].toInt() and 0xFF) == 0xE5 &&
                (payload[1].toInt() and 0xFF) == 0x11
    }
}

fun ByteArray.toHex(): String =
    joinToString(" ") { b -> "%02X".format(b.toInt() and 0xFF) }

fun ByteArray.isAllZero(): Boolean = isNotEmpty() && all { it == 0.toByte() }
