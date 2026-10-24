package com.jasongzy.medialab

import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import com.kyant.taglib.Picture
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

internal object AudioRegression {
    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun run(app: Application, instrumentation: Instrumentation) {
        val input = File(app.filesDir, "stereo.wav")
        MediaProcessing.execute(listOf("-f", "lavfi", "-i",
            "aevalsrc=0.25|-0.125:s=48000:d=1.2345", "-c:a", "pcm_s16le", input.path))
        val original = AudioFiles.read(input, input.name)
        val numbered = File(app.filesDir, "numbered.mp3")
        MediaProcessing.execute(listOf("-i", input.path, "-c:a", "libmp3lame",
            "-metadata", "disc=01", "-metadata", "track=003/012", numbered.path))
        val numberedMp3 = AudioFiles.read(numbered, numbered.name)
        check(numberedMp3.tags["DISCNUMBER"] == listOf("01"))
        check(numberedMp3.tags["TRACKNUMBER"] == listOf("003/012"))
        val numberedM4a = exportAudio(app.cacheDir, numberedMp3, AudioExportOptions(AudioFormat.M4A))
        val numberedResult = AudioFiles.read(numberedM4a, numberedM4a.name)
        check(numberedResult.tags["DISCNUMBER"] == listOf("1"))
        check(numberedResult.tags["TRACKNUMBER"] == listOf("3/12"))
        for ((format, mime) in listOf(Bitmap.CompressFormat.JPEG to "image/jpg",
            Bitmap.CompressFormat.WEBP_LOSSLESS to "image/webp")) {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0x804080c0.toInt()) }
            val bytes = ByteArrayOutputStream().use { bitmap.compress(format, 100, it); it.toByteArray() }
            bitmap.recycle()
            val picture = Picture(bytes, "测试封面", "Front Cover", mime)
            AudioFiles.writeTags(numbered, numberedMp3.tags, listOf(picture))
            val document = AudioFiles.read(numbered, numbered.name)
            val output = exportAudio(app.cacheDir, document, AudioExportOptions(AudioFormat.M4A))
            val cover = AudioFiles.read(output, output.name).pictures.single()
            if (format == Bitmap.CompressFormat.JPEG) {
                check(cover.data.contentEquals(bytes) && cover.mimeType == "image/jpeg")
            } else {
                val expected = checkNotNull(AudioFiles.thumbnail(bytes))
                val actual = checkNotNull(AudioFiles.thumbnail(cover.data))
                try { check(actual.sameAs(expected) && cover.mimeType == "image/png") }
                finally { expected.recycle(); actual.recycle() }
            }
            output.delete()
        }
        val recording = File(app.filesDir, "recording.aac")
        MediaProcessing.execute(listOf("-i", input.path, "-ac", "1", "-c:a", "aac",
            "-metadata", "title=Recording", "-f", "mp4", recording.path))
        val imported = AudioFiles.read(recording, recording.name)
        check(imported.current.canEdit && imported.current.channels == 1)
        check(abs(imported.current.durationMs - original.current.durationMs) < 100)
        check(imported.tags["TITLE"] == listOf("Recording"))
        val tagged = AudioFiles.save(app.cacheDir, imported, mapOf("TITLE" to listOf("Edited")), emptyList())
        val rereadRecording = AudioFiles.read(tagged, tagged.name)
        check(rereadRecording.tags["TITLE"] == listOf("Edited") && rereadRecording.current.canEdit)
        check(tagged.extension == "m4a" && imported.encoding?.codec == "aac")
        suspend fun aacPackets(file: File): ByteArray {
            val packets = File(app.cacheDir, "packets.aac")
            MediaProcessing.execute(listOf("-i", file.path, "-map", "0:a:0", "-c:a", "copy", "-f", "adts", packets.path))
            return packets.readBytes().also { packets.delete() }
        }
        check(aacPackets(recording).contentEquals(aacPackets(tagged)))
        val variable = File(app.filesDir, "variable.aac")
        MediaProcessing.execute(listOf("-f", "lavfi", "-i",
            "aevalsrc=if(lt(t\\,20)\\,0.2*sin(2*PI*440*t)\\,0):s=48000:d=30",
            "-c:a", "aac", "-b:a", "64k", variable.path))
        val indexed = AudioFiles.read(variable, variable.name)
        check(indexed.playbackFile != variable && indexed.playbackFile in indexed.workingFiles)
        check(abs(indexed.current.durationMs - 30000) < 100)
        check(aacPackets(variable).contentEquals(aacPackets(indexed.playbackFile)))
        suspend fun playbackDuration(file: File): Long {
            val ended = CompletableDeferred<Long>()
            lateinit var player: ExoPlayer
            instrumentation.runOnMainSync {
                val renderers = object : DefaultRenderersFactory(app) {
                    override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean,
                        enableAudioOutputPlaybackParams: Boolean): AudioSink? =
                        super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)?.let(::PlaybackAudioSink)
                }
                player = ExoPlayer.Builder(app, renderers).build().apply {
                    var started = false
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY && !started) {
                                started = true
                                seekTo((duration - 250).coerceAtLeast(0))
                                play()
                            }
                            if (playbackState == Player.STATE_ENDED) ended.complete(duration)
                        }
                        override fun onPlayerError(error: PlaybackException) { ended.completeExceptionally(error) }
                    })
                    volume = 0f
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    prepare()
                }
            }
            return try { withTimeout(10_000) { ended.await() } }
            finally { instrumentation.runOnMainSync { player.release() } }
        }
        check(abs(playbackDuration(indexed.playbackFile) - indexed.current.durationMs) < 10)
        for (document in listOf(imported, original)) {
            for (bits in listOf(16, 24, 32)) {
                val wav = exportAudio(app.cacheDir, document, AudioExportOptions(AudioFormat.WAV, bits = bits))
                try { check(abs(playbackDuration(wav) - document.current.durationMs) < 100) }
                finally { wav.delete() }
            }
        }
        val trimmed = renderAudio(imported.current, File(app.filesDir, "recording-trimmed.flac"), MediaTimeEdit(500))
        check(trimmed.channels == 1 && abs(trimmed.durationMs - 500) <= 1)
        imported.editor.commit(imported.editor.history.value.copy(media = trimmed), R.string.video_time)
        val restoredFormat = exportAudio(app.cacheDir, imported, AudioExportOptions())
        val restored = AudioFiles.read(restoredFormat, restoredFormat.name)
        check(restored.encoding?.codec == "aac" && restored.current.channels == 1 &&
            abs(restored.current.durationMs - 500) < 100)
        suspend fun pcm(media: AudioMedia): ShortArray {
            val raw = File(app.cacheDir, "samples.pcm")
            MediaProcessing.execute(listOf("-i", media.file.path, "-map", "0:a:0", "-f", "s16le", raw.path))
            val buffer = ByteBuffer.wrap(raw.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return ShortArray(buffer.remaining()).also { buffer.get(it); raw.delete() }
        }
        val originalPcm = pcm(original.current)
        val deleted = renderAudio(original.current, File(app.filesDir, "deleted.flac"),
            channels = channelGroups(2, setOf(0), false))
        val deletedPcm = pcm(deleted)
        check(deletedPcm.size == originalPcm.size / 2 && deletedPcm.all { it == (-4096).toShort() })
        val merged = renderAudio(original.current, File(app.filesDir, "merged.flac"),
            channels = channelGroups(2, setOf(0, 1), true))
        check(pcm(merged).all { abs(it - 2048) <= 1 })
        val multichannel = File(app.filesDir, "surround.wav")
        MediaProcessing.execute(listOf("-f", "lavfi", "-i",
            "aevalsrc=0.25|-0.125|0.5|-0.25:s=48000:d=0.1", "-c:a", "pcm_s16le", multichannel.path))
        val three = renderAudio(AudioFiles.read(multichannel, multichannel.name).current,
            File(app.filesDir, "three.flac"), channels = channelGroups(4, setOf(0, 2), true))
        check(pcm(three).take(3) == listOf<Short>(12288, -4096, -8192))
        for ((channels, expected) in listOf(setOf(0) to 8192, setOf(1) to -4096, setOf(0, 1) to 2048)) {
            val processor = channelAudition(2, channels)
            processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
            processor.flush()
            processor.queueInput(ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
                .putShort(8192.toShort()).putShort((-4096).toShort()).apply { flip() })
            check(processor.output.order(ByteOrder.nativeOrder()).short.toInt() == expected)
            processor.reset()
        }
        val edit = MediaTimeEdit(1000, startMs = 200, beforeMs = 200, afterMs = 300, speedPercent = 200)
        val timed = renderAudio(original.current, File(app.filesDir, "timed.flac"), edit)
        check(abs(timed.durationMs - 900) <= 1 && timed.properties?.sampleRate == 48000)
        val samples = pcm(timed)
        check(samples.take(2 * 9500).all { it == 0.toShort() })
        check(samples.drop(2 * 40000).all { it == 0.toShort() })
        check(samples.slice(2 * 15000 until 2 * 20000).any { it != 0.toShort() })

        val store = ViewModelStore()
        lateinit var model: LabViewModel
        suspend fun idle() {
            withTimeout(30_000) { while (model.state.value.busy) delay(10) }
            check(model.state.value.error == null) { model.state.value.error.orEmpty() }
        }
        try {
            instrumentation.runOnMainSync {
                model = LabViewModel(app)
                store.put("audio", model)
                model.open(Uri.fromFile(input), ImportType.AUDIO)
            }
            idle()
            val audio = checkNotNull(model.state.value.audio)
            val picture = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).let { bitmap ->
                val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
                bitmap.recycle()
                Picture(bytes, "测试封面", "Front Cover", "image/png")
            }
            instrumentation.runOnMainSync {
                audio.editor.tags.change(mapOf("TITLE" to listOf("测试")))
                audio.editor.pictures.change(listOf(picture))
                audio.editor.time.change(edit)
                model.applyAudioMetadata(AudioTool.TAGS)
            }
            idle()
            check(audio.editor.pending == listOf(AudioTool.TIME, AudioTool.ARTWORK) && audio.pictures.isEmpty())
            instrumentation.runOnMainSync { model.saveAudio() }
            withTimeout(30_000) { model.state.first { it.confirmation == Confirmation.EXPORT } }
            instrumentation.runOnMainSync { model.answerConfirmation(false) }
            idle()
            check(audio.editor.pictures.value == listOf(picture))
            instrumentation.runOnMainSync { model.applyAudioMetadata(AudioTool.ARTWORK) }
            idle()
            instrumentation.runOnMainSync {
                audio.editor.pictures.change(emptyList())
                model.applyAudioTime()
            }
            idle()
            check(audio.editor.pending == listOf(AudioTool.ARTWORK) && audio.pictures == listOf(picture))
            instrumentation.runOnMainSync { audio.editor.pictures.undo() }
            val applied = audio.current
            check(audio.tags["TITLE"] == listOf("测试") && audio.current.durationMs == timed.durationMs)
            instrumentation.runOnMainSync { model.undoAudio(-1) }
            idle()
            check(audio.current.file == audio.file && audio.tags["TITLE"] == listOf("测试"))
            instrumentation.runOnMainSync { model.undoAudio(1) }
            idle()
            check(audio.current == applied)
            instrumentation.runOnMainSync {
                audio.editor.tags.change(mapOf("TITLE" to listOf("Pending")))
                audio.editor.pictures.change(emptyList())
                model.undoAudio(-1)
            }
            withTimeout(30_000) { model.state.first { it.confirmation == Confirmation.UNDO } }
            instrumentation.runOnMainSync { model.answerConfirmation(false) }
            idle()
            check(audio.current == applied && audio.editor.pending == listOf(AudioTool.TAGS, AudioTool.ARTWORK))
            instrumentation.runOnMainSync { audio.editor.resetMetadata(); model.clearCache() }
            idle()
            check(audio.files.all { it.isFile })
            val saved = AudioFiles.save(app.cacheDir, audio, audio.tags, audio.pictures)
            val reread = AudioFiles.read(saved, saved.name)
            check(reread.tags == audio.tags && reread.pictures.single().data.contentEquals(picture.data))
            check(reread.current.durationMs == applied.durationMs)
            for (format in AudioFormat.entries) {
                val output = exportAudio(app.cacheDir, audio, AudioExportOptions(format))
                val exported = AudioFiles.read(output, output.name)
                check(exported.tags == audio.tags && exported.pictures.single().data.contentEquals(picture.data))
                check(exported.current.channels == applied.channels && abs(exported.current.durationMs - applied.durationMs) < 100)
                check(exported.encoding?.codec == when (format) {
                    AudioFormat.ORIGINAL -> "pcm_s16le"
                    AudioFormat.MP3 -> "mp3"
                    AudioFormat.AAC, AudioFormat.M4A -> "aac"
                    AudioFormat.FLAC -> "flac"
                    AudioFormat.WAV -> "pcm_s24le"
                })
                if (format in listOf(AudioFormat.AAC, AudioFormat.M4A))
                    check(exported.encoding.container == if (format == AudioFormat.AAC) "aac" else "mov")
                if (format in listOf(AudioFormat.ORIGINAL, AudioFormat.FLAC, AudioFormat.WAV))
                    check(pcm(exported.current).contentEquals(pcm(applied)))
                exported.files.forEach { it.delete() }
            }
            instrumentation.runOnMainSync {
                audio.editor.exportOptions = AudioExportOptions(AudioFormat.AAC)
                model.previewAudio()
            }
            idle()
            val preview = checkNotNull(audio.editor.preview)
            check(preview.playable != preview.file && preview.playable.extension == "m4a")
            instrumentation.runOnMainSync { audio.editor.showPreview = false; model.previewAudio() }
            idle()
            check(audio.editor.preview == preview)
            instrumentation.runOnMainSync { model.clearCache() }
            idle()
            check(preview.files.all { it.isFile })
            instrumentation.runOnMainSync { audio.editor.showPreview = false; model.clearCache() }
            idle()
            check(preview.files.none { it.exists() } && audio.editor.files.all { it.isFile })
            instrumentation.runOnMainSync {
                model.undoAudio(-1)
            }
            idle()
            instrumentation.runOnMainSync {
                audio.editor.tags.change(mapOf("TITLE" to listOf("Branch")))
                model.applyAudioMetadata(AudioTool.TAGS)
            }
            idle()
            check(!audio.editor.history.canRedo && !applied.file.exists())
            val workspace = checkNotNull(audio.file.parentFile)
            val retained = workspace.listFiles()!!.toSet()
            instrumentation.runOnMainSync { model.open(Uri.fromFile(variable), ImportType.AUDIO) }
            withTimeout(30_000) { model.state.first { it.confirmation == Confirmation.AUDIO } }
            instrumentation.runOnMainSync { model.answerConfirmation(false) }
            idle()
            check(model.state.value.audio === audio && workspace.listFiles()!!.toSet() == retained)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }
}
