package com.mhmmtbg.x5reframe

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Aşama 0: klibi seç, video izlerini ve Insta360 trailer bölümünü incele.
 * Reframe önizleme ve export, örnek dosya analizinden sonra eklenecek.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var infoText: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) inspect(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        infoText = findViewById(R.id.infoText)
        findViewById<Button>(R.id.pickButton).setOnClickListener {
            // .insv/.lrv dosyaları çoğu cihazda video/* veya application/octet-stream görünür.
            picker.launch(arrayOf("video/*", "application/octet-stream", "*/*"))
        }
    }

    private fun inspect(uri: Uri) {
        infoText.text = "İnceleniyor…"
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching { buildReport(uri) }.getOrElse { "Hata: ${it.message}" }
            }
            infoText.text = report
        }
    }

    private fun buildReport(uri: Uri): String = buildString {
        appendLine("Dosya: ${uri.lastPathSegment}")

        val extractor = MediaExtractor()
        contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            extractor.setDataSource(pfd.fileDescriptor)
            appendLine("Dosya boyutu: ${pfd.statSize / (1024 * 1024)} MB")
            appendLine("İz sayısı: ${extractor.trackCount}")
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: "?"
                append("  [$i] $mime")
                if (mime.startsWith("video/")) {
                    append(" ${f.intOrNull(MediaFormat.KEY_WIDTH)}x${f.intOrNull(MediaFormat.KEY_HEIGHT)}")
                    f.intOrNull(MediaFormat.KEY_FRAME_RATE)?.let { append(" @${it}fps") }
                }
                if (f.containsKey(MediaFormat.KEY_DURATION)) {
                    append("  süre=${"%.2f".format(f.getLong(MediaFormat.KEY_DURATION) / 1e6)} sn")
                }
                appendLine()
            }
            extractor.release()
        }

        appendLine()
        appendLine(InsvTrailer.describe(contentResolver, uri))
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
}
