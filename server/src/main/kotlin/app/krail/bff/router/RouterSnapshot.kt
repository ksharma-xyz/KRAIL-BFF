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
 * re-parsing GTFS (order-of-magnitude faster — benchmarks in
 * docs/reference/VIC_ROUTER_DESIGN.md §10).
 */
object RouterSnapshot {

    private const val MAGIC = 0x4B_52_56_52 // "KRVR"
    // v2: adds stopTimeFlags (pickup/drop-off restrictions per stop time).
    const val FORMAT_VERSION = 2
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
            out.writeInt(model.patternFifo.size)
            for (b in model.patternFifo) out.writeBoolean(b)

            out.writeStrings(model.tripIds)
            out.writeInts(model.tripServices)
            out.writeInt(model.tripHeadsigns.size)
            for (h in model.tripHeadsigns) {
                out.writeBoolean(h != null)
                if (h != null) out.writeUTF(h)
            }

            out.writeInts(model.arrivals)
            out.writeInts(model.departures)
            out.writeInt(model.stopTimeFlags.size)
            out.write(model.stopTimeFlags)

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
        // Upper bound on any declared array length: a truncated/corrupt file
        // must fail with an exception, not a multi-GB allocation.
        val sizeLimit = Files.size(path).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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

            val stopIds = input.readStrings(sizeLimit)
            val stopNames = input.readStrings(sizeLimit)
            val stopLats = input.readDoubles(sizeLimit)
            val stopLons = input.readDoubles(sizeLimit)

            val routeIds = input.readStrings(sizeLimit)
            val routeShortNames = input.readStrings(sizeLimit)
            val routeLongNames = input.readStrings(sizeLimit)
            val routeTypes = input.readInts(sizeLimit)

            val serviceCount = input.readSize(sizeLimit)
            val services = Array(serviceCount) {
                ServiceCalendar(
                    weekdayMask = input.readInt(),
                    startDate = input.readInt(),
                    endDate = input.readInt(),
                    addedDates = input.readInts(sizeLimit),
                    removedDates = input.readInts(sizeLimit),
                )
            }

            val patternRoute = input.readInts(sizeLimit)
            val patternStopsOffset = input.readInts(sizeLimit)
            val patternStops = input.readInts(sizeLimit)
            val patternTripsOffset = input.readInts(sizeLimit)
            val patternTimesBase = input.readInts(sizeLimit)
            val fifoCount = input.readSize(sizeLimit)
            val patternFifo = BooleanArray(fifoCount) { input.readBoolean() }

            val tripIds = input.readStrings(sizeLimit)
            val tripServices = input.readInts(sizeLimit)
            val headsignCount = input.readSize(sizeLimit)
            val tripHeadsigns = arrayOfNulls<String>(headsignCount)
            for (i in 0 until headsignCount) {
                if (input.readBoolean()) tripHeadsigns[i] = input.readUTF()
            }

            val arrivals = input.readInts(sizeLimit)
            val departures = input.readInts(sizeLimit)
            val flagCount = input.readSize(sizeLimit)
            val stopTimeFlags = ByteArray(flagCount)
            input.readFully(stopTimeFlags)

            val stopAdjOffset = input.readInts(sizeLimit)
            val adjPattern = input.readInts(sizeLimit)
            val adjPosition = input.readInts(sizeLimit)

            val transferOffset = input.readInts(sizeLimit)
            val transferTarget = input.readInts(sizeLimit)
            val transferSeconds = input.readInts(sizeLimit)

            val lookupSize = input.readSize(sizeLimit)
            val stopIdLookup = HashMap<String, IntArray>(lookupSize * 2)
            repeat(lookupSize) {
                val id = input.readUTF()
                stopIdLookup[id] = input.readInts(sizeLimit)
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
                patternFifo = patternFifo,
                tripIds = tripIds,
                tripServices = tripServices,
                tripHeadsigns = tripHeadsigns,
                arrivals = arrivals,
                departures = departures,
                stopTimeFlags = stopTimeFlags,
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

    private fun DataInputStream.readInts(limit: Int): IntArray {
        val n = readSize(limit)
        val a = IntArray(n)
        for (i in 0 until n) a[i] = readInt()
        return a
    }

    private fun DataInputStream.readDoubles(limit: Int): DoubleArray {
        val n = readSize(limit)
        val a = DoubleArray(n)
        for (i in 0 until n) a[i] = readDouble()
        return a
    }

    private fun DataInputStream.readStrings(limit: Int): Array<String> {
        val n = readSize(limit)
        return Array(n) { readUTF() }
    }

    private fun DataInputStream.readSize(limit: Int): Int {
        val n = readInt()
        if (n < 0 || n > limit) throw EOFException("corrupt snapshot: implausible array length $n")
        return n
    }
}
