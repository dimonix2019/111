package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DataLoadProgressCard(
    progress: DataLoadProgress,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1A2332), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = phaseTitle(progress),
            color = Color(0xFF90CAF9),
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
        Text(
            text = formatDataLoadProgressSummary(progress),
            color = Color(0xFFB0BEC5),
            fontSize = 11.sp,
            lineHeight = 14.sp,
        )
        if (progress.showCacheBar) {
            LoadProgressRow(
                label = "Кэш SQLite (15м)",
                loaded = progress.cacheBarsLoaded,
                total = progress.cacheBarsTotal.coerceAtLeast(progress.cacheBarsLoaded).coerceAtLeast(1),
                fraction = progress.cacheFraction,
                color = Color(0xFF80CBC4),
            )
        }
        if (progress.showMoexBar) {
            LoadProgressRow(
                label = "MOEX ISS (10м→15м)",
                loaded = progress.moexBarsLoaded,
                total = progress.moexBarsTotal.coerceAtLeast(1),
                fraction = progress.moexFraction,
                color = Color(0xFFFFB74D),
            )
        }
        if (progress.phase == DataLoadPhase.MarketsDaily) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                progress = { 0.35f },
                color = Color(0xFF64B5F6),
                trackColor = Color(0xFF37474F),
            )
        }
    }
}

@Composable
private fun LoadProgressRow(
    label: String,
    loaded: Int,
    total: Int,
    fraction: Float,
    color: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "$label: $loaded / $total бар.",
            color = Color(0xFFECEFF1),
            fontSize = 11.sp,
        )
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            progress = { fraction },
            color = color,
            trackColor = Color(0xFF37474F),
        )
    }
}

private fun phaseTitle(progress: DataLoadProgress): String = when (progress.phase) {
    DataLoadPhase.CacheRead -> "Чтение локального кэша"
    DataLoadPhase.MoexDownload -> "Загрузка с MOEX"
    DataLoadPhase.ApplyingZ -> "Подготовка графика"
    DataLoadPhase.MarketsDaily -> "Дневные свечи MOEX"
    DataLoadPhase.Idle -> "Загрузка"
}

@Composable
internal fun LoadingStateWithProgress(
    progress: DataLoadProgress?,
    statusText: String = "Загрузка…",
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (progress != null && progress.active) {
            DataLoadProgressCard(progress = progress)
        } else {
            LoadingState()
        }
        if (statusText.isNotBlank()) {
            Text(
                text = statusText,
                color = Color(0xFF9FA8DA),
                fontSize = 11.sp,
            )
        }
    }
}
