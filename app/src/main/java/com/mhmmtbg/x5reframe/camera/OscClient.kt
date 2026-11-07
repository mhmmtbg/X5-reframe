package com.mhmmtbg.x5reframe.camera

import android.net.Network
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Kameradaki bir dosya (OSC camera.listFiles girdisi). */
data class CamFile(
    val name: String,
    val url: String,
    val size: Long,
    /** "2026:09:26 14:16:17+03:00" biçimi; sıralama için ham bırakılır */
    val date: String,
    val thumbnail: ByteArray?,
) {
    val isVideo get() = name.endsWith(".insv", true) || name.endsWith(".mp4", true)
    val isPhoto get() = name.endsWith(".insp", true) || name.endsWith(".jpg", true) || name.endsWith(".dng", true)
    val isLrv get() = name.startsWith("LRV_", true) || name.endsWith(".lrv", true)
    /** Çekim anahtarı: VID_20260926_141617_00_142 → 20260926_141617_142 (LRV ile eşleştirmek için) */
    val shotKey: String get() {
        val m = Regex("""(\d{8}_\d{6})_\d{2}_(\d+)""").find(name)
        return m?.let { it.groupValues[1] + "_" + it.groupValues[2] } ?: name.substringBeforeLast('.')
    }
}

class CamInfo(val model: String, val firmware: String, val serial: String)
class CamState(val battery: Float, val card: String)

class OscException(message: String) : IOException(message)

/**
 * Insta360 OSC (Open Spherical Camera) istemcisi. Kamera kendi Wi-Fi erişim noktasını açar,
 * adres 192.168.42.1:80. Komutlar sırayla gönderilir (önceki yanıt gelmeden yenisi gönderilmez).
 * [network] verilirse tüm bağlantılar o ağ üzerinden açılır (telefon mobil veriyi kullanmaya çalışmasın).
 */
class OscClient(private val base: String = "http://192.168.42.1", private val network: Network? = null) {

    private val lock = Any()

