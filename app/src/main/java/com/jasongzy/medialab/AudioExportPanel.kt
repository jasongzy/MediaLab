package com.jasongzy.medialab

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun AudioExportPanel(audio: AudioDocument, model: LabViewModel) {
    val editor = audio.editor
    val options = editor.exportOptions
    val encoding = options.encoding(audio.encoding)
    val copy = options.copiesOriginal(audio)
    val problem = options.problem(audio)
    val context = LocalContext.current
    var valid by remember(options.format, audio.current) { mutableStateOf(true) }
    var revision by remember { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (editor.pending.isNotEmpty()) Text(
            stringResource(R.string.unapplied_export,
                editor.pending.map { stringResource(it.label) }.joinToString("\n") { "• $it" }),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        )
        ChoiceChips(
            AudioFormat.entries.map {
                it.ordinal to if (it == AudioFormat.ORIGINAL) stringResource(R.string.original_format) else it.label
            }, options.format.ordinal,
        ) {
            editor.exportOptions = options.copy(format = AudioFormat.entries[it],
                bitrateKbps = options.bitrateKbps.coerceAtMost(320))
        }
        if (options.format == AudioFormat.ORIGINAL) Text(
            listOfNotNull(audio.encoding?.extension ?: audio.file.extension, audio.encoding?.codec)
                .filter { it.isNotEmpty() }.map { it.uppercase() }.distinct().joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (copy) Text(stringResource(R.string.audio_copy_hint), style = MaterialTheme.typography.bodySmall)
        else if (problem == null) {
            when {
                encoding?.lossy == true -> {
                    val maximum = if (encoding.codec == "mp3") 320 else 512
                    ChoiceChips(listOf(64, 96, 128, 192, 256, 320).map { it to "$it" }, options.bitrateKbps) {
                        editor.exportOptions = options.copy(bitrateKbps = it)
                        revision++
                    }
                    key(options.format, revision) {
                        NumberField(R.string.audio_bitrate, options.bitrateKbps, Modifier.fillMaxWidth(),
                            range = 32..maximum, onValidityChanged = { valid = it }) {
                            editor.exportOptions = options.copy(bitrateKbps = it)
                        }
                    }
                    Text(stringResource(R.string.audio_bitrate_hint), style = MaterialTheme.typography.bodySmall)
                }
                encoding?.codec == "flac" -> {
                    Text(stringResource(R.string.audio_compression, options.compression))
                    ValueSlider(options.compression.toFloat(), 0f..12f) {
                        editor.exportOptions = options.copy(compression = it.roundToInt())
                    }
                    Text(stringResource(R.string.audio_flac_hint), style = MaterialTheme.typography.bodySmall)
                }
                options.format == AudioFormat.WAV -> {
                    Text(stringResource(R.string.audio_bit_depth), style = MaterialTheme.typography.bodyMedium)
                    ChoiceChips(listOf(16, 24, 32).map { it to "$it bit" }, options.bits) {
                        editor.exportOptions = options.copy(bits = it)
                    }
                }
            }
        }
        if (problem != null) Text(stringResource(problem), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        if (!copy && encoding?.container == "mov" && audio.pictures.any {
                it.description.isNotEmpty() || it.pictureType.isNotEmpty()
            }) Text(stringResource(R.string.audio_m4a_artwork_hint), style = MaterialTheme.typography.bodySmall)
        ExportActions(
            preview = model::previewAudio,
            share = { model.shareAudio { MediaFiles.share(context, it) } },
            save = model::saveAudio, enabled = valid && problem == null,
        ) {
            TextButton({
                editor.exportOptions = editor.exportDefaults.copy(format = options.format)
                valid = true
                revision++
            }, enabled = !valid || options != editor.exportDefaults.copy(format = options.format)) {
                Text(stringResource(R.string.video_reset_values))
            }
        }
    }
}
