package com.jasongzy.medialab

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal data class BatchFile(
    val uri: Uri,
    val name: String,
    val directory: String = "",
    val size: Long = -1,
    val ncm: Boolean = name.endsWith(".ncm", true),
    val allowsDeletion: Boolean = true,
) {

    val path
        get() = if (directory.isEmpty()) name else "$directory/$name"

    val key = BatchFiles.key(uri)
}

internal class BatchOutputException(val output: BatchFile, override val cause: Exception) :
    Exception(cause.message, cause)

internal class BatchProviderUnavailable(val authority: String?) : Exception()

internal class BatchFiles(private val context: Context, val tree: Uri?) {
    private val resolver = context.contentResolver
    private val collection =
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val folders = mutableMapOf<String, Uri>()
    private val entries = mutableMapOf<String, MutableList<BatchFile>>()

    suspend fun namesIn(directory: String): Sequence<String> = contents(directory).asSequence().map { it.name }

    private fun List<BatchFile>.uniqueMatch(name: String): BatchFile? {
        val matches = filter { it.name.equals(name, ignoreCase = true) }
        check(matches.size <= 1) { context.getString(R.string.batch_ambiguous_name) }
        return matches.firstOrNull()
    }

    suspend fun matchUnlocked(item: BatchItem, decoded: File, sourceDigest: String): CleanupCandidate? {
        val extension = decoded.extension
        val coroutine = currentCoroutineContext()
        val check = { coroutine.ensureActive() }
        val tried = mutableSetOf(item.file.key)
        val expected by lazy(LazyThreadSafetyMode.NONE) { AudioFingerprint.payload(decoded, extension, check) }
        suspend fun match(saved: BatchFile?): CleanupCandidate? {
            if (saved == null || !tried.add(saved.key)) return null
            val input = try {
                openInput(context, saved.uri)
            } catch (_: FileNotFoundException) {
                return null
            }
            val copy = File(decoded.parentFile, "existing.$extension")
            val hash = MessageDigest.getInstance("SHA-256")
            DigestInputStream(input, hash).use { stream ->
                copy.outputStream().use { MediaFiles.copy(stream, it) }
            }
            val actual = try {
                AudioFingerprint.payload(copy, extension, check)
            } catch (_: EOFException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (actual != expected) return null
            return CleanupCandidate(item.file, saved, sourceDigest, AudioFingerprint.hex(hash.digest()))
        }
        val name = Ncm.outputName(item.file.name, extension)
        val directory = item.file.directory
        if (item.outputTree == tree && item.output?.directory == directory)
            match(item.output)?.let { return it }
        val files = contents(directory)
        match(files.uniqueMatch(name)?.copy(directory = directory))?.let { return it }
        val numbered = Regex(Regex.escape(name.substringBeforeLast('.')) +
            " \\([1-9][0-9]*\\)\\." + Regex.escape(extension), RegexOption.IGNORE_CASE)
        for (file in files) {
            if (numbered.matches(file.name)) match(file.copy(directory = directory))?.let { return it }
        }
        return match(item.output)
    }

    private suspend fun folder(path: String, create: Boolean = false): Uri? {
        folders[path]?.let {
            return it
        }
        val root = requireNotNull(tree)
        if (path.isEmpty()) return document(root).also { folders[path] = it }
        val parent = folder(path.substringBeforeLast('/', ""), create) ?: return null
        val name = path.substringAfterLast('/')
        require(validName(name))
        val matches = children(context, root, parent).filter { it.second && it.first.name == name }
        check(matches.size <= 1) { context.getString(R.string.batch_ambiguous_name) }
        val uri =
            matches.firstOrNull()?.first?.uri
                ?: if (create)
                    checkNotNull(
                        DocumentsContract.createDocument(
                            resolver,
                            parent,
                            Document.MIME_TYPE_DIR,
                            name,
                        )
                    )
                else return null
        folders[path] = uri
        return uri
    }

    private suspend fun contents(path: String): MutableList<BatchFile> {
        entries[path]?.let {
            return it
        }
        val result =
            if (tree != null) {
                folder(path)
                    ?.let { children(context, tree, it) }
                    .orEmpty()
                    .filter { !it.second }
                    .map { it.first }
                    .toMutableList()
            } else
                MediaFiles.indexedFiles(
                    context,
                    collection,
                    relativePath(path),
                )
        entries[path] = result
        return result
    }

    suspend fun write(
        file: File,
        directory: String,
        name: String,
        policy: ConflictPolicy,
        source: Uri,
        protectedFiles: Collection<BatchFile> = emptyList(),
        progress: (Float) -> Unit,
    ): Pair<BatchFile, String>? {
        require(validName(name))
        val all = contents(directory)
        if (policy == ConflictPolicy.SKIP && all.any { it.name.equals(name, ignoreCase = true) }) return null
        val replace = if (policy == ConflictPolicy.OVERWRITE) all.uniqueMatch(name) else null
        if (replace != null) {
            requireDistinct(context, replace.uri, source, R.string.batch_same_file)
            for (protected in protectedFiles) {
                if (protected.uri != source &&
                    (protected.key == replace.key || protected.name.equals(replace.name, ignoreCase = true)))
                    requireDistinct(context, replace.uri, protected.uri, R.string.batch_protected_output)
            }
        }
        val finalName =
            if (policy == ConflictPolicy.RENAME) uniqueName(name, all.map { it.name }) else name
        val stagingName =
            if (replace == null) finalName else "MediaLab-${UUID.randomUUID()}.${file.extension}"
        var uri =
            if (tree != null) {
                checkNotNull(
                    DocumentsContract.createDocument(
                        resolver,
                        checkNotNull(folder(directory, create = true)),
                        MediaFiles.mime(file, ImportType.AUDIO),
                        stagingName,
                    )
                )
            } else {
                checkNotNull(
                    resolver.insert(
                        collection,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, stagingName)
                            put(MediaStore.MediaColumns.MIME_TYPE, MediaFiles.mime(file, ImportType.AUDIO))
                            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(directory))
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        },
                    )
                )
            }
        var verified = false
        try {
            val size = file.length()
            val hash = MessageDigest.getInstance("SHA-256")
            checkNotNull(resolver.openOutputStream(uri, "w")).use { output ->
                DigestInputStream(file.inputStream(), hash).use { input ->
                    val copied = MediaFiles.copy(input, output) { progress(0.6f + 0.25f * it / size) }
                    check(copied == size) { context.getString(R.string.batch_source_changed) }
                }
            }
            progress(0.85f)
            val coroutine = currentCoroutineContext()
            val expected = AudioFingerprint.hex(hash.digest())
            check(digest(context, uri) == expected) {
                context.getString(R.string.batch_verification_failed)
            }
            progress(0.95f)
            coroutine.ensureActive()
            if (tree == null)
                check(
                    resolver.update(
                        uri,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        },
                        null,
                        null,
                    ) == 1
                )
            verified = true
            if (replace != null) {
                // A complete verified replacement exists before the old output is removed.
                delete(context, replace.uri)
                all.remove(replace)
                if (tree != null) {
                    uri =
                        checkNotNull(DocumentsContract.renameDocument(resolver, uri, finalName)) {
                            context.getString(R.string.batch_rename_failed)
                        }
                } else {
                    check(
                        resolver.update(
                            uri,
                            ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                            },
                            null,
                            null,
                        ) == 1
                    ) {
                        context.getString(R.string.batch_rename_failed)
                    }
                }
            }
            return info(context, uri).copy(directory = directory).also { all += it } to expected
        } catch (error: Exception) {
            if (!verified) runCatching { delete(context, uri) }
            else {
                entries.remove(directory)
                throw BatchOutputException(
                    BatchFile(uri, stagingName, directory, file.length()),
                    error,
                )
            }
            throw error
        }
    }

    companion object {
        private val audioExtensions =
            setOf(
                "mp3",
                "flac",
                "wav",
                "m4a",
                "ogg",
                "ape",
                "opus",
                "aac",
                "aiff",
                "aif",
                "wma",
                "wv",
            )

        fun supports(file: BatchFile): Boolean =
            file.ncm || file.name.substringAfterLast('.', "").lowercase() in audioExtensions

        fun validName(name: String): Boolean =
            name.isNotBlank() &&
                name !in setOf(".", "..") &&
                name.none { it == '/' || it == '\\' || it.code < 32 }

        fun uniqueName(name: String, existing: List<String>): String {
            val names = existing.map { it.lowercase() }.toHashSet()
            if (name.lowercase() !in names) return name
            val stem = name.substringBeforeLast('.', name)
            val extension =
                name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            var index = 1
            while ("$stem ($index)$extension".lowercase() in names) index++
            return "$stem ($index)$extension"
        }

        fun document(tree: Uri): Uri =
            DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )

        fun key(uri: Uri): String {
            val path = uri.pathSegments
            val document = (path.size == 2 && path[0] == "document") ||
                (path.size == 4 && path[0] == "tree" && path[2] == "document")
            return if (uri.scheme == ContentResolver.SCHEME_CONTENT && document) "${uri.authority}:${path.last()}"
            else uri.toString()
        }

        suspend fun requireDistinct(
            context: Context,
            first: Uri,
            second: Uri,
            conflict: Int,
            identicalBytes: Boolean = false,
        ) {
            when (sameFile(context, first, second)) {
                true -> error(context.getString(conflict))
                null -> check(!identicalBytes && digest(context, first) != digest(context, second)) {
                    context.getString(R.string.batch_uncertain_identity)
                }
                false -> Unit
            }
        }

        private fun sameFile(context: Context, first: Uri, second: Uri): Boolean? {
            if (key(first) == key(second)) return true
            fun mediaPath(uri: Uri): Pair<String, String>? {
                if (uri.authority == "com.android.externalstorage.documents") {
                    val id = DocumentsContract.getDocumentId(uri)
                    val volume = id.substringBefore(':')
                    return (if (volume == "primary") MediaStore.VOLUME_EXTERNAL_PRIMARY
                    else volume.lowercase()) to id.substringAfter(':')
                }
                val media =
                    when (uri.authority) {
                        MediaStore.AUTHORITY -> uri
                        "com.android.providers.media.documents" ->
                            MediaStore.getMediaUri(context, uri) ?: return null
                        else -> return null
                    }
                return checkNotNull(
                        context.contentResolver.query(
                            media,
                            arrayOf(
                                MediaStore.MediaColumns.VOLUME_NAME,
                                MediaStore.MediaColumns.RELATIVE_PATH,
                                MediaStore.MediaColumns.DISPLAY_NAME,
                            ),
                            null,
                            null,
                            null,
                        )
                    )
                    .use {
                        if (it.moveToFirst())
                            it.getString(0) to (it.getString(1).orEmpty() + it.getString(2))
                        else null
                    }
            }
            val firstPath = mediaPath(first)
            val secondPath = mediaPath(second)
            if (firstPath != null && secondPath != null) {
                if (firstPath == secondPath) return true
                if (firstPath.first != secondPath.first || !firstPath.second.equals(secondPath.second, ignoreCase = true))
                    return false
                // Shared volumes can resolve case variants to the same file.
            }
            fun stat(uri: Uri) = (if (uri.scheme == ContentResolver.SCHEME_FILE) MediaFiles.openLocalFile(uri)
                else context.contentResolver.openFileDescriptor(uri, "r"))?.use {
                Os.fstat(it.fileDescriptor).takeIf { stat -> OsConstants.S_ISREG(stat.st_mode) }
            }
            val a = stat(first) ?: return null
            val b = stat(second) ?: return null
            return if (a.st_dev == b.st_dev) a.st_ino == b.st_ino else null
        }

        private fun relativePath(path: String): String =
            "Music/MediaLab/" + if (path.isEmpty()) "" else "$path/"

        fun info(context: Context, uri: Uri): BatchFile {
            val cursor =
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
            if (cursor == null) {
                requireProvider(context, uri)
                throw FileNotFoundException()
            }
            return cursor.use {
                if (!it.moveToFirst()) throw FileNotFoundException()
                val name = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)).orEmpty()
                require(validName(name)) { context.getString(R.string.batch_invalid_name) }
                val size = it.getColumnIndex(OpenableColumns.SIZE)
                BatchFile(uri, name, size = if (size < 0 || it.isNull(size)) -1 else it.getLong(size))
            }
        }

        suspend fun children(
            context: Context,
            tree: Uri,
            parent: Uri,
        ): List<Pair<BatchFile, Boolean>> {
            val uri =
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree,
                    DocumentsContract.getDocumentId(parent),
                )
            return readChildren(tree) {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        Document.COLUMN_DOCUMENT_ID,
                        Document.COLUMN_DISPLAY_NAME,
                        Document.COLUMN_MIME_TYPE,
                        Document.COLUMN_SIZE,
                    ),
                    null,
                    null,
                    null,
                ) ?: run {
                    requireProvider(context, uri)
                    throw UnsupportedMediaException(R.string.batch_folder_unavailable)
                }
            }
        }

        internal suspend fun readChildren(tree: Uri, query: () -> Cursor): List<Pair<BatchFile, Boolean>> {
            // MiXplorer keeps EXTRA_LOADING set on complete local listings, including empty folders.
            val mixLocalTree =
                tree.authority in setOf("com.mixplorer.doc", "com.mixplorer.silver.doc") &&
                    DocumentsContract.getTreeDocumentId(tree).substringBefore(':', "").let {
                        it == "primary" || it.matches(Regex("[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"))
                    }
            var previous: List<Pair<BatchFile, Boolean>>? = null
            val attempts = 20
            repeat(attempts) { attempt ->
                currentCoroutineContext().ensureActive()
                query().use { cursor ->
                    if (!cursor.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrBlank())
                        throw UnsupportedMediaException(R.string.batch_folder_unavailable)
                    val loading = cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING)
                    if (loading && !mixLocalTree) return@use
                    val result = cursor.children(tree)
                    if (!loading || (result == previous && (result.isNotEmpty() || attempt == attempts - 1)))
                        return result
                    previous = result
                }
                if (attempt < attempts - 1) delay(500)
            }
            throw UnsupportedMediaException(R.string.batch_folder_unavailable)
        }

        private suspend fun Cursor.children(tree: Uri): List<Pair<BatchFile, Boolean>> {
            if (!moveToFirst()) return emptyList()
            val idColumn = getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val nameColumn = getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            val typeColumn = getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
            val sizeColumn = getColumnIndex(Document.COLUMN_SIZE)
            val coroutine = currentCoroutineContext()
            val files = mutableListOf<Pair<BatchFile, Boolean>>()
            do {
                coroutine.ensureActive()
                val id = getString(idColumn)
                val name = getString(nameColumn)
                if (id.isNullOrEmpty() || name == null || !validName(name)) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                val size = if (sizeColumn < 0 || isNull(sizeColumn)) -1 else getLong(sizeColumn)
                files += BatchFile(uri, name, size = size) to (getString(typeColumn) == Document.MIME_TYPE_DIR)
            } while (moveToNext())
            return files.sortedWith(compareBy({ it.first.name.lowercase() }, { it.first.key }))
        }

        private fun requireProvider(context: Context, uri: Uri) {
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                val client = context.contentResolver.acquireUnstableContentProviderClient(uri)
                    ?: throw BatchProviderUnavailable(uri.authority)
                client.close()
            }
        }

        fun openInput(context: Context, uri: Uri): InputStream =
            try {
                if (uri.scheme == ContentResolver.SCHEME_FILE)
                    ParcelFileDescriptor.AutoCloseInputStream(MediaFiles.openLocalFile(uri))
                else context.contentResolver.openInputStream(uri) ?: throw BatchProviderUnavailable(uri.authority)
            } catch (error: FileNotFoundException) {
                requireProvider(context, uri)
                throw error
            } catch (error: IllegalArgumentException) {
                // A live DocumentsProvider may reject a deleted tree document's ID.
                requireProvider(context, uri)
                throw FileNotFoundException(context.getString(R.string.batch_file_unavailable)).apply { initCause(error) }
            }

        suspend fun digest(context: Context, uri: Uri): String =
            digestIfPresent(context, uri) ?: throw IOException(context.getString(R.string.batch_verification_failed))

        suspend fun digestIfPresent(context: Context, uri: Uri): String? {
            val coroutine = currentCoroutineContext()
            val input = try {
                openInput(context, uri)
            } catch (_: FileNotFoundException) {
                return null
            } catch (error: IOException) {
                throw IOException(context.getString(R.string.batch_verification_failed), error)
            }
            return input.use { AudioFingerprint.digest(it) { coroutine.ensureActive() } }
        }

        fun delete(context: Context, uri: Uri) {
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw UnsupportedMediaException(R.string.batch_permission_error)
            val resolver = context.contentResolver
            val deleted = if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(resolver, uri)
                else resolver.delete(uri, null, null) == 1
            check(deleted) { context.getString(R.string.batch_delete_failed) }
            val stillReadable = try {
                openInput(context, uri).use { true }
            } catch (_: FileNotFoundException) { false }
            catch (_: SecurityException) { false } // A deleted document's URI grant can be revoked.
            check(!stillReadable) { context.getString(R.string.batch_delete_failed) }
        }
    }
}
