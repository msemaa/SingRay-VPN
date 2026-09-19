package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.core.ping.PingDiagnosticResult
import com.example.model.CoreLog
import com.example.service.SingRayVpnService
import com.example.ui.MainViewModel
import com.example.ui.dialogs.ViewSingBoxJsonDialog
import com.example.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(viewModel: MainViewModel, initialTab: Int = 0) {
    val context = LocalContext.current
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab) }
    val logs: List<CoreLog> by viewModel.coreLogs.collectAsStateWithLifecycle()
    val diagnosticResult: PingDiagnosticResult? by viewModel.diagnosticResult.collectAsStateWithLifecycle()
    val isTestingDiag: Boolean by viewModel.isTestingDiagnostic.collectAsStateWithLifecycle()
    val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()

    var targetHost by remember { mutableStateOf("1.1.1.1") }
    var targetPort by remember { mutableStateOf("443") }
    var showJsonDialog by remember { mutableStateOf(false) }

    if (showJsonDialog) {
        ViewSingBoxJsonDialog(
            jsonConfig = viewModel.generateCurrentSingBoxJson(),
            onDismiss = { showJsonDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "DIAGNOSTICS & CONSOLE",
                    color = AppTheme.colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Network latency analysis & runtime core logs",
                    color = AppTheme.colors.textSecondary,
                    fontSize = 12.sp
                )
            }

            Button(
                onClick = { showJsonDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.surfaceVariant),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Code, contentDescription = "JSON", tint = AppTheme.colors.accentCyan, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Config JSON", color = AppTheme.colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Tabs: 0: Handshake Ping Diagnostics, 1: Live Core Console
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = AppTheme.colors.surface,
            contentColor = AppTheme.colors.accentCyan,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = AppTheme.colors.accentCyan
                )
            },
            divider = {}
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("PING DIAGNOSTICS", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("CORE LOGS", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (selectedTab == 0) {
            // Handshake & Jitter Diagnostic Tool
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "TCP Handshake & Jitter Analyzer",
                            color = AppTheme.colors.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Sends multi-packet socket handshakes to compute round-trip delay, jitter, and packet loss.",
                            color = AppTheme.colors.textSecondary,
                            fontSize = 11.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = targetHost,
                                onValueChange = { targetHost = it },
                                label = { Text("Target Host / IP") },
                                singleLine = true,
                                modifier = Modifier.weight(2f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AppTheme.colors.accentCyan,
                                    unfocusedBorderColor = AppTheme.colors.border,
                                    focusedTextColor = AppTheme.colors.textPrimary,
                                    unfocusedTextColor = AppTheme.colors.textPrimary,
                                    focusedContainerColor = AppTheme.colors.surface,
                                    unfocusedContainerColor = AppTheme.colors.surface
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedTextField(
                                value = targetPort,
                                onValueChange = { targetPort = it },
                                label = { Text("Port") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AppTheme.colors.accentCyan,
                                    unfocusedBorderColor = AppTheme.colors.border,
                                    focusedTextColor = AppTheme.colors.textPrimary,
                                    unfocusedTextColor = AppTheme.colors.textPrimary,
                                    focusedContainerColor = AppTheme.colors.surface,
                                    unfocusedContainerColor = AppTheme.colors.surface
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = {
                                val port = targetPort.toIntOrNull() ?: 443
                                viewModel.runDetailedDiagnostic(targetHost.trim(), port)
                            },
                            enabled = !isTestingDiag,
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.accentCyan),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("run_diagnostic_button")
                        ) {
                            if (isTestingDiag) {
                                CircularProgressIndicator(
                                    color = if (isDarkTheme) Color(0xFF00363D) else Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Analyzing Handshakes...",
                                    color = if (isDarkTheme) Color(0xFF00363D) else Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(
                                    Icons.Default.FlashOn,
                                    contentDescription = "Run",
                                    tint = if (isDarkTheme) Color(0xFF00363D) else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Run Diagnostic Test",
                                    color = if (isDarkTheme) Color(0xFF00363D) else Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Diagnostic Results Card
                val res = diagnosticResult
                if (res != null) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceVariant),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.accentCyan.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "DIAGNOSTIC REPORT: ${res.host}:${res.port}",
                                    color = AppTheme.colors.accentCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Loss: ${res.packetLossPercent}%",
                                    color = if (res.packetLossPercent == 0) AppTheme.colors.accentMint else AppTheme.colors.accentRuby,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                MetricColumn(label = "MIN PING", value = if (res.minLatencyMs > 0) "${res.minLatencyMs} ms" else "--")
                                MetricColumn(label = "AVG PING", value = if (res.avgLatencyMs > 0) "${res.avgLatencyMs} ms" else "--", highlight = true)
                                MetricColumn(label = "MAX PING", value = if (res.maxLatencyMs > 0) "${res.maxLatencyMs} ms" else "--")
                                MetricColumn(label = "JITTER", value = "${res.jitterMs} ms")
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Packets: ${res.packetsReceived}/${res.packetsSent} received",
                                color = AppTheme.colors.textMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        } else {
            // Live Core Console Logs
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LIVE OUTPUT (${logs.size} entries)",
                        color = AppTheme.colors.textMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { SingRayVpnService.clearLogs() }) {
                        Icon(Icons.Default.CleaningServices, contentDescription = "Clear Logs", tint = AppTheme.colors.textMuted, modifier = Modifier.size(18.dp))
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppTheme.colors.background)
                        .border(1.dp, AppTheme.colors.border, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    if (logs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No log entries yet. Connect to a server to see core trace.", color = AppTheme.colors.textMuted, fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(logs.reversed()) { log ->
                                val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(log.timestamp))
                                val color = when (log.level) {
                                    "ERROR" -> AppTheme.colors.accentRuby
                                    "WARN" -> AppTheme.colors.accentAmber
                                    else -> AppTheme.colors.accentMint
                                }

                                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Text(
                                        text = "$time ",
                                        color = AppTheme.colors.textMuted,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "[${log.tag}] ",
                                        color = AppTheme.colors.accentCyan,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = log.message,
                                        color = color,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: String, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = AppTheme.colors.textMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = if (highlight) AppTheme.colors.accentCyan else AppTheme.colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
