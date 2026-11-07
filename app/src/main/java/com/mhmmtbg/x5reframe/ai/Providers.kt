package com.mhmmtbg.x5reframe.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Desteklenen yapay zekâ sağlayıcıları. Model adları düzenlenebilir; listeler öneridir. */
enum class AiProvider(val label: String, val defaultModel: String, val models: List<String>, val keyUrl: String) {
    CLAUDE("Claude", "claude-sonnet-5-5", listOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5"), "https://console.anthropic.com/settings/keys"),
    GEMINI("Gemini", "gemini-3.8-flash", listOf("gemini-3.8-flash", "gemini-4-argon", "gemini-3.5-flash-lite"), "https://aistudio.google.com/apikey"),
    CHATGPT("ChatGPT", "gpt-6.1-terra", listOf("gpt-6.1-terra", "gpt-6.1-sol", "gpt-6-astra", "gpt-6-luna"), "https://platform.openai.com/api-keys"),
}

/** Bir istek: metin + görseller (JPEG) → JSON plan. */
class AiReply(val json: JSONObject, val usage: String)

class AiException(message: String) : Exception(message)

/**
 * Sağlayıcı istemcileri. Ağ çağrıları doğrudan telefondan sağlayıcıya gider (ara sunucu yok);
 * API anahtarı yalnızca bu istekte başlıkta kullanılır.
 */
object AiClient {

    fun request(provider: AiProvider, key: String, model: String, system: String, user: String, images: List<ByteArray>): AiReply =
        when (provider) {
            AiProvider.CLAUDE -> claude(key, model, system, user, images)
            AiProvider.GEMINI -> gemini(key, model, system, user, images)
            AiProvider.CHATGPT -> openai(key, model, system, user, images)
        }

    // ---- Claude (Anthropic Messages API, zorunlu araç çağrısıyla yapılandırılmış çıktı) ----

