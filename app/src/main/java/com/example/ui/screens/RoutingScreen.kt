package com.example.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.RoutingMode
import com.example.ui.MainViewModel
import com.example.ui.theme.AppTheme

@Composable
fun RoutingScreen(viewModel: MainViewModel) {
    val currentMode by viewModel.routingMode.collectAsStateWithLifecycle()
    val bypassLan by viewModel.bypassLan.collectAsStateWithLifecycle()
    val bypassDomestic by viewModel.bypassDomestic.collectAsStateWithLifecycle()
    val dnsServer by viewModel.dnsServer.collectAsStateWithLifecycle()
    val coreType by viewModel.coreType.collectAsStateWithLifecycle()
    val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        // Header
        Text(
            text = "ROUTING & CORE ENGINE",
            color = AppTheme.colors.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )
        Text(
            text = "Configures tun routing, split-tunneling, and DNS engines",
            color = AppTheme.colors.textSecondary,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Core Engine Selector
        Text(
            text = "SELECT CORE ENGINE",
            color = AppTheme.colors.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(8.dp))

        val activeCore by viewModel.activeCore.collectAsStateWithLifecycle()
        val installed = remember { viewModel.installedCores() }
        Text(
            text = "Active: ${activeCore.title}  |  Installed: " +
                installed.entries.joinToString(", ") { "${it.key.title} ${it.value}" },
            color = AppTheme.colors.textMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(8.dp))

        val cores = listOf("Auto", "Sing-box", "Xray", "Built-in")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            cores.forEach { engine ->
                val isSelected = coreType == engine
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) AppTheme.colors.surfaceVariant else AppTheme.colors.surface
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        if (isSelected) 1.5.dp else 1.dp,
                        if (isSelected) AppTheme.colors.accentCyan else AppTheme.colors.border
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { viewModel.setCoreType(engine) }
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (engine == "Sing-box" || engine == "Auto") Icons.Default.Memory else Icons.Default.Router,
                                contentDescription = engine,
                                tint = if (isSelected) AppTheme.colors.accentCyan else AppTheme.colors.textMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = engine,
                                color = if (isSelected) AppTheme.colors.accentCyan else AppTheme.colors.textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (engine == "Sing-box") "Ultra low memory footprint with native V1.11 rule-set" else "Battle-tested Xray XTLS Reality & VLESS core",
                            color = AppTheme.colors.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Routing Modes
        Text(
            text = "ROUTING RULES",
            color = AppTheme.colors.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(8.dp))

        RoutingMode.entries.forEach { mode ->
            val isSelected = currentMode == mode
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) AppTheme.colors.surfaceVariant else AppTheme.colors.surface
                ),
                border = androidx.compose.foundation.BorderStroke(
                    if (isSelected) 1.5.dp else 1.dp,
                    if (isSelected) AppTheme.colors.accentCyan else AppTheme.colors.border
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { viewModel.setRoutingMode(mode) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { viewModel.setRoutingMode(mode) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = AppTheme.colors.accentCyan,
                            unselectedColor = AppTheme.colors.textMuted
                        )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = mode.title,
                            color = if (isSelected) AppTheme.colors.accentCyan else AppTheme.colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = mode.description,
                            color = AppTheme.colors.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Network Rules Switches
        Text(
            text = "TRAFFIC SPLITTING RULES",
            color = AppTheme.colors.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bypass LAN Networks", color = AppTheme.colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Excludes local routers, Chromecast, 192.168.x.x, 10.x.x.x", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = bypassLan,
                        onCheckedChange = { viewModel.setBypassLan(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = if (isDarkTheme) Color(0xFF00363D) else Color.White,
                            checkedTrackColor = AppTheme.colors.accentCyan
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bypass Domestic / Iranian IPs", color = AppTheme.colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Routes Iranian banks and domestic .ir sites directly without proxy", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = bypassDomestic,
                        onCheckedChange = { viewModel.setBypassDomestic(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = if (isDarkTheme) Color(0xFF00363D) else Color.White,
                            checkedTrackColor = AppTheme.colors.accentCyan
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // DNS Server Selector
        Text(
            text = "SECURE REMOTE DNS RESOLVER",
            color = AppTheme.colors.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(8.dp))

        val dnsOptions = listOf(
            Triple("Cloudflare DoH", "1.1.1.1", "Fastest global response & privacy"),
            Triple("Google Public DNS", "8.8.8.8", "Reliable anycast infrastructure"),
            Triple("Quad9 Secure", "9.9.9.9", "Malware blocking & DNSSEC"),
            Triple("AdGuard DNS", "94.140.14.14", "Built-in tracking & ad filter")
        )

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                dnsOptions.forEach { (name, ip, desc) ->
                    val isSelected = dnsServer == ip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setDnsServer(ip) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { viewModel.setDnsServer(ip) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = AppTheme.colors.accentCyan,
                                unselectedColor = AppTheme.colors.textMuted
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = name,
                                    color = if (isSelected) AppTheme.colors.accentCyan else AppTheme.colors.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "($ip)",
                                    color = AppTheme.colors.textMuted,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Text(text = desc, color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Hardware & Battery Optimization
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.BatteryChargingFull,
                    contentDescription = "Battery",
                    tint = AppTheme.colors.accentMint,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Low-Resource & Battery Profile Active",
                        color = AppTheme.colors.accentMint,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Zero background busy-waiting, non-blocking socket handshakes, and native TUN packet routing for minimal CPU and RAM usage on all Android devices.",
                        color = AppTheme.colors.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}
