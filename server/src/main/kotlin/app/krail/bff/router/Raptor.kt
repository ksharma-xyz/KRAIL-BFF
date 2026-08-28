package app.krail.bff.router

import java.time.LocalDate
import java.util.BitSet

data class StopCall(val stop: Int, val arrSec: Int, val depSec: Int)

sealed class JourneyLeg {
    data class Transit(
        val route: Int,
        val tripId: String,
        val headsign: String?,
        val boardStop: Int,
        val alightStop: Int,
        val boardDepSec: Int,
        val alightArrSec: Int,
        /** Board..alight inclusive. */
        val stops: List<StopCall>,
    ) : JourneyLeg()

    data class Walk(val fromStop: Int, val toStop: Int, val durationSec: Int) : JourneyLeg()
}

/**
 * One Pareto-optimal journey. All times are seconds since midnight of the
 * query date (may exceed 24h when the journey crosses midnight); callers
 * apply the city timezone.
 */
data class Journey(
    val legs: List<JourneyLeg>,
    val depSec: Int,
    val arrSec: Int,
    val transfers: Int,
)

/**
 * RAPTOR (Round-bAsed Public Transit Optimized Router) over a [RouterModel].
 *
 * Round k holds the best arrival times reachable with exactly k vehicle
 * boardings; the result is the Pareto set over (arrival time, transfers).
 * Service days D-1, D and D+1 are scanned so after-midnight trips
 * (stop_times past 24:00:00) are caught on both sides of midnight.
 *
 * Query time is an explicit (date, seconds-since-midnight) pair — the router
 * never reads a clock.
 */
class RaptorRouter(private val model: RouterModel) {

    fun plan(
        fromId: String,
        toId: String,
        date: LocalDate,
        depSecSinceMidnight: Int,
        maxRounds: Int = DEFAULT_MAX_ROUNDS,
    ): List<Journey> {
        val sources = model.stopsForId(fromId)
        val targets = model.stopsForId(toId)
        if (sources.isEmpty() || targets.isEmpty()) return emptyList()
        return planStops(sources, targets, date, depSecSinceMidnight, maxRounds)
    }

    fun planStops(
        sources: IntArray,
        targets: IntArray,
        date: LocalDate,
        depSec: Int,
        maxRounds: Int = DEFAULT_MAX_ROUNDS,
    ): List<Journey> {
        require(maxRounds in 1..MAX_ROUNDS_LIMIT) { "maxRounds out of range" }
        val s = State(model, date, maxRounds)
        s.run(sources, targets, depSec)
        return s.collectJourneys(targets, depSec)
    }

    private class Round(stopCount: Int) {
        val arr = IntArray(stopCount) { INF }
        val type = ByteArray(stopCount)
        val pattern = IntArray(stopCount)
        val trip = IntArray(stopCount)
        val boardPos = IntArray(stopCount)
        val alightPos = IntArray(stopCount)
        val dayFlag = ByteArray(stopCount)
        val fromStop = IntArray(stopCount)
        val walkSec = IntArray(stopCount)
    }

