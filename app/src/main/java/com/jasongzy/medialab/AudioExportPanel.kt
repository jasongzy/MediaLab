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
    val options = audio.exportOptions
    val encoding = options.encoding(audio.encoding)
    val bitrateSteps = encoding?.bitrateSteps(audio.current)
    val bitrateRange = bitrateSteps?.indices ?: encoding?.bitrateRange(audio.current)
    val defaults = editor.exportDefaults
    val copy = options.copiesOriginal(audio)
    val problem = options.problem(audio)
    val context = LocalContext.current
    var valid by remember(options.format, audio.current) { mutableStateOf(true) }
    var revision by remember { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UnappliedExportHint(editor.pending.map { stringResource(it.label) })
        ChoiceChips(
            AudioFormat.entries.map {
                it.ordinal to if (it == AudioFormat.ORIGINAL) stringResource(R.string.original_format) else it.label
            }, options.format.ordinal,
        ) {
            editor.exportOptions = editor.exportOptions.copy(format = AudioFormat.entries[it])
        }
        if (options.format == AudioFormat.ORIGINAL) Text(
            listOfNotNull(audio.sourceFormat, audio.encoding?.codec)
                .filter { it.isNotEmpty() }.map { it.uppercase() }.distinct().joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (copy) Text(stringResource(R.string.audio_copy_hint), style = MaterialTheme.typography.bodySmall)
        val reset = if (copy || problem != null) options else {
            when {
                bitrateRange != null -> {
                    val choices = bitrateSteps ?: listOf(32, 48, 64, 96, 128, 192, 256, 320).filter { it in bitrateRange }
                    ChoiceChips(choices.map { it to "$it" }, options.bitrateKbps) {
                        editor.exportOptions = options.copy(bitrateKbps = it)
                        revision++
                    }
                    key(options.format, bitrateSteps, revision) {
                        NumberField(R.string.bitrate_kbps, bitrateSteps?.indexOf(options.bitrateKbps) ?: options.bitrateKbps,
                            Modifier.fillMaxWidth(), range = bitrateRange, onValidityChanged = { valid = it },
                            parse = { text -> text.toIntOrNull()?.let { bitrateSteps?.indexOf(it) ?: it } },
                            format = { (bitrateSteps?.get(it) ?: it).toString() }) {
                            editor.exportOptions = options.copy(bitrateKbps = bitrateSteps?.get(it) ?: it)
                        }
                    }
                    Text(stringResource(R.string.audio_bitrate_hint), style = MaterialTheme.typography.bodySmall)
                    options.copy(bitrateKbps = defaults.bitrateKbps)
                }
                encoding?.codec == "vorbis" -> {
                    Text("${stringResource(R.string.quality)} · ${options.vorbisQuality}")
                    ValueSlider(options.vorbisQuality.toFloat(), 0f..10f) {
                        editor.exportOptions = options.copy(vorbisQuality = it.roundToInt())
                    }
                    options.copy(vorbisQuality = defaults.vorbisQuality)
                }
                encoding?.codec == "flac" -> {
                    Text(stringResource(R.string.audio_compression, options.compression))
                    ValueSlider(options.compression.toFloat(), 0f..12f) {
                        editor.exportOptions = options.copy(compression = it.roundToInt())
                    }
                    Text(stringResource(R.string.audio_flac_hint), style = MaterialTheme.typography.bodySmall)
                    options.copy(compression = defaults.compression)
                }
                options.format == AudioFormat.WAV -> {
                    Text(stringResource(R.string.audio_bit_depth), style = MaterialTheme.typography.bodyMedium)
                    ChoiceChips(listOf(16, 24, 32).map { it to "$it bit" }, options.bits) {
                        editor.exportOptions = options.copy(bits = it)
                    }
                    options.copy(bits = defaults.bits)
                }
                else -> options
            }
        }
        if (problem != null) Text(stringResource(problem), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        val extension = encoding?.extension
        if (extension != null && extension in mp4AudioExtensions && audio.pictures.any {
                it.description.isNotEmpty() || it.pictureType.isNotEmpty()
            }) Text(stringResource(R.string.audio_mp4_artwork_hint, extension.uppercase()),
                style = MaterialTheme.typography.bodySmall)
        ExportActions(
            preview = model::previewAudio,
            share = { model.shareAudio { MediaFiles.share(context, it, audio.name) } },
            save = model::saveAudio, enabled = valid && problem == null,
        ) {
            TextButton({
                editor.exportOptions = reset
                valid = true
                revision++
            }, enabled = !valid || options != reset.forMedia(audio)) {
                Text(stringResource(R.string.video_reset_values))
            }
        }
    }
}
