package com.jasongzy.medialab

import android.content.ClipData
import android.content.Intent
import android.graphics.Rect
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as FloatRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
internal fun PhotoScreen(model: LabViewModel, state: LabState) {
    val photo = state.photo
    val picker =
        rememberLauncherForActivityResult(
            remember {
                object : ActivityResultContracts.OpenDocument() {
                    override fun createIntent(
                        context: android.content.Context,
                        input: Array<String>,
                    ) = super.createIntent(context, input).setType("image/*")
                }
            }
        ) {
            if (it != null) model.open(it, ImportType.IMAGE)
        }
    val open = { picker.launch(arrayOf("image/*")) }
    if (photo.preview == null || photo.info == null) {
        EmptyWorkspace(
            R.drawable.ic_photo,
            R.string.image_empty,
            R.string.pick_photo,
            open,
        )
        return
    }
    val info = photo.info
    val editor = checkNotNull(photo.editor)
    var tool by editor::tool
    val previewInteraction = remember { MutableInteractionSource() }
    val showOriginal by previewInteraction.collectIsPressedAsState()
    val expanding = tool == PhotoTool.EXPAND
    val cropHistory = editor.cropHistory
    val canvasHistory = editor.canvasHistory
    val frameHistory = if (expanding) canvasHistory else cropHistory
    val bounds = frameHistory.value.bounds
    val ratio = frameHistory.value.ratio
    fun setBounds(value: Rect) = frameHistory.change(frameHistory.value.copy(bounds = value))
    var background by editor::background
    val coverHistory = editor.coverHistory
    val strokes = coverHistory.value
    val resize = editor.resize
    var cleanup by editor::cleanup
    val toolHistory = if (tool == PhotoTool.COVER) coverHistory else frameHistory
    var fill by editor::fill
    var fillShape by editor::fillShape
    var brushWidth by editor::brushWidth
    var color by editor::color
    var dropper by editor::dropper
    var pixelX by editor::pixelX
    var pixelY by editor::pixelY
    var pixelAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val edit: (() -> Unit) -> Unit = { action ->
        if (info.wideColor || info.container?.gainmap != null) pixelAction = action else action()
    }
    LaunchedEffect(photo.picked, dropper, expanding) {
        if (dropper && photo.picked != null) {
            if (expanding) background = photo.picked or (255 shl 24)
            else color = photo.picked or (255 shl 24)
        }
    }
    LaunchedEffect(photo.frame, tool, dropper) {
        if (tool == PhotoTool.COLORS || dropper) model.pick(pixelX, pixelY)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        val panelHeight = minOf(288.dp, maxHeight * 0.46f)
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        photo.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "${info.width} × ${info.height}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ToolButton(R.drawable.ic_photo, R.string.change_photo, action = open)
                ToolButton(R.drawable.ic_undo, R.string.undo, photo.position > 0) { model.undo(-1) }
                ToolButton(
                    R.drawable.ic_redo,
                    R.string.redo,
                    photo.position < photo.history.lastIndex,
                ) {
                    model.undo(1)
                }
                IconButton(onClick = {}, interactionSource = previewInteraction) {
                    Icon(
                        painterResource(R.drawable.ic_visibility),
                        stringResource(R.string.hold_original),
                    )
                }
            }
            val preview: @Composable (Modifier) -> Unit = { area ->
                PhotoCanvas(
                    photo,
                    area,
                    tool,
                    showOriginal,
                    bounds,
                    ratio,
                    { frameHistory.preview(frameHistory.value.copy(bounds = it)) },
                    strokes,
                    coverHistory::preview,
                    { commit -> if (commit) toolHistory.commit() else toolHistory.cancel() },
                    toolHistory.revision,
                    if (expanding) background else color,
                    brushWidth,
                    if (fill) fillShape else CoverShape.BRUSH,
                    dropper,
                    pixelX,
                    pixelY,
                ) { x, y ->
                    pixelX = x.coerceIn(0, info.width - 1)
                    pixelY = y.coerceIn(0, info.height - 1)
                    model.pick(pixelX, pixelY)
                }
            }
            val controls: @Composable ColumnScope.() -> Unit = {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(PhotoTool.entries.size) { i ->
                        val item = PhotoTool.entries[i]
                        FilterChip(
                            tool == item,
                            {
                                tool = item
                                dropper = false
                            },
                            label = { Text(stringResource(item.label)) },
                            leadingIcon = {
                                Icon(painterResource(item.icon), null, Modifier.size(18.dp))
                            },
                        )
                    }
                }
                Column(
                    Modifier.fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (tool) {
                        PhotoTool.CROP, PhotoTool.EXPAND -> {
                            var lossless by editor::lossless
                            val ratios =
                                listOf(
                                    0f to stringResource(R.string.free_ratio),
                                    1f to "1:1",
                                    4f / 3 to "4:3",
                                    3f / 2 to "3:2",
                                    16f / 9 to "16:9",
                                    9f / 16 to "9:16",
                                )
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Box(Modifier.weight(1f)) {
                                    ChoiceChips(
                                        ratios.mapIndexed { i, pair -> i to pair.second },
                                        ratios.indexOfFirst { it.first == ratio },
                                    ) { i ->
                                        val nextRatio = ratios[i].first
                                        if (nextRatio == ratio) return@ChoiceChips
                                        val next = ratioBounds(
                                            IntRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                                            nextRatio,
                                            expanding,
                                        )
                                        frameHistory.change(FrameSelection(
                                            Rect(next.left, next.top, next.right, next.bottom), nextRatio,
                                        ))
                                    }
                                }
                                HistoryButtons(frameHistory)
                            }
                            if (expanding) {
                                ExpansionFields(bounds, info, ratio, ::setBounds)
                                ColorField(background, allowTransparent = true, pick = { dropper = !dropper }) {
                                    background = it
                                    dropper = false
                                }
                                if (dropper) Text(
                                    stringResource(R.string.tap_color),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    NumberField(
                                        R.string.width,
                                        bounds.width(),
                                        Modifier.weight(1f),
                                        1..if (ratio > 0)
                                                minOf(
                                                    info.width - bounds.left,
                                                    ((info.height - bounds.top) * ratio)
                                                        .roundToInt()
                                                        .coerceAtLeast(1),
                                                )
                                            else info.width - bounds.left,
                                    ) { w ->
                                        val h =
                                            if (ratio > 0)
                                                (w / ratio)
                                                    .roundToInt()
                                                    .coerceIn(1, info.height - bounds.top)
                                            else bounds.height()
                                        setBounds(Rect(bounds.left, bounds.top, bounds.left + w, bounds.top + h))
                                    }
                                    NumberField(
                                        R.string.height,
                                        bounds.height(),
                                        Modifier.weight(1f),
                                        1..if (ratio > 0)
                                                minOf(
                                                    info.height - bounds.top,
                                                    ((info.width - bounds.left) / ratio)
                                                        .roundToInt()
                                                        .coerceAtLeast(1),
                                                )
                                            else info.height - bounds.top,
                                    ) { h ->
                                        val w =
                                            if (ratio > 0)
                                                (h * ratio)
                                                    .roundToInt()
                                                    .coerceIn(1, info.width - bounds.left)
                                            else bounds.width()
                                        setBounds(Rect(bounds.left, bounds.top, bounds.left + w, bounds.top + h))
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    NumberField(
                                        R.string.left,
                                        bounds.left,
                                        Modifier.weight(1f),
                                        0..info.width - bounds.width(),
                                    ) { x ->
                                        setBounds(
                                            Rect(
                                                x,
                                                bounds.top,
                                                x + bounds.width(),
                                                bounds.bottom,
                                            )
                                        )
                                    }
                                    NumberField(
                                        R.string.top,
                                        bounds.top,
                                        Modifier.weight(1f),
                                        0..info.height - bounds.height(),
                                    ) { y ->
                                        setBounds(
                                            Rect(
                                                bounds.left,
                                                y,
                                                bounds.right,
                                                y + bounds.height(),
                                            )
                                        )
                                    }
                                }
                            }
                            val jpeg = info.container?.jpeg
                            val canLossless =
                                !expanding && jpeg != null &&
                                    info.orientation == 1 &&
                                    info.container.gainmap == null
                            if (canLossless) {
                                Toggle(R.string.lossless_jpeg, lossless) { lossless = it }
                                if (lossless)
                                    Text(
                                        stringResource(
                                            R.string.jpeg_alignment,
                                            jpeg.blockX,
                                            jpeg.blockY,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                            }
                            FlowRow(
                                Modifier.align(Alignment.End),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                TextButton({
                                    setBounds(Rect(bounds).apply {
                                        offsetTo((info.width - width()) / 2, (info.height - height()) / 2)
                                    })
                                }) {
                                    Text(stringResource(R.string.center_image))
                                }
                                val initialFrame = FrameSelection(Rect(0, 0, info.width, info.height))
                                TextButton(
                                    { frameHistory.change(initialFrame) },
                                    enabled = frameHistory.value != initialFrame,
                                ) {
                                    Text(stringResource(if (expanding) R.string.reset_canvas else R.string.reset_crop))
                                }
                                Button(
                                    onClick = {
                                        if (expanding) edit { model.expand(Rect(bounds), background) }
                                        else if (lossless && canLossless) {
                                            val aligned =
                                                Rect(bounds).apply {
                                                    left -= left % jpeg.blockX
                                                    top -= top % jpeg.blockY
                                                }
                                            setBounds(aligned)
                                            model.crop(aligned, true)
                                        } else edit { model.crop(bounds, false) }
                                    },
                                    enabled = !expanding || bounds != Rect(0, 0, info.width, info.height),
                                ) {
                                    Text(stringResource(R.string.apply))
                                }
                            }
                        }
                        PhotoTool.BACKGROUND -> {
                            Text(
                                stringResource(R.string.background_description),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            photo.backgroundElapsedMs?.let { elapsed ->
                                Text(
                                    stringResource(R.string.background_elapsed, elapsed / 1000.0),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                { edit { model.removeBackground() } },
                                Modifier.align(Alignment.End),
                            ) {
                                Text(stringResource(R.string.remove_background))
                            }
                        }
                        PhotoTool.COVER -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    fill,
                                    {
                                        fill = true
                                        dropper = false
                                    },
                                    label = { Text(stringResource(R.string.fill)) },
                                )
                                FilterChip(
                                    !fill,
                                    {
                                        fill = false
                                        dropper = false
                                    },
                                    label = { Text(stringResource(R.string.brush)) },
                                )
                                Spacer(Modifier.weight(1f))
                                HistoryButtons(coverHistory)
                            }
                            if (fill)
                                ChoiceChips(
                                    CoverShape.entries
                                        .filter { it != CoverShape.BRUSH }
                                        .map {
                                            it.ordinal to stringResource(it.label)
                                        },
                                    fillShape.ordinal,
                                ) {
                                    fillShape = CoverShape.entries[it]
                                    dropper = false
                                }
                            ColorField(color, allowTransparent = true, pick = { dropper = !dropper }) {
                                color = it
                                dropper = false
                            }
                            if (dropper)
                                Text(
                                    stringResource(R.string.tap_color),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            if (!fill) {
                                Text(
                                    "${stringResource(R.string.brush_size)} · ${brushWidth.roundToInt()} px",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                ValueSlider(
                                    brushWidth,
                                    1f..maxOf(100f, minOf(info.width, info.height) / 3f),
                                ) {
                                    brushWidth = it
                                }
                            }
                            FlowRow(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                TextButton(
                                    { coverHistory.change(emptyList()) },
                                    enabled = strokes.isNotEmpty(),
                                ) {
                                    Text(stringResource(R.string.clear_cover))
                                }
                                Button(
                                    { edit { model.cover(strokes) } },
                                    enabled = strokes.isNotEmpty(),
                                ) {
                                    Text(stringResource(R.string.apply))
                                }
                            }
                        }
                        PhotoTool.RESIZE ->
                            ResizePanel(model, info, resize) { w, h, method ->
                                edit { model.resize(w, h, method) }
                            }
                        PhotoTool.COLORS -> {
                            Text(
                                stringResource(R.string.tap_color),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumberField(
                                    R.string.pixel_x,
                                    pixelX,
                                    Modifier.weight(1f),
                                    0..info.width - 1,
                                ) {
                                    pixelX = it
                                    model.pick(pixelX, pixelY)
                                }
                                NumberField(
                                    R.string.pixel_y,
                                    pixelY,
                                    Modifier.weight(1f),
                                    0..info.height - 1,
                                ) {
                                    pixelY = it
                                    model.pick(pixelX, pixelY)
                                }
                            }
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val swatchWidth = (maxWidth - 18.dp) / 4
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(Modifier.width(swatchWidth)) {
                                            photo.picked?.let { ColorSwatch(it, Modifier.fillMaxWidth()) }
                                        }
                                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                                            OutlinedButton(model::palette) {
                                                Text(stringResource(R.string.analyze))
                                            }
                                        }
                                    }
                                    if (photo.colors.isNotEmpty())
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            photo.colors.forEach { ColorSwatch(it, Modifier.width(swatchWidth)) }
                                        }
                                }
                            }
                        }
                        PhotoTool.CLEAN -> {
                            CleanupOptions(info, cleanup) { cleanup = it }
                            Button(
                                {
                                    val action = {
                                        model.clean(
                                            cleanup.static,
                                            cleanup.flatten,
                                            cleanup.metadata,
                                            cleanup.keepColor,
                                        )
                                    }
                                    if (
                                        !cleanup.keepColor ||
                                            (cleanup.metadata && info.orientation != 1)
                                    )
                                        edit(action)
                                    else action()
                                },
                                Modifier.align(Alignment.End),
                            ) {
                                Text(stringResource(R.string.apply))
                            }
                        }
                        PhotoTool.INFO ->
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        listOfNotNull(
                                                info.mime,
                                                info.bytes?.let {
                                                    Formatter.formatFileSize(
                                                        LocalContext.current,
                                                        it,
                                                    )
                                                },
                                            )
                                            .joinToString(" · ")
                                    )
                                    if (info.container?.video != null)
                                        Text(stringResource(R.string.motion_photo))
                                    if (info.container?.edit != null)
                                        Text(stringResource(R.string.editable_photo))
                                    if (info.container?.gainmap != null)
                                        Text(stringResource(R.string.hdr))
                                    info.metadata.forEach { (key, value) ->
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                key,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                value.take(8192),
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                }
                            }
                        PhotoTool.EXPORT -> ExportPanel(model, state)
                    }
                }
            }

            if (wide) {
                Row(Modifier.weight(1f)) {
                    preview(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) { controls() }
                }
            } else {
                preview(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp))
                Column(Modifier.fillMaxWidth().height(panelHeight)) { controls() }
            }
        }
    }
    if (pixelAction != null)
        AlertDialog(
            onDismissRequest = { pixelAction = null },
            title = { Text(stringResource(R.string.convert_color)) },
            text = { Text(stringResource(R.string.convert_color_message)) },
            confirmButton = {
                TextButton({
                    val action = pixelAction
                    pixelAction = null
                    action?.invoke()
                }) {
                    Text(stringResource(R.string.convert_and_apply))
                }
            },
            dismissButton = {
                TextButton({ pixelAction = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
}

@Composable
private fun PhotoCanvas(
    photo: PhotoState,
    modifier: Modifier,
    tool: PhotoTool,
    showOriginal: Boolean,
    bounds: Rect,
    ratio: Float,
    setCrop: (Rect) -> Unit,
    strokes: List<Stroke>,
    setStrokes: (List<Stroke>) -> Unit,
    finishEdit: (Boolean) -> Unit,
    editRevision: Int,
    color: Int,
    width: Float,
    shape: CoverShape,
    dropper: Boolean,
    pixelX: Int,
    pixelY: Int,
    pick: (Int, Int) -> Unit,
) {
    val info = photo.info!!
    val imageSize = Size(info.width.toFloat(), info.height.toFloat())
    val expanding = tool == PhotoTool.EXPAND
    val framing = tool == PhotoTool.CROP || expanding
    val frameColor = if (expanding) MaterialTheme.colorScheme.primary.toArgb() else android.graphics.Color.WHITE
    val imageBorderColor = MaterialTheme.colorScheme.outline.toArgb()
    val initialScale = if (expanding) 0.6f else 0.9f
    val minScale = if (expanding) ImageViewport.MIN_OUTSIDE_SCALE else ImageViewport.MIN_SCALE
    var viewport by remember(photo.frame, expanding) { mutableStateOf(ImageViewport(initialScale)) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    fun fitted() = ImageViewport.fit(
        canvasSize, imageSize,
        FloatRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()),
    )
    LaunchedEffect(photo.frame, expanding, ratio, canvasSize) {
        if (expanding) viewport = fitted()
    }
    var selection by
        remember(photo.frame, tool, shape, dropper, showOriginal) {
            mutableStateOf<Stroke?>(null)
        }
    val currentStrokes by rememberUpdatedState(strokes)
    val currentBounds by rememberUpdatedState(bounds)
    val description = stringResource(R.string.photo_description)
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged {
                canvasSize = Size(it.width.toFloat(), it.height.toFloat())
                viewport = viewport.constrained(canvasSize, imageSize, outside = expanding)
            }
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = description }
                .pointerInput(
                    photo.frame,
                    tool,
                    showOriginal,
                    editRevision,
                    ratio,
                    if (dropper) 0 else color,
                    width,
                    shape,
                    dropper,
                ) {
                    if (showOriginal) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val canvas = Size(size.width.toFloat(), size.height.toFloat())
                        fun point(position: Offset): Offset {
                            val covering = tool == PhotoTool.COVER && !dropper
                            val at =
                                viewport.imagePoint(
                                    position,
                                    canvas,
                                    imageSize,
                                    edgeSnap =
                                        if (covering && shape != CoverShape.BRUSH) 1.dp.toPx()
                                        else 0f,
                                )
                            if (covering) return at
                            return Offset(
                                at.x.coerceIn(0f, imageSize.width),
                                at.y.coerceIn(0f, imageSize.height),
                            )
                        }
                        val start = point(down.position)
                        val picking = tool == PhotoTool.COLORS || dropper
                        if (picking) pick(start.x.toInt(), start.y.toInt())
                        val originalBounds = Rect(currentBounds)
                        val fit =
                            minOf(
                                canvas.width / imageSize.width,
                                canvas.height / imageSize.height,
                            ) * viewport.scale
                        val cropDrag =
                            CropDrag(
                                FloatRect(
                                    originalBounds.left.toFloat(),
                                    originalBounds.top.toFloat(),
                                    originalBounds.right.toFloat(),
                                    originalBounds.bottom.toFloat(),
                                ),
                                viewport.imagePoint(down.position, canvas, imageSize),
                                imageSize,
                                28.dp.toPx() / fit,
                                ratio,
                                FloatRect(
                                        viewport.imagePoint(Offset.Zero, canvas, imageSize),
                                        viewport.imagePoint(
                                            Offset(canvas.width, canvas.height),
                                            canvas,
                                            imageSize,
                                        ),
                                    )
                                    .let { if (expanding) it else it.intersect(FloatRect(Offset.Zero, imageSize)) },
                                expand = expanding,
                            )
                        var cropDragging = false
                        var points = listOf(start.x to start.y)
                        val before = currentStrokes
                        var transformed = false
                        var completed = false
                        try {
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.isConsumed }) break
                                val pointers = event.changes.count { it.pressed }
                                if (pointers >= 2) {
                                    transformed = true
                                    selection = null
                                    if (tool == PhotoTool.COVER) setStrokes(before)
                                    if (framing) setCrop(originalBounds)
                                    viewport =
                                        viewport.transformed(
                                            event.calculateZoom(),
                                            event.calculatePan(),
                                            event.calculateCentroid(),
                                            canvas,
                                            imageSize,
                                            outside = expanding,
                                        )
                                } else if (!transformed) {
                                    val position = event.changes.first().position
                                    val at = point(position)
                                    when {
                                        picking -> pick(at.x.toInt(), at.y.toInt())
                                        tool == PhotoTool.COVER -> {
                                            if (
                                                shape == CoverShape.RECTANGLE ||
                                                    shape == CoverShape.ELLIPSE
                                            )
                                                points = listOf(points.first(), at.x to at.y)
                                            else if (points.last() != (at.x to at.y))
                                                points = points + (at.x to at.y)
                                            val stroke = Stroke(points, color, width, shape)
                                            if (stroke.fill) {
                                                selection = stroke.takeIf { pointers > 0 }
                                                if (pointers == 0 && stroke.isDrawable)
                                                    setStrokes(before + stroke)
                                            } else setStrokes(before + stroke)
                                        }
                                        framing -> {
                                            if (
                                                (position - down.position).getDistance() >
                                                    viewConfiguration.touchSlop
                                            )
                                                cropDragging = true
                                            if (cropDragging) {
                                                val rect =
                                                    cropDrag.pixelBounds(
                                                        viewport.imagePoint(
                                                            position,
                                                            canvas,
                                                            imageSize,
                                                        )
                                                    )
                                                val r =
                                                    Rect(
                                                        rect.left,
                                                        rect.top,
                                                        rect.right,
                                                        rect.bottom,
                                                    )
                                                if (!r.isEmpty && r.width() > 0 && r.height() > 0) setCrop(r)
                                            }
                                        }
                                        else ->
                                            viewport =
                                                viewport.transformed(
                                                    1f,
                                                    event.calculatePan(),
                                                    position,
                                                    canvas,
                                                    imageSize,
                                                    outside = expanding,
                                                )
                                    }
                                }
                                completed = pointers == 0
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            selection = null
                            if (!picking && (framing || tool == PhotoTool.COVER))
                                finishEdit(completed && !transformed)
                        }
                    }
                }
        ) {
            val original = photo.original.takeIf { showOriginal }
            val preview = original ?: photo.preview!!
            val displaySize = if (original == null) imageSize
                else Size(original.width.toFloat(), original.height.toFloat())
            val fit = minOf(size.width / displaySize.width, size.height / displaySize.height) * viewport.scale
            val origin = size.center + viewport.offset - Offset(displaySize.width * fit / 2, displaySize.height * fit / 2)
            val destination = android.graphics.RectF(0f, 0f, displaySize.width, displaySize.height)
            val transparencyBounds = if (expanding && original == null) android.graphics.RectF(bounds) else destination
            val cell = 12.dp.toPx()
            clipRect(
                origin.x + transparencyBounds.left * fit,
                origin.y + transparencyBounds.top * fit,
                origin.x + transparencyBounds.right * fit,
                origin.y + transparencyBounds.bottom * fit,
            ) {
                for (y in 0..(size.height / cell).toInt()) for (x in 0..(size.width / cell).toInt()) {
                    drawRect(
                        if ((x + y) % 2 == 0) Color(0xffdddddd) else Color(0xfff5f5f5),
                        Offset(x * cell, y * cell),
                        Size(cell, cell),
                    )
                }
            }
            val canvas = drawContext.canvas.nativeCanvas
            val paint =
                android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG or
                        android.graphics.Paint.FILTER_BITMAP_FLAG
                )
            canvas.save()
            canvas.translate(origin.x, origin.y)
            canvas.scale(fit, fit)
            if (expanding && original == null) {
                canvas.save()
                canvas.clipRect(bounds)
                canvas.clipOutRect(destination)
                canvas.drawColor(color)
                canvas.restore()
            }
            val coverStrokes = if (tool == PhotoTool.COVER && original == null) strokes else emptyList()
            val imageLayer =
                if (coverStrokes.any { it.clearsPixels }) canvas.saveLayer(destination, null)
                else canvas.save()
            canvas.clipRect(destination)
            canvas.drawBitmap(preview, null, destination, paint)
            Photos.cover(canvas, coverStrokes)
            canvas.restoreToCount(imageLayer)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 1.dp.toPx() / fit
            paint.color = imageBorderColor
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4.dp.toPx() / fit, 3.dp.toPx() / fit), 0f)
            canvas.drawRect(destination, paint)
            paint.pathEffect = null
            if (original != null) {
                canvas.restore()
                return@Canvas
            }
            selection
                ?.takeIf { it.points.size > 1 }
                ?.let {
                    val path = it.path(closed = false)
                    paint.style = android.graphics.Paint.Style.STROKE
                    paint.strokeWidth = 3.dp.toPx() / fit
                    paint.color = android.graphics.Color.BLACK
                    canvas.drawPath(path, paint)
                    paint.strokeWidth = 1.dp.toPx() / fit
                    paint.color = android.graphics.Color.WHITE
                    canvas.drawPath(path, paint)
                }
            if (framing) {
                paint.style = android.graphics.Paint.Style.FILL
                paint.color = 0x80000000.toInt()
                canvas.save()
                if (!expanding) canvas.clipRect(destination)
                canvas.clipOutRect(bounds)
                canvas.drawPaint(paint)
                canvas.restore()
                paint.style = android.graphics.Paint.Style.STROKE
                paint.color = android.graphics.Color.BLACK
                paint.strokeWidth = 4.dp.toPx() / fit
                canvas.drawRect(bounds, paint)
                paint.color = frameColor
                paint.strokeWidth = 2.dp.toPx() / fit
                canvas.drawRect(bounds, paint)
                for (x in listOf(bounds.left, bounds.right)) for (y in listOf(bounds.top, bounds.bottom)) {
                    paint.style = android.graphics.Paint.Style.FILL
                    paint.color = android.graphics.Color.BLACK
                    canvas.drawCircle(x.toFloat(), y.toFloat(), 7.dp.toPx() / fit, paint)
                    paint.color = frameColor
                    canvas.drawCircle(x.toFloat(), y.toFloat(), 5.dp.toPx() / fit, paint)
                }
            }
            canvas.restore()
            if (tool == PhotoTool.COLORS || dropper) {
                val center = origin + Offset((pixelX + 0.5f) * fit, (pixelY + 0.5f) * fit)
                val radius = 9.dp.toPx()
                for ((markerColor, thickness) in
                    listOf(Color.Black to 4.dp.toPx(), Color.White to 2.dp.toPx())) {
                    drawCircle(
                        markerColor,
                        radius,
                        center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(thickness),
                    )
                    for (direction in
                        listOf(
                            Offset(1f, 0f),
                            Offset(-1f, 0f),
                            Offset(0f, 1f),
                            Offset(0f, -1f),
                        )) drawLine(
                        markerColor,
                        center + direction * (radius + 2.dp.toPx()),
                        center + direction * (radius + 7.dp.toPx()),
                        thickness,
                    )
                }
                drawCircle(Color.Black, 2.dp.toPx(), center)
                drawCircle(Color.White, 1.dp.toPx(), center)
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    MaterialTheme.shapes.large,
                )
        ) {
            ToolButton(
                R.drawable.ic_zoom_out,
                R.string.zoom_out,
                !showOriginal && viewport.scale > minScale,
            ) {
                viewport =
                    viewport.transformed(
                        0.5f,
                        Offset.Zero,
                        canvasSize.center,
                        canvasSize,
                        imageSize,
                        outside = expanding,
                    )
            }
            ToolButton(
                R.drawable.ic_zoom_in,
                R.string.zoom_in,
                !showOriginal && viewport.scale < ImageViewport.MAX_SCALE,
            ) {
                viewport =
                    viewport.transformed(2f, Offset.Zero, canvasSize.center, canvasSize, imageSize, outside = expanding)
            }
            val initialView = if (expanding) fitted() else ImageViewport(initialScale)
            ToolButton(R.drawable.ic_reset, R.string.reset_view, !showOriginal && viewport != initialView) {
                viewport = initialView
            }
        }
    }
}

