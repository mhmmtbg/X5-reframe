package com.mhmmtbg.x5reframe

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Arayüz ekran görüntüleri: ana ekran, oynatıcı sayfası, düzenleyici. Görüntüler İndirilenler/x5test'e
 * yazılır (CI çeker). Ayrıca ekranların çökmeden açıldığını ve boş olmadığını doğrular.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    @Test
    fun screens() {
        for (mode in listOf(com.mhmmtbg.x5reframe.ui.theme.ThemeMode.DARK, com.mhmmtbg.x5reframe.ui.theme.ThemeMode.LIGHT)) {
            inst.runOnMainSync { com.mhmmtbg.x5reframe.ui.theme.ThemeMode.set(ctx, mode) }
            val tag = mode.name.lowercase()
            for ((tab, name) in listOf(0 to "home", 1 to "album", 2 to "settings")) {
                ctx.getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE).edit().putInt(MainActivity.KEY_TAB, tab).commit()
                ActivityScenario.launch(MainActivity::class.java).use {
                    Thread.sleep(2000)
                    shot("ui_${tag}_$name")
                }
            }
            ActivityScenario.launch(com.mhmmtbg.x5reframe.camera.CameraActivity::class.java).use {
                Thread.sleep(2500)
                shot("ui_${tag}_camera")
            }
            val clip = File("/data/local/tmp/clip.insv")
            val lrv = File("/data/local/tmp/clip.lrv")
            if (!clip.exists()) continue
            val base = Intent(ctx, EditorActivity::class.java).setData(Uri.fromFile(clip))
                .putExtra(EditorActivity.EXTRA_LRV, if (lrv.exists()) Uri.fromFile(lrv).toString() else null)
            if (mode == com.mhmmtbg.x5reframe.ui.theme.ThemeMode.DARK) {
                ActivityScenario.launch<EditorActivity>(Intent(base).putExtra(EditorActivity.EXTRA_VIEWER, true)).use {
                    Thread.sleep(6000)
                    shot("ui_viewer")
                }
            }
            ActivityScenario.launch<EditorActivity>(Intent(base)).use {
                Thread.sleep(6000)
                val b = shot("ui_${tag}_editor")
                assertTrue("düzenleyici boş görünüyor", variance(b) > 50.0)
            }
        }
        inst.runOnMainSync { com.mhmmtbg.x5reframe.ui.theme.ThemeMode.set(ctx, com.mhmmtbg.x5reframe.ui.theme.ThemeMode.SYSTEM) }
        assumeTrue(true)
    }

    private fun shot(name: String): Bitmap {
        val bmp = inst.uiAutomation.takeScreenshot()
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.png")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/x5test")
        }
        runCatching {
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        android.util.Log.i("ScreenshotTest", "$name ${bmp.width}x${bmp.height} varyans=${"%.0f".format(variance(bmp))}")
        return bmp
    }

    /** Görüntü tek renk mi? (çökme / siyah ekran yakalamak için) */
    private fun variance(b: Bitmap): Double {
        var s = 0.0; var ss = 0.0; var n = 0
        val step = 16
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
            val c = b.getPixel(x, y)
            val l = ((c shr 16) and 0xff) * 0.3 + ((c shr 8) and 0xff) * 0.59 + (c and 0xff) * 0.11
            s += l; ss += l * l; n++
        }
        val m = s / n
        return ss / n - m * m
    }
}
