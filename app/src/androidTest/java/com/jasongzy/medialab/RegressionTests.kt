package com.jasongzy.medialab

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.os.Bundle
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

class RegressionTests : Instrumentation() {
    private var selectedTest: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        selectedTest = arguments?.getString("test")
        start()
    }

    override fun onStart() {
        val root = File(targetContext.cacheDir, "regression-${UUID.randomUUID()}")
        val app =
            object : Application() {
                init {
                    attachBaseContext(targetContext)
                }

                override fun getCacheDir() = File(root, "cache").apply { mkdirs() }

                override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            }
        val photos = PhotoRegression(app, this)
        val media = MediaStoreRegression(app, context.assets)
        val tests =
            listOf<Pair<String, suspend () -> Unit>>(
                    "lassoEdges" to { photos.lassoEdges() },
                    "history" to { photos.history() },
                    "expansion" to { photos.expansion() },
                    "photoWorkflow" to { photos.workflow() },
                    "imports" to { photos.imports() },
                    "publication" to { media.publication() },
                    "artwork" to { media.artwork() },
                    "modelStorage" to { ModelStorageRegression.run(targetContext) },
                )
                .filter { selectedTest == null || it.first == selectedTest }
        check(tests.isNotEmpty()) { "Unknown test: $selectedTest" }
        var failures = 0
        try {
            runBlocking {
                for ((index, test) in tests.withIndex()) {
                    val status =
                        Bundle().apply {
                            putString("class", RegressionTests::class.java.name)
                            putString("test", test.first)
                            putInt("numtests", tests.size)
                            putInt("current", index + 1)
                        }
                    sendStatus(1, status)
                    try {
                        test.second()
                        sendStatus(0, status)
                    } catch (error: Throwable) {
                        failures++
                        status.putString("stack", error.stackTraceToString())
                        sendStatus(-2, status)
                    }
                }
            }
        } finally {
            check(root.deleteRecursively()) { "Cannot remove regression files" }
        }
        finish(
            Activity.RESULT_OK,
            Bundle().apply {
                putString(
                    "stream",
                    if (failures == 0) "OK (${tests.size} tests)\n"
                    else "FAILURES: $failures/${tests.size}\n",
                )
            },
        )
    }
}
