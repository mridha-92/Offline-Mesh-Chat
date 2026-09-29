package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.model.TransportType
import com.example.database.PeerEntity
import com.example.ui.MeshChatViewModel
import com.example.ui.theme.MeshAccentCyan
import com.example.ui.theme.MeshAccentGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyPeersScreen(
    viewModel: MeshChatViewModel,
    onStartChat: (PeerEntity) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val peers by viewModel.discoveredPeers.collectAsStateWithLifecycle()
    var selectedPeerForVerify by remember { mutableStateOf<PeerEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("peers_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Nearby Devices (${peers.size})", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        onClick = { viewModel.setTransportMode(viewModel.activeTransportMode.value) },
                        modifier = Modifier.testTag("refresh_discovery_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Scan")
                    }
                }
            )
        }
    ) { padding ->
        if (peers.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Scanning for Nearby Mesh Nodes...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Ensure Bluetooth and Wi-Fi are active on neighboring devices. Discovered nodes will automatically appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(peers, key = { it.deviceId }) { peer ->
                    PeerListItem(
                        peer = peer,
                        onChatClick = { onStartChat(peer) },
                        onVerifyClick = { selectedPeerForVerify = peer }
                    )
                }
            }
        }
    }

    selectedPeerForVerify?.let { peer ->
        PeerVerificationDialog(
            peer = peer,
            onDismiss = { selectedPeerForVerify = null },
            onToggleVerified = { verified ->
                viewModel.verifyPeer(peer.deviceId, verified)
                selectedPeerForVerify = null
            }
        )
    }
}

@Composable
fun PeerListItem(
    peer: PeerEntity,
    onChatClick: () -> Unit,
    onVerifyClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .clickable(onClick = onChatClick)
            .testTag("peer_item_${peer.deviceId}"),
        leadingContent = {
            Surface(
                modifier = Modifier.size(46.dp),
                shape = CircleShape,
                color = if (peer.transport == TransportType.BLUETOOTH)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (peer.transport == TransportType.BLUETOOTH)
                            Icons.Default.Bluetooth
                        else
                            Icons.Default.Wifi,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = peer.displayName, fontWeight = FontWeight.SemiBold)
                if (peer.isVerified) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = "Verified Identity",
                        tint = MeshAccentCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        },
        supportingContent = {
            Column {
                Text(
                    text = "ID: ${peer.deviceId.take(14)} • ${peer.transport.name}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Signal: ${peer.signalStrength} dBm • State: ${peer.connectionState}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onVerifyClick) {
                    Icon(
                        imageVector = if (peer.isVerified) Icons.Default.Shield else Icons.Default.Security,
                        contentDescription = "Verify Safety Fingerprint",
                        tint = if (peer.isVerified) MeshAccentGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = onChatClick,
                    modifier = Modifier.testTag("start_chat_${peer.deviceId}")
                ) {
                    Text("Chat")
                }
            }
        }
    )
}

@Composable
fun PeerVerificationDialog(
    peer: PeerEntity,
    onDismiss: () -> Unit,
    onToggleVerified: (Boolean) -> Unit
) {
    val fingerprint = peer.fingerprint.ifBlank { "7A9F-B23E-10C4-88D1" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = MeshAccentCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Peer Identity Verification")
            }
        },
        text = {
            Column {
                Text(
                    text = "Verify ${peer.displayName}'s identity by comparing this safety number in person or over an out-of-band channel:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = fingerprint,
                        modifier = Modifier.padding(14.dp),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Comparing cryptographic fingerprints protects you against man-in-the-middle attacks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onToggleVerified(!peer.isVerified) }
            ) {
                Text(if (peer.isVerified) "Unmark Verified" else "Mark as Verified")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
