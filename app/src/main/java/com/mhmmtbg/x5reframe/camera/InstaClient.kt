package com.mhmmtbg.x5reframe.camera

import android.net.Network
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Insta360 kameraların kendi uygulamasının kullandığı denetim kanalı (TCP 6666). OSC arayüzü dosyaların
 * yalnızca bir kısmını listelediği için tam liste buradan alınır; dosyaların kendisi yine HTTP (80) ile iner.
 *
 * Paket: 4 bayt uzunluk (LE, kendisi dahil) + gövde.
 *  - Eşitleme: 06 00 00 "syNceNdinS"; canlı tutma: 05 00 00 (2 sn'de bir)
 *  - İleti: 04 00 00, ileti kodu (2 bayt LE), 02, sıra no (3 bayt LE), 80 00 00, protobuf
 *  - Yanıt: aynı başlık; kod yerine durum (200 tamam, 500 hata), sıra no isteğinkiyle aynı
 * Protokol bilgisi: RigacciOrg/insta360-wifi-api (tersine mühendislik notları); kod buradan yazıldı.
 */
class InstaClient(private val host: String = "192.168.42.1", private val port: Int = 6666, private val network: Network? = null) : AutoCloseable {

    class Response(val code: Int, val body: ByteArray)

    private lateinit var socket: Socket
    private lateinit var out: OutputStream
    private val pending = ConcurrentHashMap<Int, LinkedBlockingQueue<Response>>()
    private var seq = 1
    @Volatile private var closed = false
    @Volatile private var synced = false
    private val syncLatch = java.util.concurrent.CountDownLatch(1)
    private var lastSend = 0L

    fun open(timeoutMs: Int = 5000): InstaClient {
        socket = (network?.socketFactory?.createSocket() ?: Socket()).apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), timeoutMs)
            soTimeout = 0
        }
        out = socket.getOutputStream()
        Thread({ readLoop(socket.getInputStream()) }, "insta-rx").apply { isDaemon = true; start() }
        send(SYNC)
        send(KEEPALIVE)
        // Kamera eşitlemeyi yanıtlar; yanıtlamayan sürümler de komut kabul edebilir, kısa bekle
        syncLatch.await(2500, TimeUnit.MILLISECONDS)
        Thread({
            while (!closed) {
                Thread.sleep(500)
                if (System.currentTimeMillis() - lastSend > 2000) runCatching { send(KEEPALIVE) }
            }
        }, "insta-keepalive").apply { isDaemon = true; start() }
        return this
    }

    @Synchronized private fun send(payload: ByteArray) {
        val len = payload.size + 4
        out.write(byteArrayOf(len.toByte(), (len shr 8).toByte(), (len shr 16).toByte(), (len shr 24).toByte()))
        out.write(payload)
        out.flush()
        lastSend = System.currentTimeMillis()
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var o = 0
        while (o < n) { val r = inp.read(b, o, n - o); if (r < 0) throw IOException("bağlantı kapandı"); o += r }
        return b
    }

    private fun readLoop(inp: InputStream) {
        try {
            while (!closed) {
                val h = readFully(inp, 4)
                val len = (h[0].toInt() and 255) or ((h[1].toInt() and 255) shl 8) or ((h[2].toInt() and 255) shl 16) or ((h[3].toInt() and 255) shl 24)
                if (len < 4 || len > 64 * 1024 * 1024) throw IOException("geçersiz paket boyu $len")
                val p = readFully(inp, len - 4)
                if (p.size >= 3 && p[0].toInt() == 6) { synced = true; syncLatch.countDown(); continue }
                if (p.size < 12 || p[0].toInt() != 4) continue
                val code = (p[3].toInt() and 255) or ((p[4].toInt() and 255) shl 8)
                val s = (p[6].toInt() and 255) or ((p[7].toInt() and 255) shl 8) or ((p[8].toInt() and 255) shl 16)
                pending[s]?.offer(Response(code, p.copyOfRange(12, p.size)))
            }
        } catch (_: Throwable) {
            pending.values.forEach { it.offer(Response(-1, ByteArray(0))) }
        }
    }

    /** İleti gönder ve aynı sıra numaralı yanıtı bekle. */
    fun request(messageCode: Int, body: ByteArray, timeoutMs: Long = 10_000): Response {
        val s: Int
        synchronized(this) { s = seq++ and 0xFFFFFF }
        val q = LinkedBlockingQueue<Response>()
        pending[s] = q
        try {
            val head = byteArrayOf(4, 0, 0, messageCode.toByte(), (messageCode shr 8).toByte(), 2,
                s.toByte(), (s shr 8).toByte(), (s shr 16).toByte(), 0x80.toByte(), 0, 0)
            send(head + body)
            val r = q.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw IOException("kamera yanıt vermedi")
            if (r.code == -1) throw IOException("bağlantı koptu")
            if (r.code != 200) {
                val msg = Pb.fields(r.body).firstOrNull { it.first == 2 }?.second as? ByteArray
                throw IOException("kamera hatası ${r.code}" + (msg?.let { ": " + String(it) } ?: ""))
            }
            return r
        } finally { pending.remove(s) }
    }

    /** Kameradaki tüm video ve fotoğrafların yolları ("/DCIM/Camera01/VID_….insv" gibi). */
    fun listFiles(pageSize: Int = 500, progress: (Int, Int) -> Unit = { _, _ -> }): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var total = Int.MAX_VALUE
        while (out.size < total) {
            val body = Pb.Writer().varint(1, MEDIA_VIDEO_AND_PHOTO).varint(2, start.toLong()).varint(3, pageSize.toLong()).bytes()
            val r = request(CMD_GET_FILE_LIST, body, 20_000)
            val f = Pb.fields(r.body)
            val uris = f.filter { it.first == 1 }.mapNotNull { (it.second as? ByteArray)?.let { b -> String(b) } }
            total = (f.firstOrNull { it.first == 2 }?.second as? Long)?.toInt() ?: (out.size + uris.size)
            out += uris
            progress(out.size, total)
            if (uris.isEmpty() || uris.size < pageSize && out.size >= total) break
            start += uris.size
        }
        return out.distinct()
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        const val CMD_GET_FILE_LIST = 13
        const val MEDIA_VIDEO_AND_PHOTO = 2L
        val SYNC = byteArrayOf(6, 0, 0) + "syNceNdinS".toByteArray()
        val KEEPALIVE = byteArrayOf(5, 0, 0)
    }
}

