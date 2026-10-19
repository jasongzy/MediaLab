package com.jasongzy.medialab

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun restoredOriginalSurvivesUndoRedoAndBranchCleanup() = runBlocking {
        val info = PhotoInfo(1, 1, 1, 1, 1, "image/jpeg", 5, PhotoColor(), null, emptyList())
        val original = PhotoFrame(file(folder.root, "original.jpg"), info)
        val edited = PhotoFrame(file(folder.root, "edited.jpg"), info)
        val branch = PhotoFrame(file(folder.root, "branch.jpg"), info)
        val history = PhotoHistory(folder.root, original)
        try {
            history.append(edited)
            history.append(original)
            assertEquals(listOf(original, edited, original), history.frames)
            history.move(1)
            assertSame(edited, history.current)
            history.move(2)
            assertSame(original, history.current)
            history.move(1)
            history.append(branch)
            assertEquals(listOf(original, edited, branch), history.frames)
            assertTrue(original.source!!.isFile)
            history.move(0)
            history.append(original)
            assertFalse(edited.source!!.exists())
            assertFalse(branch.source!!.exists())
            assertTrue(original.source.isFile)
        } finally {
            history.close()
        }
        assertFalse(original.source.exists())
    }

    @Test
    fun localImageStreamsPreserveBytesAndRestrictRequests() = runBlocking {
        val bytes = ByteArray(300_000) { it.toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val imageRequests = AtomicInteger()
        server.createContext("/image.webp") { exchange ->
            imageRequests.incrementAndGet()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/image.webp")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val output = ByteArrayOutputStream()
            MediaFiles.copyLocalImage("$base/image.webp", output)
            assertArrayEquals(bytes, output.toByteArray())
            for (source in
                listOf(
                    "$base/redirect",
                    "$base/missing",
                    "$base/image.webp".replace("127.0.0.1", "localhost"),
                    "$base/image.webp".replace("127.0.0.1", "user@127.0.0.1"),
                    "$base/image.webp".replace("http:", "https:"),
                )) {
                val rejected = ByteArrayOutputStream()
                try {
                    MediaFiles.copyLocalImage(source, rejected)
                    fail(source)
                } catch (error: UnsupportedMediaException) {
                    assertEquals(R.string.image_stream_unavailable, error.messageRes)
                    assertEquals(0, rejected.size())
                }
            }
            assertEquals(1, imageRequests.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun clearingCachePreservesActiveWorkspacesAndPersistentFiles() = runBlocking {
        val cache = folder.newFolder("cache")
        val model = file(folder.root, "files/models/model/rmbg14.tflite")
        val settings = file(folder.root, "shared_prefs/settings.xml")
        val saved = file(folder.root, "Pictures/saved.png")
        val abandoned = WorkspaceCache(cache) { emptySet() }.use { file(it.work, "unfinished.part") }
        var firstFiles = emptySet<File>()
        WorkspaceCache(cache) { firstFiles }.use { first ->
            val history = listOf(file(first.work, "original.jpg"), file(first.work, "edited.png"))
            val info = PhotoInfo(1, 1, 1, 1, 1, "image/png", 5, PhotoColor(), null, emptyList())
            val frames = history.map { PhotoFrame(it, info) }
            val snapshot = file(first.work, "history.pixels")
            frames.last().snapshot = snapshot
            val audio = file(first.work, "tagged.mp3")
            val export = file(first.work, "export.webp")
            val shared = file(first.exports, "shared.png")
            val state =
                LabState(
                    photo = PhotoState(history = frames, position = 1),
                    audio = AudioDocument(audio, audio.name, null, AudioMetadata(emptyMap(), emptyList()), emptyList()),
                    exportFile = export,
                )
            firstFiles = state.workingFiles
            val stale = listOf(
                file(first.work, "unused.png"),
                file(cache, "exports/stale.png"),
                file(cache, "ncm/decoded.flac"),
            )
            lateinit var secondWork: File
            lateinit var secondExports: File
            var secondFiles = emptySet<File>()
            WorkspaceCache(cache) { secondFiles }.use { second ->
                secondWork = second.work
                secondExports = second.exports
                secondFiles = setOf(
                    file(second.work, "original.jpg"),
                    file(second.work, "history.pixels"),
                    file(second.work, "audio.mp3"),
                )
                val otherStale = listOf(
                    file(second.work, "unused.png"),
                    file(second.exports, "shared.png"),
                )
                val importing = file(second.work, "importing.part")
                second.clearStale()
                assertFalse(abandoned.exists())
                assertTrue(state.workingFiles.all { it.isFile })
                second.access.lock()
                val clearing = async(start = CoroutineStart.UNDISPATCHED) { first.clearAll() }
                try {
                    assertFalse(clearing.isCompleted)
                    assertTrue(importing.isFile)
                    secondFiles += importing
                } finally {
                    second.access.unlock()
                }
                clearing.await()
                for (kept in firstFiles + secondFiles + listOf(saved, model, settings))
                    assertEquals(kept.path, "media", kept.readText())
                assertTrue((stale + otherStale + shared).none { it.exists() })
                first.clearStale()
                assertTrue(secondFiles.all { it.isFile })
            }
            first.clearStale()
            assertFalse(secondWork.exists())
            first.clearAll()
            assertFalse(secondExports.exists())
            assertTrue(state.workingFiles.all { it.isFile })
        }
        MediaFiles.clearCache(File(folder.root, "missing"), emptySet())
        assertTrue(saved.exists())
    }

    @Test
    fun indexRecoveryScansTheMissingSubtreeWithinItsVolume() {
        val root = folder.newFolder("volume")
        val output = File(root, "Music/MediaLab")
        val song = File(output, "song.mp3")
        assertEquals(File(root, "Music"), MediaFiles.missingScanRoot(root, song))
        assertTrue(output.mkdirs())
        assertEquals(song, MediaFiles.missingScanRoot(root, song))
        song.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(MediaFiles.missingScanRoot(root, song))
        assertThrows(IllegalArgumentException::class.java) {
            MediaFiles.missingScanRoot(root, File(root, "../elsewhere/song.mp3"))
        }
        assertThrows(java.nio.file.NoSuchFileException::class.java) {
            val missingVolume = File(root, "missing-volume")
            MediaFiles.missingScanRoot(missingVolume, File(missingVolume, "song.mp3"))
        }
        assertTrue(song.delete())
        assertTrue(output.delete())
        assertEquals(output, MediaFiles.missingScanRoot(root, song))
    }

    private fun file(parent: File, name: String): File =
        File(parent, name).apply {
            parentFile!!.mkdirs()
            writeText("media")
        }
}
