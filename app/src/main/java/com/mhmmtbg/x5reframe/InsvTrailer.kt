package com.mhmmtbg.x5reframe

import android.content.ContentResolver
import android.net.Uri
import java.io.FileInputStream

/**
 * Insta360 .insv dosyalarının sonunda kalibrasyon, jiroskop ve pozlama verisini taşıyan
 * bir "trailer" bölümü bulunur ve 32 karakterlik sabit bir imza ile biter.
 * Bu sınıf şimdilik imzayı bulur ve son baytları gösterir; tam ayrıştırma örnek dosya
 * analizinden sonra eklenecek.
 */
object InsvTrailer {
    const val MAGIC = "8db42d694ccc418790edff439fe026bf"

    fun describe(cr: ContentResolver, uri: Uri): String = buildString {
        cr.openFileDescriptor(uri, "r")!!.use { pfd ->
            val size = pfd.statSize
            val tailLen = minOf(256L, size).toInt()
            val tail = ByteArray(tailLen)
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                ch.position(size - tailLen)
                val buf = java.nio.ByteBuffer.wrap(tail)
                while (buf.hasRemaining() && ch.read(buf) > 0) { /* oku */ }
            }
            val ascii = String(tail, Charsets.ISO_8859_1)
            val idx = ascii.lastIndexOf(MAGIC)
            if (idx >= 0) {
                appendLine("Insta360 trailer imzası: bulundu (sondan ${tailLen - idx} bayt)")
            } else {
                appendLine("Insta360 trailer imzası: bulunamadı")
            }
            appendLine("Son $tailLen bayt (hex):")
            tail.toList().chunked(16).forEach { row ->
                appendLine(row.joinToString(" ") { "%02x".format(it) })
            }
        }
    }
}
