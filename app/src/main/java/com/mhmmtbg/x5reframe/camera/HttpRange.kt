package com.mhmmtbg.x5reframe.camera

import android.media.MediaDataSource
import android.net.Network
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Kameradaki bir dosyaya HTTP aralık istekleriyle rastgele erişim (yalnızca gereken parçalar iner). */
class HttpRangeSource(private val url: String, private val network: Network?, knownSize: Long = -1) : MediaDataSource() {
    private val block = 256 * 1024
    private val cache = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 24
    }
    private val total: Long = if (knownSize > 0) knownSize else head(url, network)
    /** Toplam indirilen bayt (teşhis için). */
    var fetched = 0L
        private set

    override fun getSize(): Long = total

    private fun blockAt(index: Long): ByteArray {
        cache[index]?.let { return it }
        val from = index * block
        val to = minOf(total, from + block) - 1
        val b = get(url, network, from, to)
        fetched += b.size
        cache[index] = b
        return b
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= total) return -1
        var done = 0
        var pos = position
        while (done < size && pos < total) {
            val idx = pos / block
            val b = blockAt(idx)
            val inBlock = (pos - idx * block).toInt()
            val n = minOf(size - done, b.size - inBlock)
            if (n <= 0) break
            System.arraycopy(b, inBlock, buffer, offset + done, n)
            done += n; pos += n
        }
        return done
    }

    override fun close() { cache.clear() }

    companion object {
        private fun open(url: String, network: Network?) = (network?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection

        /** Dosya boyu (bulunamazsa -1). */
        /** Küçük bir metin sayfası (dizin listesi) indir. */
        fun getText(url: String, network: Network?): String {
            val c = open(url, network)
            c.connectTimeout = 4000; c.readTimeout = 8000
            try {
                if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                return c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            } finally { c.disconnect() }
        }

        fun head(url: String, network: Network?): Long {
            val c = open(url, network)
            return try {
                c.requestMethod = "HEAD"; c.connectTimeout = 4000; c.readTimeout = 6000
                if (c.responseCode in 200..299) c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1 else -1
            } catch (e: IOException) { -1 } finally { c.disconnect() }
        }

        fun get(url: String, network: Network?, from: Long, to: Long): ByteArray {
            val c = open(url, network)
            try {
                c.connectTimeout = 5000; c.readTimeout = 15000
                c.setRequestProperty("Range", "bytes=$from-$to")
                val code = c.responseCode
                if (code != 206 && !(code == 200 && from == 0L)) throw IOException("aralık okunamadı (HTTP $code)")
                return c.inputStream.use { inp ->
                    if (code == 200) { val want = (to - from + 1).toInt(); val b = ByteArray(want); var o = 0; while (o < want) { val r = inp.read(b, o, want - o); if (r < 0) break; o += r }; b.copyOf(o) }
                    else inp.readBytes()
                }
            } finally { c.disconnect() }
        }
    }
}
