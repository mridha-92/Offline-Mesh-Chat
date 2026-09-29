package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.simulation.SimulatedHopLog
import com.example.ui.MeshChatViewModel
import com.example.ui.theme.MeshAccentCyan
import com.example.ui.theme.MeshAccentGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulationScreen(
    viewModel: MeshChatViewModel,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val simResult by viewModel.latestSimulationResult.collectAsStateWithLifecycle()
    val stressMetrics by viewModel.stressTestMetrics.collectAsStateWithLifecycle()
    val isSimulating by viewModel.isSimulating.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("sim_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Mesh Multi-Hop Studio", fontWeight = FontWeight.Bold) }
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
                text = "Critical Acceptance Test: A → B → C → D",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Validates store-and-forward relay when Node D is out of direct radio range of Node A. Intermediate nodes B & C only see encrypted ciphertext and cannot access plaintext.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))

            // Test trigger buttons for Transports
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.runAcceptanceTest("BLUETOOTH") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("run_test_bluetooth"),
                    enabled = !isSimulating
                ) {
                    Text("Bluetooth", fontSize = 12.sp)
                }
                Button(
                    onClick = { viewModel.runAcceptanceTest("WI_FI_DIRECT") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("run_test_wifidirect"),
                    enabled = !isSimulating
                ) {
                    Text("Wi-Fi Direct", fontSize = 12.sp)
                }
                Button(
                    onClick = { viewModel.runAcceptanceTest("MIXED") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("run_test_mixed"),
                    enabled = !isSimulating
                ) {
                    Text("Mixed", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isSimulating) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            // Results Card
            simResult?.let { res ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (res.success) MeshAccentGreen.copy(alpha = 0.12f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = res.scenarioName,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (res.success) MeshAccentGreen else MaterialTheme.colorScheme.error
                            ) {
                                Text(
                                    text = if (res.success) "PASSED" else "FAILED",
                                    color = MaterialTheme.colorScheme.surface,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Sent Plaintext: \"${res.sentPlaintext}\"", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Received by Node D: \"${res.receivedPlaintext.orEmpty()}\"",
                            fontWeight = FontWeight.SemiBold,
                            color = MeshAccentGreen
                        )
                        Text(
                            text = "End-to-End ACK Received by A: ${if (res.ackReceivedBySender) "YES (Delivered)" else "NO"}",
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Execution Latency: ${res.executionTimeMs} ms • Total Hops: ${res.totalHops}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Hop Traversal & Intermediate Privacy Audit:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        res.hops.forEach { hop ->
                            HopItemCard(hop)
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Stress Testing Section
            Text(
                text = "Mesh Stress & Scale Testing",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Evaluates concurrent store-and-forward mesh gossip across multi-node topologies.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.runStressTest(20) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("stress_20_nodes"),
                    enabled = !isSimulating
                ) {
                    Text("20 Nodes")
                }
                OutlinedButton(
                    onClick = { viewModel.runStressTest(50) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("stress_50_nodes"),
                    enabled = !isSimulating
                ) {
                    Text("50 Nodes")
                }
                OutlinedButton(
                    onClick = { viewModel.runStressTest(100) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("stress_100_nodes"),
                    enabled = !isSimulating
                ) {
                    Text("100 Nodes")
                }
            }

            stressMetrics?.let { metrics ->
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Stress Test Results (${metrics["nodeCount"]} Nodes)",
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = "Delivery Rate: ${metrics["deliveryRate"]}", fontWeight = FontWeight.SemiBold, color = MeshAccentGreen)
                        Text(text = "Average Hops: ${metrics["averageHops"]}", fontSize = 12.sp)
                        Text(text = "Successful / Total: ${metrics["successfulDeliveries"]} / ${metrics["totalAttempts"]}", fontSize = 12.sp)
                        Text(text = "Runtime: ${metrics["elapsedMs"]} ms", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun HopItemCard(hop: SimulatedHopLog) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${hop.fromNode} ➔ ${hop.toNode}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = hop.transport.name,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MeshAccentCyan
                )
            }
            Text(
                text = "Hop #${hop.hopCount} • TTL Remaining: ${hop.ttl}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Payload Content at Node: ${hop.interceptedPayloadPreview}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = if (hop.isPayloadEncrypted) MaterialTheme.colorScheme.onSurfaceVariant else MeshAccentGreen
            )
        }
    }
}
