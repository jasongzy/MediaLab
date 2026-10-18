package com.jasongzy.medialab

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal fun expandedSize(width: Int, height: Int, ratio: Float): IntSize =
    if (ratio > 0) IntSize(
        maxOf(width, ceil(height * ratio).toInt()),
        maxOf(height, ceil(width / ratio).toInt()),
    ) else IntSize(width, height)

internal class CropDrag(
    private val original: Rect,
    private val start: Offset,
    private val image: Size,
    private val tolerance: Float,
    private val ratio: Float,
    private val visible: Rect = Rect(Offset.Zero, image),
    private val expand: Boolean = false,
) {
    private fun edge(
        value: Float,
        low: Float,
        high: Float,
        visibleLow: Float,
        visibleHigh: Float,
    ): Int {
        if (!original.inflate(tolerance).contains(start)) return 0
        val first =
            if (low in visibleLow..visibleHigh) abs(value - low) else Float.POSITIVE_INFINITY
        val last =
            if (high in visibleLow..visibleHigh) abs(value - high) else Float.POSITIVE_INFINITY
        return when {
            minOf(first, last) > tolerance -> 0
            first <= last -> -1
            else -> 1
        }
    }

    private val horizontal =
        if (original.bottom >= visible.top && original.top <= visible.bottom)
            edge(start.x, original.left, original.right, visible.left, visible.right)
        else 0
    private val vertical =
        if (original.right >= visible.left && original.left <= visible.right)
            edge(start.y, original.top, original.bottom, visible.top, visible.bottom)
        else 0
    private val resizing = horizontal != 0 || vertical != 0
    private val moving =
        original.contains(start) &&
            original != Rect(Offset.Zero, image) &&
            (expand || !(original.left < visible.left &&
                original.top < visible.top &&
                original.right > visible.right &&
                original.bottom > visible.bottom))

    fun pixelBounds(position: Offset): IntRect {
        val rect = update(position)
        return if (expand && (resizing || !moving)) IntRect(
            floor(rect.left).toInt(), floor(rect.top).toInt(),
            ceil(rect.right).toInt(), ceil(rect.bottom).toInt(),
        ) else IntRect(
            rect.left.roundToInt(), rect.top.roundToInt(),
            rect.right.roundToInt(), rect.bottom.roundToInt(),
        )
    }

    fun update(position: Offset): Rect {
        val delta = position - start
        if (delta == Offset.Zero) return original
        if (!resizing && moving) {
            return original.translate(
                Offset(
                    if (expand) delta.x.coerceIn(image.width - original.right, -original.left)
                    else delta.x.coerceIn(-original.left, image.width - original.right),
                    if (expand) delta.y.coerceIn(image.height - original.bottom, -original.top)
                    else delta.y.coerceIn(-original.top, image.height - original.bottom),
                )
            )
        }
        if (expand && !resizing) {
            val left = minOf(0f, start.x, position.x)
            val top = minOf(0f, start.y, position.y)
            val right = maxOf(image.width, start.x, position.x)
            val bottom = maxOf(image.height, start.y, position.y)
            val width = if (ratio > 0) maxOf(right - left, (bottom - top) * ratio) else right - left
            val height = if (ratio > 0) width / ratio else bottom - top
            val x = (left + right - width) / 2
            val y = (top + bottom - height) / 2
            return Rect(x, y, x + width, y + height)
        }
        val anchor =
            if (resizing)
                Offset(
                    when (horizontal) {
                        -1 -> original.right
                        1 -> original.left
                        else -> original.center.x
                    },
                    when (vertical) {
                        -1 -> original.bottom
                        1 -> original.top
                        else -> original.center.y
                    },
                )
            else Offset(start.x.coerceIn(0f, image.width), start.y.coerceIn(0f, image.height))
        val x = if (resizing) horizontal else if (position.x < anchor.x) -1 else 1
        val y = if (resizing) vertical else if (position.y < anchor.y) -1 else 1
        var width =
            if (resizing) (original.width + horizontal * delta.x).coerceAtLeast(1f)
            else abs(position.x - anchor.x)
        var height =
            if (resizing) (original.height + vertical * delta.y).coerceAtLeast(1f)
            else abs(position.y - anchor.y)
        if (ratio > 0) {
            when {
                x == 0 -> width = height * ratio
                y == 0 -> height = width / ratio
                else -> {
                    width = minOf(width, height * ratio)
                    height = width / ratio
                }
            }
        }
        if (width <= 0 || height <= 0) return original
        fun span(at: Float, extent: Float, direction: Int): Float =
            when (direction) {
                -1 -> at
                1 -> extent - at
                else -> 2 * if (expand) maxOf(at, extent - at) else minOf(at, extent - at)
            }
        val limitWidth = span(anchor.x, image.width, x)
        val limitHeight = span(anchor.y, image.height, y)
        if (ratio > 0) {
            val scale = if (expand) maxOf(1f, limitWidth / width, limitHeight / height)
                else minOf(1f, limitWidth / width, limitHeight / height)
            width *= scale
            height *= scale
        } else {
            width = if (expand) maxOf(width, limitWidth) else minOf(width, limitWidth)
            height = if (expand) maxOf(height, limitHeight) else minOf(height, limitHeight)
        }
        val left =
            anchor.x -
                when (x) {
                    -1 -> width
                    0 -> width / 2
                    else -> 0f
                }
        val top =
            anchor.y -
                when (y) {
                    -1 -> height
                    0 -> height / 2
                    else -> 0f
                }
        return Rect(left, top, left + width, top + height)
    }
}
