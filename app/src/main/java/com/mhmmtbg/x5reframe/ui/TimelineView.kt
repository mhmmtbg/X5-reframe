package com.mhmmtbg.x5reframe.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Zaman çizelgesi: kesme aralığı (koyu dışarısı), sürüklenebilir başlangıç/bitiş tutamakları,
 * anahtar kare noktaları ve oynatma çizgisi. Düz çizim, gölge yok.
 */
class TimelineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onScrub(us: Long, fromUser: Boolean)
        fun onScrubStart()
        fun onScrubEnd()
        fun onTrimChanged(inUs: Long, outUs: Long)
    }

    var listener: Listener? = null

    var durationUs = 1L
        set(v) { val n = v.coerceAtLeast(1); if (field != n) { field = n; invalidate() } }
    var positionUs = 0L
        set(v) { if (field != v) { field = v; invalidate() } }
    var inUs = 0L
        set(v) { if (field != v) { field = v; invalidate() } }
    var outUs = 1L
        set(v) { if (field != v) { field = v; invalidate() } }
    var keyframes: List<Long> = emptyList()
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Klip boyunca eşit aralıklı küçük kareler (isteğe bağlı). */
    var filmstrip: List<android.graphics.Bitmap> = emptyList()
        set(v) { field = v; invalidate() }
    private val pStrip = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pDim = Paint().apply { color = 0xA0000000.toInt() }
    private val src = android.graphics.Rect()
    /** Kısa süreli vurgulanacak anahtar kare (eklenince yanıp söner). */
    private var flashKeyUs = -1L
    private var flashUntil = 0L

    private val dp = resources.displayMetrics.density
    private val trackH = 36 * dp
    private val handleW = 14 * dp
    private val pad = handleW

    private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2A2A.toInt() }
    private val pRange = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D4A5C.toInt() }
    private val pHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC94D.toInt() }
    private val pHandleMark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A1A.toInt(); strokeWidth = 2 * dp }
    private val pKey = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6EC6FF.toInt() }
    private val pKeyFlash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val pHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 2 * dp }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFAAAAAA.toInt(); textSize = 11 * dp }
    private val rect = RectF()
    private val diamond = Path()

    private enum class Drag { NONE, SCRUB, IN, OUT }
    private var drag = Drag.NONE

    fun flashKeyframe(us: Long) {
        flashKeyUs = us
        flashUntil = System.currentTimeMillis() + 700
        invalidate()
        postDelayed({ invalidate() }, 750)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (trackH + 28 * dp).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    private fun xOf(us: Long) = pad + (width - 2 * pad) * (us.toFloat() / durationUs)
    private fun usOf(x: Float) = (((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * durationUs).toLong()

    override fun onDraw(c: Canvas) {
        val top = 16 * dp
        val bottom = top + trackH
        // Tüm klip
        rect.set(pad, top, width - pad, bottom)
        c.drawRoundRect(rect, 4 * dp, 4 * dp, pTrack)
        val xi = xOf(inUs); val xo = xOf(outUs)
        if (filmstrip.isNotEmpty()) {
            // Küçük kareler; kesme dışı kısım karartılır.
            val n = filmstrip.size
            val cellW = (width - 2 * pad) / n
            c.save()
            c.clipRect(pad, top, width - pad, bottom)
            for (i in 0 until n) {
                val b = filmstrip[i]
                val left = pad + i * cellW
                // Kareyi hücreye kırparak sığdır.
                val scale = maxOf(cellW / b.width, trackH / b.height)
                val sw = (cellW / scale).toInt(); val sh = (trackH / scale).toInt()
                val sx = (b.width - sw) / 2; val sy = (b.height - sh) / 2
                src.set(sx, sy, sx + sw, sy + sh)
                rect.set(left, top, left + cellW + 1, bottom)
                c.drawBitmap(b, src, rect, pStrip)
            }
            c.restore()
            rect.set(pad, top, xi, bottom); c.drawRect(rect, pDim)
            rect.set(xo, top, width - pad, bottom); c.drawRect(rect, pDim)
            rect.set(xi, top, xo, top + 3 * dp); c.drawRect(rect, pHandle)
            rect.set(xi, bottom - 3 * dp, xo, bottom); c.drawRect(rect, pHandle)
        } else {
            // Kesme aralığı
            rect.set(xi, top, xo, bottom)
            c.drawRect(rect, pRange)
        }
        // Tutamaklar
        drawHandle(c, xi - handleW, top, bottom)
        drawHandle(c, xo, top, bottom)
        // Anahtar kareler (baklava)
        val now = System.currentTimeMillis()
        val cy = top + trackH / 2
        for (k in keyframes) {
            val x = xOf(k)
            val s = 6 * dp
            diamond.reset()
            diamond.moveTo(x, cy - s); diamond.lineTo(x + s, cy); diamond.lineTo(x, cy + s); diamond.lineTo(x - s, cy); diamond.close()
            c.drawPath(diamond, if (k == flashKeyUs && now < flashUntil) pKeyFlash else pKey)
        }
        // Oynatma çizgisi
        val xp = xOf(positionUs)
        c.drawLine(xp, top - 6 * dp, xp, bottom + 6 * dp, pHead)
        // Süre etiketleri
        c.drawText(fmt(inUs), pad, bottom + 18 * dp, pText)
        val outLabel = fmt(outUs)
        c.drawText(outLabel, width - pad - pText.measureText(outLabel), bottom + 18 * dp, pText)
    }

    private fun drawHandle(c: Canvas, x: Float, top: Float, bottom: Float) {
        rect.set(x, top, x + handleW, bottom)
        c.drawRoundRect(rect, 3 * dp, 3 * dp, pHandle)
        val mx = x + handleW / 2
        c.drawLine(mx, top + trackH * 0.3f, mx, bottom - trackH * 0.3f, pHandleMark)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val xi = xOf(inUs) - handleW / 2
                val xo = xOf(outUs) + handleW / 2
                val slop = 18 * dp
                drag = when {
                    abs(e.x - xi) < slop && abs(e.x - xi) <= abs(e.x - xo) -> Drag.IN
                    abs(e.x - xo) < slop -> Drag.OUT
                    else -> Drag.SCRUB
                }
                if (drag != Drag.SCRUB) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                listener?.onScrubStart()
                move(e.x)
            }
            MotionEvent.ACTION_MOVE -> move(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = Drag.NONE
                listener?.onScrubEnd()
            }
        }
        return true
    }

    private fun move(x: Float) {
        val minGap = 200_000L
        when (drag) {
            Drag.IN -> {
                inUs = usOf(x + handleW / 2).coerceIn(0, outUs - minGap)
                listener?.onTrimChanged(inUs, outUs)
                listener?.onScrub(inUs, true)
            }
            Drag.OUT -> {
                outUs = usOf(x - handleW / 2).coerceIn(inUs + minGap, durationUs)
                listener?.onTrimChanged(inUs, outUs)
                listener?.onScrub(outUs, true)
            }
            Drag.SCRUB -> {
                positionUs = usOf(x)
                listener?.onScrub(positionUs, true)
            }
            Drag.NONE -> {}
        }
    }

    private fun fmt(us: Long): String {
        val s = us / 1_000_000.0
        return "%d:%04.1f".format((s / 60).toInt(), s % 60)
    }
}
