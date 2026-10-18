package com.jasongzy.medialab

import androidx.appcompat.app.AppCompatDelegate

internal enum class ColorMode(val nightMode: Int, val label: Int) {
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, R.string.follow_system),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO, R.string.theme_light),
    DARK(AppCompatDelegate.MODE_NIGHT_YES, R.string.theme_dark);

    companion object {
        fun fromStored(value: String?): ColorMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
