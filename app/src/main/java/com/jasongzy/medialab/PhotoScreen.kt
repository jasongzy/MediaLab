package com.jasongzy.medialab

import android.content.ClipData
import android.content.Intent
import android.graphics.Rect
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as FloatRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val viewportSaver = listSaver<ImageViewport, Float>(
    save = { listOf(it.scale, it.offset.x, it.offset.y) },
    restore = { ImageViewport(it[0], Offset(it[1], it[2])) },
)
private val canvasSizeSaver = listSaver<Size, Float>(
    save = { listOf(it.width, it.height) },
    restore = { Size(it[0], it[1]) },
)

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
    var menu by rememberSaveable(editor) { mutableStateOf(false) }
    var tool by editor::tool
    val toolScroll = key(photo.history.first()) { rememberLazyListState() }
    val panelScroll = key(photo.history.first()) { rememberScrollState() }
    val exportHighlight = remember(editor) { Animatable(0f) }
    val highlightColor = MaterialTheme.colorScheme.primary
    var exportRequest by remember(editor) { mutableIntStateOf(0) }
    LaunchedEffect(exportRequest) {
        if (exportRequest > 0) {
            panelScroll.scrollTo(0)
            toolScroll.animateScrollToItem(PhotoTool.EXPORT.ordinal)
            exportHighlight.snapTo(0.16f)
            exportHighlight.animateTo(0f, tween(1200))
        }
    }
    val expanding = tool == PhotoTool.EXPAND
    val cropHistory = editor.cropHistory
    val canvasHistory = editor.canvasHistory
    val frameHistory = if (expanding) canvasHistory else cropHistory
    val bounds = frameHistory.value.bounds
    val ratio = frameHistory.value.ratio
    fun setBounds(value: Rect) = frameHistory.change(frameHistory.value.copy(bounds = value))
    var background by editor::background
    var rotationBackground by editor::rotationBackground
    var cutoutBackground by editor::cutoutBackground
    val coverHistory = editor.coverHistory
    val cutout = editor.cutout.takeIf { tool == PhotoTool.BACKGROUND }
    val brushHistory = cutout?.history ?: coverHistory
    val strokes = coverHistory.value
    val resize = editor.resize
    var cleanup by editor::cleanup
    val pending = photo.pending
    val pendingDescription = stringResource(R.string.unapplied_changes)
    val toolHistory = if (tool == PhotoTool.COVER || cutout != null) brushHistory else frameHistory
    var fill by editor::fill
    var fillShape by editor::fillShape
    var brushWidth by editor::brushWidth
    var color by editor::color
    var dropper by editor::dropper
    var pixelX by editor::pixelX
    var pixelY by editor::pixelY
    LaunchedEffect(photo.picked, dropper, tool) {
        if (dropper && photo.picked != null) {
            val picked = photo.picked or (255 shl 24)
            when (tool) {
                PhotoTool.EXPAND -> background = picked
                PhotoTool.ROTATE -> rotationBackground = picked
                PhotoTool.BACKGROUND -> cutoutBackground = picked
                else -> color = picked
            }
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
                    val source = photo.history.first().info
                    FileName(
                        photo.name, photo.sourceUri, source.mime, source.bytes,
                        R.string.original_dimensions to "${source.width} × ${source.height} px",
                    )
                    Text(
                        "${info.width} × ${info.height}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ToolButton(R.drawable.ic_undo, R.string.undo, photo.position > 0) { model.undo(-1) }
                ToolButton(
                    R.drawable.ic_redo,
                    R.string.redo,
                    photo.position < photo.history.lastIndex,
                ) {
                    model.undo(1)
                }
                ToolButton(R.drawable.ic_save, R.string.export) {
                    tool = PhotoTool.EXPORT
                    dropper = false
                    exportRequest++
                }
                Box {
                    ToolButton(R.drawable.ic_more, R.string.more) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.restore_original)) },
                            enabled = photo.frame !== photo.history.first(),
                            onClick = {
                                menu = false
                                model.restoreOriginal()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.replace_image)) },
                            onClick = {
                                menu = false
                                open()
                            },
                        )
                    }
                }
            }
            val preview: @Composable (Modifier) -> Unit = { area ->
                PhotoCanvas(
                    photo,
                    area,
                    tool,
                    bounds,
                    ratio,
                    { frameHistory.preview(frameHistory.value.copy(bounds = it)) },
                    strokes,
                    coverHistory::preview,
                    { commit -> if (commit) toolHistory.commit() else toolHistory.cancel() },
                    toolHistory.revision,
                    when {
                        expanding -> background
                        tool == PhotoTool.ROTATE -> rotationBackground
                        cutout != null -> if (cutout.keep) android.graphics.Color.WHITE else android.graphics.Color.TRANSPARENT
                        else -> color
                    },
                    brushWidth,
                    if (fill && cutout == null) fillShape else CoverShape.BRUSH,
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
                    state = toolScroll,
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
                                BadgedBox(badge = {
                                    if (item in pending) Badge(
                                        Modifier.semantics { contentDescription = pendingDescription }
                                    )
                                }) {
                                    Icon(painterResource(item.icon), null, Modifier.size(18.dp))
                                }
                            },
                        )
                    }
                }
                Column(
                    Modifier.fillMaxWidth()
                        .weight(1f)
                        .drawBehind {
                            if (tool == PhotoTool.EXPORT)
                                drawRect(highlightColor.copy(alpha = exportHighlight.value))
                        }
                        .verticalScroll(panelScroll)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (tool) {
                        PhotoTool.CROP, PhotoTool.EXPAND -> {
                            var lossless by editor::lossless
                            var inputRevision by rememberSaveable(frameHistory) { mutableIntStateOf(0) }
                            var inputValid by remember(frameHistory, inputRevision) { mutableStateOf(true) }
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
                                HistoryButtons(frameHistory) {
                                    dropper = false
                                    inputRevision++
                                }
                            }
                            key(frameHistory, inputRevision) {
                                FrameFields(frameHistory, info, expanding) { inputValid = it }
                            }
                            if (expanding) {
                                ColorField(background, allowTransparent = true, pick = { dropper = !dropper }) {
                                    background = it
                                    dropper = false
                                }
                                if (dropper) Text(
                                    stringResource(R.string.tap_color),
                                    style = MaterialTheme.typography.bodySmall,
                                )
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
                                    {
                                        inputRevision++
                                        frameHistory.change(initialFrame)
                                    },
                                    enabled = frameHistory.value != initialFrame || !inputValid,
                                ) {
                                    Text(stringResource(if (expanding) R.string.reset_canvas else R.string.reset_crop))
                                }
                                Button(
                                    onClick = {
                                        if (expanding) model.expand(Rect(bounds), background)
                                        else if (lossless && canLossless) {
                                            val aligned =
                                                Rect(bounds).apply {
                                                    left -= left % jpeg.blockX
                                                    top -= top % jpeg.blockY
                                                }
                                            setBounds(aligned)
                                            model.crop(aligned, true)
                                        } else model.crop(bounds, false)
                                    },
                                    enabled = inputValid && (!expanding || bounds != Rect(0, 0, info.width, info.height)),
                                ) {
                                    Text(stringResource(R.string.apply))
                                }
                            }
                        }
                        PhotoTool.ROTATE -> RotationPanel(editor) { selection ->
                            model.rotate(selection, rotationBackground)
                        }
                        PhotoTool.BACKGROUND -> BackgroundPanel(model, photo, state.storedMask != null)
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
                                HistoryButtons(coverHistory) { dropper = false }
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
                            if (!fill) BrushSize(brushWidth, info) { brushWidth = it }
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
                                    { model.cover(strokes) },
                                    enabled = strokes.isNotEmpty(),
                                ) {
                                    Text(stringResource(R.string.apply))
                                }
                            }
                        }
                        PhotoTool.RESIZE ->
                            ResizePanel(model, info, resize) { w, h, method ->
                                model.resize(w, h, method)
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
                                    model.clean(
                                        cleanup.static,
                                        cleanup.flatten,
                                        cleanup.metadata,
                                        cleanup.keepColor,
                                    )
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
                                    PhotoDetail(
                                        stringResource(R.string.color_space),
                                        info.color.space ?: stringResource(R.string.unknown),
                                    )
                                    PhotoDetail(
                                        stringResource(R.string.wide_gamut),
                                        stringResource(when (info.color.wide) {
                                            true -> R.string.yes
                                            false -> R.string.no
                                            null -> R.string.unknown
                                        }),
                                    )
                                    info.color.model?.let { model ->
                                        PhotoDetail(
                                            stringResource(R.string.color_model),
                                            if (model == "Gray") stringResource(R.string.grayscale) else model,
                                        )
                                    }
                                    PhotoDetail(
                                        stringResource(R.string.dynamic_range),
                                        stringResource(when (info.color.hdr) {
                                            true -> R.string.hdr
                                            false -> R.string.sdr
                                            null -> R.string.unknown
                                        }),
                                    )
                                    info.metadata.filterNot { (key, _) ->
                                        (key == "ColorSpace" && info.color.space != null) ||
                                            (key == "PhotometricInterpretation" && info.color.model != null)
                                    }
                                        .forEach { (key, value) -> PhotoDetail(key, value.take(8192)) }
                                }
                            }
                        PhotoTool.EXPORT -> {
                            if (pending.isNotEmpty()) Text(
                                stringResource(
                                    R.string.unapplied_export,
                                    pending.map { stringResource(it.label) }.joinToString("\n") { "• $it" },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            ExportPanel(model, state)
                        }
                    }
                }
            }

            Layout(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                content = {
                    preview(Modifier.padding(horizontal = 12.dp))
                    Column { controls() }
                },
            ) { children, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val panelWidth = if (wide) width - width / 2 else width
                val panelHeight = if (wide) height else panelHeight.roundToPx().coerceAtMost(height)
                val canvas = children[0].measure(Constraints.fixed(
                    if (wide) width - panelWidth else width,
                    if (wide) height else height - panelHeight,
                ))
                val panel = children[1].measure(Constraints.fixed(panelWidth, panelHeight))
                layout(width, height) {
                    canvas.placeRelative(0, 0)
                    panel.placeRelative(if (wide) canvas.width else 0, if (wide) 0 else canvas.height)
                }
            }
        }
    }
}

