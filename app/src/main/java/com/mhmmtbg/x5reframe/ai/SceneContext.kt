package com.mhmmtbg.x5reframe.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.mhmmtbg.x5reframe.auto.AutoDirector
import com.mhmmtbg.x5reframe.auto.AutoEditor
import com.mhmmtbg.x5reframe.model.ViewParams
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Yapay zekâya giden sahne bağlamı. Video gönderilmez; telefonda çıkarılan özet + küçük kareler:
 *  - Her klip için birkaç zaman noktasında 4 yönden (yaw −90, 0, 90, 180) kareler, üzerine zaman ve yön yazılı
 *    "temas sayfaları" (contact sheet) halinde JPEG.
 *  - Saniye başına yüzler (yaw/pitch/boyut), ses ve hareket düzeyi, çekimi yapan kişinin yönü.
 */
class SceneContext(val json: JSONObject, val images: List<ByteArray>) {

    class ClipScan(val index: Int, val name: String, val durationUs: Long, val samples: List<AutoEditor.Sample>,
                   val frames: List<Pair<Long, List<Bitmap>>>)

    companion object {
        val YAWS = listOf(-90.0, 0.0, 90.0, 180.0)
        const val TILE_W = 288
        const val TILE_H = 216
        /** Bir sayfada kaç zaman satırı */
        const val ROWS = 5

        /** Klip başına kaç zaman noktası: toplam görüntü bütçesine göre. */
        fun timesFor(inUs: Long, outUs: Long, count: Int): List<Long> {
            val n = count.coerceAtLeast(2)
            val d = (outUs - inUs).coerceAtLeast(1)
            return (0 until n).map { i -> inUs + d * (2 * i + 1) / (2 * n) }
        }

        fun views(): List<ViewParams> = YAWS.map { ViewParams(yaw = it, pitch = -5.0, fov = 100.0) }

        fun build(scans: List<ClipScan>, project: JSONObject?, hasMusic: Boolean): SceneContext {
            val clips = JSONArray()
            for (c in scans) {
                val holder = AutoDirector.holderTrack(c.samples)
                val holderYaw = holder?.let { h ->
                    val sx = h.sumOf { kotlin.math.sin(Math.toRadians(it)) }; val cx = h.sumOf { kotlin.math.cos(Math.toRadians(it)) }
                    Math.toDegrees(kotlin.math.atan2(sx, cx)).roundToInt()
                }
                val maxA = c.samples.maxOfOrNull { it.audio }?.coerceAtLeast(1e-6f) ?: 1f
                val maxM = c.samples.maxOfOrNull { it.motion }?.coerceAtLeast(1e-6f) ?: 1f
                clips.put(JSONObject().apply {
                    put("index", c.index)
                    put("name", c.name)
                    put("duration_s", Math.round(c.durationUs / 1e5) / 10.0)
                    put("camera_operator_yaw", holderYaw ?: JSONObject.NULL)
                    put("timeline", JSONArray().apply {
                        for (s in c.samples) put(JSONObject().apply {
                            put("t", Math.round(s.tUs / 1e5) / 10.0)
                            put("faces", JSONArray().apply {
                                s.faces.sortedByDescending { it.size }.take(4).forEach { f ->
                                    put(JSONArray().put(f.yaw.roundToInt()).put(f.pitch.roundToInt()).put(Math.round(f.size * 100) / 100.0))
                                }
                            })
                            put("audio", Math.round(s.audio / maxA * 100) / 100.0)
                            put("motion", Math.round(s.motion / maxM * 100) / 100.0)
                        })
                    })
                    put("frame_times", JSONArray().apply { c.frames.forEach { put(Math.round(it.first / 1e5) / 10.0) } })
                })
            }
            val json = JSONObject().apply {
                put("clips", clips)
                put("has_music", hasMusic)
                project?.let { put("current_edit", it) }
            }
            val images = ArrayList<ByteArray>()
            for (c in scans) {
                c.frames.chunked(ROWS).forEachIndexed { page, rows -> images += sheet(c, rows, page) }
            }
            return SceneContext(json, images)
        }

        /** 4 sütun (yön) x N satır (zaman) temas sayfası. */
        private fun sheet(c: ClipScan, rows: List<Pair<Long, List<Bitmap>>>, page: Int): ByteArray {
            val head = 34
            val label = 22
            val w = TILE_W * YAWS.size
            val h = head + rows.size * (TILE_H + label)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            cv.drawColor(Color.BLACK)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f }
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 201, 77); textSize = 17f }
            cv.drawText("clip ${c.index} \"${c.name}\" — sayfa ${page + 1} — sütunlar: yaw ${YAWS.joinToString(", ") { it.toInt().toString() }}", 8f, 24f, p)
            for ((r, row) in rows.withIndex()) {
                val y0 = head + r * (TILE_H + label)
                cv.drawText("t = ${"%.1f".format(row.first / 1e6)} sn", 8f, y0 + 17f, small)
                for ((k, tile) in row.second.withIndex()) {
                    cv.drawBitmap(tile, null, android.graphics.Rect(k * TILE_W, y0 + label, (k + 1) * TILE_W, y0 + label + TILE_H), null)
                    cv.drawText("yaw ${YAWS[k].toInt()}", k * TILE_W + 6f, y0 + label + 20f, small)
                }
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 78, out)
            bmp.recycle()
            return out.toByteArray()
        }

        /** Çekim kontrolü: her çekimin ortasından, planlanan görünümle alınmış kareler. */
        fun reviewSheet(tiles: List<Pair<String, Bitmap>>): ByteArray {
            val cols = 3
            val tw = 320; val th = 240; val label = 24
            val rows = max(1, (tiles.size + cols - 1) / cols)
            val bmp = Bitmap.createBitmap(cols * tw, rows * (th + label), Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            cv.drawColor(Color.BLACK)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 201, 77); textSize = 18f }
            for ((i, t) in tiles.withIndex()) {
                val x = (i % cols) * tw; val y = (i / cols) * (th + label)
                cv.drawText(t.first, x + 6f, y + 18f, p)
                cv.drawBitmap(t.second, null, android.graphics.Rect(x, y + label, x + tw, y + label + th), null)
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 78, out)
            bmp.recycle()
            return out.toByteArray()
        }
    }
}
