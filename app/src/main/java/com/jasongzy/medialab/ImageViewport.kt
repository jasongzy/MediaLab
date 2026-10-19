package com.jasongzy.medialab

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center

internal data class ImageViewport(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    companion object {
        const val MIN_SCALE = 0.8f
        const val MIN_OUTSIDE_SCALE = 0.000001f
        const val MAX_SCALE = 64f

        fun minimumScale(canvas: Size, topInset: Float): Float =
            if (canvas.isEmpty()) MIN_SCALE
            else (1f - 2f * topInset / canvas.height).coerceIn(MIN_OUTSIDE_SCALE, MIN_SCALE)

        fun fit(canvas: Size, image: Size, bounds: Rect): ImageViewport {
            if (canvas.isEmpty() || image.isEmpty() || bounds.isEmpty) return ImageViewport()
            val base = minOf(canvas.width / image.width, canvas.height / image.height)
            val fit = minOf(canvas.width / bounds.width, canvas.height / bounds.height) * 0.6f
            val scale = (fit / base).coerceIn(MIN_OUTSIDE_SCALE, MAX_SCALE)
            return ImageViewport(scale, (image.center - bounds.center) * (base * scale))
        }
    }

    fun imagePoint(position: Offset, canvas: Size, image: Size, edgeSnap: Float = 0f): Offset {
        val fit = minOf(canvas.width / image.width, canvas.height / image.height) * scale
        val origin =
            Offset(canvas.width / 2, canvas.height / 2) + offset -
                Offset(image.width, image.height) * (fit / 2)
        val point = (position - origin) / fit
        val tolerance = edgeSnap / fit
        fun snap(value: Float, limit: Float): Float =
            when (value) {
                in 0f..tolerance -> 0f
                in (limit - tolerance)..limit -> limit
                else -> value
            }
        return Offset(snap(point.x, image.width), snap(point.y, image.height))
    }

    fun constrained(
        canvas: Size,
        image: Size,
        outside: Boolean = false,
        minScale: Float = if (outside) MIN_OUTSIDE_SCALE else MIN_SCALE,
    ): ImageViewport {
        if (canvas.isEmpty() || image.isEmpty()) return ImageViewport()
        val zoom = scale.coerceIn(minScale, MAX_SCALE)
        if (outside) return ImageViewport(zoom, offset)
        val fit = minOf(canvas.width / image.width, canvas.height / image.height)
        val limitX = ((image.width * fit * zoom - canvas.width) / 2).coerceAtLeast(0f)
        val limitY = ((image.height * fit * zoom - canvas.height) / 2).coerceAtLeast(0f)
        return ImageViewport(
            zoom,
            Offset(offset.x.coerceIn(-limitX, limitX), offset.y.coerceIn(-limitY, limitY)),
        )
    }

    fun transformed(
        zoom: Float,
        pan: Offset,
        focus: Offset,
        canvas: Size,
        image: Size,
        outside: Boolean = false,
        minScale: Float = if (outside) MIN_OUTSIDE_SCALE else MIN_SCALE,
    ): ImageViewport {
        val current = constrained(canvas, image, outside, minScale)
        val nextScale = (current.scale * zoom).coerceIn(minScale, MAX_SCALE)
        val anchor = focus - Offset(canvas.width / 2, canvas.height / 2)
        return ImageViewport(
                nextScale,
                (current.offset - anchor) * (nextScale / current.scale) + anchor + pan,
            )
            .constrained(canvas, image, outside, minScale)
    }
}
