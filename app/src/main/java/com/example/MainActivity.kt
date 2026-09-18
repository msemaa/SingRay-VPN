package com.example

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.ConnectionStatus
import com.example.ui.MainViewModel
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.RoutingScreen
import com.example.ui.screens.ServersScreen
import com.example.ui.screens.SubscriptionsScreen
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SlateDim
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TitaniumBorder
import com.example.ui.theme.TitaniumDarkBackground
import com.example.ui.theme.TitaniumSurface
import com.example.ui.theme.TitaniumSurfaceVariant

enum class AppTab(val title: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Dns),
    SERVERS("Nodes", Icons.Default.Lan),
    ROUTING("Routing", Icons.Default.Tune),
    SUBSCRIPTIONS("Feeds", Icons.Default.RssFeed),
    DIAGNOSTICS("Console", Icons.Default.Terminal)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
            val showTelegramDialog by viewModel.showTelegramDialog.collectAsStateWithLifecycle()

            MyApplicationTheme(darkTheme = isDarkTheme) {
                if (showTelegramDialog) {
                    com.example.ui.dialogs.TelegramChannelDialog(
                        onDismiss = { dontShowAgain ->
                            viewModel.dismissTelegramDialog(dontShowAgain)
                        }
                    )
                }

                MainAppContent(
                    viewModel = viewModel,
                    onRequestVpnPermission = { onGranted ->
                        val intent = VpnService.prepare(this)
                        if (intent != null) {
                            vpnLauncher.launch(intent)
                        } else {
                            onGranted()
                        }
                    }
                )
            }
        }
    }

    private var pendingVpnAction: (() -> Unit)? = null
    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingVpnAction?.invoke()
        }
        pendingVpnAction = null
    }

    fun requestVpn(action: () -> Unit) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingVpnAction = action
            vpnLauncher.launch(intent)
        } else {
            action()
        }
    }
}

@Composable
fun MainAppContent(
    viewModel: MainViewModel,
    onRequestVpnPermission: (onGranted: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    var currentTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val uiNotice by viewModel.uiNotice.collectAsStateWithLifecycle()

    LaunchedEffect(uiNotice) {
        uiNotice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissNotice()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(com.example.ui.theme.AppTheme.colors.background),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = com.example.ui.theme.AppTheme.colors.surface,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .background(com.example.ui.theme.AppTheme.colors.surface)
            ) {
                AppTab.entries.forEachIndexed { index, tab ->
                    val isSelected = currentTab == index
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentTab = index },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = com.example.ui.theme.AppTheme.colors.accentCyan,
                            selectedTextColor = com.example.ui.theme.AppTheme.colors.accentCyan,
                            unselectedIconColor = com.example.ui.theme.AppTheme.colors.textMuted,
                            unselectedTextColor = com.example.ui.theme.AppTheme.colors.textMuted,
                            indicatorColor = com.example.ui.theme.AppTheme.colors.surfaceVariant
                        ),
                        modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(com.example.ui.theme.AppTheme.colors.background)
        ) {
            when (currentTab) {
                0 -> DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToServers = { currentTab = 1 },
                    onNavigateToRouting = { currentTab = 2 },
                    onRequestConnect = {
                        viewModel.toggleConnection(onRequestVpnPermission)
                    }
                )
                1 -> ServersScreen(viewModel = viewModel)
                2 -> RoutingScreen(viewModel = viewModel)
                3 -> SubscriptionsScreen(viewModel = viewModel)
                4 -> DiagnosticsScreen(viewModel = viewModel)
            }
        }
    }
}
