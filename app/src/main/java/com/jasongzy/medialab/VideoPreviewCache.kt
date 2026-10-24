package com.jasongzy.medialab

import java.io.File

internal class VideoPreviewCache(
    private val maxEntries: Int = 5,
    private val maxBytes: Long = 512L * 1024 * 1024,
) {
    data class Preview(val file: File, val info: VideoInfo)

    private val entries = LinkedHashMap<VideoEdits, Preview>(8, 0.75f, true)

    val files: Set<File>
        get() = entries.values.map { it.file }.toSet()

    operator fun get(edits: VideoEdits): Preview? {
        val key = edits.previewKey()
        val preview = entries[key] ?: return null
        if (preview.file.isFile) return preview
        entries.remove(key)
        return null
    }

    fun retain(edits: VideoEdits, current: Preview?, history: List<VideoEdits>) {
        if (current != null) {
            val old = entries.put(edits.previewKey(), current)
            if (old != null && old.file != current.file) old.file.delete()
        }
        val retained = history.map { it.previewKey() }.toSet()
        entries.entries.removeAll { (key, preview) ->
            if (key !in retained) {
                preview.file.delete()
                true
            } else !preview.file.isFile
        }
        val idle = entries.values.filter { it.file != current?.file }
        var count = idle.size
        var bytes = idle.sumOf { it.file.length() }
        val iterator = entries.iterator()
        while (iterator.hasNext() && (count > maxEntries || bytes > maxBytes)) {
            val preview = iterator.next().value
            if (preview.file == current?.file) continue
            bytes -= preview.file.length()
            count--
            preview.file.delete()
            iterator.remove()
        }
    }
}

internal fun VideoEdits.previewKey(): VideoEdits =
    copy(
        cropRatio = 0f,
        canvasRatio = 0f,
        clearMetadata = false,
        metadataEdits = emptyMap(),
        previous = previous.map { it.copy(cropRatio = 0f, canvasRatio = 0f, clearMetadata = false, metadataEdits = emptyMap()) },
    )
