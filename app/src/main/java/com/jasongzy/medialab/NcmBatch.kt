package com.jasongzy.medialab

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class ConflictPolicy(val label: Int) {
    SKIP(R.string.batch_skip),
    RENAME(R.string.batch_rename),
    OVERWRITE(R.string.batch_overwrite),
}

internal enum class BatchStatus(val label: Int) {
    PENDING(R.string.batch_pending),
    RUNNING(R.string.batch_running),
    SUCCESS(R.string.batch_success),
    SKIPPED(R.string.batch_skipped),
    FAILED(R.string.batch_failed),
}

internal data class BatchOptions(
    val recursive: Boolean = true,
    val copyAudio: Boolean = false,
    val conflict: ConflictPolicy = ConflictPolicy.SKIP,
    val deleteOriginal: Boolean = false,
)

internal data class BatchItem(
    val file: BatchFile,
    val selected: Boolean = true,
    val status: BatchStatus = BatchStatus.PENDING,
    val output: BatchFile? = null,
    val outputTree: Uri? = null,
    val detail: String? = null,
    val sourceDeleted: Boolean = false,
)

internal data class CleanupCandidate(
    val source: BatchFile,
    val output: BatchFile,
    val sourceDigest: String,
    val outputDigest: String,
)

internal data class BatchState(
    val items: List<BatchItem> = emptyList(),
    val options: BatchOptions = BatchOptions(),
    val outputTree: Uri? = null,
    val outputName: String = "Music/MediaLab",
    val busy: Boolean = false,
    val cancelling: Boolean = false,
    val stage: Int = R.string.unlock,
    val current: String = "",
    val progress: Float? = null,
    val completed: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    val candidates: List<CleanupCandidate>? = null,
) {
    val pending
        get() = items.filter {
            it.selected &&
                !it.sourceDeleted &&
                it.status in setOf(BatchStatus.PENDING, BatchStatus.FAILED)
        }
}

internal object NcmBatch {
    private class ExistingOutput : Exception()

    private val mutable = MutableStateFlow(BatchState())
    val state = mutable.asStateFlow()
    val cacheAccess = Mutex()
    private var action: (suspend (Context) -> Unit)? = null
    private var job: Job? = null
    private val sharedUris = mutableSetOf<Uri>()

    fun configure(options: BatchOptions) {
        if (!state.value.busy)
            mutable.update {
                it.copy(
                    options = options,
                    candidates = null,
                    items =
                        it.items.map { item ->
                            if (item.status == BatchStatus.SKIPPED)
                                item.copy(status = BatchStatus.PENDING, detail = null)
                            else item
                        },
                )
            }
    }

    fun output(context: Context, uri: Uri?) =
        launch(context, R.string.batch_reading) { app ->
            val name =
                if (uri == null) "Music/MediaLab"
                else BatchFiles.info(app, BatchFiles.document(uri)).name
            mutable.update {
                it.copy(
                    outputTree = uri,
                    outputName = name,
                    candidates = null,
                    items =
                        it.items.map { item ->
                            if (!item.sourceDeleted)
                                item.copy(status = BatchStatus.PENDING, detail = null)
                            else item
                        },
                )
            }
        }

    fun select(key: String?, selected: Boolean) {
        if (!state.value.busy)
            mutable.update {
                it.copy(
                    items =
                        it.items.map { item ->
                            if (key == null || item.file.key == key) item.copy(selected = selected)
                            else item
                        },
                    candidates = null,
                )
            }
    }

    fun removeSelected() {
        if (!state.value.busy)
            mutable.update {
                it.copy(
                    items = it.items.filter { item -> !item.selected },
                    candidates = null,
                    completed = 0,
                    total = 0,
                )
            }
    }

    fun clear() {
        if (!state.value.busy) {
            mutable.value = BatchState()
            sharedUris.clear()
        }
    }

    fun clearError() {
        mutable.update { it.copy(error = null) }
    }

    fun dismissCandidates() {
        mutable.update { it.copy(candidates = null) }
    }

    fun addFiles(context: Context, uris: List<Uri>, shared: Boolean = false) =
        launch(context, R.string.batch_reading, if (shared) uris else emptyList()) { app ->
            if (shared) sharedUris.addAll(uris)
            var unsupported = 0
            for (uri in uris.distinct()) {
                currentCoroutineContext().ensureActive()
                val file = BatchFiles.info(app, uri)
                if (BatchFiles.supported(file.name)) append(listOf(file)) else unsupported++
            }
            if (unsupported > 0)
                mutable.update {
                    it.copy(
                        error =
                            app.resources.getQuantityString(
                                R.plurals.batch_unsupported,
                                unsupported,
                                unsupported,
                            )
                    )
                }
        }

