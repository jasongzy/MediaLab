package com.jasongzy.medialab

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class VideoEditsTest {
    @Test
    fun coverAndCleanupKeepDraftsHistoryAndTimeRangesConsistent() {
        val full = IntRect(0, 0, 96, 64)
        val initial = VideoEdits(1000, full, full)
        val editor = VideoEditor(initial)
        editor.draft.change(initial.copy(startMs = 100))
        editor.commit(editor.applyTool(VideoTool.TIME), editor.capture())
        editor.resetDrafts()
        editor.tool = VideoTool.COVER
        val stroke = Stroke(listOf(0f to 0f, 96f to 64f), -1, 10f, CoverShape.RECTANGLE)
        val cover = VideoCover(listOf(stroke), 200, 600)
        editor.draft.change(editor.draft.value.copy(cover = cover))
        val second = editor.draft.value.copy(cover = cover.copy(endMs = 800))
        editor.draft.change(second)
        editor.draft.undo()
        assertEquals(listOf(VideoTool.COVER), editor.pending)
        editor.draft.undo()
        assertTrue(editor.pending.isEmpty())
        editor.draft.redo()
        val applied = editor.applyTool(editor.tool)
        applied.validate(initial.size, 1000)
        editor.commit(applied, editor.capture())
        editor.resetDrafts()
        assertTrue(editor.draft.value.cover!!.strokes.isEmpty())
        assertTrue(editor.pending.isEmpty())
        val saved = checkNotNull(editor.navigationDraft(-1))
        editor.history.undo()
        editor.history.undo()
        assertSame(saved, editor.navigationDraft(1))
        editor.history.redo()
        editor.resetDrafts()
        editor.restore(saved)
        assertEquals(listOf(VideoTool.COVER), editor.pending)
        assertEquals(cover, editor.draft.value.cover)
        assertTrue(editor.draft.canUndo && editor.draft.canRedo)
        editor.draft.redo()
        assertEquals(second, editor.draft.value)
        editor.draft.change(second.copy(cover = cover.copy(startMs = 100)))
        editor.resetDrafts()
        editor.restore(saved)
        assertEquals(cover, editor.draft.value.cover)
        assertTrue(editor.draft.canRedo)
        editor.history.redo()
        editor.resetDrafts()
        editor.tool = VideoTool.CLEAN
        editor.clearMetadata = true
        assertTrue(editor.pending.isEmpty())
        assertNull(editor.capture())
        editor.commit(editor.applyTool(VideoTool.CLEAN), editor.capture())
        editor.resetDrafts()
        assertTrue(editor.output.clearMetadata)
        assertEquals(applied.steps.size, editor.output.steps.size)
        editor.history.undo()
        assertFalse(editor.output.clearMetadata)
        editor.history.redo()
        assertTrue(editor.output.clearMetadata)
        editor.commit(initial)
        assertNull(editor.navigationDraft(-1))
        editor.history.undo()
        assertNull(editor.navigationDraft(-1))
        for (position in listOf(199L, 200L, 599L, 600L)) {
            val active = mutableSetOf<Int>()
            videoFrameSource(listOf(applied.copy(previous = emptyList())), position, 10, active)
            assertEquals(position in 200L until 600L, active.isNotEmpty())
        }
        assertThrows(IllegalArgumentException::class.java) {
            applied.copy(cover = cover.copy(endMs = 1001)).validate(initial.size, 1000)
        }
        assertEquals(256, editor.gifColors)
        assertFalse(editor.gifLoop)
        assertThrows(IllegalArgumentException::class.java) {
            initial.copy(gifColors = 257).validate(initial.size, 1000)
        }
    }

    @Test
    fun speedKeepsTimeMappingAndBothHistoriesConsistent() {
        val full = IntRect(0, 0, 96, 64)
        val initial = VideoEdits(10000, full, full)
        val editor = VideoEditor(initial)
        val draft =
            editor.draft.value.copy(
                startMs = 1000,
                endMs = 9000,
                beforeMs = 500,
                afterMs = 250,
                speedPercent = 200,
            )
        editor.draft.change(draft)
        assertEquals(listOf(VideoTool.TIME), editor.pending)
        editor.draft.undo()
        assertTrue(editor.pending.isEmpty())
        editor.draft.redo()
        val fast = editor.applyTool(VideoTool.TIME)
        fast.validate(initial.size, initial.durationMs)
        assertEquals(4750L, fast.durationMs)
        assertEquals(1000L, fast.sourcePosition(0))
        assertEquals(3000L, fast.sourcePosition(1500))
        assertEquals(1500L, fast.outputPosition(3000))
        assertEquals(1500L, fast.positionFrom(initial, 3000))
        assertEquals(3000L, initial.positionFrom(fast, 1500))
        editor.history.change(fast)
        editor.resetDrafts()
        assertEquals(100, editor.draft.value.speedPercent)
        assertTrue(editor.pending.isEmpty())
        editor.history.undo()
        assertEquals(10000L, editor.output.durationMs)
        editor.history.redo()
        assertEquals(4750L, editor.output.durationMs)
        assertEquals(9500L, fast.next().copy(speedPercent = 50).durationMs)
        val short = VideoEdits(1000, full, full)
        assertEquals(
            20L,
            videoFrameRanges(listOf(short.copy(speedPercent = 150)), 30).single().outputCount,
        )
        assertEquals(
            2L,
            videoFrameRanges(listOf(short.copy(speedPercent = 800)), 10).single().outputCount,
        )
        assertEquals(
            599999L to -1,
            videoFrameSource(listOf(short.copy(speedPercent = 50)), 1000, 10),
        )
        assertEquals(
            299999L to -1,
            videoFrameSource(listOf(short.copy(speedPercent = 200)), 100, 10),
        )
        assertEquals(
            99999L to -1,
            videoFrameSource(
                short.copy(speedPercent = 200).next().copy(speedPercent = 50).steps,
                100,
                10,
            ),
        )
        assertEquals(1L, short.copy(endMs = 1, speedPercent = 800).durationMs)
        for (speed in listOf(0, 9, 801)) {
            assertThrows(IllegalArgumentException::class.java) {
                short.copy(speedPercent = speed).validate(short.size, 1000)
            }
        }
    }

    @Test
    fun rotationUsesScreenAxesAndKeepsAppliedHistorySeparate() {
        val original = VideoRotation()
        val fullTurn = (1..4).fold(original) { value, _ -> value.rotate(true) }
        assertEquals(4, fullTurn.quarterTurns)
        assertEquals(original, fullTurn.normalized())
        assertFalse(fullTurn.changed)
        assertEquals(original, original.rotate(true).rotate(false))
        assertEquals(original, original.flip(false).flip(false))
        assertEquals(original, original.flip(true).flip(true))
        assertEquals(VideoRotation(3, true), original.flip(false).rotate(true).normalized())
        assertEquals(VideoRotation(1, true), original.rotate(true).flip(false).normalized())
        assertEquals(VideoRotation(2), original.flip(true).flip(false).normalized())
        assertEquals(-1, original.rotate(false).quarterTurns)
        assertEquals(VideoRotation(3), original.rotate(false).normalized())
        val full = IntRect(0, 0, 1920, 1080)
        val initial = VideoEdits(1000, full, full)
        val editor = VideoEditor(initial)
        editor.tool = VideoTool.ROTATE
        editor.rotation = original.rotate(true)
        assertEquals(listOf(VideoTool.ROTATE), editor.pending)
        assertEquals(initial, editor.output)
        val applied = editor.applyTool(VideoTool.ROTATE)
        applied.validate(initial.size, initial.durationMs)
        assertEquals(IntSize(1080, 1920), applied.size)
        assertEquals(IntSize(360, 640), applied.copy(width = 360, height = 640).size)
        assertFalse(initial.copy(rotation = VideoRotation(2)).isIdentity(initial.size, 1000))
        editor.rotation = original
        assertTrue(editor.pending.isEmpty())
        editor.rotation = VideoRotation(2, true, true)
        assertTrue(editor.pending.isEmpty())
        assertTrue(editor.rotation.changed)
        editor.history.change(applied)
        editor.resetDrafts()
        assertEquals(original, editor.rotation)
        assertTrue(editor.pending.isEmpty())
        assertEquals(IntRect(0, 0, 1080, 1920), editor.current.crop)
        editor.current.withCrop(IntRect(10, 20, 1000, 1800)).validate(initial.size, 1000)
        editor.history.undo()
        editor.resetDrafts()
        assertEquals(initial.size, editor.current.size)
        editor.history.redo()
        editor.resetDrafts()
        assertEquals(applied.size, editor.current.size)
    }

    @Test
    fun framePositionUsesOneBasedIndicesAndClampsTheEnd() {
        assertEquals(1L to 150L, videoFramePosition(0, 5000, 30.0))
        assertEquals(2L to 150L, videoFramePosition(34, 5000, 30.0))
        assertEquals(150L to 150L, videoFramePosition(5000, 5000, 30.0))
        assertEquals(30L to 30L, videoFramePosition(1001, 1001, 30000.0 / 1001))
        assertEquals(1L to 1L, videoFramePosition(0, 1, 30.0))
    }

    @Test
    fun editsKeepTimeAndFrameBoundsConsistent() {
        val source = IntSize(1920, 1080)
        val full = IntRect(0, 0, source.width, source.height)
        val edits = VideoEdits(8000, full, full, startMs = 1000, beforeMs = 2000, afterMs = 500)
        edits.validate(source, 10000)
        assertEquals(9500L, edits.durationMs)
        assertEquals(1000L, edits.sourcePosition(0))
        assertEquals(1500L, edits.sourcePosition(2500))
        assertEquals(7999L, edits.sourcePosition(9499))
        assertEquals(2500L, edits.outputPosition(1500))
        val crop = edits.copy(width = 640, height = 360).withCrop(IntRect(5, 7, 1006, 708))
        assertEquals(IntRect(0, 0, 1001, 701), crop.canvas)
        assertEquals(IntSize(640, 360), crop.size)
        assertEquals(
            IntSize(1002, 702),
            evenVideoSize(IntSize(crop.canvas.width, crop.canvas.height)),
        )
        val expanded =
            crop.copy(canvas = IntRect(-10, -20, 1021, 731)).withCrop(IntRect(0, 0, 400, 200))
        assertEquals(IntRect(-10, -20, 420, 230), expanded.canvas)
        assertEquals(crop.size, expanded.size)
        crop.validate(source, 10000)
        for (invalid in
            listOf(
                edits.copy(endMs = 1000),
                edits.copy(endMs = 10001),
                edits.copy(beforeMs = Int.MAX_VALUE.toLong()),
                edits.copy(canvas = IntRect(0, 0, 100, 100)),
                edits.copy(width = 9000),
                edits.copy(canvas = IntRect(0, 0, 100000, 100000), width = 640, height = 360),
            )) {
            assertThrows(IllegalArgumentException::class.java) { invalid.validate(source, 10000) }
        }
    }

    @Test
    fun applyingDraftsKeepsExportAndHistorySeparate() {
        val full = IntRect(0, 0, 1920, 1080)
        val initial = VideoEdits(10000, full, full)
        val editor = VideoEditor(initial)
        editor.draft.change(initial.copy(startMs = 1000))
        editor.tool = VideoTool.CROP
        editor.draft.change(initial.withCrop(IntRect(10, 20, 1010, 520)))
        assertEquals(listOf(VideoTool.TIME, VideoTool.CROP), editor.pending)
        assertEquals(initial, editor.output)
        editor.format = VideoFormat.WEBM
        editor.history.change(editor.applyTool(editor.tool))
        editor.resetDrafts()
        assertTrue(editor.pending.isEmpty())
        assertEquals(0L, editor.output.startMs)
        assertEquals(IntSize(1000, 500), editor.output.size)
        assertEquals(IntRect(0, 0, 1000, 500), editor.draft.value.crop)
        assertEquals(VideoFormat.WEBM, editor.output.format)
        editor.history.undo()
        editor.resetDrafts()
        assertEquals(full, editor.draft.value.crop)
        assertEquals(VideoFormat.WEBM, editor.output.format)
        editor.history.redo()
        editor.resetDrafts()
        assertEquals(IntSize(1000, 500), editor.output.size)
        editor.tool = VideoTool.EXPAND
        editor.background = 0xff000000.toInt()
        assertFalse(editor.draft.canUndo)
        editor.draft.change(editor.draft.value.copy(canvasRatio = 2f))
        editor.tool = VideoTool.RESIZE
        editor.resize = IntSize(500, 250)
        assertEquals(listOf(VideoTool.RESIZE), editor.pending)
        assertEquals(editor.resize, editor.applyTool(VideoTool.RESIZE).size)
        editor.resize = IntSize(1000, 500)
        editor.tool = VideoTool.TIME
        val color = 0xff35a7c9.toInt()
        editor.timeColor = color
        assertFalse(editor.draft.canUndo)
        assertTrue(editor.pending.isEmpty())
        editor.draft.change(editor.draft.value.copy(beforeMs = 500))
        assertEquals(listOf(VideoTool.TIME), editor.pending)
        editor.draft.undo()
        assertEquals(color, editor.timeColor)
        assertTrue(editor.pending.isEmpty())
        editor.draft.redo()
        editor.history.change(editor.applyTool(editor.tool))
        editor.resetDrafts()
        assertEquals(color, editor.output.timeColor)
        editor.history.undo()
        assertEquals(0L, editor.output.beforeMs)
        editor.history.redo()
        assertEquals(color, editor.output.timeColor)
        editor.resetDrafts()
        editor.holdFrames = true
        assertTrue(editor.pending.isEmpty())
        assertFalse(editor.draft.canUndo)
        editor.draft.change(editor.draft.value.copy(beforeMs = 200, afterMs = 100))
        assertEquals(listOf(VideoTool.TIME), editor.pending)
        editor.history.change(editor.applyTool(editor.tool))
        editor.resetDrafts()
        assertTrue(editor.output.holdFrames)
        assertTrue(editor.pending.isEmpty())
        editor.history.undo()
        assertFalse(editor.output.holdFrames)
        editor.history.redo()
        assertTrue(editor.output.holdFrames)
    }

    @Test
    fun sequentialEditsUseTheCurrentCanvasAndTimeline() {
        val original = VideoEdits(10000, IntRect(0, 0, 100, 80), IntRect(0, 0, 100, 80))
        val expanded = original.next().copy(canvas = IntRect(-10, -20, 110, 100), beforeMs = 1000)
        val cropped = expanded.next().withCrop(IntRect(0, 0, 80, 60))
        val trimmed = cropped.next().copy(startMs = 500, endMs = 3000)
        trimmed.validate(original.size, original.durationMs)
        assertEquals(IntSize(80, 60), trimmed.size)
        assertEquals(2500L, trimmed.durationMs)
        assertEquals(3, trimmed.previous.size)
        assertEquals(0L, trimmed.next().startMs)
        assertEquals(IntRect(0, 0, 80, 60), trimmed.next().crop)
        assertEquals(400L, cropped.positionFrom(expanded, 400))
        assertEquals(200L, trimmed.positionFrom(cropped, 700))
        assertEquals(700L, cropped.positionFrom(trimmed, 200))
        assertEquals(500L, trimmed.sourcePosition(1000))
        assertThrows(IllegalArgumentException::class.java) {
            trimmed.next().copy(endMs = 3000).validate(original.size, original.durationMs)
        }
        val large = VideoEdits(10000, IntRect(0, 0, 10000, 9000), IntRect(0, 0, 10000, 9000))
        large.next().withCrop(IntRect(100, 100, 200, 200)).validate(large.size, large.durationMs)
    }

    @Test
    fun frameExtractionMatchesHeldEndpointsAndEarlierColorPadding() {
        val full = IntRect(0, 0, 96, 64)
        val held =
            VideoEdits(
                751,
                full,
                full,
                startMs = 251,
                beforeMs = 300,
                afterMs = 200,
                holdFrames = true,
            )
        assertEquals(350999L to -1, videoFrameSource(listOf(held), 0, 10))
        assertEquals(750999L to -1, videoFrameSource(listOf(held), 999, 10))
        val solid = held.copy(holdFrames = false)
        assertEquals(0L to 0, videoFrameSource(listOf(solid), 0, 10))
        assertEquals(0L to 0, videoFrameSource(listOf(solid), 999, 10))
        val next =
            solid
                .next()
                .copy(startMs = 150, endMs = 650, beforeMs = 100, afterMs = 100, holdFrames = true)
        assertEquals(0L to 0, videoFrameSource(next.steps, 0, 10))
        assertEquals(650999L to -1, videoFrameSource(next.steps, 699, 10))
        assertEquals(
            750999L to -1,
            videoFrameSource(next.copy(startMs = 137, endMs = 677).steps, 739, 10),
        )
        assertEquals(33333L to -1, videoFrameSource(listOf(held.copy(startMs = 0)), 0, 30))
        val tail = VideoEdits(1000, full, full).next().copy(startMs = 999)
        assertEquals(VideoFrameRange(9, 1, 1), videoFrameRanges(tail.steps, 10).last())
        assertEquals(999999L to -1, videoFrameSource(tail.steps, 0, 10))
    }

    @Test
    fun timeInputIsFiniteAndLocaleIndependent() {
        assertEquals(1234L, mediaTime("1.234"))
        assertEquals(1001L, mediaTime("1.001"))
        assertEquals("1.234", mediaSeconds(1234))
        for (text in listOf("", "NaN", "Infinity", "-1", "1.2.3")) assertNull(mediaTime(text))
    }
}
