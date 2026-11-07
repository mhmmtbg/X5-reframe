package com.mhmmtbg.x5reframe

import com.mhmmtbg.x5reframe.media.TimeStretch
import com.mhmmtbg.x5reframe.model.SpeedCurve
import com.mhmmtbg.x5reframe.model.SpeedMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeedTest {
    @Test
    fun linearMap() {
        val m = SpeedMap(10_000_000, 2.0, SpeedCurve.NONE)
        assertEquals(5_000_000, m.outputUs)
        assertEquals(4_000_000, m.sourceAt(2_000_000))
    }

    @Test
    fun curveMapIsMonotonicAndCoversSource() {
        for (c in SpeedCurve.values()) {
            val m = SpeedMap(6_000_000, 1.0, c)
            var prev = -1L
            for (k in 0..100) {
                val s = m.sourceAt(m.outputUs * k / 100)
                assertTrue("$c geri gitti", s >= prev); prev = s
            }
            assertEquals(0L, m.sourceAt(0))
            assertTrue("$c sona ulaşmadı: $prev", prev >= 5_990_000)
        }
        // Montaj: ortası hızlı olduğundan çıktının ortasında kaynağın yarısı geçilmiş olur, başta yavaş
        val mt = SpeedMap(6_000_000, 1.0, SpeedCurve.MONTAGE)
        assertTrue(mt.sourceAt(mt.outputUs / 4) < 6_000_000 / 4)
    }

    /** Sinüs: hızlandırılınca süre kısalır, perde (sıfır geçiş sıklığı) korunur. */
    @Test
    fun timeStretchKeepsPitch() {
        val freq = 440.0
        var n = 0L
        val src: (Int, ShortArray) -> Unit = { cnt, buf ->
            for (i in 0 until cnt) {
                val v = (sin(2 * PI * freq * n / 48_000) * 12_000).toInt().toShort()
                buf[i * 2] = v; buf[i * 2 + 1] = v; n++
            }
        }
        for (speed in listOf(0.5, 2.0, 3.0)) {
            n = 0
            val ts = TimeStretch(speed, src)
            val frames = 48_000
            val out = ShortArray(frames * 2)
            var done = 0
            while (done < frames) {
                val m = minOf(1024, frames - done)
                val tmp = ShortArray(m * 2)
                ts.read(m, tmp, 1f)
                System.arraycopy(tmp, 0, out, done * 2, m * 2)
                done += m
            }
            // İlk pencereyi atla, sıfır geçişlerini say
            var zc = 0
            for (i in 4800 until frames - 1) if ((out[i * 2] >= 0) != (out[(i + 1) * 2] >= 0)) zc++
            val f = zc / 2.0 / ((frames - 4801) / 48_000.0)
            assertTrue("hız $speed: perde $f Hz", f in 420.0..460.0)
            // Tüketilen girdi ≈ hız × çıktı
            val ratio = n.toDouble() / frames
            assertTrue("hız $speed: girdi oranı $ratio", ratio in speed * 0.9..speed * 1.15 + 0.1)
        }
    }
}
