package com.jasongzy.medialab

import java.io.File
import com.kyant.taglib.AudioProperties
import org.junit.Assert.*
import org.junit.Test

class AudioEditsTest {
    @Test
    fun numericTagsAllowFormattingChangesButRejectLostValues() {
        for (key in listOf("DISCNUMBER", "TRACKNUMBER")) {
            assertTrue(AudioFiles.sameTagValues(key, listOf(" 01 "), listOf("1")))
            assertTrue(AudioFiles.sameTagValues(key, listOf("003/012"), listOf("3/12")))
            assertTrue(AudioFiles.sameTagValues(key, listOf("01/00"), listOf("1")))
            for ((before, after) in listOf("1/2" to "1", "65537" to "1", "invalid" to "0",
                    "" to "0", "-1" to "65535", "1/0/2" to "1"))
                assertFalse(AudioFiles.sameTagValues(key, listOf(before), listOf(after)))
            assertFalse(AudioFiles.sameTagValues(key, listOf("01", "02"), listOf("1")))
            assertFalse(AudioFiles.sameTagValues(key, listOf("01"), emptyList()))
        }
        assertFalse(AudioFiles.sameTagValues("TITLE", listOf("01"), listOf("1")))
    }

    @Test
    fun originalExportCopiesMetadataEditsAndValidatesEncodingAfterMediaEdits() {
        val metadata = AudioMetadata(emptyMap(), emptyList())
        val audio = AudioDocument(File("song.ape"), "song.ape", null, metadata, emptyList(),
            AudioEncoding("ape", "ape", 16))
        val original = AudioExportOptions()
        audio.editor.commit(audio.editor.original.copy(metadata = metadata.copy(tags = mapOf("TITLE" to listOf("Edit")))),
            R.string.tags)
        assertTrue(original.copiesOriginal(audio))
        assertNull(original.problem(audio))
        audio.editor.commit(audio.editor.history.value.copy(media = AudioMedia(File("edit.flac"),
            AudioProperties(1000, 0, 48000, 6), emptyList())), R.string.video_time)
        assertFalse(original.copiesOriginal(audio))
        assertEquals(R.string.audio_original_unavailable, original.problem(audio))
        assertEquals(R.string.audio_mp3_channels, AudioExportOptions(AudioFormat.MP3).problem(audio))
        assertNull(AudioExportOptions(AudioFormat.FLAC).problem(audio))
        audio.editor.history.undo()
        assertTrue(original.copiesOriginal(audio))
    }

    @Test
    fun becomingMonoLeavesTheChannelTool() {
        val stereo = AudioFrame(
            AudioMedia(File("stereo"), AudioProperties(1000, 128, 44100, 2), emptyList()),
            AudioMetadata(emptyMap(), emptyList()),
        )
        val mono = stereo.copy(media = stereo.media.copy(properties = stereo.media.properties!!.copy(channels = 1)))
        val editor = AudioEditorState(stereo)
        editor.tool = AudioTool.CHANNELS
        editor.selectedChannels = setOf(0, 1)
        editor.audition = true
        editor.commit(mono, R.string.audio_channels)
        editor.resetPlayback()
        assertEquals(AudioTool.TAGS, editor.tool)
        assertTrue(editor.selectedChannels.isEmpty())
        assertFalse(editor.audition)
        editor.history.undo()
        editor.resetPlayback()
        editor.tool = AudioTool.CHANNELS
        editor.history.redo()
        editor.resetPlayback()
        assertEquals(AudioTool.TAGS, editor.tool)
    }

    @Test
    fun restoringTheOriginalKeepsAnUndoStepForLargeFiles() {
        val original = AudioFrame(AudioMedia(File("original"), null, emptyList()), AudioMetadata(emptyMap(), emptyList()))
        val edited = original.copy(media = original.media.copy(file = object : File("large-edit") {
            override fun length() = 600L * 1024 * 1024
        }))
        val editor = AudioEditorState(original)
        editor.commit(edited, R.string.video_time)
        editor.commit(original, R.string.restore_original_audio)
        assertEquals(edited, editor.history.undoValue)
        assertTrue(editor.files.containsAll(listOf(original.media.file, edited.media.file)))
    }

    @Test
    fun channelEditsPreserveUnselectedOrderAndRequireAudibleOutput() {
        assertEquals(listOf(listOf(0, 2), listOf(1), listOf(3)), channelGroups(4, setOf(0, 2), true))
        assertEquals(listOf(listOf(1), listOf(3)), channelGroups(4, setOf(0, 2), false))
        assertEquals("pan=1c|c0<c0+c1", channelFilter(channelGroups(2, setOf(0, 1), true)))
        assertThrows(IllegalArgumentException::class.java) { channelGroups(2, setOf(0, 1), false) }
        assertThrows(IllegalArgumentException::class.java) { channelGroups(2, setOf(0, 2), true) }
    }

    @Test
    fun trimSpeedAndSilenceUseOneDurationContract() {
        val edit = MediaTimeEdit(1500, startMs = 500, beforeMs = 200, afterMs = 300, speedPercent = 50)
        edit.validate(2000)
        assertEquals(2500L, edit.durationMs)
        assertEquals(834L, edit.copy(speedPercent = 300).durationMs)
        assertThrows(IllegalArgumentException::class.java) { edit.copy(endMs = 3000).validate(2000) }
        assertThrows(IllegalArgumentException::class.java) { edit.copy(speedPercent = 0).validate(2000) }
        assertThrows(IllegalArgumentException::class.java) { edit.copy(beforeMs = Int.MAX_VALUE.toLong()).validate(2000) }
    }
}
