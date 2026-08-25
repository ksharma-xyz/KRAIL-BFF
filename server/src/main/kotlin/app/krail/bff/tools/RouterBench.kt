package app.krail.bff.tools

import app.krail.bff.router.RaptorRouter
import app.krail.bff.router.RouterModel
import app.krail.bff.router.RouterSnapshot
import app.krail.bff.vic.VicGtfsIngest
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Random

/**
 * Offline benchmark for the VIC journey-planning core. Manual tooling, same
 * spirit as BuildStopsDataset — not part of the serving path.
 *
 * Usage: routerBench <gtfsDir> [snapshotPath] [foldersCsv] [dateIso]
 *   gtfsDir      directory containing <folder>/google_transit.zip
 *   snapshotPath output snapshot file (default <gtfsDir>/router-snapshot.bin)
 *   foldersCsv   folders to ingest (default 2,3,4,11)
 *   dateIso      query date (default: first Tuesday inside the dataset calendar)
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: routerBench <gtfsDir> [snapshotPath] [foldersCsv] [dateIso]" }
    fun arg(i: Int): String? = args.getOrNull(i)?.takeIf { it.isNotBlank() }
    val gtfsDir = Path.of(args[0])
    val snapshotPath = arg(1)?.let { Path.of(it) } ?: gtfsDir.resolve("router-snapshot.bin")
    val folders = arg(2)?.split(",") ?: VicGtfsIngest.METRO_FOLDERS

    val buildStart = System.nanoTime()
    var model: RouterModel? = VicGtfsIngest.buildModel(
        gtfsDir, folders, builtAtEpochSec = System.currentTimeMillis() / 1000
    )
    val buildMs = (System.nanoTime() - buildStart) / 1_000_000
    printStats(model!!, buildMs)

    val writeStart = System.nanoTime()
    RouterSnapshot.write(model, snapshotPath)
    val writeMs = (System.nanoTime() - writeStart) / 1_000_000
    val snapshotBytes = Files.size(snapshotPath)
    println("snapshot write: ${writeMs}ms, ${snapshotBytes / 1_048_576}MB ($snapshotPath)")

    // Drop the built model so heap measurement sees only the loaded one.
    model = null
    forceGc()
    val loadStart = System.nanoTime()
    val loaded = RouterSnapshot.read(snapshotPath)
    val loadMs = (System.nanoTime() - loadStart) / 1_000_000
    forceGc()
    val heapUsedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576
    println("snapshot load: ${loadMs}ms; heap after load+GC: ${heapUsedMb}MB (approximate — JVM heap measurement)")

    val date = arg(3)?.let { LocalDate.parse(it, DateTimeFormatter.ISO_DATE) } ?: defaultQueryDate(loaded)
    println("query date: $date (dataset calendar ${loaded.meta.calendarStart}..${loaded.meta.calendarEnd})")

    runQueries(loaded, date)
}

private fun printStats(model: RouterModel, buildMs: Long) {
    println(
        "model build: ${buildMs}ms — stops=${model.stopCount} routes=${model.routeIds.size} " +
            "patterns=${model.patternCount} (nonFifoPositions=${model.patternFifo.count { !it }}/${model.patternFifo.size}) " +
            "trips=${model.tripCount} stopTimes=${model.arrivals.size} " +
            "transfers=${model.transferTarget.size} services=${model.services.size}"
    )
}

private fun defaultQueryDate(model: RouterModel): LocalDate {
    val start = model.meta.calendarStart
    var date = LocalDate.of(start / 10000, start / 100 % 100, start % 100)
    while (date.dayOfWeek != DayOfWeek.TUESDAY) date = date.plusDays(1)
    return date
}

private fun runQueries(model: RouterModel, date: LocalDate) {
    val router = RaptorRouter(model)

    val canonical = listOf(
        Triple("Flinders St -> Melbourne Central", "vic:rail:FSS", "vic:rail:MCE"),
        Triple("Flagstaff -> Parliament (City Loop)", "vic:rail:FGS", "vic:rail:PAR"),
        Triple("Southern Cross -> Flinders St", "vic:rail:SSS", "vic:rail:FSS"),
        // V/Line pair — only present when folder 1 is ingested.
        Triple("Southern Cross -> Geelong (V/Line)", "vic:rail:SSS", "vic:rail:GEL"),
    )
    for ((label, from, to) in canonical) {
        if (model.stopsForId(from).isEmpty() || model.stopsForId(to).isEmpty()) {
            println("canonical [$label]: skipped (stop ids not in this dataset)")
            continue
        }
        val start = System.nanoTime()
        val journeys = router.plan(from, to, date, 8 * 3600 + 30 * 60)
        val us = (System.nanoTime() - start) / 1000
        val summary = journeys.joinToString(" | ") { j ->
            "dep=${hhmm(j.depSec)} arr=${hhmm(j.arrSec)} transfers=${j.transfers} legs=${j.legs.size}"
        }
        println("canonical [$label]: ${us}us, ${journeys.size} journeys: $summary")
    }

    // Random reachable stop pairs, seeded for reproducibility.
    val rnd = Random(42)
    fun randomStop() = rnd.nextInt(model.stopCount)
    repeat(50) { // warmup
        router.planStops(intArrayOf(randomStop()), intArrayOf(randomStop()), date, 8 * 3600)
    }
    val latenciesUs = ArrayList<Long>(200)
    var reachable = 0
    var attempts = 0
    while (reachable < 200 && attempts < 2000) {
        attempts++
        val from = intArrayOf(randomStop())
        val to = intArrayOf(randomStop())
        val depSec = (6 + rnd.nextInt(16)) * 3600 + rnd.nextInt(60) * 60
        val start = System.nanoTime()
        val journeys = router.planStops(from, to, date, depSec)
        val us = (System.nanoTime() - start) / 1000
        if (journeys.isNotEmpty()) {
            reachable++
            latenciesUs.add(us)
        }
    }
    if (latenciesUs.isEmpty()) {
        println("random pairs: none reachable in $attempts attempts — check folder set")
        return
    }
    latenciesUs.sort()
    fun pct(p: Double): Long = latenciesUs[((latenciesUs.size - 1) * p).toInt()]
    println(
        "random pairs: $reachable reachable of $attempts attempts; latency " +
            "p50=${pct(0.50) / 1000.0}ms p95=${pct(0.95) / 1000.0}ms " +
            "p99=${pct(0.99) / 1000.0}ms max=${latenciesUs.last() / 1000.0}ms"
    )
}

private fun hhmm(sec: Int): String = "%02d:%02d".format(sec / 3600, sec % 3600 / 60)

private fun forceGc() {
    repeat(3) {
        System.gc()
        Thread.sleep(100)
    }
}
