package dev.popcorn.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionTopAppBar(
    session: Session,
    playbackTarget: PlaybackTarget,
    devices: List<Device>,
    selectedDevice: Device?,
    deviceStatus: String,
    onSelectPhone: () -> Unit,
    onSelectDevice: (Device) -> Unit,
    onHistory: () -> Unit,
    onWatchlist: () -> Unit,
    onScan: () -> Unit,
    showUpdate: Boolean,
    onUpdate: () -> Unit,
    onLogout: () -> Unit,
    onRefreshDevices: () -> Unit,
) {
    var userMenuOpen by remember { mutableStateOf(false) }
    var userMenuPage by remember { mutableStateOf(UserMenuPage.Root) }
    var targetSheetOpen by remember { mutableStateOf(false) }

    Column {
        TopAppBar(
            title = { CinnamonBrand(markSize = 28, fontSize = 18) },
            actions = {
                // The playback destination is a global setting, so it lives here
                // rather than only inside Now Playing — which is unreachable when
                // nothing is playing.
                PlaybackTargetButton(
                    label = if (playbackTarget == PlaybackTarget.Phone) "This phone" else selectedDevice?.displayName() ?: "Choose TV",
                    icon = if (playbackTarget == PlaybackTarget.Phone) Icons.Default.PhoneAndroid else Icons.Default.Cast,
                    modifier = Modifier.padding(end = 8.dp).widthIn(max = 150.dp),
                    onClick = {
                        onRefreshDevices()
                        targetSheetOpen = true
                    },
                )
                UserAvatar(
                    session = session,
                    size = 38,
                    showUpdate = showUpdate,
                    modifier = Modifier.padding(end = 9.dp).clickable {
                        userMenuPage = UserMenuPage.Root
                        userMenuOpen = true
                    },
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextColor, actionIconContentColor = Muted),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = .45f)))
    }

    if (targetSheetOpen) {
        PlaybackTargetSheet(
            devices = devices,
            selectedDevice = selectedDevice,
            playbackTarget = playbackTarget,
            phoneStatus = "Play on this device",
            deviceStatus = deviceStatus,
            onDismiss = { targetSheetOpen = false },
            onSelectPhone = { targetSheetOpen = false; onSelectPhone() },
            onSelectDevice = { targetSheetOpen = false; onSelectDevice(it) },
        )
    }

    if (userMenuOpen) {
        UserMenuSheet(
            session = session,
            page = userMenuPage,
            showUpdate = showUpdate,
            onPage = { userMenuPage = it },
            onDismiss = { userMenuOpen = false; userMenuPage = UserMenuPage.Root },
            onHistory = { userMenuOpen = false; userMenuPage = UserMenuPage.Root; onHistory() },
            onWatchlist = { userMenuOpen = false; userMenuPage = UserMenuPage.Root; onWatchlist() },
            onScan = { userMenuOpen = false; onScan() },
            onRefreshDevices = { userMenuOpen = false; onRefreshDevices() },
            onUpdate = { userMenuOpen = false; onUpdate() },
            onLogout = { userMenuOpen = false; onLogout() },
        )
    }
}

