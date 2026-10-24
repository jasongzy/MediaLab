package com.jasongzy.medialab

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoPreviewCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun retentionBoundsIdlePreviewsAndPrunesDiscardedHistory() {
        val info = VideoInfo(32, 32, 1000, 30.0, 1000, "h264", emptyList(), "mp4", false, "", 0, emptyList())
        val cache = VideoPreviewCache(maxEntries = 2, maxBytes = 6)
        val history = mutableListOf(info.edits())
        fun append(bytes: Int = 2): File {
            val edits = info.edits().copy(width = 32 + history.size * 2)
            val file = folder.newFile().apply { writeBytes(ByteArray(bytes)) }
            history += edits
            cache.retain(edits, VideoPreviewCache.Preview(file, info), history)
            return file
        }
        val first = append()
        val second = append()
        val third = append()
        assertEquals(first, cache[history[1]]?.file)
        val fourth = append()
        assertFalse(second.exists())
        assertEquals(setOf(first, third, fourth), cache.files)
        val large = append(8)
        assertFalse(third.exists())
        assertEquals(setOf(first, fourth, large), cache.files)
        assertEquals(large, cache[history.last().copy(clearMetadata = true, cropRatio = 1f)]?.file)
        val current = append()
        assertEquals(setOf(current), cache.files)
        assertTrue(listOf(first, fourth, large).none { it.exists() })

        val branch = info.edits().copy(height = 16)
        val replacement = folder.newFile().apply { writeBytes(ByteArray(2)) }
        cache.retain(branch, VideoPreviewCache.Preview(replacement, info), listOf(info.edits(), branch))
        assertFalse(current.exists())
        assertEquals(setOf(replacement), cache.files)
        assertTrue(replacement.delete())
        assertNull(cache[branch])
        assertTrue(cache.files.isEmpty())
    }
}