    private inner class State(val m: RouterModel, date: LocalDate, val maxRounds: Int) {
        val stopCount = m.stopCount
        val rounds = Array(maxRounds + 1) { Round(stopCount) }
        val best = IntArray(stopCount) { INF }
        val marked = IntVec(256)
        val isMarked = BooleanArray(stopCount)
        val isTarget = BooleanArray(stopCount)
        var targetBound = INF

        // Index 0/1/2 = service day D / D-1 / D+1; offset converts a raw
        // stop_time to seconds since midnight of the query date D.
        val dayServices = arrayOf(
            m.activeServices(date),
            m.activeServices(date.minusDays(1)),
            m.activeServices(date.plusDays(1)),
        )

        val patternMinPos = IntArray(m.patternCount) { -1 }
        val touchedPatterns = IntVec(128)

        fun mark(stop: Int) {
            if (!isMarked[stop]) {
                isMarked[stop] = true
                marked.add(stop)
            }
        }

        fun run(sources: IntArray, targets: IntArray, depSec: Int) {
            for (t in targets) isTarget[t] = true

            val r0 = rounds[0]
            for (s in sources) {
                if (depSec < r0.arr[s]) {
                    r0.arr[s] = depSec
                    r0.type[s] = TYPE_SOURCE
                    best[s] = depSec
                    if (isTarget[s]) targetBound = minOf(targetBound, depSec)
                    mark(s)
                }
            }
            relaxTransfers(0, allowFromSource = true)

            for (k in 1..maxRounds) {
                if (marked.size == 0) break
                collectQueue()
                scanPatterns(k)
                relaxTransfers(k, allowFromSource = false)
            }
        }

        fun collectQueue() {
            for (i in 0 until marked.size) {
                val s = marked[i]
                isMarked[s] = false
                val from = m.stopAdjOffset[s]
                val to = m.stopAdjOffset[s + 1]
                for (e in from until to) {
                    val p = m.adjPattern[e]
                    val pos = m.adjPosition[e]
                    val cur = patternMinPos[p]
                    if (cur == -1) {
                        patternMinPos[p] = pos
                        touchedPatterns.add(p)
                    } else if (pos < cur) {
                        patternMinPos[p] = pos
                    }
                }
            }
            marked.clear()
        }

        fun scanPatterns(k: Int) {
            val prev = rounds[k - 1]
            val cur = rounds[k]
            for (i in 0 until touchedPatterns.size) {
                val p = touchedPatterns[i]
                val startPos = patternMinPos[p]
                patternMinPos[p] = -1
                val stopsOff = m.patternStopsOffset[p]
                val nStops = m.patternStopsOffset[p + 1] - stopsOff
                val tripsStart = m.patternTripsOffset[p]
                val tripsEnd = m.patternTripsOffset[p + 1]
                if (tripsStart == tripsEnd) continue
                val base = m.patternTimesBase[p]

                var curTrip = -1
                var curDayFlag = 0
                var curBoardPos = 0
                for (pos in startPos until nStops) {
                    val s = m.patternStops[stopsOff + pos]
                    if (curTrip >= 0) {
                        val slot = base + (curTrip - tripsStart) * nStops + pos
                        if (m.stopTimeFlags[slot].toInt() and STOP_TIME_NO_DROP_OFF != 0) {
                            // drop_off_type=1: the vehicle passes but nobody
                            // may alight. The earliest-departing trip no
                            // longer dominates at this position — a later
                            // trip of the same pattern may allow the
                            // drop-off — so run an exact per-trip scan here
                            // (rare: only when actually riding past a
                            // restricted call with an improvement in reach).
                            alightRestrictedFallback(
                                p, base, nStops, tripsStart, tripsEnd, stopsOff, startPos, pos, s, prev, cur
                            )
                        } else {
                            val a = m.arrivals[slot] + DAY_OFFSETS[curDayFlag]
                            if (a < best[s] && a < targetBound) {
                                cur.arr[s] = a
                                best[s] = a
                                cur.type[s] = TYPE_RIDE
                                cur.pattern[s] = p
                                cur.trip[s] = curTrip
                                cur.boardPos[s] = curBoardPos
                                cur.alightPos[s] = pos
                                cur.dayFlag[s] = curDayFlag.toByte()
                                if (isTarget[s]) targetBound = a
                                mark(s)
                            }
                        }
                    }
                    val tau = prev.arr[s]
                    if (tau >= INF) continue
                    val curDepAbs = if (curTrip >= 0) {
                        m.departures[base + (curTrip - tripsStart) * nStops + pos] + DAY_OFFSETS[curDayFlag]
                    } else {
                        INF
                    }
                    if (tau <= curDepAbs) {
                        val fifo = m.patternFifo[stopsOff + pos]
                        var bestDep = curDepAbs
                        for (day in 0 until 3) {
                            val t = earliestTrip(base, nStops, tripsStart, tripsEnd, pos, tau - DAY_OFFSETS[day], dayServices[day], fifo)
                            if (t >= 0) {
                                val dep = m.departures[base + (t - tripsStart) * nStops + pos] + DAY_OFFSETS[day]
                                if (dep < bestDep) {
                                    bestDep = dep
                                    curTrip = t
                                    curDayFlag = day
                                    curBoardPos = pos
                                }
                            }
                        }
                    }
                }
            }
            touchedPatterns.clear()
        }

        /**
         * Exact arrival improvement at a drop-off-restricted position: the
         * minimum arrival at [pos] over every trip of the pattern that allows
         * alighting there, is service-active in one of the three day windows,
         * and is boardable at some earlier position (previous-round arrival
         * before its departure, pickup allowed). Restores the journeys the
         * single-current-trip scan loses when the earliest catchable trip
         * forbids the drop-off but a later trip of the same pattern permits
         * it. O(trips × positions) worst case, but runs only at restricted
         * calls actually ridden past (~0.25% of SEQ rows, fewer elsewhere).
         */
        fun alightRestrictedFallback(
            p: Int,
            base: Int,
            nStops: Int,
            tripsStart: Int,
            tripsEnd: Int,
            stopsOff: Int,
            startPos: Int,
            pos: Int,
            s: Int,
            prev: Round,
            cur: Round,
        ) {
            for (day in 0 until 3) {
                val off = DAY_OFFSETS[day]
                val active = dayServices[day]
                for (j in tripsStart until tripsEnd) {
                    val tBase = base + (j - tripsStart) * nStops
                    if (m.stopTimeFlags[tBase + pos].toInt() and STOP_TIME_NO_DROP_OFF != 0) continue
                    val a = m.arrivals[tBase + pos] + off
                    if (a >= best[s] || a >= targetBound) continue
                    if (!active.get(m.tripServices[j])) continue
                    var boardPos = -1
                    for (q in startPos until pos) {
                        val tau = prev.arr[m.patternStops[stopsOff + q]]
                        if (tau >= INF) continue
                        if (m.stopTimeFlags[tBase + q].toInt() and STOP_TIME_NO_PICKUP != 0) continue
                        if (m.departures[tBase + q] + off >= tau) {
                            boardPos = q
                            break
                        }
                    }
                    if (boardPos < 0) continue
                    cur.arr[s] = a
                    best[s] = a
                    cur.type[s] = TYPE_RIDE
                    cur.pattern[s] = p
                    cur.trip[s] = j
                    cur.boardPos[s] = boardPos
                    cur.alightPos[s] = pos
                    cur.dayFlag[s] = day.toByte()
                    if (isTarget[s]) targetBound = a
                    mark(s)
                }
            }
        }

        /**
         * Earliest trip in the pattern departing [pos] at/after [threshold]
         * whose service is active and whose call at [pos] allows boarding
         * (pickup_type=1 calls are skipped). Trips are sorted by first-stop
         * departure; binary search is exact only when the builder verified
         * FIFO order at every position ([fifo]) — overtaking patterns get a
         * full linear scan so no catchable trip is missed.
         */
        fun earliestTrip(
            base: Int,
            nStops: Int,
            tripsStart: Int,
            tripsEnd: Int,
            pos: Int,
            threshold: Int,
            active: BitSet,
            fifo: Boolean,
        ): Int {
            if (!fifo) {
                var bestTrip = -1
                var bestDep = Int.MAX_VALUE
                for (j in tripsStart until tripsEnd) {
                    val slot = base + (j - tripsStart) * nStops + pos
                    val d = m.departures[slot]
                    if (d in threshold until bestDep &&
                        m.stopTimeFlags[slot].toInt() and STOP_TIME_NO_PICKUP == 0 &&
                        active.get(m.tripServices[j])
                    ) {
                        bestDep = d
                        bestTrip = j
                    }
                }
                return bestTrip
            }
            var lo = tripsStart
            var hi = tripsEnd
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (m.departures[base + (mid - tripsStart) * nStops + pos] < threshold) lo = mid + 1 else hi = mid
            }
            var j = lo
            while (j > tripsStart && m.departures[base + (j - 1 - tripsStart) * nStops + pos] >= threshold) j--
            while (j < tripsEnd) {
                val slot = base + (j - tripsStart) * nStops + pos
                if (m.departures[slot] >= threshold &&
                    m.stopTimeFlags[slot].toInt() and STOP_TIME_NO_PICKUP == 0 &&
                    active.get(m.tripServices[j])
                ) {
                    return j
                }
                j++
            }
            return -1
        }

