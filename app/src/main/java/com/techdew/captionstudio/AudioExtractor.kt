package com.techdew.captionstudio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class NoAudioException : Exception("This video has no audio track, so there is nothing to caption.")
class AudioDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One piece of the video's audio as a 16 kHz mono 16-bit WAV file. */
data class AudioChunk(val file: File, val startMs: Long, val durationMs: Long)

/**
 * Pulls the audio out of a video with the phone's own decoders (no FFmpeg), converts it to 16 kHz mono
 * on the fly and cuts it at pauses into ~4 minute WAV files. Uses constant memory, so it is safe on 2 GB phones.
 */
class AudioExtractor(private val context: Context) {

    suspend fun extract(uri: Uri, workDir: File, onProgress: (Float) -> Unit): List<AudioChunk> =
        withContext(Dispatchers.IO) {
            workDir.mkdirs()
            workDir.listFiles()?.forEach { it.delete() }
            val raw = File(workDir, "audio16k.raw")
            val energy = EnergyTrack()
            val totalSamples = try {
                decodeToRaw(this, uri, raw, energy, onProgress)
            } catch (e: NoAudioException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw AudioDecodeException("Couldn't read the audio of this video (${e.javaClass.simpleName}).", e)
            }
            if (totalSamples < SR / 2) throw AudioDecodeException("No usable audio was found in this video.")

            val spans = ChunkPlanner.plan(totalSamples, energy.frames, energy.count)
            val chunks = ArrayList<AudioChunk>()
            for ((i, span) in spans.withIndex()) {
                ensureActive()
                val f = File(workDir, "chunk_$i.wav")
                writeWav(raw, span.startSample, span.endSample, f)
                chunks.add(
                    AudioChunk(
                        file = f,
                        startMs = span.startSample * 1000L / SR,
                        durationMs = (span.endSample - span.startSample) * 1000L / SR
                    )
                )
            }
            raw.delete()
            chunks
        }

    /** Returns the number of 16 kHz samples written to [out]. */
    private fun decodeToRaw(
        scope: CoroutineScope,
        uri: Uri,
        out: File,
        energy: EnergyTrack,
        onProgress: (Float) -> Unit
    ): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIdx = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    trackIdx = i
                    trackFormat = f
                    break
                }
            }
            if (trackIdx < 0 || trackFormat == null) throw NoAudioException()
            extractor.selectTrack(trackIdx)

            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) trackFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(trackFormat, null, null, 0)
            dec.start()

            var inRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var inCh = maxOf(1, trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            var pcmFloat = false
            var resampler = Resampler(inRate, SR)
            var mono = FloatArray(8192)
            var res = FloatArray(resampler.maxOutput(mono.size))

            val stream = BufferedOutputStream(FileOutputStream(out), 1 shl 16)
            val stage = ByteArray(1 shl 16)
            var stageLen = 0
            var total = 0L
            var frameSumSq = 0.0
            var frameCount = 0

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0
            var lastProgress = -1f

            while (!outputDone) {
                scope.ensureActive()

                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        idle = 0
                        if (info.size > 0) {
                            val ob = dec.getOutputBuffer(outIdx)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            ob.order(ByteOrder.nativeOrder())

                            val frames: Int
                            if (pcmFloat) {
                                val fb = ob.asFloatBuffer()
                                frames = fb.remaining() / inCh
                                if (frames > mono.size) mono = FloatArray(frames)
                                for (f in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until inCh) s += fb.get()
                                    mono[f] = s / inCh
                                }
                            } else {
                                val sb = ob.asShortBuffer()
                                frames = sb.remaining() / inCh
                                if (frames > mono.size) mono = FloatArray(frames)
                                for (f in 0 until frames) {
                                    var s = 0
                                    for (c in 0 until inCh) s += sb.get().toInt()
                                    mono[f] = s / (inCh * 32768f)
                                }
                            }

                            val need = resampler.maxOutput(frames)
                            if (res.size < need) res = FloatArray(need)
                            val n = resampler.process(mono, frames, res)
                            for (k in 0 until n) {
                                val v = res[k].coerceIn(-1f, 1f)
                                val sample = (v * 32767f).toInt()
                                stage[stageLen++] = (sample and 0xFF).toByte()
                                stage[stageLen++] = ((sample shr 8) and 0xFF).toByte()
                                if (stageLen == stage.size) {
                                    stream.write(stage, 0, stageLen)
                                    stageLen = 0
                                }
                                frameSumSq += (v * v).toDouble()
                                frameCount++
                                if (frameCount == ChunkPlanner.FRAME_SAMPLES) {
                                    energy.add(sqrt(frameSumSq / frameCount).toFloat())
                                    frameSumSq = 0.0
                                    frameCount = 0
                                }
                            }
                            total += n

                            if (durationUs > 0) {
                                val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                                if (p - lastProgress >= 0.01f) {
                                    lastProgress = p
                                    onProgress(p)
                                }
                            }
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = dec.outputFormat
                        inRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        inCh = maxOf(1, nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                        pcmFloat = nf.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            nf.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        resampler = Resampler(inRate, SR)
                        res = FloatArray(resampler.maxOutput(mono.size))
                    }
                    else -> {
                        // INFO_TRY_AGAIN_LATER (or buffers changed): if the decoder stays silent after input ended, stop.
                        if (inputDone) {
                            idle++
                            if (idle > 600) outputDone = true
                        }
                    }
                }
            }

            if (stageLen > 0) stream.write(stage, 0, stageLen)
            stream.flush()
            stream.close()
            onProgress(1f)
            return total
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
                // ignore
            }
            try {
                codec?.release()
            } catch (e: Exception) {
                // ignore
            }
            extractor.release()
        }
    }

    private fun writeWav(raw: File, startSample: Long, endSample: Long, outFile: File) {
        val dataBytes = (endSample - startSample) * 2L
        RandomAccessFile(raw, "r").use { src ->
            FileOutputStream(outFile).use { fos ->
                val bos = BufferedOutputStream(fos, 1 shl 16)
                bos.write(wavHeader(dataBytes))
                src.seek(startSample * 2L)
                val buf = ByteArray(1 shl 16)
                var remaining = dataBytes
                while (remaining > 0) {
                    val n = src.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    bos.write(buf, 0, n)
                    remaining -= n
                }
                bos.flush()
            }
        }
    }

    private fun wavHeader(dataBytes: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt((36L + dataBytes).toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(1)            // PCM
        b.putShort(1)            // mono
        b.putInt(SR)
        b.putInt(SR * 2)
        b.putShort(2)
        b.putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataBytes.toInt())
        return b.array()
    }

    private companion object {
        const val SR = ChunkPlanner.SAMPLE_RATE
    }
}
