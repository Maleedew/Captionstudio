package com.techdew.captionstudio

import android.util.Base64
import android.util.Base64OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiKeyException(message: String) : Exception(message)
class QuotaException(message: String, val daily: Boolean) : Exception(message)
class ModelUnavailableException(val model: String) : Exception("Model not available: $model")
class AsrException(message: String) : Exception(message)

/** Speech-to-text for one WAV chunk. Times in the result are relative to the start of that chunk. */
interface Asr {
    suspend fun transcribe(wav: File, durationMs: Long, onStatus: (String) -> Unit): List<Seg>
}

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .writeTimeout(240, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

class Reply(val code: Int, val body: String, val retryAfterMs: Long?)

suspend fun send(req: Request): Reply {
    val resp = Http.client.newCall(req).await()
    return withContext(Dispatchers.IO) {
        resp.use { r ->
            Reply(
                code = r.code,
                body = r.body?.string().orEmpty(),
                retryAfterMs = r.header("Retry-After")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            )
        }
    }
}

private suspend fun waitWithStatus(ms: Long, onStatus: (String) -> Unit) {
    var left = ms
    while (left > 0) {
        onStatus("Free-tier rate limit — waiting ${(left + 999) / 1000}s…")
        val step = minOf(1000L, left)
        delay(step)
        left -= step
    }
}

object Prompts {
    const val GEMINI_SYSTEM =
        "You are a precise subtitle transcriber for Sri Lankan video creators. You output only valid JSON."

    fun gemini(lang: LangMode): String {
        val language = when (lang) {
            LangMode.MIXED ->
                "The speaker mixes Sinhala and English in the same sentence. Write Sinhala words in Sinhala Unicode script " +
                    "and English words in English (Latin) letters, exactly as they are spoken. " +
                    "Example: \"මම අද යන්නේ CapCut ගැන කතා කරන්න\"."
            LangMode.ENGLISH -> "The speaker speaks English. Write everything in English."
            LangMode.SINHALA ->
                "The speaker speaks Sinhala. Write it in Sinhala Unicode script; if an English word is spoken, write it in English letters."
        }
        return "Transcribe this audio into subtitle segments.\n\n" +
            "LANGUAGE RULES\n- $language\n" +
            "- Never translate. Never write Sinhala in English letters (no romanization such as \"mama ada\").\n" +
            "- Keep product, brand and technical terms in their normal English spelling (CapCut, YouTube, ChatGPT, AI, Android, APK).\n" +
            "- Remove filler sounds and stutters.\n\n" +
            "SEGMENT RULES\n" +
            "- Each segment is one short subtitle line: 2 to 7 words, at most about 2.5 seconds. Start a new segment at natural pauses.\n" +
            "- \"start\" and \"end\" are times from the very beginning of THIS audio clip, formatted MM:SS.s (for example 00:07.4). " +
            "Times must increase and segments must not overlap.\n" +
            "- Skip silence, music and noise. Never invent text that was not spoken.\n\n" +
            "Return only the JSON array."
    }

    /** Whisper continues the style of its prompt, so a mixed Sinhala/English sample keeps both scripts. */
    fun whisper(lang: LangMode): String = when (lang) {
        LangMode.MIXED -> "මම අද කතා කරන්නේ CapCut ගැන. Video එක like කරලා channel එක subscribe කරන්න."
        LangMode.SINHALA -> "මම අද කතා කරන්නේ මේ ගැන."
        LangMode.ENGLISH -> "Today I'm going to show you how this works."
    }
}

/** Streams {prefix}{base64(file)}{suffix} so a 4-minute WAV never sits in RAM as one giant string. */
private class InlineAudioBody(
    private val prefix: String,
    private val suffix: String,
    private val file: File
) : RequestBody() {
    override fun contentType(): MediaType? = "application/json; charset=utf-8".toMediaType()

    override fun contentLength(): Long = -1L

    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8(prefix)
        val shield = object : FilterOutputStream(sink.outputStream()) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
            }

            override fun close() {
                flush() // the base64 stream must not close the underlying sink
            }
        }
        val b64 = Base64OutputStream(shield, Base64.NO_WRAP)
        file.inputStream().use { it.copyTo(b64, 32 * 1024) }
        b64.close()
        sink.writeUtf8(suffix)
    }
}

object GeminiApi {
    const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private const val PLACEHOLDER = "@@AUDIO_BASE64@@"

