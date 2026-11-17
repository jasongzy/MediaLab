package com.jasongzy.medialab

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.system.Os
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal class BackgroundEngine(private val context: Context) {
    private suspend fun prepare(model: BackgroundModel): File {
        val manifest = context.assets.open("models/${model.file.substringBeforeLast('.')}.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        val hash = manifest.getString("sha256")
        val directory = File(context.filesDir, "models/$hash").apply {
            check(isDirectory || mkdirs()) { "Cannot create model directory" }
        }
        val target = File(directory, model.file)
        val bytes = manifest.getLong("bytes")
        if (target.length() == bytes) return target
        val temporary = File(directory, "model.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(context.assets.open("models/${model.file}"), digest).use { input ->
                temporary.outputStream().use { MediaFiles.copy(input, it, bytes) }
            }
            check(digest.digest().toHexString() == hash) { "Model checksum mismatch" }
            check(temporary.renameTo(target)) { "Cannot install model" }
            return target
        } finally {
            temporary.delete()
        }
    }

    suspend fun infer(model: BackgroundModel, input: FloatArray, progress: (Int) -> Unit): FloatArray = execution.withLock {
        if (!model.supportsRuntime) throw UnsupportedMediaException(R.string.model_arm64_required)
        require(input.size == ImageMath.SIZE * ImageMath.SIZE * 3)
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        progress(R.string.preparing_model)
        val path = prepare(model)
        coroutine.ensureActive()
        val compiled = File(path.parentFile, "compiled").apply {
            check(isDirectory || mkdirs()) { "Cannot create model directory" }
        }
        val mask = when (model) {
            BackgroundModel.RMBG14 -> liteRt(path, compiled, input, progress)
            BackgroundModel.RMBG20 -> mnn(path, compiled, input, progress)
        }
        coroutine.ensureActive()
        check(mask.size == ImageMath.SIZE * ImageMath.SIZE && mask.all { it.isFinite() }) {
            "Invalid model output"
        }
        mask
    }

    private suspend fun liteRt(path: File, compiled: File, input: FloatArray, progress: (Int) -> Unit): FloatArray {
        val coroutine = currentCoroutineContext()
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
                                return outputs.single().readFloat()
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

    private suspend fun mnn(path: File, compiled: File, input: FloatArray, progress: (Int) -> Unit): FloatArray {
        val system = MessageDigest.getInstance("SHA-256").digest(Build.FINGERPRINT.toByteArray()).toHexString()
        val directory = File(compiled, "mnn-${BuildConfig.MNN_VERSION}/$system").apply {
            check(isDirectory || mkdirs()) { "Cannot create model directory" }
        }
        val program = File(directory, "opencl.bin")
        val temporary = File(directory, "opencl.part")
        val coroutine = currentCoroutineContext()
        try {
            if (program.isFile) program.copyTo(temporary, overwrite = true) else temporary.delete()
            val mask = MnnEngine.infer(path.path, temporary.path, input, Runnable {
                coroutine.ensureActive()
                progress(R.string.running_model)
            })
            if (temporary.length() > 0 && !temporary.renameTo(program))
                Log.w("BackgroundEngine", "Could not store the compiled OpenCL program")
            return mask
        } catch (_: UnsupportedOperationException) {
            throw UnsupportedMediaException(R.string.model_gpu_unavailable)
        } catch (error: LinkageError) {
            throw IllegalStateException("Cannot load MNN", error)
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        // GPU memory and compilation files are shared across workspaces.
        val execution = Mutex()
    }
}

internal object MnnEngine {
    init {
        System.loadLibrary("MNN")
        System.loadLibrary("MNN_CL")
        System.loadLibrary("medialab_mnn")
    }

    external fun infer(path: String, program: String, input: FloatArray, ready: Runnable): FloatArray
}
