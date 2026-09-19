package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
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
import com.example.model.ConnectionStatus
import com.example.ui.MainViewModel
import com.example.ui.components.LatencyBadge
import com.example.ui.components.ProtocolBadge
import com.example.ui.dialogs.TELEGRAM_CHANNEL_URL
import com.example.ui.dialogs.ViewSingBoxJsonDialog
import com.example.ui.theme.AppTheme
import java.text.DecimalFormat

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigateToServers: () -> Unit,
    onNavigateToRouting: () -> Unit,
    onNavigateToConsole: () -> Unit = {},
    onRequestConnect: () -> Unit = { viewModel.connectOrDisconnect() }
) {
    val context = LocalContext.current
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()
    val trafficStats by viewModel.trafficStats.collectAsStateWithLifecycle()
    val smartPingEnabled by viewModel.smartPingEnabled.collectAsStateWithLifecycle()
    val routingMode by viewModel.routingMode.collectAsStateWithLifecycle()
    val coreType by viewModel.coreType.collectAsStateWithLifecycle()
    val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
    val batterySaverEnabled by viewModel.batterySaverEnabled.collectAsStateWithLifecycle()
    val autoSelectStrategy by viewModel.autoSelectStrategy.collectAsStateWithLifecycle()
    val allSubscriptions by viewModel.allSubscriptions.collectAsStateWithLifecycle()
    val activeSubscriptionId by viewModel.activeSubscriptionId.collectAsStateWithLifecycle()

    var showJsonDialog by remember { mutableStateOf(false) }

    if (showJsonDialog) {
        ViewSingBoxJsonDialog(
            jsonConfig = viewModel.generateCurrentSingBoxJson(),
            onDismiss = { showJsonDialog = false }
        )
    }

    val isConnected = connectionStatus == ConnectionStatus.CONNECTED
    val isConnecting = connectionStatus == ConnectionStatus.CONNECTING

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnecting) 1.08f else if (isConnected) 1.03f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        // Engineering Top Bar Status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "SINGRAY",
                        color = AppTheme.colors.accentCyan,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppTheme.colors.surfaceVariant)
                            .border(0.75.dp, AppTheme.colors.border, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = coreType,
                            color = AppTheme.colors.textMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Text(
                    text = "Xray / Sing-box • Multi-Protocol Engine",
                    color = AppTheme.colors.textSecondary,
                    fontSize = 11.sp
                )
            }

            // Top action buttons: Telegram Channel & Dark/Light Mode Switcher
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Telegram quick button
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
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF229ED9).copy(alpha = 0.15f))
                        .border(1.dp, Color(0xFF229ED9).copy(alpha = 0.4f), CircleShape)
                        .testTag("telegram_top_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Mirovex Telegram",
                        tint = Color(0xFF229ED9),
                        modifier = Modifier.size(17.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Theme Mode Switcher
                IconButton(
                    onClick = { viewModel.toggleDarkTheme() },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.surfaceVariant)
                        .border(1.dp, AppTheme.colors.border, CircleShape)
                        .testTag("theme_toggle_button")
                ) {
                    Icon(
                        imageVector = if (isDarkTheme) Icons.Default.Brightness7 else Icons.Default.Brightness4,
                        contentDescription = "Toggle Theme",
                        tint = AppTheme.colors.accentCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Route Mode Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(AppTheme.colors.surface)
                        .border(1.dp, AppTheme.colors.border, RoundedCornerShape(20.dp))
                        .clickable { onNavigateToRouting() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Routing",
                            tint = AppTheme.colors.accentCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = routingMode.title,
                            color = AppTheme.colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Connection Error Card (dismissible, showing exact message, chosen core, and View Logs action)
        AnimatedVisibility(visible = !lastError.isNullOrBlank()) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C1014)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE53935).copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag("connection_error_card")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Error",
                                tint = Color(0xFFEF5350),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Connection Failed",
                                color = Color(0xFFEF5350),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(
                            onClick = { viewModel.dismissLastError() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = Color(0xFFEF5350).copy(alpha = 0.8f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = lastError.orEmpty(),
                        color = Color(0xFFECEFF1),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                viewModel.dismissLastError()
                                onNavigateToConsole()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp).testTag("view_logs_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = Color(0xFF80D8FF),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "View Logs",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF80D8FF)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.dismissLastError() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp).testTag("dismiss_error_button")
                        ) {
                            Text(
                                text = "Dismiss",
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Center Precision Telemetry Dial
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            val ringColor = when {
                isConnected -> AppTheme.colors.accentMint
                isConnecting -> AppTheme.colors.accentCyan
                else -> AppTheme.colors.textMuted.copy(alpha = 0.4f)
            }

            Box(
                modifier = Modifier
                    .size(204.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                ringColor.copy(alpha = 0.22f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Tactile Circular Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(164.dp)
                    .clip(CircleShape)
                    .background(AppTheme.colors.surface)
                    .border(2.5.dp, ringColor, CircleShape)
                    .clickable { onRequestConnect() }
                    .testTag("core_power_button")
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Toggle Connection",
                        tint = ringColor,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when (connectionStatus) {
                            ConnectionStatus.CONNECTED -> "CONNECTED"
                            ConnectionStatus.CONNECTING -> "CONNECTING"
                            ConnectionStatus.DISCONNECTING -> "STOPPING"
                            ConnectionStatus.TESTING -> "TESTING"
                            ConnectionStatus.DISCONNECTED -> "CONNECT"
                            else -> "CONNECT"
                        },
                        color = ringColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    if (isConnected) {
                        Text(
                            text = formatDuration(trafficStats.durationSeconds),
                            color = AppTheme.colors.textMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ACTIVE PROXY NODE CARD (Shows exactly which node is connected and from which subscription)
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface),
            border = androidx.compose.foundation.BorderStroke(
                1.5.dp,
                if (isConnected) AppTheme.colors.accentMint else AppTheme.colors.accentCyan.copy(alpha = 0.45f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToServers() }
                .testTag("active_node_card")
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) AppTheme.colors.accentMint else if (isConnecting) AppTheme.colors.accentCyan else AppTheme.colors.textMuted)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isConnected) "پروفایل متصل (ACTIVE)" else "پروفایل انتخابی (SELECTED)",
                            color = if (isConnected) AppTheme.colors.accentMint else AppTheme.colors.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }

                    // Click to browse or change server
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AppTheme.colors.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "تغییر سرور",
                            color = AppTheme.colors.accentCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            tint = AppTheme.colors.accentCyan,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (selectedServer != null) {
                    val server = selectedServer!!
                    val parentSub = allSubscriptions.find { it.id == server.subscriptionId }

                    Text(
                        text = server.name,
                        color = AppTheme.colors.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Protocol badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(AppTheme.colors.accentCyan.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = server.protocol.uppercase(),
                                color = AppTheme.colors.accentCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Host:Port badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(AppTheme.colors.surfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${server.server}:${server.port}",
                                color = AppTheme.colors.textSecondary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Subscription badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF229ED9).copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = parentSub?.let { "📂 ${it.name}" } ?: "📁 نود دستی",
                                color = Color(0xFF229ED9),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Real Latency display
                        if (server.lastPingMs > 0) {
                            val pingColor = when {
                                server.lastPingMs < 100 -> AppTheme.colors.accentMint
                                server.lastPingMs < 200 -> AppTheme.colors.accentAmber
                                else -> AppTheme.colors.accentRuby
                            }
                            Text(
                                text = "${server.lastPingMs} ms",
                                color = pingColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            Text(
                                text = "تست نشده",
                                color = AppTheme.colors.textMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = AppTheme.colors.accentAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "هیچ سروری انتخاب نشده است! برای انتخاب نود کلیک کنید",
                            color = AppTheme.colors.accentAmber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Quick 1-Click Telegram Proxy Enabler (local SOCKS5 inbound)
        if (isConnected) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0E2238)),
                border = androidx.compose.foundation.BorderStroke(1.2.dp, Color(0xFF229ED9)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://socks?server=127.0.0.1&port=10808"))
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "پروکسی محلی روی 127.0.0.1:10808 فعال است", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .testTag("telegram_proxy_enabler_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF229ED9).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                tint = Color(0xFF229ED9),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "اتصال فوری تلگرام (پروکسی داخلی برنامه)",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "لمس کنید تا تلگرام مستقیماً به نود فعال وصل شود",
                                color = AppTheme.colors.textSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF229ED9))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "اتصال به تلگرام",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        // Mirovex Official Telegram Channel Banner
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF229ED9).copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_CHANNEL_URL))
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        viewModel.openTelegramDialog()
                    }
                }
                .testTag("mirovex_channel_banner")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF229ED9).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = Color(0xFF229ED9),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "کانال رسمی Mirovex",
                                color = AppTheme.colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "@MirovexOfficial",
                                color = Color(0xFF229ED9),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "دریافت آخرین سرورهای اختصاصی و کانفیگ‌های پرسرعت",
                            color = AppTheme.colors.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF229ED9))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "ورود",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Realtime Throughput HUD (Upload & Download Telemetry)
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LIVE TELEMETRY",
                        color = AppTheme.colors.textMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Throughput",
                            tint = if (isConnected) AppTheme.colors.accentMint else AppTheme.colors.textMuted,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isConnected) "ACTIVE TUNNEL" else "STANDBY",
                            color = if (isConnected) AppTheme.colors.accentMint else AppTheme.colors.textMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    // Download Gauge
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "↓", color = AppTheme.colors.accentCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "DOWNLOAD", color = AppTheme.colors.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        Text(
                            text = formatBytesPerSec(trafficStats.downloadSpeedBytes),
                            color = AppTheme.colors.textPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Total: ${formatTotalBytes(trafficStats.totalDownloadBytes)}",
                            color = AppTheme.colors.textMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(50.dp)
                            .background(AppTheme.colors.border)
                    )

                    // Upload Gauge
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "↑", color = AppTheme.colors.accentMint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "UPLOAD", color = AppTheme.colors.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        Text(
                            text = formatBytesPerSec(trafficStats.uploadSpeedBytes),
                            color = AppTheme.colors.textPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Total: ${formatTotalBytes(trafficStats.totalUploadBytes)}",
                            color = AppTheme.colors.textMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Smart Profile Selection: Auto-Select by Criteria (Ping, Speed, Packet Loss)
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = AppTheme.colors.accentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "اتصال هوشمند به بهترین پروفایل",
                            color = AppTheme.colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Auto Connect Action Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AppTheme.colors.accentCyan.copy(alpha = 0.15f))
                            .border(1.dp, AppTheme.colors.accentCyan.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .clickable { viewModel.applyAutoSelectStrategy() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("auto_select_action_button")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FlashOn, contentDescription = null, tint = AppTheme.colors.accentCyan, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "اتصال خودکار",
                                color = AppTheme.colors.accentCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Criteria Selector: Minimum Ping, Maximum Speed, Lowest Packet Loss
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AutoSelectStrategy.entries.forEach { strategy ->
                        val isSelected = autoSelectStrategy == strategy
                        val activeBg = if (isSelected) AppTheme.colors.accentCyan else AppTheme.colors.surfaceVariant
                        val activeText = if (isSelected) (if (isDarkTheme) Color(0xFF00363D) else Color.White) else AppTheme.colors.textSecondary

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(activeBg)
                                .border(
                                    1.dp,
                                    if (isSelected) AppTheme.colors.accentCyan else AppTheme.colors.border,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { viewModel.setAutoSelectStrategy(strategy) }
                                .padding(vertical = 7.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = strategy.persianTitle,
                                    color = activeText,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = autoSelectStrategy.subtitle,
                    color = AppTheme.colors.textMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )

                if (allSubscriptions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))

                    // Subscription Scope Selector (sub-scoped auto switch)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.FilterAlt,
                                contentDescription = null,
                                tint = Color(0xFF229ED9),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "دامنه سوئیچ خودکار:",
                                color = AppTheme.colors.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Reset or Active Scope Indicator
                        if (activeSubscriptionId != null) {
                            val activeSubName = allSubscriptions.find { it.id == activeSubscriptionId }?.name ?: "ساب انتخاب شده"
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF229ED9).copy(alpha = 0.15f))
                                    .clickable { viewModel.setActiveSubscriptionFilter(null) }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "فقط $activeSubName",
                                    color = Color(0xFF229ED9),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear Scope",
                                    tint = Color(0xFF229ED9),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        } else {
                            Text(
                                text = "همه سرورها",
                                color = AppTheme.colors.textMuted,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Quick Chips for Subscriptions
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            val isAll = activeSubscriptionId == null
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isAll) AppTheme.colors.accentCyan.copy(alpha = 0.2f) else AppTheme.colors.surfaceVariant)
                                    .border(0.75.dp, if (isAll) AppTheme.colors.accentCyan else AppTheme.colors.border, RoundedCornerShape(6.dp))
                                    .clickable { viewModel.setActiveSubscriptionFilter(null) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "🌐 همه نودها",
                                    color = if (isAll) AppTheme.colors.accentCyan else AppTheme.colors.textSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = if (isAll) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }

                        items(allSubscriptions) { sub ->
                            val isThis = activeSubscriptionId == sub.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isThis) Color(0xFF229ED9).copy(alpha = 0.2f) else AppTheme.colors.surfaceVariant)
                                    .border(0.75.dp, if (isThis) Color(0xFF229ED9) else AppTheme.colors.border, RoundedCornerShape(6.dp))
                                    .clickable { viewModel.setActiveSubscriptionFilter(sub.id) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "📂 ${sub.name} (${sub.totalNodes} نود)",
                                    color = if (isThis) Color(0xFF229ED9) else AppTheme.colors.textSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = if (isThis) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Battery & RAM Optimization (Eco Background Profile)
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (batterySaverEnabled) AppTheme.colors.accentMint.copy(alpha = 0.15f) else AppTheme.colors.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.BatterySaver,
                            contentDescription = "Battery Saver",
                            tint = if (batterySaverEnabled) AppTheme.colors.accentMint else AppTheme.colors.textMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "بهینه‌ساز باتری و رم (Eco Profile)",
                                color = AppTheme.colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            if (batterySaverEnabled) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(AppTheme.colors.accentMint.copy(alpha = 0.15f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "کاهش مصرف ۶۶٪",
                                        color = AppTheme.colors.accentMint,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Text(
                            text = "کاهش بیدارباش‌های CPU و مانیتورینگ سبک در پس‌زمینه",
                            color = AppTheme.colors.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Switch(
                    checked = batterySaverEnabled,
                    onCheckedChange = { viewModel.toggleBatterySaver(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = if (isDarkTheme) Color(0xFF003822) else Color.White,
                        checkedTrackColor = AppTheme.colors.accentMint,
                        uncheckedThumbColor = AppTheme.colors.textMuted,
                        uncheckedTrackColor = AppTheme.colors.surfaceVariant
                    ),
                    modifier = Modifier.testTag("battery_saver_switch")
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Active Server Information Card
        val server: ServerEntity? = selectedServer
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.border),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToServers() }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "ACTIVE PROFILE",
                            color = AppTheme.colors.textMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (server != null) {
                            ProtocolBadge(server.getProtocolEnum())
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { showJsonDialog = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Code, contentDescription = "View Sing-box Config", tint = AppTheme.colors.accentCyan)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { onNavigateToServers() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.SwapVert, contentDescription = "Switch Server", tint = AppTheme.colors.textMuted)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (server != null) {
                    Text(
                        text = server.name,
                        color = AppTheme.colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${server.server}:${server.port}" + if (server.sni.isNotBlank()) " • SNI: ${server.sni}" else "",
                        color = AppTheme.colors.textSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LatencyBadge(latencyMs = server.lastPingMs, isRealDelay = server.isRealDelay)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (server.security.isNotBlank() && server.security != "none") "• ${server.security.uppercase()}" else "",
                                color = AppTheme.colors.textMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Test Ping button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AppTheme.colors.surfaceVariant)
                                .clickable { viewModel.realDelayTest(server) }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.FlashOn, contentDescription = "Test Ping", tint = AppTheme.colors.accentCyan, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Ping Test", color = AppTheme.colors.accentCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else {
                    Text(
                        text = "No server profile selected",
                        color = AppTheme.colors.textMuted,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Tap to choose a node or import subscription",
                        color = AppTheme.colors.accentCyan,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private fun formatBytesPerSec(bytes: Long): String {
    val df = DecimalFormat("#.#")
    return when {
        bytes >= 1_000_000 -> "${df.format(bytes / 1_000_000.0)} MB/s"
        bytes >= 1_000 -> "${df.format(bytes / 1_000.0)} KB/s"
        else -> "$bytes B/s"
    }
}

private fun formatTotalBytes(bytes: Long): String {
    val df = DecimalFormat("#.#")
    return when {
        bytes >= 1_000_000_000 -> "${df.format(bytes / 1_000_000_000.0)} GB"
        bytes >= 1_000_000 -> "${df.format(bytes / 1_000_000.0)} MB"
        bytes >= 1_000 -> "${df.format(bytes / 1_000.0)} KB"
        else -> "$bytes B"
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return String.format("%02d:%02d:%02d", hours, minutes, secs)
}
