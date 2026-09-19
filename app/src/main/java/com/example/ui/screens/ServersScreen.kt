package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.entity.ServerEntity
import com.example.model.AutoSelectStrategy
import com.example.ui.MainViewModel
import com.example.ui.components.LatencyBadge
import com.example.ui.components.ProtocolBadge
import com.example.ui.dialogs.AddSubscriptionDialog
import com.example.ui.dialogs.ImportTextDialog
import com.example.ui.dialogs.ManualConfigDialog
import com.example.ui.dialogs.TELEGRAM_CHANNEL_URL
import com.example.ui.theme.AppTheme

@Composable
fun ServersScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val allServers: List<ServerEntity> by viewModel.allServers.collectAsStateWithLifecycle()
    val selectedServer: ServerEntity? by viewModel.selectedServer.collectAsStateWithLifecycle()
    val batchPingState by viewModel.batchPingState.collectAsStateWithLifecycle()
    val autoSelectStrategy by viewModel.autoSelectStrategy.collectAsStateWithLifecycle()
    val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
    val allSubscriptions by viewModel.allSubscriptions.collectAsStateWithLifecycle()
    val activeSubscriptionId by viewModel.activeSubscriptionId.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var selectedProtocolFilter by remember { mutableStateOf("ALL") }

    var showImportDialog by remember { mutableStateOf(false) }
    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var showFabMenu by remember { mutableStateOf(false) }

    if (showImportDialog) {
        ImportTextDialog(
            onDismiss = { showImportDialog = false },
            onImport = { text -> viewModel.importConfigText(text) }
        )
    }

    if (showSubscriptionDialog) {
        AddSubscriptionDialog(
            onDismiss = { showSubscriptionDialog = false },
            onAdd = { name, url -> viewModel.addSubscription(name, url) }
        )
    }

    if (showManualDialog) {
        ManualConfigDialog(
            onDismiss = { showManualDialog = false },
            onSave = { entity -> viewModel.selectServer(entity) }
        )
    }

    val filteredServers = allServers.filter { server: ServerEntity ->
        val matchesSub = activeSubscriptionId == null || server.subscriptionId == activeSubscriptionId

        val matchesSearch = searchQuery.isBlank() ||
                server.name.contains(searchQuery, ignoreCase = true) ||
                server.server.contains(searchQuery, ignoreCase = true) ||
                server.sni.contains(searchQuery, ignoreCase = true)

        val matchesProtocol = when (selectedProtocolFilter) {
            "ALL" -> true
            "VLESS" -> server.protocol.equals("vless", ignoreCase = true)
            "HY2" -> server.protocol.equals("hysteria2", ignoreCase = true) || server.protocol.equals("hy2", ignoreCase = true)
            "TROJAN" -> server.protocol.equals("trojan", ignoreCase = true)
            "SS" -> server.protocol.equals("shadowsocks", ignoreCase = true)
            "VMESS" -> server.protocol.equals("vmess", ignoreCase = true)
            "SSH" -> server.protocol.equals("ssh", ignoreCase = true)
            else -> true
        }

        matchesSub && matchesSearch && matchesProtocol
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(14.dp))

            // Header with title, count, and action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SERVER NODES",
                        color = AppTheme.colors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "${filteredServers.size} of ${allServers.size} profiles loaded",
                        color = AppTheme.colors.textSecondary,
                        fontSize = 12.sp
                    )
                }

                // Batch Ping & Telegram Link
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Telegram quick action
                    IconButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_CHANNEL_URL))
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                viewModel.openTelegramDialog()
                            }
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF229ED9).copy(alpha = 0.15f))
                            .border(1.dp, Color(0xFF229ED9).copy(alpha = 0.4f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Telegram",
                            tint = Color(0xFF229ED9),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { viewModel.testAllServersPing() },
                        enabled = !batchPingState.isTesting,
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.accentCyan),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("test_all_ping_button")
                    ) {
                        if (batchPingState.isTesting) {
                            CircularProgressIndicator(
                                color = if (isDarkTheme) Color(0xFF00363D) else Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "${batchPingState.current}/${batchPingState.total}",
                                color = if (isDarkTheme) Color(0xFF00363D) else Color.White,
                                fontSize = 11.sp
                            )
                        } else {
                            Icon(
                                Icons.Default.FlashOn,
                                contentDescription = "Test All",
                                tint = if (isDarkTheme) Color(0xFF00363D) else Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Ping All",
                                color = if (isDarkTheme) Color(0xFF00363D) else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Progress bar if batch testing
            AnimatedVisibility(visible = batchPingState.isTesting) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    val progress = if (batchPingState.total > 0) batchPingState.current.toFloat() / batchPingState.total else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = AppTheme.colors.accentCyan,
                        trackColor = AppTheme.colors.surfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Auto-Select Strategy Pill Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppTheme.colors.surface)
                    .border(1.dp, AppTheme.colors.border, RoundedCornerShape(10.dp))
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AutoSelectStrategy.entries.forEach { strategy ->
                    val isSelected = autoSelectStrategy == strategy
                    val bg = if (isSelected) AppTheme.colors.accentCyan else Color.Transparent
                    val textColor = if (isSelected) (if (isDarkTheme) Color(0xFF00363D) else Color.White) else AppTheme.colors.textSecondary

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(bg)
                            .clickable { viewModel.setAutoSelectStrategy(strategy) }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = strategy.persianTitle,
                            color = textColor,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search by name, IP, or SNI...", color = AppTheme.colors.textMuted, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = AppTheme.colors.textMuted) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = AppTheme.colors.textMuted)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppTheme.colors.accentCyan,
                    unfocusedBorderColor = AppTheme.colors.border,
                    focusedTextColor = AppTheme.colors.textPrimary,
                    unfocusedTextColor = AppTheme.colors.textPrimary,
                    focusedContainerColor = AppTheme.colors.surface,
                    unfocusedContainerColor = AppTheme.colors.surface
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (allSubscriptions.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        val isAll = activeSubscriptionId == null
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isAll) Color(0xFF229ED9).copy(alpha = 0.25f) else AppTheme.colors.surface)
                                .border(1.dp, if (isAll) Color(0xFF229ED9) else AppTheme.colors.border, RoundedCornerShape(8.dp))
                                .clickable { viewModel.setActiveSubscriptionFilter(null) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "🌐 همه اشتراک‌ها",
                                color = if (isAll) Color(0xFF229ED9) else AppTheme.colors.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (isAll) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }

                    items(allSubscriptions) { sub ->
                        val isThis = activeSubscriptionId == sub.id
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isThis) Color(0xFF229ED9).copy(alpha = 0.25f) else AppTheme.colors.surface)
                                .border(1.dp, if (isThis) Color(0xFF229ED9) else AppTheme.colors.border, RoundedCornerShape(8.dp))
                                .clickable { viewModel.setActiveSubscriptionFilter(sub.id) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "📂 ${sub.name} (${sub.totalNodes})",
                                color = if (isThis) Color(0xFF229ED9) else AppTheme.colors.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (isThis) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
            }

            // Protocol Filter Chips
            val protocolChips = listOf("ALL", "VLESS", "HY2", "TROJAN", "SS", "VMESS", "SSH")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(protocolChips) { chip ->
                    val isSelected = selectedProtocolFilter == chip
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) AppTheme.colors.accentCyan.copy(alpha = 0.2f) else AppTheme.colors.surface)
                            .border(1.dp, if (isSelected) AppTheme.colors.accentCyan else AppTheme.colors.border, RoundedCornerShape(8.dp))
                            .clickable { selectedProtocolFilter = chip }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = chip,
                            color = if (isSelected) AppTheme.colors.accentCyan else AppTheme.colors.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Server List
            if (filteredServers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No servers found",
                            color = AppTheme.colors.textMuted,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap + below to import configs or subscriptions",
                            color = AppTheme.colors.accentCyan,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(filteredServers, key = { it.id }) { server ->
                        val isCurrent = selectedServer?.id == server.id
                        ServerCardItem(
                            server = server,
                            isSelected = isCurrent,
                            onSelect = { viewModel.selectServer(server) },
                            onTestPing = { viewModel.pingSingleServer(server) },
                            onDelete = { viewModel.deleteServer(server) },
                            onCopyLink = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val linkText = if (server.rawUri.isNotBlank()) server.rawUri else "${server.protocol}://${server.server}:${server.port}"
                                val clip = ClipData.newPlainText("Proxy Link", linkText)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(80.dp)) // padding for FAB
                    }
                }
            }
        }

        // Floating Action Button for Importing
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            FloatingActionButton(
                onClick = { showFabMenu = true },
                containerColor = AppTheme.colors.accentCyan,
                contentColor = if (isDarkTheme) Color(0xFF00363D) else Color.White,
                shape = CircleShape,
                modifier = Modifier.testTag("import_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Node / Sub")
            }

            DropdownMenu(
                expanded = showFabMenu,
                onDismissRequest = { showFabMenu = false },
                modifier = Modifier.background(AppTheme.colors.surface)
            ) {
                DropdownMenuItem(
                    text = { Text("Import from Clipboard", color = AppTheme.colors.textPrimary) },
                    onClick = {
                        showFabMenu = false
                        // Auto-paste: read the clipboard immediately and import it.
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clipText = clipboard.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                            ?.trim()
                            .orEmpty()

                        if (clipText.isBlank()) {
                            Toast.makeText(context, "کلیپ‌بورد خالی است - متن کانفیگ را وارد کنید", Toast.LENGTH_SHORT).show()
                            showImportDialog = true
                        } else {
                            val found = com.example.core.parser.ConfigParser.parseContent(clipText).size
                            if (found > 0) {
                                viewModel.importConfigText(clipText)
                                Toast.makeText(context, "$found کانفیگ از کلیپ‌بورد اضافه شد", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "کانفیگ معتبری در کلیپ‌بورد نبود", Toast.LENGTH_SHORT).show()
                                showImportDialog = true
                            }
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Add Subscription URL", color = AppTheme.colors.textPrimary) },
                    onClick = {
                        showFabMenu = false
                        showSubscriptionDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Manual Config Builder", color = AppTheme.colors.textPrimary) },
                    onClick = {
                        showFabMenu = false
                        showManualDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Auto-Select Best Profile", color = AppTheme.colors.accentCyan) },
                    onClick = {
                        showFabMenu = false
                        viewModel.applyAutoSelectStrategy()
                    }
                )
            }
        }
    }
}

@Composable
fun ServerCardItem(
    server: ServerEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onTestPing: () -> Unit,
    onDelete: () -> Unit,
    onCopyLink: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) AppTheme.colors.surfaceVariant else AppTheme.colors.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) AppTheme.colors.accentCyan else AppTheme.colors.border
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .testTag("server_item_${server.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Radio selection icon
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (isSelected) "Selected" else "Not selected",
                tint = if (isSelected) AppTheme.colors.accentCyan else AppTheme.colors.textMuted,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Main info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = server.name,
                        color = AppTheme.colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProtocolBadge(server.getProtocolEnum())
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${server.server}:${server.port}",
                        color = AppTheme.colors.textSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Latency badge and ping button
            Row(verticalAlignment = Alignment.CenterVertically) {
                LatencyBadge(latencyMs = server.lastPingMs)

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(
                    onClick = onTestPing,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FlashOn,
                        contentDescription = "Test Ping",
                        tint = AppTheme.colors.accentCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = AppTheme.colors.textMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(AppTheme.colors.surface)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy Link / Config", color = AppTheme.colors.textPrimary) },
                            onClick = {
                                showMenu = false
                                onCopyLink()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Node", color = AppTheme.colors.accentRuby) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}
