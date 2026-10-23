package com.jasongzy.medialab

import android.graphics.Rect
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class FrameSelection(val bounds: Rect, val ratio: Float = 0f)

@Composable
internal fun ResizePresetChips(size: IntSize, original: IntSize, change: (IntSize) -> Unit) {
    val presets = listOf(480 to "480p", 720 to "720p", 1080 to "1080p", 1440 to "2K", 2160 to "4K")
    ChoiceChips(
        presets,
        presets.firstOrNull { resizePresetSize(original, it.first) == size }?.first ?: 0,
    ) {
        change(resizePresetSize(original, it))
    }
}

internal fun resizePresetSize(source: IntSize, shortEdge: Int): IntSize {
    val longEdge = ceil(shortEdge * 16.0 / 9).toInt()
    val width = if (source.width >= source.height) longEdge else shortEdge
    val height = if (source.width >= source.height) shortEdge else longEdge
    val scale = minOf(width.toDouble() / source.width, height.toDouble() / source.height)
    return IntSize(
        ((source.width * scale).roundToInt().coerceAtLeast(1) + 1) / 2 * 2,
        ((source.height * scale).roundToInt().coerceAtLeast(1) + 1) / 2 * 2,
    )
}

@Composable
internal fun ResizeFields(
    size: IntSize,
    original: IntSize,
    lockRatio: Boolean,
    range: IntRange = 1..100000,
    onValidityChanged: (Boolean) -> Unit,
    onDraftChanged: (Boolean) -> Unit,
    change: (IntSize) -> Unit,
) {
    var widthValid by remember { mutableStateOf(true) }
    var heightValid by remember { mutableStateOf(true) }
    var widthHasDraft by remember { mutableStateOf(false) }
    var heightHasDraft by remember { mutableStateOf(false) }
    val valid = widthValid && heightValid
    val hasDraft = widthHasDraft || heightHasDraft
    SideEffect {
        onValidityChanged(valid)
        onDraftChanged(hasDraft)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(
            R.string.width,
            size.width,
            Modifier.weight(1f),
            range,
            onValidityChanged = { widthValid = it },
            onDraftChanged = { widthHasDraft = it },
        ) { width ->
            val height =
                if (lockRatio && width != size.width)
                    (width.toDouble() * original.height / original.width)
                        .roundToInt()
                        .coerceAtLeast(1)
                else size.height
            change(IntSize(width, height))
        }
        NumberField(
            R.string.height,
            size.height,
            Modifier.weight(1f),
            range,
            onValidityChanged = { heightValid = it },
            onDraftChanged = { heightHasDraft = it },
        ) { height ->
            val width =
                if (lockRatio && height != size.height)
                    (height.toDouble() * original.width / original.height)
                        .roundToInt()
                        .coerceAtLeast(1)
                else size.width
            change(IntSize(width, height))
        }
    }
}

@Composable
internal fun FrameRatioChips(ratio: Float, change: (Float) -> Unit) {
    val ratios = listOf(0f, 1f, 4f / 3, 3f / 2, 16f / 9, 9f / 16)
    ChoiceChips(
        listOf(stringResource(R.string.free_ratio), "1:1", "4:3", "3:2", "16:9", "9:16")
            .mapIndexed { index, label -> index to label },
        ratios.indexOf(ratio),
    ) { index ->
        if (ratios[index] != ratio) change(ratios[index])
    }
}

@Composable
internal fun FrameFields(
    selection: FrameSelection,
    image: IntSize,
    expand: Boolean,
    onValidityChanged: (Boolean) -> Unit,
    preview: (FrameSelection) -> Unit,
    change: (FrameSelection) -> Unit,
    finish: (Boolean) -> Unit,
) {
    val bounds = selection.bounds
    val ratio = selection.ratio
    var dragging by remember { mutableStateOf(false) }
    var widthValid by remember { mutableStateOf(true) }
    var heightValid by remember { mutableStateOf(true) }
    var leftValid by remember { mutableStateOf(true) }
    var topValid by remember { mutableStateOf(true) }
    val valid = widthValid && heightValid && leftValid && topValid
    SideEffect { onValidityChanged(valid) }
    val startDrag = { dragging = true }
    val finishDrag: (Boolean) -> Unit = { commit ->
        dragging = false
        finish(commit)
    }
    val frame = IntRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
    val minimum = if (expand) expandedSize(image.width, image.height, ratio) else IntSize(1, 1)
    val maximum =
        if (expand)
            IntSize(
                maxOf(100000, frame.width, minimum.width),
                maxOf(100000, frame.height, minimum.height),
            )
        else croppedSize(image.width, image.height, ratio)
    fun update(next: IntRect) {
        val value = selection.copy(bounds = Rect(next.left, next.top, next.right, next.bottom))
        if (dragging) preview(value) else change(value)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(
            R.string.width,
            frame.width,
            Modifier.weight(1f),
            minimum.width..maximum.width,
            onValidityChanged = { widthValid = it },
            onDragStarted = startDrag,
            onDragFinished = finishDrag,
        ) { width ->
            val height =
                if (ratio > 0 && width != frame.width)
                    (width / ratio)
                        .roundToInt()
                        .coerceIn(
                            minimum.height,
                            if (expand) Int.MAX_VALUE else image.height,
                        )
                else frame.height
            update(resizedBounds(frame, image, IntSize(width, height), expand))
        }
        NumberField(
            R.string.height,
            frame.height,
            Modifier.weight(1f),
            minimum.height..maximum.height,
            onValidityChanged = { heightValid = it },
            onDragStarted = startDrag,
            onDragFinished = finishDrag,
        ) { height ->
            val width =
                if (ratio > 0 && height != frame.height)
                    (height * ratio)
                        .roundToInt()
                        .coerceIn(
                            minimum.width,
                            if (expand) Int.MAX_VALUE else image.width,
                        )
                else frame.width
            update(resizedBounds(frame, image, IntSize(width, height), expand))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(
            if (expand) R.string.left_padding else R.string.left,
            if (expand) -frame.left else frame.left,
            Modifier.weight(1f),
            0..if (expand) maximum.width - image.width else image.width - 1,
            onValidityChanged = { leftValid = it },
            onDragStarted = startDrag,
            onDragFinished = finishDrag,
        ) { left ->
            update(
                positionedBounds(
                    frame,
                    image,
                    if (expand) -left else left,
                    frame.top,
                    ratio,
                    expand,
                )
            )
        }
        NumberField(
            if (expand) R.string.top_padding else R.string.top,
            if (expand) -frame.top else frame.top,
            Modifier.weight(1f),
            0..if (expand) maximum.height - image.height else image.height - 1,
            onValidityChanged = { topValid = it },
            onDragStarted = startDrag,
            onDragFinished = finishDrag,
        ) { top ->
            update(
                positionedBounds(frame, image, frame.left, if (expand) -top else top, ratio, expand)
            )
        }
    }
}