    fun addFolder(context: Context, tree: Uri) =
        launch(context, R.string.batch_scanning) { app ->
            val recursive = state.value.options.recursive
            val output = state.value.outputTree?.let { BatchFiles.key(BatchFiles.document(it)) }
            val root = BatchFiles.document(tree)
            val directories = ArrayDeque<Pair<Uri, String>>()
            val visited = mutableSetOf<String>()
            directories.add(root to "")
            while (directories.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val (directory, path) = directories.removeFirst()
                val key = BatchFiles.key(directory)
                if (!visited.add(key) || (path.isNotEmpty() && key == output)) continue
                mutable.update { it.copy(current = path) }
                val files = mutableListOf<BatchFile>()
                for ((file, isDirectory) in BatchFiles.children(app, tree, directory)) {
                    if (isDirectory && recursive)
                        directories.add(
                            file.uri to if (path.isEmpty()) file.name else "$path/${file.name}"
                        )
                    else if (!isDirectory && BatchFiles.supported(file.name))
                        files += file.copy(directory = path)
                }
                append(files)
            }
        }

    private fun append(files: List<BatchFile>) {
        mutable.update { state ->
            val keys = state.items.map { it.file.key }.toMutableSet()
            state.copy(
                items = state.items + files.filter { keys.add(it.key) }.map { BatchItem(it) },
                candidates = null,
            )
        }
    }

    fun convert(context: Context, failuresOnly: Boolean = false) {
        val snapshot = state.value
        val items =
            if (failuresOnly)
                snapshot.items.filter { it.status == BatchStatus.FAILED && !it.sourceDeleted }
            else snapshot.pending
        if (items.isEmpty()) return
        launch(context, R.string.unlocking) { app ->
            val output = BatchFiles(app, snapshot.outputTree)
            process(app, items) { item, directory ->
                if (!item.file.ncm && !snapshot.options.copyAudio) {
                    update(item.file.key) {
                        it.copy(
                            status = BatchStatus.SKIPPED,
                            detail = app.getString(R.string.batch_ordinary_skipped),
                        )
                    }
                    return@process
                }
                val existing =
                    if (snapshot.options.conflict == ConflictPolicy.SKIP) {
                        val names =
                            if (item.file.ncm)
                                listOf("mp3", "flac").map {
                                    item.file.name.substringBeforeLast('.') + "." + it
                                }
                            else listOf(item.file.name)
                        names.filter { output.find(item.file.directory, it) != null }.toSet()
                    } else emptySet()
                if (!item.file.ncm && existing.isNotEmpty()) throw ExistingOutput()
                val (decoded, sourceDigest) =
                    prepare(app, item.file, directory) { extension ->
                        if (item.file.name.substringBeforeLast('.') + "." + extension in existing)
                            throw ExistingOutput()
                    }
                val name =
                    if (item.file.ncm)
                        item.file.name.substringBeforeLast('.') + "." + decoded.extension
                    else item.file.name
                val saved =
                    output.write(
                        decoded,
                        item.file.directory,
                        name,
                        snapshot.options.conflict,
                        item.file.uri,
                    ) { progress ->
                        mutable.update { it.copy(progress = progress) }
                    }
                if (saved == null) {
                    update(item.file.key) {
                        it.copy(
                            status = BatchStatus.SKIPPED,
                            detail = app.getString(R.string.batch_exists),
                        )
                    }
                } else {
                    update(item.file.key) {
                        it.copy(output = saved, outputTree = snapshot.outputTree)
                    }
                    if (snapshot.options.deleteOriginal) {
                        check(!BatchFiles.sameFile(app, item.file.uri, saved.uri)) {
                            app.getString(R.string.batch_same_file)
                        }
                        check(BatchFiles.digest(app, item.file.uri) == sourceDigest) {
                            app.getString(R.string.batch_source_changed)
                        }
                        BatchFiles.delete(app, item.file.uri)
                    }
                    update(item.file.key) {
                        it.copy(
                            status = BatchStatus.SUCCESS,
                            sourceDeleted = snapshot.options.deleteOriginal,
                        )
                    }
                }
            }
        }
    }

