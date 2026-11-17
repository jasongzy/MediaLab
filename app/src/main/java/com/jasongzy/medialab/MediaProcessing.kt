package com.jasongzy.medialab

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.MediaInformation
import com.arthenica.ffmpegkit.MediaInformationSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Session
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal val MediaInformation.videoTrack get() = streams.firstOrNull {
    it.type == "video" && it.allProperties.optJSONObject("disposition")?.optInt("attached_pic") != 1
}

internal fun MediaInformation.container(): String {
    val demuxer = format.orEmpty().substringBefore(',')
    if (demuxer != "mov") return demuxer
    val brand = tags?.optString("major_brand").orEmpty().trim()
    return when {
        brand == "qt" -> "mov"
        brand == "M4A" -> "m4a"
        brand.startsWith("3g2") -> "3g2"
        brand.startsWith("3g") -> "3gp"
        else -> "mp4"
    }
}

internal object MediaProcessing {
    private val access = Mutex()

    init {
        FFmpegKitConfig.setSessionHistorySize(1)
    }

    suspend fun inspect(file: File, formats: String? = null): MediaInformation? = access.withLock {
        val completed = CompletableDeferred<MediaInformationSession>()
        val arguments = listOf("-v", "error", "-protocol_whitelist", "file,pipe") +
            (formats?.let { listOf("-format_whitelist", it) } ?: emptyList()) +
            listOf("-show_format", "-show_streams", "-of", "json", file.path)
        val session = MediaInformationSession.create(arguments.toTypedArray(), { completed.complete(it) }, {})
        FFmpegKitConfig.asyncGetMediaInformationExecute(session, 5000)
        completed.awaitSession(session).mediaInformation
    }

    suspend fun execute(arguments: List<String>, progress: (Long) -> Unit = {}) = access.withLock {
        val completed = CompletableDeferred<FFmpegSession>()
        val session =
            FFmpegKit.executeWithArgumentsAsync(
                (listOf(
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-stats_period",
                        "0.1",
                        "-nostdin",
                        "-y",
                        "-filter_threads",
                        "2",
                        "-filter_complex_threads",
                        "2",
                    ) + arguments)
                    .toTypedArray(),
                { completed.complete(it) },
                {},
                { progress(it.time.toLong()) },
            )
        val result = completed.awaitSession(session)
        check(ReturnCode.isSuccess(result.returnCode)) {
            "FFmpeg ${result.returnCode}: ${result.allLogsAsString.takeLast(6000)}"
        }
    }

    private suspend fun <T : Session> CompletableDeferred<T>.awaitSession(session: T): T = try {
        await().also { currentCoroutineContext().ensureActive() }
    } catch (cancelled: CancellationException) {
        FFmpegKit.cancel(session.sessionId)
        withContext(NonCancellable) { await() }
        throw cancelled
    }
}
