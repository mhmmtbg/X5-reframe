package com.mhmmtbg.x5reframe.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val MIX_RATE = 48_000

/**
 * Bir ses dosyasını / izini sırayla 48 kHz stereo 16 bit PCM olarak okur.
 * Kanal sayısı ve örnekleme hızı gerekirse dönüştürülür.
 */
class PcmReader(context: Context, uri: Uri, trackIndex: Int? = null, startUs: Long = 0) {
    private val pfd = context.contentResolver.openFileDescriptor(uri, "r")!!
    private val ex = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inEos = false
    var eos = false
        private set
    private var srcRate = MIX_RATE
    private var srcCh = 2
    // Kaynak hızında, stereo ara tampon
    private var buf = ShortArray(0)
    private var bufLen = 0 // stereo kare sayısı
    private var pos = 0.0  // tampon içindeki kesirli okuma konumu (kaynak kare)
    private var skipFrames = 0L
    private var pendingStartUs = 0L
    private var firstPts = -1L

    init {
        ex.setDataSource(pfd.fileDescriptor)
        val track = trackIndex ?: (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        srcCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        // Atlamamız gereken kaynak kare sayısı, ilk çözülen örneğin zamanına göre hesaplanır.
        pendingStartUs = startUs
        codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
    }

    /** Bir sonraki çözülmüş PCM bloğunu ara tampona ekler; akış bittiyse false. */
    private fun decodeMore(): Boolean {
        while (true) {
            if (!inEos) {
                val ii = codec.dequeueInputBuffer(5000)
                if (ii >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                    else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 5000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); srcCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                continue
            }
            if (oi >= 0) {
                val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0) {
                    val ob = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val sb = ob.asShortBuffer()
                    val frames = sb.remaining() / srcCh
                    if (firstPts < 0) {
                        firstPts = info.presentationTimeUs
                        skipFrames = ((pendingStartUs - firstPts).coerceAtLeast(0) * srcRate / 1_000_000)
                    }
                    // Tamponu sıkıştır ve genişlet
                    val keep = (bufLen - pos.toInt()).coerceAtLeast(0)
                    val start = pos.toInt().coerceAtMost(bufLen)
                    val nb = ShortArray((keep + frames) * 2)
                    System.arraycopy(buf, start * 2, nb, 0, keep * 2)
                    pos -= start
                    var w = keep
                    for (f in 0 until frames) {
                        val l = sb.get(f * srcCh)
                        val r = if (srcCh >= 2) sb.get(f * srcCh + 1) else l
                        if (skipFrames > 0) { skipFrames--; continue }
                        nb[w * 2] = l; nb[w * 2 + 1] = r; w++
                    }
                    buf = nb; bufLen = w
                }
                codec.releaseOutputBuffer(oi, false)
                if (done) { eos = true; return false }
                if (info.size > 0) return true
            } else if (inEos && oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // Çözücü boşalmayı bekliyor
                continue
            }
        }
    }

