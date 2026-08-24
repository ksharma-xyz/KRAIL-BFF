package app.krail.bff.router

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Binary snapshot of a [RouterModel]: flat arrays with a magic + format
 * version header. Built offline; production loads this at startup instead of
 * re-parsing GTFS (order-of-magnitude faster, see REPORT.md benchmarks).
 */
object RouterSnapshot {

    private const val MAGIC = 0x4B_52_56_52 // "KRVR"
    const val FORMAT_VERSION = 1
    private const val BUFFER_SIZE = 1 shl 20

    fun write(model: RouterModel, path: Path) {
        path.parent?.let { Files.createDirectories(it) }
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(path), BUFFER_SIZE)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeUTF(model.meta.sourceLabel)
            out.writeInt(model.meta.calendarStart)
            out.writeInt(model.meta.calendarEnd)
            out.writeLong(model.meta.builtAtEpochSec)

            out.writeStrings(model.stopIds)
            out.writeStrings(model.stopNames)
            out.writeDoubles(model.stopLats)
            out.writeDoubles(model.stopLons)

            out.writeStrings(model.routeIds)
            out.writeStrings(model.routeShortNames)
            out.writeStrings(model.routeLongNames)
            out.writeInts(model.routeTypes)

            out.writeInt(model.services.size)
            for (svc in model.services) {
                out.writeInt(svc.weekdayMask)
                out.writeInt(svc.startDate)
                out.writeInt(svc.endDate)
                out.writeInts(svc.addedDates)
                out.writeInts(svc.removedDates)
            }

            out.writeInts(model.patternRoute)
            out.writeInts(model.patternStopsOffset)
            out.writeInts(model.patternStops)
            out.writeInts(model.patternTripsOffset)
            out.writeInts(model.patternTimesBase)

            out.writeStrings(model.tripIds)
            out.writeInts(model.tripServices)
            out.writeInt(model.tripHeadsigns.size)
            for (h in model.tripHeadsigns) {
                out.writeBoolean(h != null)
                if (h != null) out.writeUTF(h)
            }

            out.writeInts(model.arrivals)
            out.writeInts(model.departures)

            out.writeInts(model.stopAdjOffset)
            out.writeInts(model.adjPattern)
            out.writeInts(model.adjPosition)

            out.writeInts(model.transferOffset)
            out.writeInts(model.transferTarget)
            out.writeInts(model.transferSeconds)

            out.writeInt(model.stopIdLookup.size)
            for ((id, stops) in model.stopIdLookup) {
                out.writeUTF(id)
                out.writeInts(stops)
            }
        }
    }

    fun read(path: Path): RouterModel {
        DataInputStream(BufferedInputStream(Files.newInputStream(path), BUFFER_SIZE)).use { input ->
            val magic = input.readInt()
            require(magic == MAGIC) { "not a router snapshot: bad magic" }
            val version = input.readInt()
            require(version == FORMAT_VERSION) { "unsupported snapshot format version $version" }
            val meta = RouterModelMeta(
                sourceLabel = input.readUTF(),
                calendarStart = input.readInt(),
                calendarEnd = input.readInt(),
                builtAtEpochSec = input.readLong(),
            )

            val stopIds = input.readStrings()
            val stopNames = input.readStrings()
            val stopLats = input.readDoubles()
            val stopLons = input.readDoubles()

            val routeIds = input.readStrings()
            val routeShortNames = input.readStrings()
            val routeLongNames = input.readStrings()
            val routeTypes = input.readInts()

            val serviceCount = input.readInt()
            val services = Array(serviceCount) {
                ServiceCalendar(
                    weekdayMask = input.readInt(),
                    startDate = input.readInt(),
                    endDate = input.readInt(),
                    addedDates = input.readInts(),
                    removedDates = input.readInts(),
                )
            }

            val patternRoute = input.readInts()
            val patternStopsOffset = input.readInts()
            val patternStops = input.readInts()
            val patternTripsOffset = input.readInts()
            val patternTimesBase = input.readInts()

            val tripIds = input.readStrings()
            val tripServices = input.readInts()
            val headsignCount = input.readInt()
            val tripHeadsigns = arrayOfNulls<String>(headsignCount)
            for (i in 0 until headsignCount) {
                if (input.readBoolean()) tripHeadsigns[i] = input.readUTF()
            }

            val arrivals = input.readInts()
            val departures = input.readInts()

            val stopAdjOffset = input.readInts()
            val adjPattern = input.readInts()
            val adjPosition = input.readInts()

            val transferOffset = input.readInts()
            val transferTarget = input.readInts()
            val transferSeconds = input.readInts()

            val lookupSize = input.readInt()
            val stopIdLookup = HashMap<String, IntArray>(lookupSize * 2)
            repeat(lookupSize) {
                val id = input.readUTF()
                stopIdLookup[id] = input.readInts()
            }

            return RouterModel(
                meta = meta,
                stopIds = stopIds,
                stopNames = stopNames,
                stopLats = stopLats,
                stopLons = stopLons,
                routeIds = routeIds,
                routeShortNames = routeShortNames,
                routeLongNames = routeLongNames,
                routeTypes = routeTypes,
                services = services,
                patternRoute = patternRoute,
                patternStopsOffset = patternStopsOffset,
                patternStops = patternStops,
                patternTripsOffset = patternTripsOffset,
                patternTimesBase = patternTimesBase,
                tripIds = tripIds,
                tripServices = tripServices,
                tripHeadsigns = tripHeadsigns,
                arrivals = arrivals,
                departures = departures,
                stopAdjOffset = stopAdjOffset,
                adjPattern = adjPattern,
                adjPosition = adjPosition,
                transferOffset = transferOffset,
                transferTarget = transferTarget,
                transferSeconds = transferSeconds,
                stopIdLookup = stopIdLookup,
            )
        }
    }

    private fun DataOutputStream.writeInts(a: IntArray) {
        writeInt(a.size)
        for (v in a) writeInt(v)
    }

    private fun DataOutputStream.writeDoubles(a: DoubleArray) {
        writeInt(a.size)
        for (v in a) writeDouble(v)
    }

    private fun DataOutputStream.writeStrings(a: Array<String>) {
        writeInt(a.size)
        for (v in a) writeUTF(v)
    }

    private fun DataInputStream.readInts(): IntArray {
        val n = readSize()
        val a = IntArray(n)
        for (i in 0 until n) a[i] = readInt()
        return a
    }

    private fun DataInputStream.readDoubles(): DoubleArray {
        val n = readSize()
        val a = DoubleArray(n)
        for (i in 0 until n) a[i] = readDouble()
        return a
    }

    private fun DataInputStream.readStrings(): Array<String> {
        val n = readSize()
        return Array(n) { readUTF() }
    }

    private fun DataInputStream.readSize(): Int {
        val n = readInt()
        if (n < 0) throw EOFException("corrupt snapshot: negative array length")
        return n
    }
}
