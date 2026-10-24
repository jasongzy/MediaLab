package com.jasongzy.medialab

import org.junit.Assert.*
import org.junit.Test

class ToolHistoryTest {
    @Test
    fun previewsCommitOnceAndCancellationPreservesRedo() {
        val history = ToolHistory(0)
        history.change(1, 10)
        history.change(2, 20)
        assertEquals(20, history.undoAction)
        history.undo()
        val saved = history.snapshot()
        assertEquals(10, history.undoAction)
        assertEquals(20, history.redoAction)
        val revision = history.revision
        repeat(20) { history.preview(it + 3) }
        assertEquals(1, history.undoValue)
        assertNull(history.redoValue)
        assertEquals(revision, history.revision)
        assertFalse(history.canRedo)
        history.cancel()
        assertEquals(0, history.undoValue)
        assertEquals(2, history.redoValue)
        assertEquals(1, history.value)
        assertTrue(history.canRedo)
        history.change(1)
        assertEquals(revision, history.revision)
        assertTrue(history.canRedo)

        history.preview(3)
        history.preview(4)
        history.commit(30)
        assertEquals(30, history.undoAction)
        assertEquals(revision + 1, history.revision)
        assertFalse(history.canRedo)
        history.undo()
        assertEquals(1, history.value)
        assertEquals(30, history.redoAction)
        history.redo()
        assertEquals(4, history.value)
        history.restore(saved)
        assertEquals(1, history.value)
        assertEquals(10, history.undoAction)
        assertEquals(20, history.redoAction)
    }

    @Test
    fun strokeListsStayWholeAndDraftUndoCanBeRedone() {
        val history = ToolHistory(emptyList<Int>())
        history.change(listOf(1))
        history.preview(listOf(1, 2))
        history.preview(listOf(1, 3))
        history.undo()
        assertEquals(listOf(1), history.value)
        history.redo()
        assertEquals(listOf(1, 3), history.value)
        history.undo()
        history.undo()
        assertEquals(emptyList<Int>(), history.value)
        assertFalse(history.canUndo)
        assertNull(history.undoValue)
        history.undo()
        history.redo()
        assertEquals(listOf(1), history.value)
    }

    @Test
    fun historyKeepsTheLatest64UndoSteps() {
        val history = ToolHistory(0)
        repeat(80) { history.change(it + 1, it + 100) }
        repeat(64) {
            assertTrue(history.canUndo)
            assertEquals(179 - it, history.undoAction)
            history.undo()
        }
        assertEquals(16, history.value)
        assertFalse(history.canUndo)
        repeat(64) {
            assertEquals(116 + it, history.redoAction)
            history.redo()
        }
        assertEquals(80, history.value)
        assertFalse(history.canRedo)
    }
}
