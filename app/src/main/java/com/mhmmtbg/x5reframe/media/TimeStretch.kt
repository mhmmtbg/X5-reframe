package com.mhmmtbg.x5reframe.media

import kotlin.math.PI
import kotlin.math.cos

/**
 * Sesin perdesini koruyarak hızını değiştirir (WSOLA: dalga biçimi benzerliğiyle örtüşen ekleme).
 * Girdi ve çıktı 48 kHz stereo 16 bit. [speed] > 1 hızlandırır, < 1 yavaşlatır.
 *
 * Her adımda çıktıya [HOP] kare eklenir; girdide ideal konum HOP × hız ilerler ve ±[TOL] içinde
 * önceki bloğun doğal devamına en çok benzeyen yer seçilir (tıkırtı ve "robot" sesini azaltır).
 */
class TimeStretch(private val speed: Double, private val pull: (Int, ShortArray) -> Unit) {
    companion object {
        const val HOP = 960          // 20 ms
        const val WIN = 2 * HOP      // 40 ms pencere
        const val TOL = 360          // ±7,5 ms arama
        private val window = FloatArray(WIN) { (0.5 - 0.5 * cos(2 * PI * it / WIN)).toFloat() }
    }

    // Girdi tamponu (stereo, float), [base] = tampondaki ilk karenin mutlak indisi
    private var inBuf = FloatArray(0)
    private var inLen = 0
    private var base = 0L
    private val tmp = ShortArray(4096 * 2)

    private var outPos = 0.0       // ideal girdi konumu (mutlak kare)
    private var prevStart = -1L    // önceki pencerenin seçilen başlangıcı
    private val tail = FloatArray(HOP * 2) // önceki pencerenin ikinci yarısı (pencerelenmiş)
    private val ready = FloatArray(HOP * 2)
    private var readyLen = 0
    private var readyPos = 0

    private fun ensure(absEnd: Long) {
        val need = (absEnd - base).toInt()
        if (need <= inLen) return
        val add = need - inLen
        if (inBuf.size < need * 2) inBuf = inBuf.copyOf(maxOf(need * 2, inBuf.size * 2))
        var done = 0
        while (done < add) {
            val n = minOf(add - done, tmp.size / 2)
            pull(n, tmp)
            for (k in 0 until n * 2) inBuf[(inLen + done) * 2 + k] = tmp[k].toFloat()
            done += n
        }
        inLen = need
    }

    /** Artık gerekmeyen girdiyi at. */
    private fun compact(keepFrom: Long) {
        val drop = (keepFrom - base).toInt()
        if (drop <= 0 || drop > inLen) return
        System.arraycopy(inBuf, drop * 2, inBuf, 0, (inLen - drop) * 2)
        inLen -= drop
        base += drop
    }

    private fun mono(abs: Long): Float { val i = ((abs - base).toInt()) * 2; return inBuf[i] + inBuf[i + 1] }

    private fun step() {
        val ideal = outPos.toLong()
        val start: Long
        if (prevStart < 0) {
            start = ideal
            ensure(start + WIN)
        } else {
            // Önceki pencerenin doğal devamı: prevStart + HOP
            val nat = prevStart + HOP
            val lo = maxOf(ideal - TOL, base)
            val hi = ideal + TOL
            ensure(maxOf(hi + WIN, nat + HOP))
            var best = ideal; var bestScore = -Double.MAX_VALUE
            var cand = lo
            while (cand <= hi) {
                var dot = 0.0; var e = 1e-9
                var k = 0
                while (k < HOP) {
                    val a = mono(cand + k); val b = mono(nat + k)
                    dot += a * b; e += a * a
                    k += 4 // seyrek örnekleme yeterli
                }
                val score = dot / kotlin.math.sqrt(e)
                if (score > bestScore) { bestScore = score; best = cand }
                cand += 8
            }
            start = best
        }
        // Çıktı = önceki kuyruk + yeni pencerenin ilk yarısı
        val off = ((start - base).toInt()) * 2
        for (k in 0 until HOP) {
            val w = window[k]
            ready[k * 2] = tail[k * 2] + inBuf[off + k * 2] * w
            ready[k * 2 + 1] = tail[k * 2 + 1] + inBuf[off + k * 2 + 1] * w
        }
        for (k in 0 until HOP) {
            val w = window[HOP + k]
            tail[k * 2] = inBuf[off + (HOP + k) * 2] * w
            tail[k * 2 + 1] = inBuf[off + (HOP + k) * 2 + 1] * w
        }
        readyLen = HOP; readyPos = 0
        prevStart = start
        outPos += HOP * speed
        compact(minOf(start, outPos.toLong() - TOL))
    }

    /** n kare üret ([out] stereo, [gain] ile, karıştırmadan yazar). */
    fun read(n: Int, out: ShortArray, gain: Float) {
        var i = 0
        while (i < n) {
            if (readyPos >= readyLen) step()
            val m = minOf(n - i, readyLen - readyPos)
            for (k in 0 until m) {
                out[(i + k) * 2] = (ready[(readyPos + k) * 2] * gain).coerceIn(-32768f, 32767f).toInt().toShort()
                out[(i + k) * 2 + 1] = (ready[(readyPos + k) * 2 + 1] * gain).coerceIn(-32768f, 32767f).toInt().toShort()
            }
            readyPos += m; i += m
        }
    }
}
