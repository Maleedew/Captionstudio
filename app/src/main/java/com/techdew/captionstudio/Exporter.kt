package com.techdew.captionstudio

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.math.roundToInt

class OutSize(val w: Int, val h: Int, val resize: Boolean)

/**
 * Burns the captions into a new MP4 with Media3 Transformer: the phone's hardware decoder/encoder do the heavy
 * lifting, and every caption is drawn by [CaptionRenderer] (same code as the preview) as an overlay bitmap.
 * Must be created and started on the main thread.
 */
@OptIn(UnstableApi::class)
class Exporter(private val context: Context, private val renderer: CaptionRenderer) {
    private var transformer: Transformer? = null
    private val holder = ProgressHolder()

    fun start(
        video: VideoInfo,
        captions: List<Caption>,
        style: CaptionStyle,
        shortSide: Int,
        outFile: File,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val size = outputSize(video.width, video.height, shortSide)
        val overlay = CaptionOverlay(renderer, captions.sortedBy { it.startMs }, style, size.w, size.h)

        val effects = ArrayList<Effect>()
        if (size.resize) {
            effects.add(Presentation.createForWidthAndHeight(size.w, size.h, Presentation.LAYOUT_SCALE_TO_FIT))
        }
        effects.add(OverlayEffect(listOf(overlay)))

        val item = EditedMediaItem.Builder(MediaItem.fromUri(video.uri))
            .setEffects(Effects(emptyList(), effects))
            .build()

        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onDone()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    onError(describe(exportException))
                }
            })
            .build()
        transformer = t
        t.start(item, outFile.absolutePath)
    }

    /** 0..1 while the export is running, or null when the progress isn't known yet. */
    fun progress(): Float? {
        val t = transformer ?: return null
        val state = t.getProgress(holder)
        return if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress / 100f else null
    }

    fun cancel() {
        try {
            transformer?.cancel()
        } catch (e: Exception) {
            // ignore
        }
        transformer = null
    }

    private fun describe(e: ExportException): String {
        val why = when (e.errorCode) {
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "This phone couldn't decode the video. Try another video."
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
                "The phone's video encoder failed. Try 480p, close other apps and retry."
            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ->
                "Graphics processing failed (probably low memory). Try 480p."
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
            ExportException.ERROR_CODE_IO_NO_PERMISSION ->
                "The original video can't be opened any more. Import it again."
            else -> e.message ?: "Unknown error."
        }
        return "Export failed (" + e.getErrorCodeName() + "). " + why
    }

    companion object {
        /** Frame size the captions are drawn at. [shortSide] 0 = keep the original size. */
        fun outputSize(srcW: Int, srcH: Int, shortSide: Int): OutSize {
            val short = minOf(srcW, srcH)
            if (shortSide <= 0 || short <= shortSide) return OutSize(srcW, srcH, false)
            val k = shortSide.toDouble() / short
            return OutSize(even((srcW * k).roundToInt()), even((srcH * k).roundToInt()), true)
        }

        private fun even(v: Int): Int = maxOf(2, v - (v % 2))
    }
}

/**
 * Time-varying overlay: returns a full-frame transparent bitmap with the active caption drawn on it.
 * Media3 draws overlay bitmaps at their pixel size relative to the frame, so a bitmap with the frame's size covers
 * it exactly. Two bitmaps are used alternately so a change is always seen as a new texture.
 */
@OptIn(UnstableApi::class)
private class CaptionOverlay(
    private val renderer: CaptionRenderer,
    private val captions: List<Caption>,
    private val style: CaptionStyle,
    private val w: Int,
    private val h: Int
) : BitmapOverlay() {
    private val empty: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var flip = 0
    private var lastKey = -1L
    private var last: Bitmap = empty

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val tMs = presentationTimeUs / 1000L
        val idx = CaptionText.activeIndex(captions, tMs)
        if (idx < 0) {
            lastKey = -1L
            last = empty
            return empty
        }
        val c = captions[idx]
        val age = tMs - c.startMs
        val step = if (style.anim != Anim.NONE && age < CaptionRenderer.ANIM_MS) age / 33L else 1000L
        val key = idx * 2000L + step
        if (key == lastKey) return last

        flip = flip xor 1
        var bmp = buffers[flip]
        if (bmp == null) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buffers[flip] = bmp
        } else {
            bmp.eraseColor(0)
        }
        renderer.draw(Canvas(bmp), w, h, c.text, style, age)
        lastKey = key
        last = bmp
        return bmp
    }
}

object MediaSaver {
    /** Android 10+: saves into Movies/CaptionStudio via MediaStore (no permission needed). Returns the Uri or null. */
    fun saveToMovies(context: Context, file: File, displayName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CaptionStudio")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                resolver.delete(uri, null, null)
                null
            } else {
                out.use { o -> file.inputStream().use { it.copyTo(o, 64 * 1024) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Copies [file] to a Uri chosen by the user with the system "Save as" dialog (Android 8-9). */
    fun copyTo(context: Context, file: File, dest: Uri): Boolean = try {
        val out = context.contentResolver.openOutputStream(dest)
        if (out == null) {
            false
        } else {
            out.use { o -> file.inputStream().use { it.copyTo(o, 64 * 1024) } }
            true
        }
    } catch (e: Exception) {
        false
    }
}
