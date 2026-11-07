package com.mhmmtbg.x5reframe.auto

import com.mhmmtbg.x5reframe.model.Curve
import com.mhmmtbg.x5reframe.model.Keyframe
import com.mhmmtbg.x5reframe.model.Projection
import com.mhmmtbg.x5reframe.model.ViewParams
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Otomatik kurgunun "yönetmeni": taranmış örneklerden (yüzler, ses, hareket) çekim kararları verir.
 * Insta360'ın AI Edit davranışına göre:
 *  - Çekimi yapan kişi (selfie çubuğunu tutan) ayrı tanınır: çoğu karede aynı yönde, büyük yüz.
 *  - "İleri" yön, bu kişinin tam tersidir (yürüme/sürüş yönü); kişi yoksa kameranın ön yönü.
 *  - Başka kişiler varsa onlar öncelikli konudur; yoksa ileri / selfie görünümleri dönüşümlü kullanılır.
 *  - Kamera hareketi yumuşaktır: hedef değişimi gecikmeli (histerezis) ve dönüş hızı sınırlıdır.
 *  - Derleme (Oto Düzenle) kısa çekimlerden oluşur; çekim türleri art arda tekrarlanmaz,
 *    ilk çekim küçük gezegenden açılır, kesmeler varsa müziğin vuruşlarına oturur.
 *
 * Tüm açılar sabitlenmiş (ufuk düz, yön kameranın gidişini izleyen) çerçevededir.
 */
object AutoDirector {

    enum class ShotType(val label: String) { PLANET_INTRO("Gezegen açılışı"), FORWARD("İleri"), SELFIE("Selfie"), SUBJECT("Kişi"), PAN("Panorama dönüşü") }

    class Shot(val range: LongRange, val type: ShotType, val keys: List<Keyframe>)

    /** Çekimi yapan kişinin yönü (örnek başına, boşluklar doldurulmuş); bulunamazsa null. */
    fun holderTrack(samples: List<AutoEditor.Sample>): DoubleArray? {
        if (samples.size < 4) return null
        // 30°'lik dilimlerde büyük yüzlerin görülme oranı
        val bins = IntArray(12)
        for (s in samples) {
            val seen = BooleanArray(12)
            for (f in s.faces) if (f.size >= 0.09f) seen[binOf(f.yaw)] = true
            for (b in 0 until 12) if (seen[b]) bins[b]++
        }
        // Komşu dilimlerle birlikte en sık görülen yön
        val score = IntArray(12) { b -> bins[b] * 2 + bins[(b + 1) % 12] + bins[(b + 11) % 12] }
        val best = score.indices.maxBy { score[it] }
        val presence = (bins[best] + max(bins[(best + 1) % 12], bins[(best + 11) % 12])).toDouble() / samples.size
        if (presence < 0.35) return null
        val center = best * 30.0 - 180.0 + 15.0
        val raw = DoubleArray(samples.size) { Double.NaN }
        for ((i, s) in samples.withIndex()) {
            val f = s.faces.filter { it.size >= 0.07f && abs(wrap(it.yaw - center)) < 50 }.maxByOrNull { it.size }
            if (f != null) raw[i] = f.yaw
        }
        return smoothAngles(fillGaps(raw, center), 6)
    }

    /** Konu: çekimi yapan dışındaki en belirgin kişi (örnek başına yaw, yoksa NaN). */
    private fun subjectYaw(s: AutoEditor.Sample, holder: Double?): Double {
        val others = s.faces.filter { holder == null || abs(wrap(it.yaw - holder)) > 40 }
        return others.maxByOrNull { it.size }?.yaw ?: Double.NaN
    }

    // ---- Tek çekim (Akıllı kadraj) ----

    /**
     * Klip boyunca kesintisiz kamera yolu: konu varsa ona, yoksa ileri yöne bakar;
     * hedef en az 1,5 sn sürmedikçe değişmez, dönüş en fazla ~50°/sn.
     */
    fun singleTake(samples: List<AutoEditor.Sample>, fov: Double = 85.0): List<Keyframe> {
        if (samples.isEmpty()) return emptyList()
        val holder = holderTrack(samples)
        val step = stepSec(samples)
        val targets = DoubleArray(samples.size)
        var current = forwardYaw(holder, 0)
        var pending = Double.NaN; var pendingFor = 0.0
        for ((i, s) in samples.withIndex()) {
            val subj = subjectYaw(s, holder?.get(i))
            val want = if (!subj.isNaN()) subj else forwardYaw(holder, i)
            if (abs(wrap(want - current)) < 25) { current = unwrapNear(want, current); pending = Double.NaN }
            else {
                if (pending.isNaN() || abs(wrap(want - pending)) > 25) { pending = want; pendingFor = 0.0 }
                pendingFor += step
                if (pendingFor >= 1.5) { current = unwrapNear(want, current); pending = Double.NaN }
            }
            targets[i] = current
        }
        val path = rateLimit(smoothAngles(targets, 3), 50.0 * step)
        val keys = ArrayList<Keyframe>()
        val every = max(1, (0.5 / step).toInt())
        for (i in samples.indices step every) keys += Keyframe(samples[i].tUs, ViewParams(yaw = wrap(path[i]), pitch = -5.0, fov = fov), Curve.LINEAR)
        if ((samples.size - 1) % every != 0) keys += Keyframe(samples.last().tUs, ViewParams(yaw = wrap(path.last()), pitch = -5.0, fov = fov), Curve.LINEAR)
        return keys
    }

