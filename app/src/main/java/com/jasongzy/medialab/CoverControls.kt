package com.jasongzy.medialab

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun CoverTools(
    fill: Boolean,
    shape: CoverShape,
    setFill: (Boolean) -> Unit,
    setShape: (CoverShape) -> Unit,
    history: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(fill, { setFill(true) }, label = { Text(stringResource(R.string.fill)) })
        FilterChip(!fill, { setFill(false) }, label = { Text(stringResource(R.string.brush)) })
        Spacer(Modifier.weight(1f))
        history()
    }
    if (fill)
        ChoiceChips(
            CoverShape.entries
                .filter { it != CoverShape.BRUSH }
                .map { it.ordinal to stringResource(it.label) },
            shape.ordinal,
        ) {
            setShape(CoverShape.entries[it])
        }
}

@Composable
internal fun BrushSize(width: Float, image: IntSize, change: (Float) -> Unit) {
    Text(
        "${stringResource(R.string.brush_size)} · ${width.roundToInt()} px",
        style = MaterialTheme.typography.labelMedium,
    )
    ValueSlider(width, 1f..maxOf(100f, minOf(image.width, image.height) / 3f), change)
}

internal class StrokeDrag(
    start: Offset,
    color: Int,
    width: Float,
    shape: CoverShape,
    softEdges: Boolean = false,
) {
    private var stroke = Stroke(listOf(start.x to start.y), color, width, shape, softEdges)

    fun update(at: Offset): Stroke {
        val point = at.x to at.y
        stroke =
            stroke.copy(
                points =
                    when (stroke.shape) {
                        CoverShape.RECTANGLE,
                        CoverShape.ELLIPSE -> listOf(stroke.points.first(), point)
                        else ->
                            if (stroke.points.last() == point) stroke.points
                            else stroke.points + point
                    }
            )
        return stroke
    }
}
