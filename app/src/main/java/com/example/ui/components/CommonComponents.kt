package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ProxyProtocol
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.MintTelemetry
import com.example.ui.theme.RubyCritical
import com.example.ui.theme.SlateDim
import com.example.ui.theme.TitaniumBorder

@Composable
fun ProtocolBadge(protocol: ProxyProtocol, modifier: Modifier = Modifier) {
    val color = Color(protocol.badgeColorHex)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = protocol.displayName.uppercase(),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun LatencyBadge(
    latencyMs: Long,
    isRealDelay: Boolean = false,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor, text) = when {
        latencyMs == -1L -> Triple(SlateDim.copy(alpha = 0.2f), SlateDim, "Untested")
        latencyMs == -2L -> Triple(RubyCritical.copy(alpha = 0.2f), RubyCritical, "Timeout")
        !isRealDelay -> {
            // TCP-only ping: muted color and explicit "TCP <n> ms"
            Triple(SlateDim.copy(alpha = 0.25f), Color(0xFF90A4AE), "TCP $latencyMs ms")
        }
        latencyMs < 120 -> Triple(MintTelemetry.copy(alpha = 0.2f), MintTelemetry, "$latencyMs ms")
        latencyMs < 300 -> Triple(CyanNeon.copy(alpha = 0.2f), CyanNeon, "$latencyMs ms")
        latencyMs < 500 -> Triple(AmberWarning.copy(alpha = 0.2f), AmberWarning, "$latencyMs ms")
        else -> Triple(RubyCritical.copy(alpha = 0.2f), RubyCritical, "$latencyMs ms")
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, textColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}
