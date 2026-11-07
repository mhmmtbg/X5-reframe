package com.mhmmtbg.x5reframe

import com.mhmmtbg.x5reframe.auto.AutoDirector
import com.mhmmtbg.x5reframe.auto.AutoEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class AutoDirectorTest {

    /** 30 sn sahne: çekimi yapan kişi hep ~170°'de; 10–16 sn arası 20°'de başka biri konuşuyor (ses yüksek). */
    private fun scene(): List<AutoEditor.Sample> = (0 until 120).map { i ->
        val t = i * 250_000L
        val faces = ArrayList<AutoEditor.Face>()
        faces += AutoEditor.Face(170.0 + 4 * sin(i / 5.0), -10.0, 0.16f)
        if (t in 10_000_000L..16_000_000L) faces += AutoEditor.Face(20.0, 0.0, 0.06f)
        AutoEditor.Sample(t, faces, if (t in 10_000_000L..16_000_000L) 0.8f else 0.1f, 0.2f)
    }

    private fun d(a: Double, b: Double) = abs(AutoDirector.wrap(a - b))

    @Test
    fun findsHolder() {
        val h = AutoDirector.holderTrack(scene())
        assertNotNull(h)
        assertTrue("çekimi yapan ~170°: ${h!![60]}", d(h[60], 170.0) < 10)
    }

    @Test
    fun singleTakeFollowsOtherPersonThenForward() {
        val keys = AutoDirector.singleTake(scene())
        val during = keys.first { it.timeUs >= 14_000_000L }
        val before = keys.first { it.timeUs >= 5_000_000L }
        assertTrue("konuşan kişiye döner: ${during.view.yaw}", d(during.view.yaw, 20.0) < 15)
        assertTrue("önce ileri (~-10°): ${before.view.yaw}", d(before.view.yaw, 170.0 + 180.0) < 20)
        // Yumuşaklık: ardışık anahtar kareler arasında 0,5 sn'de 30°'den fazla dönüş yok
        keys.zipWithNext().forEach { (a, b) -> assertTrue(d(a.view.yaw, b.view.yaw) <= 26.0) }
    }

    @Test
    fun compilationHasVarietyAndSubject() {
        val shots = AutoDirector.compile(scene(), 3_000_000L, 15_000_000L, first = true)
        assertTrue(shots.size >= 4)
        assertEquals(AutoDirector.ShotType.PLANET_INTRO, shots.first().type)
        assertTrue("konuşma anı seçilir", shots.any { it.type == AutoDirector.ShotType.SUBJECT && it.range.first in 9_000_000L..16_000_000L })
        shots.zipWithNext().forEach { (a, b) -> assertTrue("aynı tür art arda: ${a.type}", a.type != b.type) }
        shots.zipWithNext().forEach { (a, b) -> assertTrue("örtüşme yok", a.range.last <= b.range.first) }
    }

    @Test
    fun beatPeriodFromClicks() {
        val hz = 100.0
        val env = FloatArray(3000) { i -> if (i % 50 == 0) 1f else 0.01f } // 0,5 sn = 120 BPM
        val p = AutoDirector.beatPeriod(env, hz)
        assertNotNull(p)
        assertTrue("vuruş 0,5 sn: $p", abs(p!! - 0.5) < 0.02)
    }
}