    /** n kare (48 kHz stereo) okur; akış bitince sıfırla doldurur. */
    fun read(n: Int, out: ShortArray, gain: Float, mix: Boolean) {
        val step = srcRate.toDouble() / MIX_RATE
        for (i in 0 until n) {
            while (pos + 1 >= bufLen && !eos) { if (!decodeMore()) break }
            var l = 0f; var r = 0f
            val i0 = pos.toInt()
            if (i0 < bufLen) {
                val fr = (pos - i0).toFloat()
                val i1 = if (i0 + 1 < bufLen) i0 + 1 else i0
                l = buf[i0 * 2] * (1 - fr) + buf[i1 * 2] * fr
                r = buf[i0 * 2 + 1] * (1 - fr) + buf[i1 * 2 + 1] * fr
            }
            pos += step
            val ol = (if (mix) out[i * 2].toFloat() else 0f) + l * gain
            val orr = (if (mix) out[i * 2 + 1].toFloat() else 0f) + r * gain
            out[i * 2] = ol.coerceIn(-32768f, 32767f).toInt().toShort()
            out[i * 2 + 1] = orr.coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }

    fun close() {
        runCatching { codec.stop(); codec.release() }
        ex.release()
        pfd.close()
    }
}

/** Arka plan müziği ayarları. */
data class MusicSpec(val uri: Uri, val volume: Float, val loop: Boolean, val fadeInUs: Long, val fadeOutUs: Long)

/**
 * Zaman çizelgesi boyunca orijinal sesleri ve müziği karıştırır; sırayla PCM üretir.
 * Hızı değişen parçalarda ses perdesi korunarak hızlandırılır / yavaşlatılır ([TimeStretch]);
 * 0,25×'ten yavaş ya da 4×'ten hızlı parçalar sessizdir.
 */
class AudioMixer(
    private val context: Context,
    private val segments: List<Seg>,
    private val music: MusicSpec?,
    private val originalVolume: Float,
    private val totalUs: Long,
    private val voices: List<VoiceSpec> = emptyList(),
    /** Konuşma / orijinal ses varken müziği kıs. */
    private val duck: Boolean = false,
    /** Orijinal seste uğultu ve gürültü azaltma. */
    private val denoise: Boolean = false,
) {
    class Seg(
        val uri: Uri, val audioTrack: Int?, val startUs: Long, val offsetUs: Long, val durationUs: Long, val speed: Double,
        val volume: Float = 1f, val fadeInUs: Long = 0, val fadeOutUs: Long = 0,
    )

    /** Seslendirme kaydı: zaman çizelgesinde [offsetUs]'de başlar. */
    class VoiceSpec(val uri: Uri, val offsetUs: Long, val durationUs: Long, val volume: Float)

    private val voiceReaders = HashMap<Int, PcmReader>()
    private val voiceTmp = ShortArray(2048 * 2)
    private var duckGain = 1f
    // Gürültü azaltma durumu: kanal başına yüksek geçiren süzgeç + gürültü tabanı izleme
    private val hp = Array(2) { FloatArray(4) }
    private var noiseFloor = 300f
    private var gateGain = 1f

    private var producedFrames = 0L
    private var segIdx = -1
    private var segReader: PcmReader? = null
    private var stretch: TimeStretch? = null
    private var musicReader: PcmReader? = null
    private val tmp = ShortArray(2048 * 2)

    companion object {
        /** Bu hızda orijinal ses duyulur mu (perde korunarak). */
        fun hasAudioAt(speed: Double) = speed in 0.25..4.0
    }

    val producedUs: Long get() = producedFrames * 1_000_000 / MIX_RATE

    /** Sıradaki n kareyi üretir. */
    fun render(n: Int, out: ShortArray) {
        java.util.Arrays.fill(out, 0, n * 2, 0)
        // Orijinal ses: kare kare parça sınırlarına göre parçalara böl.
        var i = 0
        while (i < n) {
            val tUs = (producedFrames + i) * 1_000_000 / MIX_RATE
            val s = segments.indexOfLast { tUs >= it.offsetUs }.coerceAtLeast(0)
            val seg = segments.getOrNull(s)
            val segEndFrame = if (seg != null) (seg.offsetUs + seg.durationUs) * MIX_RATE / 1_000_000 else Long.MAX_VALUE
            val chunk = minOf(n - i, (segEndFrame - (producedFrames + i)).coerceAtLeast(1).toInt())
            if (seg != null && seg.audioTrack != null && originalVolume > 0f && hasAudioAt(seg.speed)) {
                if (segIdx != s) {
                    segReader?.close()
                    val r = PcmReader(context, seg.uri, seg.audioTrack, seg.startUs + ((tUs - seg.offsetUs) * seg.speed).toLong())
                    segReader = r
                    stretch = if (seg.speed == 1.0) null else TimeStretch(seg.speed) { n, buf -> r.read(n, buf, 1f, false) }
                    segIdx = s
                }
                val st = stretch
                if (st != null) st.read(chunk, tmp, originalVolume) else segReader!!.read(chunk, tmp, originalVolume, false)
                if (denoise) denoiseBlock(tmp, chunk)
                // Parça seviyesi ve ses yükselme / alçalma
                val segFrame0 = (producedFrames + i) - seg.offsetUs * MIX_RATE / 1_000_000
                val durF = seg.durationUs * MIX_RATE / 1_000_000
                val inF = seg.fadeInUs * MIX_RATE / 1_000_000; val outF = seg.fadeOutUs * MIX_RATE / 1_000_000
                for (k in 0 until chunk) {
                    val f = segFrame0 + k
                    var g = seg.volume
                    if (inF > 0 && f < inF) g *= f.toFloat() / inF
                    if (outF > 0 && durF - f < outF) g *= ((durF - f).toFloat() / outF).coerceAtLeast(0f)
                    if (g != 1f) {
                        tmp[k * 2] = (tmp[k * 2] * g).coerceIn(-32768f, 32767f).toInt().toShort()
                        tmp[k * 2 + 1] = (tmp[k * 2 + 1] * g).coerceIn(-32768f, 32767f).toInt().toShort()
                    }
                }
                System.arraycopy(tmp, 0, out, i * 2, chunk * 2)
            } else if (segIdx != s) {
                segReader?.close(); segReader = null; stretch = null; segIdx = s
            }
            i += chunk
        }
        // Seslendirmeler
        val t0 = producedFrames * 1_000_000 / MIX_RATE
        val t1 = (producedFrames + n) * 1_000_000 / MIX_RATE
        voices.forEachIndexed { vi, v ->
            if (t1 <= v.offsetUs || t0 >= v.offsetUs + v.durationUs) {
                if (t0 >= v.offsetUs + v.durationUs) voiceReaders.remove(vi)?.close()
                return@forEachIndexed
            }
            val r = voiceReaders.getOrPut(vi) { PcmReader(context, v.uri, null, ((t0 - v.offsetUs).coerceAtLeast(0))) }
            // Bloğun içinde başlıyorsa baştaki kareleri atla
            val skip = ((v.offsetUs - t0).coerceAtLeast(0) * MIX_RATE / 1_000_000).toInt().coerceAtMost(n)
            val m = n - skip
            r.read(m, voiceTmp, v.volume, false)
            for (k in 0 until m) {
                out[(skip + k) * 2] = (out[(skip + k) * 2] + voiceTmp[k * 2]).coerceIn(-32768, 32767).toShort()
                out[(skip + k) * 2 + 1] = (out[(skip + k) * 2 + 1] + voiceTmp[k * 2 + 1]).coerceIn(-32768, 32767).toShort()
            }
        }
        // Kısma: orijinal + seslendirme seviyesine göre müzik kazancı (hızlı iniş, yavaş çıkış)
        if (duck) {
            var e = 0.0
            for (k in 0 until n) { val v = (out[k * 2] + out[k * 2 + 1]) * 0.5; e += v * v }
            val rms = kotlin.math.sqrt(e / n.coerceAtLeast(1)).toFloat()
            val target = if (rms > 900f) 0.3f else 1f
            val coef = if (target < duckGain) 0.35f else 0.04f
            duckGain += (target - duckGain) * coef
        }
        // Müzik
        if (music != null && music.volume > 0f) {
            if (musicReader == null) musicReader = PcmReader(context, music.uri)
            var done = 0
            while (done < n) {
                val r = musicReader!!
                val chunk = minOf(n - done, 1024)
                r.read(chunk, tmp, 1f, false)
                for (k in 0 until chunk) {
                    val f = producedFrames + done + k
                    val g = music.volume * envelope(f * 1_000_000 / MIX_RATE) * (if (duck) duckGain else 1f)
                    out[(done + k) * 2] = (out[(done + k) * 2] + tmp[k * 2] * g).coerceIn(-32768f, 32767f).toInt().toShort()
                    out[(done + k) * 2 + 1] = (out[(done + k) * 2 + 1] + tmp[k * 2 + 1] * g).coerceIn(-32768f, 32767f).toInt().toShort()
                }
                done += chunk
                if (r.eos && music.loop) { r.close(); musicReader = PcmReader(context, music.uri) }
            }
        }
        producedFrames += n
    }

    /**
     * Basit gürültü azaltma: 90 Hz yüksek geçiren (rüzgâr, uğultu) + aşağı yönlü genişletici
     * (sesin gürültü tabanına yakın olduğu yerlerde seviye düşer, konuşma etkilenmez).
     */
    private fun denoiseBlock(buf: ShortArray, n: Int) {
        // Biquad yüksek geçiren, 90 Hz, Q 0,707
        val w0 = 2 * Math.PI * 90 / MIX_RATE
        val alpha = kotlin.math.sin(w0) / (2 * 0.707)
        val cs = kotlin.math.cos(w0)
        val a0 = 1 + alpha
        val b0 = ((1 + cs) / 2 / a0).toFloat(); val b1 = (-(1 + cs) / a0).toFloat(); val b2 = b0
        val a1 = (-2 * cs / a0).toFloat(); val a2 = ((1 - alpha) / a0).toFloat()
        var e = 0.0
        for (k in 0 until n) for (ch in 0..1) {
            val st = hp[ch]
            val x = buf[k * 2 + ch].toFloat()
            val y = b0 * x + b1 * st[0] + b2 * st[1] - a1 * st[2] - a2 * st[3]
            st[1] = st[0]; st[0] = x; st[3] = st[2]; st[2] = y
            buf[k * 2 + ch] = y.coerceIn(-32768f, 32767f).toInt().toShort()
            e += y * y
        }
        val rms = kotlin.math.sqrt(e / (2 * n).coerceAtLeast(1)).toFloat()
        // Gürültü tabanı: yavaşça yükselir, hızla iner
        noiseFloor = if (rms < noiseFloor) noiseFloor * 0.9f + rms * 0.1f else noiseFloor * 1.0015f
        noiseFloor = noiseFloor.coerceIn(30f, 3000f)
        val ratio = rms / (noiseFloor * 2.5f)
        val target = (ratio * ratio).coerceIn(0.12f, 1f)
        val start = gateGain
        gateGain += (target - gateGain) * (if (target > gateGain) 0.6f else 0.15f)
        for (k in 0 until n) {
            val g = start + (gateGain - start) * k / n
            buf[k * 2] = (buf[k * 2] * g).toInt().toShort(); buf[k * 2 + 1] = (buf[k * 2 + 1] * g).toInt().toShort()
        }
    }

    private fun envelope(tUs: Long): Float {
        val m = music ?: return 1f
        var g = 1f
        if (m.fadeInUs > 0 && tUs < m.fadeInUs) g = minOf(g, tUs.toFloat() / m.fadeInUs)
        if (m.fadeOutUs > 0 && totalUs - tUs < m.fadeOutUs) g = minOf(g, (totalUs - tUs).toFloat() / m.fadeOutUs)
        return g.coerceIn(0f, 1f)
    }

    fun close() {
        voiceReaders.values.forEach { runCatching { it.close() } }; voiceReaders.clear()
        segReader?.close(); segReader = null
        musicReader?.close(); musicReader = null
    }
}

/** 48 kHz stereo AAC-LC kodlayıcı. */
class AacEncoder(bitrate: Int = 192_000) {
    val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private var framesIn = 0L
    var outputFormat: MediaFormat? = null
        private set

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, MIX_RATE, 2)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2048 * 4)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** PCM besle (n kare); giriş tamponu yoksa bekler. */
    fun feed(pcm: ShortArray, n: Int, onOutput: (ByteBuffer, MediaCodec.BufferInfo) -> Unit) {
        var off = 0
        while (off < n) {
            val ii = codec.dequeueInputBuffer(10_000)
            if (ii < 0) { drain(false, onOutput); continue }
            val ib = codec.getInputBuffer(ii)!!.order(ByteOrder.LITTLE_ENDIAN)
            ib.clear()
            val cap = ib.remaining() / 4
            val m = minOf(cap, n - off)
            ib.asShortBuffer().put(pcm, off * 2, m * 2)
            val pts = framesIn * 1_000_000 / MIX_RATE
            codec.queueInputBuffer(ii, 0, m * 4, pts, 0)
            framesIn += m
            off += m
            drain(false, onOutput)
        }
    }

    fun finish(onOutput: (ByteBuffer, MediaCodec.BufferInfo) -> Unit) {
        while (true) {
            val ii = codec.dequeueInputBuffer(10_000)
            if (ii >= 0) { codec.queueInputBuffer(ii, 0, 0, framesIn * 1_000_000 / MIX_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
            drain(false, onOutput)
        }
        drain(true, onOutput)
    }

    fun drain(eos: Boolean, onOutput: (ByteBuffer, MediaCodec.BufferInfo) -> Unit) {
        var tries = 0
        while (true) {
            val oi = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                oi == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos || ++tries > 300) return
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                oi >= 0 -> {
                    val ob = codec.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        onOutput(ob, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun release() { runCatching { codec.stop(); codec.release() } }
}