private enum class UserMenuPage { Root, Audio, Subtitles }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserMenuSheet(
    session: Session,
    page: UserMenuPage,
    showUpdate: Boolean,
    onPage: (UserMenuPage) -> Unit,
    onDismiss: () -> Unit,
    onHistory: () -> Unit,
    onWatchlist: () -> Unit,
    onScan: () -> Unit,
    onRefreshDevices: () -> Unit,
    onUpdate: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val audioOptions = listOf("" to "Track default") + prefLanguageChoices
    val subtitleOptions = listOf(
        PlaybackPrefs.SUBS_OFF to "Off",
        PlaybackPrefs.TRACK_DEFAULT to "Track default",
    ) + prefLanguageChoices
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Bg,
        contentColor = TextColor,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(Modifier.padding(top = 11.dp, bottom = 5.dp).size(width = 38.dp, height = 4.dp).clip(RoundedCornerShape(99.dp)).background(Line))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(
                when (page) {
                    UserMenuPage.Root -> "USER MENU"
                    UserMenuPage.Audio -> "PREFERRED AUDIO"
                    UserMenuPage.Subtitles -> "PREFERRED SUBTITLES"
                },
                color = Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.4.sp,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                if (page == UserMenuPage.Root) session.displayName.ifBlank { session.username }.ifBlank { "Cinnamon user" } else "Playback languages",
                color = TextColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
            when (page) {
                UserMenuPage.Root -> {
                    UserMenuAction("Watchlist", Icons.Default.Bookmark, onClick = onWatchlist)
                    UserMenuAction("Watch history", Icons.Default.History, onClick = onHistory)
                    UserMenuAction(
                        "Preferred audio",
                        Icons.AutoMirrored.Filled.VolumeUp,
                        detail = audioOptions.firstOrNull { it.first == PlaybackPrefs.audioLang }?.second.orEmpty(),
                        onClick = { onPage(UserMenuPage.Audio) },
                    )
                    UserMenuAction(
                        "Preferred subtitles",
                        Icons.Default.Subtitles,
                        detail = subtitleOptions.firstOrNull { it.first == PlaybackPrefs.subtitleLang }?.second.orEmpty(),
                        onClick = { onPage(UserMenuPage.Subtitles) },
                    )
                    UserMenuAction("Scan QR code", Icons.Default.QrCodeScanner, onClick = onScan)
                    UserMenuAction("Refresh devices", Icons.Default.Refresh, onClick = onRefreshDevices)
                    if (showUpdate) UserMenuAction("Update app", Icons.Default.SystemUpdate, tint = Accent, onClick = onUpdate)
                    UserMenuAction("Logout", Icons.AutoMirrored.Filled.Logout, onClick = onLogout)
                }
                UserMenuPage.Audio -> {
                    UserMenuAction("Back", Icons.AutoMirrored.Filled.ArrowBack, onClick = { onPage(UserMenuPage.Root) })
                    audioOptions.forEach { option ->
                        UserMenuAction(
                            option.second,
                            Icons.AutoMirrored.Filled.VolumeUp,
                            selected = PlaybackPrefs.audioLang == option.first,
                            onClick = {
                                PlaybackPrefs.setAudio(context, option.first)
                                onPage(UserMenuPage.Root)
                            },
                        )
                    }
                }
                UserMenuPage.Subtitles -> {
                    UserMenuAction("Back", Icons.AutoMirrored.Filled.ArrowBack, onClick = { onPage(UserMenuPage.Root) })
                    subtitleOptions.forEach { option ->
                        UserMenuAction(
                            option.second,
                            Icons.Default.Subtitles,
                            selected = PlaybackPrefs.subtitleLang == option.first,
                            onClick = {
                                PlaybackPrefs.setSubtitle(context, option.first)
                                onPage(UserMenuPage.Root)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserMenuAction(label: String, icon: ImageVector, tint: Color = Muted, detail: String = "", selected: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(54.dp).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, color = if (tint == Accent) Accent else TextColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (detail.isNotBlank()) Text(detail, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = Accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun UserAvatar(session: Session, size: Int, modifier: Modifier = Modifier, showUpdate: Boolean = false) {
    val label = session.displayName.ifBlank { session.username }.ifBlank { "User" }
    val initials = label.split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifBlank { "C" }
    val avatarUrl = if (session.userId > 0 && session.avatar.isNotBlank()) "${session.server}/api/users/${session.userId}/avatar?v=${session.avatar}" else ""
    Box(modifier.size(size.dp)) {
        Box(
            Modifier.fillMaxWidth().height(size.dp).clip(CircleShape).background(Surface3).border(1.dp, if (showUpdate) Accent else Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl.isNotBlank()) {
                AuthAsyncImage(session, avatarUrl, label, Modifier.fillMaxWidth().height(size.dp), ContentScale.Crop)
            } else {
                Text(initials, color = Accent, fontSize = (size * .34f).sp, fontWeight = FontWeight.Black)
            }
        }
        if (showUpdate) Box(Modifier.align(Alignment.TopEnd).size(10.dp).clip(CircleShape).background(Accent).border(2.dp, Bg, CircleShape))
    }
}
