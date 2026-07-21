package dev.popcorn.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun UpdateView(session: Session?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    fun checkForUpdate() {
        val active = session ?: return
        scope.launch {
            checking = true
            status = ""
            runCatching { Api(active).tvUpdate(appVersionCode(context)) }
                .onSuccess {
                    update = it
                    status = when {
                        !it.configured -> "The server updater is not configured."
                        it.error.isNotBlank() -> it.error
                        it.available -> "Update available."
                        else -> "This TV app is up to date."
                    }
                }
                .onFailure { status = it.message ?: "Update check failed" }
            checking = false
        }
    }

    fun downloadAndInstall() {
        val active = session ?: return
        val info = update ?: return
        if (!info.available || info.apkUrl.isBlank()) return
        scope.launch {
            downloading = true
            status = "Downloading ${info.versionName}..."
            runCatching {
                val apk = File(context.cacheDir, "updates/popcorn-tv-${info.versionCode}.apk")
                Api(active).downloadTvUpdate(info, apk)
            }.onSuccess { apk ->
                status = installApk(context, apk)
            }.onFailure {
                status = it.message ?: "Download failed"
            }
            downloading = false
        }
    }

    LaunchedEffect(session) {
        checkForUpdate()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 42.dp, vertical = 30.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("TV App Update", color = TextColor, fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            FocusButton("Back", primary = false, onClick = onBack)
        }
        Spacer(Modifier.height(24.dp))
        Text("Installed ${appVersionName(context)} (${appVersionCode(context)})", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(18.dp))
        val info = update
        if (checking) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
                Text("Checking for updates...", color = TextColor, fontSize = 16.sp)
            }
        } else if (info != null && info.configured) {
            Text("Server ${info.versionName} (${info.versionCode})", color = TextColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (info.sizeBytes > 0) {
                Text(formatBytes(info.sizeBytes), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (info.notes.isNotBlank()) {
                Text(info.notes, color = TextColor, fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp))
            }
        }
        if (status.isNotBlank()) {
            Text(status, color = if (status.contains("failed", true) || status.contains("mismatch", true) || status.contains("not readable", true)) ErrorRed else Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp))
        }
        Spacer(Modifier.height(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FocusButton("Check Again", primary = false, onClick = ::checkForUpdate)
            FocusButton("Install", primary = true, onClick = ::downloadAndInstall)
            if (downloading) {
                Spacer(Modifier.width(4.dp))
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
fun UpdateApplyDialog(session: Session?, onDismiss: () -> Unit, onUpdateStarted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val applyFocus = remember { FocusRequester() }
    var update by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var checking by remember { mutableStateOf(true) }
    var downloading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    fun checkForUpdate() {
        val active = session ?: return
        scope.launch {
            checking = true
            status = ""
            runCatching { Api(active).tvUpdate(appVersionCode(context)) }
                .onSuccess {
                    update = it
                    status = when {
                        !it.configured -> "The server updater is not configured."
                        it.error.isNotBlank() -> it.error
                        !it.available -> "This TV app is already up to date."
                        else -> ""
                    }
                }
                .onFailure { status = it.message ?: "Update check failed" }
            checking = false
        }
    }

    fun applyUpdate() {
        val active = session ?: return
        val info = update ?: return
        if (!info.available || info.apkUrl.isBlank() || downloading) return
        scope.launch {
            downloading = true
            status = "Downloading ${info.versionName}..."
            runCatching {
                val apk = File(context.cacheDir, "updates/popcorn-tv-${info.versionCode}.apk")
                Api(active).downloadTvUpdate(info, apk)
            }.onSuccess { apk ->
                status = installApk(context, apk)
                onUpdateStarted()
            }.onFailure {
                status = it.message ?: "Download failed"
            }
            downloading = false
        }
    }

    LaunchedEffect(session) {
        checkForUpdate()
    }

    LaunchedEffect(update?.available, checking) {
        if (!checking && update?.available == true) {
            applyFocus.requestFocus()
        }
    }

    Dialog(onDismissRequest = { if (!downloading) onDismiss() }) {
        Column(
            Modifier
                .widthIn(min = 420.dp, max = 560.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Surface2.copy(alpha = .98f))
                .border(1.dp, Color.White.copy(alpha = .18f), RoundedCornerShape(14.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Apply update?", color = TextColor, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text("Installed ${appVersionName(context)} (${appVersionCode(context)})", color = Muted, fontSize = 13.sp)

            when {
                checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    Text("Checking server update...", color = TextColor, fontSize = 15.sp)
                }
                update?.available == true -> {
                    val info = update!!
                    Text("Server ${info.versionName} (${info.versionCode})", color = TextColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (info.sizeBytes > 0) {
                        Text(formatBytes(info.sizeBytes), color = Muted, fontSize = 13.sp)
                    }
                    if (info.notes.isNotBlank()) {
                        Text(info.notes, color = TextColor.copy(alpha = .78f), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 4)
                    }
                }
            }

            if (status.isNotBlank()) {
                val isError = status.contains("failed", true) || status.contains("mismatch", true) || status.contains("not configured", true) || status.contains("not readable", true)
                Text(status, color = if (isError) ErrorRed else Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (downloading) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                }
                UpdateDialogButton("No", primary = false, enabled = !downloading, onClick = onDismiss)
                Spacer(Modifier.width(10.dp))
                UpdateDialogButton(
                    "Apply",
                    primary = true,
                    enabled = update?.available == true && !downloading,
                    focusRequester = applyFocus,
                    onClick = ::applyUpdate,
                )
            }
        }
    }
}

@Composable
private fun UpdateDialogButton(
    label: String,
    primary: Boolean,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        !enabled -> Surface3.copy(alpha = .45f)
        primary && focused -> Accent
        primary -> AccentDim
        focused -> Surface3
        else -> Surface2
    }
    Box(
        Modifier
            .width(112.dp)
            .height(42.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(2.dp, if (focused) FocusGlow else Color.White.copy(alpha = .10f), RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled)
            .tvActivate { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary && enabled) Color.Black else TextColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

private fun installApk(context: Context, apk: File): String {
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
		val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Allow Cinnamon to install unknown apps, then press Install again."
    }

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
	val intent = Intent(Intent.ACTION_VIEW)
		.setDataAndType(uri, "application/vnd.android.package-archive")
		.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
	context.startActivity(intent)
	Handler(Looper.getMainLooper()).postDelayed({
		(context as? Activity)?.finishAndRemoveTask()
	}, 600)
	return "Opening Android installer. Reopen Cinnamon after installation."
}

private fun formatBytes(bytes: Long): String {
    val mib = bytes.toDouble() / (1024.0 * 1024.0)
    return "%.1f MB".format(mib)
}
