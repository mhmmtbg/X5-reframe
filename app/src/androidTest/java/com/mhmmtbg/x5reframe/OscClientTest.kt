package com.mhmmtbg.x5reframe

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mhmmtbg.x5reframe.camera.OscClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread

/**
 * Kablosuz aktarım istemcisi, Insta360 OSC davranışını taklit eden yerel bir sunucuya karşı:
 * bilgi/durum, sayfalı dosya listesi + küçük resim, kaldığı yerden indirme (Range), silme.
 */
@RunWith(AndroidJUnit4::class)
class OscClientTest {
    private lateinit var server: ServerSocket
    private val files = linkedMapOf(
        "VID_20260926_141617_00_142.insv" to ByteArray(300_000) { (it * 7).toByte() },
        "LRV_20260926_141617_01_142.lrv" to ByteArray(50_000) { (it * 3).toByte() },
        "IMG_20260926_150000_00_143.insp" to ByteArray(20_000) { it.toByte() },
    )
    @Volatile private var supportRange = true
    /** OSC'nin listelemediği ama kameranın kendi kanalında görünen dosyalar (Camera02 klasörü gibi) */
    private val hidden = linkedMapOf(
        "VID_20260712_085744_00_031.insv" to ByteArray(4000) { 1 },
        "LRV_20260712_085744_01_031.lrv" to ByteArray(900) { 2 },
        "IMG_20260712_090000_00_032.insp" to ByteArray(700) { 3 },
    )
    private val thumb = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))

    @Before
    fun start() {
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(s) }; runCatching { s.close() } }
            }
        }
    }

    @After
    fun stop() { server.close() }

    private fun handle(s: Socket) {
        val inp = s.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) return; head.append(b.toChar()) }
        val lines = head.lines()
        val (method, path) = lines[0].split(" ").let { it[0] to it[1] }
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(len).also { var o = 0; while (o < len) { val n = inp.read(it, o, len - o); if (n < 0) break; o += n } }
        val out = s.getOutputStream()
        fun json(o: JSONObject) {
            val b = o.toString().toByteArray()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(b)
        }
        when {
            path == "/osc/info" -> json(JSONObject().put("model", "Insta360 X5").put("firmwareVersion", "1.0").put("serialNumber", "TEST"))
            path == "/osc/state" -> json(JSONObject().put("state", JSONObject().put("batteryLevel", 0.8).put("_cardState", "pass")))
            path == "/osc/commands/execute" -> {
                val req = JSONObject(String(body))
                assertEquals("1", headers["x-xsrf-protected"])
                val p = req.getJSONObject("parameters")
                when (req.getString("name")) {
                    "camera.listFiles" -> {
                        val start = p.optInt("startPosition"); val count = p.optInt("entryCount")
                        val names = files.keys.toList()
                        val arr = JSONArray()
                        names.drop(start).take(count).forEach { n ->
                            arr.put(JSONObject().put("name", n).put("fileUrl", "http://192.168.42.1:80/DCIM/Camera01/$n")
                                .put("size", files[n]!!.size).put("dateTimeZone", "2026:09:26 14:16:17+03:00").put("thumbnail", thumb))
                        }
                        json(JSONObject().put("name", "camera.listFiles").put("state", "done")
                            .put("results", JSONObject().put("entries", arr).put("totalEntries", names.size)))
                    }
                    "camera.delete" -> {
                        val urls = p.getJSONArray("fileUrls")
                        for (i in 0 until urls.length()) files.remove(urls.getString(i).substringAfterLast('/'))
                        json(JSONObject().put("name", "camera.delete").put("state", "done").put("results", JSONObject().put("fileUrls", JSONArray())))
                    }
                }
            }
            method == "GET" && path.endsWith("/") && path.startsWith("/DCIM") -> {
                // Kameranın web sunucusu gibi dizin listesi (tablo: ad, tarih, boyut / "directory")
                fun row(name: String, size: String) = "<tr><td><a href=\"$name\">$name</a></td><td>2026-07-12 08:57</td><td>$size</td></tr>"
                val rows = when (path) {
                    "/DCIM/" -> row("Camera01/", "directory") + row("Camera02/", "directory") + row("fileinfo_list.list", "120")
                    "/DCIM/Camera01/" -> files.entries.joinToString("") { (n, d) -> row(n, d.size.toString()) }
                    "/DCIM/Camera02/" -> hidden.entries.joinToString("") { (n, d) -> row(n, "%.1fK".format(java.util.Locale.US, d.size / 1000.0)) }
                    else -> ""
                }
                val b = "<html><body><table><thead><tr><th>Name</th><th>Date</th><th>Size</th></tr></thead><tbody>$rows</tbody></table></body></html>".toByteArray()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(b)
            }
            method == "HEAD" && path.startsWith("/DCIM/") -> {
                val data = (files + hidden)[path.substringAfterLast('/')]
                if (data == null) out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                else out.write("HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
            }
            method == "GET" && path.startsWith("/DCIM/") -> {
                val data = (files + hidden)[path.substringAfterLast('/')]!!
                val range = headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
                if (range != null && supportRange) {
                    out.write("HTTP/1.1 206 Partial Content\r\nContent-Length: ${data.size - range}\r\nContent-Range: bytes $range-${data.size - 1}/${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(data, range, data.size - range)
                } else {
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(data)
                }
            }
            else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }
        out.flush()
    }

    private fun client() = OscClient("http://127.0.0.1:${server.localPort}")

    @Test
    fun infoStateAndPagedList() {
        val c = client()
        assertEquals("Insta360 X5", c.info().model)
        assertEquals(0.8f, c.state().battery, 0.01f)
        val list = c.listFiles(page = 2)
        assertEquals(3, list.size)
        val vid = list.first { it.isVideo }
        assertTrue(vid.url.startsWith("http://127.0.0.1:"))
        assertNotNull(vid.thumbnail)
        val lrv = list.first { it.isLrv }
        assertEquals(vid.shotKey, lrv.shotKey)
        assertFalse(list.first { it.isPhoto }.isLrv)
    }

    @Test
    fun resumesDownloadWithRange() {
        val c = client()
        val vid = c.listFiles().first { it.isVideo }
        val full = files[vid.name]!!
        // Yarısı zaten inmiş gibi
        val out = ByteArrayOutputStream().apply { write(full, 0, 100_000) }
        val resumed = c.download(vid.url, 100_000, { append -> if (!append) out.reset(); out }, { false }) {}
        assertTrue(resumed)
        assertArrayEquals(full, out.toByteArray())
        // Kamera Range desteklemezse baştan yazılır
        supportRange = false
        val out2 = ByteArrayOutputStream().apply { write(full, 0, 1000) }
        val resumed2 = c.download(vid.url, 1000, { append -> if (!append) out2.reset(); out2 }, { false }) {}
        assertFalse(resumed2)
        assertArrayEquals(full, out2.toByteArray())
    }

    @Test
    fun fetchesOnlyTrailerForPreview() {
        // .insv'nin sonu: 1000 baytlık Insta360 verisi, son 72 bayt altbilgi (boyut + sihirli dize)
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val bb = java.nio.ByteBuffer.wrap(data, data.size - 72, 72).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.putInt(data.size - 72 + 32, 1000)
        "8db42d694ccc418790edff439fe026bf".toByteArray().copyInto(data, data.size - 32)
        files["VID_20260926_141617_00_142.insv"] = data
        val c = client()
        val vid = c.listFiles().first { it.isVideo }
        val tail = c.fetchTrailer(vid)
        assertEquals(1000, tail.size)
        assertArrayEquals(data.copyOfRange(data.size - 1000, data.size), tail)
    }

    /** Kameranın kendi kanalını (TCP 6666) taklit eden sunucu: eşitleme + dosya listesi (ileti kodu 13). */
    private fun fakeNative(uris: List<String>): ServerSocket {
        val ss = ServerSocket(0)
        thread(isDaemon = true) { while (!ss.isClosed) {
            val s = runCatching { ss.accept() }.getOrNull() ?: return@thread
            thread(isDaemon = true) {
            val inp = s.getInputStream(); val out = s.getOutputStream()
            fun send(p: ByteArray) { val n = p.size + 4; out.write(byteArrayOf(n.toByte(), (n shr 8).toByte(), 0, 0)); out.write(p); out.flush() }
            runCatching {
                while (true) {
                    val h = ByteArray(4); var o = 0; while (o < 4) { val r = inp.read(h, o, 4 - o); if (r < 0) return@thread; o += r }
                    val n = (h[0].toInt() and 255) or ((h[1].toInt() and 255) shl 8)
                    val p = ByteArray(n - 4); o = 0; while (o < p.size) { val r = inp.read(p, o, p.size - o); if (r < 0) return@thread; o += r }
                    if (p[0].toInt() == 6) send(com.mhmmtbg.x5reframe.camera.InstaClient.SYNC)
                    if (p[0].toInt() == 4 && p[3].toInt() == 13) {
                        val req = com.mhmmtbg.x5reframe.camera.Pb.fields(p.copyOfRange(12, p.size))
                        val start = (req.firstOrNull { it.first == 2 }?.second as? Long ?: 0L).toInt()
                        val limit = (req.firstOrNull { it.first == 3 }?.second as? Long ?: 500L).toInt()
                        val w = com.mhmmtbg.x5reframe.camera.Pb.Writer()
                        uris.drop(start).take(limit).forEach { w.string(1, it) }
                        w.varint(2, uris.size.toLong())
                        // Yanıt: kod 200, aynı sıra numarası
                        send(byteArrayOf(4, 0, 0, 200.toByte(), 0, 2, p[6], p[7], p[8], 0x80.toByte(), 0, 0) + w.bytes())
                    }
                }
            }
        }
        } }
        return ss
    }

    @Test
    fun listsEverythingThroughNativeChannel() {
        val uris = files.keys.filter { !it.startsWith("LRV") }.map { "/DCIM/Camera01/$it" } +
            listOf("/DCIM/Camera02/VID_20260712_085744_00_031.insv", "/DCIM/Camera02/IMG_20260712_090000_00_032.insp")
        val ns = fakeNative(uris)
        // Kanal kendi başına çalışıyor mu (hata ayrıntısı için doğrudan)
        val direct = runCatching { com.mhmmtbg.x5reframe.camera.InstaClient("127.0.0.1", ns.localPort).open().use { it.listFiles() } }
        android.util.Log.i("OscClientTest", "6666 doğrudan: ${direct.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + it.message } ?: direct.getOrNull()?.size}")
        val r = com.mhmmtbg.x5reframe.camera.CameraLister.listAll(client(),
            { com.mhmmtbg.x5reframe.camera.InstaClient("127.0.0.1", ns.localPort).open() }, "http://127.0.0.1:${server.localPort}", null)
        ns.close()
        val names = r.files.map { it.name }.toSet()
        assertEquals("kamera", r.source)
        assertTrue("gizli video yok: $names", "VID_20260712_085744_00_031.insv" in names)
        assertTrue("gizli LRV bulunmadı: $names", "LRV_20260712_085744_01_031.lrv" in names)
        assertTrue("foto yok", "IMG_20260712_090000_00_032.insp" in names)
        assertEquals(4000L, r.files.first { it.name == "VID_20260712_085744_00_031.insv" }.size)
        assertEquals("2026:07:12 08:57:44", r.files.first { it.name.startsWith("VID_20260712") }.date)
    }

    /** Kameranın web sunucusundaki dizin listesi: alt klasörler ve LRV dahil her dosya, tam boyutlarıyla. */
    @Test
    fun listsEverythingThroughDirectoryListing() {
        val r = com.mhmmtbg.x5reframe.camera.CameraLister.listAll(null, null, "http://127.0.0.1:${server.localPort}", null)
        val names = r.files.map { it.name }.toSet()
        android.util.Log.i("OscClientTest", "dizin: ${r.source} $names")
        assertEquals("dizin", r.source)
        assertEquals(6, names.size)
        assertTrue("LRV_20260712_085744_01_031.lrv" in names && "IMG_20260712_090000_00_032.insp" in names)
        assertFalse("fileinfo_list.list" in names)
        assertEquals(4000L, r.files.first { it.name == "VID_20260712_085744_00_031.insv" }.size)
        assertEquals(300_000L, r.files.first { it.name == "VID_20260926_141617_00_142.insv" }.size)
    }

    @Test
    fun rangeSourceReadsPartsOnly() {
        val c = client()
        val vid = c.listFiles().first { it.isVideo }
        val src = com.mhmmtbg.x5reframe.camera.HttpRangeSource(vid.url, null)
        assertEquals(300_000L, src.size)
        val buf = ByteArray(100)
        assertEquals(100, src.readAt(299_900, buf, 0, 100))
        val full = files[vid.name]!!
        assertArrayEquals(full.copyOfRange(299_900, 300_000), buf)
        assertTrue("çok fazla indirildi: ${src.fetched}", src.fetched < 300_000)
    }

    @Test
    fun deletesFiles() {
        val c = client()
        val list = c.listFiles()
        c.delete(list.filter { it.shotKey == list.first { f -> f.isVideo }.shotKey }.map { it.url })
        assertEquals(1, c.listFiles().size)
    }
}
