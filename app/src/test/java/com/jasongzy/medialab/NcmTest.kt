package com.jasongzy.medialab

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NcmTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun identifiesNcmByContentAndRejectsUnrelatedOrShortFiles() {
        val source = fixture()
        assertTrue(Ncm.matches(source))
        val misleading = folder.newFile("photo.ncm")
        for (bytes in
            listOf(
                byteArrayOf(),
                "CTENFDA".toByteArray(),
                byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70),
            )) {
            misleading.writeBytes(bytes)
            assertFalse(Ncm.matches(misleading))
        }
    }

    @Test
    fun decodesAcrossBufferBoundariesWithMetadataAndPaddedCover() = runBlocking {
        val source = fixture()
        val output = folder.newFile()
        var completed = 0L
        val result = Ncm.decode(source, output) { done, _ -> completed = done }
        assertEquals("flac", result.extension)
        assertEquals(150004L, output.length())
        assertEquals(
            "c911da932bacc7bf0f5b7e5d195fa2d95856e06da2ef1ead2993223c0b9e0310",
            MessageDigest.getInstance("SHA-256").digest(output.readBytes()).joinToString("") {
                "%02x".format(it)
            },
        )
        assertTrue(result.metadata!!.contains("Media Lab"))
        assertEquals(79, result.cover.size)
        assertEquals(source.length(), completed)
        val inspected = source.inputStream().use {
            AudioFiles.unlock(it, folder.root, source.length(), restoreMetadata = false) {}
        }
        assertEquals("flac", inspected.extension)
        assertArrayEquals(output.readBytes(), inspected.readBytes())
    }

    @Test
    fun decodesShortReadsAndHashesTheEntireContainer() {
        val source = fixture()
        val output = folder.newFile()
        val digest = MessageDigest.getInstance("SHA-256")
        val shortReads =
            object : java.io.FilterInputStream(source.inputStream()) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    `in`.read(bytes, offset, minOf(length, 3))
            }
        java.security.DigestInputStream(shortReads, digest).use { Ncm.decode(it, output) }
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(source.readBytes()),
            digest.digest(),
        )
        assertEquals(150004L, output.length())
    }

    @Test
    fun truncatedOrCancelledInputDoesNotLeaveOutput() {
        val source = fixture()
        val output = folder.newFile()
        assertThrows(InterruptedException::class.java) {
            source.inputStream().use {
                Ncm.decode(
                    it,
                    output,
                    onFormat = { extension ->
                        assertEquals("flac", extension)
                        throw InterruptedException()
                    },
                )
            }
        }
        assertFalse(output.exists())
        assertThrows(InterruptedException::class.java) {
            Ncm.decode(source, output) { _, _ -> throw InterruptedException() }
        }
        assertFalse(output.exists())
        source.writeBytes(source.readBytes().copyOf(20))
        assertThrows(IllegalArgumentException::class.java) { Ncm.decode(source, output) }
        assertFalse(output.exists())
    }

    private fun fixture(): File =
        folder.newFile().apply {
            writeBytes(
                NcmTest::class.java.getResourceAsStream("/generated.ncm")!!.use { it.readBytes() }
            )
        }
}
