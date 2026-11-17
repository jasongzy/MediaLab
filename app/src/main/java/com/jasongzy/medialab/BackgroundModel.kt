package com.jasongzy.medialab

import android.os.Build

internal enum class BackgroundModel(val file: String, val label: Int) {
    RMBG14("rmbg14.tflite", R.string.model_rmbg14),
    RMBG20("rmbg20.mnn", R.string.model_rmbg20);

    // The bundled ARM64 LiteRT runtime can crash even on CPU under x86 binary translation.
    val supportsRuntime get() = this != RMBG14 || Build.SUPPORTED_ABIS.first() == "arm64-v8a"
}
