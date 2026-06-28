package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PortfolioEntryReadinessSection(
    report: PortfolioEntryReadinessReport,
    position: ZStrategyPosition,
    entryThreshold: Double,
    exitThreshold: Double,
) {
    val positionRu = when (position) {
        ZStrategyPosition.Flat -> "Flat"
        ZStrategyPosition.Long -> "Long"
        ZStrategyPosition.Short -> "Short"
    }
    PortfolioCollapsibleSection(
        title = "Почему нет сделки / сигнала",
        subtitle = report.summary.take(120),
        defaultExpanded = true,
        compactHeader = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E272E), RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "pos=$positionRu · вход ±$entryThreshold · выход ±$exitThreshold",
                color = Color(0xFF90A4AE),
                fontSize = 9.sp,
                maxLines = 2,
            )
            Text(
                text = report.summary,
                color = if (report.primaryBlocker != null) Color(0xFFFFAB91) else Color(0xFFB0BEC5),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 4,
            )
            report.items.forEach { item ->
                PortfolioEntryReadinessRow(item)
            }
        }
    }
}

@Composable
private fun PortfolioEntryReadinessRow(item: PortfolioEntryReadinessItem) {
    val (icon, color) = when (item.status) {
        PortfolioEntryReadinessStatus.Ok -> "✓" to Color(0xFF81C784)
        PortfolioEntryReadinessStatus.Blocked -> "✗" to Color(0xFFE57373)
        PortfolioEntryReadinessStatus.Warning -> "⚠" to Color(0xFFFFCC80)
        PortfolioEntryReadinessStatus.Info -> "○" to Color(0xFF90A4AE)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = icon,
            color = color,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 1.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                color = color,
                fontSize = 9.sp,
                maxLines = 2,
            )
            item.detail?.let { detail ->
                Text(
                    text = detail,
                    color = Color(0xFF757575),
                    fontSize = 8.sp,
                    maxLines = 3,
                )
            }
        }
    }
}
