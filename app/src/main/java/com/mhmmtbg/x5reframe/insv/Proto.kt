package com.mhmmtbg.x5reframe.insv

/** Sadece ihtiyaç duyulan alanları okuyan küçük bir protobuf okuyucu. */
class Proto(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {

    class Field(val tag: Int, val wire: Int, val varint: Long, val bytes: ByteArray?, val fixed64: Long)

    private fun varint(): Long {
        var r = 0L
        var shift = 0
        while (true) {
            val x = b[pos++].toInt() and 0xff
            r = r or ((x and 0x7f).toLong() shl shift)
            if (x < 0x80) return r
            shift += 7
            if (shift > 63) throw IllegalStateException("varint")
        }
    }

    /** Tüm alanları okur; aynı etiket tekrar ederse ilki tutulur. */
    fun fields(): Map<Int, Field> {
        val out = HashMap<Int, Field>()
        while (pos < end) {
            val key = varint()
            val tag = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            val f = when (wire) {
                0 -> Field(tag, wire, varint(), null, 0)
                1 -> {
                    var v = 0L
                    for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xff) shl (8 * i))
                    pos += 8
                    Field(tag, wire, 0, null, v)
                }
                2 -> {
                    val len = varint().toInt()
                    val bytes = b.copyOfRange(pos, pos + len)
                    pos += len
                    Field(tag, wire, 0, bytes, 0)
                }
                5 -> {
                    var v = 0L
                    for (i in 0 until 4) v = v or ((b[pos + i].toLong() and 0xff) shl (8 * i))
                    pos += 4
                    Field(tag, wire, 0, null, v)
                }
                else -> throw IllegalStateException("desteklenmeyen wire tipi $wire")
            }
            if (!out.containsKey(tag)) out[tag] = f
        }
        return out
    }
}

fun Map<Int, Proto.Field>.string(tag: Int): String? = this[tag]?.bytes?.toString(Charsets.UTF_8)
fun Map<Int, Proto.Field>.long(tag: Int): Long? = this[tag]?.varint
fun Map<Int, Proto.Field>.double(tag: Int): Double? = this[tag]?.let { java.lang.Double.longBitsToDouble(it.fixed64) }
fun Map<Int, Proto.Field>.message(tag: Int): Map<Int, Proto.Field>? = this[tag]?.bytes?.let { Proto(it).fields() }
