package com.jasongzy.medialab

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class PhotoTool(val label: Int, val icon: Int) {
    CROP(R.string.crop, R.drawable.ic_crop),
    EXPAND(R.string.expand_canvas, R.drawable.ic_expand),
    ROTATE(R.string.rotate, R.drawable.ic_rotate),
    BACKGROUND(R.string.remove_background, R.drawable.ic_photo),
    COVER(R.string.cover, R.drawable.ic_brush),
    RESIZE(R.string.resize, R.drawable.ic_resize),
    COLORS(R.string.colors, R.drawable.ic_palette),
    CLEAN(R.string.clean, R.drawable.ic_clean),
    INFO(R.string.info, R.drawable.ic_info),
    EXPORT(R.string.export, R.drawable.ic_save),
}

internal class CutoutDraft(
    val mask: CutoutMask,
    val sourcePreview: Bitmap,
    val maskPreview: Bitmap,
) {
    val history = ToolHistory(emptyList<MaskEdit>())
    var showMask by mutableStateOf(true)
    var keep by mutableStateOf(true)
    var softEdges by mutableStateOf(false)
}

internal class ResizeSelection(width: Int, height: Int) {
    var width by mutableIntStateOf(width)
    var height by mutableIntStateOf(height)
    var lock by mutableStateOf(true)
    var method by mutableIntStateOf(2)
    var preview by mutableStateOf<Bitmap?>(null)
}

internal class PhotoDraft(
    val tool: PhotoTool,
    val mask: CutoutMask? = null,
    val restore: suspend (PhotoEditorState, Bitmap) -> Unit,
)

internal class PhotoEditorState(info: PhotoInfo, hasAlpha: Boolean, previous: PhotoEditorState? = null) {
    var tool: PhotoTool by mutableStateOf(previous?.tool ?: PhotoTool.CROP)
    val cropHistory = ToolHistory(FrameSelection(Rect(0, 0, info.width, info.height)))
    val canvasHistory = ToolHistory(FrameSelection(Rect(0, 0, info.width, info.height)))
    val rotationHistory = ToolHistory(RotationSelection())
    val coverHistory = ToolHistory(emptyList<Stroke>())
    val resize = ResizeSelection(info.width, info.height)
    var background by mutableIntStateOf(if (hasAlpha) Color.TRANSPARENT else Color.WHITE)
    var rotationBackground by mutableIntStateOf(if (hasAlpha) Color.TRANSPARENT else Color.WHITE)
    var cutoutBackground: Int by mutableIntStateOf(previous?.cutoutBackground ?: Color.TRANSPARENT)
    var backgroundModel: BackgroundModel by mutableStateOf(previous?.backgroundModel ?: BackgroundModel.RMBG14)
    var cutout by mutableStateOf<CutoutDraft?>(null)
    var cleanup by mutableStateOf(ExportOptions(metadata = true, static = true, flatten = true))
    var fill: Boolean by mutableStateOf(previous?.fill ?: true)
    var fillShape: CoverShape by mutableStateOf(previous?.fillShape ?: CoverShape.RECTANGLE)
    var brushWidth: Float by mutableFloatStateOf(previous?.brushWidth ?: 40f)
    var color: Int by mutableIntStateOf(previous?.color ?: Color.WHITE)
    var lossless: Boolean by mutableStateOf(previous?.lossless ?: false)
    var dropper by mutableStateOf(false)
    var pixelX by mutableIntStateOf(info.width / 2)
    var pixelY by mutableIntStateOf(info.height / 2)
    var export: ExportOptions by mutableStateOf(previous?.export ?: ExportOptions())
    var exportPreviewOptions by mutableStateOf<ExportOptions?>(null)
    var showExportPreview by mutableStateOf(false)

    fun capture(applied: PhotoTool, info: PhotoInfo): PhotoDraft? {
        if (applied == PhotoTool.CLEAN) return null
        val restore: suspend (PhotoEditorState, Bitmap) -> Unit = when (applied) {
            PhotoTool.CROP -> {
                val steps = cropHistory.snapshot()
                val exact = lossless
                { editor, _ ->
                    editor.cropHistory.restore(steps)
                    editor.lossless = exact
                }
            }
            PhotoTool.EXPAND -> {
                val steps = canvasHistory.snapshot()
                val fill = background
                { editor, _ ->
                    editor.canvasHistory.restore(steps)
                    editor.background = fill
                }
            }
            PhotoTool.ROTATE -> {
                val steps = rotationHistory.snapshot()
                val fill = rotationBackground
                { editor, _ ->
                    editor.rotationHistory.restore(steps)
                    editor.rotationBackground = fill
                }
            }
            PhotoTool.COVER -> {
                val steps = coverHistory.snapshot()
                val fill = fill
                val shape = fillShape
                val width = brushWidth
                val color = color
                { editor, _ ->
                    editor.coverHistory.restore(steps)
                    editor.fill = fill
                    editor.fillShape = shape
                    editor.brushWidth = width
                    editor.color = color
                }
            }
            PhotoTool.RESIZE -> {
                val width = resize.width
                val height = resize.height
                val lock = resize.lock
                val method = resize.method
                { editor, _ ->
                    editor.resize.width = width
                    editor.resize.height = height
                    editor.resize.lock = lock
                    editor.resize.method = method
                }
            }
            PhotoTool.BACKGROUND -> {
                val draft = checkNotNull(cutout)
                val mask = draft.mask
                val steps = draft.history.snapshot()
                val show = draft.showMask
                val keep = draft.keep
                val soft = draft.softEdges
                val width = brushWidth
                val sourceWidth = info.width
                val sourceHeight = info.height
                val fill = cutoutBackground
                { editor, preview ->
                    val maskPreview = Photos.maskPreview(mask, preview.width, preview.height, sourceWidth, sourceHeight)
                    editor.cutout = CutoutDraft(mask, preview, maskPreview).also {
                        it.history.restore(steps)
                        it.showMask = show
                        it.keep = keep
                        it.softEdges = soft
                    }
                    editor.backgroundModel = mask.model
                    editor.brushWidth = width
                    editor.cutoutBackground = fill
                }
            }
            else -> error("This tool does not apply image edits")
        }
        return PhotoDraft(applied, cutout?.mask?.takeIf { applied == PhotoTool.BACKGROUND }, restore)
    }

    fun pending(info: PhotoInfo): List<PhotoTool> = buildList {
        val fullBounds = Rect(0, 0, info.width, info.height)
        if (cropHistory.value.bounds != fullBounds) add(PhotoTool.CROP)
        if (canvasHistory.value.bounds != fullBounds) add(PhotoTool.EXPAND)
        if (rotationHistory.value.changed) add(PhotoTool.ROTATE)
        if (cutout != null) add(PhotoTool.BACKGROUND)
        if (coverHistory.value.isNotEmpty()) add(PhotoTool.COVER)
        if (resize.width != info.width || resize.height != info.height) add(PhotoTool.RESIZE)
    }
}

internal fun canClean(info: PhotoInfo, options: ExportOptions, hasSource: Boolean): Boolean =
    if (hasSource) {
        options.metadata || !options.keepColor ||
            (options.static && info.container?.video != null) ||
            (options.flatten && info.container?.edit != null)
    } else (options.metadata && info.metadata.isNotEmpty()) ||
        (!options.keepColor && (info.gainmap || (info.color.space != null && info.color.space != "sRGB")))
