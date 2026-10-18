package com.jasongzy.medialab

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal class UnsupportedMediaException(val messageRes: Int) : Exception()

internal object MediaFiles {
    suspend fun refreshMediaIndex(context: Context): Boolean {
        val storage = Environment.getExternalStorageState()
        if (storage != Environment.MEDIA_MOUNTED && storage != Environment.MEDIA_MOUNTED_READ_ONLY)
            return false
        return suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(
                context,
                arrayOf(Environment.getExternalStorageDirectory().absolutePath),
                null,
            ) { _, uri ->
                continuation.resume(uri != null)
            }
        }
    }

    suspend fun clearCache(directory: File, retained: Set<File>) {
        val root = directory.toPath().toAbsolutePath().normalize()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return
        val keep = retained.map { it.toPath().toAbsolutePath().normalize() }.toSet()
        val coroutine = currentCoroutineContext()
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                coroutine.ensureActive()
                if (path == root || path in keep) return@forEach
                if (
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                        Files.newDirectoryStream(path).use { it.iterator().hasNext() }
                )
                    return@forEach
                Files.deleteIfExists(path)
            }
        }
    }

    fun name(context: Context, uri: Uri): String =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() } ?: "media"

    fun temporary(context: Context, extension: String): File =
        File(context.cacheDir, "work/${UUID.randomUUID()}.$extension").also {
            it.parentFile!!.mkdirs()
        }

    suspend fun copy(input: InputStream, output: OutputStream, progress: (Long) -> Unit = {}) {
        val coroutine = currentCoroutineContext()
        val buffer = ByteArray(256 * 1024)
        var done = 0L
        while (true) {
            coroutine.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) {
                output.write(buffer, 0, count)
                done += count
                progress(done)
            }
        }
    }

    suspend fun import(context: Context, uri: Uri): Pair<File, String> {
        val name = name(context, uri)
        val file = temporary(context, name.substringAfterLast('.', "bin").lowercase().take(10))
        try {
            checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                file.outputStream().buffered().use { copy(input, it) }
            }
            require(file.length() > 0) { "Empty input" }
            return file to name
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    fun mime(file: File): String =
        when (file.extension.lowercase()) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "m4a",
            "mp4" -> "audio/mp4"
            "opus",
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "ape" -> "audio/x-ape"
            "wv" -> "audio/x-wavpack"
            "aac" -> "audio/aac"
            "aif",
            "aiff" -> "audio/x-aiff"
            "wma" -> "audio/x-ms-wma"
            else ->
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                    ?: "application/octet-stream"
        }

    fun outputName(name: String, extension: String): String =
        name.substringBeforeLast('.').trim().ifBlank { "media" } + "_edited.$extension"

    fun existsOnDisk(path: String): Boolean =
        try {
            Files.readAttributes(File(path).toPath(), BasicFileAttributes::class.java)
            true
        } catch (_: NoSuchFileException) {
            false
        }

    fun missingScanRoot(root: File, file: File): File? {
        val base = root.toPath().toAbsolutePath().normalize()
        var path = file.toPath().toAbsolutePath().normalize()
        require(path != base && path.startsWith(base))
        Files.readAttributes(base, BasicFileAttributes::class.java)
        if (existsOnDisk(path.toString())) return null
        while (path.parent != base && !existsOnDisk(path.parent.toString())) path = path.parent
        return path.toFile()
    }

    suspend fun indexedFiles(
        context: Context,
        collection: Uri,
        relativePath: String,
    ): MutableList<BatchFile> {
        val resolver = context.contentResolver
        val missing = mutableSetOf<File>()
        suspend fun query(): MutableList<BatchFile> {
            val list = mutableListOf<BatchFile>()
            checkNotNull(
                    resolver.query(
                        collection,
                        arrayOf(
                            MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.DATA,
                        ),
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
                        arrayOf(relativePath),
                        null,
                    )
                )
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val file = File(checkNotNull(cursor.getString(2)))
                        if (!existsOnDisk(file.path)) {
                            missing += file
                            continue
                        }
                        list +=
                            BatchFile(
                                Uri.withAppendedPath(
                                    collection,
                                    cursor.getLong(0).toString(),
                                ),
                                cursor.getString(1),
                            )
                    }
                }
            return list
        }
        var files = query()
        if (missing.isNotEmpty()) {
            // Publishing renames the pending file; stale final paths must be released beforehand.
            val root = Environment.getExternalStorageDirectory()
            val scans = missing.mapNotNull { missingScanRoot(root, it) }.toSet()
            for (file in scans) {
                check(
                    withTimeoutOrNull(30_000) {
                        suspendCancellableCoroutine { continuation ->
                            MediaScannerConnection.scanFile(context, arrayOf(file.path), null) {
                                _,
                                _ ->
                                continuation.resume(Unit)
                            }
                        }
                    } != null
                ) {
                    context.getString(R.string.batch_refresh_failed)
                }
            }
            missing.clear()
            files = query()
            check(missing.isEmpty()) { context.getString(R.string.batch_refresh_failed) }
        }
        return files
    }

    suspend fun save(context: Context, file: File, name: String, image: Boolean): Uri {
        val resolver = context.contentResolver
        val collection =
            if (image) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = if (image) "Pictures/MediaLab/" else "Music/MediaLab/"
        val existing = indexedFiles(context, collection, relativePath)
        val values =
            ContentValues().apply {
                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    BatchFiles.uniqueName(name, existing.map { it.name }),
                )
                put(MediaStore.MediaColumns.MIME_TYPE, mime(file))
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    relativePath,
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = checkNotNull(resolver.insert(collection, values))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { output ->
                file.inputStream().buffered().use { copy(it, output) }
            }
            currentCoroutineContext().ensureActive()
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1) { "Cannot publish output" }
            return uri
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }.onFailure(error::addSuppressed)
            throw error
        }
    }
}
