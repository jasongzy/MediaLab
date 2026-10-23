package com.jasongzy.medialab

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    colorMode: ColorMode,
    chooseColorMode: (ColorMode) -> Unit,
    clearingCache: Boolean,
    clearCache: () -> Unit,
    refreshingMedia: Boolean,
    refreshMediaIndex: () -> Unit,
    dismiss: () -> Unit,
) {
    val context = LocalContext.current
    val githubUrl = stringResource(R.string.github_url)
    var language by rememberSaveable { mutableStateOf(false) }
    var colors by rememberSaveable { mutableStateOf(false) }
    val locale = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val currentLanguage =
        when {
            locale.startsWith("zh") -> "zh-CN"
            locale.startsWith("en") -> "en"
            else -> ""
        }
    val languages =
        listOf(
            "" to R.string.follow_system,
            "en" to R.string.english,
            "zh-CN" to R.string.chinese,
        )
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    ToolButton(R.drawable.ic_back, R.string.back, action = dismiss)
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                content = { Text(stringResource(R.string.language)) },
                supportingContent = {
                    Text(stringResource(languages.first { it.first == currentLanguage }.second))
                },
                leadingContent = { Icon(painterResource(R.drawable.ic_language), null) },
                modifier = Modifier.clickable(role = Role.Button) { language = true },
            )
            ListItem(
                content = { Text(stringResource(R.string.color_mode)) },
                supportingContent = { Text(stringResource(colorMode.label)) },
                leadingContent = { Icon(painterResource(R.drawable.ic_theme), null) },
                modifier = Modifier.clickable(role = Role.Button) { colors = true },
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 16.dp))
            ListItem(
                content = { Text(stringResource(R.string.clear_cache)) },
                supportingContent = {
                    Text(stringResource(R.string.cache_description))
                },
                leadingContent = { Icon(painterResource(R.drawable.ic_clean), null) },
                modifier =
                    Modifier.clickable(
                        enabled = !clearingCache,
                        role = Role.Button,
                        onClick = clearCache,
                    ),
            )
            if (clearingCache)
                LinearWavyProgressIndicator(
                    Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 12.dp)
                )
            ListItem(
                content = { Text(stringResource(R.string.refresh_media_index)) },
                supportingContent = {
                    Text(stringResource(R.string.media_index_description))
                },
                leadingContent = { Icon(painterResource(R.drawable.ic_reset), null) },
                modifier =
                    Modifier.clickable(
                        enabled = !refreshingMedia,
                        role = Role.Button,
                        onClick = refreshMediaIndex,
                    ),
            )
            if (refreshingMedia)
                LinearWavyProgressIndicator(
                    Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 12.dp)
                )
            HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 16.dp))
            ListItem(
                content = { Text(stringResource(R.string.github)) },
                supportingContent = { Text(githubUrl.removePrefix("https://")) },
                leadingContent = { Icon(painterResource(R.drawable.ic_open_in_new), null) },
                modifier =
                    Modifier.clickable(role = Role.Button) {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, githubUrl.toUri()))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, R.string.cannot_open_link, Toast.LENGTH_SHORT)
                                .show()
                        }
                    },
            )
            ListItem(
                content = { Text(stringResource(R.string.version)) },
                supportingContent = {
                    Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                },
                leadingContent = { Icon(painterResource(R.drawable.ic_info), null) },
            )
        }
    }
    if (language) {
        ChoiceDialog(R.string.language, languages, currentLanguage, { language = false }) { value ->
            language = false
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(value))
        }
    }
    if (colors) {
        ChoiceDialog(
            R.string.color_mode,
            ColorMode.entries.map { it.name to it.label },
            colorMode.name,
            { colors = false },
        ) { value ->
            colors = false
            chooseColorMode(ColorMode.valueOf(value))
        }
    }
}

@Composable
private fun ChoiceDialog(
    title: Int,
    choices: List<Pair<String, Int>>,
    current: String,
    dismiss: () -> Unit,
    choose: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                for ((value, label) in choices) {
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(
                                selected = current == value,
                                role = Role.RadioButton,
                                onClick = { choose(value) },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(current == value, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.close)) } },
    )
}
