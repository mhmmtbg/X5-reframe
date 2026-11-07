package com.mhmmtbg.x5reframe.ai

import com.mhmmtbg.x5reframe.model.ColorGrade
import com.mhmmtbg.x5reframe.model.Curve
import com.mhmmtbg.x5reframe.model.EditState
import com.mhmmtbg.x5reframe.model.Keyframe
import com.mhmmtbg.x5reframe.model.Layer
import com.mhmmtbg.x5reframe.model.Projection
import com.mhmmtbg.x5reframe.model.ViewParams
import com.mhmmtbg.x5reframe.stab.StabMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Yapay zekânın döndürdüğü kurgu planı. Tüm sağlayıcılar (Claude, Gemini, ChatGPT) aynı JSON şemasını kullanır.
 * Zamanlar saniye cinsinden ve kaynak klibin kendi zamanıdır. Açılar derece; yaw 0 = kameranın ön yönü
 * (sabitlenmiş, ufku düz çerçevede), pozitif yaw sağa, pozitif pitch yukarı.
 */
data class EditPlan(
    val explanation: String,
    val shots: List<Shot>,
    val aspect: String? = null,
    val color: ColorSpec? = null,
    val originalVolume: Double? = null,
    val musicVolume: Double? = null,
) {
    data class Shot(
        val clip: Int,
        val start: Double,
        val end: Double,
        val speed: Double = 1.0,
        val transition: String = "cut",
        val stabilization: String? = null,
        val keyframes: List<Key> = emptyList(),
        val text: String? = null,
    )

    data class Key(
        val time: Double,
        val yaw: Double,
        val pitch: Double,
        val fov: Double,
        val projection: String = "normal",
        val curve: String = "smooth",
        val roll: Double = 0.0,
    )

    data class ColorSpec(val filter: String? = null, val strength: Double? = null, val adjust: Map<String, Double> = emptyMap())

    companion object {
        /** Sağlayıcıların yapılandırılmış çıktısı için JSON şeması. */
        fun schema(): JSONObject = JSONObject(SCHEMA)

        fun parse(o: JSONObject): EditPlan {
            val shots = o.optJSONArray("shots") ?: JSONArray()
            return EditPlan(
                explanation = o.optString("explanation", ""),
                shots = (0 until shots.length()).mapNotNull { i -> shots.optJSONObject(i)?.let(::shot) },
                aspect = o.optString("aspect").ifBlank { null },
                color = o.optJSONObject("color")?.let { c ->
                    val adj = HashMap<String, Double>()
                    c.optJSONObject("adjust")?.let { a -> a.keys().forEach { k -> if (!a.isNull(k)) adj[k] = a.optDouble(k) } }
                    ColorSpec(c.optString("filter").ifBlank { null }, c.optDouble("strength").takeIf { !it.isNaN() }, adj)
                },
                originalVolume = o.optDouble("original_volume").takeIf { !it.isNaN() },
                musicVolume = o.optDouble("music_volume").takeIf { !it.isNaN() },
            )
        }

        private fun shot(s: JSONObject): Shot? {
            if (!s.has("start") || !s.has("end")) return null
            val keys = s.optJSONArray("keyframes") ?: JSONArray()
            return Shot(
                clip = s.optInt("clip", 0),
                start = s.optDouble("start"),
                end = s.optDouble("end"),
                speed = s.optDouble("speed", 1.0).takeIf { !it.isNaN() } ?: 1.0,
                transition = s.optString("transition", "cut"),
                stabilization = s.optString("stabilization").ifBlank { null },
                keyframes = (0 until keys.length()).mapNotNull { i ->
                    keys.optJSONObject(i)?.let { k ->
                        Key(
                            time = k.optDouble("time"), yaw = k.optDouble("yaw", 0.0), pitch = k.optDouble("pitch", 0.0),
                            fov = k.optDouble("fov", 100.0), projection = k.optString("projection", "normal"),
                            curve = k.optString("curve", "smooth"), roll = k.optDouble("roll", 0.0),
                        ).takeIf { !it.time.isNaN() }
                    }
                },
                text = s.optString("text").ifBlank { null }?.takeIf { it != "null" },
            )
        }

        /** Model JSON'u metin içinde döndürdüyse ilk { … } bloğunu çıkar. */
        fun extractJson(text: String): JSONObject {
            val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            runCatching { return JSONObject(t) }
            val a = t.indexOf('{'); val b = t.lastIndexOf('}')
            require(a >= 0 && b > a) { "yanıtta JSON yok" }
            return JSONObject(t.substring(a, b + 1))
        }

        private const val SCHEMA = """{
  "type": "object",
  "properties": {
    "explanation": {"type": "string", "description": "Kullanıcıya Türkçe, 1-3 cümlelik açıklama: ne yaptın, neden."},
    "shots": {
      "type": "array",
      "description": "Kurgudaki çekimler, oynatma sırasıyla.",
      "items": {
        "type": "object",
        "properties": {
          "clip": {"type": "integer", "description": "Kaynak klip numarası (clips[].index)."},
          "start": {"type": "number", "description": "Kaynak klipte başlangıç (sn)."},
          "end": {"type": "number", "description": "Kaynak klipte bitiş (sn)."},
          "speed": {"type": "number", "description": "0.25-4, varsayılan 1."},
          "transition": {"type": "string", "enum": ["cut", "fade"], "description": "Bu çekimden sonrakine geçiş."},
          "stabilization": {"type": "string", "enum": ["flowstate", "lock", "smooth", "off"]},
          "keyframes": {
            "type": "array",
            "description": "Kamera yönü. Zaman kaynak klip zamanıdır (sn) ve [start,end] içinde olmalı. En az 1.",
            "items": {
              "type": "object",
              "properties": {
                "time": {"type": "number"},
                "yaw": {"type": "number", "description": "-180..180, 0 = kameranın önü, + sağ."},
                "pitch": {"type": "number", "description": "-90..90, + yukarı."},
                "fov": {"type": "number", "description": "Görüş açısı derece."},
                "projection": {"type": "string", "enum": ["normal", "wide", "superwide", "fisheye", "planet", "tunnel", "360"]},
                "curve": {"type": "string", "enum": ["smooth", "linear", "ease_in", "ease_out", "delayed", "fast", "hold"], "description": "Bu kareden sonrakine geçiş eğrisi."}
              },
              "required": ["time", "yaw", "pitch", "fov", "projection", "curve"]
            }
          },
          "text": {"type": "string", "description": "İsteğe bağlı kısa ekran yazısı; yoksa boş bırak."}
        },
        "required": ["clip", "start", "end", "keyframes"]
      }
    },
    "aspect": {"type": "string", "enum": ["16:9", "9:16", "1:1", "4:5", "2:1"]},
    "color": {
      "type": "object",
      "properties": {
        "filter": {"type": "string", "enum": ["none", "vivid", "cinema", "warm", "cool", "sunset", "faded", "mono", "night"]},
        "strength": {"type": "number", "description": "0..1"},
        "adjust": {
          "type": "object",
          "properties": {
            "exposure": {"type": "number"}, "contrast": {"type": "number"}, "saturation": {"type": "number"},
            "vibrance": {"type": "number"}, "temperature": {"type": "number"}, "tint": {"type": "number"},
            "highlights": {"type": "number"}, "shadows": {"type": "number"}, "fade": {"type": "number"}
          }
        }
      }
    },
    "original_volume": {"type": "number", "description": "Kamera sesi 0..1"},
    "music_volume": {"type": "number", "description": "Müzik 0..1 (müzik eklenmişse)"}
  },
  "required": ["explanation", "shots"]
}"""
    }

    /** Planı JSON'a geri yaz (sonraki isteklerde "mevcut kurgu" olarak gönderilir). */
    fun toJson(): JSONObject = JSONObject().apply {
        put("explanation", explanation)
        put("shots", JSONArray().apply {
            shots.forEach { s ->
                put(JSONObject().apply {
                    put("clip", s.clip); put("start", r(s.start)); put("end", r(s.end)); put("speed", s.speed); put("transition", s.transition)
                    s.stabilization?.let { put("stabilization", it) }
                    s.text?.let { put("text", it) }
                    put("keyframes", JSONArray().apply {
                        s.keyframes.forEach { k ->
                            put(JSONObject().put("time", r(k.time)).put("yaw", r(k.yaw)).put("pitch", r(k.pitch)).put("fov", r(k.fov))
                                .put("projection", k.projection).put("curve", k.curve))
                        }
                    })
                })
            }
        })
        aspect?.let { put("aspect", it) }
    }

    private fun r(v: Double) = Math.round(v * 100) / 100.0
}

