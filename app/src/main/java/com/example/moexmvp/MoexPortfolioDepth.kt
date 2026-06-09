package com.example.moexmvp

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class PortfolioDepthOption(val days: Long, val chipLabel: String)

internal val PORTFOLIO_DEPTH_OPTIONS = listOf(
    PortfolioDepthOption(1, "1 день"),
    PortfolioDepthOption(3, "3 дня"),
    PortfolioDepthOption(7, "Неделя"),
    PortfolioDepthOption(30, "Месяц"),
)

internal const val DEFAULT_PORTFOLIO_LOOKBACK_DAYS = 30L

internal fun normalizePortfolioLookbackDays(days: Long): Long =
    PORTFOLIO_DEPTH_OPTIONS.firstOrNull { it.days == days }?.days ?: DEFAULT_PORTFOLIO_LOOKBACK_DAYS

internal fun portfolioLookbackPeriodLabel(days: Long): String =
    PORTFOLIO_DEPTH_OPTIONS.firstOrNull { it.days == normalizePortfolioLookbackDays(days) }?.chipLabel
        ?: "${normalizePortfolioLookbackDays(days)} дн."

internal fun loadPortfolioLookbackDays(context: Context): Long =
    normalizePortfolioLookbackDays(
        context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(PREF_PORTFOLIO_LOOKBACK_DAYS, DEFAULT_PORTFOLIO_LOOKBACK_DAYS)
    )

internal fun savePortfolioLookbackDays(context: Context, days: Long) {
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putLong(PREF_PORTFOLIO_LOOKBACK_DAYS, normalizePortfolioLookbackDays(days))
        .apply()
}

@Composable
internal fun PortfolioDepthSelector(
    selectedDays: Long,
    onSelect: (Long) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val selected = normalizePortfolioLookbackDays(selectedDays)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PORTFOLIO_DEPTH_OPTIONS.forEach { option ->
            PortfolioDepthChip(
                label = option.chipLabel,
                selected = option.days == selected,
                enabled = enabled,
                onClick = { onSelect(option.days) },
            )
        }
    }
}

@Composable
private fun PortfolioDepthChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF1565C0) else Color(0xFF424242),
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF303030),
            disabledContentColor = Color(0xFF757575),
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, fontSize = 11.sp)
    }
}
