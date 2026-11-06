package com.mhmmtbg.x5reframe.project

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.mhmmtbg.x5reframe.model.Aspect
import com.mhmmtbg.x5reframe.model.EditState
import com.mhmmtbg.x5reframe.model.Keyframe
import com.mhmmtbg.x5reframe.model.Quality
import com.mhmmtbg.x5reframe.model.ViewParams
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Transition(val label: String) { CUT("Kesme"), FADE("Siyaha geçiş") }

/** Projede sıralı bir klip parçası: kaynak dosya + o parçanın düzenlemesi. */
data class Segment(
    val id: String,
    val clipUri: Uri,
    val clipName: String,
    val durationUs: Long,
    val edit: EditState,
    val lrvUri: Uri? = null,
    /** 0,25..4 */
    val speed: Double = 1.0,
    /** Bu parçadan sonrakine geçiş. */
    val transitionOut: Transition = Transition.CUT,
) {
    val outputDurationUs: Long get() = ((edit.outUs - edit.inUs) / speed).toLong()
}

/** Projenin ses ayarları: orijinal ses seviyesi ve arka plan müziği. */
data class ProjectAudio(
    val originalVolume: Float = 1f,
    val musicUri: Uri? = null,
    val musicName: String = "",
    val musicVolume: Float = 0.6f,
    val loop: Boolean = true,
    val fade: Boolean = true,
) {
    /** Ses yeniden kodlanmalı mı (müzik var ya da orijinal seviye değişmiş). */
    val needsMix: Boolean get() = musicUri != null || originalVolume != 1f
}

/** Kaydedilmiş bir düzenleme projesi: sıralı parçalar. */
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val segments: List<Segment>,
    val audio: ProjectAudio = ProjectAudio(),
) {
    val totalDurationUs: Long get() = segments.sumOf { it.outputDurationUs }
    val keyframeCount: Int get() = segments.sumOf { s -> s.edit.keyframes.size + s.edit.extraPanes.sumOf { it.keyframes.size } }
}

/** Projeleri uygulamanın kendi klasöründe JSON olarak saklar (internet, hesap yok). */
class ProjectStore(context: Context) {
    private val dir = File(context.filesDir, "projects").apply { mkdirs() }

    fun list(): List<Project> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    fun get(id: String): Project? = File(dir, "$id.json").takeIf { it.exists() }
        ?.let { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }

    fun create(clipUri: Uri, clipName: String, durationUs: Long, lrvUri: Uri? = null): Project {
        val now = System.currentTimeMillis()
        val p = Project(
            UUID.randomUUID().toString(), clipName.substringBeforeLast('.'), now, now,
            listOf(newSegment(clipUri, clipName, durationUs, lrvUri)),
        )
        save(p)
        return p
    }

    fun newSegment(clipUri: Uri, clipName: String, durationUs: Long, lrvUri: Uri?, edit: EditState? = null) =
        Segment(UUID.randomUUID().toString(), clipUri, clipName, durationUs, edit ?: EditState(outUs = durationUs), lrvUri)

    @Synchronized
    fun save(p: Project) {
        val tmp = File(dir, "${p.id}.json.tmp")
        tmp.writeText(toJson(p).toString())
        tmp.renameTo(File(dir, "${p.id}.json"))
    }

    fun rename(id: String, name: String) { get(id)?.let { save(it.copy(name = name, updatedAt = System.currentTimeMillis())) } }

    fun duplicate(id: String): Project? {
        val p = get(id) ?: return null
        val now = System.currentTimeMillis()
        val copy = p.copy(id = UUID.randomUUID().toString(), name = p.name + " (kopya)", createdAt = now, updatedAt = now)
        save(copy)
        thumbnail(id).takeIf { it.exists() }?.copyTo(thumbnail(copy.id), overwrite = true)
        return copy
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        thumbnail(id).delete()
    }

    fun thumbnail(id: String) = File(dir, "$id.jpg")

