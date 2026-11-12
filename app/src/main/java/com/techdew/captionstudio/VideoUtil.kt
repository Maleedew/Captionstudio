package com.techdew.captionstudio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

/** Display size is the upright size (rotation metadata already applied). */
data class VideoInfo(val uri: Uri, val name: String, val durationMs: Long, val width: Int, val height: Int) {
    val aspect: Float get() = if (height > 0) width.toFloat() / height else 16f / 9f
}

object VideoUtil {
    const val MAX_VIDEO_MS = 15 * 60_000L + 5_000L   // 15 min (+5 s tolerance)

    fun read(context: Context, uri: Uri): VideoInfo? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val swap = rot == 90 || rot == 270
            VideoInfo(uri, displayName(context, uri), dur, if (swap) h else w, if (swap) w else h)
        } catch (e: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return "video"
    }
}
