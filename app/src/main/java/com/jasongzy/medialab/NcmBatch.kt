package com.jasongzy.medialab

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
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
    UNLOCK_FAILED(R.string.batch_failed),
    CLEANUP_FAILED(R.string.batch_failed);

    val failed get() = this == UNLOCK_FAILED || this == CLEANUP_FAILED
}

internal data class BatchOptions(
    val recursive: Boolean = true,
    val copyAudio: Boolean = false,
    val conflict: ConflictPolicy = ConflictPolicy.SKIP,
    val deleteOriginal: Boolean = false,
)

internal data class BatchItem(
    val file: BatchFile,
    val shared: Boolean = false,
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
) {
    suspend fun deleteSource(context: Context) {
        check(
            BatchFiles.digest(context, source.uri) == sourceDigest &&
                BatchFiles.digest(context, output.uri) == outputDigest
        ) {
            context.getString(R.string.batch_source_changed)
        }
        if (sourceDigest == outputDigest)
            BatchFiles.requireDistinct(context, source.uri, output.uri, R.string.batch_same_file, identicalBytes = true)
        currentCoroutineContext().ensureActive()
        BatchFiles.delete(context, source.uri)
    }
}

internal data class BatchState(
    val items: List<BatchItem> = emptyList(),
    val options: BatchOptions = BatchOptions(),
    val inputTree: Uri? = null,
    val outputTree: Uri? = null,
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
                (it.status == BatchStatus.PENDING || it.status.failed)
        }

    val protectedFiles
        get() = items.mapNotNull { item ->
            when {
                item.sourceDeleted -> item.output
                item.file.ncm || (options.copyAudio && item.selected) -> item.file
                else -> null
            }
        }
}

internal object NcmBatch {
    private class ExistingOutput : Exception()

    private val mutable = MutableStateFlow(BatchState())
    val state = mutable.asStateFlow()
    val cacheAccess = Mutex()
    private var action: (suspend (Context) -> Unit)? = null
    private var job: Job? = null

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

    fun output(uri: Uri?) {
        if (!state.value.busy && state.value.outputTree != uri)
            mutable.update {
                it.copy(
                    outputTree = uri,
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
        if (state.value.busy) return
        mutable.update {
            it.copy(
                items = it.items.filter { item -> !item.selected },
                candidates = null,
                completed = 0,
                total = 0,
            )
        }
    }

    fun clear(context: Context) {
        if (!state.value.busy) {
            mutable.value = BatchState()
            releaseNcmGrants(context)
        }
    }

    fun clearError() {
        mutable.update { it.copy(error = null) }
    }

    fun dismissCandidates() {
        mutable.update { it.copy(candidates = null) }
    }

    fun addFiles(context: Context, uris: List<Uri>, sharedNcm: Boolean = false) =
        launch(context, R.string.batch_reading, if (sharedNcm) uris else emptyList()) { app ->
            var unsupported = 0
            val files = mutableListOf<BatchFile>()
            val issues = linkedSetOf<String>()
            val known = knownNcmKeys()
            try {
                for (uri in uris.distinct()) {
                    currentCoroutineContext().ensureActive()
                    val file = readInput(app, known, issues) {
                        val info = BatchFiles.info(app, uri)
                        if (sharedNcm) info.copy(ncm = true) else info
                    } ?: continue
                    if (BatchFiles.supports(file)) files += file else unsupported++
                }
            } finally {
                append(files, shared = sharedNcm)
            }
            if (unsupported > 0)
                issues += app.resources.getQuantityString(R.plurals.batch_unsupported, unsupported, unsupported)
            if (issues.isNotEmpty()) mutable.update { it.copy(error = issues.joinToString("\n")) }
        }

    fun addFolder(context: Context, tree: Uri) =
        launch(context, R.string.batch_scanning) { app ->
            mutable.update { it.copy(inputTree = tree) }
            val recursive = state.value.options.recursive
            val output = state.value.outputTree?.let { BatchFiles.key(BatchFiles.document(it)) }
            val root = BatchFiles.document(tree)
            val directories = ArrayDeque<Pair<Uri, String>>()
            val visited = mutableSetOf<String>()
            val known = knownNcmKeys()
            val issues = linkedSetOf<String>()
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
                    else if (!isDirectory) {
                        val input = readInput(app, known, issues) { file } ?: continue
                        if (BatchFiles.supports(input)) files += input.copy(directory = path)
                    }
                }
                append(files, fromFolder = true)
            }
            if (issues.isNotEmpty()) mutable.update { it.copy(error = issues.joinToString("\n")) }
        }

