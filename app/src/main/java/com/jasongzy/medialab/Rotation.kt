package com.jasongzy.medialab

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun rotationAngle(text: String): Int? {
    if (text.startsWith("+") && text.getOrNull(1)?.isDigit() != true) return null
    return text.removePrefix("+").toBigIntegerOrNull()?.remainder(360.toBigInteger())?.toInt()?.let(::rotationAngle)
}

internal fun rotationAngle(value: Int): Int {
    val angle = value % 360
    return when {
        angle > 180 -> angle - 360
        angle < -180 -> angle + 360
        else -> angle
    }
}

internal class RotationDrag(
    private val initial: RotationSelection,
    start: Offset,
    private val center: Offset,
    private val deadZone: Float,
) {
    private var previous = direction(start)
    private var angle = initial.angle.toFloat()
    private var snapped = angle.takeIf { initial.angle % 90 == 0 }
    val snapAngle: Int?
        get() = snapped?.let { (it.roundToInt() % 360 + 360) % 360 }

    private fun direction(point: Offset): Float? {
        val offset = point - center
        return if (offset.getDistance() < deadZone) null
            else Math.toDegrees(atan2(offset.y.toDouble(), offset.x.toDouble())).toFloat()
    }

    fun update(point: Offset): RotationSelection {
        val next = direction(point)
        if (next != null) previous?.let {
            val delta = (next - it + 540f) % 360f - 180f
            angle += if (initial.flipHorizontal xor initial.flipVertical) -delta else delta
        }
        previous = next
        val target = round(angle / 90f) * 90f
        snapped = target.takeIf { abs(angle - it) <= if (it == snapped) 5f else 3f }
        return initial.copy(angle = rotationAngle((snapped ?: angle).roundToInt()))
    }
}

internal data class RotationSelection(
    val angle: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val lockCanvas: Boolean = false,
) {
    init { require(angle in -180..180) }

    val changed: Boolean
        get() = angle != 0 || flipHorizontal || flipVertical
    val keepsHdr: Boolean
        get() = angle % 90 == 0 && (!lockCanvas || angle % 180 == 0)
    private val cosine: Double
        get() = when (angle) {
            0 -> 1.0
            -180, 180 -> -1.0
            -90, 90 -> 0.0
            else -> cos(Math.toRadians(angle.toDouble()))
        }
    private val sine: Double
        get() = when (angle) {
            0, -180, 180 -> 0.0
            90 -> 1.0
            -90 -> -1.0
            else -> sin(Math.toRadians(angle.toDouble()))
        }

    fun size(width: Int, height: Int): IntSize {
        require(width > 0 && height > 0)
        if (lockCanvas) return IntSize(width, height)
        val w = ceil(width * abs(cosine) + height * abs(sine)).toLong()
        val h = ceil(width * abs(sine) + height * abs(cosine)).toLong()
        require(w in 1..Int.MAX_VALUE.toLong() && h in 1..Int.MAX_VALUE.toLong())
        return IntSize(w.toInt(), h.toInt())
    }

    fun matrixValues(width: Int, height: Int): FloatArray {
        val output = size(width, height)
        val sx = if (flipHorizontal) -1 else 1
        val sy = if (flipVertical) -1 else 1
        val a = sx * cosine
        val b = -sx * sine
        val c = sy * sine
        val d = sy * cosine
        var x = (output.width - a * width - b * height) / 2
        var y = (output.height - c * width - d * height) / 2
        // Keep right-angle edits on pixel boundaries when the locked canvas has different parity.
        if (angle % 90 == 0) {
            x = round(x)
            y = round(y)
        }
        return floatArrayOf(a.toFloat(), b.toFloat(), x.toFloat(), c.toFloat(), d.toFloat(), y.toFloat(), 0f, 0f, 1f)
    }
}
