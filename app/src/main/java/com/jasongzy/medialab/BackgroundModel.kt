package com.jasongzy.medialab

internal enum class BackgroundModel(val file: String, val label: Int) {
    RMBG14("rmbg14.tflite", R.string.model_rmbg14),
    RMBG20("rmbg20.mnn", R.string.model_rmbg20),
}
