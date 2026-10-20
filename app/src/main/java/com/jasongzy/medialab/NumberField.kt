package com.jasongzy.medialab

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

internal class NumberDrag(
    private val initial: Int,
    private val range: IntRange,
    private val change: (Int) -> Unit,
    private val finished: (Boolean) -> Unit,
) {
    private var position = initial.toFloat()
    private var value = initial

    fun move(delta: Float) {
        position = (position + delta).coerceIn(range.first.toFloat(), range.last.toFloat())
        val next = position.roundToInt()
        if (next != value) {
            value = next
            change(next)
        }
    }

    fun finish(commit: Boolean) {
        if (!commit && value != initial) change(initial)
        finished(commit)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NumberField(
    label: Int,
    value: Int,
    modifier: Modifier,
    range: IntRange = 1..100000,
    onDraftChanged: (Boolean) -> Unit = {},
    onDragStarted: () -> Unit = {},
    onDragFinished: (Boolean) -> Unit = {},
    parse: (String) -> Int? = String::toIntOrNull,
    change: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    var dragging by remember { mutableStateOf(false) }
    val currentValue by rememberUpdatedState(value)
    val currentRange by rememberUpdatedState(range)
    val currentChange by rememberUpdatedState(change)
    val currentStarted by rememberUpdatedState(onDragStarted)
    val currentFinished by rememberUpdatedState(onDragFinished)
    val updateText by rememberUpdatedState<(Int) -> Unit> {
        text = it.toString()
        onDraftChanged(false)
    }
    val focus = LocalFocusManager.current
    val tooltip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    val hint = stringResource(R.string.drag_to_adjust)
    val description = stringResource(R.string.adjust_value, stringResource(label))
    val adjustable = range.first < range.last
    LaunchedEffect(value) {
        if (parse(text) != value) text = value.toString()
        onDraftChanged(text != value.toString())
    }
    OutlinedTextField(
        text,
        { input ->
            val digits = if (range.first < 0 && (input.startsWith('-') || input.startsWith('+'))) input.drop(1) else input
            if ((range.first < 0 || input.length <= 7) && digits.all(Char::isDigit)) {
                text = input
                onDraftChanged(input != value.toString())
                parse(input)?.takeIf { it in range }?.let(change)
            }
        },
        modifier.onFocusChanged {
            if (!it.isFocused) parse(text)?.takeIf { number -> number in range }?.let { number ->
                text = number.toString()
                onDraftChanged(false)
            }
        },
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = parse(text) !in range,
        keyboardOptions = KeyboardOptions(keyboardType = if (range.first < 0) KeyboardType.Ascii else KeyboardType.Number),
        trailingIcon = {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(hint) } },
                state = tooltip,
            ) {
                IconButton(
                    onClick = { scope.launch { tooltip.show() } },
                    enabled = adjustable,
                    modifier = Modifier.size(48.dp)
                        .semantics {
                            contentDescription = description
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                value.toFloat(), range.first.toFloat()..range.last.toFloat(),
                                (range.last - range.first - 1).coerceAtLeast(0),
                            )
                            setProgress { target ->
                                val next = target.roundToInt().coerceIn(range)
                                if (next == value) false else {
                                    updateText(next)
                                    change(next)
                                    true
                                }
                            }
                        }
                        .pointerInput(adjustable) {
                            if (!adjustable) return@pointerInput
                            var drag: NumberDrag? = null
                            fun finish(commit: Boolean) {
                                val current = drag ?: return
                                drag = null
                                dragging = false
                                current.finish(commit)
                            }
                            try {
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        // Keep linked fields anchored to the values at gesture start.
                                        val apply = currentChange
                                        val finished = currentFinished
                                        drag = NumberDrag(currentValue, currentRange, {
                                            updateText(it)
                                            apply(it)
                                        }, finished)
                                        focus.clearFocus()
                                        tooltip.dismiss()
                                        updateText(currentValue)
                                        dragging = true
                                        currentStarted()
                                    },
                                    onDragEnd = { finish(true) },
                                    onDragCancel = { finish(false) },
                                ) { _, delta -> drag?.move(delta / density) }
                            } finally {
                                finish(false)
                            }
                        },
                ) {
                    Icon(
                        painterResource(R.drawable.ic_drag_indicator), null,
                        tint = if (dragging) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
            }
        },
    )
}
