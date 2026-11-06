package com.mhmmtbg.x5reframe.insv

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Insta360 dosya sonu ("trailer") okuyucu.
 *
 * Düzen telemetry-parser (AdrianEddy, MIT/Apache-2.0) belgelerine göre:
 *  - Son 72 bayt: 32 dolgu, u32 trailer boyutu, u32 sürüm, 32 baytlık ASCII imza.
 *  - Her kaydın sonunda 6 baytlık tanımlayıcı: u8 format, u8 id, u32 boyut; veri tanımlayıcıdan önce.
 *  - X4/X5'te en sondaki kayıt (id 0) bir tablo: her kayıt için 10 bayt (id, format, boyut, trailer içi ofset).
 */
class Trailer private constructor(private val ch: FileChannel, val trailerStart: Long, val size: Long) {

    data class Entry(val id: Int, val format: Int, val size: Int, val offset: Long)

    val entries = HashMap<Int, Entry>()

    fun read(id: Int): ByteArray? {
        val e = entries[id] ?: return null
        return readAt(trailerStart + e.offset, e.size)
    }

    private fun readAt(pos: Long, len: Int): ByteArray {
        val buf = ByteBuffer.allocate(len)
        var p = pos
        while (buf.hasRemaining()) {
            val n = ch.read(buf, p)
            if (n <= 0) throw IOException("dosya beklenenden kısa")
            p += n
        }
        return buf.array()
    }

    companion object {
        const val MAGIC = "8db42d694ccc418790edff439fe026bf"
        const val HEADER = 72
        const val ID_OFFSETS = 0
        const val ID_METADATA = 1
        const val ID_GYRO = 3

        fun open(ch: FileChannel): Trailer {
            val fileSize = ch.size()
            val footer = ByteBuffer.allocate(HEADER)
            ch.read(footer, fileSize - HEADER)
            val f = footer.array()
            if (String(f, HEADER - 32, 32, Charsets.ISO_8859_1) != MAGIC) {
                throw IOException("Insta360 verisi bulunamadı (bu bir .insv/.lrv dosyası mı?)")
            }
            val bb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
            val trailerSize = bb.getInt(32).toLong() and 0xffffffffL
            require(trailerSize in HEADER..fileSize) { "trailer boyutu geçersiz" }
            val t = Trailer(ch, fileSize - trailerSize, trailerSize)
            t.scan(fileSize)
            return t
        }
    }

    private fun scan(fileSize: Long) {
        // İlk tanımlayıcı: tablo mu?
        var offset = (HEADER + 6).toLong()
        val first = descriptorAt(fileSize - offset)
        if (first.second == ID_OFFSETS && offset + first.third <= size) {
            val table = readAt(fileSize - offset - first.third, first.third)
            val tb = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i + 10 <= table.size) {
                val id = table[i].toInt() and 0xff
                val fmt = table[i + 1].toInt() and 0xff
                val sz = tb.getInt(i + 2)
                val off = tb.getInt(i + 6).toLong() and 0xffffffffL
                if (id > 0 && off + sz <= size) entries[id] = Entry(id, fmt, sz, off)
                i += 10
            }
            if (entries.isNotEmpty()) return
        }
        // Tablosuz düzen: tanımlayıcıları geriye doğru yürü.
        var count = 0
        while (offset < size && count < 256) {
            val (fmt, id, sz) = descriptorAt(fileSize - offset)
            if (offset + sz > size) break
            val start = fileSize - offset - sz
            if (!entries.containsKey(id)) entries[id] = Entry(id, fmt, sz, start - trailerStart)
            offset += sz + 6
            count++
        }
    }

    /** (format, id, boyut) */
    private fun descriptorAt(pos: Long): Triple<Int, Int, Int> {
        val d = readAt(pos, 6)
        val sz = ByteBuffer.wrap(d, 2, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return Triple(d[0].toInt() and 0xff, d[1].toInt() and 0xff, sz)
    }
}
