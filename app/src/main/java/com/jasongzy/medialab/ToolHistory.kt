package com.jasongzy.medialab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class ToolHistory<T>(initial: T) {
    private var steps by mutableStateOf(listOf(initial))
    private var position by mutableIntStateOf(0)
    var value by mutableStateOf(initial)
        private set
    var revision by mutableIntStateOf(0)
        private set

    val canUndo: Boolean
        get() = value != steps[position] || position > 0
    val canRedo: Boolean
        get() = value == steps[position] && position < steps.lastIndex

    fun preview(value: T) {
        this.value = value
    }

    fun change(value: T) {
        preview(value)
        commit()
    }

    fun commit() {
        if (value == steps[position]) return
        steps = (steps.take(position + 1) + listOf(value)).takeLast(MAX_STEPS + 1)
        position = steps.lastIndex
        revision++
    }

    fun cancel() {
        value = steps[position]
    }

    fun undo() {
        commit()
        if (position > 0) {
            value = steps[--position]
            revision++
        }
    }

    fun redo() {
        if (canRedo) {
            value = steps[++position]
            revision++
        }
    }

    private companion object {
        const val MAX_STEPS = 64
    }
}
