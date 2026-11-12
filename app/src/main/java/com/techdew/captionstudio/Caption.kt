package com.techdew.captionstudio

import java.util.concurrent.atomic.AtomicLong

/** One caption shown on screen between [startMs] and [endMs] (video time). */
data class Caption(val id: Long, val startMs: Long, val endMs: Long, val text: String)

/** A raw transcript segment coming from a speech-to-text provider. */
data class Seg(val startMs: Long, val endMs: Long, val text: String)

object Ids {
    private val counter = AtomicLong(System.currentTimeMillis())
    fun next(): Long = counter.incrementAndGet()
}
