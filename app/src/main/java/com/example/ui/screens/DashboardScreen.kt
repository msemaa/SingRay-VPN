package com.example.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
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
    onRequestConnect: () -> Unit = { viewModel.connectOrDisconnect() }
) {
    val context = LocalContext.current
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()
    val trafficStats by viewModel.trafficStats.collectAsStateWithLifecycle()
    val smartPingEnabled by viewModel.smartPingEnabled.collectAsStateWithLifecycle()
    val routingMode by viewModel.routingMode.collectAsStateWithLifecycle()
    val coreType by viewModel.coreType.collectAsStateWithLifecycle()
    val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
    val batterySaverEnabled by viewModel.batterySaverEnabled.collectAsStateWithLifecycle()
    val autoSelectStrategy by viewModel.autoSelectStrategy.collectAsStateWithLifecycle()

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

        Spacer(modifier = Modifier.height(22.dp))

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

        Spacer(modifier = Modifier.height(16.dp))

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
                            LatencyBadge(latencyMs = server.lastPingMs)
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
                                .clickable { viewModel.pingSingleServer(server) }
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
