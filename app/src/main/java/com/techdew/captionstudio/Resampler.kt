package com.techdew.captionstudio

/**
 * Streaming sample-rate converter (box-filter average, so it also low-passes when downsampling).
 * Mono float in, mono float out. Constant memory: feed it buffer after buffer.
 */
class Resampler(inRate: Int, outRate: Int) {
    private val step = inRate.toDouble() / outRate.toDouble()
    private var acc = 0.0
    private var binEnd = step
    private var pos = 0.0

    /** Upper bound of output samples produced by [process] for [n] input samples. */
    fun maxOutput(n: Int): Int = (n / step).toInt() + 4

    /** Consumes [n] samples from [input]; writes results to [output] (must hold [maxOutput] samples). Returns count. */
    fun process(input: FloatArray, n: Int, output: FloatArray): Int {
        var outN = 0
        for (i in 0 until n) {
            val s = input[i].toDouble()
            var segStart = pos
            val segEnd = pos + 1.0
            while (segStart < segEnd - 1e-12) {
                val take = minOf(segEnd, binEnd) - segStart
                acc += s * take
                segStart += take
                if (segStart >= binEnd - 1e-12) {
                    output[outN++] = (acc / step).toFloat()
                    acc = 0.0
                    binEnd += step
                }
            }
            pos = segEnd
        }
        return outN
    }
}
