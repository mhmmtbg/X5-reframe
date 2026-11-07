package com.mhmmtbg.x5reframe

import com.mhmmtbg.x5reframe.ai.AiClient
import com.mhmmtbg.x5reframe.ai.EditPlan
import com.mhmmtbg.x5reframe.ai.PlanApplier
import com.mhmmtbg.x5reframe.ai.PlanSource
import com.mhmmtbg.x5reframe.model.Aspect
import com.mhmmtbg.x5reframe.model.ColorGrade
import com.mhmmtbg.x5reframe.model.Curve
import com.mhmmtbg.x5reframe.model.EditState
import com.mhmmtbg.x5reframe.model.Projection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPlanTest {
    private val reply = """
        Tabii! İşte plan:
        ```json
        {"explanation":"İki an seçtim.","aspect":"9:16",
         "color":{"filter":"cinema","strength":0.7,"adjust":{"exposure":0.2}},
         "shots":[
          {"clip":0,"start":2,"end":5.5,"transition":"cut","keyframes":[
             {"time":2,"yaw":10,"pitch":-5,"fov":90,"projection":"planet","curve":"linear"},
             {"time":5,"yaw":40,"pitch":0,"fov":80,"projection":"normal","curve":"smooth"}]},
          {"clip":1,"start":30,"end":99,"speed":9,"transition":"fade","keyframes":[],"text":"Merhaba"},
          {"clip":7,"start":0,"end":3,"keyframes":[]}
         ]}
        ```
    """.trimIndent()

    private fun sources() = listOf(
        PlanSource(20_000_000, EditState(outUs = 20_000_000), true),
        PlanSource(40_000_000, EditState(outUs = 40_000_000), true),
    )

    @Test
    fun parsesAndApplies() {
        val plan = EditPlan.parse(EditPlan.extractJson(reply))
        assertEquals(3, plan.shots.size)
        val (shots, warn) = PlanApplier.apply(plan, sources())
        assertEquals(2, shots.size)
        assertTrue(warn.any { it.contains("klip 7") })
        val a = shots[0].edit
        assertEquals(2_000_000L, a.inUs); assertEquals(5_500_000L, a.outUs)
        assertEquals(Aspect.PORTRAIT, a.aspect)
        assertEquals(ColorGrade.Filter.CINEMA, a.color.filter)
        val k0 = a.keyframes.first()
        assertEquals(Projection.WIDE, k0.view.projection); assertEquals(-90.0, k0.view.pitch, 0.01); assertEquals(Curve.LINEAR, k0.curve)
        // Aralık klip süresine, hız 4'e sıkıştırılır; boş anahtar kareye varsayılan konur; yazı katmanı eklenir
        val b = shots[1]
        assertEquals(40_000_000L, b.edit.outUs); assertEquals(4.0, b.speed, 0.0); assertTrue(b.fade)
        assertEquals(1, b.edit.keyframes.size)
        assertEquals("Merhaba", b.edit.layers.single().content)
    }

    @Test
    fun requestBodiesHaveImagesAndSchema() {
        val img = ByteArray(10) { it.toByte() }
        val c = AiClient.claudeBody("claude-sonnet-5-5", "sys", "istek", listOf(img))
        assertEquals("apply_edit_plan", c.getJSONObject("tool_choice").getString("name"))
        assertEquals("image", c.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("type"))
        val o = AiClient.openaiBody("gpt-6.1-terra", "sys", "istek", listOf(img))
        assertTrue(o.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0)
            .getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
        assertEquals("json_schema", o.getJSONObject("response_format").getString("type"))
        val g = AiClient.geminiBody("sys", "istek", listOf(img), true)
        assertEquals("application/json", g.getJSONObject("generationConfig").getString("responseMimeType"))
        assertTrue(g.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).has("inline_data"))
        // Şema geçerli JSON ve zorunlu alanları içeriyor
        val schema: JSONObject = EditPlan.schema()
        assertTrue(schema.getJSONArray("required").toString().contains("shots"))
    }

    @Test
    fun describeRoundTrips() {
        val plan = EditPlan.parse(EditPlan.extractJson(reply))
        val (shots, _) = PlanApplier.apply(plan, sources())
        val d = PlanApplier.describe(shots.map { Triple(it.source, it.edit, it.speed) })
        val again = EditPlan.parse(d.put("explanation", ""))
        val (shots2, _) = PlanApplier.apply(again, sources())
        assertEquals(shots.size, shots2.size)
        assertEquals(shots[0].edit.inUs, shots2[0].edit.inUs)
    }
}
