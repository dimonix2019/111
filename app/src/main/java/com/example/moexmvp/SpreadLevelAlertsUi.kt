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
    val longEnabled = remember(tick) {
        EntryLevelAlertSettings.isEnabled(context, EntryAlertSide.Long)
    }
    val shortEnabled = remember(tick) {
        EntryLevelAlertSettings.isEnabled(context, EntryAlertSide.Short)
    }
    val longTradeAt = remember(tick) {
        EntryLevelAlertSettings.tradeOpenedAtMillis(context, EntryAlertSide.Long)
    }
    val shortTradeAt = remember(tick) {
        EntryLevelAlertSettings.tradeOpenedAtMillis(context, EntryAlertSide.Short)
    }
    val longEnterPct = remember(tick) { EntryLevelAlertSettings.longEnterPct(context) }
    val shortEnterPct = remember(tick) { EntryLevelAlertSettings.shortEnterPct(context) }
    val addonLongEnabled = remember(tick) {
        AddonExtraLevelAlertSettings.isEnabled(context, AddonExtraAlertSlot.AddonLong)
    }
    val addonShortEnabled = remember(tick) {
        AddonExtraLevelAlertSettings.isEnabled(context, AddonExtraAlertSlot.AddonShort)
    }
    val extraLongEnabled = remember(tick) {
        AddonExtraLevelAlertSettings.isEnabled(context, AddonExtraAlertSlot.ExtraLong)
    }
    val extraShortEnabled = remember(tick) {
        AddonExtraLevelAlertSettings.isEnabled(context, AddonExtraAlertSlot.ExtraShort)
    }
    val addonLongTradeAt = remember(tick) {
        AddonExtraLevelAlertSettings.tradeOpenedAtMillis(context, AddonExtraAlertSlot.AddonLong)
    }
    val addonShortTradeAt = remember(tick) {
        AddonExtraLevelAlertSettings.tradeOpenedAtMillis(context, AddonExtraAlertSlot.AddonShort)
    }
    val extraLongTradeAt = remember(tick) {
        AddonExtraLevelAlertSettings.tradeOpenedAtMillis(context, AddonExtraAlertSlot.ExtraLong)
    }
    val extraShortTradeAt = remember(tick) {
        AddonExtraLevelAlertSettings.tradeOpenedAtMillis(context, AddonExtraAlertSlot.ExtraShort)
    }
    val addonLongPct = remember(tick) {
        AddonExtraLevelAlertSettings.enterPct(context, AddonExtraAlertSlot.AddonLong)
    }
    val addonShortPct = remember(tick) {
        AddonExtraLevelAlertSettings.enterPct(context, AddonExtraAlertSlot.AddonShort)
    }
    val extraLongPct = remember(tick) {
        AddonExtraLevelAlertSettings.enterPct(context, AddonExtraAlertSlot.ExtraLong)
    }
    val extraShortPct = remember(tick) {
        AddonExtraLevelAlertSettings.enterPct(context, AddonExtraAlertSlot.ExtraShort)
    }
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
            text = "Вход",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Push при касании порогов входа стратегии. После открытия сделки алерт выключается.",
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        EntryAlertSideRow(
            side = EntryAlertSide.Long,
            levelPct = longEnterPct,
            enabled = longEnabled,
            tradeAtMillis = longTradeAt,
            onToggle = { on ->
                EntryLevelAlertSettings.setEnabled(context, EntryAlertSide.Long, on)
                bump()
            },
        )
        EntryAlertSideRow(
            side = EntryAlertSide.Short,
            levelPct = shortEnterPct,
            enabled = shortEnabled,
            tradeAtMillis = shortTradeAt,
            onToggle = { on ->
                EntryLevelAlertSettings.setEnabled(context, EntryAlertSide.Short, on)
                bump()
            },
        )

        Text(
            text = "Добор и экстра",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "Push при касании порогов добора (2/7) и экстра (1/9). После открытия ноги алерт выключается.",
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        AddonExtraAlertSlotRow(
            slot = AddonExtraAlertSlot.AddonLong,
            levelPct = addonLongPct,
            enabled = addonLongEnabled,
            tradeAtMillis = addonLongTradeAt,
            onToggle = { on ->
                AddonExtraLevelAlertSettings.setEnabled(context, AddonExtraAlertSlot.AddonLong, on)
                bump()
            },
        )
        AddonExtraAlertSlotRow(
            slot = AddonExtraAlertSlot.AddonShort,
            levelPct = addonShortPct,
            enabled = addonShortEnabled,
            tradeAtMillis = addonShortTradeAt,
            onToggle = { on ->
                AddonExtraLevelAlertSettings.setEnabled(context, AddonExtraAlertSlot.AddonShort, on)
                bump()
            },
        )
        AddonExtraAlertSlotRow(
            slot = AddonExtraAlertSlot.ExtraLong,
            levelPct = extraLongPct,
            enabled = extraLongEnabled,
            tradeAtMillis = extraLongTradeAt,
            onToggle = { on ->
                AddonExtraLevelAlertSettings.setEnabled(context, AddonExtraAlertSlot.ExtraLong, on)
                bump()
            },
        )
        AddonExtraAlertSlotRow(
            slot = AddonExtraAlertSlot.ExtraShort,
            levelPct = extraShortPct,
            enabled = extraShortEnabled,
            tradeAtMillis = extraShortTradeAt,
            onToggle = { on ->
                AddonExtraLevelAlertSettings.setEnabled(context, AddonExtraAlertSlot.ExtraShort, on)
                bump()
            },
        )

        Text(
            text = "Алерты спреда",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
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
private fun EntryAlertSideRow(
    side: EntryAlertSide,
    levelPct: Double,
    enabled: Boolean,
    tradeAtMillis: Long?,
    onToggle: (Boolean) -> Unit,
) {
    val sideLabel = when (side) {
        EntryAlertSide.Long -> "Long"
        EntryAlertSide.Short -> "Short"
    }
    val levelText = formatRuSignedNumber(levelPct)
    AlertToggleRow(
        label = "$sideLabel вход $levelText%",
        enabled = enabled,
        tradeAtMillis = tradeAtMillis,
        onToggle = onToggle,
    )
}

@Composable
private fun AddonExtraAlertSlotRow(
    slot: AddonExtraAlertSlot,
    levelPct: Double,
    enabled: Boolean,
    tradeAtMillis: Long?,
    onToggle: (Boolean) -> Unit,
) {
    val kindRu = addonExtraKindLabelRu(slot.kind())
    val sideLabel = when (slot.side()) {
        EntryAlertSide.Long -> "Long"
        EntryAlertSide.Short -> "Short"
    }
    val levelText = formatRuSignedNumber(levelPct)
    AlertToggleRow(
        label = "$kindRu $sideLabel $levelText%",
        enabled = enabled,
        tradeAtMillis = tradeAtMillis,
        onToggle = onToggle,
    )
}

@Composable
private fun AlertToggleRow(
    label: String,
    enabled: Boolean,
    tradeAtMillis: Long?,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = label,
                color = Color(0xFFB0BEC5),
                fontSize = 12.sp,
            )
            if (!enabled && tradeAtMillis != null) {
                Text(
                    text = formatEntryAlertTradeLabel(tradeAtMillis),
                    color = Color(0xFFFFCC80),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF1565C0),
            ),
        )
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