@Composable
private fun HistoryButtons(history: ToolHistory<*>) {
    Row {
        ToolButton(R.drawable.ic_undo, R.string.undo, history.canUndo, history::undo)
        ToolButton(R.drawable.ic_redo, R.string.redo, history.canRedo, history::redo)
    }
}

@Composable
private fun ExpansionFields(bounds: Rect, info: PhotoInfo, ratio: Float, change: (Rect) -> Unit) {
    fun resize(width: Int, height: Int) {
        val left = bounds.left.coerceIn(info.width - width, 0)
        val top = bounds.top.coerceIn(info.height - height, 0)
        change(Rect(left, top, left + width, top + height))
    }
    val minimum = expandedSize(info.width, info.height, ratio)
    val minWidth = minimum.width
    val minHeight = minimum.height
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(R.string.width, bounds.width(), Modifier.weight(1f), minWidth..maxOf(100000, bounds.width(), minWidth)) {
            resize(it, if (ratio > 0) (it / ratio).roundToInt().coerceAtLeast(minHeight) else bounds.height())
        }
        NumberField(R.string.height, bounds.height(), Modifier.weight(1f), minHeight..maxOf(100000, bounds.height(), minHeight)) {
            resize(if (ratio > 0) (it * ratio).roundToInt().coerceAtLeast(minWidth) else bounds.width(), it)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(R.string.left_padding, -bounds.left, Modifier.weight(1f), 0..bounds.width() - info.width) {
            change(Rect(-it, bounds.top, bounds.width() - it, bounds.bottom))
        }
        NumberField(R.string.top_padding, -bounds.top, Modifier.weight(1f), 0..bounds.height() - info.height) {
            change(Rect(bounds.left, -it, bounds.right, bounds.height() - it))
        }
    }
}

