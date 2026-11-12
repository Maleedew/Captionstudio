package com.techdew.captionstudio

/** Growable list of per-frame (20 ms) RMS values of the 16 kHz mono audio. */
class EnergyTrack {
    var frames = FloatArray(8192)
        private set
    var count = 0
        private set

    fun add(v: Float) {
        if (count == frames.size) frames = frames.copyOf(count * 2)
        frames[count++] = v
    }
}

data class ChunkSpan(val startSample: Long, val endSample: Long)

/** Decides where to cut the audio into ~4 minute chunks: always at the quietest moment, never mid-word. */
object ChunkPlanner {
    const val SAMPLE_RATE = 16000
    const val FRAME_SAMPLES = 320                 // 20 ms
    private const val MAX_CHUNK_MS = 285_000L     // anything up to this stays in one chunk
    private const val TARGET_MS = 240_000L
    private const val SEARCH_MS = 40_000L         // look for the pause in [target-40s, target+40s]
    private const val WINDOW_FRAMES = 20          // 400 ms quiet window

    fun plan(totalSamples: Long, energy: FloatArray, frameCount: Int): List<ChunkSpan> {
        val sr = SAMPLE_RATE.toLong()
        val out = ArrayList<ChunkSpan>()
        var start = 0L
        while (true) {
            val remainingMs = (totalSamples - start) * 1000L / sr
            if (remainingMs <= MAX_CHUNK_MS) {
                out.add(ChunkSpan(start, totalSamples))
                break
            }
            val lo = start + (TARGET_MS - SEARCH_MS) * sr / 1000L
            val hi = start + (TARGET_MS + SEARCH_MS) * sr / 1000L
            val cut = quietestPoint(lo, hi, energy, frameCount)
            out.add(ChunkSpan(start, cut))
            start = cut
        }
        return out
    }

    private fun quietestPoint(lo: Long, hi: Long, energy: FloatArray, frameCount: Int): Long {
        val first = (lo / FRAME_SAMPLES).toInt()
        val last = minOf((hi / FRAME_SAMPLES).toInt(), frameCount - WINDOW_FRAMES)
        if (last <= first) return lo
        var sum = 0.0
        for (i in first until first + WINDOW_FRAMES) sum += energy[i]
        var best = sum
        var bestStart = first
        var i = first + 1
        while (i <= last) {
            sum += energy[i + WINDOW_FRAMES - 1] - energy[i - 1]
            if (sum < best) {
                best = sum
                bestStart = i
            }
            i++
        }
        return (bestStart + WINDOW_FRAMES / 2).toLong() * FRAME_SAMPLES
    }
}
