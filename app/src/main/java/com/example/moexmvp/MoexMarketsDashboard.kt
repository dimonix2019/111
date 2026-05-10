package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun MarketsSummaryStrip(
    z: Double?,
    spread: Double?,
    position: ZStrategyPosition,
    signalsToday: Int,
    signalsMax: Int,
    todayPnlSpreadHint: String?,
    lastLoadedAt: String?,
    dataSource: MarketsDataSource,
    stale: Boolean,
    onMoexRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121212), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Сводка", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Button(
                onClick = onMoexRefresh,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Обновить MOEX", fontSize = 12.sp)
            }
        }
        Text(
            text = "Z: ${z?.let { String.format(Locale.US, "%.2f", it) } ?: "—"}   |   Спред: ${spread?.let { String.format(Locale.US, "%.2f%%", it) } ?: "—"}",
            color = Color(0xFFE0E0E0),
            fontSize = 13.sp
        )
        Text(
            text = "Позиция: ${positionLabel(position)}",
            color = Color(0xFFB3E5FC),
            fontSize = 12.sp
        )
        Text(
            text = "Сигналы сегодня: $signalsToday / $signalsMax",
            color = Color(0xFFFFCC80),
            fontSize = 12.sp
        )
        if (todayPnlSpreadHint != null) {
            Text(
                text = "Оценка PnL сегодня (журнал): $todayPnlSpreadHint",
                color = Color(0xFFCE93D8),
                fontSize = 12.sp
            )
        }
        Text(
            text = "Данные: ${dataSource.labelRu} · $lastLoadedAt",
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp
        )
        if (stale) {
            Text(
                text = "Показан последний успешный снимок — текущее обновление с MOEX не удалось.",
                color = Color(0xFFFFAB91),
                fontSize = 11.sp
            )
        }
    }
}

private fun positionLabel(p: ZStrategyPosition): String = when (p) {
    ZStrategyPosition.Flat -> "FLAT"
    ZStrategyPosition.Long -> "LONG спрэд (TATN / TATNP)"
    ZStrategyPosition.Short -> "SHORT спрэд"
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
internal fun MarketsPullRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val pullState = rememberPullRefreshState(refreshing, onRefresh)
    Box(modifier.pullRefresh(pullState)) {
        content()
        PullRefreshIndicator(
            refreshing = refreshing,
            state = pullState,
            modifier = Modifier.align(Alignment.TopCenter),
            contentColor = Color(0xFF64B5F6)
        )
    }
}
