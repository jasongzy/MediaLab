package com.jasongzy.medialab

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val grantIo = Dispatchers.IO.limitedParallelism(1)
private const val accessModes =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

@Composable
@SuppressLint("WrongConstant") // Only the read/write bits from the picker result reach the resolver.
internal fun <I> rememberNcmPicker(
    contract: ActivityResultContract<I, *>,
    onPick: (List<Uri>) -> Unit,
): (I) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@rememberLauncherForActivityResult
            val uris = ActivityResultContracts.OpenMultipleDocuments().parseResult(result.resultCode, data)
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                if (data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
                    withContext(grantIo) {
                        for (uri in uris) {
                            try {
                                context.contentResolver.takePersistableUriPermission(uri, data.flags and accessModes)
                            } catch (_: SecurityException) {
                                // The picker's temporary grant can still be used for this selection.
                            }
                        }
                    }
                }
                onPick(uris)
            }
        }
    return { launcher.launch(contract.createIntent(context, it)) }
}

internal fun releaseNcmGrants(context: Context) {
    val resolver = context.applicationContext.contentResolver
    CoroutineScope(grantIo).launch {
        // Only NCM retains URI grants. Read the system list to also reclaim grants after process death.
        for (grant in resolver.persistedUriPermissions) {
            try {
                resolver.releasePersistableUriPermission(grant.uri, accessModes)
            } catch (_: SecurityException) {
                // The provider can revoke a grant before we release it.
            }
        }
    }
}
