package com.jasongzy.medialab

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun clearingCachePreservesWorkspaceAndPersistentFiles() = runBlocking {
        val cache = folder.newFolder("cache")
        val model = file(folder.root, "files/models/model/rmbg14.tflite")
        val settings = file(folder.root, "shared_prefs/settings.xml")
        val history = listOf(file(cache, "work/original.jpg"), file(cache, "work/edited.png"))
        val info = PhotoInfo(1, 1, 1, 1, 1, "image/png", 5, false, null, emptyList())
        val frames = history.map { PhotoFrame(it, info) }
        val snapshot = file(cache, "work/history.pixels")
        frames.last().snapshot = snapshot
        val audio = file(cache, "work/tagged.mp3")
        val export = file(cache, "work/export.webp")
        val state =
            LabState(
                photo = PhotoState(history = frames, position = 1),
                audio =
                    AudioDocument(
                        audio,
                        audio.name,
                        null,
                        emptyMap(),
                        emptyList(),
                        emptyList(),
                    ),
                exportFile = export,
            )
        val stale =
            listOf(
                file(cache, "work/source.mp3"),
                file(cache, "work/unused.png"),
                file(cache, "exports/shared.png"),
                file(cache, "work/model.part"),
                file(cache, "ncm/decoded.flac"),
            )
        val saved = file(folder.root, "Pictures/saved.png")

        MediaFiles.clearCache(cache, state.workingFiles)

        for (kept in
            history +
                listOf(
                    snapshot,
                    audio,
                    export,
                    saved,
                    model,
                    settings,
                )) assertEquals(kept.name, "media", kept.readText())
        for (removed in stale) assertFalse(removed.path, removed.exists())
        assertFalse(File(cache, "exports").exists())
        MediaFiles.clearCache(cache, state.workingFiles)
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
