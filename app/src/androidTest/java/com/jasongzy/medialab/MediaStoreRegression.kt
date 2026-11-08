package com.jasongzy.medialab

import android.content.ContentValues
import android.content.Context
import android.content.res.AssetManager
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

internal class MediaStoreRegression(private val app: Context, private val fixtures: AssetManager) {
    suspend fun directoryListing() {
        val columns = arrayOf(Document.COLUMN_MIME_TYPE, Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME)
        for (authority in listOf("test.files", "com.mixplorer.silver.doc")) {
            val tree = DocumentsContract.buildTreeDocumentUri(authority, "ABCD-1234:Music")
            var reads = 0
            val entries = BatchFiles.readChildren(tree) {
                MatrixCursor(columns).apply {
                    val read = ++reads
                    check(read <= 4)
                    extras = Bundle().apply {
                        putBoolean(DocumentsContract.EXTRA_LOADING, read < 3 || authority != "test.files")
                    }
                    addRow(arrayOf(Document.MIME_TYPE_DIR, "parent", ".."))
                    if (read >= 2 || authority == "test.files") addRow(arrayOf("audio/mpeg", "one", "first.mp3"))
                    if (read >= 3) addRow(arrayOf("application/octet-stream", "two", "second.ncm"))
                }
            }
            check(entries.all { !it.second })
            check(entries.map { it.first.name to DocumentsContract.getDocumentId(it.first.uri) } ==
                listOf("first.mp3" to "one", "second.ncm" to "two"))
        }
    }