    /** Used only if the live model list cannot be read. Names change often, so the app normally discovers them. */
    val FALLBACK_MODELS = listOf("gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-2.5-flash", "gemini-2.5-flash-lite")

    fun bodyParts(lang: LangMode, thinkingLow: Boolean): Pair<String, String> {
        val body = JSONObject()
        body.put(
            "systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Prompts.GEMINI_SYSTEM)))
        )
        val audio = JSONObject().put(
            "inline_data",
            JSONObject().put("mime_type", "audio/wav").put("data", PLACEHOLDER)
        )
        val text = JSONObject().put("text", Prompts.gemini(lang))
        body.put(
            "contents",
            JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().put(audio).put(text))
            )
        )
        val schema = JSONObject()
            .put("type", "ARRAY")
            .put(
                "items",
                JSONObject()
                    .put("type", "OBJECT")
                    .put(
                        "properties",
                        JSONObject()
                            .put("start", JSONObject().put("type", "STRING"))
                            .put("end", JSONObject().put("type", "STRING"))
                            .put("text", JSONObject().put("type", "STRING"))
                    )
                    .put("required", JSONArray().put("start").put("end").put("text"))
            )
        // Google: keep temperature at its default (1.0) for Gemini 3 models, lowering it can cause looping.
        val gen = JSONObject()
            .put("responseMimeType", "application/json")
            .put("responseSchema", schema)
        // Transcription needs no deep reasoning: a low thinking level makes Gemini 3 models much faster.
        if (thinkingLow) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        body.put("generationConfig", gen)
        val safety = JSONArray()
        for (cat in listOf(
            "HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT"
        )) {
            safety.put(JSONObject().put("category", cat).put("threshold", "BLOCK_NONE"))
        }
        body.put("safetySettings", safety)

        val s = body.toString()
        val i = s.indexOf(PLACEHOLDER)
        return s.substring(0, i) to s.substring(i + PLACEHOLDER.length)
    }

    fun errorMessage(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    } catch (e: JSONException) {
        null
    }

    fun isBadKey(body: String): Boolean =
        body.contains("API_KEY_INVALID") || body.contains("API key not valid", ignoreCase = true)

    class Quota(val daily: Boolean, val waitMs: Long)

    fun quota(body: String): Quota {
        val daily = body.contains("PerDay", ignoreCase = true) ||
            body.contains("limit: 0") ||
            body.contains("per day", ignoreCase = true)
        val m = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(body)
        val sec = m?.groupValues?.get(1)?.toDoubleOrNull() ?: 20.0
        return Quota(daily, (sec * 1000).toLong() + 500L)
    }

    fun parse(body: String): List<Seg> {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw AsrException("Unexpected reply from Gemini.")
        }
        val cands = root.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            val block = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw AsrException(if (block.isNotEmpty()) "Gemini blocked this audio ($block)." else "Gemini returned no result.")
        }
        val cand = cands.getJSONObject(0)
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought", false)) continue
                sb.append(p.optString("text", ""))
            }
        }
        var text = sb.toString().trim()
        if (text.startsWith("```")) text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (text.isEmpty()) {
            val reason = cand.optString("finishReason", "")
            if (reason.isNotEmpty() && reason != "STOP") throw AsrException("Gemini stopped early ($reason).")
            return emptyList() // silent chunk
        }
        val arr = readArray(text)
        val out = ArrayList<Seg>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("text", "").trim()
            if (t.isEmpty()) continue
            val st = CaptionText.parseTimeMs(o.optString("start", "")) ?: continue
            val en = CaptionText.parseTimeMs(o.optString("end", "")) ?: (st + CaptionText.estimateDurationMs(t))
            out.add(Seg(st, en, t))
        }
        return out
    }

    private fun readArray(text: String): JSONArray {
        try {
            return JSONArray(text)
        } catch (e: JSONException) {
            val s = text.indexOf('[')
            val e2 = text.lastIndexOf(']')
            if (s >= 0 && e2 > s) {
                try {
                    return JSONArray(text.substring(s, e2 + 1))
                } catch (e3: JSONException) {
                    // fall through to salvage below
                }
            }
            val lastBrace = text.lastIndexOf('}')
            if (s >= 0 && lastBrace > s) {
                try {
                    return JSONArray(text.substring(s, lastBrace + 1) + "]")
                } catch (e4: JSONException) {
                    // give up
                }
            }
            throw AsrException("Could not read Gemini's answer (not valid JSON). Please try again.")
        }
    }

    /** Stable Flash models from the live list, newest first (full Flash before Flash-Lite). */
    suspend fun listCandidates(key: String): List<String> {
        val r = send(Request.Builder().url("$BASE/models?pageSize=200").header("x-goog-api-key", key).get().build())
        if (r.code == 400 && isBadKey(r.body) || r.code == 401) throw ApiKeyException("Gemini says this API key is not valid.")
        if (r.code !in 200..299) return FALLBACK_MODELS
        val re = Regex("^gemini-(\\d+)(?:\\.(\\d+))?-flash(-lite)?$")
        class M(val name: String, val ver: Int, val lite: Boolean)
        val found = ArrayList<M>()
        try {
            val arr = JSONObject(r.body).optJSONArray("models") ?: return FALLBACK_MODELS
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").removePrefix("models/")
                val methods = o.optJSONArray("supportedGenerationMethods")
                var canGenerate = methods == null
                if (methods != null) for (k in 0 until methods.length()) if (methods.optString(k) == "generateContent") canGenerate = true
                if (!canGenerate) continue
                val m = re.find(name) ?: continue
                val major = m.groupValues[1].toIntOrNull() ?: continue
                val minor = m.groupValues[2].toIntOrNull() ?: 0
                found.add(M(name, major * 100 + minor, m.groupValues[3].isNotEmpty()))
            }
        } catch (e: JSONException) {
            return FALLBACK_MODELS
        }
        if (found.isEmpty()) return FALLBACK_MODELS
        return found.sortedWith(compareBy<M> { -it.ver }.thenBy { it.lite }).take(6).map { it.name }
    }

    sealed interface Probe {
        data object Ok : Probe
        data class Unavailable(val why: String) : Probe
    }

    /** A tiny text-only call: tells us whether this model works with this key (free tier) right now. */
    suspend fun probe(key: String, model: String): Probe {
        val json = """{"contents":[{"parts":[{"text":"ping"}]}],"generationConfig":{"maxOutputTokens":64}}"""
        val req = Request.Builder()
            .url("$BASE/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        val r = send(req)
        return when {
            r.code in 200..299 -> Probe.Ok
            r.code == 429 -> if (quota(r.body).daily) Probe.Unavailable("free quota is 0 or used up") else Probe.Ok
            r.code == 400 && isBadKey(r.body) || r.code == 401 -> throw ApiKeyException("Gemini says this API key is not valid.")
            else -> Probe.Unavailable(errorMessage(r.body)?.take(120) ?: "HTTP ${r.code}")
        }
    }

    /** Returns the best model that works for this key, skipping the names in [exclude]. */
    suspend fun pick(key: String, exclude: Set<String>): String {
        var last: String? = null
        for (m in listCandidates(key).filter { it !in exclude }) {
            when (val p = probe(key, m)) {
                is Probe.Ok -> return m
                is Probe.Unavailable -> last = "$m: ${p.why}"
            }
        }
        throw AsrException(
            "No Gemini model could be used with this key" + (last?.let { " ($it)" } ?: "") +
                ". You can type a model name in Settings."
        )
    }
}

