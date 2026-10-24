package com.jasongzy.medialab

import android.content.ContentValues
import android.content.Context
import androidx.core.content.FileProvider
import android.content.Intent
import android.content.ClipData
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal enum class ImportType { IMAGE, AUDIO }

internal class UnsupportedMediaException(val messageRes: Int) : Exception()

internal object MediaFiles {
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime(file))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(file.name, uri) }
        context.startActivity(Intent.createChooser(intent, null))
    }

    private val cacheCleanup = Mutex()

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

    suspend fun clearCache(
        directory: File,
        retained: Set<File>,
        keepDirectory: (File) -> Boolean = { false },
    ): Unit = cacheCleanup.withLock {
        val root = directory.toPath().toAbsolutePath().normalize()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return@withLock
        val keep = retained.map { it.toPath().toAbsolutePath().normalize() }.toSet()
        val coroutine = currentCoroutineContext()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                coroutine.ensureActive()
                return if (keepDirectory(path.toFile())) FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE
            }

            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                coroutine.ensureActive()
                if (path !in keep) Files.deleteIfExists(path)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(path: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                coroutine.ensureActive()
                if (path != root) {
                    try {
                        Files.deleteIfExists(path)
                    } catch (_: DirectoryNotEmptyException) {}
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    fun name(context: Context, uri: Uri): String {
        val name =
            if (uri.scheme == "http" || uri.scheme == "file") uri.lastPathSegment
            else
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
        return name
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() } ?: "media"
    }

    fun temporary(directory: File, extension: String): File =
        File(directory, "${UUID.randomUUID()}.$extension").also {
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

    suspend fun import(context: Context, uri: Uri, directory: File): Pair<File, String> {
        val name = name(context, uri)
        val file = temporary(directory, name.substringAfterLast('.', "bin").lowercase().take(10))
        try {
            file.outputStream().buffered().use { output ->
                if (uri.scheme == "http") copyLocalStream(uri.toString(), output)
                else
                    checkNotNull(context.contentResolver.openInputStream(uri)).use {
                        copy(it, output)
                    }
            }
            if (file.length() == 0L) throw UnsupportedMediaException(R.string.empty_media_file)
            return file to name
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    suspend fun copyLocalStream(source: String, output: OutputStream) {
        val uri = URI(source)
        if (uri.scheme != "http" || uri.host != "127.0.0.1" || uri.userInfo != null)
            throw UnsupportedMediaException(R.string.media_stream_unavailable)
        currentCoroutineContext().ensureActive()
        val connection = uri.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
            }
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
            val expected = connection.contentLengthLong
            var copied = 0L
            connection.inputStream.use { copy(it, output) { count -> copied = count } }
            if (expected >= 0 && copied != expected) throw EOFException("Incomplete media stream")
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            throw UnsupportedMediaException(R.string.media_stream_unavailable).apply {
                initCause(error)
            }
        } finally {
            connection.disconnect()
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

    fun outputName(name: String, extension: String, suffix: String = "edited"): String =
        name.substringBeforeLast('.').trim().ifBlank { "media" } + "_$suffix.$extension"

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

    suspend fun save(context: Context, file: File, name: String, type: ImportType): String {
        val resolver = context.contentResolver
        val collection =
            if (type == ImportType.IMAGE) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = if (type == ImportType.IMAGE) "Pictures/MediaLab/" else "Music/MediaLab/"
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
            return relativePath + name(context, uri)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }.onFailure(error::addSuppressed)
            throw error
        }
    }
}