        fun relaxTransfers(k: Int, allowFromSource: Boolean) {
            val cur = rounds[k]
            val rideCount = marked.size
            for (i in 0 until rideCount) {
                val s = marked[i]
                val type = cur.type[s]
                if (type != TYPE_RIDE && !(allowFromSource && type == TYPE_SOURCE)) continue
                val from = m.transferOffset[s]
                val to = m.transferOffset[s + 1]
                for (e in from until to) {
                    val u = m.transferTarget[e]
                    val t = cur.arr[s] + m.transferSeconds[e]
                    if (t < best[u] && t < targetBound) {
                        cur.arr[u] = t
                        best[u] = t
                        cur.type[u] = TYPE_WALK
                        cur.fromStop[u] = s
                        cur.walkSec[u] = m.transferSeconds[e]
                        if (isTarget[u]) targetBound = t
                        mark(u)
                    }
                }
            }
        }

        fun collectJourneys(targets: IntArray, queryDep: Int): List<Journey> {
            val journeys = ArrayList<Journey>()
            var bestSoFar = INF
            for (k in 0..maxRounds) {
                var tBest = INF
                var tStop = -1
                for (t in targets) {
                    if (rounds[k].arr[t] < tBest) {
                        tBest = rounds[k].arr[t]
                        tStop = t
                    }
                }
                if (tStop >= 0 && tBest < bestSoFar) {
                    bestSoFar = tBest
                    val j = reconstruct(tStop, k, queryDep)
                    if (j.legs.isNotEmpty()) journeys.add(j)
                }
            }
            return journeys
        }

