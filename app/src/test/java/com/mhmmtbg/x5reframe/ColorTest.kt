package com.mhmmtbg.x5reframe

import com.mhmmtbg.x5reframe.model.ColorGrade
import com.mhmmtbg.x5reframe.model.CubeLut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorTest {
    @Test
    fun parsesCube() {
        val text = """
            TITLE "ters"
            # yorum
            LUT_3D_SIZE 2
            1 1 1
            0 1 1
            1 0 1
            0 0 1
            1 1 0
            0 1 0
            1 0 0
            0 0 0
        """.trimIndent()
        val l = CubeLut.parse(text)
        assertEquals(2, l.size)
        assertEquals(24, l.data.size)
        assertEquals(1f, l.data[0]); assertEquals(0f, l.data[3])
    }

    @Test
    fun uniformsAndIdentity() {
        assertTrue(ColorGrade().isIdentity)
        assertFalse(ColorGrade(vignette = 0.5f).isIdentity)
        assertFalse(ColorGrade(hsl = ColorGrade.DEFAULT_HSL.mapIndexed { i, h -> if (i == 2) h.copy(sat = 0.3f) else h }).isIdentity)
        assertTrue(ColorGrade(filter = ColorGrade.Filter.VIVID, filterStrength = 0f).isIdentity)
        val u = ColorGrade(sharpen = 0.4f, curveMid = 0.2f).uniforms()
        assertEquals(ColorGrade.UNIFORM_COUNT, u.size)
        assertEquals(0.4f, u[18]); assertEquals(0.2f, u[21]); assertEquals(-1f, u[19])
    }
}
