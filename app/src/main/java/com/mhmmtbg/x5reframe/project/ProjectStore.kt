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

/** Kaydedilmiş bir düzenleme projesi. */
data class Project(
    val id: String,
    val name: String,
    val clipUri: Uri,
    val clipName: String,
    val durationUs: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val edit: EditState,
    val lrvUri: Uri? = null,
)

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
            UUID.randomUUID().toString(), clipName.substringBeforeLast('.'), clipUri, clipName, durationUs, now, now,
            EditState(outUs = durationUs), lrvUri,
        )
        save(p)
        return p
    }

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
            put("version", 1)
            put("id", p.id); put("name", p.name)
            put("clipUri", p.clipUri.toString()); put("clipName", p.clipName)
            put("durationUs", p.durationUs)
            put("createdAt", p.createdAt); put("updatedAt", p.updatedAt)
            put("edit", editToJson(p.edit))
            p.lrvUri?.let { put("lrvUri", it.toString()) }
        }

        fun fromJson(o: JSONObject) = Project(
            o.getString("id"), o.getString("name"), Uri.parse(o.getString("clipUri")), o.getString("clipName"),
            o.getLong("durationUs"), o.getLong("createdAt"), o.getLong("updatedAt"), editFromJson(o.getJSONObject("edit")),
            o.optString("lrvUri").takeIf { it.isNotEmpty() }?.let(Uri::parse),
        )

        fun editToJson(e: EditState) = JSONObject().apply {
            put("inUs", e.inUs); put("outUs", e.outUs)
            put("baseView", viewToJson(e.baseView))
            put("keyframes", JSONArray().apply {
                e.keyframes.forEach { k -> put(JSONObject().put("t", k.timeUs).put("view", viewToJson(k.view))) }
            })
            put("aspect", e.aspect.name); put("quality", e.quality.name)
            put("hevc", e.hevc); put("includeAudio", e.includeAudio)
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
            )
        }

        private fun viewToJson(v: ViewParams) = JSONObject()
            .put("yaw", v.yaw).put("pitch", v.pitch).put("roll", v.roll).put("fov", v.fov).put("wide", v.wide)

        private fun viewFromJson(o: JSONObject) = ViewParams(
            o.optDouble("yaw", 0.0), o.optDouble("pitch", 0.0), o.optDouble("roll", 0.0), o.optDouble("fov", 90.0), o.optBoolean("wide"),
        )
    }
}
