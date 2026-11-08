package com.jasongzy.medialab

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BatchFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun namesPreserveExtensionsAndRejectPathSegments() {
        assertEquals("歌曲.01_cover.jpg", MediaFiles.outputName("歌曲.01.flac", "jpg", "cover"))
        assertEquals(
            "song.flac",
            BatchFiles.uniqueName("song.flac", listOf("song 2.flac", "song.mp3")),
        )
        assertEquals(
            "歌曲.01.NCM (2).flac",
            BatchFiles.uniqueName(
                Ncm.outputName("歌曲.01.NCM", "flac"),
                listOf("歌曲.01.ncm.FLAC", "歌曲.01.ncm (1).flac"),
            ),
        )
        for (name in listOf("", ".", "..", "a/b", "a\\b", "a\u0000b")) assertFalse(
            BatchFiles.validName(name)
        )
        assertTrue(BatchFiles.validName("歌曲 01.flac"))
    }

    @Test
    fun cleanupComparesEncodedAudioAcrossTagChanges() {
        val audio = byteArrayOf(-1, -5) + ByteArray(512) { it.toByte() }
        val plain = file(audio)
        val id3 = "ID3".toByteArray() + byteArrayOf(4, 0, 0, 0, 0, 0, 12) +
            "TIT2".toByteArray() + byteArrayOf(0, 0, 0, 2, 0, 0, 3, 65)
        val tagged = file(id3 + audio + "TAG".toByteArray() + ByteArray(125))
        assertEquals(
            AudioFingerprint.payload(plain, "mp3"),
            AudioFingerprint.payload(tagged, "mp3"),
        )
        val different = file(audio.copyOf().apply { this[100] = 11 })
        assertNotEquals(
            AudioFingerprint.payload(plain, "mp3"),
            AudioFingerprint.payload(different, "mp3"),
        )
        for (invalid in listOf(
            "ID3".toByteArray() + ByteArray(16), byteArrayOf(-1, -15) + ByteArray(16),
            audio.copyOf().apply { this[1] = 0xfd.toByte() }, // MPEG Layer II
            audio.copyOf().apply { this[2] = 0xf0.toByte() }, audio.copyOf().apply { this[2] = 0x0c },
        )) {
            assertThrows(IllegalArgumentException::class.java) { AudioFingerprint.payload(file(invalid), "mp3") }
        }

        val frames = byteArrayOf(-1, -8) + ByteArray(512) { it.toByte() }
        val streamInfo = ByteArray(34)
        val flac = file("fLaC".toByteArray() + byteArrayOf(-128, 0, 0, 34) + streamInfo + frames)
        val withTags =
            file(
                id3 + "fLaC".toByteArray() +
                    byteArrayOf(0, 0, 0, 34) +
                    streamInfo +
                    byteArrayOf(-124, 0, 0, 5) +
                    ByteArray(5) +
                    frames
            )
        assertEquals(
            AudioFingerprint.payload(flac, "flac"),
            AudioFingerprint.payload(withTags, "flac"),
        )
        assertNotEquals(
            AudioFingerprint.payload(flac, "flac"),
            AudioFingerprint.payload(file(flac.readBytes().apply { this[15] = 1 }), "flac"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AudioFingerprint.payload(file(flac.readBytes().copyOf(42)), "flac")
        }
    }

    private fun file(bytes: ByteArray): File = folder.newFile().apply { writeBytes(bytes) }
}
