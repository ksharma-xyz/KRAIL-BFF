package app.krail.bff.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CsvReaderTest {

    private fun rows(input: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        CsvReader(input.byteInputStream()).use { csv ->
            while (csv.readRow()) {
                out.add((0 until csv.fieldCount).map { csv.string(it) })
            }
        }
        return out
    }

    @Test
    fun `strips utf8 bom before header`() {
        val r = rows("﻿stop_id,name\n1,A\n")
        assertEquals(listOf(listOf("stop_id", "name"), listOf("1", "A")), r)
    }

    @Test
    fun `quoted fields with commas and escaped quotes`() {
        val r = rows("\"a,b\",\"say \"\"hi\"\"\",plain\n")
        assertEquals(listOf(listOf("a,b", "say \"hi\"", "plain")), r)
    }

    @Test
    fun `quoted field with embedded newline`() {
        val r = rows("\"line1\nline2\",x\n")
        assertEquals(listOf(listOf("line1\nline2", "x")), r)
    }

    @Test
    fun `crlf and missing final newline`() {
        val r = rows("a,b\r\nc,d")
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), r)
    }

    @Test
    fun `blank lines skipped`() {
        val r = rows("a,b\n\n\nc,d\n\n")
        assertEquals(2, r.size)
    }

    @Test
    fun `empty fields preserved`() {
        val r = rows("a,,c\n,,\n")
        assertEquals(listOf(listOf("a", "", "c"), listOf("", "", "")), r)
    }

    @Test
    fun `time parsing handles single digit hours and over 24h`() {
        CsvReader("4:57:09,25:30:00,,bad\n".byteInputStream()).use { csv ->
            assertTrue(csv.readRow())
            assertEquals(4 * 3600 + 57 * 60 + 9, csv.timeSeconds(0))
            assertEquals(25 * 3600 + 30 * 60, csv.timeSeconds(1))
            assertEquals(-1, csv.timeSeconds(2))
            assertEquals(-1, csv.timeSeconds(3))
        }
    }

    @Test
    fun `ragged row does not expose stale fields`() {
        CsvReader("a,b,c\nonly\n".byteInputStream()).use { csv ->
            assertTrue(csv.readRow()) // header
            assertTrue(csv.readRow()) // ragged row: 1 field vs 3 in header
            assertEquals(1, csv.fieldCount)
            assertEquals("", csv.string(2))
            assertTrue(csv.isBlank(2))
            assertFalse(csv.fieldEquals(2, "c"))
        }
    }

    @Test
    fun `implausibly large numbers fall back to defaults`() {
        CsvReader("999999999999999,9999999:00:00\n".byteInputStream()).use { csv ->
            assertTrue(csv.readRow())
            assertEquals(-7, csv.int(0, -7))
            assertEquals(-1, csv.timeSeconds(1))
        }
    }

    @Test
    fun `int and fieldEquals accessors`() {
        CsvReader("42,,x,trip-1\n".byteInputStream()).use { csv ->
            assertTrue(csv.readRow())
            assertEquals(42, csv.int(0, -1))
            assertEquals(7, csv.int(1, 7))
            assertEquals(9, csv.int(2, 9))
            assertTrue(csv.fieldEquals(3, "trip-1"))
            assertFalse(csv.fieldEquals(3, "trip-2"))
            assertFalse(csv.fieldEquals(0, "421"))
        }
    }
}
