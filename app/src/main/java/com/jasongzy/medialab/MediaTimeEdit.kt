package com.jasongzy.medialab

import java.util.Locale
import kotlin.math.roundToLong

internal data class MediaTimeEdit(
    val endMs: Long,
    val startMs: Long = 0,
    val beforeMs: Long = 0,
    val afterMs: Long = 0,
    val speedPercent: Int = 100,
) {
    val durationMs get() = ((endMs - startMs) * 100 + speedPercent - 1) / speedPercent + beforeMs + afterMs

    fun validate(duration: Long) {
        require(startMs >= 0 && endMs in (startMs + 1)..duration)
        require(beforeMs in 0..Int.MAX_VALUE.toLong() && afterMs in 0..Int.MAX_VALUE.toLong())
        require(speedPercent in 10..800 && durationMs in 1..Int.MAX_VALUE.toLong())
    }

    fun audioFilter() =
        "asetpts=N/SR/TB,atrim=start=${mediaSeconds(startMs)}:end=${mediaSeconds(endMs)},asetpts=PTS-STARTPTS," +
            tempoFilter(speedPercent) +
            "adelay=$beforeMs:all=1,apad=whole_dur=${mediaSeconds(durationMs)}," +
            "asetpts=N/SR/TB,atrim=duration=${mediaSeconds(durationMs)}"
}

internal fun tempoFilter(speedPercent: Int): String = buildString {
    var speed = speedPercent / 100.0
    // Keep each stage within 0.5–2 to blend samples at high speeds.
    while (speed < 0.5 || speed > 2) {
        val factor = if (speed < 0.5) 0.5 else 2.0
        append("atempo=$factor,")
        speed /= factor
    }
    if (speed != 1.0) append("atempo=$speed,")
}

internal fun mediaSeconds(ms: Long): String = String.format(Locale.ROOT, "%.3f", ms / 1000.0)

internal fun mediaTime(text: String): Long? =
    text
        .toDoubleOrNull()
        ?.takeIf {
            it.isFinite() && it >= 0 && it <= Int.MAX_VALUE / 1000.0
        }
        ?.let { (it * 1000).roundToLong() }
