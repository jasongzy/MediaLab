package com.jasongzy.medialab

import android.widget.Toast
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.verticalScrollbar(state: ScrollableState): Modifier {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    return drawWithContent {
        drawContent()
        if (!state.canScrollForward && !state.canScrollBackward) return@drawWithContent
        val indicator = state.scrollIndicatorState ?: return@drawWithContent
        val viewport = indicator.viewportSize.toFloat()
        val content = indicator.contentSize.toFloat()
        if (viewport <= 0f || content <= viewport) return@drawWithContent
        val inset = 4.dp.toPx()
        val track = (size.height - inset * 2).coerceAtLeast(0f)
        val thumb = (track * viewport / content).coerceIn(minOf(24.dp.toPx(), track), track)
        val fraction = when {
            !state.canScrollBackward -> 0f
            !state.canScrollForward -> 1f
            else -> (indicator.scrollOffset / (content - viewport)).coerceIn(0f, 1f)
        }
        val width = 3.dp.toPx()
        drawRoundRect(
            color,
            topLeft = Offset(size.width - inset - width, inset + (track - thumb) * fraction),
            size = Size(width, thumb),
            cornerRadius = CornerRadius(width / 2),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolButton(icon: Int, label: Int, enabled: Boolean = true, action: () -> Unit) {
    TooltipBox(
        positionProvider =
            TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(stringResource(label)) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = action, enabled = enabled) {
            Icon(painterResource(icon), stringResource(label))
        }
    }
}

@Composable
internal fun HistoryButtons(history: ToolHistory<*>, action: Int, beforeChange: () -> Unit) {
    val context = LocalContext.current
    val undone = stringResource(R.string.undone, stringResource(action))
    val redone = stringResource(R.string.redone, stringResource(action))
    Row {
        ToolButton(R.drawable.ic_undo, R.string.undo, history.canUndo) {
            beforeChange()
            history.undo()
            Toast.makeText(context, undone, Toast.LENGTH_SHORT).show()
        }
        ToolButton(R.drawable.ic_redo, R.string.redo, history.canRedo) {
            beforeChange()
            history.redo()
            Toast.makeText(context, redone, Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
internal fun FlipChip(
    vertical: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = {
            Text(stringResource(if (vertical) R.string.flip_vertical else R.string.flip_horizontal))
        },
        leadingIcon = {
            Icon(
                painterResource(R.drawable.ic_flip),
                null,
                Modifier.size(18.dp).rotate(if (vertical) 90f else 0f),
            )
        },
    )
}

@Composable
internal fun ColorField(
    color: Int,
    label: Int = R.string.color_code,
    allowTransparent: Boolean = false,
    pick: (() -> Unit)? = null,
    change: (Int) -> Unit,
) {
    var hex by rememberSaveable(color) { mutableStateOf("%06X".format(color and 0xffffff)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                ChoiceChips(
                    listOf(
                            android.graphics.Color.WHITE to R.string.white,
                            android.graphics.Color.BLACK to R.string.black,
                            android.graphics.Color.TRANSPARENT to R.string.transparent,
                        )
                        .filter {
                            allowTransparent || it.first != android.graphics.Color.TRANSPARENT
                        }
                        .map { it.first to stringResource(it.second) },
                    color,
                    change,
                )
            }
            if (pick != null)
                ToolButton(R.drawable.ic_dropper, R.string.pick_from_image, action = pick)
        }
        if (color ushr 24 != 0)
            OutlinedTextField(
                hex,
                { value ->
                    if (
                        value.length <= 6 &&
                            value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                    ) {
                        hex = value
                        if (value.length == 6) change(value.toInt(16) or (255 shl 24))
                    }
                },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(label)) },
                prefix = { Text("#") },
                leadingIcon = {
                    Surface(
                        Modifier.size(20.dp),
                        color = Color(color),
                        shape = MaterialTheme.shapes.small,
                    ) {}
                },
                singleLine = true,
                isError = hex.length != 6,
            )
    }
}

@Composable
internal fun MediaDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ExportActions(
    preview: () -> Unit,
    share: () -> Unit,
    save: () -> Unit,
    enabled: Boolean = true,
    leading: @Composable () -> Unit = {},
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        TextButton(preview, enabled = enabled) { Text(stringResource(R.string.preview)) }
        ToolButton(R.drawable.ic_share, R.string.share, enabled, share)
        Button(save, enabled = enabled) { Text(stringResource(R.string.save_copy)) }
    }
}

@Composable
internal fun ChoiceChips(choices: List<Pair<Int, String>>, selected: Int, choose: (Int) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(choices.size) { i ->
            val (key, label) = choices[i]
            FilterChip(selected == key, { choose(key) }, label = { Text(label) })
        }
    }
}

@Composable
internal fun CheckOption(label: Int, value: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) =
    CheckOption(stringResource(label), value, enabled, set)

@Composable
internal fun CheckOption(label: String, value: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value, enabled = enabled, role = Role.Checkbox, onValueChange = set),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(value, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(end = 12.dp))
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
        )
    }
}

@Composable
internal fun EmptyWorkspace(
    icon: Int,
    title: Int,
    action: Int,
    open: () -> Unit,
    extraAction: @Composable () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Icon(
                painterResource(icon),
                null,
                Modifier.padding(24.dp).size(48.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(open) { Text(stringResource(action)) }
        extraAction()
    }
}

@Composable
internal fun ValueSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    change: (Float) -> Unit,
) {
    val state = rememberSliderState(value, trackRange = range)
    state.value = value
    Slider(
        state = state,
        onValueChange = {
            state.value = it
            change(it)
        },
    )
}