    private fun knownNcmKeys() = state.value.items.mapNotNullTo(hashSetOf()) {
        if (it.file.ncm && !it.sourceDeleted) it.file.key else null
    }

    private inline fun readInput(
        context: Context,
        known: Set<String>,
        issues: MutableSet<String>,
        read: () -> BatchFile,
    ): BatchFile? =
        try {
            val file = read()
            if (!file.ncm && file.key in known && BatchFiles.openInput(context, file.uri).use(Ncm::matches))
                file.copy(ncm = true)
            else file
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            issues += friendly(context, error)
            null
        }

    private fun append(files: List<BatchFile>, shared: Boolean = false, fromFolder: Boolean = false) {
        if (files.isEmpty()) return
        mutable.update { state ->
            val items = state.items.associateByTo(linkedMapOf()) { it.file.key }
            for (file in files) {
                val old = items[file.key]
                items[file.key] = when {
                    old == null || old.sourceDeleted -> BatchItem(file, shared = shared)
                    else -> {
                        val input = if (fromFolder) file else file.copy(directory = old.file.directory)
                        val changed = input.directory != old.file.directory || input.ncm != old.file.ncm
                        old.copy(
                            file = input,
                            shared = shared,
                            status = if (changed) BatchStatus.PENDING else old.status,
                            detail = if (changed) null else old.detail,
                        )
                    }
                }
            }
            state.copy(items = items.values.toList())
        }
    }

