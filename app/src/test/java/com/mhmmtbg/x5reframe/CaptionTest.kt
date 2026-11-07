package com.mhmmtbg.x5reframe

import com.mhmmtbg.x5reframe.captions.Captions
import com.mhmmtbg.x5reframe.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CaptionTest {
    /** 1 sn sessizlik, 1,5 sn "konuşma", 0,8 sn sessizlik, 1 sn konuşma, 1 sn sessizlik → iki bölüm. */
    @Test
    fun findsSpeechSegments() {
        val r = Captions.RATE
        val parts = listOf(1.0 to false, 1.5 to true, 0.8 to false, 1.0 to true, 1.0 to false)
        val pcm = ArrayList<Short>()
        var n = 0
        val rnd = java.util.Random(1)
        for ((sec, voice) in parts) repeat((sec * r).toInt()) {
            val noise = rnd.nextGaussian() * 60
            val v = if (voice) 6000 * sin(2 * PI * 220 * n / r) * (0.6 + 0.4 * sin(2 * PI * 3 * n / r)) else 0.0
            pcm += (v + noise).toInt().toShort(); n++
        }
        val segs = Captions.speechSegments(pcm.toShortArray())
        assertEquals(2, segs.size)
        assertTrue(kotlin.math.abs(segs[0].first - r) < r * 0.2)
        assertTrue(kotlin.math.abs(segs[1].first - 3.3 * r) < r * 0.2)
    }

    @Test
    fun wrapsLongLines() {
        val w = Captions.wrap("Bugün dağın zirvesine çıktık ve manzara gerçekten inanılmazdı")
        assertEquals(2, w.split('\n').size)
        assertEquals("Kısa satır", Captions.wrap("Kısa satır"))
    }

    @Test
    fun animationsFadeInAndOut() {
        val l = Layer("a", Layer.Kind.TEXT, "x", startUs = 1_000_000, endUs = 3_000_000, animIn = Layer.Anim.FADE, animOut = Layer.Anim.SLIDE_UP)
        assertEquals(0f, l.animAt(1_000_000, 5_000_000)[0], 0.01f)
        assertEquals(1f, l.animAt(2_000_000, 5_000_000)[0], 0.01f)
        val out = l.animAt(2_900_000, 5_000_000)
        assertTrue(out[0] < 0.5f && out[2] > 0f)
    }
}