/** En küçük protobuf yardımcıları (yalnızca gereken tür kodları: 0 varint, 2 uzunluklu). */
object Pb {
    class Writer {
        private val b = ByteArrayOutputStream()
        private fun raw(v: Long) { var x = v; while (true) { if (x and 0x7FL.inv() == 0L) { b.write(x.toInt()); return }; b.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 } }
        fun varint(field: Int, v: Long) = apply { raw((field shl 3).toLong()); raw(v) }
        fun bytes(field: Int, v: ByteArray) = apply { raw(((field shl 3) or 2).toLong()); raw(v.size.toLong()); b.write(v) }
        fun string(field: Int, v: String) = bytes(field, v.toByteArray())
        fun bytes(): ByteArray = b.toByteArray()
    }

    /** (alan no, değer) listesi: varint → Long, uzunluklu → ByteArray; diğer türler atlanır. */
    fun fields(data: ByteArray): List<Pair<Int, Any>> {
        val out = ArrayList<Pair<Int, Any>>()
        var i = 0
        fun varint(): Long { var r = 0L; var s = 0; while (i < data.size) { val c = data[i++].toInt() and 255; r = r or ((c and 0x7F).toLong() shl s); if (c < 0x80) break; s += 7 }; return r }
        while (i < data.size) {
            val key = varint(); val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out += field to varint()
                2 -> { val n = varint().toInt(); if (n < 0 || i + n > data.size) return out; out += field to data.copyOfRange(i, i + n); i += n }
                1 -> i += 8
                5 -> i += 4
                else -> return out
            }
        }
        return out
    }
}
