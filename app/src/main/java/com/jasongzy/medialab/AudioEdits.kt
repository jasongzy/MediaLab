package com.jasongzy.medialab

import java.io.File

internal fun channelGroups(count: Int, selected: Set<Int>, merge: Boolean): List<List<Int>> {
    require(count in 1..8 && selected.isNotEmpty() && selected.all { it in 0 until count })
    require(if (merge) selected.size >= 2 else selected.size < count)
    return (0 until count).mapNotNull { channel ->
        when {
            channel !in selected -> listOf(channel)
            merge && channel == selected.min() -> selected.sorted()
            else -> null
        }
    }
}

internal fun channelFilter(groups: List<List<Int>>): String {
    require(groups.isNotEmpty() && groups.all { it.isNotEmpty() })
    return "pan=${groups.size}c|" + groups.mapIndexed { index, channels ->
        "c$index<" + channels.joinToString("+") { "c$it" }
    }.joinToString("|")
}

internal suspend fun renderAudio(
    source: AudioMedia,
    target: File,
    time: MediaTimeEdit = MediaTimeEdit(source.durationMs),
    channels: List<List<Int>>? = null,
    progress: (Float) -> Unit = {},
): AudioMedia {
    time.validate(source.durationMs)
    require(source.channels in 1..8)
    val filters = listOfNotNull(
        time.takeUnless { it == MediaTimeEdit(source.durationMs) }?.audioFilter(),
        channels?.let(::channelFilter),
    ).joinToString(",").ifEmpty { "anull" }
    try {
        MediaProcessing.execute(
            listOf(
                "-protocol_whitelist", "file,pipe", "-threads", "2", "-i", source.file.path,
                "-map", "0:a:0", "-vn", "-af", filters, "-c:a", "flac", "-threads", "2",
                "-map_metadata", "-1", target.path,
            ),
        ) { progress((it.toFloat() / time.durationMs).coerceIn(0f, 1f)) }
        val result = AudioFiles.read(target, target.name).current
        check(result.durationMs > 0 && result.channels == (channels?.size ?: source.channels))
        return result
    } catch (error: Throwable) {
        target.delete()
        throw error
    }
}
