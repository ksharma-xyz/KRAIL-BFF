package app.krail.bff.tools

/**
 * Minimal RFC 4180-ish CSV parser: handles quoted fields, escaped quotes (""),
 * and optional UTF-8 BOM. Fine for GTFS shape; not a general-purpose CSV lib.
 * Used by BuildStopsDataset (manual ROADMAP §2 tooling).
 */
internal fun parseCsv(input: String): List<List<String>> {
    val source = if (input.isNotEmpty() && input[0] == '﻿') input.substring(1) else input
    val rows = mutableListOf<List<String>>()
    val current = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < source.length) {
        val c = source[i]
        when {
            inQuotes -> when {
                c == '"' && i + 1 < source.length && source[i + 1] == '"' -> {
                    field.append('"'); i++
                }
                c == '"' -> inQuotes = false
                else -> field.append(c)
            }
            c == '"' -> inQuotes = true
            c == ',' -> {
                current.add(field.toString()); field.setLength(0)
            }
            c == '\n' -> {
                current.add(field.toString()); field.setLength(0)
                rows.add(current.toList()); current.clear()
            }
            c == '\r' -> { /* swallow; treat as part of CRLF */ }
            else -> field.append(c)
        }
        i++
    }
    if (field.isNotEmpty() || current.isNotEmpty()) {
        current.add(field.toString())
        rows.add(current.toList())
    }
    return rows
}
