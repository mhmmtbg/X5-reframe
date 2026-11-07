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
    private val uRot: Int
    private val uAspect: Int
    private val uMask: Int
    private val uFlip: Int
    private val uKey: Int
    private val uReveal: Int
    private val uKeyP: Int

    private class Tex(val id: Int, val aspect: Float)
    private val cache = HashMap<String, Tex>()

    init {
        program = GlUtil.link(VS, FS)
        uRect = GLES30.glGetUniformLocation(program, "uRect")
        uAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
        uTex = GLES30.glGetUniformLocation(program, "uTex")
        uRot = GLES30.glGetUniformLocation(program, "uRot")
        uAspect = GLES30.glGetUniformLocation(program, "uAspect")
        uMask = GLES30.glGetUniformLocation(program, "uMask")
        uFlip = GLES30.glGetUniformLocation(program, "uFlip")
        uKey = GLES30.glGetUniformLocation(program, "uKey")
        uReveal = GLES30.glGetUniformLocation(program, "uReveal")
        uKeyP = GLES30.glGetUniformLocation(program, "uKeyP")
        val quad = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
        buf.position(0)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
    }

    /** @param t parçanın kaynak zamanı (µs); [segEndUs] "sona kadar" katmanların bitişi (çıkış animasyonu için) */
    fun draw(width: Int, height: Int, layers: List<Layer>, t: Long, segEndUs: Long = Long.MAX_VALUE, ox: Int = 0, oy: Int = 0) {
        val active = layers.filter { it.activeAt(t) }
        if (active.isEmpty()) return
        GLES30.glViewport(ox, oy, width, height)
        GLES30.glUseProgram(program)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glUniform1f(uAspect, width.toFloat() / height)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(uTex, 0)
        for (l in active) {
            val tex = texture(l) ?: continue
            val an = l.animAt(t, segEndUs)
            if (an[0] <= 0.001f) continue
            val w = l.scale * an[3]
            val h = w * width / height / tex.aspect
            // NDC: x sağa, y yukarı; merkez ve yarı boyutlar
            GLES30.glUniform4f(uRect, (l.x + an[1]) * 2 - 1, 1 - (l.y + an[2]) * 2, w, h)
            GLES30.glUniform1f(uReveal, an[4])
            GLES30.glUniform1f(uRot, Math.toRadians(l.rotation.toDouble()).toFloat())
            GLES30.glUniform1i(uMask, l.mask.ordinal)
            GLES30.glUniform1f(uFlip, if (l.flipH) 1f else 0f)
            val k = l.chroma
            GLES30.glUniform4f(uKey, ((k.color shr 16) and 255) / 255f, ((k.color shr 8) and 255) / 255f, (k.color and 255) / 255f, if (k.on && l.kind == Layer.Kind.IMAGE) 1f else 0f)
            GLES30.glUniform4f(uKeyP, k.similarity, k.smooth.coerceAtLeast(0.001f), k.spill, 0f)
            GLES30.glUniform1f(uAlpha, l.opacity * an[0])
            when (l.blend) {
                Layer.Blend.NORMAL -> GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                Layer.Blend.SCREEN -> GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_COLOR)
                Layer.Blend.MULTIPLY -> GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                Layer.Blend.ADD -> GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex.id)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun texture(l: Layer): Tex? {
        cache[l.textureKey]?.let { return it }
        val bmp = when (l.kind) {
            Layer.Kind.TEXT -> renderText(l.content, l.style, l.font, l.color, l.align)
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
        fun renderText(text: String, style: Layer.TextStyle, font: Layer.Font = Layer.Font.SANS_BOLD, textColor: Int = 0, align: Layer.Align = Layer.Align.CENTER): Bitmap {
            val lines = text.ifEmpty { " " }.split('\n')
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = if (textColor != 0) textColor else style.fg; textSize = 96f
                typeface = Typeface.create(Typeface.create(font.family, Typeface.NORMAL), if (font.bold) Typeface.BOLD else Typeface.NORMAL)
            }
            val lineH = p.fontSpacing
            val pad = if (style.bg ushr 24 != 0) 36f else 8f
            val w = (lines.maxOf { p.measureText(it) } + 2 * pad).toInt().coerceAtLeast(8)
            fun xOf(s: String) = when (align) {
                Layer.Align.LEFT -> pad
                Layer.Align.CENTER -> (w - p.measureText(s)) / 2
                Layer.Align.RIGHT -> w - pad - p.measureText(s)
            }
            val h = (lines.size * lineH + 2 * pad).toInt().coerceAtLeast(8)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            if (style.bg ushr 24 != 0) {
                c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.bg })
            } else if (style != Layer.TextStyle.PLAIN) {
                // Saydam zeminde okunabilirlik için ince kontur
                val stroke = Paint(p).apply { this.style = Paint.Style.STROKE; strokeWidth = 8f; color = 0x99000000.toInt() }
                lines.forEachIndexed { i, s -> c.drawText(s, xOf(s), pad - p.ascent() + i * lineH, stroke) }
            }
            lines.forEachIndexed { i, s -> c.drawText(s, xOf(s), pad - p.ascent() + i * lineH, p) }
            return bmp
        }

        private const val VS = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec4 uRect;    // merkez x, y (NDC), yarı genişlik, yarı yükseklik (NDC)
