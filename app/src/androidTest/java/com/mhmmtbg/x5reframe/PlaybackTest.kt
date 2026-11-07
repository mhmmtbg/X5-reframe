package com.mhmmtbg.x5reframe

import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mhmmtbg.x5reframe.insv.ClipInfo
import com.mhmmtbg.x5reframe.media.Player
import com.mhmmtbg.x5reframe.model.ViewParams
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Önizleme oynatıcısının kare zamanlaması. Ekran yerine ImageReader yüzeyine çizilir.
 * Emülatör yazılım çözücü kullandığından mutlak hız telefonu yansıtmaz; burada
 * takılma (uzun boşluk) ve kare atlama mantığı doğrulanır.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun lrvPreviewPlaysSmoothly() {
        val clipFile = File("/data/local/tmp/clip.insv")
        val lrvFile = File("/data/local/tmp/clip.lrv")
        assumeTrue("örnek klip yok", clipFile.exists() && lrvFile.exists())
        val clip = ClipInfo.load(ctx, Uri.fromFile(clipFile), Uri.fromFile(lrvFile))

        val rt = HandlerThread("reader").apply { start() }
        val reader = ImageReader.newInstance(960, 540, PixelFormat.RGBA_8888, 4)
        var images = 0
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close(); images++ }, Handler(rt.looper))

        var errors = ""
        val player = Player(ctx, clip, object : Player.Listener {
            override fun onPosition(us: Long) {}
            override fun onPlayingChanged(playing: Boolean) {}
            override fun onError(message: String) { errors += message }
        })
        player.sceneProvider = { listOf(ViewParams(yaw = 180.0, fov = 90.0)) }
        player.setMuted(true)
        player.attachSurface(reader.surface, 960, 540)
        Thread.sleep(1500)
        player.play()
        Thread.sleep(5000)
        player.pause()
        val s = player.stats.snapshot()
        android.util.Log.i("PlaybackTest", "lrv playback: $s images=$images errors=$errors")
        File(ctx.getExternalFilesDir(null), "playback.txt").writeText("lrv: $s images=$images errors=$errors\n")
        player.release()
        Thread.sleep(300)
        reader.close(); rt.quitSafely()

        assertTrue("oynatıcı hatası: $errors", errors.isEmpty())
        assertTrue("kare gösterilmedi", s.shown > 30)
        // 5 sn'de bir kez bile 400 ms'den uzun donma olmamalı
        assertTrue("uzun donma: ${s.maxMs} ms", s.maxMs < 400)
    }
}
