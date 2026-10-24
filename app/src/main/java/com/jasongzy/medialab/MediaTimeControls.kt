package com.jasongzy.medialab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun MediaTimeActions(
    durationMs: Long,
    resetEnabled: Boolean,
    applyEnabled: Boolean,
    reset: () -> Unit,
    apply: () -> Unit,
) {
    val duration = stringResource(R.string.video_result_duration, mediaSeconds(durationMs))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (durationMs > 60_000) "$duration (${playbackTime(durationMs)})" else duration,
            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(reset, enabled = resetEnabled) { Text(stringResource(R.string.video_reset_time)) }
        Button(apply, enabled = applyEnabled && durationMs in 1..Int.MAX_VALUE.toLong()) {
            Text(stringResource(R.string.apply))
        }
    }
}

@Composable
internal fun MediaTimeline(
    edits: MediaTimeEdit,
    durationMs: Long,
    extension: @Composable RowScope.(Float) -> Unit,
    seek: (Long) -> Unit,
    change: (Long, Long) -> Unit,
    finish: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val total = durationMs * 100 / edits.speedPercent + edits.beforeMs + edits.afterMs
    val before = if (edits.beforeMs > 0) (edits.beforeMs.toFloat() / total).coerceIn(0.025f, 0.25f) else 0f
    val after = if (edits.afterMs > 0) (edits.afterMs.toFloat() / total).coerceIn(0.025f, 0.25f) else 0f
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (before > 0f) extension(before)
            MediaRangeSlider(durationMs, edits.startMs, edits.endMs, Modifier.weight(1f - before - after),
                removed = true, seek = seek, change = change, finish = finish)
            if (after > 0f) extension(after)
        }
        actions()
    }
}

@Composable
internal fun MediaRangeSlider(
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    modifier: Modifier = Modifier,
    removed: Boolean = false,
    seek: (Long) -> Unit,
    change: (Long, Long) -> Unit,
    finish: () -> Unit,
) {
    val slider =
        remember(durationMs) {
            RangeSliderState(
                0f,
                durationMs.toFloat(),
                trackRange = 0f..durationMs.toFloat(),
            )
        }
    slider.startValue = startMs.toFloat()
    slider.endValue = endMs.toFloat()
    val removedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val removedBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    RangeSlider(
        state = slider,
        modifier = modifier.systemGestureExclusion(),
        onValueChange = { range ->
            if (range.endInclusive - range.start >= 1) {
                val start = range.start.toLong().coerceIn(0, durationMs - 1)
                val end = range.endInclusive.toLong().coerceIn(start + 1, durationMs)
                slider.startValue = start.toFloat()
                slider.endValue = end.toFloat()
                if (start != startMs) seek(start)
                else if (end != endMs) seek(end - 1)
                change(start, end)
            }
        },
        onValueChangeFinished = finish,
        track = { state ->
            SliderDefaults.Track(
                state,
                colors =
                    if (removed) SliderDefaults.colors(inactiveTrackColor = Color.Transparent)
                    else SliderDefaults.colors(),
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 0.dp,
                modifier =
                    Modifier.clip(RoundedCornerShape(50)).drawBehind {
                        if (removed) {
                            drawRect(removedBackground)
                            var x = -size.height
                            while (x < size.width) {
                                drawLine(
                                    removedColor,
                                    Offset(x, size.height),
                                    Offset(x + size.height, 0f),
                                    1.dp.toPx(),
                                )
                                x += 8.dp.toPx()
                            }
                        }
                    },
            )
        },
    )
}

@Composable
internal fun MediaTimeFields(
    edits: MediaTimeEdit,
    durationMs: Long,
    validity: (Boolean) -> Unit,
    seek: (Long) -> Unit,
    interaction: () -> Unit = {},
    change: (MediaTimeEdit, Boolean) -> Unit,
    finish: (Boolean) -> Unit,
) {
    val valid = remember { mutableStateMapOf<Int, Boolean>() }
    SideEffect { validity(valid.values.all { it }) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MediaTimeField(
            R.string.video_start,
            edits.startMs,
            0 until edits.endMs,
            Modifier.weight(1f),
            { valid[0] = it },
            interaction,
            change = { value, preview -> change(value, preview) },
            finish = finish,
        ) {
            seek(it)
            edits.copy(startMs = it)
        }
        MediaTimeField(
            R.string.video_end,
            edits.endMs,
            (edits.startMs + 1)..durationMs,
            Modifier.weight(1f),
            { valid[1] = it },
            interaction,
            change = { value, preview -> change(value, preview) },
            finish = finish,
        ) {
            seek(it - 1)
            edits.copy(endMs = it)
        }
    }
    var presetRevision by remember { mutableIntStateOf(0) }
    ChoiceChips(
        listOf(25, 50, 75, 100, 125, 150, 200, 400).map {
            it to "${(it / 100.0).toString().removeSuffix(".0")}×"
        },
        edits.speedPercent,
    ) {
        interaction()
        change(edits.copy(speedPercent = it), false)
        presetRevision++
    }
    key(presetRevision) {
        var speedDragging by remember { mutableStateOf(false) }
        NumberField(
            R.string.video_speed,
            edits.speedPercent,
            Modifier.fillMaxWidth(),
            10..800,
            onValidityChanged = { valid[4] = it },
            onDragStarted = {
                interaction()
                speedDragging = true
            },
            onDragFinished = { commit ->
                speedDragging = false
                finish(commit)
            },
            parse = { text ->
                text
                    .toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it in 0.1..8.0 }
                    ?.let { (it * 100).roundToInt() }
            },
            format = { "%.2f".format(java.util.Locale.ROOT, it / 100.0) },
            decimal = true,
            suffix = "×",
        ) {
            interaction()
            val next = edits.copy(speedPercent = it)
            change(next, speedDragging)
        }
    }
    Text(
        stringResource(R.string.video_speed_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(stringResource(R.string.video_extend_time), style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MediaTimeField(
            R.string.video_before,
            edits.beforeMs,
            0..Int.MAX_VALUE.toLong(),
            Modifier.weight(1f),
            { valid[2] = it },
            interaction,
            change = { value, preview -> change(value, preview) },
            finish = finish,
        ) {
            edits.copy(beforeMs = it)
        }
        MediaTimeField(
            R.string.video_after,
            edits.afterMs,
            0..Int.MAX_VALUE.toLong(),
            Modifier.weight(1f),
            { valid[3] = it },
            interaction,
            change = { value, preview -> change(value, preview) },
            finish = finish,
        ) {
            edits.copy(afterMs = it)
        }
    }
}

@Composable
internal fun <T> MediaTimeField(
    label: Int,
    value: Long,
    range: LongRange,
    modifier: Modifier,
    valid: (Boolean) -> Unit,
    interaction: () -> Unit,
    change: (T, Boolean) -> Unit,
    finish: (Boolean) -> Unit,
    edit: (Long) -> T,
) {
    var dragging by remember { mutableStateOf(false) }
    NumberField(
        label,
        value.toInt(),
        modifier,
        range.first.toInt()..range.last.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        onValidityChanged = valid,
        onDragStarted = {
            interaction()
            dragging = true
        },
        onDragFinished = { commit ->
            dragging = false
            finish(commit)
        },
        parse = { mediaTime(it)?.toInt() },
        format = { mediaSeconds(it.toLong()) },
        decimal = true,
        dragStep = 10f,
        suffix = "s",
    ) {
        interaction()
        val next = edit(it.toLong())
        change(next, dragging)
    }
}