@Composable
internal fun NumberField(
    label: Int,
    value: Int,
    modifier: Modifier,
    range: IntRange = 1..100000,
    onDraftChanged: (Boolean) -> Unit = {},
    change: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text,
        { input ->
            if (input.all(Char::isDigit) && input.length <= 7) {
                text = input
                onDraftChanged(input != value.toString())
                input.toIntOrNull()?.takeIf { it in range }?.let(change)
            }
        },
        modifier,
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = text.toIntOrNull() !in range,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun ResizePanel(
    model: LabViewModel,
    info: PhotoInfo,
    selection: ResizeSelection,
    apply: (Int, Int, Int) -> Unit,
) {
    val width = selection.width
    val height = selection.height
    val method = selection.method
    var preview by remember(info) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var inputRevision by remember(selection) { mutableIntStateOf(0) }
    var widthHasDraft by remember(selection, width, inputRevision) { mutableStateOf(false) }
    var heightHasDraft by remember(selection, height, inputRevision) { mutableStateOf(false) }
    key(selection, inputRevision) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(R.string.width, width, Modifier.weight(1f), onDraftChanged = { widthHasDraft = it }) {
                selection.width = it
                if (selection.lock)
                    selection.height = (it.toDouble() * info.height / info.width).roundToInt().coerceAtLeast(1)
            }
            NumberField(R.string.height, height, Modifier.weight(1f), onDraftChanged = { heightHasDraft = it }) {
                selection.height = it
                if (selection.lock)
                    selection.width = (it.toDouble() * info.width / info.height).roundToInt().coerceAtLeast(1)
            }
        }
    }
    Toggle(R.string.keep_ratio, selection.lock) { selection.lock = it }
    ChoiceChips(
        listOf(
            0 to stringResource(R.string.nearest),
            1 to stringResource(R.string.bilinear),
            2 to stringResource(R.string.bicubic),
        ),
        method,
    ) {
        selection.method = it
    }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(
            {
                selection.width = info.width
                selection.height = info.height
                // Also reset incomplete text that has not changed the numeric values.
                inputRevision++
            },
            enabled = width != info.width || height != info.height || widthHasDraft || heightHasDraft,
        ) {
            Text(stringResource(R.string.reset_resize))
        }
        TextButton({ model.previewResize(width, height, method) { preview = it } }) {
            Text(stringResource(R.string.preview))
        }
        Button({ apply(width, height, method) }) { Text(stringResource(R.string.apply)) }
    }
    preview?.let { bitmap ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(stringResource(R.string.preview)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(
                        bitmap.asImageBitmap(),
                        stringResource(R.string.preview),
                        Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    )
                    Text("$width × $height px")
                }
            },
            confirmButton = {
                TextButton({
                    preview = null
                    apply(width, height, method)
                }) {
                    Text(stringResource(R.string.apply))
                }
            },
            dismissButton = {
                TextButton({ preview = null }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

@Composable
private fun ColorField(
    color: Int,
    label: Int = R.string.color_code,
    allowTransparent: Boolean = false,
    pick: (() -> Unit)? = null,
    change: (Int) -> Unit,
) {
    var hex by remember(color) { mutableStateOf("%06X".format(color and 0xffffff)) }
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
                        .filter { allowTransparent || it.first != android.graphics.Color.TRANSPARENT }
                        .map { it.first to stringResource(it.second) },
                    color,
                    change,
                )
            }
            if (pick != null) ToolButton(R.drawable.ic_dropper, R.string.pick_from_image, action = pick)
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
private fun ColorSwatch(color: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val hex = "%06X".format(color and 0xffffff)
    val copied = stringResource(R.string.color_copied, hex)
    val foreground =
        if (android.graphics.Color.luminance(color) > 0.45f) Color.Black else Color.White
    Surface(
        onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", hex)))
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            }
        },
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = Color(color or android.graphics.Color.BLACK),
        contentColor = foreground,
    ) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp, horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "#$hex",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ExportPanel(model: LabViewModel, state: LabState) {
    var options by checkNotNull(state.photo.editor)::export
    var previewOptions by remember { mutableStateOf<ExportOptions?>(null) }
    var showPreview by remember { mutableStateOf(false) }
    LaunchedEffect(state.error) { if (state.error != null) showPreview = false }
    val context = LocalContext.current
    ChoiceChips(
        ImageFormat.entries.map { it.ordinal to stringResource(it.label) },
        options.format.ordinal,
    ) {
        options = options.copy(format = ImageFormat.entries[it])
    }
    if (options.format in listOf(ImageFormat.JPEG, ImageFormat.WEBP_LOSSY)) {
        Text(
            "${stringResource(R.string.quality)} · ${options.quality}",
            style = MaterialTheme.typography.labelMedium,
        )
        ValueSlider(options.quality.toFloat(), 1f..100f) {
            options = options.copy(quality = it.roundToInt())
        }
    }
    if (options.format == ImageFormat.JPEG && state.photo.preview?.hasAlpha() == true) {
        ColorField(options.background, R.string.transparency_background) {
            options = options.copy(background = it)
        }
        Text(
            stringResource(R.string.jpeg_background_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (
        (state.photo.info?.wideColor == true || state.photo.info?.container?.gainmap != null) &&
            (options.format != ImageFormat.ORIGINAL || !options.keepColor)
    )
        Text(stringResource(R.string.pixel_export_note), style = MaterialTheme.typography.bodySmall)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton({
            previewOptions = options
            showPreview = false
            model.previewExport(options) { showPreview = true }
        }) {
            Text(stringResource(R.string.preview))
        }
        ToolButton(R.drawable.ic_share, R.string.share) {
            model.shareImage(options) { file ->
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val intent =
                    Intent(Intent.ACTION_SEND)
                        .setType(MediaFiles.mime(file))
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .apply { clipData = ClipData.newRawUri(file.name, uri) }
                context.startActivity(Intent.createChooser(intent, null))
            }
        }
        Button({ model.saveImage(options) }) { Text(stringResource(R.string.save_copy)) }
    }
    if (showPreview && !state.busy && state.exportPreview != null && previewOptions == options)
        AlertDialog(
            onDismissRequest = { showPreview = false },
            title = { Text(stringResource(R.string.preview)) },
            text = {
                Column {
                    Image(
                        state.exportPreview.asImageBitmap(),
                        stringResource(R.string.preview),
                        Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    )
                    Text(
                        stringResource(
                            R.string.export_size,
                            Formatter.formatFileSize(context, state.exportFile!!.length()),
                        )
                    )
                }
            },
            confirmButton = {
                TextButton({ showPreview = false }) { Text(stringResource(R.string.close)) }
            },
        )
}

@Composable
private fun ValueSlider(
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

@Composable
private fun CleanupOptions(
    info: PhotoInfo,
    options: ExportOptions,
    change: (ExportOptions) -> Unit,
) {
    val motion = info.container?.video != null
    val editable = info.container?.edit != null
    if (motion)
        Toggle(R.string.extract_static, options.static) {
            change(
                options.copy(
                    static = it,
                    metadata = options.metadata && it,
                    flatten = options.flatten || it,
                )
            )
        }
    if (editable)
        Toggle(R.string.flatten_edits, options.flatten) {
            change(
                options.copy(
                    flatten = it,
                    static = options.static && it,
                    metadata = options.metadata && it,
                )
            )
        }
    Toggle(R.string.clear_metadata, options.metadata) {
        change(
            options.copy(
                metadata = it,
                static = options.static || it,
                flatten = options.flatten || it,
            )
        )
    }
    Text(
        stringResource(R.string.clear_metadata_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (motion || editable)
        Text(
            stringResource(
                if (motion && editable) R.string.cleanup_motion_edits
                else R.string.cleanup_attachments
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    Toggle(R.string.keep_color, options.keepColor) { change(options.copy(keepColor = it)) }
}
