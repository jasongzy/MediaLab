package com.jasongzy.medialab

import java.io.File
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

    suspend fun clearAll() {
        for (workspace in active.filter { it.cache == cache }) {
            workspace.access.withLock {
                val retained = workspace.workingFiles()
                MediaFiles.clearCache(workspace.work, { it in retained || keepShared(it) })
                MediaFiles.clearCache(workspace.exports)
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