    private fun fixture(extension: String = "wav"): File =
        File(app.cacheDir, "${UUID.randomUUID()}.$extension").apply {
            if (extension != "wav") {
                fixtures.open("silence.$extension").use { input ->
                    outputStream().use(input::copyTo)
                }
            } else
                writeBytes(
                    ByteBuffer.allocate(1644)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .apply {
                            put("RIFF".toByteArray()).putInt(1636).put("WAVEfmt ".toByteArray())
                            putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000)
                            putShort(2).putShort(16).put("data".toByteArray()).putInt(1600)
                        }
                        .array()
                )
        }

    suspend fun publication() {
        val resolver = app.contentResolver
        val directory = "MediaLab-test-${UUID.randomUUID()}"
        val relative = "Music/MediaLab/$directory/"
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val source = fixture()
        val original = source.readBytes()
        val replacement = original.copyOf().apply { this[lastIndex] = 7 }
        var outputDirectory: File? = null
        fun verify(uri: Uri, bytes: ByteArray) {
            checkNotNull(resolver.openInputStream(uri)).use {
                check(it.readBytes().contentEquals(bytes))
            }
            checkNotNull(
                    resolver.query(
                        uri,
                        arrayOf(MediaStore.MediaColumns.IS_PENDING),
                        null,
                        null,
                        null,
                    )
                )
                .use {
                    check(it.moveToFirst() && it.getInt(0) == 0)
                }
        }
        try {
            for (policy in ConflictPolicy.entries) {
                val name = "${policy.name.lowercase()}.wav"
                val stale =
                    checkNotNull(
                        resolver.insert(
                            collection,
                            ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                                put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                                put(MediaStore.MediaColumns.IS_PENDING, 0)
                            },
                        )
                    )
                checkNotNull(
                        resolver.query(
                            stale,
                            arrayOf(MediaStore.MediaColumns.DATA),
                            null,
                            null,
                            null,
                        )
                    )
                    .use {
                        check(it.moveToFirst())
                        val file = File(it.getString(0))
                        outputDirectory = file.parentFile
                        check(!MediaFiles.existsOnDisk(file.path)) {
                            "Fixture must retain only the index row"
                        }
                    }
                val output = BatchFiles(app, null)
                source.writeBytes(original)
                val (saved, savedDigest) =
                    checkNotNull(
                        output.write(source, directory, name, policy, Uri.fromFile(source)) {}
                    )
                check(saved.name == name)
                verify(saved.uri, original)
                if (policy == ConflictPolicy.OVERWRITE) {
                    val alias =
                        MediaStore.Files.getContentUri(
                            MediaStore.VOLUME_EXTERNAL_PRIMARY,
                            saved.uri.lastPathSegment!!.toLong(),
                        )
                    val document =
                        DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents",
                            "primary:$relative$name",
                        )
                    check(BatchFiles.key(alias) != saved.key)
                    val failure = runCatching {
                        output.write(source, directory, name, policy, alias) {}
                    }
                        .exceptionOrNull()
                    check(
                        failure is IllegalStateException &&
                            failure.message == app.getString(R.string.batch_same_file)
                    ) {
                        "An alternate URI must not overwrite its own source: $failure"
                    }
                    val otherInput = BatchItem(BatchFile(document, name, ncm = true), selected = false)
                    val batch = BatchState(items = listOf(otherInput))
                    val protected = runCatching {
                        output.write(source, directory, name, policy, Uri.fromFile(source), batch.protectedFiles) {}
                    }.exceptionOrNull()
                    check(protected?.message == app.getString(R.string.batch_protected_output))
                    val caseAlias = DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents", "primary:$relative${name.uppercase()}",
                    )
                    check(runCatching {
                        output.write(source, directory, name, policy, Uri.fromFile(source), listOf(BatchFile(caseAlias, name))) {}
                    }.isFailure)
                    verify(saved.uri, original)
                }
                source.writeBytes(replacement)
                val again = output.write(source, directory, name, policy, Uri.fromFile(source)) {}
                when (policy) {
                    ConflictPolicy.SKIP -> {
                        check(again == null)
                        verify(saved.uri, original)
                    }
                    ConflictPolicy.RENAME -> {
                        val (renamed, digest) = checkNotNull(again)
                        check(renamed.name == "rename (1).wav")
                        verify(renamed.uri, replacement)
                        val candidate = CleanupCandidate(saved, renamed, savedDigest, digest)
                        check(candidate.complete(app, deleteOriginal = false))
                        verify(saved.uri, original)
                        BatchFiles.delete(app, renamed.uri)
                        val failure = runCatching { candidate.deleteSource(app) }.exceptionOrNull()
                        check(failure?.message == app.getString(R.string.batch_verification_failed))
                        verify(saved.uri, original)
                        val decoded = fixture("mp3")
                        val item = BatchItem(BatchFile(saved.uri, "song.ncm", directory), output = renamed)
                        val (current, hash) = checkNotNull(
                            output.write(decoded, directory, "song.ncm.mp3", policy, item.file.uri) {}
                        )
                        val offline = Uri.parse("content://test.offline/tree/old")
                        val previous = item.copy(
                            outputTree = offline,
                            output = BatchFile(DocumentsContract.buildDocumentUriUsingTree(offline, "song"), current.name),
                        )
                        val otherDirectory = item.copy(output = BatchFile(Uri.fromFile(decoded), current.name, "nested"))
                        for (entry in listOf(item, previous, otherDirectory)) {
                            check(output.matchUnlocked(entry, decoded, savedDigest) ==
                                CleanupCandidate(item.file, current, savedDigest, hash))
                        }
                        val fresh = item.copy(output = null)
                        val (numbered, numberedHash) = checkNotNull(
                            output.write(decoded, directory, current.name, policy, item.file.uri) {}
                        )
                        for (file in listOf(current, numbered)) {
                            checkNotNull(resolver.openOutputStream(file.uri, "wt")).use { it.write(original) }
                            val found = BatchFiles(app, null).matchUnlocked(fresh, decoded, savedDigest)
                            if (file == current) check(found?.output?.uri == numbered.uri &&
                                found.outputDigest == numberedHash) { "Expected ${numbered.uri}, found $found" }
                            else check(found == null) { "Matched an unrelated file: $found" }
                        }
                    }
                    ConflictPolicy.OVERWRITE -> {
                        val replaced = checkNotNull(again).first
                        check(replaced.name == name)
                        verify(replaced.uri, replacement)
                    }
                }
            }
        } finally {
            source.delete()
            val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            checkNotNull(
                    resolver.query(
                        files,
                        arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                        arrayOf(relative),
                        null,
                    )
                )
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        resolver.delete(
                            Uri.withAppendedPath(files, cursor.getLong(0).toString()),
                            null,
                            null,
                        )
                    }
                }
            outputDirectory?.delete()
        }
    }

    suspend fun artwork() {
        for ((extension, format, mime) in
            listOf(
                Triple("wav", Bitmap.CompressFormat.PNG, "image/png"),
                Triple("mp3", Bitmap.CompressFormat.JPEG, "image/jpeg"),
                Triple("flac", Bitmap.CompressFormat.WEBP_LOSSLESS, "image/webp"),
            )) verifyArtwork(fixture(extension), format, mime)
    }

    private suspend fun verifyArtwork(source: File, format: Bitmap.CompressFormat, mime: String) {
        val original = source.readBytes()
        val document = AudioFiles.read(source, source.name)
        check(document.properties != null)
        check(document.technical.any { it.first == "mime" })
        check(document.technical.none { it.first in listOf("durationUs", "bitrate", "sample-rate", "channel-count") })
        val propertyName = if (source.extension == "flac") "CUSTOM" else "说明🧪"
        val tags =
            document.tags +
                mapOf(
                    "TITLE" to listOf("中文标题 café e\u0301 🧪 𠮷"),
                    propertyName to listOf("自定义字段 🎵"),
                )
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        try {
            val data =
                ByteArrayOutputStream().use {
                    check(bitmap.compress(format, 100, it))
                    it.toByteArray()
                }
            check(AudioFiles.pictureMime(data) == mime)
            val picture = Picture(data, "中文封面 café e\u0301 🧪 𠮷", "Back Cover", mime)
            val edited = AudioFiles.save(app.cacheDir, document, tags, listOf(picture))
            try {
                val read = AudioFiles.read(edited, source.name)
                val cover = read.pictures.single()
                check(read.tags["TITLE"] == tags["TITLE"])
                check(
                    cover.data.contentEquals(data) &&
                        cover.description == picture.description &&
                        cover.pictureType == picture.pictureType &&
                        cover.mimeType == mime
                )
                verifyDescriptions(edited, tags, propertyName)
                if (format == Bitmap.CompressFormat.PNG) {
                    val failure = runCatching {
                        AudioFiles.save(
                            app.cacheDir,
                            document,
                            tags,
                            listOf(picture.copy(pictureType = "invalid cover type")),
                        )
                    }
                        .exceptionOrNull()
                    check(failure is UnsupportedMediaException) {
                        "Unsupported artwork edits must be reported"
                    }
                }
            } finally {
                check(edited.delete())
            }
            if (format == Bitmap.CompressFormat.PNG)
                check(
                    runCatching { AudioFiles.pictureMime(byteArrayOf(1, 2, 3)) }.exceptionOrNull()
                        is UnsupportedMediaException
                )
            check(source.readBytes().contentEquals(original))
        } finally {
            bitmap.recycle()
            source.delete()
        }
    }

    private suspend fun verifyDescriptions(
        existing: File,
        tags: Map<String, List<String>>,
        propertyName: String,
    ) {
        val imported = AudioFiles.read(existing, existing.name)
        for (description in listOf(imported.pictures.single().description, "", "新的中文说明 🎵 𠮷")) {
            val edited =
                AudioFiles.save(
                    app.cacheDir,
                    imported,
                    tags + ("TITLE" to listOf("更新标题 🎵")),
                    imported.pictures.map { it.copy(description = description) },
                )
            try {
                val read = AudioFiles.read(edited, edited.name)
                check(read.tags["TITLE"] == listOf("更新标题 🎵"))
                check(read.pictures.single().description == description)
                val fd =
                    ParcelFileDescriptor.open(edited, ParcelFileDescriptor.MODE_READ_ONLY)
                        .detachFd()
                check(
                    TagLib.getMetadataPropertyValues(fd, propertyName)?.toList() ==
                        tags[propertyName]
                )
            } finally {
                check(edited.delete())
            }
        }
    }
}