    fun claudeBody(model: String, system: String, user: String, images: List<ByteArray>): JSONObject = JSONObject().apply {
        put("model", model)
        put("max_tokens", 8000)
        put("system", system)
        put("tools", JSONArray().put(JSONObject().apply {
            put("name", "apply_edit_plan")
            put("description", "Kurgu planını uygulamaya uygula.")
            put("input_schema", EditPlan.schema())
        }))
        put("tool_choice", JSONObject().put("type", "tool").put("name", "apply_edit_plan"))
        put("messages", JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
                images.forEach { img ->
                    put(JSONObject().put("type", "image").put("source", JSONObject()
                        .put("type", "base64").put("media_type", "image/jpeg").put("data", b64(img))))
                }
                put(JSONObject().put("type", "text").put("text", user))
            })
        }))
    }

    private fun claude(key: String, model: String, system: String, user: String, images: List<ByteArray>): AiReply {
        val res = post("https://api.anthropic.com/v1/messages", claudeBody(model, system, user, images),
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"))
        val content = res.optJSONArray("content") ?: throw AiException("boş yanıt")
        for (i in 0 until content.length()) {
            val c = content.getJSONObject(i)
            if (c.optString("type") == "tool_use") return AiReply(c.getJSONObject("input"), usageOf(res.optJSONObject("usage"), "input_tokens", "output_tokens"))
        }
        // Araç çağrısı yoksa metinden JSON çıkar
        val text = (0 until content.length()).joinToString("") { content.getJSONObject(it).optString("text") }
        return AiReply(EditPlan.extractJson(text), usageOf(res.optJSONObject("usage"), "input_tokens", "output_tokens"))
    }

    // ---- Gemini (generateContent, JSON yanıt biçimi) ----

    fun geminiBody(system: String, user: String, images: List<ByteArray>, withSchema: Boolean): JSONObject = JSONObject().apply {
        put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        put("contents", JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                images.forEach { img -> put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64(img)))) }
                put(JSONObject().put("text", if (withSchema) user else user + "\n\nYanıt şeması (JSON Schema):\n" + EditPlan.schema().toString()))
            })
        }))
        put("generationConfig", JSONObject().apply {
            put("responseMimeType", "application/json")
            if (withSchema) put("responseJsonSchema", EditPlan.schema())
        })
    }

    private fun gemini(key: String, model: String, system: String, user: String, images: List<ByteArray>): AiReply {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val res = try {
            post(url, geminiBody(system, user, images, true), mapOf("x-goog-api-key" to key))
        } catch (e: AiException) {
            // Eski modeller responseJsonSchema'yı tanımayabilir: şemayı metinde vererek yeniden dene
            if (e.message?.contains("400") == true && e.message?.contains("responseJsonSchema", true) == true)
                post(url, geminiBody(system, user, images, false), mapOf("x-goog-api-key" to key)) else throw e
        }
        val cands = res.optJSONArray("candidates") ?: throw AiException("yanıt yok: ${res.optJSONObject("promptFeedback")?.optString("blockReason") ?: ""}")
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: throw AiException("boş yanıt")
        val text = (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
        return AiReply(EditPlan.extractJson(text), usageOf(res.optJSONObject("usageMetadata"), "promptTokenCount", "candidatesTokenCount"))
    }

    // ---- ChatGPT (OpenAI Chat Completions, json_schema yanıt biçimi) ----

    fun openaiBody(model: String, system: String, user: String, images: List<ByteArray>): JSONObject = JSONObject().apply {
        put("model", model)
        put("messages", JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", system))
            put(JSONObject().put("role", "user").put("content", JSONArray().apply {
                images.forEach { img ->
                    put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                        .put("url", "data:image/jpeg;base64," + b64(img)).put("detail", "high")))
                }
                put(JSONObject().put("type", "text").put("text", user))
            }))
        })
        put("response_format", JSONObject().put("type", "json_schema").put("json_schema", JSONObject()
            .put("name", "edit_plan").put("strict", false).put("schema", EditPlan.schema())))
    }

    private fun openai(key: String, model: String, system: String, user: String, images: List<ByteArray>): AiReply {
        val res = post("https://api.openai.com/v1/chat/completions", openaiBody(model, system, user, images),
            mapOf("Authorization" to "Bearer $key"))
        val msg = res.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: throw AiException("boş yanıt")
        msg.optString("refusal").takeIf { it.isNotBlank() && it != "null" }?.let { throw AiException("model reddetti: $it") }
        return AiReply(EditPlan.extractJson(msg.optString("content")), usageOf(res.optJSONObject("usage"), "prompt_tokens", "completion_tokens"))
    }

    /** Anahtar ve modelin çalıştığını küçük bir istekle doğrula. */
    fun test(provider: AiProvider, key: String, model: String): String {
        val r = request(provider, key, model, "Yalnızca şemaya uygun JSON döndür.",
            "Test: explanation alanına 'Bağlantı tamam' yaz, shots boş dizi olsun.", emptyList())
        return r.json.optString("explanation", "yanıt alındı") + " · " + r.usage
    }

    // ---- Ortak ----

    private fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)

    private fun usageOf(u: JSONObject?, inKey: String, outKey: String): String =
        if (u == null) "" else "${u.optInt(inKey)} girdi + ${u.optInt(outKey)} çıktı token"

    /** Sık karşılaşılan sağlayıcı hatalarını ne yapılacağını söyleyen Türkçe mesaja çevir. */
    fun friendly(url: String, code: Int, msg: String): String {
        val m = msg.lowercase()
        val who = when { "anthropic" in url -> "Anthropic (Claude)"; "openai" in url -> "OpenAI (ChatGPT)"; else -> "Google (Gemini)" }
        return when {
            "credit balance is too low" in m ->
                "Anthropic API hesabında kredi yok. console.anthropic.com > Plans & Billing bölümünden kredi yükle. " +
                    "Not: Claude.ai Pro/Max aboneliği API kredisi içermez, API ayrı ücretlendirilir."
            "insufficient_quota" in m || "exceeded your current quota" in m ->
                "$who hesabında kullanılabilir bakiye/kota yok. Sağlayıcının faturalandırma sayfasından bakiye ekle."
            code == 401 || "invalid x-api-key" in m || "incorrect api key" in m || "api key not valid" in m ->
                "$who API anahtarı geçersiz. Ayarlar > Yapay zekâ'da anahtarı yeniden gir."
            code == 403 -> "$who bu anahtara izin vermiyor (bölge, proje ya da model erişimi): $msg"
            code == 404 || "not_found" in m || "model" in m && "not found" in m ->
                "$who bu model adını tanımıyor. Ayarlar > Yapay zekâ'da başka bir model seç."
            code == 429 || "resource_exhausted" in m || "rate limit" in m ->
                "$who istek sınırına ulaşıldı. Biraz bekleyip tekrar dene ya da daha ucuz/hızlı bir model seç."
            code == 529 || code == 503 || "overloaded" in m -> "$who şu an yoğun. Birkaç dakika sonra tekrar dene."
            code == 413 || "too large" in m -> "İstek çok büyük. Daha kısa bir aralık ya da daha az klip ile dene."
            else -> "HTTP $code: $msg"
        }
    }

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20_000
        c.readTimeout = 180_000
        c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching {
                    val o = JSONObject(text)
                    o.optJSONObject("error")?.optString("message") ?: o.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: text.take(300)
                throw AiException(friendly(url, code, msg))
            }
            return JSONObject(text)
        } catch (e: java.io.IOException) {
            throw AiException("Bağlantı hatası: ${e.message}")
        } finally {
            c.disconnect()
        }
    }
}

