package com.techdew.captionstudio

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SavedProject(val video: VideoInfo, val captions: List<Caption>, val raw: List<Seg>)

/** Keeps the last project (captions + raw transcript) on disk so a killed app (low RAM!) doesn't lose the work. */
class ProjectStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "project.json")

    fun save(video: VideoInfo, captions: List<Caption>, raw: List<Seg>) {
        try {
            val o = JSONObject()
                .put("uri", video.uri.toString())
                .put("name", video.name)
                .put("dur", video.durationMs)
                .put("w", video.width)
                .put("h", video.height)
            val c = JSONArray()
            for (x in captions) c.put(JSONObject().put("id", x.id).put("s", x.startMs).put("e", x.endMs).put("t", x.text))
            val r = JSONArray()
            for (x in raw) r.put(JSONObject().put("s", x.startMs).put("e", x.endMs).put("t", x.text))
            o.put("captions", c).put("raw", r)
            val tmp = File(file.parentFile, "project.json.tmp")
            tmp.writeText(o.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(o.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            // best effort
        }
    }

    fun load(): SavedProject? {
        if (!file.exists()) return null
        return try {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            val video = VideoInfo(
                uri = Uri.parse(o.getString("uri")),
                name = o.optString("name", "video"),
                durationMs = o.optLong("dur", 0L),
                width = o.optInt("w", 0),
                height = o.optInt("h", 0)
            )
            val captions = ArrayList<Caption>()
            val ca = o.optJSONArray("captions") ?: JSONArray()
            for (i in 0 until ca.length()) {
                val x = ca.getJSONObject(i)
                captions.add(Caption(x.optLong("id", Ids.next()), x.getLong("s"), x.getLong("e"), x.optString("t", "")))
            }
            val raw = ArrayList<Seg>()
            val ra = o.optJSONArray("raw") ?: JSONArray()
            for (i in 0 until ra.length()) {
                val x = ra.getJSONObject(i)
                raw.add(Seg(x.getLong("s"), x.getLong("e"), x.optString("t", "")))
            }
            if (video.width <= 0 || video.height <= 0) null else SavedProject(video, captions, raw)
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        file.delete()
    }
}