        fun reconstruct(target: Int, lastRound: Int, queryDep: Int): Journey {
            val legs = ArrayList<JourneyLeg>()
            var s = target
            var r = lastRound
            while (true) {
                val round = rounds[r]
                when (round.type[s]) {
                    TYPE_SOURCE -> break
                    TYPE_WALK -> {
                        legs.add(JourneyLeg.Walk(round.fromStop[s], s, round.walkSec[s]))
                        s = round.fromStop[s]
                    }
                    TYPE_RIDE -> {
                        val p = round.pattern[s]
                        val t = round.trip[s]
                        val bPos = round.boardPos[s]
                        val aPos = round.alightPos[s]
                        val offset = DAY_OFFSETS[round.dayFlag[s].toInt()]
                        val stopsOff = m.patternStopsOffset[p]
                        val nStops = m.patternStopsOffset[p + 1] - stopsOff
                        val timesBase = m.patternTimesBase[p] + (t - m.patternTripsOffset[p]) * nStops
                        val calls = ArrayList<StopCall>(aPos - bPos + 1)
                        for (pos in bPos..aPos) {
                            calls.add(
                                StopCall(
                                    stop = m.patternStops[stopsOff + pos],
                                    arrSec = m.arrivals[timesBase + pos] + offset,
                                    depSec = m.departures[timesBase + pos] + offset,
                                )
                            )
                        }
                        legs.add(
                            JourneyLeg.Transit(
                                route = m.patternRoute[p],
                                tripId = m.tripIds[t],
                                headsign = m.tripHeadsigns[t],
                                boardStop = calls.first().stop,
                                alightStop = calls.last().stop,
                                boardDepSec = calls.first().depSec,
                                alightArrSec = calls.last().arrSec,
                                stops = calls,
                            )
                        )
                        s = m.patternStops[stopsOff + bPos]
                        r--
                    }
                    else -> error("raptor: broken parent chain at stop $s round $r")
                }
            }
            legs.reverse()

            var depSec = queryDep
            var walkBefore = 0
            for (leg in legs) {
                if (leg is JourneyLeg.Walk) {
                    walkBefore += leg.durationSec
                } else if (leg is JourneyLeg.Transit) {
                    depSec = leg.boardDepSec - walkBefore
                    break
                }
            }
            val transitCount = legs.count { it is JourneyLeg.Transit }
            return Journey(
                legs = legs,
                depSec = depSec,
                arrSec = rounds[lastRound].arr[target],
                transfers = (transitCount - 1).coerceAtLeast(0),
            )
        }
    }

    companion object {
        const val DEFAULT_MAX_ROUNDS = 5
        private const val MAX_ROUNDS_LIMIT = 8
        private const val INF = Int.MAX_VALUE / 2
        private const val DAY = 86_400
        private val DAY_OFFSETS = intArrayOf(0, -DAY, DAY)
        private const val TYPE_SOURCE: Byte = 1
        private const val TYPE_RIDE: Byte = 2
        private const val TYPE_WALK: Byte = 3
    }
}
