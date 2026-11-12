package com.techdew.captionstudio

import kotlin.math.ceil

/** Pure text/timing helpers (no Android dependencies, so they are easy to unit-test). */
object CaptionText {
    private val ws = Regex("\\s+")
    private const val LINGER_MS = 200L
    private const val MIN_SHOWN_MS = 450L

    /** "MM:SS", "MM:SS.s", "H:MM:SS.s" or plain seconds ("12.5") -> milliseconds, or null. */
    fun parseTimeMs(raw: String?): Long? {
        if (raw == null) return null
        val s = raw.trim().trim('[', ']', '(', ')').replace(',', '.')
        if (s.isEmpty()) return null
        val p = s.split(':')
        return try {
            when (p.size) {
                1 -> (p[0].toDouble() * 1000.0).toLong()
                2 -> ((p[0].toDouble() * 60.0 + p[1].toDouble()) * 1000.0).toLong()
                3 -> ((p[0].toDouble() * 3600.0 + p[1].toDouble() * 60.0 + p[2].toDouble()) * 1000.0).toLong()
                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    fun wordCount(text: String): Int = text.trim().split(ws).count { it.isNotEmpty() }

    fun estimateDurationMs(text: String): Long = (wordCount(text) * 380L).coerceIn(700L, 6000L)

    /**
     * Cleans one chunk's segments: sorts, trims text, clamps to the chunk, removes overlaps,
     * rescales if the model used a longer time-scale than the chunk, and shifts by [offsetMs]
     * so the result is in whole-video time.
     */
    fun sanitizeChunk(segs: List<Seg>, chunkDurMs: Long, offsetMs: Long): List<Seg> {
        if (segs.isEmpty()) return emptyList()
        val maxEnd = segs.maxOf { maxOf(it.startMs, it.endMs) }
        val scale = if (maxEnd > chunkDurMs * 1.05 && maxEnd > 0) chunkDurMs.toDouble() / maxEnd else 1.0
        val out = ArrayList<Seg>()
        var prevEnd = 0L
        for (s in segs.sortedBy { it.startMs }) {
            val text = s.text.replace(ws, " ").trim()
            if (text.isEmpty()) continue
            var st = (s.startMs * scale).toLong().coerceIn(0L, chunkDurMs)
            var en = (s.endMs * scale).toLong().coerceIn(0L, chunkDurMs)
            if (st < prevEnd) st = prevEnd
            if (en <= st) en = minOf(chunkDurMs, st + estimateDurationMs(text))
            if (en <= st) continue
            out.add(Seg(st + offsetMs, en + offsetMs, text))
            prevEnd = en
        }
        return out
    }

    /** Splits a long segment into balanced pieces of at most [maxWords] words; time is shared by text length. */
    fun splitSeg(seg: Seg, maxWords: Int): List<Seg> {
        val words = seg.text.split(ws).filter { it.isNotEmpty() }
        if (maxWords < 1 || words.size <= maxWords) return listOf(seg)
        val groups = ceil(words.size / maxWords.toDouble()).toInt()
        val base = words.size / groups
        val extra = words.size % groups
        val pieces = ArrayList<String>()
        var idx = 0
        for (g in 0 until groups) {
            val n = base + if (g < extra) 1 else 0
            pieces.add(words.subList(idx, idx + n).joinToString(" "))
            idx += n
        }
        val totalChars = pieces.sumOf { it.length }.coerceAtLeast(1)
        val dur = seg.endMs - seg.startMs
        var t = seg.startMs
        val out = ArrayList<Seg>()
        for ((i, p) in pieces.withIndex()) {
            val end = if (i == pieces.lastIndex) seg.endMs else t + dur * p.length / totalChars
            out.add(Seg(t, end, p))
            t = end
        }
        return out
    }

    /** Final step: split by words-per-caption, let captions linger briefly, never overlap, clamp to the video. */
    fun toCaptions(raw: List<Seg>, totalDurMs: Long, maxWords: Int): List<Caption> {
        val pieces = raw.sortedBy { it.startMs }.flatMap { splitSeg(it, maxWords) }
        val out = ArrayList<Caption>()
        for ((i, s) in pieces.withIndex()) {
            val limit = if (i + 1 < pieces.size) pieces[i + 1].startMs else totalDurMs
            val start = s.startMs.coerceIn(0L, maxOf(0L, totalDurMs - 1))
            var end = minOf(s.endMs + LINGER_MS, limit)
            if (end - start < MIN_SHOWN_MS) end = minOf(start + MIN_SHOWN_MS, maxOf(limit, start + 1))
            end = end.coerceAtMost(maxOf(totalDurMs, start + 1))
            if (end <= start) continue
            out.add(Caption(Ids.next(), start, end, s.text))
        }
        return out
    }

    /** Splits one caption in two (by words, time shared by text length). Returns the original if it has < 2 words. */
    fun splitCaption(c: Caption): List<Caption> {
        val words = c.text.split(ws).filter { it.isNotEmpty() }
        if (words.size < 2) return listOf(c)
        val mid = words.size / 2
        val a = words.subList(0, mid).joinToString(" ")
        val b = words.subList(mid, words.size).joinToString(" ")
        val cut = c.startMs + (c.endMs - c.startMs) * a.length / (a.length + b.length)
        return listOf(c.copy(endMs = cut, text = a), Caption(Ids.next(), cut, c.endMs, b))
    }

    fun merge(a: Caption, b: Caption): Caption =
        a.copy(endMs = maxOf(a.endMs, b.endMs), text = (a.text.trim() + " " + b.text.trim()).trim())

    /** Index of the caption visible at [t] in a list sorted by start time, or -1. Handles small overlaps. */
    fun activeIndex(sorted: List<Caption>, t: Long): Int {
        var lo = 0
        var hi = sorted.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].startMs <= t) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        var i = ans
        var steps = 0
        while (i >= 0 && steps < 4) {
            if (sorted[i].endMs > t) return i
            i--
            steps++
        }
        return -1
    }

    fun formatTime(ms: Long): String {
        val t = maxOf(0L, ms)
        val m = t / 60000
        val s = (t % 60000) / 1000
        val d = (t % 1000) / 100
        return "%02d:%02d.%d".format(m, s, d)
    }
}
