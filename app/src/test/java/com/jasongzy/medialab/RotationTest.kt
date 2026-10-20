package com.jasongzy.medialab

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class RotationTest {
    @Test
    fun handleDragCrossesTheAngleSeamAndTracksMirrorsWithoutJumpsAtTheCenter() {
        val center = Offset(250f, 170f)
        fun point(angle: Int): Offset {
            val radians = Math.toRadians(angle.toDouble())
            return center + Offset(cos(radians).toFloat(), sin(radians).toFloat()) * 100f
        }
        for (horizontal in listOf(false, true)) for (vertical in listOf(false, true)) {
            val initial = RotationSelection(170, horizontal, vertical, lockCanvas = true)
            val drag = RotationDrag(initial, point(170), center, 16f)
            val expected = if (horizontal xor vertical) 150 else -170
            assertEquals(initial.copy(angle = expected), drag.update(point(-170)))
            assertEquals(expected, drag.update(center + Offset(2f, 2f)).angle)
            assertEquals(expected, drag.update(point(0)).angle)
            assertEquals(170, drag.update(point(-20)).angle)
        }
    }

    @Test
    fun anglesWrapAndExpandedBoundsContainEveryCorner() {
        for ((text, expected) in listOf(
            "540" to 180, "-540" to -180, "721" to 1, "-721" to -1,
            "+360000000000000000000045" to 45,
        )) {
            assertEquals(expected, rotationAngle(text))
        }
        for (text in listOf("", "-", "+-1", "1.5")) assertNull(rotationAngle(text))
        assertEquals(IntSize(31, 48), RotationSelection(90).size(48, 31))
        assertEquals(IntSize(48, 31), RotationSelection(-180).size(48, 31))
        for (angle in -180..180) {
            val selection = RotationSelection(angle, flipHorizontal = true)
            val size = selection.size(48, 31)
            val m = selection.matrixValues(48, 31)
            for (x in listOf(0, 48)) for (y in listOf(0, 31)) {
                val px = m[0] * x + m[1] * y + m[2]
                val py = m[3] * x + m[4] * y + m[5]
                assertTrue(px >= -0.0001f && px <= size.width + 0.0001f)
                assertTrue(py >= -0.0001f && py <= size.height + 0.0001f)
            }
            assertEquals(IntSize(48, 31), selection.copy(lockCanvas = true).size(48, 31))
        }
    }

    @Test
    fun mirrorsFollowTheRotatedAxesAndLockedRightAnglesStayOnPixelBoundaries() {
        val rotated = RotationSelection(90)
        assertArrayEquals(floatArrayOf(0f, -1f, 3f, 1f, 0f, 0f, 0f, 0f, 1f), rotated.matrixValues(4, 3), 0f)
        assertArrayEquals(
            floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            rotated.copy(flipHorizontal = true).matrixValues(4, 3), 0f,
        )
        assertArrayEquals(
            floatArrayOf(0f, -1f, 3f, -1f, 0f, 4f, 0f, 0f, 1f),
            rotated.copy(flipVertical = true).matrixValues(4, 3), 0f,
        )
        val locked = rotated.copy(lockCanvas = true).matrixValues(4, 3)
        assertEquals(locked[2].toInt().toFloat(), locked[2], 0f)
        assertEquals(locked[5].toInt().toFloat(), locked[5], 0f)
    }
}
