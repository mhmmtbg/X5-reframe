package com.mhmmtbg.x5reframe.captions

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.mhmmtbg.x5reframe.ai.AiException
import com.mhmmtbg.x5reframe.ai.AiProvider
import com.mhmmtbg.x5reframe.ai.AiSettings
import com.mhmmtbg.x5reframe.media.PcmReader
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** Bir altyazı satırı (kaynak zamanı, µs). */
data class Caption(val startUs: Long, val endUs: Long, val text: String)

/**
 * Otomatik altyazı. Ses 16 kHz mono'ya çevrilir; konuşma bölümleri enerjiye göre ayrılır.
 * - Cihazda: Android'in cihaz içi konuşma tanıması (Android 13+, dil paketi indirilmişse internetsiz).
 * - OpenAI Whisper: kullanıcının kendi API anahtarıyla (ses OpenAI'ye gönderilir).
 */
object Captions {
    const val RATE = 16_000

    /** Kaynak aralığının sesi, 16 kHz mono 16 bit. */
    fun pcm16k(context: Context, uri: Uri, track: Int, startUs: Long, endUs: Long, cancelled: () -> Boolean): ShortArray {
        val r = PcmReader(context, uri, track, startUs)
        try {
            val frames48 = ((endUs - startUs) * 48_000 / 1_000_000).toInt().coerceAtLeast(0)
            val out = ShortArray(frames48 / 3 + 1)
            val tmp = ShortArray(3 * 1024 * 2)
            var done = 0; var o = 0
            while (done < frames48 && !cancelled()) {
                val n = minOf(3 * 1024, frames48 - done)
                r.read(n, tmp, 1f, false)
                var i = 0
                while (i + 2 < n && o < out.size) {
                    // 3 kareyi ortala (basit alçak geçiren + 48 → 16 kHz)
                    var s = 0
                    for (k in 0..2) s += tmp[(i + k) * 2] + tmp[(i + k) * 2 + 1]
                    out[o++] = (s / 6).toShort()
                    i += 3
                }
                done += n
            }
            return out.copyOf(o)
        } finally { r.close() }
    }

    /**
     * Konuşma bölümleri (örnek indisleri): 30 ms pencerelerin enerjisi uyarlamalı eşiği geçince konuşma.
     * Kısa boşluklar birleştirilir, uzun bölümler en sessiz yerden bölünür (en fazla ~7 sn).
     */
    fun speechSegments(pcm: ShortArray): List<IntRange> {
        val win = RATE * 30 / 1000
        val n = pcm.size / win
        if (n == 0) return emptyList()
        val e = FloatArray(n) { i ->
            var s = 0.0
            for (k in 0 until win) { val v = pcm[i * win + k] / 32768.0; s += v * v }
            sqrt(s / win).toFloat()
        }
        val sorted = e.sorted()
        val floor = sorted[(n * 0.1).toInt().coerceAtMost(n - 1)]
        val loud = sorted[(n * 0.9).toInt().coerceAtMost(n - 1)]
        val th = maxOf(floor * 2.5f, floor + (loud - floor) * 0.18f, 0.004f)
        val on = BooleanArray(n) { e[it] > th }
        // Boşlukları kapat (≤ 350 ms), çok kısa bölümleri at (< 250 ms)
        val raw = ArrayList<IntRange>()
        var i = 0
        while (i < n) {
            if (!on[i]) { i++; continue }
            var j = i
            var gap = 0
            while (j + 1 < n && (on[j + 1] || gap < 12)) { j++; if (on[j]) gap = 0 else gap++ }
            j -= gap
            if (j - i + 1 >= 8) raw += i..j
            i = j + 1
        }
        // Uzun bölümleri böl
        val maxW = 7000 / 30
        val out = ArrayList<IntRange>()
        for (r in raw) {
            var a = r.first
            while (r.last - a + 1 > maxW) {
                val lo = a + maxW / 2; val hi = a + maxW
                val cut = (lo..minOf(hi, r.last)).minByOrNull { e[it] } ?: hi
                out += a until cut
                a = cut
            }
            out += a..r.last
        }
        // Biraz pay bırak, örnek indisine çevir
        return out.map { (maxOf(0, it.first - 4) * win) until minOf(pcm.size, (it.last + 6) * win) }
    }

