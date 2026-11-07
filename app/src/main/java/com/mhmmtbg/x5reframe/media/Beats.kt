package com.mhmmtbg.x5reframe.media

import android.content.Context
import android.net.Uri
import com.mhmmtbg.x5reframe.auto.AutoDirector

/** Müzikte vuruş zamanları (µs): enerji başlangıçlarından tempo ve faz kestirimi. */
object Beats {
    private val cache = HashMap<String, List<Long>>()

    @Synchronized fun cached(uri: Uri): List<Long>? = cache[uri.toString()]

    fun detect(context: Context, uri: Uri, maxUs: Long = 10 * 60 * 1_000_000L): List<Long> {
        cached(uri)?.let { return it }
        val hz = 100
        val hop = MIX_RATE / hz
        val r = PcmReader(context, uri)
        val env = ArrayList<Float>()
        try {
            val buf = ShortArray(hop * 2)
            var t = 0L
            while (!r.eos && t < maxUs) {
                r.read(hop, buf, 1f, false)
                var e = 0.0
                for (k in 0 until hop) { val v = (buf[k * 2] + buf[k * 2 + 1]) * 0.5; e += v * v }
                env += kotlin.math.sqrt(e / hop).toFloat()
                t += 1_000_000L / hz
            }
        } finally { r.close() }
        // Sondaki sessizliği at (okuyucu bitince sıfır döndürür)
        while (env.isNotEmpty() && env.last() < 1f) env.removeAt(env.size - 1)
        val e = env.toFloatArray()
        val period = AutoDirector.beatPeriod(e, hz.toDouble()) ?: return emptyList()
        val lag = (period * hz).toInt().coerceAtLeast(1)
        // Başlangıç (onset) gücü ve en iyi faz
        val on = FloatArray(e.size) { i -> if (i == 0) 0f else maxOf(0f, kotlin.math.ln(1e-3f + e[i]) - kotlin.math.ln(1e-3f + e[i - 1])) }
        var bestPhase = 0; var best = -1f
        for (ph in 0 until lag) {
            var s = 0f; var i = ph
            while (i < on.size) { s += on[i] + 0.5f * (on.getOrElse(i - 1) { 0f } + on.getOrElse(i + 1) { 0f }); i += lag }
            if (s > best) { best = s; bestPhase = ph }
        }
        val out = ArrayList<Long>()
        var i = bestPhase.toDouble()
        while (i < e.size) { out += (i / hz * 1e6).toLong(); i += period * hz }
        synchronized(this) { cache[uri.toString()] = out }
        return out
    }
}
