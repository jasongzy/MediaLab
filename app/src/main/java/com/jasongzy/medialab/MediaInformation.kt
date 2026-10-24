package com.jasongzy.medialab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun InformationPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.current_media_info),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.current_media_info_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
internal fun PhotoInformation(info: PhotoInfo, model: LabViewModel) {
    InformationPanel {
        MediaDetail(stringResource(R.string.dimensions), "${info.width} × ${info.height} px")
        if (info.container?.video != null) Text(stringResource(R.string.motion_photo))
        if (info.container?.edit != null) Text(stringResource(R.string.editable_photo))
        MediaDetail(
            stringResource(R.string.color_space),
            info.color.space ?: stringResource(R.string.unknown),
        )
        MediaDetail(
            stringResource(R.string.wide_gamut),
            stringResource(
                when (info.color.wide) {
                    true -> R.string.yes
                    false -> R.string.no
                    null -> R.string.unknown
                }
            ),
        )
        info.color.model?.let { model ->
            MediaDetail(
                stringResource(R.string.color_model),
                if (model == "Gray") stringResource(R.string.grayscale) else model,
            )
        }
        MediaDetail(
            stringResource(R.string.dynamic_range),
            stringResource(
                when (info.color.hdr) {
                    true -> R.string.hdr
                    false -> R.string.sdr
                    null -> R.string.unknown
                }
            ),
        )
        val metadata = PhotoMetadata.retained(info.metadata)
        if (metadata.isNotEmpty()) {
            HorizontalDivider()
            Text(
                stringResource(R.string.media_metadata),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(stringResource(R.string.edit_metadata_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            MetadataEntries(metadata, group = { PhotoMetadata.group(it, metadata) }, save = model::editPhotoMetadata)
        }
    }
}

@Composable
internal fun VideoInformation(video: VideoDocument, model: LabViewModel) {
    val edits = video.editor.history.value
    InformationPanel {
        MediaDetail(
            stringResource(R.string.dimensions),
            "${edits.size.width} × ${edits.size.height} px",
        )
        MediaDetail(stringResource(R.string.video_duration), mediaSeconds(edits.durationMs) + " s")
        MediaDetail(
            stringResource(R.string.video_audio_tracks),
            video.info.audioTracks.size.toString(),
        )
        val rendered = video.appliedInfo ?: video.info
        val color = rendered.color
        if (color.isNotEmpty()) MediaDetail(stringResource(R.string.color_space), color)
        MediaDetail(
            stringResource(R.string.dynamic_range),
            stringResource(if (rendered.hdr) R.string.hdr else R.string.sdr),
        )
        val metadata = video.info.metadata(edits)
        if (metadata.isNotEmpty()) {
            HorizontalDivider()
            Text(
                stringResource(R.string.media_metadata),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(stringResource(R.string.edit_metadata_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            metadata.forEach { group ->
                if (group.streamIndex != null)
                    Text(
                        if (group.streamIndex == video.info.videoIndex)
                            stringResource(R.string.video_tab)
                        else
                            stringResource(
                                R.string.video_audio_track,
                                video.info.audioTracks.indexOfFirst {
                                    it.index == group.streamIndex
                                } + 1,
                            ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                MetadataEntries(group.entries) { changes, done ->
                    val entry = changes.entries.single()
                    model.editVideoMetadata(VideoTag(group.streamIndex, entry.key), entry.value, done)
                }
            }
        }
    }
}

@Composable
private fun MetadataEntries(
    entries: List<Pair<String, String>>,
    group: (String) -> List<Pair<String, String>> = { key -> entries.filter { it.first == key } },
    save: (Map<String, String?>, () -> Unit) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    entries.forEach { (key, value) ->
        Column(Modifier.fillMaxWidth().clickable { editing = key }.padding(vertical = 4.dp)) {
            MediaDetail(key, value.take(8192))
        }
    }
    editing?.let { key ->
        val fields = group(key)
        if (fields.isEmpty()) return@let
        var values by rememberSaveable(key, fields) { mutableStateOf<List<String>>(fields.map { it.second }) }
        val dismiss = { editing = null }
        // Selection and context menus must belong to the dialog's window.
        CompositionLocalProvider(
            LocalTextContextMenuToolbarProvider provides null,
            LocalTextContextMenuDropdownProvider provides null,
        ) {
            DisableSelection {
                AlertDialog(
                    onDismissRequest = dismiss,
                    title = { Text(stringResource(R.string.edit_metadata)) },
                    text = {
                        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            fields.forEachIndexed { index, (tag, _) ->
                                OutlinedTextField(
                                    values[index],
                                    { text -> values = values.mapIndexed { i, value -> if (i == index) text else value } },
                                    Modifier.fillMaxWidth(),
                                    label = { Text(tag) },
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton({ save(fields.mapIndexed { index, (tag, _) -> tag to values[index].takeIf { it.isNotEmpty() } }.toMap(), dismiss) },
                            enabled = values != fields.map { it.second }) { Text(stringResource(R.string.apply)) }
                    },
                    dismissButton = {
                        Row {
                            TextButton({ save(fields.associate { it.first to null }, dismiss) }) { Text(stringResource(R.string.delete)) }
                            TextButton(dismiss) { Text(stringResource(R.string.cancel)) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun VideoSourceDetails(info: VideoInfo) {
    listOf(
            R.string.original_dimensions to "${info.width} × ${info.height} px",
            R.string.video_codec to info.codec,
            R.string.video_audio_codec to
                info.audioTracks
                    .map { it.codec }
                    .distinct()
                    .joinToString(" · ")
                    .ifEmpty { stringResource(R.string.video_no_audio) },
            R.string.video_fps to "%.3f".format(Locale.ROOT, info.fps),
            R.string.video_bitrate to (info.bitrate / 1000).toString(),
            R.string.dynamic_range to stringResource(if (info.hdr) R.string.hdr else R.string.sdr),
        )
        .forEach { (label, value) -> MediaDetail(stringResource(label), value) }
    if (info.color.isNotEmpty()) MediaDetail(stringResource(R.string.color_space), info.color)
}
