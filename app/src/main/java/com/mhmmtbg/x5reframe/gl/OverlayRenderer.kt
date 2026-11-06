package com.mhmmtbg.x5reframe.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import com.mhmmtbg.x5reframe.model.Layer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Metin ve görsel katmanlarını kadrajlanmış görüntünün üstüne saydamlıkla çizer. */
class OverlayRenderer {
    private val program: Int
    private val vbo: Int
    private val uRect: Int
    private val uAlpha: Int
    private val uTex: Int

    private class Tex(val id: Int, val aspect: Float)
    private val cache = HashMap<String, Tex>()

    init {
        program = GlUtil.link(VS, FS)
        uRect = GLES30.glGetUniformLocation(program, "uRect")
        uAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
        uTex = GLES30.glGetUniformLocation(program, "uTex")
        val quad = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
        buf.position(0)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
    }

    /** @param t parçanın kaynak zamanı (µs) */
    fun draw(width: Int, height: Int, layers: List<Layer>, t: Long) {
        val active = layers.filter { it.activeAt(t) }
        if (active.isEmpty()) return
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(program)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(uTex, 0)
        for (l in active) {
            val tex = texture(l) ?: continue
            val w = l.scale
            val h = w * width / height / tex.aspect
            val x0 = l.x - w / 2; val y0 = l.y - h / 2
            // NDC: x sağa, y yukarı
            GLES30.glUniform4f(uRect, x0 * 2 - 1, 1 - (y0 + h) * 2, w * 2, h * 2)
            GLES30.glUniform1f(uAlpha, l.opacity)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex.id)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun texture(l: Layer): Tex? {
        cache[l.textureKey]?.let { return it }
        val bmp = when (l.kind) {
            Layer.Kind.TEXT -> renderText(l.content, l.style)
            Layer.Kind.IMAGE -> runCatching { BitmapFactory.decodeFile(l.content) }.getOrNull()
        } ?: return null
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        val t = Tex(id[0], bmp.width.toFloat() / bmp.height)
        bmp.recycle()
        cache[l.textureKey] = t
        return t
    }

    /** Silinen katmanların dokularını bırak. */
    fun retain(layers: List<Layer>) {
        val keep = layers.map { it.textureKey }.toSet()
        val drop = cache.keys.filter { it !in keep }
        drop.forEach { k -> cache.remove(k)?.let { GLES30.glDeleteTextures(1, intArrayOf(it.id), 0) } }
    }

    fun release() {
        cache.values.forEach { GLES30.glDeleteTextures(1, intArrayOf(it.id), 0) }
        cache.clear()
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
    }

    companion object {
        /** Metni önceden çarpılmış alfa ile bir bitmap'e çizer (çok satır destekli). */
        fun renderText(text: String, style: Layer.TextStyle): Bitmap {
            val lines = text.ifEmpty { " " }.split('\n')
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = style.fg; textSize = 96f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val lineH = p.fontSpacing
            val pad = if (style.bg ushr 24 != 0) 36f else 8f
            val w = (lines.maxOf { p.measureText(it) } + 2 * pad).toInt().coerceAtLeast(8)
            val h = (lines.size * lineH + 2 * pad).toInt().coerceAtLeast(8)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            if (style.bg ushr 24 != 0) {
                c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.bg })
            } else {
                // Saydam zeminde okunabilirlik için ince kontur
                val stroke = Paint(p).apply { this.style = Paint.Style.STROKE; strokeWidth = 8f; color = 0x99000000.toInt() }
                lines.forEachIndexed { i, s -> c.drawText(s, pad, pad - p.ascent() + i * lineH, stroke) }
            }
            lines.forEachIndexed { i, s -> c.drawText(s, pad, pad - p.ascent() + i * lineH, p) }
            return bmp
        }

        private const val VS = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec4 uRect; // x0, y0 (NDC sol alt), w, h
out vec2 vUv;
void main() {
    vUv = vec2(aPos.x, 1.0 - aPos.y);
    gl_Position = vec4(uRect.x + aPos.x * uRect.z, uRect.y + aPos.y * uRect.w, 0.0, 1.0);
}
"""

        private const val FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform float uAlpha;
out vec4 outColor;
void main() {
    outColor = texture(uTex, vUv) * uAlpha; // önceden çarpılmış alfa
}
"""
    }
}
