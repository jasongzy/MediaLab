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
    private val clipDurationMs get() = ((endMs - startMs) * 100 + speedPercent - 1) / speedPercent
    val durationMs get() = clipDurationMs + beforeMs + afterMs

    fun validate(duration: Long) {
        require(startMs >= 0 && endMs in (startMs + 1)..duration)
        require(beforeMs in 0..Int.MAX_VALUE.toLong() && afterMs in 0..Int.MAX_VALUE.toLong())
        require(speedPercent in 10..800 && durationMs in 1..Int.MAX_VALUE.toLong())
    }

    fun audioFilter() =
        "asetpts=N/SR/TB,atrim=start=${mediaSeconds(startMs)}:end=${mediaSeconds(endMs)},asetpts=PTS-STARTPTS," +
            audioOutputFilter()

    fun audioOutputFilter() = buildString {
        if (speedPercent != 100) {
            // atempo needs samples beyond the cut to flush even a very short clip.
            append("apad,")
            var speed = speedPercent / 100.0
            while (speed < 0.5 || speed > 2) {
                val factor = if (speed < 0.5) 0.5 else 2.0
                append("atempo=$factor,")
                speed /= factor
            }
            if (speed != 1.0) append("atempo=$speed,")
            append("atrim=duration=${mediaSeconds(clipDurationMs)},")
        }
        append("adelay=$beforeMs:all=1,apad=whole_dur=${mediaSeconds(durationMs)},")
        append("asetpts=N/SR/TB,atrim=duration=${mediaSeconds(durationMs)}")
    }
}

internal fun mediaSeconds(ms: Long): String = String.format(Locale.ROOT, "%.3f", ms / 1000.0)

internal fun mediaMilliseconds(seconds: Double): Long = Math.round(seconds * 1000)

internal fun Float.toTimelineMs(duration: Long): Long =
    if (this >= duration.toFloat()) duration else roundToLong().coerceIn(0, duration)

internal fun mediaTime(text: String): Long? =
    text
        .toDoubleOrNull()
        ?.takeIf { it in 0.0..Int.MAX_VALUE / 1000.0 }
        ?.let(::mediaMilliseconds)