    fun convert(context: Context, failuresOnly: Boolean = false) {
        val snapshot = state.value
        val items =
            if (failuresOnly)
                snapshot.pending.filter { it.status == BatchStatus.UNLOCK_FAILED }
            else snapshot.pending
        if (items.isEmpty()) return
        launch(context, R.string.unlocking, sharedInputs(items)) { app ->
            val output = BatchFiles(app, snapshot.outputTree)
            val protectedFiles = snapshot.protectedFiles.toMutableList()
            process(app, items, BatchStatus.UNLOCK_FAILED) { item, directory ->
                if (!item.file.ncm && !snapshot.options.copyAudio) {
                    update(item.file.key) {
                        it.copy(
                            status = BatchStatus.SKIPPED,
                            detail = app.getString(R.string.batch_ordinary_skipped),
                        )
                    }
                    return@process
                }
                val existing = if (snapshot.options.conflict == ConflictPolicy.SKIP)
                    output.namesIn(item.file.directory) else emptySequence()
                fun skipExisting(name: String) {
                    if (existing.any { it.equals(name, ignoreCase = true) }) throw ExistingOutput()
                }
                if (!item.file.ncm) skipExisting(item.file.name)
                val (decoded, sourceDigest) =
                    prepare(app, item.file, directory) { extension ->
                        skipExisting(Ncm.outputName(item.file.name, extension))
                    }
                val name =
                    if (item.file.ncm) Ncm.outputName(item.file.name, decoded.extension)
                    else item.file.name
                val result =
                    output.write(
                        decoded,
                        item.file.directory,
                        name,
                        snapshot.options.conflict,
                        item.file.uri,
                        protectedFiles,
                    ) { progress ->
                        mutable.update { it.copy(progress = progress) }
                    }
                if (result == null) {
                    update(item.file.key) {
                        it.copy(
                            status = BatchStatus.SKIPPED,
                            detail = app.getString(R.string.batch_exists),
                        )
                    }
                } else {
                    val (saved, outputDigest) = result
                    update(item.file.key) {
                        it.copy(output = saved, outputTree = snapshot.outputTree)
                    }
                    if (snapshot.options.deleteOriginal) {
                        CleanupCandidate(item.file, saved, sourceDigest, outputDigest).deleteSource(app)
                        protectedFiles.remove(item.file)
                        protectedFiles += saved
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
        launch(context, R.string.batch_checking, sharedInputs(items)) { app ->
            val output = BatchFiles(app, snapshot.outputTree)
            val candidates = mutableListOf<CleanupCandidate>()
            val failures = process(app, items, BatchStatus.CLEANUP_FAILED) { item, directory ->
                val (decoded, sourceDigest) =
                    prepare(app, item.file, directory, restoreMetadata = false)
                val candidate = output.matchUnlocked(item, decoded, sourceDigest)
                if (candidate == null) {
                    update(item.file.key) {
                        it.copy(
                            status = BatchStatus.PENDING,
                            output = null,
                            outputTree = null,
                            detail = app.getString(R.string.batch_unmatched),
                        )
                    }
                } else {
                    candidates += candidate
                    val savedTree = if (candidate.output.uri == item.output?.uri) item.outputTree else snapshot.outputTree
                    update(item.file.key) {
                        it.copy(
                            status = if (savedTree == snapshot.outputTree && candidate.output.directory == item.file.directory)
                                BatchStatus.SUCCESS else item.status,
                            output = candidate.output,
                            outputTree = savedTree,
                            detail = app.getString(R.string.batch_matched),
                        )
                    }
                }
            }
            if (candidates.isNotEmpty() || failures == 0)
                mutable.update { it.copy(candidates = candidates) }
        }
    }

    fun deleteCandidates(context: Context) {
        val candidates = state.value.candidates ?: return
        if (candidates.isEmpty()) {
            dismissCandidates()
            return
        }
        val byKey = candidates.associateBy { it.source.key }
        val items = state.value.items.filter { it.file.key in byKey }
        launch(context, R.string.batch_deleting, sharedInputs(items)) { app ->
            process(app, items, BatchStatus.CLEANUP_FAILED) { item, _ ->
                val candidate = byKey.getValue(item.file.key)
                candidate.deleteSource(app)
                update(item.file.key) {
                    it.copy(
                        status = BatchStatus.SUCCESS,
                        sourceDeleted = true,
                        detail = app.getString(R.string.batch_deleted),
                    )
                }
            }
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
            BatchFiles.openInput(context, source.uri).use { stream ->
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
        failureStatus: BatchStatus,
        run: suspend (BatchItem, File) -> Unit,
    ): Int {
        val directory = File(context.cacheDir, "ncm")
        check(directory.mkdirs() || directory.isDirectory)
        mutable.update { it.copy(total = items.size, completed = 0) }
        var failures = 0
        for (item in items) {
            currentCoroutineContext().ensureActive()
            mutable.update { it.copy(current = item.file.path, progress = 0f) }
            update(item.file.key) { it.copy(status = BatchStatus.RUNNING, detail = null) }
            try {
                run(item, directory)
            } catch (cancelled: CancellationException) {
                update(item.file.key) { it.copy(status = item.status, detail = item.detail) }
                throw cancelled
            } catch (_: ExistingOutput) {
                update(item.file.key) {
                    it.copy(
                        status = BatchStatus.SKIPPED,
                        detail = context.getString(R.string.batch_exists),
                    )
                }
            } catch (error: Exception) {
                failures++
                val cause = (error as? BatchOutputException)?.cause ?: error
                update(item.file.key) {
                    it.copy(
                        status = failureStatus,
                        detail = friendly(context, cause),
                        output = (error as? BatchOutputException)?.output ?: it.output,
                        outputTree =
                            if (error is BatchOutputException) state.value.outputTree
                            else it.outputTree,
                    )
                }
                if (cause is BatchProviderUnavailable) throw cause
            } finally {
                directory.listFiles()?.forEach { it.delete() }
            }
            mutable.update { it.copy(completed = it.completed + 1, progress = null) }
        }
        return failures
    }

    private fun update(key: String, transform: (BatchItem) -> BatchItem) {
        mutable.update { state ->
            state.copy(items = state.items.map { if (it.file.key == key) transform(it) else it })
        }
    }

    private fun sharedInputs(items: List<BatchItem>) = items.filter { it.shared }.map { it.file.uri }

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
                candidates = null,
            )
        }
        try {
            val uris = grants.distinct().filter {
                context.checkUriPermission(it, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED
            }
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
            is BatchProviderUnavailable -> {
                val pm = context.packageManager
                val appName =
                    error.authority?.let { pm.resolveContentProvider(it, PackageManager.MATCH_DISABLED_COMPONENTS) }
                        ?.applicationInfo?.loadLabel(pm)
                if (appName.isNullOrBlank()) context.getString(R.string.batch_provider_unavailable)
                else context.getString(R.string.batch_provider_unavailable_named, appName)
            }
            is UnsupportedMediaException -> context.getString(error.messageRes)
            is java.io.FileNotFoundException -> context.getString(R.string.batch_file_unavailable)
            is SecurityException -> context.getString(R.string.batch_permission_error)
            else ->
                error.message?.takeIf { it.isNotBlank() }?.take(240)
                    ?: context.getString(R.string.error_message)
        }
    }
}
