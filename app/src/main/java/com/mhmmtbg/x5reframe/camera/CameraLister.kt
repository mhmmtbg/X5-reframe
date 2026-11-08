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

    private val ROW = Regex("""<tr[^>]*>(.*?)</tr>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val LINK = Regex("""<a[^>]*href\s*=\s*"([^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CELL = Regex("""<td[^>]*>(.*?)</td>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SIZE = Regex("""^([\d.,]+)\s*([KMGT]?)i?B?$""", RegexOption.IGNORE_CASE)

    /** "1.9G", "345 MB", "123456" → bayt; anlaşılmazsa -1. */
    fun parseSize(t: String): Long {
        val m = SIZE.find(t.trim()) ?: return -1
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return -1
        val mul = when (m.groupValues[2].uppercase()) { "K" -> 1e3; "M" -> 1e6; "G" -> 1e9; "T" -> 1e12; else -> 1.0 }
        return (n * mul).toLong()
    }

    /** Dizin listesi sayfasındaki (ad, bağlantı, boyut yazısı, dizin mi) satırları. */
    fun parseListing(html: String): List<Triple<String, String, Pair<String, Boolean>>> {
        val out = ArrayList<Triple<String, String, Pair<String, Boolean>>>()
        val rows = ROW.findAll(html).toList()
        val chunks = if (rows.isNotEmpty()) rows.map { it.groupValues[1] } else html.split('\n')
        for (row in chunks) {
            val a = LINK.find(row) ?: continue
            val href = a.groupValues[1]
            val name = a.groupValues[2].replace(Regex("<[^>]+>"), "").trim().trimEnd('/')
            if (name.isEmpty() || name == ".." || name.startsWith(".") || href.startsWith("?") || href == "../") continue
            val cells = CELL.findAll(row).map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }.toList()
            val sizeText = cells.getOrNull(2) ?: cells.lastOrNull() ?: ""
            val dir = href.endsWith("/") || sizeText.contains("dir", true) || cells.any { it.equals("directory", true) }
            out += Triple(name, href, sizeText to dir)
        }
        return out
    }

    /**
     * Kameranın web sunucusundaki dizin listesi (/DCIM/ ve alt klasörleri): kartta ne varsa (LRV dahil) görünür.
     * Resmi olmayan ama kameranın HTTP sunucusunun sunduğu yöntem; olmazsa boş döner.
     */
    fun listHttp(httpBase: String, network: Network?, progress: (String) -> Unit = {}): List<CamFile> {
        val out = ArrayList<CamFile>()
        val queue = ArrayDeque(listOf("/DCIM/"))
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 64) {
            val dir = queue.removeFirst()
            val html = runCatching { HttpRangeSource.getText(httpBase + dir, network) }.getOrNull() ?: continue
            for ((name, href, meta) in parseListing(html)) {
                val (sizeText, isDir) = meta
                val path = when {
                    href.startsWith("http") -> java.net.URL(href).path
                    href.startsWith("/") -> href
                    else -> dir + href
                }
                if (isDir) { if (path.startsWith(dir) && path.length > dir.length) queue += path.trimEnd('/') + "/"; continue }
                if (name.endsWith(".list", true)) continue
                // Yalnızca tam bayt sayısı güvenilir (aralıklı okuma tam boyu ister); "1.9G" gibi yazılar HEAD ile tamamlanır
                val exact = sizeText.trim().takeIf { t -> t.isNotEmpty() && t.all { it.isDigit() } }?.toLongOrNull() ?: -1
                out += CamFile(name, httpBase + path, exact, dateOf(name), null)
            }
            progress("Klasörler okunuyor… ${out.size} dosya")
        }
        return out
    }

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
        // 1b) Web sunucusunun dizin listesi (LRV ve gizli klasörler dahil her şey)
        val httpFiles = runCatching { listHttp(httpBase, network, progress) }
            .onFailure { android.util.Log.w("CameraLister", "dizin listesi alınamadı: ${it.message}") }.getOrNull().orEmpty()
        httpFiles.forEach { f ->
            val prev = byName[f.name]
            byName[f.name] = if (prev == null) f else prev.copy(size = if (prev.size > 0) prev.size else f.size)
        }
        if (source.isEmpty() && httpFiles.isNotEmpty()) source = "dizin"
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
