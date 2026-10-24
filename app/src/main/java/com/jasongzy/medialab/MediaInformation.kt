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
