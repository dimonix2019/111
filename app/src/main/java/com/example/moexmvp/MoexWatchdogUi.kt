package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MoexWatchdogStatusCard(
    status: MoexWatchdogStatus,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val healthy = status.overallHealthy
    val bg = if (healthy) Color(0xFF1B3A2F) else Color(0xFF3A1B1B)
    val accent = if (healthy) Color(0xFF81C784) else Color(0xFFE57373)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Shield,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (healthy) "Watchdog: OK" else "Watchdog: проверьте монитор",
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (!status.monitorEnabled) {
            Text(
                text = "Фоновый монитор выключен (BG выкл). Watchdog ждёт включения.",
                color = Color(0xFFB0BEC5),
                fontSize = 11.sp,
            )
        } else {
            Text(
                text = buildString {
                    append("Сервис: ")
                    append(
                        when {
                            !status.serviceRunning -> "не запущен"
                            status.serviceStale -> "нет пульса ${formatWatchdogAgeSec(status.serviceAgeSec)}"
                            else -> "OK (${formatWatchdogAgeSec(status.serviceAgeSec)} назад)"
                        }
                    )
                    append(" · UI: ")
                    append(
                        if (status.uiAgeSec < 0) "—" else formatWatchdogAgeSec(status.uiAgeSec) + " назад"
                    )
                },
                color = Color(0xFFE0E0E0),
                fontSize = 11.sp,
            )
            if (status.serviceRestartCount > 0) {
                Text(
                    text = "Перезапусков сервиса: ${status.serviceRestartCount}" +
                        status.lastRestartReason.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                    color = Color(0xFF90A4AE),
                    fontSize = 10.sp,
                )
            }
        }
        Button(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF37474F),
                contentColor = Color.White,
            ),
            contentPadding = ButtonDefaults.ContentPadding,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Проверить / перезапустить монитор", fontSize = 11.sp)
            }
        }
    }
}

internal fun refreshWatchdogStatus(screen: MoexScreenState) {
    MoexWatchdog.recordUiPing(screen.context)
    MoexWatchdog.performMonitorWatchdogCheck(screen.context, "ui_manual")
    screen.watchdogStatus = MoexWatchdog.readStatus(screen.context)
}
