package com.mhmmtbg.x5reframe.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 360° videolar için Google "Spherical Video V1" bilgisini MP4'e ekler (YouTube, VR oynatıcılar).
 * MediaMuxer moov kutusunu dosyanın sonuna yazdığı için, kutu video trak'ının sonuna eklenir ve
 * yalnızca moov yeniden yazılır; medya verisi (mdat) ve parça ofsetleri değişmez.
 */
object SphericalMetadata {
    private val UUID = byteArrayOf(
        0xff.toByte(), 0xcc.toByte(), 0x82.toByte(), 0x63, 0xf8.toByte(), 0x55, 0x4a, 0x93.toByte(),
        0x88.toByte(), 0x14, 0x58, 0x7a, 0x02, 0x52, 0x1f, 0xdd.toByte(),
    )

    private const val XML = "<?xml version=\"1.0\"?><rdf:SphericalVideo " +
        "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" " +
        "xmlns:GSpherical=\"http://ns.google.com/videos/1.0/spherical/\">" +
        "<GSpherical:Spherical>true</GSpherical:Spherical>" +
        "<GSpherical:Stitched>true</GSpherical:Stitched>" +
        "<GSpherical:StitchingSoftware>X5 Reframe</GSpherical:StitchingSoftware>" +
        "<GSpherical:ProjectionType>equirectangular</GSpherical:ProjectionType>" +
        "</rdf:SphericalVideo>"

    private data class Box(val type: String, val offset: Long, val size: Long, val header: Int)

    private fun readBoxes(buf: ByteBuffer, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = start
        while (p + 8 <= end) {
            var size = buf.getInt(p).toLong() and 0xffffffffL
            val type = String(ByteArray(4) { buf.get(p + 4 + it) }, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = buf.getLong(p + 8); header = 16 }
            if (size == 0L) size = (end - p).toLong()
            if (size < header || p + size > end) break
            out += Box(type, p.toLong(), size, header)
            p += size.toInt()
        }
        return out
    }

    /** @return eklendiyse true */
    fun inject(ch: FileChannel): Boolean {
        val fileSize = ch.size()
        // Üst düzey kutuları dolaş.
        var pos = 0L
        var moov: Box? = null
        var mdatOffset = -1L
        val hdr = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        while (pos + 8 <= fileSize) {
            hdr.clear(); ch.read(hdr, pos)
            var size = hdr.getInt(0).toLong() and 0xffffffffL
            val type = String(ByteArray(4) { hdr.get(4 + it) }, Charsets.ISO_8859_1)
            if (size == 1L) size = hdr.getLong(8)
            if (size == 0L) size = fileSize - pos
            if (size < 8) return false
            if (type == "moov") moov = Box(type, pos, size, 8)
            if (type == "mdat") mdatOffset = pos
            pos += size
        }
        val m = moov ?: return false
        // moov mdat'tan önceyse ofsetler kayar; bu durumda dokunma.
        if (mdatOffset < 0 || mdatOffset > m.offset) return false
        if (m.offset + m.size != fileSize) return false
        if (m.size > 64L * 1024 * 1024) return false

        val moovBuf = ByteBuffer.allocate(m.size.toInt()).order(ByteOrder.BIG_ENDIAN)
        ch.read(moovBuf, m.offset)
        val traks = readBoxes(moovBuf, 8, m.size.toInt()).filter { it.type == "trak" }
        val video = traks.firstOrNull { trak -> handlerOf(moovBuf, trak) == "vide" } ?: return false

        val xml = XML.toByteArray(Charsets.UTF_8)
        val uuidSize = 8 + 16 + xml.size
        val newMoov = ByteBuffer.allocate(m.size.toInt() + uuidSize).order(ByteOrder.BIG_ENDIAN)
        val trakEnd = (video.offset + video.size).toInt()
        val bytes = moovBuf.array()
        newMoov.put(bytes, 0, trakEnd)
        newMoov.putInt(uuidSize); newMoov.put("uuid".toByteArray(Charsets.ISO_8859_1)); newMoov.put(UUID); newMoov.put(xml)
        newMoov.put(bytes, trakEnd, bytes.size - trakEnd)
        newMoov.putInt(0, (m.size + uuidSize).toInt())
        newMoov.putInt(video.offset.toInt(), (video.size + uuidSize).toInt())
        newMoov.flip()
        var w = m.offset
        while (newMoov.hasRemaining()) w += ch.write(newMoov, w)
        return true
    }

    private fun handlerOf(buf: ByteBuffer, trak: Box): String? {
        val mdia = readBoxes(buf, (trak.offset + trak.header).toInt(), (trak.offset + trak.size).toInt()).firstOrNull { it.type == "mdia" } ?: return null
        val hdlr = readBoxes(buf, (mdia.offset + mdia.header).toInt(), (mdia.offset + mdia.size).toInt()).firstOrNull { it.type == "hdlr" } ?: return null
        // hdlr: header, version/flags(4), pre_defined(4), handler_type(4)
        val p = (hdlr.offset + hdlr.header + 8).toInt()
        return String(ByteArray(4) { buf.get(p + it) }, Charsets.ISO_8859_1)
    }
}
