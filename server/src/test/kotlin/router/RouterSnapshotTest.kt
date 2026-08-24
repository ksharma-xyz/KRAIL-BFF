package app.krail.bff.router

import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RouterSnapshotTest {

    @Test
    fun `round-trip preserves the model and query results`() {
        val model = MiniNetworkFixture.buildModel()
        val path = Files.createTempFile("router-snapshot", ".bin")
        try {
            RouterSnapshot.write(model, path)
            val loaded = RouterSnapshot.read(path)

            assertContentEquals(model.stopIds, loaded.stopIds)
            assertContentEquals(model.stopNames, loaded.stopNames)
            assertContentEquals(model.routeIds, loaded.routeIds)
            assertContentEquals(model.tripIds, loaded.tripIds)
            assertContentEquals(model.arrivals, loaded.arrivals)
            assertContentEquals(model.departures, loaded.departures)
            assertContentEquals(model.patternStops, loaded.patternStops)
            assertContentEquals(model.patternFifo.toTypedArray(), loaded.patternFifo.toTypedArray())
            assertContentEquals(model.transferTarget, loaded.transferTarget)
            assertContentEquals(model.transferSeconds, loaded.transferSeconds)
            assertEquals(model.stopIdLookup.keys, loaded.stopIdLookup.keys)
            assertEquals(model.meta.sourceLabel, loaded.meta.sourceLabel)
            assertEquals(model.meta.calendarStart, loaded.meta.calendarStart)
            assertEquals(model.meta.calendarEnd, loaded.meta.calendarEnd)

            val monday = LocalDate.of(2026, 3, 2)
            val dep = MiniNetworkFixture.sec(7, 55)
            val original = RaptorRouter(model).plan("A", "F", monday, dep)
            val reloaded = RaptorRouter(loaded).plan("A", "F", monday, dep)
            assertEquals(original, reloaded)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `rejects files that are not snapshots`() {
        val path = Files.createTempFile("router-snapshot-bad", ".bin")
        try {
            Files.write(path, ByteArray(64) { 7 })
            assertFailsWith<IllegalArgumentException> { RouterSnapshot.read(path) }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
