package com.jasongzy.medialab

import android.graphics.Rect
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun VideoScreen(model: LabViewModel, state: LabState, active: Boolean) {
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            if (it != null) model.open(it, ImportType.VIDEO)
        }
    val open = { picker.launch(arrayOf("video/*", "image/gif")) }
    val video = state.video
    if (video == null) {
        EmptyWorkspace(R.drawable.ic_video, R.string.video_empty, R.string.choose_video, open)
        return
    }
    val editor = video.editor
    val history = editor.history
    val pending = editor.pending
    val pendingDescription = stringResource(R.string.unapplied_changes)
    val edits = editor.output
    val validSize =
        if (editor.tool.editable) editor.applyTool(editor.tool).validSize else edits.validSize
    var menu by rememberSaveable(video) { mutableStateOf(false) }
    val exportHighlight = remember(editor) { Animatable(0f) }
    val highlightColor = MaterialTheme.colorScheme.primary
    var exportRequest by remember(editor) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scroll = key(video) { rememberScrollState() }
    val toolScroll = key(video) { rememberLazyListState() }
    var shownTool by rememberSaveable(video) { mutableStateOf(editor.tool) }
    LaunchedEffect(active) { if (!active) editor.dropper = false }
    LaunchedEffect(video, editor.tool, editor.inputRevision) {
        editor.dropper = false
        if (shownTool != editor.tool) {
            scroll.scrollTo(0)
            shownTool = editor.tool
        }
        if (toolScroll.layoutInfo.visibleItemsInfo.none { it.index == editor.tool.ordinal })
            toolScroll.animateScrollToItem(editor.tool.ordinal)
    }
    LaunchedEffect(exportRequest) {
        if (exportRequest > 0) {
            scroll.scrollTo(0)
            toolScroll.animateScrollToItem(VideoTool.EXPORT.ordinal)
            exportHighlight.snapTo(0.16f)
            exportHighlight.animateTo(0f, tween(1200))
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                FileName(
                    video.name,
                    video.sourceUri,
                    MediaFiles.mime(video.file),
                    video.file.length(),
                    R.string.original_duration to playbackTime(video.info.durationMs),
                    sourceDetails = { VideoSourceDetails(video.info) },
                )
                Text(
                    "${history.value.size.width} × ${history.value.size.height} · ${playbackTime(history.value.durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ToolButton(R.drawable.ic_undo, R.string.undo, history.canUndo) {
                model.undoVideo(-1)
            }
            ToolButton(R.drawable.ic_redo, R.string.redo, history.canRedo) {
                model.undoVideo(1)
            }
            ToolButton(R.drawable.ic_save, R.string.export) {
                if (editor.tool != VideoTool.EXPORT) editor.inputValid = true
                editor.tool = VideoTool.EXPORT
                editor.dropper = false
                exportRequest++
            }
            Box {
                ToolButton(R.drawable.ic_more, R.string.more) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.restore_video)) },
                        enabled =
                            history.value != editor.original ||
                                pending.isNotEmpty() ||
                                !editor.inputValid,
                        onClick = {
                            menu = false
                            model.restoreVideo()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.replace_video)) },
                        onClick = {
                            menu = false
                            open()
                        },
                    )
                }
            }
        }
        EditorLayout(
            Modifier.weight(1f).fillMaxWidth(),
            preview = {
                val file = video.playbackFile
                val applied = history.value
                MediaPlayer(
                    file,
                    active && !editor.showPreview,
                    editor.positionMs,
                    video = true,
                    muted = editor.playbackMuted || video.info.audioTracks.isEmpty(),
                    paused = state.busy,
                    modifier = Modifier.fillMaxSize(),
                    onInteraction = { editor.dropper = false },
                    seekPosition = editor.seekPosition,
                    onSeekHandled = { editor.seekPosition = null },
                    hasVideoPlaceholder =
                        video.poster?.let { it.file == file && it.positionMs == editor.positionMs } == true,
                    videoContent = { player, loading, frameReady ->
                        VideoCanvas(
                            video,
                            player,
                            loading,
                            frameReady,
                            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                        )
                    },
                ) {
                    if (video.playbackFile == file && history.value == applied) {
                        model.rememberPlaybackPosition(file, it)
                        editor.positionMs = it
                    }
                }
            },
            controls = {
                Column {
                    LazyRow(
                        Modifier.fillMaxWidth(),
                        state = toolScroll,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(VideoTool.entries.size) { index ->
                            val tool = VideoTool.entries[index]
                            FilterChip(
                                editor.tool == tool,
                                {
                                    if (editor.tool != tool) editor.inputValid = true
                                    editor.tool = tool
                                    editor.dropper = false
                                },
                                label = { Text(stringResource(tool.label)) },
                                leadingIcon = {
                                    BadgedBox(
                                        badge = {
                                            if (tool in pending)
                                                Badge(
                                                    Modifier.semantics {
                                                        contentDescription = pendingDescription
                                                    }
                                                )
                                        }
                                    ) {
                                        Icon(painterResource(tool.icon), null, Modifier.size(18.dp))
                                    }
                                },
                            )
                        }
                    }
                    if (editor.tool == VideoTool.TIME) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            VideoTimeline(editor)
                            HorizontalDivider()
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth()
                            .weight(1f)
                            .drawBehind {
                                if (editor.tool == VideoTool.EXPORT)
                                    drawRect(highlightColor.copy(alpha = exportHighlight.value))
                            }
                            .verticalScrollbar(scroll)
                            .verticalScroll(scroll)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!validSize)
                            Text(
                                stringResource(R.string.video_size_limit),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        key(editor, editor.tool, editor.inputRevision) {
                            when (editor.tool) {
                                VideoTool.TIME -> VideoTimePanel(video, model::applyVideo)
                                VideoTool.CROP,
                                VideoTool.EXPAND ->
                                    VideoFramePanel(
                                        video,
                                        editor.tool == VideoTool.EXPAND,
                                        model::applyVideo,
                                    )
                                VideoTool.ROTATE -> VideoRotationPanel(editor, model::applyVideo)
                                VideoTool.COVER -> VideoCoverPanel(editor, model::applyVideo)
                                VideoTool.RESIZE -> VideoResizePanel(video, model::applyVideo)
                                VideoTool.CLEAN -> {
                                    CheckOption(
                                        R.string.clear_metadata,
                                        editor.clearMetadata || history.value.clearMetadata,
                                        !history.value.clearMetadata,
                                    ) {
                                        editor.clearMetadata = it
                                    }
                                    Text(
                                        stringResource(R.string.video_clear_metadata_hint),
                                        Modifier.padding(start = 36.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Button(
                                        model::applyVideo,
                                        Modifier.align(Alignment.End),
                                        enabled = editor.clearMetadata && !history.value.clearMetadata,
                                    ) {
                                        Text(stringResource(R.string.apply))
                                    }
                                }
                                VideoTool.EXTRACT -> {
                                    val (frame, count) =
                                        videoFramePosition(
                                            editor.positionMs,
                                            history.value.durationMs,
                                            if (video.appliedPreview == null) video.info.fps
                                            else history.value.fps.toDouble(),
                                        )
                                    Text(
                                        AnnotatedString.fromHtml(
                                            stringResource(
                                                R.string.video_frame_position,
                                                frame,
                                                count,
                                            )
                                        ),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    Text(
                                        stringResource(R.string.video_frame_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    FlowRow(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        FilledTonalButton(
                                            { model.extractVideo(false, false) },
                                            enabled = validSize,
                                        ) {
                                            Text(stringResource(R.string.video_frame_edit))
                                        }
                                        TextButton(
                                            { model.extractVideo(false, true) },
                                            enabled = validSize,
                                        ) {
                                            Text(stringResource(R.string.video_frame_save))
                                        }
                                    }
                                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                    VideoAudioTracks(video)
                                    FlowRow(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        FilledTonalButton(
                                            { model.extractVideo(true, false) },
                                            enabled = video.selectedAudioTrack != null,
                                        ) {
                                            Text(stringResource(R.string.video_audio_edit))
                                        }
                                        TextButton(
                                            { model.extractVideo(true, true) },
                                            enabled = video.selectedAudioTrack != null,
                                        ) {
                                            Text(stringResource(R.string.video_audio_save))
                                        }
                                    }
                                }
                                VideoTool.INFO -> VideoInformation(video, model)
                                VideoTool.EXPORT -> {
                                    if (pending.isNotEmpty())
                                        Text(
                                            stringResource(
                                                R.string.unapplied_export,
                                                pending
                                                    .map { stringResource(it.label) }
                                                    .joinToString("\n") { "• $it" },
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    ChoiceChips(
                                        listOf(-1 to stringResource(R.string.original_format)) +
                                            VideoFormat.entries.map { it.ordinal to it.name },
                                        editor.format?.ordinal ?: -1,
                                    ) {
                                        editor.format = VideoFormat.entries.getOrNull(it)
                                    }
                                    if (editor.format == null) Text(
                                        video.info.format.uppercase(java.util.Locale.ROOT),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    val supportedFormat = editor.format != null || video.info.exportFormat != null
                                    if (!supportedFormat) Text(
                                        stringResource(R.string.video_current_format_unavailable),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    var fpsValid by remember { mutableStateOf(true) }
                                    var bitrateValid by remember { mutableStateOf(true) }
                                    var colorsValid by remember { mutableStateOf(true) }
                                    var colorsRevision by remember { mutableIntStateOf(0) }
                                    var fpsRevision by remember { mutableIntStateOf(0) }
                                    var bitrateRevision by remember { mutableIntStateOf(0) }
                                    val inputValid =
                                        fpsValid &&
                                            (if (edits.format == VideoFormat.GIF) colorsValid
                                            else bitrateValid)
                                    SideEffect { editor.inputValid = inputValid }
                                    ChoiceChips(
                                        listOf(15, 24, 25, 30, 50, 60, 120).map { it to "$it" },
                                        edits.fps,
                                    ) {
                                        editor.fps = it
                                        fpsRevision++
                                    }
                                    key(fpsRevision) {
                                        NumberField(
                                            R.string.video_fps,
                                            edits.fps,
                                            Modifier.fillMaxWidth(),
                                            1..120,
                                            onValidityChanged = { fpsValid = it },
                                        ) {
                                            editor.fps = it
                                        }
                                    }
                                    if (edits.format == VideoFormat.GIF) {
                                        ChoiceChips(
                                            listOf(16, 32, 64, 128, 256).map { it to "$it" },
                                            edits.gifColors,
                                        ) {
                                            editor.gifColors = it
                                            colorsRevision++
                                        }
                                        key(colorsRevision) {
                                            NumberField(
                                                R.string.gif_colors,
                                                edits.gifColors,
                                                Modifier.fillMaxWidth(),
                                                4..256,
                                                onValidityChanged = { colorsValid = it },
                                            ) {
                                                editor.gifColors = it
                                            }
                                        }
                                        CheckOption(R.string.gif_loop, edits.gifLoop) {
                                            editor.gifLoop = it
                                        }
                                    } else {
                                        ChoiceChips(
                                            listOf(500, 1000, 2000, 5000, 10000, 20000, 40000).map {
                                                it to
                                                    if (it == 500) "0.5 Mbps"
                                                    else "${it / 1000} Mbps"
                                            },
                                            edits.bitrateKbps,
                                        ) {
                                            editor.bitrateKbps = it
                                            bitrateRevision++
                                        }
                                        key(bitrateRevision) {
                                            NumberField(
                                                R.string.video_bitrate,
                                                edits.bitrateKbps,
                                                Modifier.fillMaxWidth(),
                                                100..200000,
                                                onValidityChanged = { bitrateValid = it },
                                            ) {
                                                editor.bitrateKbps = it
                                            }
                                        }
                                        Text(
                                            stringResource(R.string.video_bitrate_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        CheckOption(
                                            R.string.video_mute,
                                            edits.mute,
                                            video.info.audioTracks.isNotEmpty(),
                                        ) {
                                            editor.mute = it
                                        }
                                    }
                                    val size =
                                        if (edits.format == VideoFormat.GIF) edits.size
                                        else evenVideoSize(edits.size)
                                    if (supportedFormat) Text(
                                        stringResource(
                                            if (Videos.canCopy(video.info, edits))
                                                R.string.video_copy_hint
                                            else
                                                when (edits.format) {
                                                    VideoFormat.GIF ->
                                                        R.string.video_gif_export_hint
                                                    VideoFormat.WEBM ->
                                                        R.string.video_webm_export_hint
                                                    else -> R.string.video_h264_export_hint
                                                }
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (supportedFormat && !Videos.canCopy(video.info, edits) && size != edits.size)
                                        Text(
                                            stringResource(R.string.video_even_size_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    if (
                                        supportedFormat && video.info.hdr &&
                                            !Videos.canCopy(video.info, edits)
                                    )
                                        Text(
                                            stringResource(R.string.video_hdr_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    ExportActions(
                                        preview = model::previewVideo,
                                        share = {
                                            model.shareVideo {
                                                MediaFiles.share(context, it)
                                            }
                                        },
                                        save = model::saveVideo,
                                        enabled = editor.inputValid && validSize && supportedFormat,
                                    ) {
                                        TextButton(
                                            {
                                                editor.fps = editor.original.fps
                                                editor.bitrateKbps = editor.original.bitrateKbps
                                                editor.gifColors = editor.original.gifColors
                                                editor.inputRevision++
                                            },
                                            enabled =
                                                !inputValid ||
                                                    editor.gifColors != editor.original.gifColors ||
                                                    editor.fps != editor.original.fps ||
                                                    editor.bitrateKbps !=
                                                        editor.original.bitrateKbps,
                                        ) {
                                            Text(stringResource(R.string.video_reset_values))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }
    val preview = video.previewFile
    if (editor.showPreview && preview != null && !state.busy)
        androidx.compose.ui.window.Dialog({ editor.showPreview = false }) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.video_edited_preview),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (preview.extension == "gif") GifPreview(preview, active)
                    else
                        MediaPlayer(
                            preview,
                            active,
                            model.playbackPosition(preview),
                            video = true,
                            muted =
                                editor.playbackMuted ||
                                    video.info.audioTracks.isEmpty() ||
                                    video.previewEdits?.mute == true,
                        ) {
                            model.rememberPlaybackPosition(preview, it)
                        }
                    Text(
                        Formatter.formatFileSize(context, preview.length()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton({ editor.showPreview = false }, Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
}

@Composable
private fun VideoAudioTracks(video: VideoDocument) {
    val tracks = video.info.audioTracks
    val metadata = video.info.metadata(video.editor.history.value)
    if (tracks.isEmpty()) {
        Text(stringResource(R.string.video_no_audio), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val angle by
        animateFloatAsState(
            if (video.audioTracksExpanded) 180f else 0f,
            tween(180),
            label = "audioTracks",
        )
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(
                role = Role.Button,
                onClickLabel =
                    stringResource(
                        if (video.audioTracksExpanded) R.string.video_audio_collapse
                        else R.string.video_audio_expand
                    ),
            ) {
                video.audioTracksExpanded = !video.audioTracksExpanded
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(
                    R.string.video_audio_selection,
                    tracks.indexOfFirst { it.index == video.selectedAudioTrack } + 1,
                    tracks.size,
                ),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
            )
            Icon(
                painterResource(R.drawable.ic_expand_more),
                null,
                Modifier.size(24.dp).rotate(angle),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            video.audioTracksExpanded,
            enter = expandVertically(tween(180), expandFrom = Alignment.Top) + fadeIn(tween(180)),
            exit =
                shrinkVertically(tween(180), shrinkTowards = Alignment.Top) + fadeOut(tween(180)),
        ) {
            Column(Modifier.selectableGroup()) {
                tracks.forEachIndexed { i, track ->
                    key(track.index) {
                        val selected = track.index == video.selectedAudioTrack
                        val tags = metadata.firstOrNull { it.streamIndex == track.index }?.entries.orEmpty()
                        fun tag(name: String) = tags.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second.orEmpty()
                        ListItem(
                            content = {
                                Text(
                                    listOf(
                                            stringResource(R.string.video_audio_track, i + 1),
                                            tag("title"),
                                        )
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            supportingContent = {
                                Text(
                                    listOf(track.codec, tag("language"))
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            leadingContent = { RadioButton(selected, null) },
                            modifier =
                                Modifier.selectable(selected, role = Role.RadioButton) {
                                    video.selectedAudioTrack = track.index
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GifPreview(file: java.io.File, active: Boolean) {
    var drawable by remember(file) { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
    var failed by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file) {
        try {
            drawable =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    android.graphics.ImageDecoder.decodeDrawable(
                        android.graphics.ImageDecoder.createSource(file)
                    ) { decoder, info, _ ->
                        val scale = minOf(1.0, 1280.0 / maxOf(info.size.width, info.size.height))
                        decoder.setTargetSize(
                            (info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } catch (_: OutOfMemoryError) {
            failed = true
        }
    }
    if (active)
        androidx.lifecycle.compose.LifecycleResumeEffect(drawable) {
            val animation = drawable as? android.graphics.drawable.AnimatedImageDrawable
            animation?.start()
            onPauseOrDispose { animation?.stop() }
        }
    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                android.widget.ImageView(it).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }
            },
            update = { it.setImageDrawable(drawable) },
            onRelease = { it.setImageDrawable(null) },
            modifier = Modifier.fillMaxSize(),
        )
        if (drawable == null && !failed) CanvasLoading()
    }
    if (failed)
        Text(
            stringResource(R.string.video_gif_preview_failed),
            style = MaterialTheme.typography.bodySmall,
        )
}

@Composable
private fun VideoCoverPanel(editor: VideoEditor, apply: () -> Unit) {
    val history = editor.draft
    val edits = history.value
    val cover = checkNotNull(edits.cover)
    val valid = remember { mutableStateMapOf<Int, Boolean>() }
    SideEffect { editor.inputValid = valid.values.all { it } }
    CoverTools(
        editor.coverFill,
        editor.coverShape,
        {
            editor.coverFill = it
            editor.dropper = false
        },
        {
            editor.coverShape = it
            editor.dropper = false
        },
    ) {
        HistoryButtons(history, R.string.cover) { editor.inputRevision++ }
    }
    VideoColorField(editor, editor.coverColor) { editor.coverColor = it }
    if (!editor.coverFill)
        BrushSize(editor.brushWidth, editor.current.size) { editor.brushWidth = it }
    Text(stringResource(R.string.video_cover_time), style = MaterialTheme.typography.labelMedium)
    MediaRangeSlider(
        editor.current.durationMs,
        cover.startMs,
        cover.endMs,
        Modifier.fillMaxWidth(),
        seek = { editor.dropper = false; editor.seekPosition = it },
        change = { start, end ->
            history.preview(edits.copy(cover = cover.copy(startMs = start, endMs = end)))
        },
        finish = history::commit,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VideoTimeField(
            R.string.video_start,
            cover.startMs,
            0 until cover.endMs,
            Modifier.weight(1f),
            { valid[0] = it },
            editor,
        ) {
            editor.seekPosition = it
            edits.copy(cover = cover.copy(startMs = it))
        }
        VideoTimeField(
            R.string.video_end,
            cover.endMs,
            (cover.startMs + 1)..editor.current.durationMs,
            Modifier.weight(1f),
            { valid[1] = it },
            editor,
        ) {
            editor.seekPosition = it - 1
            edits.copy(cover = cover.copy(endMs = it))
        }
    }
    VideoEditActions(editor, apply) {
        TextButton(
            { history.change(edits.copy(cover = cover.copy(strokes = emptyList()))) },
            enabled = cover.strokes.isNotEmpty(),
        ) {
            Text(stringResource(R.string.clear_cover))
        }
    }
}

@Composable
private fun VideoTimeline(editor: VideoEditor) {
    val history = editor.draft
    val edits = history.value
    val perforation = MaterialTheme.colorScheme.onSecondaryContainer
    val padding =
        Modifier.height(12.dp)
            .background(
                if (editor.holdFrames) MaterialTheme.colorScheme.secondaryContainer
                else Color(editor.timeColor),
                MaterialTheme.shapes.extraSmall,
            )
            .drawBehind {
                if (editor.holdFrames) {
                    val hole = Size(2.dp.toPx(), 2.dp.toPx())
                    var x = 3.dp.toPx()
                    while (x + hole.width < size.width) {
                        drawRect(perforation, Offset(x, 1.dp.toPx()), hole)
                        drawRect(perforation, Offset(x, size.height - 3.dp.toPx()), hole)
                        x += 6.dp.toPx()
                    }
                }
            }
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
    MediaTimeline(
        edits.time, editor.current.durationMs, { Spacer(padding.weight(it)) },
        seek = { editor.dropper = false; editor.seekPosition = it },
        change = { start, end -> history.preview(edits.copy(startMs = start, endMs = end)) },
        finish = history::commit,
    ) { HistoryButtons(history, R.string.video_time) { editor.inputRevision++ } }
}

@Composable
private fun VideoTimePanel(video: VideoDocument, apply: () -> Unit) {
    val history = video.editor.draft
    val edits = history.value
    MediaTimeFields(
        edits.time, video.editor.current.durationMs,
        validity = { video.editor.inputValid = it },
        seek = { video.editor.seekPosition = it },
        interaction = { video.editor.dropper = false },
        change = { time, preview ->
            val next = edits.withTime(time)
            if (preview) history.preview(next) else history.change(next)
        },
        finish = { if (it) history.commit() else history.cancel() },
    )
    ChoiceChips(
        listOf(
            0 to stringResource(R.string.video_solid_color),
            1 to stringResource(R.string.video_hold_frames),
        ),
        if (video.editor.holdFrames) 1 else 0,
    ) {
        video.editor.dropper = false
        video.editor.holdFrames = it == 1
    }
    if (video.editor.holdFrames)
        Text(
            stringResource(R.string.video_hold_frames_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    else VideoColorField(video.editor, video.editor.timeColor) { video.editor.timeColor = it }
    MediaTimeActions(
        edits.durationMs,
        resetEnabled = !video.editor.inputValid || edits.time != MediaTimeEdit(video.editor.current.durationMs),
        applyEnabled = video.editor.inputValid && video.editor.applyTool(VideoTool.TIME).validSize &&
            VideoTool.TIME in video.editor.pending,
        reset = {
            history.change(edits.withTime(MediaTimeEdit(video.editor.current.durationMs)))
            video.editor.inputRevision++
        },
        apply = { video.editor.dropper = false; apply() },
    )
}

@Composable
private fun VideoTimeField(
    label: Int, value: Long, range: LongRange, modifier: Modifier,
    valid: (Boolean) -> Unit, editor: VideoEditor, edit: (Long) -> VideoEdits,
) = MediaTimeField(
    label, value, range, modifier, valid,
    interaction = { editor.dropper = false },
    change = { next, preview -> if (preview) editor.draft.preview(next) else editor.draft.change(next) },
    finish = { if (it) editor.draft.commit() else editor.draft.cancel() },
    edit = edit,
)

@Composable
private fun VideoFramePanel(video: VideoDocument, expand: Boolean, apply: () -> Unit) {
    val history = video.editor.draft
    val edits = history.value
    val bounds = if (expand) edits.canvas else edits.crop
    val ratio = if (expand) edits.canvasRatio else edits.cropRatio
    fun edit(next: FrameSelection): VideoEdits {
        video.editor.dropper = false
        return if (expand)
            edits.copy(
                canvas = next.bounds.let { IntRect(it.left, it.top, it.right, it.bottom) },
                canvasRatio = next.ratio,
            )
        else
            edits.withCrop(
                next.bounds.let { IntRect(it.left, it.top, it.right, it.bottom) },
                next.ratio,
            )
    }
    val image = video.editor.current.size
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.weight(1f)) {
            FrameRatioChips(ratio) { nextRatio ->
                val rect = ratioBounds(bounds, nextRatio, expand)
                history.change(
                    edit(
                        FrameSelection(
                            Rect(rect.left, rect.top, rect.right, rect.bottom),
                            nextRatio,
                        )
                    )
                )
            }
        }
        HistoryButtons(history, if (expand) R.string.expand_canvas else R.string.crop) {
            video.editor.inputRevision++
        }
    }
    FrameFields(
        FrameSelection(Rect(bounds.left, bounds.top, bounds.right, bounds.bottom), ratio),
        image,
        expand,
        { video.editor.inputValid = it },
        { history.preview(edit(it)) },
        { history.change(edit(it)) },
        { if (it) history.commit() else history.cancel() },
    )
    if (expand)
        VideoColorField(video.editor, video.editor.background) { video.editor.background = it }
    VideoEditActions(video.editor, apply) {
        TextButton({
            val left = (image.width - bounds.width) / 2
            val top = (image.height - bounds.height) / 2
            history.change(
                edit(
                    FrameSelection(Rect(left, top, left + bounds.width, top + bounds.height), ratio)
                )
            )
        }) {
            Text(stringResource(R.string.center_image))
        }
        TextButton(
            {
                history.change(edit(FrameSelection(Rect(0, 0, image.width, image.height))))
                video.editor.inputRevision++
            },
            enabled =
                !video.editor.inputValid ||
                    bounds != IntRect(0, 0, image.width, image.height) ||
                    ratio != 0f,
        ) {
            Text(stringResource(if (expand) R.string.reset_canvas else R.string.reset_crop))
        }
    }
}

@Composable
private fun VideoColorField(editor: VideoEditor, color: Int, change: (Int) -> Unit) {
    ColorField(color, pick = { editor.dropper = !editor.dropper }) {
        editor.dropper = false
        change(it)
    }
    if (editor.dropper)
        Text(stringResource(R.string.tap_color), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun VideoRotationPanel(editor: VideoEditor, apply: () -> Unit) {
    val colors = FilterChipDefaults.filterChipColors()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (clockwise in listOf(false, true)) {
            AssistChip(
                onClick = { editor.rotation = editor.rotation.rotate(clockwise) },
                modifier = Modifier.weight(1f),
                colors =
                    AssistChipDefaults.assistChipColors(
                        labelColor = colors.labelColor,
                        leadingIconContentColor = colors.leadingIconColor,
                    ),
                leadingIcon = {
                    Icon(
                        painterResource(
                            if (clockwise) R.drawable.ic_rotate_90_cw
                            else R.drawable.ic_rotate_90_ccw
                        ),
                        null,
                        Modifier.size(18.dp),
                    )
                },
                label = {
                    Text(
                        stringResource(
                            if (clockwise) R.string.rotate_right_90 else R.string.rotate_left_90
                        )
                    )
                },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (vertical in listOf(true, false)) {
            FlipChip(
                vertical,
                if (vertical) editor.rotation.flipVertical else editor.rotation.flipHorizontal,
                Modifier.weight(1f),
            ) {
                editor.rotation = editor.rotation.flip(vertical)
            }
        }
    }
    VideoEditActions(editor, apply) {
        TextButton({ editor.rotation = VideoRotation() }, enabled = editor.rotation.changed) {
            Text(stringResource(R.string.reset_rotation))
        }
    }
}

@Composable
private fun VideoResizePanel(video: VideoDocument, apply: () -> Unit) {
    val editor = video.editor
    var hasDraft by remember { mutableStateOf(false) }
    ResizePresetChips(editor.resize, editor.current.size) {
        editor.resize = it
        editor.inputRevision++
    }
    ResizeFields(
        editor.resize,
        editor.current.size,
        editor.lockRatio,
        1..8192,
        onValidityChanged = { editor.inputValid = it },
        onDraftChanged = { hasDraft = it },
    ) {
        editor.resize = it
    }
    CheckOption(R.string.keep_ratio, editor.lockRatio) { editor.lockRatio = it }
    VideoEditActions(editor, apply) {
        TextButton(
            {
                editor.resize = editor.current.size
                editor.inputRevision++
            },
            enabled = editor.resize != editor.current.size || hasDraft,
        ) {
            Text(stringResource(R.string.reset_resize))
        }
    }
}

@Composable
private fun VideoEditActions(
    editor: VideoEditor,
    apply: () -> Unit,
    actions: @Composable () -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        actions()
        Button(
            {
                editor.dropper = false
                apply()
            },
            enabled =
                editor.inputValid &&
                    editor.applyTool(editor.tool).validSize &&
                    editor.tool in editor.pending,
        ) {
            Text(stringResource(R.string.apply))
        }
    }
}
