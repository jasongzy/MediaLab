package com.jasongzy.medialab

import androidx.compose.runtime.*
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import java.util.IdentityHashMap
import kotlin.math.ceil

internal enum class VideoFormat(val extension: String) {
    MP4("mp4"),
    MOV("mov"),
    MKV("mkv"),
    WEBM("webm"),
    GIF("gif"),
}

internal enum class VideoTool(val label: Int, val icon: Int) {
    TIME(R.string.video_time, R.drawable.ic_time),
    CROP(R.string.crop, R.drawable.ic_crop),
    EXPAND(R.string.expand_canvas, R.drawable.ic_expand),
    ROTATE(R.string.rotate, R.drawable.ic_rotate),
    COVER(R.string.cover, R.drawable.ic_brush),
    RESIZE(R.string.resize, R.drawable.ic_resize),
    CLEAN(R.string.clean, R.drawable.ic_clean),
    EXTRACT(R.string.video_extract, R.drawable.ic_photo),
    INFO(R.string.info, R.drawable.ic_info),
    EXPORT(R.string.export, R.drawable.ic_save);

    val editable
        get() = this in listOf(TIME, CROP, EXPAND, ROTATE, COVER, RESIZE)
}

internal data class VideoRotation(
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
) {
    val changed
        get() = quarterTurns % 4 != 0 || flipHorizontal || flipVertical

    fun rotate(clockwise: Boolean) =
        copy(
            quarterTurns =
                quarterTurns + if (clockwise != (flipHorizontal xor flipVertical)) 1 else -1
        )

    fun flip(vertical: Boolean) =
        if (vertical) copy(flipVertical = !flipVertical) else copy(flipHorizontal = !flipHorizontal)

    fun normalized() =
        VideoRotation(
            (Math.floorMod(quarterTurns, 4) + if (flipVertical) 2 else 0) % 4,
            flipHorizontal xor flipVertical,
        )

    fun size(input: IntSize) =
        if (quarterTurns % 2 == 0) input else IntSize(input.height, input.width)
}

internal data class VideoCover(val strokes: List<Stroke>, val startMs: Long, val endMs: Long) {
    fun visible(positionMs: Long) = positionMs in startMs until endMs
}

