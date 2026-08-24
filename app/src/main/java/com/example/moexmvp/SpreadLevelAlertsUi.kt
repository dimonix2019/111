package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SpreadLevelAlertsSettingsCard(
    modifier: Modifier = Modifier,
    onChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val master = remember(tick) { SpreadLevelAlertSettings.isMasterEnabled(context) }
    val levels = remember(tick) { SpreadLevelAlertSettings.levelStates(context) }
    val bump = {
        tick++
        onChanged()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Алерты спреда",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Push при пересечении 0,5 / 1 / 2 / 2,5%. Отключите уровень, если алертов слишком много.",
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Все алерты спреда", color = Color(0xFFE0E0E0), fontSize = 12.sp)
            Switch(
                checked = master,
                onCheckedChange = { enabled ->
                    SpreadLevelAlertSettings.setMasterEnabled(context, enabled)
                    bump()
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF2E7D32),
                ),
            )
        }
        levels.forEach { (levelPct, enabled) ->
            SpreadLevelAlertLevelRow(
                levelPct = levelPct,
                enabled = enabled && master,
                masterEnabled = master,
                onToggle = { on ->
                    SpreadLevelAlertSettings.setLevelEnabled(context, levelPct, on)
                    bump()
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = {
                    SpreadLevelAlertSettings.setAllLevelsEnabled(context, true)
                    SpreadLevelAlertSettings.setMasterEnabled(context, true)
                    bump()
                },
                enabled = !master || levels.any { !it.second },
            ) {
                Text("Включить все", color = Color(0xFF81D4FA), fontSize = 11.sp)
            }
            TextButton(
                onClick = {
                    SpreadLevelAlertSettings.setAllLevelsEnabled(context, false)
                    bump()
                },
                enabled = master && levels.any { it.second },
            ) {
                Text("Отключить все уровни", color = Color(0xFFFFAB91), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SpreadLevelAlertLevelRow(
    levelPct: Double,
    enabled: Boolean,
    masterEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val label = formatRuSignedNumber(levelPct)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Уровень $label%",
            color = if (masterEnabled) Color(0xFFB0BEC5) else Color(0xFF616161),
            fontSize = 12.sp,
        )
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            enabled = masterEnabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF1565C0),
            ),
        )
    }
}

@Composable
internal fun SpreadLevelAlertDisableButton(
    correlationTag: String?,
    modifier: Modifier = Modifier,
    refreshTick: Int = 0,
    onDisabled: () -> Unit = {},
) {
    if (!isSpreadLevelAlertCorrelationTag(correlationTag)) return
    val context = LocalContext.current
    val level = parseSpreadLevelAlertCorrelationTag(correlationTag) ?: return
    val enabled = remember(refreshTick, level) {
        SpreadLevelAlertSettings.isLevelEnabled(context, level)
    }
    if (enabled) {
        Button(
            onClick = {
                SpreadLevelAlertSettings.setLevelEnabled(context, level, enabled = false)
                onDisabled()
            },
            modifier = modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF5D4037),
                contentColor = Color.White,
            ),
            contentPadding = ButtonDefaults.ContentPadding,
        ) {
            Text(
                text = "Отключить алерт ${formatRuSignedNumber(level)}%",
                fontSize = 11.sp,
            )
        }
    } else {
        Text(
            text = "Алерт ${formatRuSignedNumber(level)}% отключён (см. «О приложении»)",
            color = Color(0xFF757575),
            fontSize = 10.sp,
            modifier = modifier.padding(top = 4.dp),
        )
    }
}
