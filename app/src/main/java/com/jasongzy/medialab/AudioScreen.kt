package com.jasongzy.medialab

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AudioScreen(model: LabViewModel, state: LabState, active: Boolean) {
    val picker =
        rememberLauncherForActivityResult(
            remember {
                object : ActivityResultContracts.OpenDocument() {
                    override fun createIntent(
                        context: android.content.Context,
                        input: Array<String>,
                    ) = super.createIntent(context, input).setType("audio/*")
                }
            }
        ) {
            if (it != null) model.open(it, ImportType.AUDIO)
        }
    val open = { picker.launch(arrayOf("audio/*")) }
    val importNcm = { model.showBatch(true) }
    val audio = state.audio
    if (audio == null) {
        EmptyWorkspace(
            R.drawable.ic_audio,
            R.string.audio_empty,
            R.string.choose_audio,
            open,
        ) {
            TextButton(importNcm) { Text(stringResource(R.string.unlock)) }
        }
        return
    }
    val editor = audio.editor
    val history = editor.history
    var menu by remember(editor) { mutableStateOf(false) }
    var editTag by editor::tag
    var editPicture by editor::picture
    val coverPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val request = editor.coverRequest
            editor.coverRequest = null
            if (uri != null && request != null)
                model.cover(uri) { picture ->
                    val current = model.state.value.audio ?: return@cover
                    if (current.editor !== editor) return@cover
                    val index = request.index
                    if (index != null && index !in current.pictures.indices) return@cover
                    val pictures = current.pictures.toMutableList()
                    if (index != null)
                        pictures[index] =
                            picture.copy(
                                description = pictures[index].description,
                                pictureType = pictures[index].pictureType,
                            )
                    else pictures += picture
                    model.editAudio(current.tags, pictures)
                }
        }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileName(
                audio.name, audio.sourceUri,
                MediaFiles.mime(audio.file).takeUnless { it == "application/octet-stream" } ?: "audio/*",
                audio.sourceBytes,
                R.string.original_duration to (
                    audio.sourceDuration?.takeIf { it >= 0 }?.let(::playbackTime)
                        ?: stringResource(R.string.unknown)
                ),
                Modifier.weight(1f),
            )
            ToolButton(R.drawable.ic_undo, R.string.undo, history.canUndo) { model.undoAudio(-1) }
            ToolButton(R.drawable.ic_redo, R.string.redo, history.canRedo) { model.undoAudio(1) }
            ToolButton(R.drawable.ic_save, R.string.save_copy, action = model::saveAudio)
            Box {
                ToolButton(R.drawable.ic_more, R.string.more) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.restore_original_audio)) },
                        enabled = history.value != editor.original,
                        onClick = {
                            menu = false
                            model.restoreAudio()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.replace_audio)) },
                        onClick = {
                            menu = false
                            open()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.unlock)) },
                        onClick = {
                            menu = false
                            importNcm()
                        },
                    )
                }
            }
        }
        AudioPlayer(audio.file, active, model.playbackPosition(audio.file)) {
            model.rememberPlaybackPosition(audio.file, it)
        }
        val common =
            listOf(
                "TITLE",
                "ARTIST",
                "ALBUM",
                "ALBUMARTIST",
                "TRACKNUMBER",
                "DISCNUMBER",
                "DATE",
                "GENRE",
                "COMPOSER",
                "LYRICS",
                "COMMENT",
            )
        val keys =
            common.filter { it in audio.tags || it in listOf("TITLE", "ARTIST", "ALBUM") } +
                (audio.tags.keys - common.toSet()).sorted()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.tags),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ToolButton(R.drawable.ic_add, R.string.add_tag) {
                        editTag = TagDraft("", emptyList())
                    }
                }
            }
            items(keys, key = { it }) { key ->
                ListItem(
                    content = { Text(tagLabel(key)) },
                    supportingContent = {
                        Text(
                            audio.tags[key]?.joinToString(" · ").orEmpty(),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier =
                        Modifier.clickable { editTag = TagDraft(key, audio.tags[key].orEmpty()) },
                )
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.artwork),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ToolButton(R.drawable.ic_add, R.string.add_cover) {
                        editor.coverRequest = CoverRequest()
                        coverPicker.launch(arrayOf("image/*"))
                    }
                }
            }
            items(audio.pictures.size) { index ->
                val picture = audio.pictures[index]
                val bitmap by
                    produceState<Bitmap?>(null, picture.data) {
                        value =
                            withContext(Dispatchers.IO) { AudioFiles.thumbnail(picture.data) }
                    }
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    bitmap?.let {
                        Image(
                            it.asImageBitmap(),
                            stringResource(R.string.artwork),
                            Modifier.size(80.dp).clickable { editPicture = PictureDraft(index, picture) },
                        )
                    }
                    Column(Modifier.weight(1f).clickable { editPicture = PictureDraft(index, picture) }) {
                        Text(
                            pictureLabel(picture.pictureType),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (picture.description.isNotBlank())
                            Text(
                                picture.description,
                                maxLines = 2,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        Text(
                            "${picture.mimeType} · ${Formatter.formatFileSize(context, picture.data.size.toLong())}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    ToolButton(R.drawable.ic_save, R.string.export_cover) {
                        model.saveCover(picture)
                    }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
                Text(
                    stringResource(R.string.technical),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                SelectionContainer {
                    Column(
                        Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${audio.file.extension.uppercase()} · ${Formatter.formatFileSize(context, audio.file.length())}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        audio.properties?.let { properties ->
                            DetailRow(
                                stringResource(R.string.duration),
                                playbackTime(properties.length.toLong()),
                            )
                            DetailRow(
                                stringResource(R.string.bitrate),
                                "${properties.bitrate} kb/s",
                            )
                            DetailRow(
                                stringResource(R.string.sample_rate),
                                "${properties.sampleRate} Hz",
                            )
                            DetailRow(
                                stringResource(R.string.channels),
                                properties.channels.toString(),
                            )
                        }
                        audio.technical.forEach { (key, value) -> DetailRow(key, value) }
                    }
                }
            }
        }
    }
    editTag?.let { draft ->
        val original = draft.original
        var key by draft::key
        var text by draft::text
        val normalized = key.trim().uppercase(Locale.ROOT)
        AlertDialog(
            onDismissRequest = { editTag = null },
            title = {
                Text(
                    if (original.isBlank()) stringResource(R.string.add_tag) else tagLabel(original)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        key,
                        { key = it },
                        label = { Text(stringResource(R.string.tag_key)) },
                        singleLine = true,
                        readOnly = original.isNotBlank(),
                    )
                    OutlinedTextField(
                        text,
                        { text = it },
                        label = {
                            Text(
                                if (original in listOf("LYRICS", "COMMENT")) tagLabel(original)
                                else stringResource(R.string.tag_values)
                            )
                        },
                        modifier = Modifier.heightIn(max = 300.dp),
                        minLines = 2,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    {
                        val tags = audio.tags.toMutableMap()
                        val newValues =
                            (if (normalized in listOf("LYRICS", "COMMENT")) listOf(text)
                                else text.lines())
                                .filter { it.isNotBlank() }
                        if (newValues.isEmpty()) tags.remove(normalized)
                        else tags[normalized] = newValues
                        model.editAudio(tags, audio.pictures)
                        editTag = null
                    },
                    enabled =
                        normalized.isNotBlank() &&
                            normalized.length <= 256 &&
                            (original.isNotBlank() || normalized !in audio.tags),
                ) {
                    Text(stringResource(R.string.apply))
                }
            },
            dismissButton = {
                Row {
                    if (original.isNotBlank())
                        TextButton({
                            model.editAudio(audio.tags - original, audio.pictures)
                            editTag = null
                        }) {
                            Text(stringResource(R.string.delete))
                        }
                    TextButton({ editTag = null }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
    editPicture
        ?.takeIf { it.index in audio.pictures.indices }
        ?.let { draft ->
            val index = draft.index
            val picture = audio.pictures[index]
            val bitmap by
                produceState<Bitmap?>(null, picture.data) {
                    value = withContext(Dispatchers.IO) { AudioFiles.thumbnail(picture.data, 1024) }
                }
            var description by draft::description
            var type by draft::type
            var deleting by draft::deleting
            AlertDialog(
                onDismissRequest = { editPicture = null },
                title = {
                    Text(stringResource(if (deleting) R.string.delete_cover else R.string.artwork))
                },
                text = {
                    if (!deleting)
                        Column(
                            Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier.fillMaxWidth().height(280.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                bitmap?.let {
                                    Image(
                                        it.asImageBitmap(),
                                        stringResource(R.string.artwork),
                                        Modifier.fillMaxSize().combinedClickable(
                                            enabled = !state.busy,
                                            onLongClickLabel = stringResource(R.string.export_cover),
                                            onLongClick = { model.saveCover(picture) },
                                            onClick = {},
                                        ),
                                        contentScale = ContentScale.Fit,
                                    )
                                }
                            }
                            OutlinedTextField(
                                description,
                                { description = it },
                                label = { Text(stringResource(R.string.description)) },
                            )
                            OutlinedTextField(
                                type,
                                { type = it },
                                label = { Text(stringResource(R.string.cover_type)) },
                                singleLine = true,
                            )
                            val types = listOf("Front Cover", "Back Cover", "Other")
                            ChoiceChips(
                                types.mapIndexed { i, value -> i to pictureLabel(value) },
                                types.indexOf(type),
                            ) {
                                type = types[it]
                            }
                            TextButton({
                                editor.coverRequest = CoverRequest(index)
                                editPicture = null
                                coverPicker.launch(arrayOf("image/*"))
                            }) {
                                Text(stringResource(R.string.replace_cover))
                            }
                        }
                },
                confirmButton = {
                    TextButton({
                        val pictures = audio.pictures.toMutableList()
                        if (deleting) pictures.removeAt(index)
                        else
                            pictures[index] =
                                picture.copy(description = description, pictureType = type)
                        model.editAudio(audio.tags, pictures)
                        editPicture = null
                    }) {
                        Text(stringResource(if (deleting) R.string.delete else R.string.apply))
                    }
                },
                dismissButton = {
                    Row {
                        if (!deleting)
                            TextButton({ deleting = true }) {
                                Text(stringResource(R.string.delete))
                            }
                        TextButton({ editPicture = null }) { Text(stringResource(R.string.cancel)) }
                    }
                },
            )
        }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            label,
            Modifier.weight(1f).alignByBaseline(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(value, Modifier.weight(1f).alignByBaseline(), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun tagLabel(key: String): String =
    when (key) {
        "TITLE" -> stringResource(R.string.title)
        "ARTIST" -> stringResource(R.string.artist)
        "ALBUM" -> stringResource(R.string.album)
        "ALBUMARTIST" -> stringResource(R.string.album_artist)
        "TRACKNUMBER" -> stringResource(R.string.track)
        "DISCNUMBER" -> stringResource(R.string.disc)
        "DATE" -> stringResource(R.string.date)
        "GENRE" -> stringResource(R.string.genre)
        "COMPOSER" -> stringResource(R.string.composer)
        "LYRICS" -> stringResource(R.string.lyrics)
        "COMMENT" -> stringResource(R.string.comment)
        else -> key
    }

@Composable
private fun pictureLabel(type: String): String =
    when (type) {
        "Front Cover" -> stringResource(R.string.front_cover)
        "Back Cover" -> stringResource(R.string.back_cover)
        "Other" -> stringResource(R.string.other_cover)
        else -> type
    }
