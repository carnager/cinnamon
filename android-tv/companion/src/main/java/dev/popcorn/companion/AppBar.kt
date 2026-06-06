package dev.popcorn.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionTopAppBar(
    devices: List<Device>,
    selectedDevice: Device?,
    playbackTarget: PlaybackTarget,
    onSelectPhone: () -> Unit,
    onSelectDevice: (Device) -> Unit,
    onScan: () -> Unit,
    showUpdate: Boolean,
    onUpdate: () -> Unit,
    onLogout: () -> Unit,
    onRefreshDevices: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val targetLabel = if (playbackTarget == PlaybackTarget.Phone) "Phone" else selectedDevice?.displayName() ?: "No TV"
    val targetIcon = if (playbackTarget == PlaybackTarget.Phone) Icons.Default.PhoneAndroid else Icons.Default.LiveTv
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Popcorn", fontWeight = FontWeight.Black)
                Box {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { expanded = true },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Icon(targetIcon, contentDescription = null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(targetLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("\u2304", color = Muted, fontSize = 15.sp)
                        }
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.background(Surface2),
                    ) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            text = {
                                Text(
                                    "Phone",
                                    color = TextColor,
                                    fontWeight = if (playbackTarget == PlaybackTarget.Phone) FontWeight.Black else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = {
                                expanded = false
                                onSelectPhone()
                            },
                        )
                        devices.forEach { device ->
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.LiveTv, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                text = {
                                    Text(
                                        device.displayName(),
                                        color = TextColor,
                                        fontWeight = if (playbackTarget == PlaybackTarget.Shield && selectedDevice?.id == device.id) FontWeight.Black else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    onSelectDevice(device)
                                },
                            )
                        }
                    }
                }
            }
        },
        actions = {
            if (showUpdate) {
                IconButton(onClick = onUpdate) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = "Update app")
                }
            }
            IconButton(onClick = onScan) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR")
            }
            IconButton(onClick = onLogout) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Logout")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}
