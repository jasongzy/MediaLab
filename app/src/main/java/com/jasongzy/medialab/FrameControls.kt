package com.jasongzy.medialab

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.roundToInt

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