    // ---- Derleme (Oto Düzenle) ----

    /**
     * Öne çıkan anları seç ve her birine çekim türü + kamera hareketi ata.
     * @param shotUs hedef çekim süresi (vuruşa göre ayarlanmış olabilir)
     * @param targetUs bu klipten alınacak toplam süre
     * @param first projedeki ilk klip mi (küçük gezegen açılışı)
     */
    fun compile(samples: List<AutoEditor.Sample>, shotUs: Long, targetUs: Long, first: Boolean, lastType: ShotType? = null): List<Shot> {
        if (samples.size < 4) return emptyList()
        val holder = holderTrack(samples)
        val step = stepSec(samples)
        val per = max(2, (shotUs / 1e6 / step).toInt())
        val subj = DoubleArray(samples.size) { subjectYaw(samples[it], holder?.get(it)) }
        val score = scores(samples, subj)
        // Örtüşmeyen, klip boyuna yayılmış en iyi pencereler
        val windows = (0..samples.size - per).map { i -> i to (i until i + per).sumOf { score[it] } / per }.sortedByDescending { it.second }
        val chosen = ArrayList<Int>()
        var total = 0L
        for ((i, _) in windows) {
            if (total >= targetUs) break
            if (chosen.any { abs(it - i) < per + (1.0 / step).toInt() }) continue
            chosen += i; total += shotUs
        }
        chosen.sort()
        val shots = ArrayList<Shot>()
        var prev = lastType
        for ((k, i) in chosen.withIndex()) {
            val idx = i until min(samples.size, i + per)
            val a = samples[idx.first].tUs
            val b = samples[idx.last].tUs + (step * 1e6).toLong()
            val subjFrac = idx.count { !subj[it].isNaN() }.toDouble() / per
            val type = when {
                first && k == 0 -> ShotType.PLANET_INTRO
                subjFrac >= 0.4 && prev != ShotType.SUBJECT -> ShotType.SUBJECT
                holder != null && prev != ShotType.SELFIE && (prev == ShotType.FORWARD || prev == ShotType.SUBJECT || k % 3 == 1) -> ShotType.SELFIE
                prev == ShotType.FORWARD -> ShotType.PAN
                else -> ShotType.FORWARD
            }
            shots += Shot(a..b, type, shotKeys(type, samples, idx, holder, subj, a, b))
            prev = type
        }
        return shots
    }

    private fun shotKeys(type: ShotType, samples: List<AutoEditor.Sample>, idx: IntRange, holder: DoubleArray?, subj: DoubleArray, a: Long, b: Long): List<Keyframe> {
        val mid = (idx.first + idx.last) / 2
        val fwd = forwardYaw(holder, mid)
        val L = Curve.LINEAR
        return when (type) {
            ShotType.PLANET_INTRO -> listOf(
                Keyframe(a, ViewParams(yaw = fwd, pitch = -90.0, fov = 250.0, projection = Projection.WIDE), Curve.EASE_IN_OUT),
                Keyframe(a + (b - a) * 3 / 5, ViewParams(yaw = fwd, pitch = -5.0, fov = 100.0, projection = Projection.WIDE), L),
                Keyframe(b, ViewParams(yaw = fwd, pitch = -5.0, fov = 92.0, projection = Projection.WIDE)),
            )
            // Hafif içeri itme: geniş başla, biraz yaklaş
            ShotType.FORWARD -> listOf(
                Keyframe(a, ViewParams(yaw = fwd, pitch = -6.0, fov = 108.0, projection = Projection.SUPERWIDE), L),
                Keyframe(b, ViewParams(yaw = forwardYaw(holder, idx.last), pitch = -4.0, fov = 94.0, projection = Projection.SUPERWIDE)),
            )
            // "Ben" görünümü: kişi ve arkasındaki manzara, geniş açı
            ShotType.SELFIE -> {
                val h = holder ?: return shotKeys(ShotType.FORWARD, samples, idx, null, subj, a, b)
                listOf(
                    Keyframe(a, ViewParams(yaw = wrap(h[idx.first] + 8), pitch = -8.0, fov = 120.0, projection = Projection.SUPERWIDE), L),
                    Keyframe(b, ViewParams(yaw = wrap(h[idx.last] - 8), pitch = -6.0, fov = 110.0, projection = Projection.SUPERWIDE)),
                )
            }
            ShotType.SUBJECT -> {
                val raw = DoubleArray(idx.count()) { j -> subj[idx.first + j] }
                val path = rateLimit(smoothAngles(fillGaps(raw, raw.firstOrNull { !it.isNaN() } ?: fwd), 3), 40.0 * stepSec(samples))
                val every = max(1, (0.5 / stepSec(samples)).toInt())
                (path.indices step every).map { j ->
                    Keyframe(samples[idx.first + j].tUs, ViewParams(yaw = wrap(path[j]), pitch = -3.0, fov = 75.0), L)
                } + Keyframe(b, ViewParams(yaw = wrap(path.last()), pitch = -3.0, fov = 72.0))
            }
            ShotType.PAN -> listOf(
                Keyframe(a, ViewParams(yaw = wrap(fwd - 60), pitch = -4.0, fov = 100.0, projection = Projection.SUPERWIDE), L),
                Keyframe(b, ViewParams(yaw = wrap(fwd + 60), pitch = -4.0, fov = 100.0, projection = Projection.SUPERWIDE)),
            )
        }
    }

