package com.jasongzy.medialab

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoxScope.CanvasLoading() {
    Box(
        Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        CircularWavyProgressIndicator(color = Color.White, trackColor = Color.White.copy(alpha = 0.2f))
    }
}

internal val viewportSaver =
    listSaver<ImageViewport, Float>(
        save = { listOf(it.scale, it.offset.x, it.offset.y) },
        restore = { ImageViewport(it[0], Offset(it[1], it[2])) },
    )
internal val canvasSizeSaver =
    listSaver<Size, Float>(
        save = { listOf(it.width, it.height) },
        restore = { Size(it[0], it[1]) },
    )

internal fun DrawScope.drawColorPicker(center: Offset) {
    val radius = 9.dp.toPx()
    for ((color, thickness) in listOf(Color.Black to 4.dp.toPx(), Color.White to 2.dp.toPx())) {
        drawCircle(color, radius, center, style = Stroke(thickness))
        for (direction in
            listOf(Offset(1f, 0f), Offset(-1f, 0f), Offset(0f, 1f), Offset(0f, -1f))) drawLine(
            color,
            center + direction * (radius + 2.dp.toPx()),
            center + direction * (radius + 7.dp.toPx()),
            thickness,
        )
    }
    drawCircle(Color.Black, 2.dp.toPx(), center)
    drawCircle(Color.White, 1.dp.toPx(), center)
}

@Composable
internal fun snapGuideAlpha(target: Int?): State<Float> {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(target) {
        if (target != null) alpha.snapTo(1f)
        alpha.animateTo(0f, tween(if (target == null) 120 else 500))
    }
    return alpha.asState()
}

internal fun frameSnapFeedback(
    previous: Set<Int>,
    current: Set<Int>,
    visible: Pair<Int?, Int?>,
    vibrate: () -> Unit,
): Pair<Int?, Int?> {
    val entered = current - previous
    if (entered.isNotEmpty()) vibrate()
    return current.firstOrNull { it < 3 }?.takeIf { it == visible.first || it in entered } to
        current.firstOrNull { it >= 3 }?.takeIf { it == visible.second || it in entered }
}

internal fun DrawScope.drawImageBorder(bounds: Rect, color: Color) {
    drawRect(
        color,
        bounds.topLeft,
        bounds.size,
        style =
            Stroke(
                1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            ),
    )
}

internal fun DrawScope.drawGuides(
    bounds: Rect,
    color: Color,
    fractions: List<Float>,
    extent: Rect = bounds,
    excludeCenter: Offset? = null,
    highlight: Offset = Offset.Zero,
    snapColor: Color = color,
) {
    for (fraction in fractions) {
        val center = fraction == 0.5f
        val x = bounds.left + bounds.width * fraction
        val y = bounds.top + bounds.height * fraction
        val effect =
            PathEffect.dashPathEffect(floatArrayOf((if (center) 6 else 3).dp.toPx(), 5.dp.toPx()))
        for (outline in listOf(true, false)) {
            val tint =
                if (outline) Color.Black.copy(alpha = 45 / 255f)
                else color.copy(alpha = (if (center) 145 else 100) / 255f)
            val stroke = (if (outline) 2 else 1).dp.toPx()
            if (excludeCenter == null || abs(x - excludeCenter.x) > 1)
                drawLine(
                    tint,
                    Offset(x, extent.top),
                    Offset(x, extent.bottom),
                    stroke,
                    pathEffect = effect,
                )
            if (excludeCenter == null || abs(y - excludeCenter.y) > 1)
                drawLine(
                    tint,
                    Offset(extent.left, y),
                    Offset(extent.right, y),
                    stroke,
                    pathEffect = effect,
                )
        }
    }
    for (vertical in listOf(true, false)) {
        val alpha = if (vertical) highlight.x else highlight.y
        if (alpha <= 0) continue
        val start =
            if (vertical) Offset(bounds.center.x, extent.top)
            else Offset(extent.left, bounds.center.y)
        val end =
            if (vertical) Offset(bounds.center.x, extent.bottom)
            else Offset(extent.right, bounds.center.y)
        drawLine(Color.Black.copy(alpha = 150 / 255f * alpha), start, end, 4.dp.toPx())
        drawLine(snapColor.copy(alpha = alpha), start, end, 2.dp.toPx())
    }
}

internal fun DrawScope.drawFrameOverlay(
    image: Rect,
    frame: Rect,
    expand: Boolean,
    frameColor: Color,
    imageColor: Color,
    snapColor: Color,
    highlight: Offset,
    showGuides: Boolean = true,
) {
    val shade = if (expand) Rect(Offset.Zero, size) else image
    clipPath(Path().apply { addRect(frame) }, ClipOp.Difference) {
        drawRect(Color.Black.copy(alpha = 0.5f), shade.topLeft, shade.size)
    }
    if (showGuides) {
        val extent =
            Rect(
                minOf(image.left, frame.left),
                minOf(image.top, frame.top),
                maxOf(image.right, frame.right),
                maxOf(image.bottom, frame.bottom),
            )
        drawGuides(
            image,
            imageColor,
            listOf(0.5f),
            extent,
            highlight = highlight,
            snapColor = snapColor,
        )
        drawGuides(frame, frameColor, frameGuideFractions, excludeCenter = image.center)
    }
    drawRect(Color.Black, frame.topLeft, frame.size, style = Stroke(4.dp.toPx()))
    drawRect(frameColor, frame.topLeft, frame.size, style = Stroke(2.dp.toPx()))
    for (x in listOf(frame.left, frame.right)) for (y in listOf(frame.top, frame.bottom)) {
        drawCircle(Color.Black, 7.dp.toPx(), Offset(x, y))
        drawCircle(frameColor, 5.dp.toPx(), Offset(x, y))
    }
}

@Composable
internal fun ViewportToolbar(
    viewport: ImageViewport,
    initial: ImageViewport,
    minScale: Float,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    zoom: (Float) -> Unit,
    reset: () -> Unit,
    content: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.background(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
            MaterialTheme.shapes.large,
        )
    ) {
        ToolButton(
            R.drawable.ic_zoom_out,
            R.string.zoom_out,
            enabled && viewport.scale > minScale,
        ) {
            zoom(0.5f)
        }
        ToolButton(
            R.drawable.ic_zoom_in,
            R.string.zoom_in,
            enabled && viewport.scale < ImageViewport.MAX_SCALE,
        ) {
            zoom(2f)
        }
        ToolButton(R.drawable.ic_reset, R.string.reset_view, enabled && viewport != initial, reset)
        content()
    }
}