    fun inspectCleanup(context: Context) {
        val snapshot = state.value
        val items = snapshot.items.filter { it.selected && it.file.ncm && !it.sourceDeleted }
        if (items.isEmpty()) return
        launch(context, R.string.batch_checking) { app ->
            val output = BatchFiles(app, snapshot.outputTree)
            val candidates = mutableListOf<CleanupCandidate>()
            process(app, items) { item, directory ->
                val (decoded, sourceDigest) =
                    prepare(app, item.file, directory, restoreMetadata = false)
                val expected = item.file.name.substringBeforeLast('.') + "." + decoded.extension
                val saved =
                    item.output?.takeIf { item.outputTree == snapshot.outputTree }
                        ?: output.find(item.file.directory, expected)
                if (saved != null && !BatchFiles.sameFile(app, saved.uri, item.file.uri)) {
                    val copy = File(directory, "existing.${decoded.extension}")
                    checkNotNull(app.contentResolver.openInputStream(saved.uri)).use { input ->
                        copy.outputStream().use { MediaFiles.copy(input, it) }
                    }
                    val coroutine = currentCoroutineContext()
                    if (
                        AudioFingerprint.payload(decoded, decoded.extension) {
                            coroutine.ensureActive()
                        } ==
                            AudioFingerprint.payload(copy, decoded.extension) {
                                coroutine.ensureActive()
                            }
                    ) {
                        val digest =
                            copy.inputStream().use {
                                AudioFingerprint.digest(it) { coroutine.ensureActive() }
                            }
                        candidates += CleanupCandidate(item.file, saved, sourceDigest, digest)
                        update(item.file.key) {
                            it.copy(
                                status = BatchStatus.SUCCESS,
                                output = saved,
                                outputTree = snapshot.outputTree,
                                detail = app.getString(R.string.batch_matched),
                            )
                        }
                        return@process
                    }
                }
                update(item.file.key) {
                    it.copy(
                        status = BatchStatus.PENDING,
                        detail = app.getString(R.string.batch_unmatched),
                    )
                }
            }
            mutable.update { it.copy(candidates = candidates) }
        }
    }

    fun deleteCandidates(context: Context) {
        val candidates = state.value.candidates ?: return
        if (candidates.isEmpty()) {
            dismissCandidates()
            return
        }
        launch(context, R.string.batch_deleting) { app ->
            val byKey = candidates.associateBy { it.source.key }
            process(app, state.value.items.filter { it.file.key in byKey }) { item, _ ->
                val candidate = byKey.getValue(item.file.key)
                check(!BatchFiles.sameFile(app, candidate.source.uri, candidate.output.uri)) {
                    app.getString(R.string.batch_same_file)
                }
                check(
                    BatchFiles.digest(app, candidate.source.uri) == candidate.sourceDigest &&
                        BatchFiles.digest(app, candidate.output.uri) == candidate.outputDigest
                ) {
                    app.getString(R.string.batch_source_changed)
                }
                BatchFiles.delete(app, candidate.source.uri)
                update(item.file.key) {
                    it.copy(
                        status = BatchStatus.SUCCESS,
                        sourceDeleted = true,
                        detail = app.getString(R.string.batch_deleted),
                    )
                }
            }
            dismissCandidates()
        }
    }

    private suspend fun prepare(
        context: Context,
        source: BatchFile,
        directory: File,
        restoreMetadata: Boolean = true,
        onFormat: (String) -> Unit = {},
    ): Pair<File, String> {
        val hash = MessageDigest.getInstance("SHA-256")
        val output =
            checkNotNull(context.contentResolver.openInputStream(source.uri)).use { stream ->
                DigestInputStream(stream, hash).use { input ->
                    if (source.ncm)
                        try {
                            AudioFiles.unlock(
                                input,
                                directory,
                                source.size,
                                restoreMetadata,
                                onFormat,
                            ) { progress ->
                                mutable.update {
                                    it.copy(
                                        progress =
                                            progress?.let { value -> value.coerceIn(0f, 1f) * 0.6f }
                                    )
                                }
                            }
                        } catch (error: Exception) {
                            if (
                                error is IllegalArgumentException ||
                                    error is java.io.EOFException ||
                                    error is java.security.GeneralSecurityException ||
                                    error is org.json.JSONException
                            )
                                throw UnsupportedMediaException(R.string.batch_invalid_ncm)
                            throw error
                        }
                    else
                        File(directory, "copy.${source.name.substringAfterLast('.').lowercase()}")
                            .also { file ->
                                file.outputStream().use { output ->
                                    MediaFiles.copy(input, output) { done ->
                                        if (source.size > 0)
                                            mutable.update {
                                                it.copy(
                                                    progress =
                                                        (done.toFloat() / source.size).coerceIn(
                                                            0f,
                                                            1f,
                                                        ) * 0.6f
                                                )
                                            }
                                    }
                                }
                            }
                }
            }
        check(output.length() > 0) { context.getString(R.string.batch_empty_file) }
        if (!source.ncm) AudioFiles.validate(output)
        mutable.update { it.copy(progress = 0.6f) }
        return output to AudioFingerprint.hex(hash.digest())
    }

