package com.jasongzy.medialab

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

@Composable
internal fun EditorLayout(
    modifier: Modifier,
    preview: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val wide = maxWidth > maxHeight
        val panelHeight = minOf(288.dp, maxHeight * 0.46f)
        Layout(
            content = {
                preview()
                controls()
            }
        ) { children, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val panelWidth = if (wide) width - width / 2 else width
            val panelPixels = if (wide) height else panelHeight.roundToPx().coerceAtMost(height)
            val canvas =
                children[0].measure(
                    Constraints.fixed(
                        if (wide) width - panelWidth else width,
                        if (wide) height else height - panelPixels,
                    )
                )
            val panel = children[1].measure(Constraints.fixed(panelWidth, panelPixels))
            layout(width, height) {
                canvas.placeRelative(0, 0)
                panel.placeRelative(if (wide) canvas.width else 0, if (wide) 0 else canvas.height)
            }
        }
    }
}
