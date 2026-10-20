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

internal val frameGuideFractions = listOf(0.5f, 1f / 3, 2f / 3)

internal fun expandedSize(width: Int, height: Int, ratio: Float): IntSize =
    if (ratio > 0) IntSize(
        maxOf(width, ceil(height * ratio).toInt()),
        maxOf(height, ceil(width / ratio).toInt()),
    ) else IntSize(width, height)

internal fun croppedSize(width: Int, height: Int, ratio: Float): IntSize =
    if (ratio > 0) IntSize(
        minOf(width, (height * ratio).roundToInt().coerceAtLeast(1)),
        minOf(height, (width / ratio).roundToInt().coerceAtLeast(1)),
    ) else IntSize(width, height)

internal fun ratioBounds(bounds: IntRect, ratio: Float, expand: Boolean): IntRect {
    if (ratio <= 0) return bounds
    val width = bounds.width
    val height = bounds.height
    val size = if (expand) expandedSize(width, height, ratio) else croppedSize(width, height, ratio)
    val left = bounds.left + (width - size.width) / 2
    val top = bounds.top + (height - size.height) / 2
    return IntRect(left, top, left + size.width, top + size.height)
}

internal fun resizedBounds(bounds: IntRect, image: IntSize, size: IntSize, expand: Boolean): IntRect {
    require(size.width > 0 && size.height > 0)
    val left = if (expand) bounds.left.coerceIn(image.width - size.width, 0)
        else bounds.left.coerceIn(0, image.width - size.width)
    val top = if (expand) bounds.top.coerceIn(image.height - size.height, 0)
        else bounds.top.coerceIn(0, image.height - size.height)
    return IntRect(left, top, left + size.width, top + size.height)
}

internal fun positionedBounds(
    bounds: IntRect,
    image: IntSize,
    left: Int,
    top: Int,
    ratio: Float,
    expand: Boolean,
): IntRect {
    require(if (expand) left <= 0 && top <= 0 else left in 0 until image.width && top in 0 until image.height)
    val width = if (expand) maxOf(bounds.width, image.width - left) else minOf(bounds.width, image.width - left)
    val height = if (expand) maxOf(bounds.height, image.height - top) else minOf(bounds.height, image.height - top)
    val size = if (width == bounds.width && height == bounds.height) IntSize(width, height)
        else if (expand) expandedSize(width, height, ratio) else croppedSize(width, height, ratio)
    return IntRect(left, top, left + size.width, top + size.height)
}

internal class CropDrag(
    private val original: Rect,
    private val start: Offset,
    private val image: Size,
    private val tolerance: Float,
    private val ratio: Float,
    private val visible: Rect = Rect(Offset.Zero, image),
    private val expand: Boolean = false,
    private val snapTolerance: Float = 0f,
) {
    var snapped: Set<Int> = aligned(original)
        private set

    private fun aligned(rect: Rect): Set<Int> = buildSet {
        frameGuideFractions.forEachIndexed { index, fraction ->
            if (abs(rect.left + rect.width * fraction - image.width / 2) < 0.01f) add(index)
            if (abs(rect.top + rect.height * fraction - image.height / 2) < 0.01f) add(index + 3)
        }
    }

    private data class Snap(val key: Int, val bounds: Rect, val distance: Float)

    private fun snap(rect: Rect, anchor: Offset? = null, x: Int = 1, y: Int = 1): Rect {
        if (snapTolerance <= 0) return rect
        val candidates = buildList {
            for (axis in 0..1) {
                if (anchor != null && ratio <= 0 && (if (axis == 0) x else y) == 0) continue
                val origin = if (axis == 0) rect.left else rect.top
                val extent = if (axis == 0) rect.width else rect.height
                val target = (if (axis == 0) image.width else image.height) / 2
                frameGuideFractions.forEachIndexed { index, fraction ->
                    val line = origin + extent * fraction
                    val candidate = if (anchor == null) {
                        rect.translate(if (axis == 0) Offset(target - line, 0f) else Offset(0f, target - line))
                    } else {
                        val fixed = if (axis == 0) anchor.x else anchor.y
                        if (abs(line - fixed) < 0.01f) return@forEachIndexed
                        val scale = (target - fixed) / (line - fixed)
                        if (scale <= 0 || !scale.isFinite()) return@forEachIndexed
                        val sx = if (axis == 0 || ratio > 0) scale else 1f
                        val sy = if (axis == 1 || ratio > 0) scale else 1f
                        Rect(
                            anchor.x + (rect.left - anchor.x) * sx,
                            anchor.y + (rect.top - anchor.y) * sy,
                            anchor.x + (rect.right - anchor.x) * sx,
                            anchor.y + (rect.bottom - anchor.y) * sy,
                        )
                    }
                    if (candidate.width < 1 || candidate.height < 1) return@forEachIndexed
                    val valid = if (expand)
                        candidate.left <= 0 && candidate.top <= 0 &&
                            candidate.right >= image.width && candidate.bottom >= image.height
                    else candidate.left >= 0 && candidate.top >= 0 &&
                        candidate.right <= image.width && candidate.bottom <= image.height
                    val distance = maxOf(
                        abs(candidate.left - rect.left), abs(candidate.right - rect.right),
                        abs(candidate.top - rect.top), abs(candidate.bottom - rect.bottom),
                    )
                    val key = axis * 3 + index
                    if (valid && distance <= snapTolerance * if (key in snapped) 1.75f else 1f)
                        add(Snap(key, candidate, distance))
                }
            }
        }
        fun best(options: List<Snap>) = options.minWithOrNull(
            compareBy<Snap> { it.key !in snapped }.thenBy { it.distance }
        )
        val result = if (anchor != null && ratio > 0) best(candidates)?.bounds ?: rect else {
            val horizontal = best(candidates.filter { it.key < 3 })?.bounds ?: rect
            val vertical = best(candidates.filter { it.key >= 3 })?.bounds ?: rect
            Rect(horizontal.left, vertical.top, horizontal.right, vertical.bottom)
        }
        snapped = aligned(result)
        return result
    }

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
        if (delta == Offset.Zero) {
            snapped = aligned(original)
            return original
        }
        if (!resizing && moving) {
            return snap(original.translate(
                Offset(
                    if (expand) delta.x.coerceIn(image.width - original.right, -original.left)
                    else delta.x.coerceIn(-original.left, image.width - original.right),
                    if (expand) delta.y.coerceIn(image.height - original.bottom, -original.top)
                    else delta.y.coerceIn(-original.top, image.height - original.bottom),
                )
            ))
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
            val rect = Rect(x, y, x + width, y + height)
            val dx = when {
                position.x < minOf(0f, start.x) -> -1
                position.x > maxOf(image.width, start.x) -> 1
                else -> 0
            }
            val dy = when {
                position.y < minOf(0f, start.y) -> -1
                position.y > maxOf(image.height, start.y) -> 1
                else -> 0
            }
            if (dx == 0 && dy == 0) return rect.also { snapped = aligned(it) }
            val anchor = Offset(
                if (dx < 0) rect.right else if (dx > 0) rect.left else rect.center.x,
                if (dy < 0) rect.bottom else if (dy > 0) rect.top else rect.center.y,
            )
            return snap(rect, anchor, dx, dy)
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
        return snap(Rect(left, top, left + width, top + height), anchor, x, y)
    }
}
