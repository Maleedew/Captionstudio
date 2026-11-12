package com.techdew.captionstudio

import org.json.JSONObject

enum class Anim { NONE, POP, FADE }

/**
 * Everything the user can change about how captions look.
 * Sizes are fractions (of video height / font size) so the preview and the exported video match at any resolution.
 * Colors are ARGB ints.
 */
data class CaptionStyle(
    val fontId: String = "noto_sans",
    val size: Float = 0.052f,            // font size = size * video height
    val bold: Boolean = true,
    val italic: Boolean = false,
    val uppercase: Boolean = false,
    val textColor: Int = 0xFFF2F4F7.toInt(),
    val strokeOn: Boolean = true,
    val strokeColor: Int = 0xFF000000.toInt(),
    val strokeWidth: Float = 0.14f,      // * font size
    val shadowOn: Boolean = true,
    val shadowColor: Int = 0xFF000000.toInt(),
    val shadowBlur: Float = 0.12f,       // * font size
    val shadowOffset: Float = 0.08f,     // * font size (downwards)
    val bgOn: Boolean = false,
    val bgColor: Int = 0xFF07080A.toInt(),
    val bgAlpha: Float = 0.60f,
    val bgRadius: Float = 0.35f,         // * font size
    val posY: Float = 0.80f,             // vertical centre of the caption block (0 = top, 1 = bottom)
    val maxWidth: Float = 0.88f,         // fraction of video width available to the text
    val anim: Anim = Anim.POP,
    val animStrength: Float = 0.25f      // pop: starts at (1 - strength) of its size
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("fontId", fontId)
        put("size", size.toDouble())
        put("bold", bold)
        put("italic", italic)
        put("uppercase", uppercase)
        put("textColor", textColor)
        put("strokeOn", strokeOn)
        put("strokeColor", strokeColor)
        put("strokeWidth", strokeWidth.toDouble())
        put("shadowOn", shadowOn)
        put("shadowColor", shadowColor)
        put("shadowBlur", shadowBlur.toDouble())
        put("shadowOffset", shadowOffset.toDouble())
        put("bgOn", bgOn)
        put("bgColor", bgColor)
        put("bgAlpha", bgAlpha.toDouble())
        put("bgRadius", bgRadius.toDouble())
        put("posY", posY.toDouble())
        put("maxWidth", maxWidth.toDouble())
        put("anim", anim.name)
        put("animStrength", animStrength.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject): CaptionStyle {
            val d = CaptionStyle()
            fun f(key: String, def: Float) = o.optDouble(key, def.toDouble()).toFloat()
            return CaptionStyle(
                fontId = o.optString("fontId", d.fontId),
                size = f("size", d.size).coerceIn(0.015f, 0.15f),
                bold = o.optBoolean("bold", d.bold),
                italic = o.optBoolean("italic", d.italic),
                uppercase = o.optBoolean("uppercase", d.uppercase),
                textColor = o.optInt("textColor", d.textColor),
                strokeOn = o.optBoolean("strokeOn", d.strokeOn),
                strokeColor = o.optInt("strokeColor", d.strokeColor),
                strokeWidth = f("strokeWidth", d.strokeWidth).coerceIn(0f, 0.5f),
                shadowOn = o.optBoolean("shadowOn", d.shadowOn),
                shadowColor = o.optInt("shadowColor", d.shadowColor),
                shadowBlur = f("shadowBlur", d.shadowBlur).coerceIn(0f, 0.6f),
                shadowOffset = f("shadowOffset", d.shadowOffset).coerceIn(0f, 0.5f),
                bgOn = o.optBoolean("bgOn", d.bgOn),
                bgColor = o.optInt("bgColor", d.bgColor),
                bgAlpha = f("bgAlpha", d.bgAlpha).coerceIn(0f, 1f),
                bgRadius = f("bgRadius", d.bgRadius).coerceIn(0f, 1f),
                posY = f("posY", d.posY).coerceIn(0.05f, 0.95f),
                maxWidth = f("maxWidth", d.maxWidth).coerceIn(0.4f, 0.98f),
                anim = try {
                    Anim.valueOf(o.optString("anim", d.anim.name))
                } catch (e: IllegalArgumentException) {
                    d.anim
                },
                animStrength = f("animStrength", d.animStrength).coerceIn(0f, 0.6f)
            )
        }
    }
}

