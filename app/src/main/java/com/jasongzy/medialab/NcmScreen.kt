package com.jasongzy.medialab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class NotificationAction { CONVERT, RETRY, CLEANUP }

@Composable
private fun FolderRow(label: Int, path: String, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(label),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            actions()
        }
        Text(
            path,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NcmScreen(model: LabViewModel, state: BatchState) {
    val context = LocalContext.current
    var options by rememberSaveable { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var addMenu by rememberSaveable { mutableStateOf(false) }
    var failures by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var notificationAction by rememberSaveable { mutableStateOf<NotificationAction?>(null) }
    var askedNotification by rememberSaveable { mutableStateOf(false) }
    fun perform(action: NotificationAction) {
        when (action) {
            NotificationAction.CONVERT -> NcmBatch.convert(context, false)
            NotificationAction.RETRY -> NcmBatch.convert(context, true)
            NotificationAction.CLEANUP -> NcmBatch.inspectCleanup(context)
        }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationAction?.let(::perform)
            notificationAction = null
        }
    val notifyThen: (NotificationAction) -> Unit = { action ->
        if (
            Build.VERSION.SDK_INT >= 33 &&
                !askedNotification &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            askedNotification = true
            notificationAction = action
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else perform(action)
    }
    val files =
        rememberNcmPicker(ActivityResultContracts.OpenMultipleDocuments()) {
            NcmBatch.addFiles(context, it)
        }
    val folder =
        rememberNcmPicker(ActivityResultContracts.OpenDocumentTree()) {
            NcmBatch.addFolder(context, it.first())
        }
    val output =
        rememberNcmPicker(ActivityResultContracts.OpenDocumentTree()) {
            NcmBatch.output(it.first())
        }
    val start: (Boolean) -> Unit = { retry ->
        if (state.options.deleteOriginal || state.options.conflict == ConflictPolicy.OVERWRITE)
            confirm = retry
        else notifyThen(if (retry) NotificationAction.RETRY else NotificationAction.CONVERT)
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(R.drawable.ic_back, R.string.back) { model.showBatch(false) }
            Text(
                stringResource(R.string.unlock),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Box {
                TextButton({ addMenu = true }, enabled = !state.busy) {
                    Text(stringResource(R.string.batch_add))
                }
                DropdownMenu(addMenu, { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.batch_add_files)) },
                        onClick = {
                            addMenu = false
                            files(arrayOf("*/*"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.batch_add_folder)) },
                        onClick = {
                            addMenu = false
                            folder(null)
                        },
                    )
                }
            }
            Box {
                IconButton({ menu = true }, enabled = !state.busy) {
                    Icon(
                        androidx.compose.ui.res.painterResource(R.drawable.ic_more),
                        stringResource(R.string.batch_actions),
                    )
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.batch_options)) },
                        onClick = {
                            menu = false
                            options = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.batch_remove_selected)) },
                        enabled = state.items.any { it.selected },
                        onClick = {
                            menu = false
                            NcmBatch.removeSelected()
                        },
                    )
                }
            }
        }
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                val inputTree = state.inputTree
                val outputTree = state.outputTree
                if (inputTree != null) {
                    FolderRow(R.string.batch_input, DocumentsContract.getTreeDocumentId(inputTree)) {
                        if (inputTree != outputTree)
                            TextButton({ NcmBatch.output(inputTree) }, enabled = !state.busy) {
                                Text(stringResource(R.string.batch_use_as_output))
                            }
                    }
                    HorizontalDivider()
                }
                val destination =
                    when (outputTree) {
                        null -> "Music/MediaLab"
                        inputTree -> stringResource(R.string.batch_same_as_input)
                        else -> DocumentsContract.getTreeDocumentId(outputTree)
                    }
                FolderRow(R.string.batch_output, destination) {
                    TextButton({ output(outputTree ?: inputTree) }, enabled = !state.busy) {
                        Text(stringResource(R.string.batch_change))
                    }
                    if (outputTree != null)
                        TextButton({ NcmBatch.output(null) }, enabled = !state.busy) {
                            Text(stringResource(R.string.batch_reset_output))
                        }
                }
            }
        }
        if (state.items.isEmpty() && !state.busy) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.batch_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(16.dp))
                Button({ files(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.batch_add_files))
                }
                TextButton({ folder(null) }) {
                    Text(stringResource(R.string.batch_add_folder))
                }
                TextButton({ options = true }) { Text(stringResource(R.string.batch_options)) }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val all = state.items.isNotEmpty() && state.items.all { it.selected }
                Checkbox(all, { NcmBatch.select(null, it) }, enabled = !state.busy)
                Text(
                    stringResource(
                        R.string.batch_selected,
                        state.items.count { it.selected },
                        state.items.size,
                    ),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                FilterChip(
                    failures,
                    { failures = !failures },
                    label = { Text(stringResource(R.string.batch_failures)) },
                )
            }
            val scroll = rememberLazyListState()
            LazyColumn(Modifier.weight(1f).verticalScrollbar(scroll), state = scroll) {
                items(
                    state.items.filter { !failures || it.status.failed },
                    key = { it.file.key },
                ) { item ->
                    ListItem(
                        content = {
                            Text(item.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = {
                            Checkbox(
                                item.selected,
                                { NcmBatch.select(item.file.key, it) },
                                enabled = !state.busy,
                            )
                        },
                        supportingContent = {
                            Column {
                                if (item.file.directory.isNotEmpty())
                                    Text(
                                        item.file.directory,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                Text(
                                    item.detail ?: stringResource(item.status.label),
                                    color =
                                        if (item.status.failed)
                                            MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (item.output != null && item.output.name != item.file.name)
                                    Text(
                                        item.output.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                            }
                        },
                        trailingContent = {
                            if (item.output != null)
                                ToolButton(R.drawable.ic_open_in_new, R.string.open) {
                                    model.open(item.output.uri, ImportType.AUDIO)
                                }
                        },
                        modifier =
                            Modifier.clickable(enabled = !state.busy) {
                                NcmBatch.select(item.file.key, !item.selected)
                            },
                    )
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.busy) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(if (state.cancelling) R.string.cancelling else state.stage),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (state.total > 0)
                        Text(
                            stringResource(R.string.batch_progress, state.completed, state.total),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                if (state.total > 0)
                    LinearWavyProgressIndicator(
                        progress = { (state.completed + (state.progress ?: 0f)) / state.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                if (state.total > 0 || state.current.isNotEmpty())
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.total > 0)
                            CircularWavyProgressIndicator(Modifier.size(24.dp).clearAndSetSemantics {})
                        Text(
                            state.current,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                OutlinedButton(
                    NcmBatch::cancel,
                    Modifier.fillMaxWidth(),
                    enabled = !state.cancelling,
                ) {
                    Text(stringResource(R.string.batch_stop))
                }
            } else {
                val success = state.items.count { it.status == BatchStatus.SUCCESS }
                val skipped = state.items.count { it.status == BatchStatus.SKIPPED }
                val failed = state.items.count { it.status.failed }
                if (success + skipped + failed > 0)
                    Text(
                        stringResource(R.string.batch_summary, success, skipped, failed),
                        style = MaterialTheme.typography.bodySmall,
                    )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pending = state.pending
                    if (state.items.any { it.status == BatchStatus.UNLOCK_FAILED })
                        OutlinedButton(
                            { start(true) },
                            Modifier.weight(1f),
                            enabled = pending.any { it.status == BatchStatus.UNLOCK_FAILED },
                        ) {
                            Text(stringResource(R.string.batch_retry))
                        }
                    Button(
                        { start(false) },
                        Modifier.weight(1f),
                        enabled = pending.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.batch_start))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (state.items.any { it.selected && it.file.ncm && !it.sourceDeleted })
                        TextButton({ notifyThen(NotificationAction.CLEANUP) }) {
                            Text(stringResource(R.string.batch_cleanup))
                        }
                    Spacer(Modifier.weight(1f))
                    if (state.items.any { it.output != null && it.outputTree == state.outputTree })
                        TextButton(
                            {
                                val uri =
                                    state.outputTree?.let(BatchFiles::document)
                                        ?: DocumentsContract.buildDocumentUri(
                                            "com.android.externalstorage.documents",
                                            "primary:Music/MediaLab",
                                        )
                                try {
                                    val manager =
                                        context.packageManager
                                            .resolveActivity(
                                                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                                                PackageManager.MATCH_SYSTEM_ONLY or
                                                    PackageManager.MATCH_DEFAULT_ONLY,
                                            )
                                            ?.activityInfo
                                            ?.packageName
                                            ?: throw android.content.ActivityNotFoundException()
                                    // DocumentsUI uses its own permission to browse document providers.
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW)
                                            .setDataAndType(
                                                uri,
                                                DocumentsContract.Document.MIME_TYPE_DIR,
                                            )
                                            .setPackage(manager)
                                    )
                                } catch (_: android.content.ActivityNotFoundException) {
                                    Toast.makeText(
                                            context,
                                            R.string.batch_open_output_failed,
                                            Toast.LENGTH_SHORT,
                                        )
                                        .show()
                                } catch (_: SecurityException) {
                                    Toast.makeText(
                                            context,
                                            R.string.batch_open_output_failed,
                                            Toast.LENGTH_SHORT,
                                        )
                                        .show()
                                }
                            },
                        ) {
                            Text(stringResource(R.string.batch_open_output))
                        }
                }
            }
        }
    }
    if (options)
        ModalBottomSheet(onDismissRequest = { options = false }) {
            val scroll = rememberScrollState()
            Column(
                Modifier.fillMaxWidth()
                    .verticalScrollbar(scroll)
                    .verticalScroll(scroll)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.batch_options),
                    style = MaterialTheme.typography.titleLarge,
                )
                CheckOption(R.string.batch_recursive, state.options.recursive) {
                    NcmBatch.configure(state.options.copy(recursive = it))
                }
                CheckOption(R.string.batch_copy_audio, state.options.copyAudio) {
                    NcmBatch.configure(state.options.copy(copyAudio = it))
                }
                Text(
                    stringResource(R.string.batch_conflict),
                    style = MaterialTheme.typography.labelLarge,
                )
                ChoiceChips(
                    ConflictPolicy.entries.map { it.ordinal to stringResource(it.label) },
                    state.options.conflict.ordinal,
                ) {
                    NcmBatch.configure(state.options.copy(conflict = ConflictPolicy.entries[it]))
                }
                CheckOption(R.string.batch_delete_original, state.options.deleteOriginal) {
                    NcmBatch.configure(state.options.copy(deleteOriginal = it))
                }
            }
        }
    confirm?.let { retry ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.batch_confirm)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.options.conflict == ConflictPolicy.OVERWRITE)
                        Text(stringResource(R.string.batch_overwrite_confirm))
                    if (state.options.deleteOriginal)
                        Text(stringResource(R.string.batch_delete_confirm))
                }
            },
            confirmButton = {
                TextButton({
                    confirm = null
                    notifyThen(if (retry) NotificationAction.RETRY else NotificationAction.CONVERT)
                }) {
                    Text(
                        stringResource(R.string.batch_start),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton({ confirm = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    state.candidates
        ?.takeIf { !state.busy }
        ?.let { candidates ->
            AlertDialog(
                onDismissRequest = NcmBatch::dismissCandidates,
                title = {
                    Text(
                        stringResource(
                            if (candidates.isEmpty()) R.string.batch_cleanup
                            else R.string.batch_delete_title
                        )
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (candidates.isEmpty()) stringResource(R.string.batch_cleanup_empty)
                            else
                                pluralStringResource(
                                    R.plurals.batch_cleanup_confirm,
                                    candidates.size,
                                    candidates.size,
                                )
                        )
                        LazyColumn(Modifier.heightIn(max = 240.dp)) {
                            items(candidates, key = { it.source.key }) {
                                Text(
                                    it.source.path,
                                    Modifier.padding(vertical = 4.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton({
                        if (candidates.isEmpty()) NcmBatch.dismissCandidates()
                        else NcmBatch.deleteCandidates(context)
                    }) {
                        Text(
                            stringResource(
                                if (candidates.isEmpty()) R.string.close
                                else R.string.batch_delete_permanently
                            ),
                            color =
                                if (candidates.isEmpty()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    if (candidates.isNotEmpty())
                        TextButton(NcmBatch::dismissCandidates) {
                            Text(stringResource(R.string.cancel))
                        }
                },
            )
        }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = NcmBatch::clearError,
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(NcmBatch::clearError) { Text(stringResource(R.string.close)) }
            },
        )
    }
}