    private fun open(url: String): HttpURLConnection {
        val u = URL(url)
        return (network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
    }

    private fun call(path: String, body: JSONObject?, timeoutMs: Int = 15_000): JSONObject = synchronized(lock) {
        val c = open(base + path)
        try {
            c.connectTimeout = 6_000
            c.readTimeout = timeoutMs
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("X-XSRF-Protected", "1")
            if (body != null) {
                val bytes = body.toString().toByteArray()
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json;charset=utf-8")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val o = runCatching { JSONObject(text) }.getOrElse { throw OscException("Kamera yanıtı okunamadı (HTTP $code)") }
            o.optJSONObject("error")?.let { e -> throw OscException(e.optString("message", e.optString("code", "kamera hatası"))) }
            if (code !in 200..299) throw OscException("HTTP $code")
            o
        } finally {
            c.disconnect()
        }
    }

    fun info(): CamInfo {
        val o = call("/osc/info", null, 6_000)
        return CamInfo(o.optString("model", "Insta360"), o.optString("firmwareVersion"), o.optString("serialNumber"))
    }

    fun state(): CamState {
        val s = call("/osc/state", JSONObject()).optJSONObject("state") ?: JSONObject()
        return CamState(s.optDouble("batteryLevel", -1.0).toFloat(), s.optString("_cardState", ""))
    }

    private fun execute(name: String, params: JSONObject, timeoutMs: Int = 30_000): JSONObject {
        var r = call("/osc/commands/execute", JSONObject().put("name", name).put("parameters", params), timeoutMs)
        // Uzun süren komutlar "inProgress" döner: bitene kadar durum sorgula
        var n = 0
        while (r.optString("state") == "inProgress" && n++ < 120) {
            Thread.sleep(1000)
            r = call("/osc/commands/status", JSONObject().put("id", r.optString("id")))
        }
        if (r.optString("state") == "error") throw OscException(r.optJSONObject("error")?.optString("message") ?: "komut hatası")
        return r.optJSONObject("results") ?: JSONObject()
    }

    /** Tüm dosyalar, sayfa sayfa. [thumbSize] 0 ise küçük resim istenmez. */
    fun listFiles(thumbSize: Int = 240, page: Int = 30, progress: (Int, Int) -> Unit = { _, _ -> }): List<CamFile> {
        val out = ArrayList<CamFile>()
        var start = 0
        var total = Int.MAX_VALUE
        while (start < total) {
            val p = JSONObject().put("fileType", "all").put("entryCount", page).put("startPosition", start)
            if (thumbSize > 0) p.put("maxThumbSize", thumbSize) else p.put("maxThumbSize", JSONObject.NULL)
            val r = execute("camera.listFiles", p, 60_000)
            val entries = r.optJSONArray("entries") ?: JSONArray()
            total = r.optInt("totalEntries", out.size + entries.length())
            for (i in 0 until entries.length()) out += parse(entries.getJSONObject(i))
            progress(out.size, total)
            if (entries.length() == 0) break
            start += entries.length()
        }
        return out
    }

    private fun parse(e: JSONObject): CamFile {
        val thumb = e.optString("thumbnail").takeIf { it.isNotBlank() && it != "null" }?.let { t ->
            runCatching { java.util.Base64.getDecoder().decode(t.substringAfter("base64,")) }.getOrNull()
        }
        val url = e.optString("fileUrl")
        return CamFile(e.optString("name", url.substringAfterLast('/')), absolute(url), e.optLong("size", -1), e.optString("dateTimeZone"), thumb)
    }

    /** Kamera bazen adresi kendi IP'siyle verir; testte ve farklı ağlarda temel adrese çevir. */
    private fun absolute(url: String): String {
        if (url.startsWith("/")) return base + url
        val path = url.substringAfter("://").substringAfter('/', "")
        return if (url.startsWith("http")) "$base/$path" else "$base/$url"
    }

    fun delete(urls: List<String>) {
        val arr = JSONArray()
        // Kameraya kendi verdiği adres biçimiyle gönder
        urls.forEach { arr.put(it.replace(base, "http://192.168.42.1:80")) }
        execute("camera.delete", JSONObject().put("fileUrls", arr), 60_000)
    }

    /** Bayt aralığı (Range) iste; kamera desteklemezse hata. */
    private fun range(url: String, from: Long, to: Long): ByteArray {
        val c = open(url)
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 30_000
            c.setRequestProperty("Range", "bytes=$from-$to")
            if (c.responseCode != 206) throw OscException("Kamera kısmi okumayı desteklemiyor (HTTP ${c.responseCode})")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * .insv dosyasının sonundaki Insta360 verisini (lens kalibrasyonu + hareket sensörü) indir.
     * Video indirilmeden önizleme için yeterlidir; dönen baytlar dosyanın son kısmıdır.
     */
    fun fetchTrailer(f: CamFile): ByteArray {
        require(f.size > 72) { "dosya boyutu bilinmiyor" }
        val footer = range(f.url, f.size - 72, f.size - 1)
        val magic = String(footer, 40, 32, Charsets.ISO_8859_1)
        if (magic != "8db42d694ccc418790edff439fe026bf") throw OscException("Insta360 verisi bulunamadı")
        val size = java.nio.ByteBuffer.wrap(footer).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(32).toLong() and 0xffffffffL
        if (size < 72 || size > minOf(f.size, 64L * 1024 * 1024)) throw OscException("Insta360 verisi geçersiz")
        return range(f.url, f.size - size, f.size - 1)
    }

    /**
     * Dosyayı indir; [offset] > 0 ise kaldığı yerden (Range). Kamera Range desteklemezse baştan yazılır
     * ve dönüş değeri false olur (çağıran hedefi sıfırlamalı).
     * @return true = kaldığı yerden devam edildi
     */
    fun download(url: String, offset: Long, out: (append: Boolean) -> OutputStream, cancelled: () -> Boolean, progress: (Long) -> Unit): Boolean {
        val c = open(url)
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 30_000
            if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
            val code = c.responseCode
            if (code !in 200..299) throw OscException("İndirme HTTP $code")
            val resumed = offset > 0 && code == 206
            var done = if (resumed) offset else 0L
            out(resumed).use { o ->
                c.inputStream.use { inp ->
                    val buf = ByteArray(256 * 1024)
                    var last = 0L
                    while (true) {
                        if (cancelled()) throw InterruptedException()
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 250) { progress(done); last = now }
                    }
                }
            }
            progress(done)
            return resumed
        } finally {
            c.disconnect()
        }
    }
}
