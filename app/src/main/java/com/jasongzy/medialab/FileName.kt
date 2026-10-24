package com.jasongzy.medialab

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.FileUriExposedException
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun FileName(
    name: String,
    sourceUri: Uri?,
    sourceMime: String,
    sourceBytes: Long?,
    sourceDetail: Pair<Int, String>,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openWith = stringResource(R.string.open_with)
    var details by rememberSaveable(name, sourceUri) { mutableStateOf(false) }
    Text(
        name,
        modifier.combinedClickable(
            onClick = { details = true },
            onClickLabel = stringResource(R.string.file_details),
            onLongClickLabel = stringResource(R.string.copy),
            onLongClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", name)))
                    Toast.makeText(context, R.string.filename_copied, Toast.LENGTH_SHORT).show()
                }
            },
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.titleSmall,
    )
    if (details)
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text(stringResource(R.string.file_details)) },
            text = {
                SelectionContainer {
                    Column(
                        Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        listOfNotNull(
                                R.string.filename to name,
                                R.string.file_format to sourceMime,
                                sourceBytes?.let {
                                    R.string.original_file_size to
                                        Formatter.formatFileSize(context, it)
                                },
                                sourceDetail,
                            )
                            .forEach { (label, value) ->
                                MediaDetail(stringResource(label), value)
                            }
                        sourceUri?.let { MediaDetail(stringResource(R.string.source_address), it.toString()) }
                    }
                }
            },
            confirmButton = {
                TextButton({ details = false }) { Text(stringResource(R.string.close)) }
            },
            dismissButton = {
                if (sourceUri != null)
                    TextButton({
                        val error =
                            try {
                                val intent =
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(sourceUri, sourceMime)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        .apply { clipData = ClipData.newRawUri(name, sourceUri) }
                                val chooser =
                                    Intent.createChooser(intent, openWith)
                                        .putExtra(
                                            Intent.EXTRA_EXCLUDE_COMPONENTS,
                                            arrayOf(
                                                ComponentName(context, MainActivity::class.java)
                                            ),
                                        )
                                context.startActivity(chooser)
                                null
                            } catch (_: ActivityNotFoundException) {
                                R.string.no_file_viewer
                            } catch (_: SecurityException) {
                                R.string.source_open_failed
                            } catch (_: FileUriExposedException) {
                                R.string.source_open_failed
                            }
                        error?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    }) {
                        Text(openWith)
                    }
            },
        )
}