/** Kaynak klibin plan uygulamasına gereken özeti. */
data class PlanSource(val durationUs: Long, val baseEdit: EditState, val hasImu: Boolean)

/** Uygulanmış çekim: hangi kaynaktan, hangi düzenleme ile. */
data class AppliedShot(val source: Int, val edit: EditState, val speed: Double, val fade: Boolean)

/**
 * Planı doğrulayıp projenin düzenleme modeline çevirir. Model hatalarına karşı hoşgörülü:
 * aralıklar ve açılar sınırlara çekilir, eksik anahtar kare varsa ileri yön kullanılır.
 */
object PlanApplier {

    fun apply(plan: EditPlan, sources: List<PlanSource>): Pair<List<AppliedShot>, List<String>> {
        val warn = ArrayList<String>()
        val aspect = plan.aspect?.let { a -> com.mhmmtbg.x5reframe.model.Aspect.values().firstOrNull { it.label == a } }
        val color = plan.color?.let { colorOf(it) }
        val out = ArrayList<AppliedShot>()
        for ((n, s) in plan.shots.withIndex()) {
            if (s.clip !in sources.indices) { warn += "Çekim ${n + 1}: klip ${s.clip} yok, atlandı"; continue }
            val src = sources[s.clip]
            val dur = src.durationUs / 1e6
            val a = s.start.coerceIn(0.0, dur)
            var b = s.end.coerceIn(0.0, dur)
            if (b - a < 0.4) { b = (a + 0.4).coerceAtMost(dur) }
            if (b - a < 0.3) { warn += "Çekim ${n + 1}: süre çok kısa, atlandı"; continue }
            val inUs = (a * 1e6).toLong(); val outUs = (b * 1e6).toLong()
            val keys = s.keyframes.map { k -> keyOf(k, inUs, outUs) }.sortedBy { it.timeUs }.distinctBy { it.timeUs }
                .ifEmpty { listOf(Keyframe(inUs, ViewParams(fov = 100.0))) }
            val stab = when (s.stabilization) {
                "lock" -> StabMode.LOCK; "smooth" -> StabMode.SMOOTH; "off" -> StabMode.OFF; "flowstate" -> StabMode.HORIZON
                else -> src.baseEdit.stabMode
            }
            var e = src.baseEdit.copy(
                inUs = inUs, outUs = outUs, keyframes = keys, stabMode = stab,
                layout = com.mhmmtbg.x5reframe.model.Layout.SINGLE, extraPanes = emptyList(), tracks = emptyList(),
                layers = s.text?.let { t ->
                    listOf(Layer("ai$n", Layer.Kind.TEXT, t.take(80), y = 0.82f, scale = 0.6f, startUs = inUs, endUs = outUs,
                        style = Layer.TextStyle.WHITE_BOX))
                } ?: emptyList(),
            )
            if (aspect != null) e = e.copy(aspect = aspect)
            if (color != null) e = e.copy(color = color)
            out += AppliedShot(s.clip, e, s.speed.coerceIn(0.25, 4.0), s.transition == "fade")
        }
        if (out.isEmpty()) warn += "Planda uygulanabilir çekim yok"
        return out to warn
    }

