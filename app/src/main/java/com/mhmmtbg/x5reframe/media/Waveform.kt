package com.mhmmtbg.x5reframe.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Zaman çizelgesi için ses dalga biçimi: her [binUs] aralığının RMS seviyesi (0..1).
 * Ses bir kez çözülür; sonuç uygulama açık kaldıkça bellekte tutulur.
 */
object Waveform {
    const val BIN_US = 50_000L
    private val cache = HashMap<String, FloatArray>()

    @Synchronized fun cached(uri: Uri): FloatArray? = cache[uri.toString()]

    /** Bellek azalınca: yalnızca [keep] klibinin dalga biçimi kalsın. */
    @Synchronized fun trim(keep: Uri?) { cache.keys.retainAll { it == keep?.toString() } }

    fun compute(context: Context, uri: Uri, track: Int, cancelled: () -> Boolean = { false }): FloatArray? {
        cached(uri)?.let { return it }
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
        pfd.use {
            val ex = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                ex.setDataSource(it.fileDescriptor)
                ex.selectTrack(track)
                val fmt = ex.getTrackFormat(track)
                val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else return null
                val bins = (durUs / BIN_US + 1).toInt().coerceIn(1, 200_000)
                val sum = DoubleArray(bins); val cnt = IntArray(bins)
                codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(fmt, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inEos = false
                var channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48_000
                while (!cancelled()) {
                    if (!inEos) {
                        val ii = codec.dequeueInputBuffer(5_000)
                        if (ii >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                            else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 5_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = codec.outputFormat
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT); rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else if (oi >= 0) {
                        val buf = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val sb = buf.asShortBuffer()
                        val frames = sb.remaining() / channels.coerceAtLeast(1)
                        // Her 8. kare yeterli (görsel amaçlı)
                        var f = 0
                        while (f < frames) {
                            val t = info.presentationTimeUs + f * 1_000_000L / rate
                            val b = (t / BIN_US).toInt()
                            if (b in 0 until bins) {
                                val v = sb.get(f * channels) / 32768.0
                                sum[b] += v * v; cnt[b]++
                            }
                            f += 8
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                if (cancelled()) return null
                val out = FloatArray(bins) { i -> if (cnt[i] > 0) sqrt(sum[i] / cnt[i]).toFloat() else 0f }
                // Normalize: en yüksek değerlerin %98'i 1'e
                val sorted = out.sorted()
                val ref = sorted[(sorted.size * 0.98).toInt().coerceIn(0, sorted.size - 1)].coerceAtLeast(0.02f)
                for (i in out.indices) out[i] = (out[i] / ref).coerceIn(0f, 1f)
                synchronized(this) { cache[uri.toString()] = out }
                return out
            } catch (t: Throwable) {
                return null
            } finally {
                runCatching { codec?.stop() }; runCatching { codec?.release() }
                ex.release()
            }
        }
    }

}