    private suspend fun process(
        context: Context,
        items: List<BatchItem>,
        run: suspend (BatchItem, File) -> Unit,
    ) {
        val directory = File(context.cacheDir, "ncm")
        check(directory.mkdirs() || directory.isDirectory)
        mutable.update { it.copy(total = items.size, completed = 0, candidates = null) }
        for (item in items) {
            currentCoroutineContext().ensureActive()
            mutable.update { it.copy(current = item.file.path, progress = 0f) }
            update(item.file.key) { it.copy(status = BatchStatus.RUNNING, detail = null) }
            try {
                run(item, directory)
            } catch (cancelled: CancellationException) {
                update(item.file.key) { it.copy(status = BatchStatus.PENDING) }
                throw cancelled
            } catch (_: ExistingOutput) {
                update(item.file.key) {
                    it.copy(
                        status = BatchStatus.SKIPPED,
                        detail = context.getString(R.string.batch_exists),
                    )
                }
            } catch (error: Exception) {
                update(item.file.key) {
                    it.copy(
                        status = BatchStatus.FAILED,
                        detail =
                            friendly(context, (error as? BatchOutputException)?.cause ?: error),
                        output = (error as? BatchOutputException)?.output ?: it.output,
                        outputTree =
                            if (error is BatchOutputException) state.value.outputTree
                            else it.outputTree,
                    )
                }
            } finally {
                directory.listFiles()?.forEach { it.delete() }
            }
            mutable.update { it.copy(completed = it.completed + 1, progress = null) }
        }
    }

    private fun update(key: String, transform: (BatchItem) -> BatchItem) {
        mutable.update { state ->
            state.copy(items = state.items.map { if (it.file.key == key) transform(it) else it })
        }
    }

    @Synchronized
    private fun launch(
        context: Context,
        stage: Int,
        grants: List<Uri> = emptyList(),
        action: suspend (Context) -> Unit,
    ) {
        if (state.value.busy) {
            mutable.update { it.copy(error = context.getString(R.string.batch_busy)) }
            return
        }
        this.action = action
        mutable.update {
            it.copy(
                busy = true,
                cancelling = false,
                stage = stage,
                current = "",
                completed = 0,
                total = 0,
                progress = null,
                error = null,
            )
        }
        try {
            val uris = (grants + sharedUris).distinct()
            val intent = Intent(context, NcmService::class.java)
            if (uris.isNotEmpty()) {
                intent.clipData =
                    ClipData.newRawUri("", uris.first()).apply {
                        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                    }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startForegroundService(intent)
        } catch (error: Exception) {
            this.action = null
            mutable.update { it.copy(busy = false, error = friendly(context, error)) }
        }
    }

    suspend fun execute(context: Context) {
        job = currentCoroutineContext().job
        try {
            cacheAccess.withLock {
                if (state.value.cancelling) return
                val directory = File(context.cacheDir, "ncm")
                directory.listFiles()?.forEach { it.delete() }
                action?.invoke(context)
            }
        } catch (_: CancellationException) {} catch (error: Exception) {
            mutable.update { it.copy(error = friendly(context, error)) }
        } finally {
            action = null
            job = null
            mutable.update {
                it.copy(busy = false, cancelling = false, current = "", progress = null)
            }
        }
    }

    fun cancel() {
        mutable.update { it.copy(cancelling = true) }
        job?.cancel()
    }

    fun fail(context: Context, error: Exception) {
        action = null
        mutable.update { it.copy(busy = false, error = friendly(context, error)) }
    }

    private fun friendly(context: Context, error: Exception): String {
        if (BuildConfig.DEBUG) Log.e("NcmBatch", "Batch operation failed", error)
        return when (error) {
            is UnsupportedMediaException -> context.getString(error.messageRes)
            is SecurityException -> context.getString(R.string.batch_permission_error)
            else ->
                error.message?.takeIf { it.isNotBlank() }?.take(240)
                    ?: context.getString(R.string.error_message)
        }
    }
}
