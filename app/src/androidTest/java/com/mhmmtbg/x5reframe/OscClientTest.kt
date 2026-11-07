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
            method == "GET" && path.startsWith("/DCIM/Camera01/") -> {
                val data = files[path.substringAfterLast('/')]!!
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

    @Test
    fun deletesFiles() {
        val c = client()
        val list = c.listFiles()
        c.delete(list.filter { it.shotKey == list.first { f -> f.isVideo }.shotKey }.map { it.url })
        assertEquals(1, c.listFiles().size)
    }
}
