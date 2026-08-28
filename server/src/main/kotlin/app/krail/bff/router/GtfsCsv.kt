package app.krail.bff.router

import java.io.InputStream
import java.io.InputStreamReader

/**
 * Streaming CSV reader for GTFS files.
 *
 * Handles UTF-8 BOM, CRLF, quoted fields with escaped quotes ("") and
 * embedded commas/newlines. Fields are exposed without per-field String
 * allocation — numeric accessors parse straight from the char buffer,
 * which matters at stop_times.txt scale (7.5M rows in the VIC bus feed).
 */
class CsvReader(input: InputStream) : AutoCloseable {

    private val reader = InputStreamReader(input, Charsets.UTF_8)
    private val chunk = CharArray(1 shl 16)
    private var chunkLen = 0
    private var pos = 0
    private var eof = false
    private var bomPending = true

    // Current row: field chars packed into [buf], bounds in [starts]/[ends].
    private var buf = CharArray(1 shl 12)
    private var bufLen = 0
    private var starts = IntArray(64)
    private var ends = IntArray(64)

    var fieldCount = 0
        private set

    /** Reads the next row. Returns false at end of input. Skips blank lines. */
    fun readRow(): Boolean {
        while (true) {
            bufLen = 0
            fieldCount = 0
            var fieldStart = 0
            var inQuotes = false
            var sawContent = false
            var started = false
            while (true) {
                val ci = nextChar()
                if (ci < 0) {
                    if (!started) return false
                    if (inQuotes) throw IllegalArgumentException("CSV: unterminated quoted field at end of input")
                    endField(fieldStart)
                    return true
                }
                started = true
                val c = ci.toChar()
                if (inQuotes) {
                    if (c == '"') {
                        if (peekChar() == '"'.code) {
                            nextChar()
                            append('"')
                        } else {
                            inQuotes = false
                        }
                    } else {
                        append(c)
                    }
                    continue
                }
                when (c) {
                    '"' -> if (bufLen == fieldStart) {
                        inQuotes = true
                        sawContent = true
                    } else {
                        append(c) // stray quote mid-field: keep literally
                    }
                    ',' -> {
                        endField(fieldStart)
                        fieldStart = bufLen
                        sawContent = true
                    }
                    '\r' -> {
                        if (peekChar() == '\n'.code) nextChar()
                        if (!sawContent && bufLen == fieldStart && fieldCount == 0) break // blank line
                        endField(fieldStart)
                        return true
                    }
                    '\n' -> {
                        if (!sawContent && bufLen == fieldStart && fieldCount == 0) break // blank line
                        endField(fieldStart)
                        return true
                    }
                    else -> append(c)
                }
            }
        }
    }

    /** Column-name → index map for the current (header) row. */
    fun headerMap(): Map<String, Int> =
        (0 until fieldCount).associateBy({ string(it).trim() }, { it })

    /** Empty string for an out-of-range index (ragged rows must not read stale offsets). */
    fun string(i: Int): String =
        if (i < 0 || i >= fieldCount) "" else String(buf, starts[i], ends[i] - starts[i])

    fun isBlank(i: Int): Boolean = i < 0 || i >= fieldCount || ends[i] == starts[i]

    /** Allocation-free equality check against [s] (stop_times trip-id hot path). */
    fun fieldEquals(i: Int, s: String): Boolean {
        if (i < 0 || i >= fieldCount) return false
        val len = ends[i] - starts[i]
        if (len != s.length) return false
        val off = starts[i]
        for (p in 0 until len) if (buf[off + p] != s[p]) return false
        return true
    }

    /** Parses a non-negative integer field; [default] on blank, malformed, or implausibly large. */
    fun int(i: Int, default: Int): Int {
        if (isBlank(i)) return default
        var v = 0
        for (p in starts[i] until ends[i]) {
            val c = buf[p]
            if (c < '0' || c > '9') return default
            v = v * 10 + (c - '0')
            if (v > 100_000_000) return default // dates are the largest legit values
        }
        return v
    }

    fun double(i: Int, default: Double): Double {
        if (isBlank(i)) return default
        return string(i).toDoubleOrNull() ?: default
    }

    /**
     * Parses a GTFS time field ("HH:MM:SS" or "H:MM:SS", hours may exceed 24
     * for after-midnight service) to seconds since service-day midnight.
     * Returns -1 on blank or malformed.
     */
    fun timeSeconds(i: Int): Int {
        if (isBlank(i)) return -1
        val s = starts[i]
        val e = ends[i]
        var p = s
        var h = 0
        while (p < e && buf[p] != ':') {
            val c = buf[p]
            if (c < '0' || c > '9') return -1
            h = h * 10 + (c - '0')
            if (h > 168) return -1 // a week of hours: anything beyond is garbage
            p++
        }
        if (p + 5 > e || buf[p] != ':' || buf[p + 3] != ':') return -1
        val m1 = buf[p + 1] - '0'
        val m2 = buf[p + 2] - '0'
        val s1 = buf[p + 4] - '0'
        val s2 = buf[p + 5] - '0'
        if (m1 !in 0..9 || m2 !in 0..9 || s1 !in 0..9 || s2 !in 0..9) return -1
        return h * 3600 + (m1 * 10 + m2) * 60 + s1 * 10 + s2
    }

    override fun close() = reader.close()

    private fun nextChar(): Int {
        if (pos >= chunkLen && !fill()) return -1
        val c = chunk[pos++]
        if (bomPending) {
            bomPending = false
            if (c == '\uFEFF') return nextChar()
        }
        return c.code
    }

    private fun peekChar(): Int {
        if (pos >= chunkLen && !fill()) return -1
        return chunk[pos].code
    }

    private fun fill(): Boolean {
        if (eof) return false
        val n = reader.read(chunk)
        if (n <= 0) {
            eof = true
            return false
        }
        chunkLen = n
        pos = 0
        return true
    }

    private fun append(c: Char) {
        if (bufLen == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[bufLen++] = c
    }

    private fun endField(fieldStart: Int) {
        if (fieldCount == starts.size) {
            starts = starts.copyOf(starts.size * 2)
            ends = ends.copyOf(ends.size * 2)
        }
        starts[fieldCount] = fieldStart
        ends[fieldCount] = bufLen
        fieldCount++
    }
}

/** Growable primitive byte buffer used while streaming GTFS rows. */
class ByteVec(initialCapacity: Int = 1024) {
    private var data = ByteArray(initialCapacity)
    var size = 0
        private set

    fun add(v: Byte) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Byte = data[i]

    fun toArray(): ByteArray = data.copyOf(size)
}

/** Growable primitive int buffer used while streaming GTFS rows. */
class IntVec(initialCapacity: Int = 1024) {
    var data = IntArray(initialCapacity)
        private set
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]

    fun clear() {
        size = 0
    }

    fun toArray(): IntArray = data.copyOf(size)
}
