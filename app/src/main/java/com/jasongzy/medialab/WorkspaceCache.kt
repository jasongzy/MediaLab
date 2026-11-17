package com.jasongzy.medialab

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class WorkspaceCache(
    cacheDir: File,
    private val keepShared: (File) -> Boolean = { false },
    private val workingFiles: () -> Set<File>,
) : AutoCloseable {
    private val cache = cacheDir.absoluteFile.normalize()
    val work = File(cache, "work/${UUID.randomUUID()}")
    val exports = File(cache, "exports/${work.name}")
    val access = Mutex()

    init {
        active += this
    }

    suspend fun clearStale() =
        MediaFiles.clearCache(work.parentFile!!, keepShared, ::isActiveDirectory)

    fun share(export: File, type: ImportType): File {
        val directory = File(exports, type.name)
        if (export.parentFile == directory) return export
        require(export.parentFile == work)
        check(directory.mkdirs() || directory.isDirectory)
        return Files.move(export.toPath(), File(directory, export.name).toPath(), StandardCopyOption.ATOMIC_MOVE).toFile()
    }

    fun discard(file: File) {
        if (file.parentFile == work) file.delete()
    }

    suspend fun clearAll() {
        for (workspace in active.filter { it.cache == cache }) {
            workspace.access.withLock {
                val retained = workspace.workingFiles()
                MediaFiles.clearCache(workspace.work, { it in retained || keepShared(it) })
                MediaFiles.clearCache(workspace.exports, retained::contains)
            }
        }
        MediaFiles.clearCache(cache, keepShared, ::isActiveDirectory)
    }

    override fun close() {
        active -= this
    }

    private companion object {
        val active = ConcurrentHashMap.newKeySet<WorkspaceCache>()

        fun isActiveDirectory(directory: File) =
            active.any { directory == it.work || directory == it.exports }
    }
}
