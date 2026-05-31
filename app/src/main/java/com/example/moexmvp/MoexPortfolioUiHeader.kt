package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

@Composable
internal fun PortfolioPresetSection(
    presets: List<PortfolioPreset>,
    onApply: (PortfolioPreset) -> Unit,
    onDelete: (String) -> Unit,
    onSave: (String) -> Unit,
    showSaveButton: Boolean = true
) {
    var showDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Пресеты",
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            presets.forEach { p ->
                OutlinedButton(
                    onClick = { onApply(p) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(p.name, fontSize = 10.sp, maxLines = 1, color = Color(0xFFB3E5FC))
                }
                TextButton(onClick = { onDelete(p.id) }, contentPadding = PaddingValues(4.dp)) {
                    Text("×", color = Color(0xFFEF9A9A), fontSize = 16.sp)
                }
            }
            if (showSaveButton) {
                OutlinedButton(
                    onClick = {
                        name = ""
                        showDialog = true
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("+ сохранить", fontSize = 10.sp, color = Color(0xFFE0E0E0))
                }
            }
        }
    }
    if (showSaveButton && showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Имя пресета", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Название") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(name)
                        showDialog = false
                    }
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Отмена")
                }
            },
            containerColor = Color(0xFF263238)
        )
    }
}

@Composable
internal fun PortfolioDataRefreshHeader(
    title: String,
    portfolioLoading: Boolean,
    onRefresh: () -> Unit,
    onMoex15mFullReload: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (portfolioLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color(0xFF64B5F6),
                    strokeWidth = 2.dp
                )
            }
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Обновить", fontSize = 11.sp)
                }
            }
            if (onMoex15mFullReload != null) {
                OutlinedButton(
                    onClick = onMoex15mFullReload,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFB3E5FC))
                        Spacer(Modifier.width(4.dp))
                        Text("MOEX заново", fontSize = 10.sp, color = Color(0xFFB3E5FC))
                    }
                }
            }
        }
    }
}

@Composable
internal fun PortfolioCollapsibleSection(
    title: String,
    subtitle: String? = null,
    defaultExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .background(Color(0xFF2C2C2C), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color(0xFFE0E0E0),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                subtitle?.let {
                    Text(
                        text = it,
                        color = Color(0xFF757575),
                        fontSize = 10.sp,
                        maxLines = 2
                    )
                }
            }
            Text(
                text = if (expanded) "▼" else "▶",
                color = Color(0xFF9E9E9E),
                fontSize = 12.sp
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
internal fun PortfolioCompactHeroInline(metrics: PortfolioMetrics) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val pnlColor = when {
            metrics.totalPnlRubApprox > 0 -> Color(0xFF81C784)
            metrics.totalPnlRubApprox < 0 -> Color(0xFFE57373)
            else -> Color(0xFFBDBDBD)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text("Итого PnL", color = Color(0xFF90A4AE), fontSize = 10.sp)
            Text(
                text = "${formatRubSigned(metrics.totalPnlRubApprox)}  ${formatPercentSigned(metrics.totalReturnPercent)}",
                color = pnlColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text("Макс. просадка", color = Color(0xFF90A4AE), fontSize = 10.sp)
            Text(
                text = "${formatRubSigned(-metrics.maxDrawdownRubApprox)}  ${String.format(Locale.US, "%.1f%%", metrics.maxDrawdownPercent)}",
                color = Color(0xFFFFB74D),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