/**
 * Bundled fonts (all SIL Open Font License, see assets/fonts/licenses). Sinhala fonts also cover Latin.
 * The Latin-only display fonts fall back to the system font for Sinhala letters automatically.
 */
data class FontSpec(
    val id: String,
    val label: String,
    val file: String?,              // null = system default
    val boldFile: String? = null,   // separate bold file, if the family ships one
    val variable: Boolean = false   // variable font: bold is done through the 'wght' axis
)

object Fonts {
    val all: List<FontSpec> = listOf(
        FontSpec("noto_sans", "Noto Sans", "NotoSansSinhala-var.ttf", variable = true),
        FontSpec("gemunu", "Gemunu Libre", "GemunuLibre-var.ttf", variable = true),
        FontSpec("yaldevi", "Yaldevi", "Yaldevi-var.ttf", variable = true),
        FontSpec("abhaya", "Abhaya Libre", "AbhayaLibre-Regular.ttf", boldFile = "AbhayaLibre-Bold.ttf"),
        FontSpec("poppins", "Poppins", "Poppins-Regular.ttf", boldFile = "Poppins-Bold.ttf"),
        FontSpec("anton", "Anton", "Anton-Regular.ttf"),
        FontSpec("system", "System", null)
    )

    fun byId(id: String): FontSpec = all.firstOrNull { it.id == id } ?: all.first()
}

/** One-tap looks shown in the Style tab. They keep the user's font, size and position. */
object Presets {
    val names = listOf("Classic", "Electric", "Pop", "Box", "Minimal")

    fun apply(name: String, s: CaptionStyle): CaptionStyle = when (name) {
        "Electric" -> s.copy(
            textColor = 0xFFF2F4F7.toInt(), strokeOn = true, strokeColor = 0xFF00B7FF.toInt(), strokeWidth = 0.10f,
            shadowOn = true, shadowColor = 0xFF00B7FF.toInt(), shadowBlur = 0.35f, shadowOffset = 0f,
            bgOn = false, bold = true, anim = Anim.POP
        )
        "Pop" -> s.copy(
            textColor = 0xFFFFD60A.toInt(), strokeOn = true, strokeColor = 0xFF000000.toInt(), strokeWidth = 0.20f,
            shadowOn = true, shadowColor = 0xFF000000.toInt(), shadowBlur = 0.05f, shadowOffset = 0.10f,
            bgOn = false, bold = true, uppercase = true, anim = Anim.POP, animStrength = 0.35f
        )
        "Box" -> s.copy(
            textColor = 0xFFF2F4F7.toInt(), strokeOn = false, shadowOn = false,
            bgOn = true, bgColor = 0xFF07080A.toInt(), bgAlpha = 0.72f, bgRadius = 0.40f,
            bold = true, uppercase = false, anim = Anim.FADE
        )
        "Minimal" -> s.copy(
            textColor = 0xFFF2F4F7.toInt(), strokeOn = false,
            shadowOn = true, shadowColor = 0xFF000000.toInt(), shadowBlur = 0.25f, shadowOffset = 0.05f,
            bgOn = false, bold = false, uppercase = false, anim = Anim.FADE
        )
        else -> s.copy( // Classic
            textColor = 0xFFF2F4F7.toInt(), strokeOn = true, strokeColor = 0xFF000000.toInt(), strokeWidth = 0.14f,
            shadowOn = true, shadowColor = 0xFF000000.toInt(), shadowBlur = 0.12f, shadowOffset = 0.08f,
            bgOn = false, bold = true, uppercase = false, anim = Anim.POP, animStrength = 0.25f
        )
    }
}
