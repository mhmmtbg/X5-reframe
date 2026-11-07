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
 *
 * Şeridin altında şeritler (izler): ses dalga biçimi ve metin / görsel katmanları. Katman çubuğu
 * sürüklenerek taşınır, uçlarından tutularak uzatılır; uçlar oynatma çizgisine, kesme noktalarına,
 * anahtar karelere ve diğer katmanlara yapışır (titreşimle hissedilir).
 */
class TimelineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onScrub(us: Long, fromUser: Boolean)
        fun onScrubStart()
        fun onScrubEnd()
        fun onTrimChanged(inUs: Long, outUs: Long)
        /** Kesme tutamağı sürüklenirken o kareyi göster (oynatma konumu değişmez). */
        fun onPeek(us: Long) {}
        /** Katman çubuğuna dokunuldu. */
        fun onLayerSelected(id: String) {}
        /** Katmanın zamanı sürükleme sırasında değişti; [done] = parmak kalktı (geri alınabilir kayıt). */
        fun onLayerTimeChanged(id: String, startUs: Long, endUs: Long, done: Boolean) {}
    }

    /** Zaman çizelgesindeki bir katman çubuğu. [endUs] çözülmüş (parça sonu yerine gerçek değer). */
    data class LayerBar(val id: String, val label: String, val startUs: Long, val endUs: Long, val image: Boolean, val selected: Boolean)

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
    /** Ses seviyeleri, [waveBinUs] aralıklarla (0..1); null = ses yok / hesaplanıyor. */
    var waveform: FloatArray? = null
        set(v) { field = v; requestLayout(); invalidate() }
    var waveBinUs = 50_000L
    /** Müzik vuruşları (bu parçanın kaynak zamanında); cetvelde nokta, yapışma noktası. */
    var beats: List<Long> = emptyList()
        set(v) { if (field != v) { field = v; invalidate() } }
    var layerBars: List<LayerBar> = emptyList()
        set(v) {
            if (field == v) return
            val rowsBefore = rows(field).second
            field = v
            if (rows(v).second != rowsBefore) requestLayout()
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val trackH = 48 * dp
    private val handleW = 14 * dp
    private val top = 30 * dp
    private val bottom get() = top + trackH
    private val laneGap = 6 * dp
    private val waveH = 20 * dp
    private val barH = 22 * dp
    private val waveTop get() = bottom + 10 * dp
    private val layersTop get() = (if (waveform != null) waveTop + waveH else bottom + 10 * dp) + laneGap

    /** Çakışan katmanlar alt alta: her çubuğun satırı ve toplam satır sayısı (en fazla 3). */
    private fun rows(bars: List<LayerBar>): Pair<IntArray, Int> {
        val ends = LongArray(3) { Long.MIN_VALUE }
        val r = IntArray(bars.size)
        var used = 0
        bars.forEachIndexed { i, b ->
            val row = (0 until 3).firstOrNull { ends[it] <= b.startUs } ?: 2
            r[i] = row; ends[row] = maxOf(ends[row], b.endUs); used = maxOf(used, row + 1)
        }
        return r to used
    }

    /** Yakınlaştırma: saniye başına piksel. */
    private var pxPerSec = 56 * dp
    private val pxPerUs get() = pxPerSec / 1_000_000f

    private val pStrip = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pDim = Paint().apply { color = 0xB0000000.toInt() }
    private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22262D.toInt() }
    private val pHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D6DF2.toInt() }
    private val pHandleMark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 2 * dp }
    private val pKey = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8DAEFF.toInt() }
    private val pKeyFlash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val pHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 2 * dp }
    private val pTick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5A6270.toInt(); strokeWidth = 1 * dp }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A96A6.toInt(); textSize = 10 * dp }
    private val pTrackRange = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3DDC84.toInt() }
    private val pWave = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6B7280.toInt() }
    private val pBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2F35.toInt() }
    private val pBarSel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D6DF2.toInt() }
    private val pBarText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF3F4F6.toInt(); textSize = 11 * dp }
    private val pSnap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8DAEFF.toInt(); strokeWidth = 1.5f * dp }
    private val src = android.graphics.Rect()
    private val rect = RectF()
    private val diamond = Path()

    private var dark = true

    /** Açık / koyu tema renkleri. */
    fun setDark(d: Boolean) {
        if (d == dark) return
        dark = d
        pTrack.color = if (d) 0xFF1C1E22.toInt() else 0xFFE4E6EA.toInt()
        pDim.color = if (d) 0xB0000000.toInt() else 0xB0F7F7F8.toInt()
        pHandle.color = if (d) 0xFF3D6DF2.toInt() else 0xFF2754D6.toInt()
        pKey.color = if (d) 0xFF8DAEFF.toInt() else 0xFF2754D6.toInt()
        pKeyFlash.color = if (d) 0xFFFFFFFF.toInt() else 0xFF12151A.toInt()
        pHead.color = if (d) 0xFFFFFFFF.toInt() else 0xFF12151A.toInt()
        pTick.color = if (d) 0xFF5A6270.toInt() else 0xFFA0A8B3.toInt()
        pText.color = if (d) 0xFFA3ABB7.toInt() else 0xFF59626E.toInt()
        pTrackRange.color = if (d) 0xFF3DDC84.toInt() else 0xFF1E9E57.toInt()
        pWave.color = if (d) 0xFF6B7280.toInt() else 0xFF9AA0AA.toInt()
        pBar.color = if (d) 0xFF2C2F35.toInt() else 0xFFDCDEE3.toInt()
        pBarSel.color = if (d) 0xFF3D6DF2.toInt() else 0xFF2754D6.toInt()
        pBarText.color = if (d) 0xFFF3F4F6.toInt() else 0xFF111318.toInt()
        pSnap.color = if (d) 0xFF8DAEFF.toInt() else 0xFF2754D6.toInt()
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
        val n = rows(layerBars).second
        val h = (layersTop + n * (barH + 4 * dp) + 8 * dp).toInt()
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
        // Müzik vuruşları
        for (b in beats) {
            val x = xOf(b)
            if (x < -4 * dp || x > width + 4 * dp) continue
            c.drawCircle(x, top - 2.5f * dp, 1.6f * dp, pKey)
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
        // Ses dalga biçimi
        waveform?.let { w ->
            val mid = waveTop + waveH / 2
            val step = 2 * dp
            var x = maxOf(x0, 0f)
            val end = minOf(x1, width.toFloat())
            while (x < end) {
                val us = positionUs + dxToUs(x - cx)
                val i = (us / waveBinUs).toInt()
                // Bu sütunun kapsadığı kutulardaki en yüksek değer
                val i2 = ((us + dxToUs(step)) / waveBinUs).toInt()
                var v = 0f
                for (k in i..maxOf(i, i2)) if (k in w.indices) v = maxOf(v, w[k])
                val hh = maxOf(1 * dp, v * waveH / 2)
                c.drawRect(x, mid - hh, x + step * 0.6f, mid + hh, pWave)
                x += step
            }
        }
        // Katman çubukları
        val (rowOf, _) = rows(layerBars)
        layerBars.forEachIndexed { i, b ->
            val y = layersTop + rowOf[i] * (barH + 4 * dp)
            val l = xOf(b.startUs); val r = maxOf(xOf(b.endUs), l + 8 * dp)
            if (r < -10 * dp || l > width + 10 * dp) return@forEachIndexed
            rect.set(l, y, r, y + barH)
            val paint = if (b.selected) pBarSel else pBar
            c.drawRoundRect(rect, 6 * dp, 6 * dp, paint)
            pBarText.color = if (b.selected) 0xFFFFFFFF.toInt() else if (dark) 0xFFF3F4F6.toInt() else 0xFF111318.toInt()
            c.save(); c.clipRect(rect)
            c.drawText((if (b.image) "▣ " else "T  ") + b.label, maxOf(l, 0f) + 8 * dp, y + barH / 2 + 4 * dp, pBarText)
            c.restore()
            if (b.selected) {
                // Uç tutamakları
                rect.set(l, y + 4 * dp, l + 3 * dp, y + barH - 4 * dp); c.drawRect(rect, pHandleMark)
                rect.set(r - 3 * dp, y + 4 * dp, r, y + barH - 4 * dp); c.drawRect(rect, pHandleMark)
            }
        }
        // Yapışma çizgisi
        if (snapUs >= 0 && drag != Drag.NONE && drag != Drag.SCROLL) {
            val x = xOf(snapUs)
            c.drawLine(x, top - 4 * dp, x, height.toFloat(), pSnap)
        }
        // Sabit oynatma çizgisi
        c.drawLine(cx, top - 4 * dp, cx, height - 4 * dp, pHead)
    }

    private fun drawHandle(c: Canvas, x: Float) {
        rect.set(x, top, x + handleW, bottom)
        c.drawRoundRect(rect, 3 * dp, 3 * dp, pHandle)
        val mx = x + handleW / 2
        c.drawLine(mx, top + trackH * 0.32f, mx, bottom - trackH * 0.32f, pHandleMark)
    }

    // ---- Dokunma ----

    private enum class Drag { NONE, SCROLL, IN, OUT, LAYER_MOVE, LAYER_START, LAYER_END }
    private var drag = Drag.NONE
    private var dragLayer: LayerBar? = null
    private var dragStart = 0L
    private var dragEnd = 0L
    private var downX = 0f
    private var moved = false
    /** Son yapışılan zaman (çizgi çizilir); -1 yok. */
    private var snapUs = -1L

    /** Yapışma noktaları: oynatma çizgisi, kesme noktaları, anahtar kareler, diğer katmanların uçları. */
    private fun snapTargets(exclude: String?): List<Long> = buildList {
        add(positionUs); add(inUs); add(outUs); add(0L); add(durationUs)
        addAll(keyframes)
        layerBars.forEach { if (it.id != exclude) { add(it.startUs); add(it.endUs) } }
        addAll(beats)
    }

    /** [us]'ya 10 dp içindeki en yakın yapışma noktası (yan etkisiz). */
    private fun nearest(us: Long, exclude: String?): Long? {
        val tol = dxToUs(10 * dp)
        return snapTargets(exclude).minByOrNull { abs(it - us) }?.takeIf { abs(it - us) <= tol }
    }

    /** Yapışmayı göster; yeni bir noktaya yapışınca titreşim. */
    private fun markSnap(target: Long?) {
        if (target != null && target != snapUs) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        snapUs = target ?: -1
    }

    /** [us]'yu yakındaki bir noktaya yapıştır. */
    private fun snap(us: Long, exclude: String?): Long {
        val t = nearest(us, exclude)
        markSnap(t)
        return t ?: us
    }

    /** Dokunulan katman çubuğu ve hangi kısmı (gövde / sol uç / sağ uç). */
    private fun hitLayer(x: Float, y: Float): Pair<LayerBar, Drag>? {
        val (rowOf, _) = rows(layerBars)
        val edge = 14 * dp
        for (i in layerBars.indices.reversed()) {
            val b = layerBars[i]
            val ty = layersTop + rowOf[i] * (barH + 4 * dp)
            if (y < ty - 4 * dp || y > ty + barH + 4 * dp) continue
            val l = xOf(b.startUs); val r = maxOf(xOf(b.endUs), l + 8 * dp)
            if (x < l - edge || x > r + edge) continue
            return b to when {
                b.selected && abs(x - l) < edge -> Drag.LAYER_START
                b.selected && abs(x - r) < edge -> Drag.LAYER_END
                else -> Drag.LAYER_MOVE
            }
        }
        return null
    }
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
                val hit = if (e.y > bottom + 8 * dp) hitLayer(e.x, e.y) else null
                dragLayer = hit?.first
                drag = when {
                    hit != null -> hit.second
                    onTrack && abs(e.x - xi) < slop && abs(e.x - xi) <= abs(e.x - xo) -> Drag.IN
                    onTrack && abs(e.x - xo) < slop -> Drag.OUT
                    else -> Drag.SCROLL
                }
                hit?.first?.let { dragStart = it.startUs; dragEnd = it.endUs; rawStart = it.startUs; rawEnd = it.endUs }
                rawIn = inUs; rawOut = outUs
                if (drag == Drag.IN || drag == Drag.OUT) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                snapUs = -1
                downX = e.x; moved = false
                lastX = e.x
                listener?.onScrubStart()
            }
            MotionEvent.ACTION_POINTER_DOWN -> drag = Drag.NONE
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1 || scale.isInProgress) { lastX = e.x; return true }
                val dx = e.x - lastX
                lastX = e.x
                if (abs(e.x - downX) > 6 * dp) moved = true
                if (drag in LAYER_DRAGS && !moved) return true
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
                val lay = dragLayer
                if (lay != null && drag in LAYER_DRAGS) {
                    if (!moved) listener?.onLayerSelected(lay.id)
                    else listener?.onLayerTimeChanged(lay.id, dragStart, dragEnd, true)
                }
                dragLayer = null
                drag = Drag.NONE
                snapUs = -1
                invalidate()
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
            Drag.LAYER_MOVE, Drag.LAYER_START, Drag.LAYER_END -> {
                val lay = dragLayer ?: return
                val d = dxToUs(dx)
                val len = dragEnd - dragStart
                rawStart += if (drag != Drag.LAYER_END) d else 0
                rawEnd += if (drag != Drag.LAYER_START) d else 0
                when (drag) {
                    Drag.LAYER_MOVE -> {
                        // Başı ya da sonu (hangisi daha yakınsa) yapışır
                        val s0 = rawStart.coerceIn(0, (durationUs - len).coerceAtLeast(0))
                        val ns = nearest(s0, lay.id); val ne = nearest(s0 + len, lay.id)
                        val useStart = ns != null && (ne == null || abs(ns - s0) <= abs(ne - (s0 + len)))
                        dragStart = when {
                            useStart -> { markSnap(ns); ns!! }
                            ne != null -> { markSnap(ne); ne - len }
                            else -> { markSnap(null); s0 }
                        }.coerceIn(0, (durationUs - len).coerceAtLeast(0))
                        dragEnd = dragStart + len
                    }
                    Drag.LAYER_START -> dragStart = snap(rawStart.coerceIn(0, dragEnd - minGap), lay.id).coerceIn(0, dragEnd - minGap)
                    else -> dragEnd = snap(rawEnd.coerceIn(dragStart + minGap, durationUs), lay.id).coerceIn(dragStart + minGap, durationUs)
                }
                layerBars = layerBars.map { if (it.id == lay.id) it.copy(startUs = dragStart, endUs = dragEnd) else it }
                listener?.onLayerTimeChanged(lay.id, dragStart, dragEnd, false)
            }
            Drag.IN -> {
                rawIn += dxToUs(dx)
                inUs = snap(rawIn.coerceIn(0, outUs - minGap), null).coerceIn(0, outUs - minGap)
                listener?.onTrimChanged(inUs, outUs)
                listener?.onPeek(inUs)
            }
            Drag.OUT -> {
                rawOut += dxToUs(dx)
                outUs = snap(rawOut.coerceIn(inUs + minGap, durationUs), null).coerceIn(inUs + minGap, durationUs)
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

    // Yapışmadan bağımsız ham parmak konumları (yapışma "takılı" kalmasın diye)
    private var rawIn = 0L
    private var rawOut = 0L
    private var rawStart = 0L
    private var rawEnd = 0L
    private val LAYER_DRAGS = setOf(Drag.LAYER_MOVE, Drag.LAYER_START, Drag.LAYER_END)

    // ---- Erişilebilirlik: ekran okuyucu konumu okur, kaydırma eylemleriyle 1 sn ileri / geri ----
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
    }

    private fun spoken(us: Long) = "%d dakika %d saniye".format(us / 60_000_000, (us / 1_000_000) % 60)

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.contentDescription = "Zaman çizelgesi. Konum ${spoken(positionUs)}, süre ${spoken(durationUs)}. Kesme ${spoken(inUs)} ile ${spoken(outUs)} arası."
        info.rangeInfo = android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(
            android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, durationUs / 1e6f, positionUs / 1e6f)
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        val step = when (action) {
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1_000_000L
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1_000_000L
            else -> return super.performAccessibilityAction(action, arguments)
        }
        positionUs = (positionUs + step).coerceIn(0, durationUs)
        listener?.onScrubStart(); listener?.onScrub(positionUs, true); listener?.onScrubEnd()
        announceForAccessibility(spoken(positionUs))
        return true
    }

    private fun label(us: Long): String {
        val s = us / 1_000_000.0
        return if (s % 1.0 == 0.0) "%d:%02d".format((s / 60).toInt(), (s % 60).toInt()) else "%.1f".format(s % 60)
    }
}
