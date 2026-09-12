package com.example.moexmvp

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SwapHoriz
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
internal fun MainTabSelector(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    tradeCloseNowPnl: CloseNowPnl? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        MainTab.navTabs.forEach { tab ->
            val isSel = tab == selected
            val icon = when (tab) {
                MainTab.Markets -> Icons.Filled.ShowChart
                MainTab.Trade -> Icons.Filled.SwapHoriz
                MainTab.Sandbox -> Icons.Filled.AccountBalance
                MainTab.WebDesk -> Icons.Filled.Language
                MainTab.About -> Icons.Filled.Info
                else -> Icons.Filled.Info
            }
            Button(
                onClick = { onSelect(tab) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSel) Color(0xFF1565C0) else Color(0xFF424242),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    val tradePnl = if (tab == MainTab.Trade) tradeCloseNowPnl else null
                    Text(
                        text = when {
                            tab == MainTab.About -> "${tab.label}\n${BuildConfig.VERSION_NAME}"
                            tradePnl != null -> "${tab.label}\n${formatCloseNowHeroRub(tradePnl.netRub)}"
                            else -> tab.label
                        },
                        fontWeight = if (isSel || tradePnl != null) FontWeight.Bold else FontWeight.Normal,
                        fontSize = if (tradePnl != null) 13.sp else 11.sp,
                        color = when {
                            tradePnl == null -> Color.White
                            else -> closeNowPnlAccent(tradePnl.netRub)
                        },
                        maxLines = if (tab == MainTab.About || tradePnl != null) 2 else 1
                    )
                }
            }
        }
    }
}

