package io.github.danielkarp.trainfusion.core

import io.github.danielkarp.trainfusion.core.inference.Belief
import io.github.danielkarp.trainfusion.core.inference.InferenceEngine
import io.github.danielkarp.trainfusion.core.inference.Model
import io.github.danielkarp.trainfusion.core.inference.Replay
import io.github.danielkarp.trainfusion.core.inference.ReplayControl
import io.github.danielkarp.trainfusion.core.inference.SampleRide
import io.github.danielkarp.trainfusion.core.recording.Ride
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * The sample ride (docs/recorder.md, "The sample ride"), generated and played through the
 * real features and engine, as the app plays it back.
 */
class SampleRideTest {
    private val model = Model.load(File(System.getProperty("repo.root"), "ios/TrainRecorder/model.json"))

    /** Every second through the engine (the first `seconds`), as replay does it. */
    private fun play(sample: SampleRide, seconds: Int = sample.duration.toInt()): List<Belief> {
        val engine = InferenceEngine(model)
        val out = ArrayList<Belief>()
        for (second in 0 until seconds) {
            sample.feed(second, engine.features)
            out.addAll(engine.process())
        }
        return out
    }

    @Test
    fun theModelHearsItsStopsAndNamesItsStations() {
        val sample = SampleRide(model)
        assertEquals(15, sample.stops.size)
        assertEquals("San Francisco 4th & King Street", sample.stops.first().station)
        assertEquals("Palo Alto", sample.stops.last().station)
        assertTrue("about a ride's length: ${sample.duration}", sample.duration in 1200.0..3600.0)

        val beliefs = play(sample)
        val last = beliefs.last()
        assertEquals("a door opening heard at every stop", sample.stops.size, last.stops)
        // each heard within a few seconds of its door chime
        for ((stop, heard) in sample.stops.zip(last.stopLog)) {
            assertTrue("${stop.station}: chime ${stop.opened}, heard ${heard.opened}", heard.opened - stop.opened in -2.0..6.0)
        }
        // and, at the end, every station named, going south
        assertEquals(sample.stops.map { it.station }, last.stopLog.map { it.station })
        assertTrue(last.stations.first().southbound)
        assertTrue("sure of it: ${last.stations.first().p}", last.stations.first().p > 0.9)
    }

    @Test
    fun sameSeedSameRide() {
        val sample = SampleRide(model)
        val first = play(sample, 300).map { it.speedMean }
        sample.rewind()
        assertEquals(first, play(sample, 300).map { it.speedMean })
    }

    @Test
    fun replayKnowsTheSampleByItsMetaJson() {
        val sample = SampleRide(model)
        val dir = Files.createTempDirectory("sample").toFile().resolve("20000102T030405Z").also { it.mkdirs() }
        File(dir, "meta.json").writeText(sample.meta(1_000_000.0).toString())
        assertTrue(Replay.isSample(dir))
        val ride = Ride.read(dir, dir.name)
        assertTrue(ride.isSample)
        assertEquals("Sample: San Francisco → Palo Alto", ride.title)
        assertEquals(1_000_000.0 + sample.stops[1].opened, ride.stops[1].doorsOpened, 1e-9)
        // and plays it, to the end, without a single file but meta.json
        class Done : Exception()
        var seconds = 0
        runBlocking {
            try {
                Replay.run(Replay.source(dir, model) { error("no audio file to open") }, model, ReplayControl().apply { speed = null },
                    ByteArrayOutputStream()) { u -> seconds = u.second; if (u.atEnd) throw Done() }
            } catch (_: Done) {
            }
        }
        assertEquals(sample.duration.toInt(), seconds)
        dir.parentFile.deleteRecursively()
    }
}
