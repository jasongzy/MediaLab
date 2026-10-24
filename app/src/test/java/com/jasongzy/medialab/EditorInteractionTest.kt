package com.jasongzy.medialab

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.unit.IntSize
import com.kyant.taglib.Picture
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EditorInteractionTest {
    @Test
    fun resolutionPresetsFitTheFrameAndKeepTheOrientation() {
        assertEquals(IntSize(854, 480), resizePresetSize(IntSize(1920, 1080), 480))
        assertEquals(IntSize(720, 1280), resizePresetSize(IntSize(1080, 1920), 720))
        assertEquals(IntSize(1440, 1080), resizePresetSize(IntSize(4000, 3000), 1080))
        assertEquals(IntSize(2560, 1440), resizePresetSize(IntSize(1920, 1080), 1440))
        assertEquals(IntSize(3840, 1646), resizePresetSize(IntSize(7000, 3000), 2160))
    }

    @Test
    fun colorWarningsFollowCurrentFeaturesAndActualExportPath() {
        val p3 = PhotoColor("Display P3", "RGB", true, false)
        assertTrue(p3.losses().isEmpty())
        assertEquals(listOf(R.string.hdr), p3.copy(hdr = true).losses())
        assertEquals(listOf(R.string.wide_gamut), p3.losses(forceSrgb = true))
        assertEquals(listOf(R.string.wide_gamut, R.string.hdr), p3.copy(hdr = true, convertsToSrgb = true).losses())
        assertTrue(PhotoColor("sRGB", wide = false, hdr = false).losses(forceSrgb = true).isEmpty())
        assertEquals(listOf(R.string.source_color_space), PhotoColor("Custom RGB", convertsToSrgb = true).losses())
        val info = PhotoInfo(1, 1, 1, 1, 1, "image/jpeg", 5, PhotoColor(), null, emptyList())
        val clean = ExportOptions(metadata = true)
        assertTrue(clean.preservesSource(info))
        assertFalse(clean.preservesSource(info.copy(orientation = 6)))
        assertFalse(clean.preservesSource(info.copy(mime = "image/heif")))
        assertFalse(ExportOptions(format = ImageFormat.PNG).preservesSource(info))
        assertTrue(canClean(info.copy(color = p3), ExportOptions(metadata = false, keepColor = false), false))
    }

    @Test
    fun audioMetadataDraftsHaveIndependentHistoryAndApplySeparately() {
        val metadata = AudioMetadata(mapOf("TITLE" to listOf("Original")), emptyList())
        val audio = AudioDocument(File("song.mp3"), "song.mp3", null, metadata, emptyList())
        val editor = audio.editor
        val tags = mapOf("TITLE" to listOf("Edited"))
        val picture = Picture(byteArrayOf(1, 2, 3), "Cover", "Front Cover", "image/png")
        editor.tags.change(tags)
        editor.pictures.change(listOf(picture))
        assertEquals(listOf(AudioTool.TAGS, AudioTool.ARTWORK), editor.pending)
        assertEquals(metadata.tags, audio.tags)
        assertTrue(audio.dirty)
        editor.tags.undo()
        assertEquals(listOf(AudioTool.ARTWORK), editor.pending)
        editor.tags.redo()
        editor.commit(editor.history.value.copy(metadata = metadata.copy(tags = tags)), R.string.tags)
        assertEquals(tags, audio.tags)
        assertFalse(editor.tags.canUndo)
        assertTrue(editor.pictures.canUndo)
        assertEquals(listOf(AudioTool.ARTWORK), editor.pending)
        assertTrue(audio.pictures.isEmpty())
        editor.pictures.undo()
        assertTrue(editor.pending.isEmpty())
        editor.pictures.redo()
        editor.commit(editor.history.value.copy(metadata = AudioMetadata(tags, listOf(picture))), R.string.artwork)
        assertFalse(editor.pictures.canUndo)
        assertSame(picture.data, audio.pictures.single().data)
        assertFalse(audio.copy(saved = editor.history.value).dirty)
        editor.history.undo()
        editor.resetMetadata()
        assertTrue(audio.pictures.isEmpty())
        assertTrue(editor.pending.isEmpty())
    }

    @Test
    fun cleanupRequiresAnApplicableSelection() {
        val info = PhotoInfo(1, 1, 1, 1, 1, "image/png", null, PhotoColor(), null, emptyList())
        val defaults = ExportOptions(metadata = true, static = true, flatten = true)
        assertTrue(canClean(info, defaults, hasSource = true))
        assertFalse(canClean(info, defaults.copy(metadata = false), hasSource = true))
        val changed = defaults.copy(keepColor = false)
        assertTrue(canClean(info, changed, hasSource = true))
        assertFalse(canClean(info, changed, hasSource = false))
        assertTrue(canClean(info.copy(metadata = listOf("Software" to "Camera")), changed, hasSource = false))
    }

    @Test
    fun expandedCanvasCanZoomOutAndPanBeyondTheImage() {
        val canvas = Size(400f, 300f)
        val image = Size(800f, 600f)
        val view = ImageViewport(0.6f)
        val pan = Offset(80f, -60f)
        val zoomed = view.transformed(0.25f, pan, canvas.center, canvas, image, outside = true)
        assertEquals(0.15f, zoomed.scale, 0.0001f)
        assertEquals(pan, zoomed.offset)
        val before = view.imagePoint(canvas.center, canvas, image)
        val after = zoomed.imagePoint(canvas.center + pan, canvas, image)
        assertEquals(0f, (before - after).getDistance(), 0.001f)
        val smallest = zoomed.transformed(0.000001f, Offset.Zero, canvas.center, canvas, image, outside = true)
        assertEquals(ImageViewport.MIN_OUTSIDE_SCALE, smallest.scale, 0f)
        val constrained = smallest.constrained(canvas, image)
        assertEquals(ImageViewport.MIN_SCALE, constrained.scale, 0f)
        assertEquals(0f, constrained.offset.getDistance(), 0f)
        val bounds = Rect(-800f, -1200f, 800f, 600f)
        val fitted = ImageViewport.fit(canvas, image, bounds)
        assertEquals(0f, (fitted.imagePoint(canvas.center, canvas, image) - bounds.center).getDistance(), 0.001f)
        val topLeft = fitted.imagePoint(Offset.Zero, canvas, image)
        val bottomRight = fitted.imagePoint(Offset(canvas.width, canvas.height), canvas, image)
        assertTrue(topLeft.x < bounds.left && topLeft.y < bounds.top)
        assertTrue(bottomRight.x > bounds.right && bottomRight.y > bounds.bottom)
    }

    @Test
    fun lassoRequiresBoundaryButBrushSupportsSinglePixel() {
        val dot = Stroke(listOf(10f to 10f), -1, 40f, CoverShape.BRUSH)
        assertTrue(dot.isDrawable)
        assertFalse(dot.copy(shape = CoverShape.LASSO).isDrawable)
        assertFalse(
            dot.copy(points = listOf(0f to 0f, 20f to 30f), shape = CoverShape.LASSO).isDrawable
        )
        assertTrue(
            dot.copy(points = listOf(0f to 0f, 20f to 30f, 40f to 0f), shape = CoverShape.LASSO)
                .isDrawable
        )
    }

    @Test
    fun regularFillsAcceptBothDragDirectionsAndRequireAnArea() {
        for (shape in listOf(CoverShape.RECTANGLE, CoverShape.ELLIPSE)) {
            val stroke = Stroke(listOf(20f to 30f), 0, 40f, shape)
            assertTrue(stroke.fill)
            assertFalse(stroke.isDrawable)
            for (corner in listOf(0f to 0f, 40f to 60f, 0f to 60f, 40f to 0f)) assertTrue(
                stroke.copy(points = stroke.points + corner).isDrawable
            )
            for (corner in listOf(20f to 0f, 0f to 30f, 20f to 30f)) assertFalse(
                stroke.copy(points = stroke.points + corner).isDrawable
            )
        }
    }

    @Test
    fun zoomOutCentersImageAndLeavesRoomForToolbarAndSelections() {
        val canvas = Size(400f, 300f)
        val image = Size(800f, 600f)
        val toolbarInset = 56f
        val minScale = ImageViewport.minimumScale(canvas, toolbarInset)
        var view = ImageViewport(4f, Offset(600f, -450f))
        view = view.transformed(0.5f, Offset.Zero, canvas.center, canvas, image, minScale = minScale)
        assertEquals(ImageViewport(2f, Offset(200f, -150f)), view)
        assertEquals(Offset(200f, 450f), view.imagePoint(canvas.center, canvas, image))
        view = view.transformed(0.5f, Offset.Zero, canvas.center, canvas, image, minScale = minScale)
        assertEquals(1f, view.scale, 0f)
        assertEquals(0f, view.offset.x, 0.001f)
        assertEquals(0f, view.offset.y, 0.001f)
        view = view.transformed(0.5f, Offset.Zero, canvas.center, canvas, image, minScale = minScale)
        assertEquals(ImageViewport(minScale), view)
        val limited = view.transformed(0.5f, Offset(100f, -50f), Offset.Zero, canvas, image, minScale = minScale)
        assertEquals(view.scale, limited.scale, 0f)
        assertEquals(0f, limited.offset.getDistance(), 0.001f)
        assertEquals(Offset(400f, 300f), view.imagePoint(canvas.center, canvas, image))
        val before = view.imagePoint(Offset(20f, 15f), canvas, image)
        val after = view.imagePoint(Offset(380f, 285f), canvas, image)
        assertTrue(before.x < 0f && before.y < 0f)
        assertTrue(after.x > image.width && after.y > image.height)
        for (area in listOf(canvas, Size(300f, 160f), Size(300f, 800f))) {
            val minimum = ImageViewport.minimumScale(area, toolbarInset)
            val fit = minOf(area.width / image.width, area.height / image.height)
            val top = (area.height - image.height * fit * minimum) / 2
            assertTrue(top >= toolbarInset - 0.001f)
        }
    }
}
