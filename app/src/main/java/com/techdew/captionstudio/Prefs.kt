package com.techdew.captionstudio

import android.content.Context
import org.json.JSONObject

enum class Provider { GEMINI, GROQ }
enum class LangMode { MIXED, ENGLISH, SINHALA }

/** Plain (non-secret) settings. API keys live in [SecureKeys]. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var provider: Provider
        get() = try {
            Provider.valueOf(sp.getString("provider", Provider.GEMINI.name) ?: Provider.GEMINI.name)
        } catch (e: IllegalArgumentException) {
            Provider.GEMINI
        }
        set(v) = sp.edit().putString("provider", v.name).apply()

    var langMode: LangMode
        get() = try {
            LangMode.valueOf(sp.getString("lang", LangMode.MIXED.name) ?: LangMode.MIXED.name)
        } catch (e: IllegalArgumentException) {
            LangMode.MIXED
        }
        set(v) = sp.edit().putString("lang", v.name).apply()

    /** Model typed by the user in Settings (empty = pick automatically). */
    var geminiModel: String
        get() = sp.getString("gemini_model", "") ?: ""
        set(v) = sp.edit().putString("gemini_model", v.trim()).apply()

    /** Model found automatically by probing the key (cached so we don't probe every time). */
    var geminiModelAuto: String
        get() = sp.getString("gemini_model_auto", "") ?: ""
        set(v) = sp.edit().putString("gemini_model_auto", v).apply()

    var groqModel: String
        get() = sp.getString("groq_model", "whisper-large-v3") ?: "whisper-large-v3"
        set(v) = sp.edit().putString("groq_model", v.trim().ifEmpty { "whisper-large-v3" }).apply()

    var wordsPerCaption: Int
        get() = sp.getInt("words", 5)
        set(v) = sp.edit().putInt("words", v.coerceIn(1, 12)).apply()

    var exportHeight: Int   // short side in px: 480, 720 or 0 = original
        get() = sp.getInt("export_h", 720)
        set(v) = sp.edit().putInt("export_h", v).apply()

    var style: CaptionStyle
        get() = try {
            CaptionStyle.fromJson(JSONObject(sp.getString("style", "{}") ?: "{}"))
        } catch (e: Exception) {
            CaptionStyle()
        }
        set(v) = sp.edit().putString("style", v.toJson().toString()).apply()
}
