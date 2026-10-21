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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class NotificationAction { CONVERT, RETRY, CLEANUP }

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
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            if (it.isNotEmpty()) NcmBatch.addFiles(context, it)
        }
    val folder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            if (it != null) NcmBatch.addFolder(context, it)
        }
    val output =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            if (it != null) NcmBatch.output(context, it)
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
                            files.launch(arrayOf("*/*"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.batch_add_folder)) },
                        onClick = {
                            addMenu = false
                            folder.launch(null)
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
                        text = { Text(stringResource(R.string.batch_cleanup)) },
                        enabled =
                            state.items.any { it.selected && it.file.ncm && !it.sourceDeleted },
                        onClick = {
                            menu = false
                            notifyThen(NotificationAction.CLEANUP)
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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.batch_output),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    state.outputName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton({ output.launch(state.outputTree) }, enabled = !state.busy) {
                Text(stringResource(R.string.batch_change))
            }
            if (state.outputTree != null)
                TextButton({ NcmBatch.output(context, null) }, enabled = !state.busy) {
                    Text(stringResource(R.string.batch_reset_output))
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
                Button({ files.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.batch_add_files))
                }
                TextButton({ folder.launch(null) }) {
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
            LazyColumn(Modifier.weight(1f)) {
                items(
                    state.items.filter { !failures || it.status == BatchStatus.FAILED },
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
                                        if (item.status == BatchStatus.FAILED)
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
                Text(
                    stringResource(if (state.cancelling) R.string.cancelling else state.stage),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (state.total > 0) {
                    LinearWavyProgressIndicator(
                        progress = { (state.completed + (state.progress ?: 0f)) / state.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.batch_progress, state.completed, state.total),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                if (state.current.isNotEmpty())
                    Text(
                        state.current,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                if (state.progress != null)
                    LinearWavyProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().height(12.dp),
                    )
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
                val failed = state.items.count { it.status == BatchStatus.FAILED }
                if (success + skipped + failed > 0)
                    Text(
                        stringResource(R.string.batch_summary, success, skipped, failed),
                        style = MaterialTheme.typography.bodySmall,
                    )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (failed > 0)
                        OutlinedButton({ start(true) }, Modifier.weight(1f)) {
                            Text(stringResource(R.string.batch_retry))
                        }
                    Button(
                        { start(false) },
                        Modifier.weight(1f),
                        enabled = state.pending.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.batch_start))
                    }
                }
                if (state.items.any { it.output != null })
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
                        Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.batch_open_output))
                    }
            }
        }
    }
    if (options)
        ModalBottomSheet(onDismissRequest = { options = false }) {
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.batch_options),
                    style = MaterialTheme.typography.titleLarge,
                )
                Toggle(R.string.batch_recursive, state.options.recursive) {
                    NcmBatch.configure(state.options.copy(recursive = it))
                }
                Toggle(R.string.batch_copy_audio, state.options.copyAudio) {
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
                Toggle(R.string.batch_delete_original, state.options.deleteOriginal) {
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
