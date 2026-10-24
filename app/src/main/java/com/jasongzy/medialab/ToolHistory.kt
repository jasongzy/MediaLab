package com.jasongzy.medialab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class ToolHistory<T>(initial: T) {
    data class Snapshot<T>(
        val steps: List<T>, val position: Int, val value: T,
        val actions: List<Int?> = List(steps.size) { null },
    )

    private var steps by mutableStateOf(listOf(initial))
    private var actions by mutableStateOf(listOf<Int?>(null))
    private var position by mutableIntStateOf(0)
    var value by mutableStateOf(initial)
        private set
    var revision by mutableIntStateOf(0)
        private set

    val canUndo: Boolean
        get() = value != steps[position] || position > 0
    val canRedo: Boolean
        get() = value == steps[position] && position < steps.lastIndex

    val undoValue: T?
        get() = if (value != steps[position]) steps[position] else steps.getOrNull(position - 1)

    val redoValue: T?
        get() = if (canRedo) steps[position + 1] else null

    val undoAction: Int?
        get() = if (value == steps[position]) actions[position] else null
    val redoAction: Int?
        get() = if (canRedo) actions[position + 1] else null

    fun snapshot() = Snapshot(steps, position, value, actions)

    fun restore(snapshot: Snapshot<T>) {
        steps = snapshot.steps
        actions = snapshot.actions
        position = snapshot.position
        value = snapshot.value
        revision++
    }

    fun preview(value: T) {
        this.value = value
    }

    fun change(value: T, action: Int? = null) {
        preview(value)
        commit(action)
    }

    fun commit(action: Int? = null) {
        if (value == steps[position]) return
        steps = (steps.take(position + 1) + listOf(value)).takeLast(MAX_STEPS + 1)
        actions = (actions.take(position + 1) + action).takeLast(MAX_STEPS + 1)
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