    /** Anlam skoru: başka kişiler > ses > orta düzey hareket; çekimi yapan kişi tek başına az puan. */
    private fun scores(samples: List<AutoEditor.Sample>, subj: DoubleArray): DoubleArray {
        val maxA = samples.maxOf { it.audio }.coerceAtLeast(1e-6f)
        val maxM = samples.maxOf { it.motion }.coerceAtLeast(1e-6f)
        return DoubleArray(samples.size) { i ->
            val s = samples[i]
            val others = if (subj[i].isNaN()) 0.0 else 1.0
            val anyFace = if (s.faces.isNotEmpty()) 0.3 else 0.0
            val a = (s.audio / maxA).toDouble()
            val m = (s.motion / maxM).toDouble()
            others * 1.4 + anyFace + a * 0.9 + m * (1.3 - m) * 0.8
        }
    }

    // ---- Yardımcılar ----

    private fun forwardYaw(holder: DoubleArray?, i: Int) = if (holder != null) wrap(holder[i.coerceIn(0, holder.size - 1)] + 180.0) else 0.0

    private fun stepSec(samples: List<AutoEditor.Sample>) =
        if (samples.size > 1) (samples[1].tUs - samples[0].tUs) / 1e6 else 0.25

    private fun binOf(yaw: Double) = (((wrap(yaw) + 180.0) / 30.0).toInt()).coerceIn(0, 11)

    fun wrap(a: Double): Double = ViewParams.wrap(a)

    private fun unwrapNear(a: Double, ref: Double): Double { var x = a; while (x - ref > 180) x -= 360; while (x - ref < -180) x += 360; return x }

    /** NaN boşlukları komşu değerlerle doldur (açısal). */
    private fun fillGaps(raw: DoubleArray, fallback: Double): DoubleArray {
        val out = raw.copyOf()
        val first = out.indexOfFirst { !it.isNaN() }
        if (first < 0) return DoubleArray(out.size) { fallback }
        for (i in 0 until first) out[i] = out[first]
        var last = out[first]
        for (i in first until out.size) { if (out[i].isNaN()) out[i] = last else { out[i] = unwrapNear(out[i], last); last = out[i] } }
        return out
    }

    /** Açısal hareketli ortalama (sarma açılarak). */
    private fun smoothAngles(a: DoubleArray, r: Int): DoubleArray {
        val u = a.copyOf()
        for (i in 1 until u.size) u[i] = unwrapNear(u[i], u[i - 1])
        return DoubleArray(u.size) { i ->
            var s = 0.0; var w = 0.0
            for (j in max(0, i - r)..min(u.size - 1, i + r)) { val k = exp(-((j - i) * (j - i)).toDouble() / (r * r + 1e-9)); s += u[j] * k; w += k }
            s / w
        }
    }

    /** Örnek başına en fazla [maxStep] derece dönüş. */
    private fun rateLimit(a: DoubleArray, maxStep: Double): DoubleArray {
        val o = a.copyOf()
        for (i in 1 until o.size) {
            val d = o[i] - o[i - 1]
            if (abs(d) > maxStep) o[i] = o[i - 1] + maxStep * kotlin.math.sign(d)
        }
        return o
    }

    // ---- Müzik vuruşu ----

    /**
     * Basit tempo kestirimi: enerji başlangıçlarının öz-ilintisinden vuruş aralığı (sn).
     * @param env ~100 Hz enerji zarfı
     */
    fun beatPeriod(env: FloatArray, hz: Double): Double? {
        if (env.size < hz * 8) return null
        val on = DoubleArray(env.size)
        for (i in 1 until env.size) on[i] = max(0.0, kotlin.math.ln(1e-6 + env[i]) - kotlin.math.ln(1e-6 + env[i - 1]))
        val mean = on.average()
        for (i in on.indices) on[i] -= mean
        val lo = (hz * 60 / 180).toInt(); val hi = (hz * 60 / 70).toInt()
        var best = -1.0; var bestLag = 0
        for (lag in lo..hi) {
            var s = 0.0
            for (i in lag until on.size) s += on[i] * on[i - lag]
            // 120 BPM civarını hafifçe tercih et
            val bpm = 60 * hz / lag
            val w = exp(-((bpm - 120) / 60) * ((bpm - 120) / 60) * 0.5)
            if (s * w > best) { best = s * w; bestLag = lag }
        }
        return if (bestLag > 0) bestLag / hz else null
    }
}
