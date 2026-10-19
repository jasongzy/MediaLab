package com.jasongzy.medialab

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal class BackgroundEngine(private val context: Context) {
    suspend fun prepare(): File = preparation.withLock {
        val manifest = context.assets.open("models/model.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        val hash = manifest.getString("sha256")
        val directory = File(context.filesDir, "models/$hash").apply {
            check(isDirectory || mkdirs()) { "Cannot create model directory" }
        }
        val target = File(directory, "rmbg14.tflite")
        if (target.length() == manifest.getLong("bytes")) return@withLock target
        val temporary = File(directory, "model.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("models/rmbg14.tflite").use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            check(temporary.length() == manifest.getLong("bytes")) { "Incomplete model" }
            check(digest.digest().joinToString("") { "%02x".format(it) } == hash) {
                "Model checksum mismatch"
            }
            check(temporary.renameTo(target)) { "Cannot install model" }
            target
        } finally {
            temporary.delete()
        }
    }

    suspend fun infer(input: FloatArray, progress: (Int) -> Unit): FloatArray {
        require(input.size == ImageMath.SIZE * ImageMath.SIZE * 3)
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        progress(R.string.preparing_model)
        val path = prepare()
        coroutine.ensureActive()
        val compiled = File(path.parentFile, "compiled").apply {
            check(isDirectory || mkdirs()) { "Cannot create model directory" }
        }
        // LiteRT obtains its compiler cache path from Context.cacheDir.
        val compilerContext = object : ContextWrapper(context) {
            override fun getCacheDir() = compiled
        }
        val libraryDir = context.applicationInfo.nativeLibraryDir
        Os.setenv("ADSP_LIBRARY_PATH", libraryDir, true)
        val options =
            CompiledModel.Options(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU).apply {
                qualcommOptions = CompiledModel.QualcommOptions(
                    logLevel = CompiledModel.QualcommOptions.LogLevel.WARN,
                    htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.BURST,
                    optimizationLevel =
                        CompiledModel.QualcommOptions.OptimizationLevel.HTP_OPTIMIZE_FOR_INFERENCE,
                )
            }
        try {
            Environment.create(
                compilerContext,
                mapOf(
                    Environment.Option.DispatchLibraryDir to libraryDir,
                    Environment.Option.CompilerPluginLibraryDir to libraryDir,
                ),
                enableCompilerCache = true,
            ).use { environment ->
                CompiledModel.create(path.path, options, environment)
                    .use { model ->
                        coroutine.ensureActive()
                        val inputs = model.createInputBuffers()
                        try {
                            val outputs = model.createOutputBuffers()
                            try {
                                inputs.single().writeFloat(input)
                                progress(R.string.running_model)
                                model.run(inputs, outputs)
                                val mask = outputs.single().readFloat()
                                coroutine.ensureActive()
                                check(mask.size == ImageMath.SIZE * ImageMath.SIZE && mask.all { it.isFinite() }) {
                                    "Invalid model output"
                                }
                                return mask
                            } finally {
                                outputs.forEach { it.close() }
                            }
                        } finally {
                            inputs.forEach { it.close() }
                        }
                    }
            }
        } catch (error: LinkageError) {
            throw IllegalStateException("Cannot load LiteRT", error)
        }
    }

    private companion object {
        val preparation = Mutex()
    }
}