class GeminiAsr(
    private val key: String,
    val model: String,
    private val lang: LangMode
) : Asr {
    override suspend fun transcribe(wav: File, durationMs: Long, onStatus: (String) -> Unit): List<Seg> {
        var thinkingLow = model.startsWith("gemini-3")
        var attempt = 0
        while (true) {
            attempt++
            val (prefix, suffix) = GeminiApi.bodyParts(lang, thinkingLow)
            val req = Request.Builder()
                .url("${GeminiApi.BASE}/models/$model:generateContent")
                .header("x-goog-api-key", key)
                .post(InlineAudioBody(prefix, suffix, wav))
                .build()
            val r = send(req)
            if (r.code in 200..299) return GeminiApi.parse(r.body)

            val msg = GeminiApi.errorMessage(r.body)
            when {
                r.code == 429 -> {
                    val q = GeminiApi.quota(r.body)
                    if (q.daily) throw QuotaException("The free daily limit of $model is used up.", daily = true)
                    if (attempt >= 4 || q.waitMs > 90_000L) {
                        throw QuotaException("Gemini's free rate limit is reached. Wait a minute and try again.", daily = false)
                    }
                    waitWithStatus(q.waitMs, onStatus)
                }
                r.code == 404 -> throw ModelUnavailableException(model)
                r.code == 400 && thinkingLow && (msg ?: "").contains("thinking", ignoreCase = true) -> {
                    thinkingLow = false // this model doesn't accept the hint: retry without it
                }
                r.code == 400 && GeminiApi.isBadKey(r.body) || r.code == 401 ->
                    throw ApiKeyException("Gemini says this API key is not valid.")
                r.code == 403 -> throw ApiKeyException(msg ?: "Gemini refused the request (HTTP 403). Check the key and your region.")
                r.code >= 500 -> {
                    if (attempt >= 4) throw AsrException("Gemini server error (${r.code}). Try again in a few minutes.")
                    waitWithStatus(attempt * 4000L, onStatus)
                }
                else -> throw AsrException(msg ?: "Gemini error (HTTP ${r.code}).")
            }
        }
    }
}