internal data class VideoEdits(
    val endMs: Long,
    val crop: IntRect,
    val canvas: IntRect,
    val startMs: Long = 0,
    val beforeMs: Long = 0,
    val afterMs: Long = 0,
    val speedPercent: Int = 100,
    val holdFrames: Boolean = false,
    val timeColor: Int = 0xff000000.toInt(),
    val background: Int = 0xffffffff.toInt(),
    val cropRatio: Float = 0f,
    val canvasRatio: Float = 0f,
    val width: Int = 0,
    val height: Int = 0,
    val rotation: VideoRotation = VideoRotation(),
    val bitrateKbps: Int = 6000,
    val fps: Int = 30,
    val format: VideoFormat = VideoFormat.MP4,
    val mute: Boolean = false,
    val gifColors: Int = 256,
    val gifLoop: Boolean = false,
    val clearMetadata: Boolean = false,
    val metadataEdits: Map<VideoTag, String?> = emptyMap(),
    val cover: VideoCover? = null,
    val previous: List<VideoEdits> = emptyList(),
) {
    val time get() = MediaTimeEdit(endMs, startMs, beforeMs, afterMs, speedPercent)

    fun withTime(time: MediaTimeEdit) = copy(
        startMs = time.startMs, endMs = time.endMs, beforeMs = time.beforeMs,
        afterMs = time.afterMs, speedPercent = time.speedPercent,
    )

    val steps
        get() = previous + copy(previous = emptyList())

    val durationMs
        get() = time.durationMs

    fun sourcePosition(positionMs: Long): Long =
        steps.asReversed().fold(positionMs) { position, step ->
            ((position - step.beforeMs) * step.speedPercent / 100 + step.startMs).coerceIn(
                step.startMs,
                step.endMs - 1,
            )
        }

    fun outputPosition(sourceMs: Long): Long =
        steps.fold(sourceMs) { position, step ->
            ((position - step.startMs) * 100 / step.speedPercent + step.beforeMs).coerceIn(
                0,
                step.durationMs - 1,
            )
        }

    fun positionFrom(other: VideoEdits, positionMs: Long): Long {
        val before = other.steps
        val after = steps
        val shared = before.zip(after).takeWhile { (a, b) -> a == b }.size
        var position = positionMs
        for (step in before.drop(shared).asReversed()) position = step.sourcePosition(position)
        for (step in after.drop(shared)) position = step.outputPosition(position)
        return position.coerceIn(0, durationMs - 1)
    }

    fun next() =
        VideoEdits(
            durationMs,
            IntRect(0, 0, size.width, size.height),
            IntRect(0, 0, size.width, size.height),
            bitrateKbps = bitrateKbps,
            fps = fps,
            clearMetadata = clearMetadata,
            metadataEdits = metadataEdits,
            previous = steps,
        )

    fun isIdentity(inputSize: IntSize, duration: Long) =
        cover == null &&
            startMs == 0L &&
            endMs == duration &&
            beforeMs == 0L &&
            afterMs == 0L &&
            speedPercent == 100 &&
            !rotation.normalized().changed &&
            crop == IntRect(0, 0, inputSize.width, inputSize.height) &&
            canvas == crop &&
            size == inputSize

    val size
        get(): IntSize {
            val rotated = rotation.size(IntSize(canvas.width, canvas.height))
            return IntSize(
                if (width > 0) width else rotated.width,
                if (height > 0) height else rotated.height,
            )
        }

    fun withCrop(bounds: IntRect, ratio: Float = cropRatio) =
        copy(
            crop = bounds,
            cropRatio = ratio,
            canvas =
                IntRect(
                    canvas.left,
                    canvas.top,
                    bounds.width + canvas.right - crop.width,
                    bounds.height + canvas.bottom - crop.height,
                ),
            canvasRatio = 0f,
        )

    fun applyTool(draft: VideoEdits, tool: VideoTool): VideoEdits =
        when (tool) {
            VideoTool.TIME ->
                copy(
                    startMs = draft.startMs,
                    endMs = draft.endMs,
                    beforeMs = draft.beforeMs,
                    afterMs = draft.afterMs,
                    speedPercent = draft.speedPercent,
                    holdFrames =
                        if (draft.beforeMs > 0 || draft.afterMs > 0) draft.holdFrames
                        else holdFrames,
                    timeColor =
                        if (!draft.holdFrames && (draft.beforeMs > 0 || draft.afterMs > 0))
                            draft.timeColor
                        else timeColor,
                )
            VideoTool.CROP ->
                if (crop == draft.crop) this else withCrop(draft.crop, draft.cropRatio)
            VideoTool.EXPAND ->
                if (canvas == draft.canvas) this
                else
                    copy(
                        canvas = draft.canvas,
                        canvasRatio = draft.canvasRatio,
                        background = draft.background,
                    )
            VideoTool.ROTATE -> copy(rotation = draft.rotation.normalized())
            VideoTool.COVER ->
                draft.cover?.takeIf { it.strokes.isNotEmpty() }?.let { copy(cover = it) } ?: this
            VideoTool.RESIZE ->
                if (size == draft.size) this else copy(width = draft.width, height = draft.height)
            else -> this
        }

    fun validate(source: IntSize, duration: Long) {
        var inputSize = source
        var inputDuration = duration
        for ((index, step) in steps.withIndex()) {
            if (index != 0 || previous.isEmpty() || !step.isIdentity(source, duration))
                step.validateStep(inputSize, inputDuration)
            inputSize = step.size
            inputDuration = step.durationMs
        }
    }

    private fun validateStep(source: IntSize, duration: Long) {
        time.validate(duration)
        require(
            crop.left >= 0 &&
                crop.top >= 0 &&
                crop.right <= source.width &&
                crop.bottom <= source.height
        )
        require(crop.width > 0 && crop.height > 0)
        require(
            canvas.left <= 0 &&
                canvas.top <= 0 &&
                canvas.right >= crop.width &&
                canvas.bottom >= crop.height
        )
        require(validSize)
        require(fps in 1..120 && bitrateKbps in 100..200000 && gifColors in 4..256)
        cover?.let {
            require(it.startMs >= 0 && it.endMs in (it.startMs + 1)..(endMs - startMs))
            require(
                it.strokes.all { stroke ->
                    stroke.isDrawable &&
                        stroke.color ushr 24 == 255 &&
                        stroke.width.isFinite() &&
                        stroke.width > 0 &&
                        stroke.points.all { (x, y) -> x.isFinite() && y.isFinite() }
                }
            )
        }
    }

    val validSize
        get() =
            canvas.width in 1..8192 &&
                canvas.height in 1..8192 &&
                size.width in 1..8192 &&
                size.height in 1..8192
}