    fun keyOf(k: EditPlan.Key, inUs: Long, outUs: Long): Keyframe {
        val t = (k.time * 1e6).toLong().coerceIn(inUs, outUs)
        var proj = when (k.projection.lowercase()) {
            "wide", "planet", "tunnel" -> Projection.WIDE
            "superwide" -> Projection.SUPERWIDE
            "fisheye" -> Projection.FISHEYE
            "360", "equirect" -> Projection.EQUIRECT
            else -> Projection.RECTILINEAR
        }
        var pitch = k.pitch; var fov = k.fov
        when (k.projection.lowercase()) {
            "planet" -> { pitch = -90.0; fov = if (fov < 200) 250.0 else fov }
            "tunnel" -> { pitch = 90.0; fov = if (fov < 200) 250.0 else fov }
        }
        if (proj == Projection.EQUIRECT) { fov = 360.0; pitch = 0.0 }
        val v = ViewParams(yaw = k.yaw, pitch = pitch, roll = k.roll, fov = fov.coerceIn(proj.minFov, proj.maxFov), projection = proj).clamped()
        return Keyframe(t, v, curveOf(k.curve))
    }

    fun curveOf(name: String): Curve = when (name.lowercase()) {
        "linear" -> Curve.LINEAR; "ease_in" -> Curve.EASE_IN; "ease_out" -> Curve.EASE_OUT; "delayed" -> Curve.DELAYED
        "fast" -> Curve.FAST_IN_OUT; "hold" -> Curve.HOLD; else -> Curve.EASE_IN_OUT
    }

