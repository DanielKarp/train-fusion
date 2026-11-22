package io.github.danielkarp.trainfusion.core

import io.github.danielkarp.trainfusion.core.inference.InferenceEngine
import io.github.danielkarp.trainfusion.core.inference.Model
import io.github.danielkarp.trainfusion.core.inference.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The engine on made-up sensor data: a phone lying still, quiet audio, and one door-open
 * chime. (How closely the port follows phone-sensor-fusion is checked on whole recordings:
 * android/README.md, "Checking the model's port".)
 */
class InferenceEngineTest {
    private val model = Model.load(File(System.getProperty("repo.root"), "ios/TrainRecorder/model.json"))

    @Test
    fun hearsADoorOpeningAndCountsItAsAStop() {
        val engine = InferenceEngine(model)
        val f = engine.features
        val rng = Random(1)
        val beliefs = ArrayList<io.github.danielkarp.trainfusion.core.inference.Belief>()
        for (sec in 0 until 60) {
            val x = FloatArray(48000) { i ->
                val t = sec + i / 48000.0
                var v = 0.005 * (rng.nextDouble() * 2 - 1)
                if (t in 20.0..21.5) for (p in doubleArrayOf(2200.0, 4400.0, 6600.0)) v += 0.05 * sin(2 * PI * p * t)
                v.toFloat()
            }
            f.addAudio48k(x, t = if (sec == 0) 0.02 else null)
            for (k in 0 until 100) {
                val t = sec + k / 100.0
                f.addAccel(t, Vec3(0.001 * rng.nextDouble(), 0.001 * rng.nextDouble(), -1.0))
                // an iPhone's raw gyro lying still: its bias (~0.008 rad/s) and a little noise
                f.addGyro(t, Vec3(0.006 + 0.001 * rng.nextDouble(), -0.005, 0.002))
            }
            beliefs.addAll(engine.process())
        }
        assertTrue("a belief for (almost) every second", beliefs.size >= 58)
        assertEquals((0 until beliefs.size).toList(), beliefs.map { it.second })
        val last = beliefs.last()
        assertEquals("stops; stop log ${last.stopLog}", 1, last.stops)
        assertEquals(1, last.stopLog.size)
        assertTrue("opened at the chime: ${last.stopLog[0].opened}", last.stopLog[0].opened in 19..22)
        // the phone is lying still
        assertTrue("resting: ${last.phoneP.toList()}", last.phoneP[0] > 0.9)
        // probabilities are probabilities
        assertEquals(1.0, last.modeP.sum(), 1e-6)
        assertEquals(1.0, last.phoneP.sum(), 1e-6)
    }
}
