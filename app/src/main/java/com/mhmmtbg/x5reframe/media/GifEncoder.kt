package com.mhmmtbg.x5reframe.media

import java.io.OutputStream

/**
 * Basit animasyonlu GIF kodlayıcı: sabit 6×7×6 renk paleti (252 renk), sıralı (Bayer) titreştirme,
 * LZW sıkıştırma, sonsuz döngü. Kareler RGBA (satırlar yukarıdan aşağı) verilir.
 */
class GifEncoder(private val out: OutputStream, private val width: Int, private val height: Int, private val delayCs: Int) {
    private var started = false
    private val index = ByteArray(width * height)

    private fun writeShort(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }

    private fun header() {
        out.write("GIF89a".toByteArray())
        writeShort(width); writeShort(height)
        out.write(0xF7) // genel renk tablosu, 8 bit, 256 giriş
        out.write(0); out.write(0)
        for (i in 0 until 256) {
            if (i < 252) {
                val r = i / 42; val g = (i / 6) % 7; val b = i % 6
                out.write(r * 255 / 5); out.write(g * 255 / 6); out.write(b * 255 / 5)
            } else { out.write(0); out.write(0); out.write(0) }
        }
        // NETSCAPE2.0: sonsuz döngü
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B)); out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))
        started = true
    }

    /** @param rgba width×height×4 bayt, üst satır başta */
    fun addFrame(rgba: ByteArray) {
        if (!started) header()
        // Sıralı titreştirme ile palete indir
        for (y in 0 until height) for (x in 0 until width) {
            val o = (y * width + x) * 4
            val d = BAYER[(y and 3) * 4 + (x and 3)] / 16f - 0.47f
            val r = ((rgba[o].toInt() and 255) / 255f * 5 + d).toInt().coerceIn(0, 5)
            val g = ((rgba[o + 1].toInt() and 255) / 255f * 6 + d).toInt().coerceIn(0, 6)
            val b = ((rgba[o + 2].toInt() and 255) / 255f * 5 + d).toInt().coerceIn(0, 5)
            index[y * width + x] = (r * 42 + g * 6 + b).toByte()
        }
        // Grafik denetimi (gecikme) + görüntü tanımlayıcı
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00)); writeShort(delayCs); out.write(0); out.write(0)
        out.write(0x2C); writeShort(0); writeShort(0); writeShort(width); writeShort(height); out.write(0)
        lzw(index)
    }

    fun finish() {
        if (!started) header()
        out.write(0x3B)
        out.flush()
    }

    /** GIF LZW (en küçük kod boyu 8), 255 baytlık alt bloklarla. */
    private fun lzw(data: ByteArray) {
        val minCode = 8
        out.write(minCode)
        val clear = 1 shl minCode; val eoi = clear + 1
        val block = ByteArray(255); var blen = 0
        var acc = 0; var bits = 0
        fun emitByte(b: Int) { block[blen++] = b.toByte(); if (blen == 255) { out.write(255); out.write(block, 0, 255); blen = 0 } }
        var codeSize = minCode + 1
        fun emit(code: Int) {
            acc = acc or (code shl bits); bits += codeSize
            while (bits >= 8) { emitByte(acc and 0xff); acc = acc ushr 8; bits -= 8 }
        }
        // Sözlük: (önek kodu << 8 | bayt) → kod
        val dict = HashMap<Int, Int>(8192)
        var next = eoi + 1
        emit(clear)
        var prefix = data[0].toInt() and 255
        for (i in 1 until data.size) {
            val c = data[i].toInt() and 255
            val key = (prefix shl 8) or c
            val found = dict[key]
            if (found != null) { prefix = found; continue }
            emit(prefix)
            if (next < 4096) {
                dict[key] = next++
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                emit(clear); dict.clear(); next = eoi + 1; codeSize = minCode + 1
            }
            prefix = c
        }
        emit(prefix)
        emit(eoi)
        if (bits > 0) emitByte(acc and 0xff)
        if (blen > 0) { out.write(blen); out.write(block, 0, blen) }
        out.write(0)
    }

    companion object {
        private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    }
}
