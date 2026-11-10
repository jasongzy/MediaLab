package com.jasongzy.medialab

import java.io.File
import java.io.FilterInputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NcmTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun recognizesTheCompleteNcmHeader() {
        assertTrue(Ncm.matches(fixture()))
        assertFalse(Ncm.matches("CTENFDA".byteInputStream()))
    }

    @Test
    fun decodesAcrossBufferBoundariesWithMetadataAndPaddedCover() = runBlocking {
        val source = fixture()
        val output = folder.newFile()
        var completed = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        val shortReads = object : FilterInputStream(source.inputStream()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int) =
                `in`.read(bytes, offset, minOf(length, 3))
        }
        val result = DigestInputStream(shortReads, digest).use {
            Ncm.decode(it, output) { done, _ -> completed = done }
        }
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source.readBytes()), digest.digest())
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
        val truncated = source.readBytes().copyOf(20)
        assertThrows(IllegalArgumentException::class.java) {
            Ncm.decode(truncated.inputStream(), output, truncated.size.toLong())
        }
        assertFalse(output.exists())
    }

    private fun fixture(): File =
        folder.newFile().apply {
            writeBytes(
                NcmTest::class.java.getResourceAsStream("/generated.ncm")!!.use { it.readBytes() }
            )
        }
}
