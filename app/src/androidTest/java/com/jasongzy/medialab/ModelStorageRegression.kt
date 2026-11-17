package com.jasongzy.medialab

import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Bundle
import android.system.Os
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CancellationException

internal object ModelStorageRegression {
    suspend fun run(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val name = "regression-models-${UUID.randomUUID()}"
        val files = File(context.filesDir, name).apply { mkdirs() }
        val cache = File(context.cacheDir, name).apply { mkdirs() }
        val isolated =
            object : ContextWrapper(context) {
                override fun getFilesDir() = files
                override fun getCacheDir() = cache
            }
        try {
            val engine = BackgroundEngine(isolated)
            val pixels = IntArray(ImageMath.SIZE * ImageMath.SIZE) { i ->
                val x = i % ImageMath.SIZE
                val y = i / ImageMath.SIZE
                ((x % 256) shl 16) or ((y % 256) shl 8) or ((x + y) % 256)
            }
            for (choice in BackgroundModel.entries) {
                val input = ImageMath.normalize(pixels, choice)
                val cold = try {
                    engine.infer(choice, input) {}
                } catch (error: UnsupportedMediaException) {
                    val reason = when {
                        !choice.supportsRuntime -> R.string.model_arm64_required
                        choice == BackgroundModel.RMBG20 -> R.string.model_gpu_unavailable
                        else -> throw error
                    }
                    check(error.messageRes == reason && files.walkTopDown().none { it.extension == "part" })
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString("stream", "$choice: ${context.getString(reason)}\n")
                    })
                    continue
                }
                val model = File(files, "models").walkTopDown().maxDepth(2).single { it.name == choice.file }
                val inode = Os.stat(model.path).st_ino
                val compiled = File(model.parentFile, "compiled").walkTopDown()
                    .filter { it.isFile && (it.extension == "tflite" || it.name == "opencl.bin") }
                    .toList()
                val disposable = File(cache, "temporary").apply { writeText("temporary") }
                MediaFiles.clearCache(cache)
                check(!disposable.exists() && compiled.all { it.isFile })
                val warm = engine.infer(choice, input) {}
                check(Os.stat(model.path).st_ino == inode)
                check(cold.indices.all { abs(cold[it] - warm[it]) < 0.0001f })
                if (choice == BackgroundModel.RMBG20) {
                    try {
                        engine.infer(choice, input) { if (it == R.string.running_model) throw CancellationException() }
                        error("Expected cancellation")
                    } catch (_: CancellationException) {}
                    check(model.parentFile!!.walkTopDown().none { it.extension == "part" })
                } else {
                    val cpu = cpuReference(model, input)
                    check(cpu.size == warm.size)
                    val mae = warm.indices.sumOf { abs(warm[it] - cpu[it]).toDouble() } / warm.size
                    check(mae < 0.001) { "Mask MAE: $mae" }
                }
            }
            check(files.walkTopDown().filter { it.isFile }.all {
                Os.stat(it.path).st_gid == Os.stat(files.path).st_gid
            })
        } finally {
            check(cache.deleteRecursively())
            check(files.deleteRecursively())
        }
    }

    private fun cpuReference(path: File, input: FloatArray): FloatArray {
        val options = CompiledModel.Options(Accelerator.CPU).apply {
            cpuOptions = CompiledModel.CpuOptions(numThreads = 4)
        }
        CompiledModel.create(path.path, options).use { model ->
            val inputs = model.createInputBuffers()
            try {
                val outputs = model.createOutputBuffers()
                try {
                    inputs.single().writeFloat(input)
                    model.run(inputs, outputs)
                    return outputs.single().readFloat()
                } finally {
                    outputs.forEach { it.close() }
                }
            } finally {
                inputs.forEach { it.close() }
            }
        }
    }
}
