package com.example.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.entity.SubscriptionEntity
import com.example.ui.MainViewModel
import com.example.ui.dialogs.AddSubscriptionDialog
import com.example.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SubscriptionsScreen(viewModel: MainViewModel) {
    val subscriptions: List<SubscriptionEntity> by viewModel.allSubscriptions.collectAsStateWithLifecycle()
    val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    if (showAddDialog) {
        AddSubscriptionDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url -> viewModel.addSubscription(name, url) }
        )
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SUBSCRIPTIONS",
                        color = AppTheme.colors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "${subscriptions.size} remote feeds managed",
                        color = AppTheme.colors.textSecondary,
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = {
                        subscriptions.forEach { sub -> viewModel.updateSubscription(sub) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.accentCyan),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("update_all_subs_button")
                ) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = "Sync All",
                        tint = if (isDarkTheme) Color(0xFF00363D) else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "Sync All",
                        color = if (isDarkTheme) Color(0xFF00363D) else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (subscriptions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.RssFeed,
                            contentDescription = "No Subs",
                            tint = AppTheme.colors.textMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No subscriptions added", color = AppTheme.colors.textMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Tap + below to add a subscription link", color = AppTheme.colors.accentCyan, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(subscriptions, key = { it.id }) { sub ->
                        SubscriptionCard(
                            subscription = sub,
                            onUpdate = { viewModel.updateSubscription(sub) },
                            onDelete = { viewModel.deleteSubscription(sub) }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }

        // Add Subscription FAB
        FloatingActionButton(
            onClick = { showAddDialog = true },
            containerColor = AppTheme.colors.accentCyan,
            contentColor = if (isDarkTheme) Color(0xFF00363D) else Color.White,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("add_sub_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Subscription")
        }
    }
}

@Composable
fun SubscriptionCard(
    subscription: SubscriptionEntity,
    onUpdate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
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
                        imageVector = Icons.Default.RssFeed,
                        contentDescription = "Feed",
                        tint = AppTheme.colors.accentCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = subscription.name,
                        color = AppTheme.colors.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AppTheme.colors.accentMint.copy(alpha = 0.15f))
                        .border(1.dp, AppTheme.colors.accentMint.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${subscription.totalNodes} Nodes",
                        color = AppTheme.colors.accentMint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subscription.url,
                color = AppTheme.colors.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val formattedTime = if (subscription.lastUpdated > 0) {
                    SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(subscription.lastUpdated))
                } else "Never"

                Text(
                    text = "Updated: $formattedTime",
                    color = AppTheme.colors.textMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                Row {
                    IconButton(
                        onClick = onUpdate,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "Sync", tint = AppTheme.colors.accentCyan, modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = AppTheme.colors.accentRuby, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