    fun colorOf(c: EditPlan.ColorSpec): ColorGrade {
        val f = when (c.filter?.lowercase()) {
            "vivid" -> ColorGrade.Filter.VIVID; "cinema" -> ColorGrade.Filter.CINEMA; "warm" -> ColorGrade.Filter.WARM
            "cool" -> ColorGrade.Filter.COOL; "sunset" -> ColorGrade.Filter.SUNSET; "faded" -> ColorGrade.Filter.FADED
            "mono" -> ColorGrade.Filter.MONO; "night" -> ColorGrade.Filter.NIGHT; else -> ColorGrade.Filter.NONE
        }
        var g = ColorGrade(f, (c.strength ?: 1.0).toFloat().coerceIn(0f, 1f))
        for ((k, v) in c.adjust) {
            val a = ColorGrade.Adjust.values().firstOrNull { it.name.equals(k, true) } ?: continue
            g = a.set(g, v.toFloat().coerceIn(a.min, a.max))
        }
        return g
    }

    /** Mevcut projeyi plan biçiminde anlat (takip isteklerinde "şu anki kurgu"). */
    fun describe(shots: List<Triple<Int, EditState, Double>>): JSONObject {
        val arr = JSONArray()
        for ((src, e, speed) in shots) {
            arr.put(JSONObject().apply {
                put("clip", src); put("start", e.inUs / 1e6); put("end", e.outUs / 1e6); put("speed", speed)
                put("stabilization", when (e.stabMode) { StabMode.LOCK -> "lock"; StabMode.SMOOTH -> "smooth"; StabMode.OFF -> "off"; else -> "flowstate" })
                put("keyframes", JSONArray().apply {
                    e.keyframes.forEach { k ->
                        put(JSONObject().put("time", k.timeUs / 1e6).put("yaw", Math.round(k.view.yaw).toDouble()).put("pitch", Math.round(k.view.pitch).toDouble())
                            .put("fov", Math.round(k.view.fov).toDouble()).put("projection", when (k.view.projection) {
                                Projection.WIDE -> if (k.view.pitch < -60 && k.view.fov > 200) "planet" else "wide"
                                Projection.SUPERWIDE -> "superwide"; Projection.FISHEYE -> "fisheye"; Projection.EQUIRECT -> "360"; else -> "normal"
                            }))
                    }
                })
            })
        }
        return JSONObject().put("shots", arr)
    }
}
