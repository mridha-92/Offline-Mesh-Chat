package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.model.TransportType
import com.example.transport.api.TransportStatus
import com.example.ui.MeshChatViewModel
import com.example.ui.theme.MeshAccentCyan
import com.example.ui.theme.MeshAccentGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransportStatusScreen(
    viewModel: MeshChatViewModel,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val activeMode by viewModel.activeTransportMode.collectAsStateWithLifecycle()
    val nearbyStatus by (viewModel.nearbyStatus ?: kotlinx.coroutines.flow.MutableStateFlow(TransportStatus(TransportType.AUTO, statusMessage = "Nearby unavailable"))).collectAsStateWithLifecycle()
    val btStatus by viewModel.bluetoothStatus.collectAsStateWithLifecycle()
    val wdStatus by viewModel.wifiDirectStatus.collectAsStateWithLifecycle()
    val isRelayEnabled by viewModel.isRelayEnabled.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("transports_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Network & Transports", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "Transport Mode Selection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Configure how your device discovers peers and routes encrypted mesh envelopes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))

            // Transport Selector segmented cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransportModeChip(
                    title = "Automatic",
                    subtitle = "Dual-Link",
                    icon = Icons.Default.AllInclusive,
                    isSelected = activeMode == TransportType.AUTO,
                    onClick = { viewModel.setTransportMode(TransportType.AUTO) },
                    modifier = Modifier.weight(1f)
                )
                TransportModeChip(
                    title = "Bluetooth",
                    subtitle = "BLE / RFCOMM",
                    icon = Icons.Default.Bluetooth,
                    isSelected = activeMode == TransportType.BLUETOOTH,
                    onClick = { viewModel.setTransportMode(TransportType.BLUETOOTH) },
                    modifier = Modifier.weight(1f)
                )
                TransportModeChip(
                    title = "Wi-Fi Direct",
                    subtitle = "High Speed P2P",
                    icon = Icons.Default.Wifi,
                    isSelected = activeMode == TransportType.WI_FI_DIRECT,
                    onClick = { viewModel.setTransportMode(TransportType.WI_FI_DIRECT) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Relay Mode Participation Setting
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Store-and-Forward Mesh Relay",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Participate in relaying encrypted envelopes for neighboring nodes (A -> B -> C -> D). Relay nodes cannot decipher message contents.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isRelayEnabled,
                        onCheckedChange = { viewModel.toggleRelayParticipation(it) },
                        modifier = Modifier.testTag("relay_mode_switch")
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Radio Telemetry & Foundation",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            RadioStatusCard(
                name = "Google Nearby Connections (P2P Cluster)",
                icon = Icons.Default.Hub,
                status = nearbyStatus
            )

            Spacer(modifier = Modifier.height(12.dp))

            RadioStatusCard(
                name = "Bluetooth / BLE Mesh",
                icon = Icons.Default.Bluetooth,
                status = btStatus
            )

            Spacer(modifier = Modifier.height(12.dp))

            RadioStatusCard(
                name = "Wi-Fi Direct (P2P)",
                icon = Icons.Default.Wifi,
                status = wdStatus
            )
        }
    }
}

@Composable
fun TransportModeChip(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.testTag("transport_mode_${title.lowercase()}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = if (isSelected) CardDefaults.outlinedCardBorder() else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun RadioStatusCard(name: String, icon: ImageVector, status: TransportStatus) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (status.isEnabled) MeshAccentCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        text = status.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (status.isEnabled) MeshAccentGreen.copy(alpha = 0.2f) else MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = if (status.isEnabled) "ACTIVE" else "OFFLINE",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (status.isEnabled) MeshAccentGreen else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}
