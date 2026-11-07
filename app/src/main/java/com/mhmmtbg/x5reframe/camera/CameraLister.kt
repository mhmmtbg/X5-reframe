package com.mhmmtbg.x5reframe.camera

import android.net.Network
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Kameradaki tüm çekimleri bulur:
 *  1) Kameranın kendi kanalından (TCP 6666) tam liste — resmi uygulamanın gördüğü liste;
 *  2) olmazsa / eksik kalırsa OSC listesiyle birleştirilir;
 *  3) her videonun LRV önizleme dosyası aynı klasörde adından bulunur (OSC bunları listelemez);
 *  4) boyutlar HTTP HEAD ile tamamlanır.
 */
object CameraLister {
    private val NAME_DATE = Regex("""(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})""")

    fun dateOf(name: String): String = NAME_DATE.find(name)?.groupValues?.let { g -> "${g[1]}:${g[2]}:${g[3]} ${g[4]}:${g[5]}:${g[6]}" } ?: ""

    /** VID_20260711_081025_00_024.insv → LRV_20260711_081025_01_024.lrv (aynı klasör). */
    fun lrvNameFor(name: String): String? {
        val m = Regex("""^(?:VID|PRO_VID)_(\d{8}_\d{6})_(\d)\d_(\d+)\.(insv|mp4)$""", RegexOption.IGNORE_CASE).find(name) ?: return null
        return "LRV_${m.groupValues[1]}_${m.groupValues[2]}1_${m.groupValues[3]}.lrv"
    }

    class Result(val files: List<CamFile>, val source: String)

    fun listAll(
        osc: OscClient?, native: (() -> InstaClient)?, httpBase: String, network: Network?,
        progress: (String) -> Unit = {},
    ): Result {
        val byName = LinkedHashMap<String, CamFile>()
        var source = ""
        // 1) Kameranın kendi listesi
        val nativeUris = native?.let { open ->
            runCatching { open().use { it.listFiles { got, total -> progress("Dosyalar okunuyor… $got / $total") } } }
                .onFailure { android.util.Log.w("CameraLister", "6666 listesi alınamadı: ${it.message}") }.getOrNull()
        }
        nativeUris?.forEach { u ->
            val path = if (u.startsWith("/")) u else "/$u"
            val name = path.substringAfterLast('/')
            byName[name] = CamFile(name, httpBase + path, -1, dateOf(name), null)
        }
        if (!nativeUris.isNullOrEmpty()) source = "kamera"
        // 2) OSC (küçük resim ve boyut da verir)
        val oscList = osc?.let { c -> runCatching { c.listFiles { got, total -> progress("Dosyalar okunuyor… $got / $total") } }.getOrNull() }
        oscList?.forEach { f ->
            val prev = byName[f.name]
            byName[f.name] = if (prev == null) f else prev.copy(size = if (f.size > 0) f.size else prev.size, thumbnail = f.thumbnail ?: prev.thumbnail,
                date = f.date.ifEmpty { prev.date })
        }
        if (source.isEmpty() && !oscList.isNullOrEmpty()) source = "osc"
        // 3) LRV'ler ve 4) boyutlar: paralel HEAD istekleri
        val pool = Executors.newFixedThreadPool(6)
        try {
            val videos = byName.values.filter { it.isVideo && !it.isLrv }
            val lrvJobs = videos.mapNotNull { v ->
                val ln = lrvNameFor(v.name) ?: return@mapNotNull null
                if (byName.containsKey(ln)) return@mapNotNull null
                val url = v.url.substringBeforeLast('/') + "/" + ln
                Callable { val n = HttpRangeSource.head(url, network); if (n > 0) CamFile(ln, url, n, v.date, null) else null }
            }
            val sizeJobs = byName.values.filter { it.size <= 0 }.map { f -> Callable { f.name to HttpRangeSource.head(f.url, network) } }
            progress("Önizleme dosyaları aranıyor…")
            pool.invokeAll(lrvJobs).forEach { fut -> runCatching { fut.get() }.getOrNull()?.let { byName[it.name] = it } }
            pool.invokeAll(sizeJobs).forEach { fut -> runCatching { fut.get() }.getOrNull()?.let { (n, s) -> byName[n]?.let { f -> byName[n] = f.copy(size = s) } } }
        } finally { pool.shutdownNow() }
        return Result(byName.values.toList(), source)
    }
}
