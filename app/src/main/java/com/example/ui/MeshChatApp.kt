package com.example.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.*

sealed class Screen {
    data object Home : Screen()
    data class Chat(val conversationId: String, val peerDeviceId: String, val peerDisplayName: String) : Screen()
    data object NearbyPeers : Screen()
    data object Transports : Screen()
    data object Security : Screen()
    data object Simulation : Screen()
}

@Composable
fun MeshChatApp(
    viewModel: MeshChatViewModel = viewModel()
) {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    // Required permissions depending on Android version
    val requiredPermissions = remember {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        list.toTypedArray()
    }

    var hasAllPermissions by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PermissionChecker.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasAllPermissions = results.values.all { it }
        if (hasAllPermissions) {
            viewModel.setTransportMode(viewModel.activeTransportMode.value)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasAllPermissions) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!hasAllPermissions) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Radio permissions required for peer discovery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { permissionLauncher.launch(requiredPermissions) },
                        modifier = Modifier.testTag("grant_permissions_button")
                    ) {
                        Text("Grant", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        when (val screen = currentScreen) {
            is Screen.Home -> {
                HomeScreen(
                    viewModel = viewModel,
                    onOpenChat = { convId, peerId, name ->
                        currentScreen = Screen.Chat(convId, peerId, name)
                    },
                    onNavigatePeers = { currentScreen = Screen.NearbyPeers },
                    onNavigateTransports = { currentScreen = Screen.Transports },
                    onNavigateSecurity = { currentScreen = Screen.Security },
                    onNavigateSimulation = { currentScreen = Screen.Simulation }
                )
            }
            is Screen.Chat -> {
                ChatScreen(
                    conversationId = screen.conversationId,
                    peerDeviceId = screen.peerDeviceId,
                    peerDisplayName = screen.peerDisplayName,
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.Home }
                )
            }
            is Screen.NearbyPeers -> {
                NearbyPeersScreen(
                    viewModel = viewModel,
                    onStartChat = { peer ->
                        viewModel.startConversationWithPeer(peer)
                        currentScreen = Screen.Chat("conv_${peer.deviceId}", peer.deviceId, peer.displayName)
                    },
                    onBack = { currentScreen = Screen.Home }
                )
            }
            is Screen.Transports -> {
                TransportStatusScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.Home }
                )
            }
            is Screen.Security -> {
                SecurityIdentityScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.Home }
                )
            }
            is Screen.Simulation -> {
                SimulationScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.Home }
                )
            }
        }
    }
}
