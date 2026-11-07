package com.mhmmtbg.x5reframe.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.FileDescriptor

/**
 * Önizleme sesi: AAC izini çözüp AudioTrack'e yazar ve oynatıcıya ana saat olur.
 * Görüntü bu saate göre çizilir; böylece ses ile görüntü kaymaz.
 */
class AudioPreview(private val fd: FileDescriptor, private val track: Int) {

    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var startUs = 0L
    @Volatile private var sampleRate = 48_000
    @Volatile var muted = false
        set(v) { field = v; applyVolume() }
    @Volatile var volume = 1f
        set(v) { field = v; applyVolume() }

    private fun applyVolume() { audioTrack?.setVolume(if (muted) 0f else volume.coerceIn(0f, 1f)) }

    private val ts = android.media.AudioTimestamp()

    /**
     * Hoparlörden o an çıkan sesin konumu (µs); ses henüz başlamadıysa null.
     * AudioTrack.getTimestamp ile donanım zaman damgası alınıp şimdiye ara değerlenir;
     * playbackHeadPosition birçok cihazda 20–40 ms'lik sıçramalarla ilerlediği için kullanılmaz.
     */
    fun positionUs(): Long? {
        val at = audioTrack ?: return null
        if (at.playState != AudioTrack.PLAYSTATE_PLAYING) return null
        if (!at.getTimestamp(ts) || ts.framePosition <= 0) return null
        val nowNs = System.nanoTime()
        val frames = ts.framePosition + (nowNs - ts.nanoTime) * sampleRate / 1_000_000_000L
        return startUs + frames * 1_000_000 / sampleRate
    }

    fun start(fromUs: Long) {
        stop()
        startUs = fromUs
        running = true
        thread = Thread({ runDecode(fromUs) }, "audio-preview").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        audioTrack?.let { runCatching { it.pause(); it.flush(); it.release() } }
        audioTrack = null
    }

    private fun runDecode(fromUs: Long) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(fd)
            ex.selectTrack(track)
            ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val fmt = ex.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()

            sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            val at = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            at.setVolume(if (muted) 0f else volume.coerceIn(0f, 1f))
            audioTrack = at
            at.play()

            val info = MediaCodec.BufferInfo()
            var inEos = false
            var pcm = ByteArray(16 * 1024)
            while (running) {
                if (!inEos) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(oi)!!
                        var offset = info.offset
                        var size = info.size
                        // Başlangıçtan önceki örnekleri at, ilk tampon tam hedef konumdan başlasın.
                        if (info.presentationTimeUs < fromUs) {
                            val skipFrames = (fromUs - info.presentationTimeUs) * sampleRate / 1_000_000
                            val skipBytes = (skipFrames * channels * 2).toInt().coerceAtMost(size)
                            offset += skipBytes; size -= skipBytes
                        }
                        if (size > 0) {
                            if (pcm.size < size) pcm = ByteArray(size)
                            buf.position(offset); buf.get(pcm, 0, size)
                            val out = if (channels > 2) downmix(pcm.copyOf(size), channels) else pcm
                            val outSize = if (channels > 2) out.size else size
                            var w = 0
                            while (w < outSize && running) {
                                val r = at.write(out, w, outSize - w)
                                if (r <= 0) break
                                w += r
                            }
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            // Kuyruktaki son örneklerin çalınmasını bekle.
            val end = SystemClock.elapsedRealtime() + 1000
            while (running && SystemClock.elapsedRealtime() < end) Thread.sleep(20)
        } catch (_: Throwable) {
            // Ses sorunu görüntüyü durdurmasın.
        } finally {
            runCatching { codec?.stop(); codec?.release() }
            ex.release()
        }
    }

    /** Çok kanallı sesi ilk iki kanala indir. */
    private fun downmix(pcm: ByteArray, ch: Int): ByteArray {
        val frames = pcm.size / (2 * ch)
        val out = ByteArray(frames * 4)
        for (f in 0 until frames) {
            System.arraycopy(pcm, f * ch * 2, out, f * 4, 4)
        }
        return out
    }
}
