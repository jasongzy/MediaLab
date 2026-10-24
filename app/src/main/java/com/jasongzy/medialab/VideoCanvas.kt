package com.jasongzy.medialab

import android.view.TextureView
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun VideoCanvas(
    video: VideoDocument, player: Player?, loading: Boolean, frameReady: Boolean, modifier: Modifier,
) {
    val editor = video.editor
    val expanding = editor.tool == VideoTool.EXPAND
    val framing = expanding || editor.tool == VideoTool.CROP
    val covering = editor.tool == VideoTool.COVER
    val history = if (framing || covering) editor.draft else null
    val edits = history?.value
    val selection = if (expanding) edits?.canvas else if (framing) edits?.crop else null
    val picking =
        editor.dropper && editor.tool in listOf(VideoTool.TIME, VideoTool.EXPAND, VideoTool.COVER)
    val painting = covering && !picking
    val shape = if (editor.coverFill) editor.coverShape else CoverShape.BRUSH
    var selectionStroke by
        remember(video, history, picking, shape) { mutableStateOf<Stroke?>(null) }
    var brushPoint by remember(video, history, picking) { mutableStateOf<Offset?>(null) }
    var pickedPoint by remember(video, history, picking) { mutableStateOf<Offset?>(null) }
    val bounds = selection?.let {
        Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
    }
    val ratio = if (expanding) edits?.canvasRatio else edits?.cropRatio
    val dimensions =
        if (editor.tool == VideoTool.RESIZE) editor.resize else editor.history.value.size
    val rotation = if (editor.tool == VideoTool.ROTATE) editor.rotation else VideoRotation()
    val angle =
        key(video, editor.history.value, editor.tool) {
            animateFloatAsState(rotation.quarterTurns * 90f, tween(180), label = "videoRotation")
        }
    val flipX =
        key(video, editor.history.value, editor.tool) {
            animateFloatAsState(
                if (rotation.flipHorizontal) -1f else 1f,
                tween(180),
                label = "videoFlipX",
            )
        }
    val flipY =
        key(video, editor.history.value, editor.tool) {
            animateFloatAsState(
                if (rotation.flipVertical) -1f else 1f,
                tween(180),
                label = "videoFlipY",
            )
        }
    val rotated = rotation.size(dimensions)
    val image = Size(rotated.width.toFloat(), rotated.height.toFloat())
    val radians = Math.toRadians(angle.value.toDouble())
    val displaySize =
        Size(
            (dimensions.width * abs(cos(radians)) + dimensions.height * abs(sin(radians)))
                .toFloat(),
            (dimensions.width * abs(sin(radians)) + dimensions.height * abs(cos(radians)))
                .toFloat(),
        )
    val initialScale = if (expanding) 0.6f else 0.9f
    var viewport by
        rememberSaveable(video, editor.history.value, expanding, stateSaver = viewportSaver) {
            mutableStateOf(ImageViewport(initialScale))
        }
    var canvas by rememberSaveable(stateSaver = canvasSizeSaver) { mutableStateOf(Size.Zero) }
    var toolbar by remember { mutableStateOf(IntSize.Zero) }
    val minScale =
        if (expanding) ImageViewport.MIN_OUTSIDE_SCALE
        else
            ImageViewport.minimumScale(
                canvas,
                toolbar.height + with(LocalDensity.current) { 8.dp.toPx() },
            )
    fun fitted() = ImageViewport.fit(canvas, image, bounds ?: Rect(Offset.Zero, image))
    var fittedRatio by
        rememberSaveable(video, editor.history.value, expanding) { mutableStateOf<Float?>(null) }
    LaunchedEffect(canvas, image, expanding, ratio, minScale) {
        if (!canvas.isEmpty()) {
            viewport =
                if (expanding && fittedRatio != ratio) fitted()
                else viewport.constrained(canvas, image, expanding, minScale)
            fittedRatio = ratio
        }
    }
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val currentPlayer by rememberUpdatedState(player)
    val currentLoading by rememberUpdatedState(loading || (picking && !frameReady))
    val currentEdits by rememberUpdatedState(edits)
    var snapped by remember(video, history) { mutableStateOf<Pair<Int?, Int?>>(null to null) }
    val verticalHighlight = snapGuideAlpha(snapped.first)
    val horizontalHighlight = snapGuideAlpha(snapped.second)
    val frameColor = if (expanding) MaterialTheme.colorScheme.primary else Color.White
    val imageColor = if (expanding) Color.White else MaterialTheme.colorScheme.primary
    val snapColor = MaterialTheme.colorScheme.primary
    val borderColor = MaterialTheme.colorScheme.outline
    val description = stringResource(R.string.video_canvas)
    val context = LocalContext.current
    val surface = remember(context) { TextureView(context) }
    val file = video.playbackFile
    LaunchedEffect(file) { if (video.poster?.file != file) video.poster = null }
    DisposableEffect(player, surface, file) {
        var rendered = false
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { rendered = true }
        }
        player?.addListener(listener)
        player?.setVideoTextureView(surface)
        onDispose {
            if (rendered && surface.isAvailable && video.playbackFile == file) {
                val scale = minOf(1f, 640f / maxOf(surface.width, surface.height))
                surface.getBitmap(
                    (surface.width * scale).roundToInt().coerceAtLeast(1),
                    (surface.height * scale).roundToInt().coerceAtLeast(1),
                )?.let { video.poster = VideoPoster(file, editor.positionMs, it) }
            }
            player?.removeListener(listener)
            player?.clearVideoTextureView(surface)
        }
    }
    Box(
        modifier
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .onSizeChanged {
                val next = Size(it.width.toFloat(), it.height.toFloat())
                if (!canvas.isEmpty() && !next.isEmpty()) {
                    val oldFit = minOf(canvas.width / image.width, canvas.height / image.height)
                    val newFit = minOf(next.width / image.width, next.height / image.height)
                    viewport = viewport.copy(offset = viewport.offset * (newFit / oldFit))
                }
                canvas = next
            }
    ) {
        val fit =
            if (canvas.isEmpty()) 0f
            else minOf(canvas.width / image.width, canvas.height / image.height) * viewport.scale
        val origin = canvas.center + viewport.offset - Offset(image.width, image.height) * (fit / 2)
        if (expanding && bounds != null)
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    Color(editor.background),
                    origin + bounds.topLeft * fit,
                    bounds.size * fit,
                )
            }
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .layout { measurable, constraints ->
                        val base =
                            minOf(
                                constraints.maxWidth / displaySize.width,
                                constraints.maxHeight / displaySize.height,
                            )
                        val child =
                            measurable.measure(
                                Constraints.fixed(
                                    (dimensions.width * base).roundToInt().coerceAtLeast(1),
                                    (dimensions.height * base).roundToInt().coerceAtLeast(1),
                                )
                            )
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            child.place(
                                (constraints.maxWidth - child.width) / 2,
                                (constraints.maxHeight - child.height) / 2,
                            )
                        }
                    }
                    .graphicsLayer {
                        scaleX = viewport.scale * flipX.value
                        scaleY = viewport.scale * flipY.value
                        translationX = viewport.offset.x
                        translationY = viewport.offset.y
                    }
                    .graphicsLayer { rotationZ = angle.value },
        ) {
            AndroidView(factory = { surface }, modifier = Modifier.matchParentSize())
            if (!frameReady) video.poster?.takeIf { it.file == file }?.let {
                Image(it.bitmap.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            }
        }
        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = description }
                .pointerInput(
                    history,
                    history?.revision,
                    image,
                    expanding,
                    ratio,
                    minScale,
                    picking,
                    painting,
                    shape,
                    editor.brushWidth,
                    if (picking) 0 else editor.coverColor,
                ) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (currentLoading) return@awaitEachGesture
                        focus.clearFocus()
                        currentPlayer?.pause()
                        val initial = currentEdits
                        val frame =
                            (if (picking || !framing) null
                                else if (expanding) initial?.canvas else initial?.crop)
                                ?.let {
                                    Rect(
                                        it.left.toFloat(),
                                        it.top.toFloat(),
                                        it.right.toFloat(),
                                        it.bottom.toFloat(),
                                    )
                                }
                        val scale =
                            minOf(canvas.width / image.width, canvas.height / image.height) *
                                viewport.scale
                        val drag = frame?.let {
                            CropDrag(
                                it,
                                viewport.imagePoint(down.position, canvas, image),
                                image,
                                28.dp.toPx() / scale,
                                ratio ?: 0f,
                                Rect(
                                    viewport.imagePoint(Offset.Zero, canvas, image),
                                    viewport.imagePoint(
                                        Offset(canvas.width, canvas.height),
                                        canvas,
                                        image,
                                    ),
                                ),
                                expanding,
                                6.dp.toPx() / scale,
                            )
                        }
                        fun point(position: Offset) =
                            viewport.imagePoint(
                                position,
                                canvas,
                                image,
                                edgeSnap = if (shape != CoverShape.BRUSH) 1.dp.toPx() else 0f,
                            )
                        val cover = initial?.cover
                        val strokeDrag =
                            if (painting)
                                StrokeDrag(
                                    point(down.position),
                                    editor.coverColor,
                                    editor.brushWidth,
                                    shape,
                                )
                            else null
                        if (painting && cover != null && !cover.visible(editor.positionMs))
                            editor.seekPosition = cover.startMs
                        if (painting && shape == CoverShape.BRUSH) brushPoint = point(down.position)
                        var transform = false
                        var complete = false
                        val bitmap =
                            if (picking)
                                try {
                                    surface.bitmap
                                } catch (_: IllegalStateException) {
                                    null
                                }
                            else null
                        if (picking && bitmap == null) {
                            Toast.makeText(context, R.string.video_pick_failed, Toast.LENGTH_SHORT)
                                .show()
                            return@awaitEachGesture
                        }
                        fun pick(point: Offset) {
                            if (bitmap == null) return
                            val pixel = viewport.imagePoint(point, canvas, image)
                            val x =
                                (pixel.x / image.width * bitmap.width)
                                    .toInt()
                                    .coerceIn(0, bitmap.width - 1)
                            val y =
                                (pixel.y / image.height * bitmap.height)
                                    .toInt()
                                    .coerceIn(0, bitmap.height - 1)
                            val color = bitmap.getPixel(x, y) or (255 shl 24)
                            pickedPoint =
                                Offset(
                                    (x + 0.5f) / bitmap.width * image.width,
                                    (y + 0.5f) / bitmap.height * image.height,
                                )
                            when {
                                covering -> editor.coverColor = color
                                expanding -> editor.background = color
                                else -> editor.timeColor = color
                            }
                        }
                        try {
                            if (picking) pick(down.position)
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } > 1 && !transform) {
                                    transform = true
                                    if (!picking) history?.cancel()
                                    snapped = null to null
                                    pickedPoint = null
                                    selectionStroke = null
                                    brushPoint = null
                                }
                                if (
                                    (transform || (!picking && !painting && drag == null)) &&
                                        event.changes.any { it.pressed }
                                ) {
                                    viewport =
                                        viewport.transformed(
                                            event.calculateZoom(),
                                            event.calculatePan(),
                                            event.calculateCentroid(),
                                            canvas,
                                            image,
                                            expanding,
                                            minScale,
                                        )
                                } else if (!transform && picking) {
                                    event.changes
                                        .firstOrNull { it.id == down.id }
                                        ?.let { pick(it.position) }
                                } else if (!transform && strokeDrag != null && cover != null) {
                                    event.changes
                                        .firstOrNull { it.id == down.id }
                                        ?.let { pointer ->
                                            val at = point(pointer.position)
                                            val stroke = strokeDrag.update(at)
                                            if (!stroke.fill) brushPoint = at
                                            if (stroke.fill && pointer.pressed)
                                                selectionStroke = stroke
                                            else if (stroke.isDrawable)
                                                history?.preview(
                                                    checkNotNull(initial)
                                                        .copy(
                                                            cover =
                                                                cover.copy(
                                                                    strokes = cover.strokes + stroke
                                                                )
                                                        )
                                                )
                                        }
                                } else if (!transform && drag != null) {
                                    val pointer = event.changes.firstOrNull { it.id == down.id }
                                    if (
                                        pointer != null &&
                                            pointer.pressed &&
                                            pointer.position != pointer.previousPosition
                                    ) {
                                        val previous = drag.snapped
                                        val rect =
                                            drag.pixelBounds(
                                                viewport.imagePoint(pointer.position, canvas, image)
                                            )
                                        history?.preview(
                                            if (expanding) checkNotNull(initial).copy(canvas = rect)
                                            else checkNotNull(initial).withCrop(rect)
                                        )
                                        snapped =
                                            frameSnapFeedback(previous, drag.snapped, snapped) {
                                                haptics.performHapticFeedback(
                                                    HapticFeedbackType.SegmentFrequentTick
                                                )
                                            }
                                    }
                                }
                                event.changes.forEach { it.consume() }
                                complete = event.changes.none { it.pressed }
                            } while (!complete)
                        } finally {
                            bitmap?.recycle()
                            if (!picking) {
                                if (complete && !transform) history?.commit() else history?.cancel()
                            }
                            snapped = null to null
                            selectionStroke = null
                            brushPoint = null
                        }
                    }
                }
        ) {
            val destination = Rect(origin, image * fit)
            if (covering) {
                val canvas = drawContext.canvas.nativeCanvas
                canvas.save()
                canvas.translate(origin.x, origin.y)
                canvas.scale(fit, fit)
                canvas.clipRect(0f, 0f, image.width, image.height)
                val cover = edits?.cover
                if (
                    cover != null &&
                        (cover.visible(editor.positionMs) ||
                            brushPoint != null ||
                            selectionStroke != null)
                )
                    Photos.cover(canvas, cover.strokes)
                val paint =
                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE
                    }
                for ((color, thickness) in
                    listOf(
                        android.graphics.Color.BLACK to 3.dp.toPx(),
                        android.graphics.Color.WHITE to 1.dp.toPx(),
                    )) {
                    paint.color = color
                    paint.strokeWidth = thickness / fit
                    brushPoint?.let { canvas.drawCircle(it.x, it.y, editor.brushWidth / 2, paint) }
                    selectionStroke?.let { canvas.drawPath(it.path(closed = false), paint) }
                }
                canvas.restore()
            }
            if (editor.tool == VideoTool.ROTATE) {
                val previewScale =
                    minOf(size.width / displaySize.width, size.height / displaySize.height) *
                        viewport.scale
                val frameSize =
                    Size(dimensions.width * previewScale, dimensions.height * previewScale)
                withTransform({
                    translate(center.x + viewport.offset.x, center.y + viewport.offset.y)
                    scale(flipX.value, flipY.value, Offset.Zero)
                    rotate(angle.value, Offset.Zero)
                }) {
                    drawImageBorder(
                        Rect(Offset(-frameSize.width / 2, -frameSize.height / 2), frameSize),
                        borderColor,
                    )
                }
            } else drawImageBorder(destination, borderColor)
            if (bounds != null)
                drawFrameOverlay(
                    destination,
                    Rect(origin + bounds.topLeft * fit, bounds.size * fit),
                    expanding,
                    frameColor,
                    imageColor,
                    snapColor,
                    Offset(verticalHighlight.value, horizontalHighlight.value),
                    showGuides = !picking,
                )
            pickedPoint?.let { drawColorPicker(origin + it * fit) }
        }
        val initial = if (expanding) fitted() else ImageViewport(initialScale)
        ViewportToolbar(
            viewport,
            initial,
            minScale,
            Modifier.align(Alignment.TopEnd).onSizeChanged { toolbar = it },
            zoom = { factor ->
                viewport =
                    viewport.transformed(
                        factor,
                        Offset.Zero,
                        canvas.center,
                        canvas,
                        image,
                        expanding,
                        minScale,
                    )
            },
            reset = { viewport = initial },
        ) {
            val muted = editor.playbackMuted || video.info.audioTracks.isEmpty()
            ToolButton(
                if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
                if (muted) R.string.unmute_playback else R.string.mute_playback,
                video.info.audioTracks.isNotEmpty(),
            ) {
                editor.dropper = false
                editor.playbackMuted = !editor.playbackMuted
            }
        }
        if (loading) CanvasLoading()
    }
}