class GroqAsr(
    private val key: String,
    private val model: String,
    private val lang: LangMode
) : Asr {
    override suspend fun transcribe(wav: File, durationMs: Long, onStatus: (String) -> Unit): List<Seg> {
        var attempt = 0
        while (true) {
            attempt++
            val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .addFormDataPart("temperature", "0")
                .addFormDataPart("prompt", Prompts.whisper(lang))
            when (lang) {
                LangMode.ENGLISH -> mb.addFormDataPart("language", "en")
                LangMode.SINHALA -> mb.addFormDataPart("language", "si")
                LangMode.MIXED -> {}
            }
            mb.addFormDataPart("file", "audio.wav", wav.asRequestBody("audio/wav".toMediaType()))
            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/transcriptions")
                .header("Authorization", "Bearer $key")
                .post(mb.build())
                .build()
            val r = send(req)
            if (r.code in 200..299) return parse(r.body, durationMs)

            val msg = try {
                JSONObject(r.body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            } catch (e: JSONException) {
                null
            }
            when {
                r.code == 401 -> throw ApiKeyException("Groq says this API key is not valid.")
                r.code == 429 -> {
                    val daily = (msg ?: "").contains("per day", ignoreCase = true) || (msg ?: "").contains("ASD")
                    val wait = (r.retryAfterMs ?: 20_000L) + 500L
                    if (daily) throw QuotaException("Groq's free daily audio limit is used up.", daily = true)
                    if (attempt >= 4 || wait > 90_000L) throw QuotaException("Groq's free rate limit is reached. Wait a minute and try again.", daily = false)
                    waitWithStatus(wait, onStatus)
                }
                r.code == 413 -> throw AsrException("This audio piece is too large for Groq's free tier (25 MB).")
                r.code >= 500 -> {
                    if (attempt >= 4) throw AsrException("Groq server error (${r.code}). Try again in a few minutes.")
                    waitWithStatus(attempt * 4000L, onStatus)
                }
                else -> throw AsrException(msg ?: "Groq error (HTTP ${r.code}).")
            }
        }
    }

    private fun parse(body: String, durationMs: Long): List<Seg> {
        val o = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw AsrException("Unexpected reply from Groq.")
        }
        val arr = o.optJSONArray("segments")
        if (arr != null && arr.length() > 0) {
            val out = ArrayList<Seg>()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val t = s.optString("text", "").trim()
                if (t.isEmpty()) continue
                // Whisper invents text over silence / music and sometimes loops: drop those segments.
                if (s.optDouble("no_speech_prob", 0.0) > 0.7) continue
                if (s.optDouble("compression_ratio", 0.0) > 2.6) continue
                out.add(Seg((s.optDouble("start", 0.0) * 1000).toLong(), (s.optDouble("end", 0.0) * 1000).toLong(), t))
            }
            return out
        }
        val t = o.optString("text", "").trim()
        return if (t.isEmpty()) emptyList() else listOf(Seg(0L, durationMs, t))
    }
}

object KeyTester {
    /** Cheap validity checks that don't use transcription quota. Returns a human-readable result. */
    suspend fun groq(key: String): String {
        val r = send(Request.Builder().url("https://api.groq.com/openai/v1/models").header("Authorization", "Bearer $key").get().build())
        return when {
            r.code in 200..299 -> "Groq key works."
            r.code == 401 -> throw ApiKeyException("Groq says this API key is not valid.")
            else -> throw AsrException("Groq check failed (HTTP ${r.code}).")
        }
    }
}