    fun onDeviceAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Cihaz içi tanıma: her konuşma bölümü ayrı ayrı (ana iş parçacığında çalışır, burada beklenir). */
    fun recognizeOnDevice(
        context: Context, pcm: ShortArray, offsetUs: Long, language: String,
        cancelled: () -> Boolean, progress: (Float) -> Unit,
    ): List<Caption> {
        check(Build.VERSION.SDK_INT >= 33) { "Cihazda tanıma Android 13 gerektirir" }
        val segs = speechSegments(pcm)
        val main = Handler(Looper.getMainLooper())
        val out = ArrayList<Caption>()
        val dir = File(context.cacheDir, "asr").apply { mkdirs() }
        segs.forEachIndexed { idx, r ->
            if (cancelled()) return out
            progress(idx.toFloat() / segs.size)
            val f = File(dir, "seg.pcm")
            f.outputStream().use { os ->
                val bb = ByteBuffer.allocate((r.last - r.first + 1) * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (k in r) bb.putShort(pcm[k])
                os.write(bb.array())
            }
            val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            val done = CountDownLatch(1)
            var text: String? = null
            var err: Int? = null
            var rec: SpeechRecognizer? = null
            main.post {
                rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onResults(results: Bundle) {
                            text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(); done.countDown()
                        }
                        override fun onError(error: Int) { err = error; done.countDown() }
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
                    })
                }
            }
            done.await(30, TimeUnit.SECONDS)
            main.post { runCatching { rec?.destroy() } }
            runCatching { pfd.close() }
            val e = err
            // "Konuşma yok" hatalarını atla; dil paketi yoksa dur
            if (e == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || e == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                throw IllegalStateException("Cihazda bu dil için konuşma tanıma paketi yok (Ayarlar › Google › Konuşma tanıma'dan Türkçe'yi indir)")
            val t = text?.trim()
            if (!t.isNullOrEmpty()) out += Caption(offsetUs + r.first * 1_000_000L / RATE, offsetUs + (r.last + 1) * 1_000_000L / RATE, t)
        }
        progress(1f)
        return out
    }

    /** OpenAI Whisper (ses dosyası olarak gönderilir; bölüm zamanlarıyla döner). */
    fun recognizeOpenAi(context: Context, pcm: ShortArray, offsetUs: Long, language: String?): List<Caption> {
        val key = AiSettings.key(context, AiProvider.CHATGPT) ?: throw AiException("Ayarlar › Yapay zekâ'da ChatGPT (OpenAI) API anahtarı gir")
        val wav = wav(pcm)
        if (wav.size > 24 * 1024 * 1024) throw AiException("Ses 25 MB sınırını aşıyor; daha kısa bir aralık seç")
        val boundary = "x5" + System.nanoTime()
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("model", "whisper-1")
        field("response_format", "verbose_json")
        field("timestamp_granularities[]", "segment")
        language?.let { field("language", it) }
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        body.write(wav)
        body.write("\r\n--$boundary--\r\n".toByteArray())
        val url = "https://api.openai.com/v1/audio/transcriptions"
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 20_000; c.readTimeout = 300_000
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setFixedLengthStreamingMode(body.size())
            c.outputStream.use { body.writeTo(it) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull() ?: text.take(200)
                throw AiException(com.mhmmtbg.x5reframe.ai.AiClient.friendly(url, code, msg))
            }
            val segs = JSONObject(text).optJSONArray("segments") ?: return emptyList()
            return (0 until segs.length()).mapNotNull { i ->
                val s = segs.getJSONObject(i)
                val t = s.optString("text").trim()
                if (t.isEmpty()) null else Caption(offsetUs + (s.optDouble("start") * 1e6).toLong(), offsetUs + (s.optDouble("end") * 1e6).toLong(), t)
            }
        } catch (e: java.io.IOException) {
            throw AiException("Bağlantı hatası: ${e.message}")
        } finally { c.disconnect() }
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val data = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + data); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1); bb.putInt(RATE); bb.putInt(RATE * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(data)
        for (s in pcm) bb.putShort(s)
        return bb.array()
    }

    /** Uzun satırları en fazla iki satıra böl (~34 karakter). */
    fun wrap(text: String, max: Int = 34): String {
        if (text.length <= max) return text
        val words = text.split(' ')
        var best = text; var bestDiff = Int.MAX_VALUE
        var left = ""
        for (i in 0 until words.size - 1) {
            left = if (left.isEmpty()) words[i] else "$left ${words[i]}"
            val right = words.drop(i + 1).joinToString(" ")
            val d = kotlin.math.abs(left.length - right.length)
            if (d < bestDiff) { bestDiff = d; best = "$left\n$right" }
        }
        return best
    }
}