    fun saveThumbnail(id: String, bmp: Bitmap) {
        thumbnail(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
    }

    companion object {
        fun toJson(p: Project) = JSONObject().apply {
            put("version", 2)
            put("id", p.id); put("name", p.name)
            put("createdAt", p.createdAt); put("updatedAt", p.updatedAt)
            put("segments", JSONArray().apply { p.segments.forEach { put(segmentToJson(it)) } })
            put("audio", JSONObject().apply {
                put("originalVolume", p.audio.originalVolume.toDouble())
                p.audio.musicUri?.let { put("musicUri", it.toString()) }
                put("musicName", p.audio.musicName); put("musicVolume", p.audio.musicVolume.toDouble())
                put("loop", p.audio.loop); put("fade", p.audio.fade)
            })
        }

        private fun segmentToJson(s: Segment) = JSONObject().apply {
            put("id", s.id)
            put("clipUri", s.clipUri.toString()); put("clipName", s.clipName)
            put("durationUs", s.durationUs)
            put("edit", editToJson(s.edit))
            s.lrvUri?.let { put("lrvUri", it.toString()) }
            put("speed", s.speed); put("transitionOut", s.transitionOut.name)
        }

        private fun segmentFromJson(o: JSONObject) = Segment(
            o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            Uri.parse(o.getString("clipUri")), o.getString("clipName"), o.getLong("durationUs"),
            editFromJson(o.getJSONObject("edit")),
            o.optString("lrvUri").takeIf { it.isNotEmpty() }?.let(Uri::parse),
            o.optDouble("speed", 1.0),
            runCatching { Transition.valueOf(o.optString("transitionOut")) }.getOrDefault(Transition.CUT),
        )

        fun fromJson(o: JSONObject): Project {
            // Sürüm 1: tek klip alanları doğrudan projede.
            val segs = o.optJSONArray("segments")?.let { arr -> (0 until arr.length()).map { segmentFromJson(arr.getJSONObject(it)) } }
                ?: listOf(segmentFromJson(o))
            val a = o.optJSONObject("audio")
            val audio = if (a == null) ProjectAudio() else ProjectAudio(
                a.optDouble("originalVolume", 1.0).toFloat(),
                a.optString("musicUri").takeIf { it.isNotEmpty() }?.let(Uri::parse),
                a.optString("musicName"), a.optDouble("musicVolume", 0.6).toFloat(),
                a.optBoolean("loop", true), a.optBoolean("fade", true),
            )
            return Project(o.getString("id"), o.getString("name"), o.getLong("createdAt"), o.getLong("updatedAt"), segs, audio)
        }

        fun editToJson(e: EditState) = JSONObject().apply {
            put("inUs", e.inUs); put("outUs", e.outUs)
            put("baseView", viewToJson(e.baseView))
            put("keyframes", JSONArray().apply {
                e.keyframes.forEach { k -> put(JSONObject().put("t", k.timeUs).put("view", viewToJson(k.view))) }
            })
            put("aspect", e.aspect.name); put("quality", e.quality.name)
            put("hevc", e.hevc); put("includeAudio", e.includeAudio)
            put("stabMode", e.stabMode.name); put("stabStrength", e.stabStrength)
            put("stitchDistance", e.stitchDistance)
            put("layout", e.layout.name)
            put("layers", JSONArray().apply {
                e.layers.forEach { l ->
                    put(JSONObject().put("id", l.id).put("kind", l.kind.name).put("content", l.content)
                        .put("x", l.x.toDouble()).put("y", l.y.toDouble()).put("scale", l.scale.toDouble()).put("opacity", l.opacity.toDouble())
                        .put("startUs", l.startUs).put("endUs", l.endUs).put("visible", l.visible).put("style", l.style.name))
                }
            })
            put("extraPanes", JSONArray().apply {
                e.extraPanes.forEach { p ->
                    put(JSONObject().put("baseView", viewToJson(p.baseView)).put("keyframes", JSONArray().apply {
                        p.keyframes.forEach { k -> put(JSONObject().put("t", k.timeUs).put("view", viewToJson(k.view))) }
                    }))
                }
            })
        }

        fun editFromJson(o: JSONObject): EditState {
            val kfs = o.optJSONArray("keyframes") ?: JSONArray()
            return EditState(
                inUs = o.optLong("inUs"), outUs = o.optLong("outUs"),
                keyframes = (0 until kfs.length()).map { i ->
                    val k = kfs.getJSONObject(i); Keyframe(k.getLong("t"), viewFromJson(k.getJSONObject("view")))
                },
                baseView = o.optJSONObject("baseView")?.let(::viewFromJson) ?: ViewParams(),
                aspect = runCatching { Aspect.valueOf(o.optString("aspect")) }.getOrDefault(Aspect.LANDSCAPE),
                quality = runCatching { Quality.valueOf(o.optString("quality")) }.getOrDefault(Quality.FHD),
                hevc = o.optBoolean("hevc"), includeAudio = o.optBoolean("includeAudio", true),
                stabMode = runCatching { com.mhmmtbg.x5reframe.stab.StabMode.valueOf(o.optString("stabMode")) }
                    .getOrDefault(com.mhmmtbg.x5reframe.stab.StabMode.OFF),
                stabStrength = o.optDouble("stabStrength", 0.5),
                stitchDistance = o.optDouble("stitchDistance", 0.0),
                layers = (o.optJSONArray("layers") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val l = arr.getJSONObject(i)
                        runCatching {
                            com.mhmmtbg.x5reframe.model.Layer(
                                l.getString("id"), com.mhmmtbg.x5reframe.model.Layer.Kind.valueOf(l.getString("kind")), l.getString("content"),
                                l.optDouble("x", 0.5).toFloat(), l.optDouble("y", 0.85).toFloat(), l.optDouble("scale", 0.5).toFloat(),
                                l.optDouble("opacity", 1.0).toFloat(), l.optLong("startUs"), l.optLong("endUs", -1), l.optBoolean("visible", true),
                                runCatching { com.mhmmtbg.x5reframe.model.Layer.TextStyle.valueOf(l.optString("style")) }
                                    .getOrDefault(com.mhmmtbg.x5reframe.model.Layer.TextStyle.WHITE),
                            )
                        }.getOrNull()
                    }
                },
                layout = runCatching { com.mhmmtbg.x5reframe.model.Layout.valueOf(o.optString("layout")) }
                    .getOrDefault(com.mhmmtbg.x5reframe.model.Layout.SINGLE),
                extraPanes = (o.optJSONArray("extraPanes") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).map { i ->
                        val po = arr.getJSONObject(i)
                        val ks = po.optJSONArray("keyframes") ?: JSONArray()
                        com.mhmmtbg.x5reframe.model.Pane(
                            (0 until ks.length()).map { j -> val k = ks.getJSONObject(j); Keyframe(k.getLong("t"), viewFromJson(k.getJSONObject("view"))) },
                            po.optJSONObject("baseView")?.let(::viewFromJson) ?: ViewParams(),
                        )
                    }
                },
            )
        }

        private fun viewToJson(v: ViewParams) = JSONObject()
            .put("yaw", v.yaw).put("pitch", v.pitch).put("roll", v.roll).put("fov", v.fov).put("projection", v.projection.name)

        private fun viewFromJson(o: JSONObject) = ViewParams(
            o.optDouble("yaw", 0.0), o.optDouble("pitch", 0.0), o.optDouble("roll", 0.0), o.optDouble("fov", 90.0),
            runCatching { com.mhmmtbg.x5reframe.model.Projection.valueOf(o.optString("projection")) }.getOrElse {
                if (o.optBoolean("wide")) com.mhmmtbg.x5reframe.model.Projection.WIDE else com.mhmmtbg.x5reframe.model.Projection.RECTILINEAR
            },
        )
    }
}