uniform float uRot;    // saat yönünde (radyan)
uniform float uAspect; // çıktı genişlik / yükseklik
uniform float uFlip;
out vec2 vUv;
void main() {
    vUv = vec2(mix(aPos.x, 1.0 - aPos.x, uFlip), 1.0 - aPos.y);
    vec2 p = (aPos * 2.0 - 1.0) * uRect.zw;
    p.x *= uAspect;                       // kare piksel uzayında döndür
    float c = cos(uRot), s = sin(uRot);
    p = vec2(c * p.x + s * p.y, -s * p.x + c * p.y);
    p.x /= uAspect;
    gl_Position = vec4(uRect.xy + p, 0.0, 1.0);
}
"""

        private const val FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform float uAlpha;
uniform int uMask;   // 0 yok, 1 yuvarlak köşe, 2 daire
uniform vec4 uKey;   // renk anahtarı rgb, açık mı
uniform float uReveal; // daktilo: soldan görünen oran
uniform vec4 uKeyP;  // benzerlik, yumuşaklık, taşma
out vec4 outColor;
vec2 cbcr(vec3 c) { return vec2(-0.1687 * c.r - 0.3313 * c.g + 0.5 * c.b, 0.5 * c.r - 0.4187 * c.g - 0.0813 * c.b); }
void main() {
    float m = 1.0;
    vec2 d = abs(vUv - 0.5) * 2.0;
    if (uMask == 2) {
        float r = length(vUv - 0.5) * 2.0;
        m = 1.0 - smoothstep(0.98, 1.0, r);
    } else if (uMask == 1) {
        vec2 q = max(d - (1.0 - 0.24), 0.0) / 0.24;
        m = 1.0 - smoothstep(0.92, 1.0, length(q));
    }
    vec4 t = texture(uTex, vUv);
    if (uKey.w > 0.5 && t.a > 0.0) {
        vec3 c = t.rgb / t.a;
        float d = distance(cbcr(c), cbcr(uKey.rgb));
        float a = smoothstep(uKeyP.x * 0.3, uKeyP.x * 0.3 + uKeyP.y * 0.3, d);
        if (uKey.g >= uKey.r && uKey.g >= uKey.b) c.g = mix(c.g, min(c.g, (c.r + c.b) * 0.5 + 0.02), uKeyP.z);
        else if (uKey.b >= uKey.r) c.b = mix(c.b, min(c.b, (c.r + c.g) * 0.5 + 0.02), uKeyP.z);
        t = vec4(c * t.a * a, t.a * a);
    }
    if (uReveal < 1.0) m *= 1.0 - smoothstep(uReveal - 0.02, uReveal, vUv.x);
    outColor = t * uAlpha * m; // önceden çarpılmış alfa
}
"""
    }
}
