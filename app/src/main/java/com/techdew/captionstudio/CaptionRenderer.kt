package com.techdew.captionstudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.pow

/**
 * Draws one caption onto any Canvas. The live preview and the exported video both call this same function,
 * so what you see while editing is exactly what gets burned into the MP4. Complex scripts (Sinhala conjuncts,
 * vowel signs) are shaped by Android's text engine.
 */
class CaptionRenderer(context: Context) {
    private val assets = context.applicationContext.assets
    private val cache = HashMap<String, Typeface>()

    @Synchronized
    fun typeface(style: CaptionStyle): Typeface {
        val spec = Fonts.byId(style.fontId)
        val key = spec.id + if (style.bold) "#b" else "#r"
        cache[key]?.let { return it }
        val tf: Typeface = try {
            when {
                spec.file == null -> Typeface.create(Typeface.DEFAULT, if (style.bold) Typeface.BOLD else Typeface.NORMAL)
                spec.variable -> Typeface.Builder(assets, "fonts/${spec.file}")
                    .setFontVariationSettings(if (style.bold) "'wght' 700" else "'wght' 400")
                    .build() ?: Typeface.DEFAULT
                style.bold && spec.boldFile != null -> Typeface.createFromAsset(assets, "fonts/${spec.boldFile}")
                else -> Typeface.createFromAsset(assets, "fonts/${spec.file}")
            }
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
        cache[key] = tf
        return tf
    }

    /** True when the chosen font has no real bold, so bold should be faked. */
    private fun needsFakeBold(style: CaptionStyle): Boolean {
        val spec = Fonts.byId(style.fontId)
        return style.bold && spec.file != null && !spec.variable && spec.boldFile == null
    }

    /**
     * @param w,h size of the surface in pixels (video frame size for export, preview box size on screen)
     * @param ageMs time since the caption appeared, drives the pop / fade animation
     */
    fun draw(canvas: Canvas, w: Int, h: Int, rawText: String, style: CaptionStyle, ageMs: Long) {
        val text = (if (style.uppercase) rawText.uppercase() else rawText).trim()
        if (text.isEmpty() || w <= 0 || h <= 0) return

        val fontPx = max(8f, style.size * h)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typeface(style)
            textSize = fontPx
            isFakeBoldText = needsFakeBold(style)
            textSkewX = if (style.italic) -0.22f else 0f
            strokeJoin = Paint.Join.ROUND
            strokeMiter = 2f
        }
        val maxW = max(40, (w * style.maxWidth).toInt())
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, maxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.05f)
            .build()

        var textW = 0f
        for (i in 0 until layout.lineCount) textW = max(textW, layout.getLineWidth(i))
        val blockH = layout.height.toFloat()

        // animation: scale (pop) and alpha (fade / pop)
        var scale = 1f
        var alpha = 1f
        if (style.anim != Anim.NONE && ageMs < ANIM_MS) {
            val t = (ageMs.toFloat() / ANIM_MS).coerceIn(0f, 1f)
            when (style.anim) {
                Anim.POP -> {
                    val from = 1f - style.animStrength
                    scale = from + (1f - from) * easeOutBack(t)
                    alpha = (t * 3f).coerceIn(0f, 1f)
                }
                Anim.FADE -> alpha = t
                Anim.NONE -> {}
            }
        }

        val cx = w / 2f
        val cy = style.posY * h
        val checkpoint = if (alpha < 0.999f) canvas.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (alpha * 255).toInt()) else -1
        val save = canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.translate(cx - maxW / 2f, cy - blockH / 2f)

        if (style.bgOn) {
            val padX = fontPx * 0.55f
            val padY = fontPx * 0.28f
            val rect = RectF((maxW - textW) / 2f - padX, -padY, (maxW + textW) / 2f + padX, blockH + padY)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(style.bgColor, (style.bgAlpha * 255).toInt().coerceIn(0, 255))
            }
            val r = fontPx * style.bgRadius
            canvas.drawRoundRect(rect, r, r, bg)
        }

        val shadowAlpha = 215
        if (style.strokeOn && style.strokeWidth > 0f) {
            tp.style = Paint.Style.STROKE
            tp.strokeWidth = fontPx * style.strokeWidth
            tp.color = style.strokeColor
            if (style.shadowOn) {
                tp.setShadowLayer(
                    max(0.01f, fontPx * style.shadowBlur), 0f, fontPx * style.shadowOffset,
                    ColorUtils.setAlphaComponent(style.shadowColor, shadowAlpha)
                )
            }
            layout.draw(canvas)
            tp.clearShadowLayer()
        }
        tp.style = Paint.Style.FILL
        tp.color = style.textColor
        if (style.shadowOn && !(style.strokeOn && style.strokeWidth > 0f)) {
            tp.setShadowLayer(
                max(0.01f, fontPx * style.shadowBlur), 0f, fontPx * style.shadowOffset,
                ColorUtils.setAlphaComponent(style.shadowColor, shadowAlpha)
            )
        }
        layout.draw(canvas)
        tp.clearShadowLayer()

        canvas.restoreToCount(save)
        if (checkpoint >= 0) canvas.restoreToCount(checkpoint)
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.4f
        val c3 = c1 + 1f
        return 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
    }

    companion object {
        const val ANIM_MS = 200L
    }
}
