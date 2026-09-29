package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.model.DeliveryStatus
import com.example.database.MessageEntity
import com.example.ui.MeshChatViewModel
import com.example.ui.theme.MeshAccentAmber
import com.example.ui.theme.MeshAccentCyan
import com.example.ui.theme.MeshAccentGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    peerDeviceId: String,
    peerDisplayName: String,
    viewModel: MeshChatViewModel,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    LaunchedEffect(conversationId) {
        viewModel.openConversation(conversationId)
    }

    val messages by viewModel.activeConversationMessages.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var textInput by remember { mutableStateOf("") }
    var selectedMessageForDetails by remember { mutableStateOf<MessageEntity?>(null) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val onSendMessage = {
        if (textInput.isNotBlank()) {
            viewModel.sendMessage(textInput.trim(), peerDeviceId)
            textInput = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("chat_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = peerDisplayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "End-to-End Encrypted",
                                tint = MeshAccentCyan,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = "Node ID: ${peerDeviceId.take(16)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input"),
                        placeholder = { Text("Write offline mesh message...") },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = { onSendMessage() }
                        ),
                        trailingIcon = {
                            if (textInput.isNotEmpty()) {
                                IconButton(onClick = { textInput = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear input",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FloatingActionButton(
                        onClick = onSendMessage,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("chat_send_button"),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send Message",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.EnhancedEncryption,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MeshAccentCyan
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Encrypted Local Channel",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Messages are locally sealed with AES-GCM-256. Intermediate mesh hops cannot read your messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    reverseLayout = false
                ) {
                    items(messages, key = { it.messageId }) { msg ->
                        MessageBubble(
                            message = msg,
                            onClick = { selectedMessageForDetails = msg }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }
    }

    selectedMessageForDetails?.let { msg ->
        MessageDetailsDialog(message = msg, onDismiss = { selectedMessageForDetails = null })
    }
}

@Composable
fun MessageBubble(
    message: MessageEntity,
    onClick: () -> Unit
) {
    val isOutgoing = message.isOutgoing
    val alignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    val bgColor = if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isOutgoing) 16.dp else 4.dp,
                        bottomEnd = if (isOutgoing) 4.dp else 16.dp
                    )
                )
                .background(bgColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = message.plaintext,
                color = textColor,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeFormat.format(Date(message.createdAt)),
                    fontSize = 11.sp,
                    color = textColor.copy(alpha = 0.7f)
                )
                if (isOutgoing) {
                    Spacer(modifier = Modifier.width(4.dp))
                    DeliveryStatusBadge(status = message.state, hops = message.relayHops)
                }
            }
        }
    }
}

@Composable
fun DeliveryStatusBadge(status: DeliveryStatus, hops: String) {
    when (status) {
        DeliveryStatus.QUEUED -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = "Queued locally",
                    modifier = Modifier.size(13.dp),
                    tint = MeshAccentAmber
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text("Queued", fontSize = 10.sp, color = MeshAccentAmber)
            }
        }
        DeliveryStatus.DISCOVERING, DeliveryStatus.ROUTING -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Route,
                    contentDescription = "Routing through mesh",
                    modifier = Modifier.size(13.dp),
                    tint = MeshAccentCyan
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text("Routing", fontSize = 10.sp, color = MeshAccentCyan)
            }
        }
        DeliveryStatus.SENDING -> {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = "Transmitting",
                modifier = Modifier.size(13.dp),
                tint = MeshAccentCyan
            )
        }
        DeliveryStatus.RELAYED -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SyncAlt,
                    contentDescription = "Relayed",
                    modifier = Modifier.size(13.dp),
                    tint = MeshAccentGreen
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text("Relayed", fontSize = 10.sp, color = MeshAccentGreen)
            }
        }
        DeliveryStatus.DELIVERED -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Delivered to recipient",
                modifier = Modifier.size(14.dp),
                tint = MeshAccentGreen
            )
        }
        DeliveryStatus.READ -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Read",
                modifier = Modifier.size(14.dp),
                tint = MeshAccentCyan
            )
        }
        DeliveryStatus.FAILED, DeliveryStatus.EXPIRED -> {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = "Failed",
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun MessageDetailsDialog(message: MessageEntity, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mesh Envelope Details") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                DetailRow("Message ID", message.messageId.take(16))
                DetailRow("Status", message.state.name)
                DetailRow("Hops Count", "${message.hopCount}")
                DetailRow("TTL Limit", "${message.ttl}")
                if (message.relayHops.isNotBlank()) {
                    DetailRow("Relay Path", message.relayHops)
                }
                DetailRow("Encryption", "AES-256-GCM (Authenticated)")
                message.nonce?.let {
                    DetailRow("IV / Nonce", it.take(6).joinToString("") { b -> "%02X".format(b) })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
