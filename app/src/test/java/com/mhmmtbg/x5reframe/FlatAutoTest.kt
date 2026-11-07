package com.mhmmtbg.x5reframe

import com.mhmmtbg.x5reframe.auto.FlatAuto
import com.mhmmtbg.x5reframe.model.Aspect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatAutoTest {
    private fun samples(n: Int, f: (Int) -> FlatAuto.Sample) = (0 until n).map(f)

    @Test
    fun highlightsPickActiveMoments() {
        // 0–10 sn sakin, 10–14 sn hareketli + sesli, sonra sakin
        val s = samples(60) { i ->
            val t = i * 400_000L
            val hot = t in 10_000_000..14_000_000
            FlatAuto.Sample(t, if (hot) 0.2f else 0.01f, if (hot) 0.8f else 0.05f, Float.NaN, Float.NaN, 0f)
        }
        val r = FlatAuto.highlights(s, 3_000_000, 3_000_000)
        assertEquals(1, r.size)
        assertTrue(r[0].first in 9_600_000..11_600_000)
    }

    @Test
    fun reframeFollowsFaceWithinBounds() {
        // Yüz sağda (x = 0.8) → dikey çıktıda kadraj sağa kayar ama kenarı aşmaz
        val s = samples(30) { i -> FlatAuto.Sample(i * 400_000L, 0f, 0f, 0.8f, 0.5f, 0.2f) }
        val k = FlatAuto.reframe(s, 16f / 9f, Aspect.PORTRAIT)
        assertTrue(k.isNotEmpty())
        val cx = k.last().view.yaw / 45.0
        // 16:9 kaynak, 9:16 çıktı (doldur): hx = 16/9, ekran yarı genişliği 9/16
        val max = 16.0 / 9 - 9.0 / 16
        assertTrue("kaymadı: $cx", cx > 0.5)
        assertTrue("kenarı aştı: $cx", cx <= max + 1e-6)
    }

    @Test
    fun findsSilences() {
        val bins = FloatArray(200) { i -> if (i in 60..100) 0.01f else 0.6f + 0.2f * kotlin.math.sin(i * 0.7f) }
        val sil = FlatAuto.silences(bins, 0, 200 * 50_000L)
        assertEquals(1, sil.size)
        assertTrue(sil[0].first in 3_000_000..3_400_000 && sil[0].last in 4_800_000..5_100_000)
    }
}
