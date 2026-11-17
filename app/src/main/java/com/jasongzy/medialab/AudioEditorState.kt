package com.jasongzy.medialab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import com.kyant.taglib.Picture
import com.kyant.taglib.AudioProperties
import java.io.File

internal data class AudioMetadata(val tags: Map<String, List<String>>, val pictures: List<Picture>)

internal data class AudioMedia(
    val file: File,
    val properties: AudioProperties,
    val technical: List<Pair<String, String>>,
) {
    val durationMs get() = properties.length.toLong()
    val channels get() = properties.channels
    val canEdit get() = durationMs > 0 && channels in 1..8
}

internal data class AudioFrame(val media: AudioMedia, val metadata: AudioMetadata)

internal enum class AudioTool(val label: Int, val icon: Int) {
    TIME(R.string.video_time, R.drawable.ic_time),
    CHANNELS(R.string.channels, R.drawable.ic_channels),
    TAGS(R.string.tags, R.drawable.ic_tag),
    ARTWORK(R.string.artwork, R.drawable.ic_photo),
    INFO(R.string.info, R.drawable.ic_info),
    EXPORT(R.string.export, R.drawable.ic_save),
}

internal class AudioEditorState(val original: AudioFrame, val exportDefaults: AudioExportOptions = AudioExportOptions()) {
    val history = ToolHistory(original)
    var tool by mutableStateOf(AudioTool.TAGS)
    var time by mutableStateOf(ToolHistory(MediaTimeEdit(original.media.durationMs)))
        private set
    var tags by mutableStateOf(ToolHistory(original.metadata.tags))
        private set
    var pictures by mutableStateOf(ToolHistory(original.metadata.pictures))
        private set
    var selectedChannels by mutableStateOf(setOf<Int>())
    var audition by mutableStateOf(false)
    var seekPosition by mutableStateOf<Long?>(null)
    var inputRevision by mutableStateOf(0)
    var inputValid by mutableStateOf(true)
    var tag by mutableStateOf<TagDraft?>(null)
    var picture by mutableStateOf<PictureDraft?>(null)
    var coverRequest by mutableStateOf<CoverRequest?>(null)
    var exportOptions by mutableStateOf(exportDefaults)
    var preview by mutableStateOf<AudioExportPreview?>(null)
    var showPreview by mutableStateOf(false)

    val hasDraft: Boolean
        get() = tag != null || picture != null || pending.isNotEmpty()

    val pendingTime get() = time.value != MediaTimeEdit(history.value.media.durationMs) ||
        (tool == AudioTool.TIME && !inputValid)
    val pending get() = AudioTool.entries.filter { tool ->
        when (tool) {
            AudioTool.TIME -> pendingTime
            AudioTool.TAGS -> tags.value != history.value.metadata.tags
            AudioTool.ARTWORK -> pictures.value != history.value.metadata.pictures
            else -> false
        }
    }
    val files get() = (history.snapshot().steps.map { it.media.file } + original.media.file).toSet()

    fun resetMetadata(tool: AudioTool? = null) {
        if (tool == null || tool == AudioTool.TAGS) tags = ToolHistory(history.value.metadata.tags)
        if (tool == null || tool == AudioTool.ARTWORK) pictures = ToolHistory(history.value.metadata.pictures)
    }

    fun resetTime() {
        time = ToolHistory(MediaTimeEdit(history.value.media.durationMs))
        inputValid = true
        inputRevision++
    }

    fun resetPlayback() {
        if (tool == AudioTool.CHANNELS && history.value.media.channels <= 1) tool = AudioTool.TAGS
        selectedChannels = emptySet()
        audition = false
        seekPosition = null
        resetTime()
    }

    fun commit(frame: AudioFrame, action: Int) {
        if (frame == history.value) return
        val metadata = history.value.metadata
        history.change(frame, action)
        if (metadata.tags != frame.metadata.tags) resetMetadata(AudioTool.TAGS)
        if (metadata.pictures != frame.metadata.pictures) resetMetadata(AudioTool.ARTWORK)
        val snapshot = history.snapshot()
        var steps = snapshot.steps
        // Keep at least one undo step, even when a single edit exceeds the disk budget.
        while (steps.size > 2 && (steps.size > 17 ||
                steps.map { it.media.file }.distinct().filter {
                    it != frame.media.file && it != original.media.file
                }.sumOf { it.length() } > 512L * 1024 * 1024)) {
            steps = steps.drop(1)
        }
        if (steps.size != snapshot.steps.size)
            history.restore(snapshot.copy(
                steps = steps, position = steps.lastIndex, actions = snapshot.actions.takeLast(steps.size),
            ))
    }
}

internal class TagDraft(val original: String, private val initialValues: List<String>) {
    var key by mutableStateOf(original)
    val normalizedKey get() = original.ifEmpty { key.trim().uppercase() }
    private val hasLineBreaks = initialValues.any { '\n' in it || '\r' in it }
    val multilineValue get() = normalizedKey.equals("LYRICS", true) || normalizedKey.equals("COMMENT", true) || hasLineBreaks
    private val initialFields = if (multilineValue) initialValues.ifEmpty { listOf("") }
        else listOf(initialValues.joinToString("\n"))
    val fields = initialFields.toMutableStateList()
    val values: List<String>
        get() {
            val texts = fields.toList()
            if (texts == initialFields) return initialValues
            return (if (multilineValue) texts else texts.single().lines()).filter { it.isNotBlank() }
        }
}

internal class PictureDraft(val index: Int, picture: Picture) {
    var picture by mutableStateOf(picture)
    var deleting by mutableStateOf(false)
}

internal data class CoverRequest(val replacement: PictureDraft? = null)
