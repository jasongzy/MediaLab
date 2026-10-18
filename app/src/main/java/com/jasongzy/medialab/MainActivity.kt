package com.jasongzy.medialab

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var model: LabViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (
            savedInstanceState == null &&
                !NcmBatch.state.value.busy &&
                !intent.getBooleanExtra("batch", false)
        )
            NcmBatch.clear()
        model = ViewModelProvider(this)[LabViewModel::class.java]
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.mode) {
                AppCompatDelegate.setDefaultNightMode(state.mode.nightMode)
            }
            val context = LocalContext.current
            val colors =
                if (isSystemInDarkTheme()) dynamicDarkColorScheme(context)
                else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = colors) { LabApp(model, state, ::finish) }
            LaunchedEffect(Unit) {
                if (savedInstanceState == null) {
                    model.state.first { !it.busy }
                    receive(intent)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleScope.launch {
            model.state.first { !it.busy }
            receive(intent)
        }
    }

    private fun receive(intent: Intent) {
        if (intent.getBooleanExtra("batch", false)) model.showBatch(true)
        val uri =
            when (intent.action) {
                Intent.ACTION_SEND ->
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                Intent.ACTION_VIEW -> intent.data
                else -> null
            }
        if (uri != null) model.open(uri, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabApp(model: LabViewModel, state: LabState, exit: () -> Unit) {
    val batch by NcmBatch.state.collectAsStateWithLifecycle()
    var settings by rememberSaveable { mutableStateOf(false) }
    val clearingCache = state.busy && state.stage == R.string.clearing_cache
    var confirmExit by remember { mutableStateOf(false) }
    var stopAndExit by remember { mutableStateOf(false) }
    LaunchedEffect(stopAndExit, batch.busy) {
        if (stopAndExit && !batch.busy) {
            NcmBatch.clear()
            exit()
        }
    }
    BackHandler(
        enabled =
            !settings &&
                (state.photo.frame != null ||
                    state.audio != null ||
                    batch.items.isNotEmpty() ||
                    batch.busy)
    ) {
        confirmExit = true
    }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.notice) {
        state.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            model.clearNotice()
        }
    }
    BackHandler(enabled = settings) { settings = false }
    BackHandler(enabled = !settings && state.tab == 1 && state.batchPage) { model.showBatch(false) }
    AnimatedContent(
        targetState = settings,
        modifier = Modifier.fillMaxSize().clipToBounds(),
        transitionSpec = { pageTransition(targetState) },
        label = "settings",
    ) { showSettings ->
        if (showSettings)
            SettingsScreen(
                state.mode,
                model::mode,
                clearingCache,
                model::clearCache,
                state.refreshingMedia,
                model::refreshMediaIndex,
            ) {
                settings = false
            }
        else
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                        },
                        actions = {
                            if (batch.busy && !(state.tab == 1 && state.batchPage))
                                TextButton({ model.showBatch(true) }) {
                                    Text(stringResource(R.string.unlock))
                                }
                            ToolButton(R.drawable.ic_settings, R.string.settings) {
                                settings = true
                            }
                        },
                    )
                },
                bottomBar = {
                    NavigationBar {
                        for ((index, label, icon) in
                            listOf(
                                Triple(0, R.string.image_tab, R.drawable.ic_photo),
                                Triple(1, R.string.audio_tab, R.drawable.ic_audio),
                            )) {
                            NavigationBarItem(
                                selected = state.tab == index,
                                onClick = { model.tab(index) },
                                icon = { Icon(painterResource(icon), null) },
                                label = { Text(stringResource(label)) },
                            )
                        }
                    }
                },
            ) { padding ->
                AnimatedContent(
                    targetState = state.tab,
                    modifier = Modifier.fillMaxSize().padding(padding).clipToBounds(),
                    transitionSpec = { pageTransition(targetState > initialState) },
                    label = "workspace",
                ) { tab ->
                    if (tab == 0) PhotoScreen(model, state)
                    else
                        AnimatedContent(
                            targetState = state.batchPage,
                            transitionSpec = { pageTransition(targetState) },
                            label = "audio-page",
                        ) { showBatch ->
                            if (showBatch) NcmScreen(model, batch)
                            else
                                AudioScreen(
                                    model,
                                    state,
                                    active = !settings && state.tab == 1 && !state.batchPage,
                                )
                        }
                }
            }
    }
    state.replacing?.let { type ->
        val image = type == ImportType.IMAGE
        AlertDialog(
            onDismissRequest = { model.confirmReplacement(false) },
            title = {
                Text(stringResource(if (image) R.string.replace_image else R.string.replace_audio))
            },
            text = {
                Text(
                    stringResource(
                        if (image) R.string.replace_message else R.string.replace_audio_message
                    )
                )
            },
            confirmButton = {
                TextButton({ model.confirmReplacement(true) }) {
                    Text(stringResource(R.string.discard))
                }
            },
            dismissButton = {
                TextButton({ model.confirmReplacement(false) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (state.busy && state.replacing == null && (!settings || !clearingCache))
        Dialog(onDismissRequest = {}) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(state.stage), style = MaterialTheme.typography.titleMedium)
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(
                        onClick = model::cancel,
                        modifier = Modifier.align(androidx.compose.ui.Alignment.End),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = model::clearError,
            title = { Text(stringResource(R.string.error_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.error_message))
                    SelectionContainer {
                        Text(
                            error,
                            Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = model::clearError) { Text(stringResource(R.string.close)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(android.content.ClipData.newPlainText("", error))
                            )
                            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.copy))
                }
            },
        )
    }
    if (confirmExit)
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.exit_confirmation)) },
            text = {
                Text(
                    stringResource(
                        if (batch.busy) R.string.batch_exit_message else R.string.exit_message
                    )
                )
            },
            confirmButton = {
                TextButton({
                    confirmExit = false
                    if (!batch.busy) NcmBatch.clear()
                    exit()
                }) {
                    Text(
                        stringResource(if (batch.busy) R.string.batch_background else R.string.exit)
                    )
                }
            },
            dismissButton = {
                Row {
                    if (batch.busy)
                        TextButton({
                            confirmExit = false
                            stopAndExit = true
                            NcmBatch.cancel()
                        }) {
                            Text(
                                stringResource(R.string.batch_stop_exit),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    TextButton({ confirmExit = false }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
}

private fun pageTransition(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return slideInHorizontally(
        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
        initialOffsetX = { it * direction },
    ) togetherWith
        slideOutHorizontally(
            animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
            targetOffsetX = { -it * direction },
        )
}

@Composable
internal fun ChoiceChips(choices: List<Pair<Int, String>>, selected: Int, choose: (Int) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(choices.size) { i ->
            val (key, label) = choices[i]
            FilterChip(selected == key, { choose(key) }, label = { Text(label) })
        }
    }
}

@Composable
internal fun Toggle(label: Int, value: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            stringResource(label),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(value, set, enabled = enabled)
    }
}

@Composable
internal fun EmptyWorkspace(
    icon: Int,
    title: Int,
    action: Int,
    open: () -> Unit,
    extraAction: @Composable () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Icon(
                painterResource(icon),
                null,
                Modifier.padding(24.dp).size(48.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(open) { Text(stringResource(action)) }
        extraAction()
    }
}
