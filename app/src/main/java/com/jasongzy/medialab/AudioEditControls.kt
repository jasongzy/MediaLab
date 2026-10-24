package com.jasongzy.medialab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
internal fun audioChannelLabel(channel: Int, count: Int): String = when {
    count == 2 && channel == 0 -> stringResource(R.string.audio_left)
    count == 2 -> stringResource(R.string.audio_right)
    else -> stringResource(R.string.audio_channel_number, channel + 1)
}

@Composable
internal fun AudioTimeline(editor: AudioEditorState) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        val edits = editor.time.value
        val line = MaterialTheme.colorScheme.onSurfaceVariant
        val silence = Modifier.height(12.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.extraSmall)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
            .drawBehind { drawLine(line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx()) }
        MediaTimeline(
            edits, editor.history.value.media.durationMs, { Spacer(silence.weight(it)) },
            seek = { editor.seekPosition = it },
            change = { start, end -> editor.time.preview(edits.copy(startMs = start, endMs = end)) },
            finish = editor.time::commit,
        ) { HistoryButtons(editor.time, R.string.video_time) { editor.inputRevision++ } }
        HorizontalDivider()
    }
}

@Composable
internal fun AudioEditPanel(audio: AudioDocument, model: LabViewModel) {
    val editor = audio.editor
    val media = audio.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!media.canEdit) Text(when {
            media.durationMs <= 0 -> stringResource(R.string.audio_duration_unavailable)
            media.channels <= 0 -> stringResource(R.string.audio_channels_unavailable)
            else -> stringResource(R.string.audio_channels_unsupported, media.channels)
        })
        else if (editor.tool == AudioTool.TIME) key(editor.inputRevision) {
            val edits = editor.time.value
            MediaTimeFields(
                edits, media.durationMs,
                validity = { editor.inputValid = it },
                seek = { editor.seekPosition = it },
                change = { next, preview ->
                    if (preview) editor.time.preview(next) else editor.time.change(next)
                },
                finish = { if (it) editor.time.commit() else editor.time.cancel() },
            )
            Text(stringResource(R.string.audio_silence_hint), style = MaterialTheme.typography.bodySmall)
            MediaTimeActions(
                edits.durationMs,
                resetEnabled = editor.pendingTime,
                applyEnabled = editor.inputValid && editor.pendingTime,
                reset = {
                    editor.time.change(MediaTimeEdit(media.durationMs))
                    editor.inputRevision++
                },
                apply = model::applyAudioTime,
            )
        } else {
            Text(stringResource(R.string.audio_channel_hint), style = MaterialTheme.typography.bodySmall)
            repeat(media.channels) { channel ->
                CheckOption(audioChannelLabel(channel, media.channels), channel in editor.selectedChannels) { selected ->
                    editor.selectedChannels =
                        if (selected) editor.selectedChannels + channel else editor.selectedChannels - channel
                    if (editor.selectedChannels.isEmpty()) editor.audition = false
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilterChip(
                    selected = editor.audition,
                    onClick = { editor.audition = !editor.audition },
                    enabled = editor.selectedChannels.isNotEmpty(),
                    label = { Text(stringResource(R.string.audio_audition)) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_volume_up), null, Modifier.size(FilterChipDefaults.IconSize))
                    },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.audio_merge_hint), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton({ model.editAudioChannels(false) },
                    enabled = editor.selectedChannels.size in 1 until media.channels) {
                    Text(stringResource(R.string.audio_delete_channels))
                }
                Button({ model.editAudioChannels(true) }, enabled = editor.selectedChannels.size >= 2) {
                    Text(stringResource(R.string.audio_merge_channels))
                }
            }
        }
    }
}