@Composable
private fun PhotoDetail(label: String, value: String) {
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
internal fun colorLosses(losses: List<Int>): String =
    losses.map { stringResource(it) }.joinToString("\n") { "• $it" }

@Composable
private fun BackgroundPanel(model: LabViewModel, photo: PhotoState, hasStoredMask: Boolean) {
    val context = LocalContext.current
    val editor = checkNotNull(photo.editor)
    val draft = editor.cutout
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        BackgroundModel.entries.forEachIndexed { index, item ->
            SegmentedButton(
                selected = editor.backgroundModel == item,
                onClick = { editor.backgroundModel = item; editor.dropper = false },
                shape = SegmentedButtonDefaults.itemShape(index, BackgroundModel.entries.size),
            ) { Text(stringResource(item.label)) }
        }
    }
    Text(stringResource(R.string.background_description), style = MaterialTheme.typography.bodySmall)
    if (editor.backgroundModel == BackgroundModel.RMBG20) Text(
        stringResource(R.string.model_rmbg20_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (draft != null) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.align(Alignment.CenterVertically), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    draft.showMask, { draft.showMask = true; editor.dropper = false },
                    label = { Text(stringResource(R.string.mask)) },
                )
                FilterChip(
                    !draft.showMask, { draft.showMask = false; editor.dropper = false },
                    label = { Text(stringResource(R.string.mask_result)) },
                )
            }
            Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    {
                        draft.history.change(draft.history.value + MaskEdit.Harden)
                        editor.dropper = false
                        Toast.makeText(context, R.string.mask_hardened, Toast.LENGTH_SHORT).show()
                    },
                    enabled = draft.history.value.lastOrNull() != MaskEdit.Harden,
                ) { Text(stringResource(R.string.harden_mask)) }
                HistoryButtons(draft.history) { editor.dropper = false }
            }
        }
        Text(
            stringResource(if (draft.showMask) R.string.mask_hint else R.string.mask_result_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!draft.showMask) {
            ColorField(editor.cutoutBackground, allowTransparent = true, pick = { editor.dropper = !editor.dropper }) {
                editor.cutoutBackground = it
                editor.dropper = false
            }
            if (editor.dropper) Text(stringResource(R.string.tap_color), style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                draft.keep, { draft.keep = true; editor.dropper = false },
                label = { Text(stringResource(R.string.mask_keep)) },
            )
            FilterChip(
                !draft.keep, { draft.keep = false; editor.dropper = false },
                label = { Text(stringResource(R.string.mask_remove)) },
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                { draft.history.change(emptyList()); editor.dropper = false },
                enabled = draft.history.value.isNotEmpty(),
            ) {
                Text(stringResource(R.string.reset_mask))
            }
        }
        BrushSize(editor.brushWidth, checkNotNull(photo.info)) { editor.brushWidth = it; editor.dropper = false }
        Toggle(R.string.soft_edges, draft.softEdges) { draft.softEdges = it; editor.dropper = false }
    }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton({ editor.dropper = false; model.storeMask() }, enabled = draft != null) {
            Text(stringResource(R.string.store_mask))
        }
        TextButton({ editor.dropper = false; model.loadMask() }, enabled = hasStoredMask) {
            Text(stringResource(R.string.load_mask))
        }
        val infer: () -> Unit = { editor.dropper = false; model.removeBackground(editor.backgroundModel) }
        if (draft == null) Button(infer) {
            Text(stringResource(R.string.remove_background))
        } else TextButton(infer) {
            Text(stringResource(R.string.reinfer))
        }
    }
    photo.backgroundElapsedMs?.let { elapsed ->
        val time = stringResource(R.string.background_elapsed, elapsed / 1000.0)
        Text(
            if (draft == null) time else "${stringResource(draft.mask.model.label)} · $time",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (draft != null) Row(
        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        TextButton({ editor.dropper = false; model.discardBackground() }) { Text(stringResource(R.string.discard)) }
        Button({ editor.dropper = false; model.applyBackground() }) { Text(stringResource(R.string.apply)) }
    }
}

@Composable
private fun BrushSize(width: Float, info: PhotoInfo, change: (Float) -> Unit) {
    Text(
        "${stringResource(R.string.brush_size)} · ${width.roundToInt()} px",
        style = MaterialTheme.typography.labelMedium,
    )
    ValueSlider(width, 1f..maxOf(100f, minOf(info.width, info.height) / 3f), change)
}

@Composable
private fun snapGuideAlpha(target: Int?): State<Float> {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(target) {
        if (target != null) alpha.snapTo(1f)
        alpha.animateTo(0f, tween(if (target == null) 120 else 500))
    }
    return alpha.asState()
}

@Composable
private fun PhotoCanvas(
    photo: PhotoState,
    modifier: Modifier,
    tool: PhotoTool,
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
    val previewInteraction = remember { MutableInteractionSource() }
    val showOriginal by previewInteraction.collectIsPressedAsState()
    val info = photo.info!!
    val cutout = photo.editor?.cutout.takeIf { tool == PhotoTool.BACKGROUND }
    val softEdges = cutout?.softEdges == true
    val painting = (tool == PhotoTool.COVER || cutout != null) && !dropper
    val rotationHistory = photo.editor?.rotationHistory.takeIf { tool == PhotoTool.ROTATE }
    val rotation = rotationHistory?.value
    val flipX = key(photo.frame, tool) {
        animateFloatAsState(if (rotation?.flipHorizontal == true) -1f else 1f, tween(180), label = "flipX")
    }
    val flipY = key(photo.frame, tool) {
        animateFloatAsState(if (rotation?.flipVertical == true) -1f else 1f, tween(180), label = "flipY")
    }
    val previewSize = if (tool == PhotoTool.RESIZE) photo.editor?.resize?.let { IntSize(it.width, it.height) }
        else rotation?.size(info.width, info.height)
    val imageSize = Size((previewSize?.width ?: info.width).toFloat(), (previewSize?.height ?: info.height).toFloat())
    val rotationMatrix = remember(rotation, info.width, info.height) {
        rotation?.let { android.graphics.Matrix().apply { setValues(it.matrixValues(info.width, info.height)) } }
    }
    val inverseRotation = remember(rotationMatrix) {
        rotationMatrix?.let { matrix -> android.graphics.Matrix().also { check(matrix.invert(it)) } }
    }
    val expanding = tool == PhotoTool.EXPAND
    val framing = tool == PhotoTool.CROP || expanding
    val frameColor = if (expanding) MaterialTheme.colorScheme.primary.toArgb() else android.graphics.Color.WHITE
    val imageBorderColor = MaterialTheme.colorScheme.outline.toArgb()
    val guideColor = if (expanding) android.graphics.Color.WHITE else MaterialTheme.colorScheme.primary.toArgb()
    val snapColor = MaterialTheme.colorScheme.primary.toArgb()
    var snapGuides by remember(photo.frame, tool, dropper, showOriginal) {
        mutableStateOf<Pair<Int?, Int?>>(null to null)
    }
    val (verticalHighlight, horizontalHighlight) = key(photo.frame, tool, dropper, showOriginal) {
        snapGuideAlpha(snapGuides.first) to snapGuideAlpha(snapGuides.second)
    }
    val initialScale = if (expanding) 0.6f else if (rotation != null) 0.8f else 0.9f
    var viewport by rememberSaveable(photo.frame, expanding, tool == PhotoTool.ROTATE, stateSaver = viewportSaver) {
        mutableStateOf(ImageViewport(initialScale))
    }
    var fittedRatio by rememberSaveable(photo.frame, expanding) { mutableStateOf<Float?>(null) }
    var canvasSize by rememberSaveable(stateSaver = canvasSizeSaver) { mutableStateOf(Size.Zero) }
    var toolbarSize by remember { mutableStateOf(IntSize.Zero) }
    var rotationDragging by remember(photo.frame, tool) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val toolbarInset = toolbarSize.height + with(LocalDensity.current) { 8.dp.toPx() }
    val minScale = if (expanding) ImageViewport.MIN_OUTSIDE_SCALE
        else ImageViewport.minimumScale(canvasSize, toolbarInset)
    fun fitted() = ImageViewport.fit(
        canvasSize, imageSize,
        FloatRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()),
    )
    LaunchedEffect(photo.frame, expanding, ratio, canvasSize, minScale, imageSize, rotationDragging) {
        if (!canvasSize.isEmpty() && !rotationDragging) {
            viewport = if (expanding && fittedRatio != ratio) fitted()
                else viewport.constrained(canvasSize, imageSize, outside = expanding, minScale = minScale)
            fittedRatio = ratio
        }
    }
    val rotationHandle = if (rotationMatrix != null && !dropper && !showOriginal && !canvasSize.isEmpty()) {
        val point = floatArrayOf(info.width / 2f, 0f)
        rotationMatrix.mapPoints(point)
        val fit = minOf(canvasSize.width / imageSize.width, canvasSize.height / imageSize.height) * viewport.scale
        val center = canvasSize.center + viewport.offset
        val edge = center + (Offset(point[0], point[1]) - imageSize.center) * fit
        val vector = floatArrayOf(0f, -1f)
        rotationMatrix.mapVectors(vector)
        val direction = Offset(vector[0], vector[1])
        val gap = with(LocalDensity.current) { 28.dp.toPx() }
        val margin = with(LocalDensity.current) { 24.dp.toPx() }
        val desired = edge + direction * gap
        val x = desired.x.coerceIn(margin, maxOf(margin, canvasSize.width - margin))
        val top = if (x + margin > canvasSize.width - toolbarSize.width) toolbarInset + margin else margin
        val bottom = maxOf(margin, canvasSize.height - margin)
        val y = desired.y.coerceIn(minOf(top, bottom), bottom)
        edge to Offset(x, y)
    } else null
    var selection by
        remember(photo.frame, tool, shape, dropper, showOriginal) {
            mutableStateOf<Stroke?>(null)
        }
    var brushPoint by remember(photo.frame, tool, dropper, showOriginal) { mutableStateOf<Offset?>(null) }
    val currentStrokes by rememberUpdatedState(strokes)
    val currentBounds by rememberUpdatedState(bounds)
    val currentRotation by rememberUpdatedState(rotation)
    val currentImageSize by rememberUpdatedState(imageSize)
    val currentInverseRotation by rememberUpdatedState(inverseRotation)
    val currentRotationHandle by rememberUpdatedState(rotationHandle)
    val description = stringResource(R.string.photo_description)
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged {
                val next = Size(it.width.toFloat(), it.height.toFloat())
                if (!canvasSize.isEmpty() && !next.isEmpty()) {
                    val previousFit = minOf(canvasSize.width / imageSize.width, canvasSize.height / imageSize.height)
                    val nextFit = minOf(next.width / imageSize.width, next.height / imageSize.height)
                    viewport = viewport.copy(offset = viewport.offset * (nextFit / previousFit))
                }
                canvasSize = next
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
                    cutout,
                    softEdges,
                    minScale,
                    if (rotation == null) imageSize else null,
                    rotationHistory?.revision,
                    rotation?.flipHorizontal,
                    rotation?.flipVertical,
                    rotation?.lockCanvas,
                ) {
                    if (showOriginal) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val canvas = Size(size.width.toFloat(), size.height.toFloat())
                        val imageSize = currentImageSize
                        val rotation = currentRotation
                        val inverseRotation = currentInverseRotation
                        val picking = tool == PhotoTool.COLORS || dropper
                        val rotationDrag = currentRotationHandle?.takeIf {
                            (down.position - it.second).getDistance() <= 24.dp.toPx()
                        }?.let {
                            focus.clearFocus()
                            RotationDrag(checkNotNull(rotation), down.position, canvas.center + viewport.offset, 16.dp.toPx())
                        }
                        fun point(position: Offset): Offset {
                            val at =
                                viewport.imagePoint(
                                    position,
                                    canvas,
                                    imageSize,
                                    edgeSnap =
                                        if (painting && shape != CoverShape.BRUSH) 1.dp.toPx()
                                        else 0f,
                                )
                            if (painting) return at
                            if (picking && inverseRotation != null) {
                                val pixel = floatArrayOf(at.x, at.y)
                                inverseRotation.mapPoints(pixel)
                                return Offset(
                                    pixel[0].coerceIn(0f, info.width.toFloat()),
                                    pixel[1].coerceIn(0f, info.height.toFloat()),
                                )
                            }
                            return Offset(
                                at.x.coerceIn(0f, imageSize.width),
                                at.y.coerceIn(0f, imageSize.height),
                            )
                        }
                        val start = point(down.position)
                        if (painting && shape == CoverShape.BRUSH) brushPoint = start
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
                                snapTolerance = 6.dp.toPx() / fit,
                            )
                        var cropDragging = false
                        var points = listOf(start.x to start.y)
                        val before = currentStrokes
                        val beforeMask = cutout?.history?.value.orEmpty()
                        fun brush(stroke: Stroke? = null) {
                            if (cutout != null) cutout.history.preview(beforeMask + listOfNotNull(stroke))
                            else setStrokes(before + listOfNotNull(stroke))
                        }
                        var transformed = false
                        var completed = false
                        try {
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.isConsumed }) break
                                val pointers = event.changes.count { it.pressed }
                                if (pointers >= 2) {
                                    snapGuides = null to null
                                    if (!transformed && rotationDrag != null) {
                                        rotationHistory?.cancel()
                                        rotationDragging = false
                                    }
                                    transformed = true
                                    selection = null
                                    brushPoint = null
                                    if (painting) brush()
                                    if (framing) setCrop(originalBounds)
                                    viewport =
                                        viewport.transformed(
                                            event.calculateZoom(),
                                            event.calculatePan(),
                                            event.calculateCentroid(),
                                            canvas,
                                            imageSize,
                                            outside = expanding,
                                            minScale = minScale,
                                        )
                                } else if (!transformed) {
                                    val position = event.changes.first().position
                                    val at = point(position)
                                    when {
                                        picking -> pick(at.x.toInt(), at.y.toInt())
                                        rotationDrag != null -> {
                                            if ((position - down.position).getDistance() > viewConfiguration.touchSlop)
                                                rotationDragging = true
                                            if (rotationDragging) {
                                                val previous = rotationDrag.snapAngle
                                                rotationHistory?.preview(rotationDrag.update(position))
                                                val snapped = rotationDrag.snapAngle
                                                if (snapped != previous) {
                                                    snapGuides = snapped?.takeIf { it % 180 == 0 } to
                                                        snapped?.takeIf { it % 180 != 0 }
                                                    if (snapped != null)
                                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                                }
                                            }
                                        }
                                        painting -> {
                                            if (shape == CoverShape.BRUSH) brushPoint = at
                                            if (
                                                shape == CoverShape.RECTANGLE ||
                                                    shape == CoverShape.ELLIPSE
                                            )
                                                points = listOf(points.first(), at.x to at.y)
                                            else if (points.last() != (at.x to at.y))
                                                points = points + (at.x to at.y)
                                            val stroke = Stroke(points, color, width, shape, softEdges)
                                            if (stroke.fill) {
                                                selection = stroke.takeIf { pointers > 0 }
                                                if (pointers == 0 && stroke.isDrawable)
                                                    brush(stroke)
                                            } else brush(stroke)
                                        }
                                        framing -> {
                                            if (
                                                (position - down.position).getDistance() >
                                                    viewConfiguration.touchSlop
                                            )
                                                cropDragging = true
                                            if (cropDragging) {
                                                val previous = cropDrag.snapped
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
                                                if (!r.isEmpty && r.width() > 0 && r.height() > 0) {
                                                    setCrop(r)
                                                    val entered = cropDrag.snapped - previous
                                                    snapGuides = cropDrag.snapped.firstOrNull { it < 3 }
                                                        ?.takeIf { it == snapGuides.first || it in entered } to
                                                        cropDrag.snapped.firstOrNull { it >= 3 }
                                                            ?.takeIf { it == snapGuides.second || it in entered }
                                                    if (entered.isNotEmpty())
                                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                                }
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
                                                    minScale = minScale,
                                                )
                                    }
                                }
                                completed = pointers == 0
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            snapGuides = null to null
                            selection = null
                            brushPoint = null
                            rotationDragging = false
                            if (rotationDrag != null) {
                                if (completed && !transformed) rotationHistory?.commit() else rotationHistory?.cancel()
                            }
                            if (!picking && (framing || painting))
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
            if (rotation != null && original == null) {
                val layer = canvas.saveLayer(destination, null)
                if (!dropper) canvas.scale(
                    flipX.value * if (rotation.flipHorizontal) -1f else 1f,
                    flipY.value * if (rotation.flipVertical) -1f else 1f,
                    destination.centerX(), destination.centerY(),
                )
                Photos.drawRotated(canvas, preview, rotation, info.width, info.height, color)
                canvas.restoreToCount(layer)
            } else if (cutout != null && original == null) {
                Photos.drawCutout(canvas, cutout, destination, checkNotNull(photo.editor).cutoutBackground)
            } else {
                val coverStrokes = if (tool == PhotoTool.COVER && original == null) strokes else emptyList()
                val imageLayer =
                    if (coverStrokes.any { it.clearsPixels }) canvas.saveLayer(destination, null)
                    else canvas.save()
                canvas.clipRect(destination)
                canvas.drawBitmap(preview, null, destination, paint)
                Photos.cover(canvas, coverStrokes)
                canvas.restoreToCount(imageLayer)
            }
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
            fun guides(rect: android.graphics.RectF, tint: Int, grid: Boolean) {
                paint.style = android.graphics.Paint.Style.STROKE
                val left = if (grid) rect.left else minOf(rect.left, bounds.left.toFloat())
                val top = if (grid) rect.top else minOf(rect.top, bounds.top.toFloat())
                val right = if (grid) rect.right else maxOf(rect.right, bounds.right.toFloat())
                val bottom = if (grid) rect.bottom else maxOf(rect.bottom, bounds.bottom.toFloat())
                val fractions = when {
                    !grid -> listOf(0.5f)
                    rotation != null -> listOf(0.25f, 0.5f, 0.75f)
                    else -> frameGuideFractions
                }
                for (fraction in fractions) {
                    val center = fraction == 0.5f
                    paint.pathEffect = android.graphics.DashPathEffect(
                        floatArrayOf((if (center) 6 else 3).dp.toPx() / fit, 5.dp.toPx() / fit), 0f,
                    )
                    val x = rect.left + rect.width() * fraction
                    val y = rect.top + rect.height() * fraction
                    for (outline in listOf(true, false)) {
                        paint.color = if (outline) android.graphics.Color.BLACK else tint
                        paint.alpha = if (outline) 45 else if (center) 145 else 100
                        paint.strokeWidth = (if (outline) 2 else 1).dp.toPx() / fit
                        if (!framing || !grid || abs(x - destination.centerX()) * fit > 1)
                            canvas.drawLine(x, top, x, bottom, paint)
                        if (!framing || !grid || abs(y - destination.centerY()) * fit > 1)
                            canvas.drawLine(left, y, right, y, paint)
                    }
                }
                paint.pathEffect = null
                if (!grid || rotation != null) {
                    for (vertical in listOf(true, false)) {
                        val alpha = if (vertical) verticalHighlight.value else horizontalHighlight.value
                        if (alpha <= 0f) continue
                        for (outline in listOf(true, false)) {
                            paint.color = if (outline) android.graphics.Color.BLACK else snapColor
                            paint.alpha = ((if (outline) 150 else 255) * alpha).roundToInt()
                            paint.strokeWidth = (if (outline) 4 else 2).dp.toPx() / fit
                            if (vertical) canvas.drawLine(rect.centerX(), top, rect.centerX(), bottom, paint)
                            else canvas.drawLine(left, rect.centerY(), right, rect.centerY(), paint)
                        }
                    }
                }
                paint.alpha = 255
            }
            if (rotation != null && !dropper) guides(destination, android.graphics.Color.WHITE, grid = true)
            brushPoint?.let { point ->
                paint.color = android.graphics.Color.BLACK
                paint.strokeWidth = 3.dp.toPx() / fit
                canvas.drawCircle(point.x, point.y, width / 2, paint)
                paint.color = android.graphics.Color.WHITE
                paint.strokeWidth = 1.dp.toPx() / fit
                canvas.drawCircle(point.x, point.y, width / 2, paint)
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
                if (!dropper) {
                    guides(destination, guideColor, grid = false)
                    guides(android.graphics.RectF(bounds), frameColor, grid = true)
                }
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
            rotationHandle?.let { (edge, handle) ->
                drawLine(Color.Black.copy(alpha = 0.6f), edge, handle, 3.dp.toPx())
                drawLine(Color.White, edge, handle, 1.dp.toPx())
            }
            if (tool == PhotoTool.COLORS || dropper) {
                val pixel = floatArrayOf(pixelX + 0.5f, pixelY + 0.5f)
                rotationMatrix?.mapPoints(pixel)
                val center = origin + Offset(pixel[0] * fit, pixel[1] * fit)
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
        rotationHandle?.let { (_, handle) ->
            val size = with(LocalDensity.current) { 48.dp.toPx() }
            val description = stringResource(R.string.drag_to_rotate)
            Box(
                Modifier.offset { IntOffset((handle.x - size / 2).roundToInt(), (handle.y - size / 2).roundToInt()) }
                    .size(48.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                        progressBarRangeInfo = ProgressBarRangeInfo(checkNotNull(rotation).angle.toFloat(), -180f..180f, 359)
                        setProgress { angle ->
                            val next = rotationAngle(angle.roundToInt())
                            if (next == rotation.angle) false else {
                                checkNotNull(rotationHistory).change(rotation.copy(angle = next))
                                true
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_rotate), null,
                    Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(6.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd)
                .onSizeChanged { toolbarSize = it }
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
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
                        minScale = minScale,
                    )
            }
            ToolButton(
                R.drawable.ic_zoom_in,
                R.string.zoom_in,
                !showOriginal && viewport.scale < ImageViewport.MAX_SCALE,
            ) {
                viewport =
                    viewport.transformed(
                        2f, Offset.Zero, canvasSize.center, canvasSize, imageSize,
                        outside = expanding, minScale = minScale,
                    )
            }
            val initialView = if (expanding) fitted() else ImageViewport(initialScale)
            ToolButton(R.drawable.ic_reset, R.string.reset_view, !showOriginal && viewport != initialView) {
                viewport = initialView
            }
            IconButton(onClick = {}, interactionSource = previewInteraction) {
                Icon(
                    painterResource(R.drawable.ic_visibility),
                    stringResource(R.string.hold_original),
                )
            }
        }
    }
}

@Composable
private fun HistoryButtons(history: ToolHistory<*>, beforeChange: () -> Unit) {
    Row {
        ToolButton(R.drawable.ic_undo, R.string.undo, history.canUndo) { beforeChange(); history.undo() }
        ToolButton(R.drawable.ic_redo, R.string.redo, history.canRedo) { beforeChange(); history.redo() }
    }
}

@Composable
private fun FrameFields(
    history: ToolHistory<FrameSelection>,
    info: PhotoInfo,
    expand: Boolean,
    onValidityChanged: (Boolean) -> Unit,
) {
    val selection = history.value
    val bounds = selection.bounds
    val ratio = selection.ratio
    var dragging by remember { mutableStateOf(false) }
    var widthValid by remember { mutableStateOf(true) }
    var heightValid by remember { mutableStateOf(true) }
    var leftValid by remember { mutableStateOf(true) }
    var topValid by remember { mutableStateOf(true) }
    val valid = widthValid && heightValid && leftValid && topValid
    SideEffect { onValidityChanged(valid) }
    val startDrag = { dragging = true }
    val finishDrag: (Boolean) -> Unit = { commit ->
        dragging = false
        if (commit) history.commit() else history.cancel()
    }
    val frame = IntRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
    val image = IntSize(info.width, info.height)
    val minimum = if (expand) expandedSize(image.width, image.height, ratio) else IntSize(1, 1)
    val maximum = if (expand) IntSize(
        maxOf(100000, frame.width, minimum.width),
        maxOf(100000, frame.height, minimum.height),
    ) else croppedSize(image.width, image.height, ratio)
    fun update(next: IntRect) {
        val value = selection.copy(bounds = Rect(next.left, next.top, next.right, next.bottom))
        if (dragging) history.preview(value) else history.change(value)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(
            R.string.width, frame.width, Modifier.weight(1f), minimum.width..maximum.width,
            onValidityChanged = { widthValid = it },
            onDragStarted = startDrag, onDragFinished = finishDrag,
        ) { width ->
            val height = if (ratio > 0 && width != frame.width) (width / ratio).roundToInt().coerceIn(
                minimum.height, if (expand) Int.MAX_VALUE else image.height,
            ) else frame.height
            update(resizedBounds(frame, image, IntSize(width, height), expand))
        }
        NumberField(
            R.string.height, frame.height, Modifier.weight(1f), minimum.height..maximum.height,
            onValidityChanged = { heightValid = it },
            onDragStarted = startDrag, onDragFinished = finishDrag,
        ) { height ->
            val width = if (ratio > 0 && height != frame.height) (height * ratio).roundToInt().coerceIn(
                minimum.width, if (expand) Int.MAX_VALUE else image.width,
            ) else frame.width
            update(resizedBounds(frame, image, IntSize(width, height), expand))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(
            if (expand) R.string.left_padding else R.string.left,
            if (expand) -frame.left else frame.left,
            Modifier.weight(1f),
            0..if (expand) maximum.width - image.width else image.width - 1,
            onValidityChanged = { leftValid = it },
            onDragStarted = startDrag, onDragFinished = finishDrag,
        ) { left ->
            update(positionedBounds(frame, image, if (expand) -left else left, frame.top, ratio, expand))
        }
        NumberField(
            if (expand) R.string.top_padding else R.string.top,
            if (expand) -frame.top else frame.top,
            Modifier.weight(1f),
            0..if (expand) maximum.height - image.height else image.height - 1,
            onValidityChanged = { topValid = it },
            onDragStarted = startDrag, onDragFinished = finishDrag,
        ) { top ->
            update(positionedBounds(frame, image, frame.left, if (expand) -top else top, ratio, expand))
        }
    }
}

@Composable
private fun RotationPanel(editor: PhotoEditorState, apply: (RotationSelection) -> Unit) {
    val history = editor.rotationHistory
    val selection = history.value
    val focus = LocalFocusManager.current
    var dragging by remember(history) { mutableStateOf(false) }
    var inputRevision by rememberSaveable(history) { mutableIntStateOf(0) }
    var hasDraft by remember(history, inputRevision) { mutableStateOf(false) }
    var inputValid by remember(history, inputRevision) { mutableStateOf(true) }
    fun finishInput() {
        focus.clearFocus()
        editor.dropper = false
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        key(history, inputRevision) {
            NumberField(
                R.string.rotation_angle, selection.angle, Modifier.weight(1f), -180..180,
                onDraftChanged = { hasDraft = it },
                onValidityChanged = { inputValid = it },
                onDragStarted = {
                    dragging = true
                    editor.dropper = false
                },
                onDragFinished = { commit ->
                    dragging = false
                    if (commit) history.commit() else history.cancel()
                },
                parse = ::rotationAngle,
            ) { angle ->
                editor.dropper = false
                val next = selection.copy(angle = angle)
                if (dragging) history.preview(next) else history.change(next)
            }
        }
        HistoryButtons(history) {
            finishInput()
            inputRevision++
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (vertical in listOf(true, false)) {
            FilterChip(
                selected = if (vertical) selection.flipVertical else selection.flipHorizontal,
                onClick = {
                    finishInput()
                    history.change(
                        if (vertical) selection.copy(flipVertical = !selection.flipVertical)
                        else selection.copy(flipHorizontal = !selection.flipHorizontal)
                    )
                },
                label = { Text(stringResource(if (vertical) R.string.flip_vertical else R.string.flip_horizontal)) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_flip), null, Modifier.size(18.dp).rotate(if (vertical) 90f else 0f))
                },
            )
        }
    }
    Toggle(R.string.lock_canvas_size, selection.lockCanvas) {
        finishInput()
        history.change(selection.copy(lockCanvas = it))
    }
    ColorField(editor.rotationBackground, allowTransparent = true, pick = {
        focus.clearFocus()
        editor.dropper = !editor.dropper
    }) {
        finishInput()
        editor.rotationBackground = it
    }
    if (editor.dropper) Text(stringResource(R.string.tap_color), style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(
            {
                finishInput()
                inputRevision++
                history.change(RotationSelection(lockCanvas = selection.lockCanvas))
            },
            enabled = selection.changed || hasDraft,
        ) { Text(stringResource(R.string.reset_rotation)) }
        Button(
            {
                finishInput()
                apply(selection)
            },
            enabled = selection.changed && inputValid,
        ) { Text(stringResource(R.string.apply)) }
    }
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
    var preview by selection::preview
    var inputRevision by rememberSaveable(selection) { mutableIntStateOf(0) }
    var widthHasDraft by remember(selection, width, inputRevision) { mutableStateOf(false) }
    var heightHasDraft by remember(selection, height, inputRevision) { mutableStateOf(false) }
    var widthValid by remember(selection, inputRevision) { mutableStateOf(true) }
    var heightValid by remember(selection, inputRevision) { mutableStateOf(true) }
    ResizePresetChips(IntSize(width, height), IntSize(info.width, info.height)) {
        selection.width = it.width
        selection.height = it.height
        inputRevision++
    }
    key(selection, inputRevision) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                R.string.width, width, Modifier.weight(1f),
                onDraftChanged = { widthHasDraft = it }, onValidityChanged = { widthValid = it },
            ) {
                selection.width = it
                if (selection.lock)
                    selection.height = if (it == width) height
                        else (it.toDouble() * info.height / info.width).roundToInt().coerceAtLeast(1)
            }
            NumberField(
                R.string.height, height, Modifier.weight(1f),
                onDraftChanged = { heightHasDraft = it }, onValidityChanged = { heightValid = it },
            ) {
                selection.height = it
                if (selection.lock)
                    selection.width = if (it == height) width
                        else (it.toDouble() * info.width / info.height).roundToInt().coerceAtLeast(1)
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
        TextButton({ model.previewResize(width, height, method) { preview = it } }, enabled = widthValid && heightValid) {
            Text(stringResource(R.string.preview))
        }
        Button({ apply(width, height, method) }, enabled = widthValid && heightValid) {
            Text(stringResource(R.string.apply))
        }
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
    val info = checkNotNull(state.photo.info)
    val editor = checkNotNull(state.photo.editor)
    var options by editor::export
    var previewOptions by editor::exportPreviewOptions
    var showPreview by editor::showExportPreview
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
    val losses = info.color.losses(forceSrgb = !options.keepColor)
    if (
        losses.isNotEmpty() &&
            (state.photo.frame?.source == null || !options.preservesSource(info))
    )
        Text(stringResource(R.string.pixel_export_note, colorLosses(losses)), style = MaterialTheme.typography.bodySmall)
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
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
