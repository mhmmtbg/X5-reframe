package com.mhmmtbg.x5reframe.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs

/**
 * Ortası sabit oynatma çizgili, kaydırılan zaman çizelgesi (Insta360 düzenleyicisi gibi):
 * küçük kare şeridi altta akar, beyaz çizgi hep ortadadır. Parmakla kaydırınca zaman ilerler,
 * iki parmakla yakınlaştırılır. Kesme tutamakları ve anahtar kareler şeridin üstünde çizilir.
 */
class TimelineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onScrub(us: Long, fromUser: Boolean)
        fun onScrubStart()
        fun onScrubEnd()
        fun onTrimChanged(inUs: Long, outUs: Long)
        /** Kesme tutamağı sürüklenirken o kareyi göster (oynatma konumu değişmez). */
        fun onPeek(us: Long) {}
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
    /** Takip edilen aralıklar (Derin takip), şeridin altında yeşil çizgi. */
    var trackRanges: List<LongRange> = emptyList()
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Klip boyunca eşit aralıklı küçük kareler (isteğe bağlı). */
    var filmstrip: List<android.graphics.Bitmap> = emptyList()
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    private val trackH = 48 * dp
    private val handleW = 14 * dp
    private val top = 30 * dp
    private val bottom get() = top + trackH

    /** Yakınlaştırma: saniye başına piksel. */
    private var pxPerSec = 56 * dp
    private val pxPerUs get() = pxPerSec / 1_000_000f

    private val pStrip = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pDim = Paint().apply { color = 0xB0000000.toInt() }
    private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22262D.toInt() }
    private val pHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC94D.toInt() }
    private val pHandleMark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A1A.toInt(); strokeWidth = 2 * dp }
    private val pKey = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6EC6FF.toInt() }
    private val pKeyFlash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val pHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 2 * dp }
    private val pTick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5A6270.toInt(); strokeWidth = 1 * dp }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A96A6.toInt(); textSize = 10 * dp }
    private val pTrackRange = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3DDC84.toInt() }
    private val src = android.graphics.Rect()
    private val rect = RectF()
    private val diamond = Path()

    private var dark = true

    /** Açık / koyu tema renkleri. */
    fun setDark(d: Boolean) {
        if (d == dark) return
        dark = d
        pTrack.color = if (d) 0xFF1B1D21.toInt() else 0xFFE1E5EA.toInt()
        pDim.color = if (d) 0xB0000000.toInt() else 0xB0F5F6F8.toInt()
        pHandle.color = if (d) 0xFFFFC94D.toInt() else 0xFFC77A00.toInt()
        pKey.color = if (d) 0xFF6EC6FF.toInt() else 0xFF1565C0.toInt()
        pKeyFlash.color = if (d) 0xFFFFFFFF.toInt() else 0xFF12151A.toInt()
        pHead.color = if (d) 0xFFFFFFFF.toInt() else 0xFF12151A.toInt()
        pTick.color = if (d) 0xFF5A6270.toInt() else 0xFFA0A8B3.toInt()
        pText.color = if (d) 0xFFA3ABB7.toInt() else 0xFF59626E.toInt()
        pTrackRange.color = if (d) 0xFF3DDC84.toInt() else 0xFF1E9E57.toInt()
        invalidate()
    }

    private var flashKeyUs = -1L
    private var flashUntil = 0L

    fun flashKeyframe(us: Long) {
        flashKeyUs = us
        flashUntil = System.currentTimeMillis() + 700
        invalidate()
        postDelayed({ invalidate() }, 750)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (top + trackH + 14 * dp).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    private fun xOf(us: Long) = width / 2f + (us - positionUs) * pxPerUs
    private fun dxToUs(dx: Float) = (dx / pxPerUs).toLong()

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val x0 = xOf(0); val x1 = xOf(durationUs)
        // Zaman ölçeği
        val stepSec = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0).first { it * pxPerSec >= 64 * dp }
        val stepUs = (stepSec * 1e6).toLong()
        var t = (((positionUs - dxToUs(cx)) / stepUs) - 1).coerceAtLeast(0) * stepUs
        while (t <= durationUs) {
            val x = xOf(t)
            if (x > width + 40 * dp) break
            if (x >= -40 * dp) {
                c.drawLine(x, top - 8 * dp, x, top - 3 * dp, pTick)
                c.drawText(label(t), x + 3 * dp, top - 10 * dp, pText)
            }
            t += stepUs
        }
        // Şerit
        rect.set(maxOf(x0, 0f), top, minOf(x1, width.toFloat()), bottom)
        c.drawRect(rect, pTrack)
        if (filmstrip.isNotEmpty()) {
            val n = filmstrip.size
            val cellW = trackH * 16f / 9f
            c.save()
            c.clipRect(maxOf(x0, 0f), top, minOf(x1, width.toFloat()), bottom)
            var left = x0 + ((-x0) / cellW).toInt().coerceAtLeast(0) * cellW
            while (left < minOf(x1, width.toFloat())) {
                val tm = (positionUs + dxToUs(left + cellW / 2 - cx)).coerceIn(0, durationUs - 1)
                val b = filmstrip[(tm * n / durationUs).toInt().coerceIn(0, n - 1)]
                val scale = maxOf(cellW / b.width, trackH / b.height)
                val sw = (cellW / scale).toInt(); val sh = (trackH / scale).toInt()
                val sx = (b.width - sw) / 2; val sy = (b.height - sh) / 2
                src.set(sx, sy, sx + sw, sy + sh)
                rect.set(left, top, left + cellW + 1, bottom)
                c.drawBitmap(b, src, rect, pStrip)
                left += cellW
            }
            c.restore()
        }
        // Kesme dışı karartılır; seçili aralık sarı çerçeve
        val xi = xOf(inUs); val xo = xOf(outUs)
        if (xi > x0) { rect.set(x0, top, xi, bottom); c.drawRect(rect, pDim) }
        if (xo < x1) { rect.set(xo, top, x1, bottom); c.drawRect(rect, pDim) }
        rect.set(xi, top, xo, top + 2.5f * dp); c.drawRect(rect, pHandle)
        rect.set(xi, bottom - 2.5f * dp, xo, bottom); c.drawRect(rect, pHandle)
        drawHandle(c, xi - handleW)
        drawHandle(c, xo)
        // Derin takip aralıkları
        for (r in trackRanges) {
            rect.set(xOf(r.first), bottom + 4 * dp, xOf(r.last), bottom + 7 * dp)
            c.drawRoundRect(rect, 2 * dp, 2 * dp, pTrackRange)
        }
        // Anahtar kareler (şeridin üst kenarında baklava)
        val now = System.currentTimeMillis()
        for (k in keyframes) {
            val x = xOf(k)
            if (x < -10 * dp || x > width + 10 * dp) continue
            val s = 6 * dp
            val ky = top
            diamond.reset()
            diamond.moveTo(x, ky - s); diamond.lineTo(x + s, ky); diamond.lineTo(x, ky + s); diamond.lineTo(x - s, ky); diamond.close()
            c.drawPath(diamond, if (k == flashKeyUs && now < flashUntil) pKeyFlash else pKey)
        }
        // Sabit oynatma çizgisi
        c.drawLine(cx, top - 4 * dp, cx, bottom + 4 * dp, pHead)
    }

    private fun drawHandle(c: Canvas, x: Float) {
        rect.set(x, top, x + handleW, bottom)
        c.drawRoundRect(rect, 3 * dp, 3 * dp, pHandle)
        val mx = x + handleW / 2
        c.drawLine(mx, top + trackH * 0.32f, mx, bottom - trackH * 0.32f, pHandleMark)
    }

    // ---- Dokunma ----

    private enum class Drag { NONE, SCROLL, IN, OUT }
    private var drag = Drag.NONE
    private var lastX = 0f
    private var velocity: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private var flingLastX = 0

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            pxPerSec = (pxPerSec * d.scaleFactor).coerceIn(6 * dp, 600 * dp)
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.forceFinished(true)
                val xi = xOf(inUs) - handleW / 2
                val xo = xOf(outUs) + handleW / 2
                val slop = 20 * dp
                val onTrack = e.y >= top - 12 * dp && e.y <= bottom + 12 * dp
                drag = when {
                    onTrack && abs(e.x - xi) < slop && abs(e.x - xi) <= abs(e.x - xo) -> Drag.IN
                    onTrack && abs(e.x - xo) < slop -> Drag.OUT
                    else -> Drag.SCROLL
                }
                if (drag != Drag.SCROLL) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                lastX = e.x
                listener?.onScrubStart()
            }
            MotionEvent.ACTION_POINTER_DOWN -> drag = Drag.NONE
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1 || scale.isInProgress) { lastX = e.x; return true }
                val dx = e.x - lastX
                lastX = e.x
                move(dx)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag == Drag.SCROLL && e.actionMasked == MotionEvent.ACTION_UP) {
                    velocity?.computeCurrentVelocity(1000)
                    val vx = velocity?.xVelocity ?: 0f
                    if (abs(vx) > 600) {
                        flingLastX = 0
                        scroller.fling(0, 0, (-vx).toInt(), 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2, 0, 0)
                        postOnAnimation(flingStep)
                        drag = Drag.NONE
                        velocity?.recycle(); velocity = null
                        return true
                    }
                }
                drag = Drag.NONE
                velocity?.recycle(); velocity = null
                listener?.onScrubEnd()
            }
        }
        return true
    }

    private val flingStep = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) { listener?.onScrubEnd(); return }
            val x = scroller.currX
            val d = (x - flingLastX).toFloat()
            flingLastX = x
            val before = positionUs
            positionUs = (positionUs + dxToUs(d)).coerceIn(0, durationUs)
            listener?.onScrub(positionUs, true)
            if (positionUs == before && d != 0f) { scroller.forceFinished(true); listener?.onScrubEnd(); return }
            postOnAnimation(this)
        }
    }

    private fun move(dx: Float) {
        val minGap = 200_000L
        when (drag) {
            Drag.IN -> {
                inUs = (inUs + dxToUs(dx)).coerceIn(0, outUs - minGap)
                listener?.onTrimChanged(inUs, outUs)
                listener?.onPeek(inUs)
            }
            Drag.OUT -> {
                outUs = (outUs + dxToUs(dx)).coerceIn(inUs + minGap, durationUs)
                listener?.onTrimChanged(inUs, outUs)
                listener?.onPeek(outUs)
            }
            Drag.SCROLL -> {
                // Şerit parmakla birlikte kayar: sola çekmek ileri sarar.
                positionUs = (positionUs - dxToUs(dx)).coerceIn(0, durationUs)
                listener?.onScrub(positionUs, true)
            }
            Drag.NONE -> {}
        }
    }

    private fun label(us: Long): String {
        val s = us / 1_000_000.0
        return if (s % 1.0 == 0.0) "%d:%02d".format((s / 60).toInt(), (s % 60).toInt()) else "%.1f".format(s % 60)
    }
}
