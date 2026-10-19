package com.jasongzy.medialab

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import com.kyant.taglib.Picture
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EditorInteractionTest {
    @Test
    fun colorWarningsFollowCurrentFeaturesAndActualExportPath() {
        assertTrue(PhotoColor(wide = false, hdr = false).losses.isEmpty())
        assertEquals(listOf(R.string.wide_gamut), PhotoColor(wide = true, hdr = false).losses)
        assertEquals(listOf(R.string.hdr), PhotoColor(wide = false, hdr = true).losses)
        assertEquals(listOf(R.string.wide_gamut, R.string.hdr), PhotoColor(wide = true, hdr = true).losses)
        val info = PhotoInfo(1, 1, 1, 1, 1, "image/jpeg", 5, PhotoColor(), null, emptyList())
        val clean = ExportOptions(metadata = true)
        assertTrue(clean.preservesSource(info))
        assertFalse(clean.preservesSource(info.copy(orientation = 6)))
        assertFalse(clean.preservesSource(info.copy(mime = "image/heif")))
        assertFalse(ExportOptions(format = ImageFormat.PNG).preservesSource(info))
    }

    @Test
    fun audioHistoryRestoresTagsAndArtworkAcrossSaveAndBranching() {
        val original = AudioMetadata(mapOf("TITLE" to listOf("Original")), emptyList())
        val audio = AudioDocument(File("song.mp3"), "song.mp3", null, original, emptyList())
        val history = audio.editor.history
        history.change(original)
        assertFalse(history.canUndo)
        assertFalse(audio.dirty)
        val tagged = original.copy(tags = mapOf("TITLE" to listOf("Edited")))
        history.change(tagged)
        val picture = Picture(byteArrayOf(1, 2, 3), "Cover", "Front Cover", "image/png")
        val illustrated = tagged.copy(pictures = listOf(picture))
        history.change(illustrated)
        history.undo()
        assertEquals(tagged.tags, audio.tags)
        assertTrue(audio.pictures.isEmpty())
        history.undo()
        assertFalse(audio.dirty)
        history.redo()
        history.redo()
        val saved = audio.copy(saved = history.value)
        assertFalse(saved.dirty)
        assertSame(picture.data, saved.pictures.single().data)
        history.undo()
        assertTrue(saved.dirty)
        history.redo()
        assertFalse(saved.dirty)
        history.change(saved.editor.original)
        assertEquals(original.tags, saved.tags)
        assertTrue(saved.pictures.isEmpty())
        assertTrue(saved.dirty)
        history.undo()
        assertEquals(illustrated, history.value)
        history.redo()
        assertEquals(original, history.value)
        history.undo()
        history.change(tagged)
        assertFalse(history.canRedo)
        assertTrue(saved.dirty)
        assertEquals(tagged.tags, saved.tags)
        assertFalse(AudioDocument(audio.file, audio.name, null, original, emptyList()).editor.history.canUndo)
    }

    @Test
    fun cleanupOnlyWarnsForChangedOptionsWithAnApplicableAction() {
        val info = PhotoInfo(1, 1, 1, 1, 1, "image/png", null, PhotoColor(), null, emptyList())
        val defaults = ExportOptions(metadata = true, static = true, flatten = true)
        assertFalse(hasPendingCleanup(info, defaults, hasSource = true))
        assertFalse(hasPendingCleanup(info, defaults.copy(metadata = false), hasSource = true))
        val changed = defaults.copy(keepColor = false)
        assertTrue(hasPendingCleanup(info, changed, hasSource = true))
        assertFalse(hasPendingCleanup(info, changed, hasSource = false))
        assertTrue(hasPendingCleanup(info.copy(metadata = listOf("Software" to "Camera")), changed, hasSource = false))
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
    fun zoomOutCentersImageAndLeavesRoomForSelectionsBeyondItsEdges() {
        val canvas = Size(400f, 300f)
        val image = Size(800f, 600f)
        var view = ImageViewport(4f, Offset(600f, -450f))
        view = view.transformed(0.5f, Offset.Zero, canvas.center, canvas, image)
        assertEquals(ImageViewport(2f, Offset(200f, -150f)), view)
        assertEquals(Offset(200f, 450f), view.imagePoint(canvas.center, canvas, image))
        view = view.transformed(0.5f, Offset.Zero, canvas.center, canvas, image)
        assertEquals(1f, view.scale, 0f)
        assertEquals(0f, view.offset.x, 0.001f)
        assertEquals(0f, view.offset.y, 0.001f)
        view = view.transformed(0.5f, Offset.Zero, canvas.center, canvas, image)
        assertEquals(ImageViewport(0.8f), view)
        val limited = view.transformed(0.5f, Offset(100f, -50f), Offset.Zero, canvas, image)
        assertEquals(view.scale, limited.scale, 0f)
        assertEquals(0f, limited.offset.getDistance(), 0.001f)
        assertEquals(Offset(400f, 300f), view.imagePoint(canvas.center, canvas, image))
        val before = view.imagePoint(Offset(20f, 15f), canvas, image)
        val after = view.imagePoint(Offset(380f, 285f), canvas, image)
        assertTrue(before.x < 0f && before.y < 0f)
        assertTrue(after.x > image.width && after.y > image.height)
    }
}