internal class VideoEditor(initial: VideoEdits) {
    val history = ToolHistory(initial)
    val original = initial
    private var drafts by mutableStateOf(newDrafts())
    private val appliedDrafts = IdentityHashMap<VideoEdits, VideoDraft>()
    var tool by mutableStateOf(VideoTool.TIME)
    var positionMs by mutableLongStateOf(0)
    var seekPosition by mutableStateOf<Long?>(null)
    var lockRatio by mutableStateOf(true)
    var resize by mutableStateOf(initial.size)
    var rotation by mutableStateOf(VideoRotation())
    var inputValid by mutableStateOf(true)
    var inputRevision by mutableIntStateOf(0)
    var showPreview by mutableStateOf(false)
    var playbackMuted by mutableStateOf(false)
    var dropper by mutableStateOf(false)
    var holdFrames by mutableStateOf(initial.holdFrames)
    var timeColor by mutableIntStateOf(initial.timeColor)
    var background by mutableIntStateOf(initial.background)
    var format by mutableStateOf<VideoFormat?>(null)
    var fps by mutableIntStateOf(initial.fps)
    var bitrateKbps by mutableIntStateOf(initial.bitrateKbps)
    var mute by mutableStateOf(initial.mute)
    var gifColors by mutableIntStateOf(initial.gifColors)
    var gifLoop by mutableStateOf(initial.gifLoop)
    var clearMetadata by mutableStateOf(false)
    var coverFill by mutableStateOf(true)
    var coverShape by mutableStateOf(CoverShape.RECTANGLE)
    var coverColor by mutableIntStateOf(0xffffffff.toInt())
    var brushWidth by mutableFloatStateOf(40f)

    fun capture() = if (tool == VideoTool.CLEAN) null else VideoDraft(
        tool, drafts[tool]?.snapshot(), resize, lockRatio, rotation, holdFrames, timeColor,
        background, coverFill, coverShape, coverColor, brushWidth,
    )

    fun commit(next: VideoEdits, draft: VideoDraft? = null, action: Int = tool.label) {
        history.change(next, action)
        if (draft != null && history.value === next) appliedDrafts[next] = draft
        val retained = history.snapshot().steps
        appliedDrafts.keys.removeAll { key -> retained.none { it === key } }
    }

    fun navigationDraft(direction: Int): VideoDraft? {
        val snapshot = history.snapshot()
        // After redo, the following operation is still unapplied.
        val index = snapshot.position + if (direction < 0) 0 else 2
        return snapshot.steps.getOrNull(index)?.let(appliedDrafts::get)
    }

    fun restore(draft: VideoDraft) {
        tool = draft.tool
        draft.history?.let { checkNotNull(drafts[tool]).restore(it) }
        when (tool) {
            VideoTool.TIME -> {
                holdFrames = draft.holdFrames
                timeColor = draft.timeColor
            }
            VideoTool.EXPAND -> background = draft.background
            VideoTool.ROTATE -> rotation = draft.rotation
            VideoTool.RESIZE -> {
                resize = draft.resize
                lockRatio = draft.lockRatio
            }
            VideoTool.COVER -> {
                coverFill = draft.coverFill
                coverShape = draft.coverShape
                coverColor = draft.coverColor
                brushWidth = draft.brushWidth
            }
            else -> Unit
        }
    }

    val draft
        get() = checkNotNull(drafts[tool])

    val current
        get() = history.value.next()

    val pending
        get() =
            VideoTool.entries.filter { tool ->
                tool.editable &&
                    applyTool(tool)
                        .copy(
                            cropRatio = 0f,
                            canvasRatio = 0f,
                        ) != current
            }

    fun applyTool(tool: VideoTool): VideoEdits =
        if (tool == VideoTool.CLEAN) history.value.copy(clearMetadata = true, metadataEdits = emptyMap())
        else
            current.applyTool(
                when (tool) {
                    VideoTool.RESIZE -> current.copy(width = resize.width, height = resize.height)
                    VideoTool.ROTATE -> current.copy(rotation = rotation)
                    else ->
                        checkNotNull(drafts[tool])
                            .value
                            .copy(
                                holdFrames = holdFrames,
                                timeColor = timeColor,
                                background = background,
                            )
                },
                tool,
            )