/** Ayarlar ve anahtarlar. Anahtarlar Android Keystore'daki AES anahtarıyla şifrelenip saklanır. */
object AiSettings {
    private const val PREFS = "ai"
    private const val ALIAS = "x5reframe_ai_keys"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun provider(c: Context): AiProvider =
        prefs(c).getString("provider", null)?.let { n -> AiProvider.values().firstOrNull { it.name == n } } ?: AiProvider.CLAUDE
    fun setProvider(c: Context, p: AiProvider) = prefs(c).edit().putString("provider", p.name).apply()

    fun model(c: Context, p: AiProvider): String = prefs(c).getString("model_${p.name}", null) ?: p.defaultModel
    fun setModel(c: Context, p: AiProvider, m: String) = prefs(c).edit().putString("model_${p.name}", m.trim()).apply()

    /** Uygulanan planı karelerle görüp bir tur düzeltsin mi. */
    fun review(c: Context) = prefs(c).getBoolean("review", true)
    fun setReview(c: Context, on: Boolean) = prefs(c).edit().putBoolean("review", on).apply()

    /** Kullanıcı karelerin gönderileceğini bu sağlayıcı için onayladı mı. */
    fun consented(c: Context, p: AiProvider) = prefs(c).getBoolean("consent_${p.name}", false)
    fun setConsented(c: Context, p: AiProvider) = prefs(c).edit().putBoolean("consent_${p.name}", true).apply()

    fun hasKey(c: Context, p: AiProvider) = prefs(c).contains("key_${p.name}")

    fun key(c: Context, p: AiProvider): String? = runCatching {
        val enc = prefs(c).getString("key_${p.name}", null) ?: return null
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(raw.copyOfRange(12, raw.size)))
    }.getOrNull()

    fun setKey(c: Context, p: AiProvider, key: String?) {
        if (key.isNullOrBlank()) { prefs(c).edit().remove("key_${p.name}").apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val out = cipher.iv + cipher.doFinal(key.trim().toByteArray())
        prefs(c).edit().putString("key_${p.name}", Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }
}
