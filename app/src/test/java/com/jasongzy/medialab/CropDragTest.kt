package com.jasongzy.medialab

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class CropDragTest {
    private val image = Size(1000f, 800f)
    private val original = Rect(200f, 200f, 600f, 400f)

    @Test
    fun movingSnapsGuidesWithScreenDistanceHysteresisAndPreservesDimensions() {
        val square = Rect(200f, 100f, 600f, 500f)
        for (fit in listOf(0.4f, 1f, 4f)) {
            val drag = CropDrag(square, square.center, image, 28f / fit, 0f, snapTolerance = 6f / fit)
            val target = image.center
            assertEquals(target, drag.update(target - Offset(4f, 4f) / fit).center)
            val aligned = drag.snapped
            assertEquals(2, aligned.size)
            assertEquals(target, drag.update(target + Offset(8f, 8f) / fit).center)
            assertEquals(aligned, drag.snapped)
            val released = drag.update(target + Offset(12f, 12f) / fit)
            assertEquals(target + Offset(12f, 12f) / fit, released.center)
            assertTrue(drag.snapped.isEmpty())
            assertEquals(square.size, released.size)
        }
        val thirds = CropDrag(original, original.center, image, 20f, 0f, snapTolerance = 6f)
        val frame = thirds.update(Offset(563f, 300f))
        assertEquals(500f, frame.left + frame.width / 3, 0.001f)
        val expanded = Rect(-200f, -100f, 1400f, 1000f)
        val move = CropDrag(expanded, expanded.center, image, 20f, 0f, expand = true, snapTolerance = 6f)
        assertEquals(Rect(-300f, -150f, 1300f, 950f), move.update(Offset(504f, 404f)))
    }

    @Test
    fun resizingSnapsWithoutMovingTheAnchorOrBreakingRatioAndImageBounds() {
        val full = Rect(Offset.Zero, image)
        for ((frame, start) in listOf(original to original.bottomRight, full to original.topLeft)) {
            val crop = CropDrag(frame, start, image, 20f, 2f, snapTolerance = 6f)
            val snapped = crop.update(Offset(796f, 498f))
            assertEquals(original.topLeft, snapped.topLeft)
            assertEquals(500f, snapped.center.x, 0.001f)
            assertEquals(2f, snapped.width / snapped.height, 0.001f)
        }
        val padded = Rect(-200f, -100f, 1100f, 900f)
        val visible = Rect(-2000f, -2000f, 3000f, 3000f)
        val expansion = CropDrag(padded, padded.topLeft, image, 20f, 0f, visible, true, 6f)
        assertEquals(Rect(-100f, -100f, 1100f, 900f), expansion.update(Offset(-104f, -100f)))
        val drawing = CropDrag(full, Offset(-200f, -100f), image, 20f, 0f, visible, true, 6f)
        assertEquals(Rect(-200f, -100f, 1200f, 900f), drawing.update(Offset(1196f, 896f)))
        for (expand in listOf(false, true)) for (ratio in listOf(0f, 1f, 2f)) {
            val initial = if (expand) padded else original
            val drag = CropDrag(initial, initial.bottomRight, image, 20f, ratio, visible, expand, 15f)
            for (point in listOf(Offset(-100f, -100f), Offset(797f, 598f), Offset(1600f, 1200f))) {
                val result = drag.pixelBounds(point)
                assertTrue(result.width > 0 && result.height > 0)
                if (expand) assertTrue(result.left <= 0 && result.top <= 0 && result.right >= 1000 && result.bottom >= 800)
                else assertTrue(result.left >= 0 && result.top >= 0 && result.right <= 1000 && result.bottom <= 800)
                if (ratio > 0) assertEquals(result.width.toFloat(), result.height * ratio, 3f)
            }
        }
    }

    @Test
    fun numericDragsRestoreLinkedDimensionsAndCommitOnce() {
        val image = IntSize(1000, 800)
        val initial = IntRect(600, 500, 1000, 800)
        val history = ToolHistory(initial)
        fun drag(): NumberDrag {
            val start = history.value
            return NumberDrag(start.left, 0..999, {
                history.preview(positionedBounds(start, image, it, start.top, 0f, false))
            }, { commit -> if (commit) history.commit() else history.cancel() })
        }
        val returning = drag()
        returning.move(0.25f)
        assertEquals(initial, history.value)
        returning.move(0.5f)
        assertEquals(601, history.value.left)
        returning.move(199.25f)
        assertEquals(IntRect(800, 500, 1000, 800), history.value)
        returning.move(-200f)
        returning.finish(true)
        assertEquals(initial, history.value)
        assertFalse(history.canUndo)

        val accepted = drag()
        accepted.move(1000f)
        assertEquals(999, history.value.left)
        accepted.move(-1f)
        assertEquals(998, history.value.left)
        assertEquals(0, history.revision)
        accepted.finish(true)
        assertEquals(1, history.revision)
        history.undo()
        assertEquals(initial, history.value)
        val cancelled = drag()
        cancelled.move(100f)
        cancelled.finish(false)
        assertEquals(initial, history.value)
        assertTrue(history.canRedo)
        history.redo()
        assertEquals(IntRect(998, 500, 1000, 800), history.value)
    }

    @Test
    fun resizingPreservesRequestedDimensionsAndSnapsTheOrigin() {
        val image = IntSize(1000, 800)
        val crop = IntRect(600, 500, 1000, 800)
        assertEquals(IntRect(300, 300, 1000, 800), resizedBounds(crop, image, IntSize(700, 500), false))
        assertEquals(IntRect(600, 500, 800, 600), resizedBounds(crop, image, IntSize(200, 100), false))
        val canvas = IntRect(-400, -300, 1000, 800)
        assertEquals(IntRect(-100, -100, 1000, 800), resizedBounds(canvas, image, IntSize(1100, 900), true))
        assertEquals(IntRect(-400, -300, 1600, 1300), resizedBounds(canvas, image, IntSize(2000, 1600), true))
        val full = IntRect(0, 0, 1000, 800)
        assertEquals(full, resizedBounds(crop, image, image, false))
        assertEquals(full, resizedBounds(canvas, image, image, true))
    }

    @Test
    fun movingPreservesTheOriginAndFitsDimensionsOnlyWhenNeeded() {
        val image = IntSize(1000, 800)
        val crop = IntRect(600, 500, 1000, 800)
        assertEquals(IntRect(800, 500, 1000, 800), positionedBounds(crop, image, 800, 500, 0f, false))
        assertEquals(IntRect(800, 500, 1000, 600), positionedBounds(crop, image, 800, 500, 2f, false))
        assertEquals(IntRect(600, 700, 800, 800), positionedBounds(crop, image, 600, 700, 2f, false))
        assertEquals(IntRect(999, 799, 1000, 800), positionedBounds(crop, image, 999, 799, 2f, false))
        val canvas = IntRect(-100, -100, 1000, 800)
        assertEquals(IntRect(-400, -300, 1000, 800), positionedBounds(canvas, image, -400, -300, 0f, true))
        assertEquals(IntRect(-400, -300, 1800, 800), positionedBounds(canvas, image, -400, -300, 2f, true))
        val rounded = IntRect(-33, 0, 1034, 800)
        assertEquals(IntRect(-20, 0, 1047, 800), positionedBounds(rounded, image, -20, 0, 4f / 3, true))
    }

    @Test
    fun presetsUseTheCurrentFrameCenterAndRespectTheEditingDirection() {
        val crop = IntRect(210, 130, 610, 430)
        assertEquals(IntRect(260, 130, 560, 430), ratioBounds(crop, 1f, expand = false))
        assertEquals(IntRect(210, 180, 610, 380), ratioBounds(crop, 2f, expand = false))
        val canvas = IntRect(-80, -30, 1120, 870)
        assertEquals(IntRect(-80, -180, 1120, 1020), ratioBounds(canvas, 1f, expand = true))
        assertEquals(IntRect(-380, -30, 1420, 870), ratioBounds(canvas, 2f, expand = true))
        for (expand in listOf(false, true)) {
            var bounds = if (expand) canvas else crop
            for (ratio in listOf(16f / 9, 9f / 16, 4f / 3, 1f)) {
                val next = ratioBounds(bounds, ratio, expand)
                val inner = if (expand) bounds else next
                val outer = if (expand) next else bounds
                assertTrue(inner.left >= outer.left && inner.top >= outer.top)
                assertTrue(inner.right <= outer.right && inner.bottom <= outer.bottom)
                assertEquals(next, ratioBounds(next, 0f, expand))
                bounds = next
            }
        }
    }

    @Test
    fun presetsKeepTinyEdgeSelectionsValid() {
        val pixel = IntRect(999, 799, 1000, 800)
        assertEquals(pixel, ratioBounds(pixel, 16f / 9, expand = false))
        val strip = IntRect(999, 0, 1000, 800)
        assertEquals(IntRect(999, 399, 1000, 401), ratioBounds(strip, 9f / 16, expand = false))
        assertEquals(IntRect(-1, 0, 1, 1), ratioBounds(IntRect(-1, 0, 0, 1), 16f / 9, expand = true))
    }

    @Test
    fun expansionRoundsDimensionsOnceAndKeepsFractionalEdgesOutside() {
        assertEquals(IntSize(178, 100), expandedSize(100, 100, 16f / 9))
        assertEquals(IntSize(64, 114), expandedSize(64, 48, 9f / 16))
        assertEquals(IntSize(64, 48), expandedSize(64, 48, 0f))
        val ratio = 4f / 3
        val minimum = expandedSize(1000, 800, ratio)
        val rounded = Rect(-33f, 0f, 1034f, 800f)
        val start = Offset(rounded.left, rounded.center.y)
        val returning = CropDrag(rounded, start, image, 20f, ratio,
            Rect(-2000f, -2000f, 3000f, 3000f), expand = true)
        assertEquals(IntRect(-33, 0, 1034, 800), returning.pixelBounds(start))
        val frame = Rect(-33.333332f, 0f, 1033.3334f, 800f)
        val drag = CropDrag(frame, Offset(-33.333332f, 400f), image, 20f, ratio,
            Rect(-2000f, -2000f, 3000f, 3000f), expand = true)
        for (end in listOf(Offset(-33.333332f, 400f), Offset(50f, 400f), Offset(-500f, 400f))) {
            val result = drag.pixelBounds(end)
            assertTrue(result.left <= 0 && result.top <= 0 && result.right >= 1000 && result.bottom >= 800)
            assertTrue(result.width >= minimum.width && result.height >= minimum.height)
        }
        val crop = CropDrag(original, original.center, image, 20f, 0f)
        assertEquals(IntRect(200, 200, 600, 400), crop.pixelBounds(original.center + Offset(0.4f, 0.4f)))
        val padded = Rect(-20f, -10f, 1040f, 830f)
        val move = CropDrag(padded, padded.center, image, 20f, 0f, expand = true)
        for (delta in listOf(Offset(0.4f, 0.4f), Offset(-0.4f, -0.4f), Offset(500f, -500f))) {
            val result = move.pixelBounds(padded.center + delta)
            assertEquals(1060, result.width)
            assertEquals(840, result.height)
        }
    }

    @Test
    fun expansionHandlesAndMovesKeepTheWholeImage() {
        val full = Rect(Offset.Zero, image)
        val visible = Rect(-1000f, -1000f, 2000f, 2000f)
        val corner = CropDrag(full, Offset.Zero, image, 20f, 0f, visible, expand = true)
        assertEquals(Rect(-200f, -100f, 1000f, 800f), corner.update(Offset(-200f, -100f)))
        assertEquals(full, corner.update(Offset(200f, 100f)))
        val right = CropDrag(full, Offset(1000f, 400f), image, 20f, 0f, visible, expand = true)
        assertEquals(Rect(0f, 0f, 1400f, 800f), right.update(Offset(1400f, 400f)))
        val frame = Rect(-200f, -100f, 1400f, 1000f)
        val move = CropDrag(frame, frame.center, image, 20f, 0f, visible, expand = true)
        assertEquals(Rect(0f, 0f, 1600f, 1100f), move.update(Offset(3000f, 3000f)))
        assertEquals(Rect(-600f, -300f, 1000f, 800f), move.update(Offset(-3000f, -3000f)))
    }

    @Test
    fun expansionDrawingAndResizingRespectTheRatioAndOriginalPixels() {
        val visible = Rect(-1000f, -1000f, 2000f, 2000f)
        val full = Rect(Offset.Zero, image)
        val drawing = CropDrag(full, Offset(-400f, -200f), image, 20f, 2f, visible, expand = true)
        assertEquals(Rect(-700f, -200f, 1300f, 800f), drawing.update(Offset(-100f, -20f)))
        val frame = Rect(-300f, 0f, 1300f, 800f)
        val resizing = CropDrag(frame, Offset(-300f, 400f), image, 20f, 2f, visible, expand = true)
        assertEquals(Rect(-500f, -50f, 1300f, 850f), resizing.update(Offset(-500f, 400f)))
        assertEquals(frame, resizing.update(Offset(500f, 400f)))
    }

    @Test
    fun fullImageAndFullyHiddenFrameCreateNewCropsWithAFixedAnchor() {
        val full = Rect(Offset.Zero, image)
        val frame = Rect(200f, 150f, 800f, 650f)
        val hidden = Rect(210f, 160f, 790f, 640f)
        for ((crop, visible, start) in
            listOf(
                Triple(full, full, Offset(500f, 400f)),
                Triple(full, hidden, Offset(500f, 400f)),
                Triple(frame, hidden, Offset(215f, 165f)),
            )) {
            val drag = CropDrag(crop, start, image, 20f, 0f, visible)
            assertEquals(crop, drag.update(start))
            assertEquals(
                Rect(start, start + Offset(100f, 80f)),
                drag.update(start + Offset(100f, 80f)),
            )
            assertEquals(
                Rect(start, start + Offset(200f, 160f)),
                drag.update(start + Offset(200f, 160f)),
            )
        }
        val oneVisibleEdge = Rect(200f, 160f, 790f, 640f)
        val moving = CropDrag(frame, frame.center, image, 20f, 0f, oneVisibleEdge)
        assertEquals(
            frame.translate(Offset(50f, 50f)),
            moving.update(frame.center + Offset(50f, 50f)),
        )
    }

    @Test
    fun visibleHandlesTakePriorityWithAConstantScreenTolerance() {
        val full = Rect(Offset.Zero, image)
        val corner = CropDrag(full, Offset(10f, 10f), image, 20f, 0f)
        assertEquals(Rect(100f, 100f, 1000f, 800f), corner.update(Offset(110f, 110f)))
        val visible = Rect(200f, 210f, 590f, 390f)
        val left = CropDrag(original, Offset(208f, 300f), image, 20f, 0f, visible)
        assertEquals(Rect(300f, 200f, 600f, 400f), left.update(Offset(308f, 300f)))
        for (fit in listOf(0.4f, 1f, 4f)) {
            for (distance in listOf(20f, 32f)) {
                val start = Offset(original.left + distance / fit, original.center.y)
                val drag = CropDrag(original, start, image, 28f / fit, 0f)
                assertEquals(
                    if (distance < 28f) Rect(250f, 200f, 600f, 400f)
                    else original.translate(Offset(50f, 0f)),
                    drag.update(start + Offset(50f, 0f)),
                )
            }
        }
    }

    @Test
    fun drawingOnEdgeExtensionsStartsANewRectangleInAllFourDirections() {
        val directions =
            listOf(
                Offset(-60f, -40f) to Rect(-60f, -40f, 0f, 0f),
                Offset(60f, -40f) to Rect(0f, -40f, 60f, 0f),
                Offset(-60f, 40f) to Rect(-60f, 0f, 0f, 40f),
                Offset(60f, 40f) to Rect(0f, 0f, 60f, 40f),
            )
        for (start in listOf(Offset(200f, 100f), Offset(100f, 200f))) {
            val drag = CropDrag(original, start, image, 20f, 0f)
            assertEquals(original, drag.update(start))
            for ((direction, expected) in directions) {
                assertEquals(expected.translate(start), drag.update(start + direction))
            }
        }
        val outside = CropDrag(Rect(0f, 0f, 1000f, 800f), Offset(-40f, -40f), image, 20f, 0f)
        assertEquals(Rect(0f, 0f, 100f, 100f), outside.update(Offset(100f, 100f)))
    }

    @Test
    fun resizingUsesTheNearestHandleAndPreservesUntouchedEdges() {
        val cases =
            listOf(
                Triple(Offset(208f, 300f), Offset(108f, 310f), Rect(100f, 200f, 600f, 400f)),
                Triple(Offset(592f, 300f), Offset(692f, 280f), Rect(200f, 200f, 700f, 400f)),
                Triple(Offset(400f, 208f), Offset(410f, 108f), Rect(200f, 100f, 600f, 400f)),
                Triple(Offset(400f, 392f), Offset(420f, 492f), Rect(200f, 200f, 600f, 500f)),
                Triple(Offset(208f, 208f), Offset(108f, 108f), Rect(100f, 100f, 600f, 400f)),
                Triple(Offset(592f, 208f), Offset(692f, 108f), Rect(200f, 100f, 700f, 400f)),
                Triple(Offset(208f, 392f), Offset(108f, 492f), Rect(100f, 200f, 600f, 500f)),
                Triple(Offset(592f, 392f), Offset(692f, 492f), Rect(200f, 200f, 700f, 500f)),
            )
        for ((start, end, expected) in cases) {
            val drag = CropDrag(original, start, image, 20f, 0f)
            assertEquals(original, drag.update(start))
            assertEquals(expected, drag.update(end))
        }
        val small = Rect(200f, 200f, 220f, 220f)
        val drag = CropDrag(small, Offset(219f, 210f), image, 28f, 0f)
        assertEquals(Rect(200f, 200f, 230f, 220f), drag.update(Offset(229f, 210f)))
    }

    @Test
    fun ratioAndImageBoundsArePreservedWhenDrawingResizingAndMoving() {
        val start = Offset(100f, 100f)
        val newCrop = CropDrag(original, start, image, 20f, 2f)
        for (x in listOf(-1, 1)) for (y in listOf(-1, 1)) {
            val result = newCrop.update(start + Offset(x * 80f, y * 80f))
            assertEquals(80f, result.width, 0.001f)
            assertEquals(40f, result.height, 0.001f)
            assertEquals(start.x, if (x < 0) result.right else result.left, 0f)
            assertEquals(start.y, if (y < 0) result.bottom else result.top, 0f)
        }
        val left = CropDrag(original, Offset(200f, 300f), image, 20f, 2f)
        assertEquals(Rect(0f, 150f, 600f, 450f), left.update(Offset(-100f, 500f)))
        val top = CropDrag(original, Offset(400f, 200f), image, 20f, 2f)
        assertEquals(Rect(0f, 0f, 800f, 400f), top.update(Offset(500f, -500f)))
        val corner = CropDrag(original, Offset(600f, 400f), image, 20f, 2f)
        assertEquals(Rect(200f, 200f, 1000f, 600f), corner.update(Offset(2000f, 2000f)))
        val move = CropDrag(original, original.center, image, 20f, 2f)
        assertEquals(Rect(600f, 600f, 1000f, 800f), move.update(Offset(2000f, 2000f)))
    }
}
