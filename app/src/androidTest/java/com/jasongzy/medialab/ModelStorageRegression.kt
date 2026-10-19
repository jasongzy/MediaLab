package com.jasongzy.medialab

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.system.Os
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal object ModelStorageRegression {
    suspend fun run(context: Context) {
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
            val models = coroutineScope {
                List(2) { async(Dispatchers.IO) { BackgroundEngine(isolated).prepare() } }.awaitAll()
            }
            val model = models.first()
            check(models.all { it == model } && !File(model.parentFile, "model.part").exists())
            val inode = Os.stat(model.path).st_ino
            check(engine.prepare() == model && Os.stat(model.path).st_ino == inode)
            val input = ImageMath.normalize(IntArray(ImageMath.SIZE * ImageMath.SIZE) { i ->
                val x = i % ImageMath.SIZE
                val y = i / ImageMath.SIZE
                ((x % 256) shl 16) or ((y % 256) shl 8) or ((x + y) % 256)
            })
            val coldStart = SystemClock.elapsedRealtime()
            val cold = engine.infer(input) {}
            val coldMs = SystemClock.elapsedRealtime() - coldStart
            val compiled = File(model.parentFile, "compiled").walkTopDown()
                .filter { it.isFile && it.extension == "tflite" }
                .associateWith { digest(it) }
            MediaFiles.clearCache(cache, emptySet())
            val warmStart = SystemClock.elapsedRealtime()
            val warm = engine.infer(input) {}
            val warmMs = SystemClock.elapsedRealtime() - warmStart
            check(cold.indices.all { abs(cold[it] - warm[it]) < 0.0001f })
            check(compiled.all { (file, hash) -> digest(file).contentEquals(hash) })
            check(files.walkTopDown().filter { it.isFile }.all {
                Os.stat(it.path).st_gid == Os.stat(files.path).st_gid
            })
            val cpu = infer(model, input, Accelerator.CPU)
            val gpu = infer(model, input, Accelerator.GPU)
            for (mask in listOf(warm, gpu)) {
                check(mask.size == cpu.size && mask.all { it.isFinite() })
                val mae = mask.indices.sumOf { abs(mask[it] - cpu[it]).toDouble() } / mask.size
                check(mae < 0.001) { "Mask MAE: $mae" }
                Log.i("ModelRegression", "mask_mae=$mae")
            }
            Log.i("ModelRegression", "cold_ms=$coldMs warm_ms=$warmMs compiled_files=${compiled.size}")
        } finally {
            check(cache.deleteRecursively())
            check(files.deleteRecursively())
        }
    }

    private fun digest(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest()
    }

    private fun infer(path: File, input: FloatArray, accelerator: Accelerator): FloatArray {
        val options = CompiledModel.Options(accelerator).apply {
            if (accelerator == Accelerator.CPU) cpuOptions = CompiledModel.CpuOptions(numThreads = 4)
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
