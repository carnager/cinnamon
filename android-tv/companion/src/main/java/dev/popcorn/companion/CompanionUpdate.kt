package dev.popcorn.companion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun CompanionUpdateDialog(
    api: Api,
    info: AppUpdateInfo,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update Popcorn") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Version ${info.versionName} (${info.versionCode}) is available.")
                if (info.notes.isNotBlank()) Text(info.notes, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (info.sizeBytes > 0) Text(formatUpdateBytes(info.sizeBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (downloading) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator()
                        Text("Downloading...")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !downloading,
                onClick = {
                    scope.launch {
                        downloading = true
                        status = ""
                        runCatching {
                            val apk = File(context.cacheDir, "updates/popcorn-companion-${info.versionCode}.apk")
                            api.downloadCompanionUpdate(info, apk)
                        }.onSuccess { apk ->
                            status = installCompanionApk(context, apk)
                        }.onFailure {
                            val message = it.message ?: "Download failed"
                            status = message
                            onError(message)
                        }
                        downloading = false
                    }
                },
            ) {
                Text("Install")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !downloading) {
                Text("Later")
            }
        },
    )
}

private fun installCompanionApk(context: Context, apk: File): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Allow Popcorn to install unknown apps, then press Install again."
    }

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(intent)
    return "Opening Android installer."
}

private fun formatUpdateBytes(bytes: Long): String {
    val mib = bytes.toDouble() / (1024.0 * 1024.0)
    return "%.1f MB".format(mib)
}