    val output
        get() =
            history.value.copy(
                format = format ?: original.format,
                fps = fps,
                bitrateKbps = bitrateKbps,
                mute = mute,
                gifColors = gifColors,
                gifLoop = gifLoop,
            )

    private fun newDrafts() =
        listOf(VideoTool.TIME, VideoTool.CROP, VideoTool.EXPAND, VideoTool.COVER).associateWith {
            ToolHistory(
                if (it == VideoTool.COVER)
                    current.copy(cover = VideoCover(emptyList(), 0, current.durationMs))
                else current
            )
        }

    fun resetDrafts() {
        dropper = false
        seekPosition = null
        holdFrames = current.holdFrames
        timeColor = current.timeColor
        background = current.background
        resize = current.size
        rotation = VideoRotation()
        clearMetadata = false
        drafts = newDrafts()
        inputRevision++
        inputValid = true
    }
}

internal data class VideoDraft(
    val tool: VideoTool,
    val history: ToolHistory.Snapshot<VideoEdits>?,
    val resize: IntSize,
    val lockRatio: Boolean,
    val rotation: VideoRotation,
    val holdFrames: Boolean,
    val timeColor: Int,
    val background: Int,
    val coverFill: Boolean,
    val coverShape: CoverShape,
    val coverColor: Int,
    val brushWidth: Float,
)


internal data class VideoFrameRange(val start: Long, val count: Long, val outputCount: Long)

internal fun videoFrameRanges(steps: List<VideoEdits>, fps: Int): List<VideoFrameRange> {
    var available = Long.MAX_VALUE
    return steps.mapIndexed { index, step ->
        val start = if (index == 0) 0 else minOf((step.startMs * fps + 999) / 1000, available - 1)
        val count = minOf(((step.endMs - step.startMs) * fps + 999) / 1000, available - start)
        check(count > 0) { "No frame in the selected interval" }
        val divisor = 1000L * step.speedPercent
        val outputCount =
            minOf(
                ((step.endMs - step.startMs) * fps * 100 + divisor - 1) / divisor,
                (count * 100 + step.speedPercent - 1) / step.speedPercent,
            )
        available =
            outputCount + (step.beforeMs * fps + 500) / 1000 + (step.afterMs * fps + 500) / 1000
        VideoFrameRange(start, count, outputCount)
    }
}

internal fun videoFrameSource(
    steps: List<VideoEdits>,
    positionMs: Long,
    fps: Int,
    activeCovers: MutableSet<Int>? = null,
): Pair<Long, Int> {
    val ranges = videoFrameRanges(steps, fps)
    val last = steps.last()
    val available =
        ranges.last().outputCount +
            (last.beforeMs * fps + 500) / 1000 +
            (last.afterMs * fps + 500) / 1000
    var frame = (positionMs * fps / 1000).coerceIn(0, available - 1)
    for (index in steps.indices.reversed()) {
        val step = steps[index]
        val range = ranges[index]
        frame -= (step.beforeMs * fps + 500) / 1000
        if (!step.holdFrames && frame !in 0 until range.outputCount) return 0L to index
        frame =
            (frame.coerceIn(0, range.outputCount - 1) * step.speedPercent / 100).coerceAtMost(
                range.count - 1
            )
        if (step.cover?.visible(frame * 1000 / fps) == true) activeCovers?.add(index)
        frame += range.start
    }
    // Match the fps filter's downward timestamp rounding.
    return (steps.first().startMs * 1000 + ((frame + 1) * 1_000_000 + fps - 1) / fps - 1) to -1
}

internal fun videoFramePosition(positionMs: Long, durationMs: Long, fps: Double): Pair<Long, Long> {
    val total = ceil(durationMs * fps / 1000).toLong().coerceAtLeast(1)
    val frame = (positionMs.coerceAtLeast(0) * fps / 1000).toLong().coerceAtMost(total - 1) + 1
    return frame to total
}

internal fun evenVideoSize(size: IntSize) =
    IntSize((size.width + 1) / 2 * 2, (size.height + 1) / 2 * 2)
