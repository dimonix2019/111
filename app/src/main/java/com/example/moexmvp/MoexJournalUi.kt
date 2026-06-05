package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val JOURNAL_SECTION_SIGNALS = 0
private const val JOURNAL_SECTION_NOTIFICATIONS = 1

@Composable
internal fun JournalTabContent(
    events: List<StrategySignalEvent>,
    pushNotifications: List<PushNotificationLogEntry>,
    entryThreshold: Double,
    exitThreshold: Double,
    modifier: Modifier = Modifier,
    onClearHistoryRequest: () -> Unit = {},
    onClearPushLogRequest: () -> Unit = {}
) {
    var journalSection by remember { mutableIntStateOf(JOURNAL_SECTION_SIGNALS) }
    var typeFilter by remember { mutableIntStateOf(0) }
    var dayFilter by remember { mutableStateOf<LocalDate?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showClearPushDialog by remember { mutableStateOf(false) }
    val zone = ZoneId.of("Europe/Moscow")
    val filtered = remember(events, typeFilter, dayFilter) {
        events.asSequence().filter { ev ->
            when (typeFilter) {
                1 -> if (ev.signalType != StrategySignalType.EnterLong) return@filter false
                2 -> if (ev.signalType != StrategySignalType.EnterShort) return@filter false
                3 -> if (ev.signalType != StrategySignalType.ExitLong &&
                    ev.signalType != StrategySignalType.ExitShort
                ) {
                    return@filter false
                }
            }
            if (dayFilter != null) {
                val d = Instant.ofEpochMilli(ev.timestampMillis).atZone(zone).toLocalDate()
                if (d != dayFilter) return@filter false
            }
            true
        }.toList().asReversed()
    }
    val filteredPush = remember(pushNotifications, dayFilter) {
        pushNotifications.asSequence()
            .filter { entry ->
                if (dayFilter != null) {
                    val d = Instant.ofEpochMilli(entry.wallTimestampMillis).atZone(zone).toLocalDate()
                    if (d != dayFilter) return@filter false
                }
                true
            }
            .toList()
            .asReversed()
    }
    Column(modifier) {
        if (showClearPushDialog) {
            AlertDialog(
                onDismissRequest = { showClearPushDialog = false },
                title = { Text("Очистить журнал уведомлений?", color = Color.White) },
                text = {
                    Text(
                        "Будут удалены все локальные записи попыток показа push (включая ID уведомлений). " +
                            "Журнал сигналов и сделки на портфеле не меняются.",
                        color = Color(0xFFB0BEC5),
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearPushDialog = false
                            onClearPushLogRequest()
                        }
                    ) {
                        Text("Очистить", color = Color(0xFFFFAB91))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearPushDialog = false }) {
                        Text("Отмена", color = Color(0xFF90CAF9))
                    }
                },
                containerColor = Color(0xFF263238)
            )
        }
        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text("Очистить историю сигналов?", color = Color.White) },
                text = {
                    Text(
                        "Будут удалены все записи журнала (входы и выходы), сброшена сохранённая позиция Z в FLAT, " +
                            "карточка «Принять» и локальный лог последнего спрэда. Токен и счёт песочницы не меняются.",
                        color = Color(0xFFB0BEC5),
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearDialog = false
                            onClearHistoryRequest()
                        }
                    ) {
                        Text("Очистить", color = Color(0xFFFFAB91))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text("Отмена", color = Color(0xFF90CAF9))
                    }
                },
                containerColor = Color(0xFF263238)
            )
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (journalSection == JOURNAL_SECTION_NOTIFICATIONS) "Журнал уведомлений"
                    else "Журнал сигналов",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = {
                        if (journalSection == JOURNAL_SECTION_NOTIFICATIONS) {
                            showClearPushDialog = true
                        } else {
                            showClearDialog = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5D4037)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        if (journalSection == JOURNAL_SECTION_NOTIFICATIONS) "Очистить push"
                        else "Очистить историю",
                        fontSize = 11.sp,
                        color = Color.White
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip("Сигналы", selected = journalSection == JOURNAL_SECTION_SIGNALS) {
                    journalSection = JOURNAL_SECTION_SIGNALS
                }
                FilterChip("Уведомления", selected = journalSection == JOURNAL_SECTION_NOTIFICATIONS) {
                    journalSection = JOURNAL_SECTION_NOTIFICATIONS
                }
            }
        }
        if (journalSection == JOURNAL_SECTION_SIGNALS) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip("Все", selected = typeFilter == 0) { typeFilter = 0 }
                    FilterChip("Вход LONG", selected = typeFilter == 1) { typeFilter = 1 }
                    FilterChip("Вход SHORT", selected = typeFilter == 2) { typeFilter = 2 }
                    FilterChip("Выходы", selected = typeFilter == 3) { typeFilter = 3 }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val today = LocalDate.now(zone)
                FilterChip("Сегодня", selected = dayFilter == today) { dayFilter = today }
                FilterChip("Все дни", selected = dayFilter == null) { dayFilter = null }
            }
        }
        if (journalSection == JOURNAL_SECTION_SIGNALS) {
            itemsIndexed(
                items = filtered,
                key = { index, ev -> "sig_${index}_${ev.timestampMillis}_${ev.signalType.name}" }
            ) { _, ev ->
                val view = remember(ev, events, pushNotifications, entryThreshold, exitThreshold) {
                    buildStrategySignalJournalPushView(
                        event = ev,
                        pushLog = pushNotifications,
                        entryThreshold = entryThreshold,
                        exitThreshold = exitThreshold,
                        allJournalEvents = events,
                    )
                }
                JournalSignalOrPushCard(
                    receivedAtMillis = view.receivedAtMillis,
                    signalId = view.signalId,
                    pushIdText = view.pushIdText,
                    title = view.title,
                    messageBody = view.messageBody,
                    pushStatusText = view.pushStatusText,
                    pushPosted = view.pushPosted,
                )
            }
        } else {
            if (filteredPush.isEmpty()) {
                item {
                    Text(
                        "Нет записей push (или нет за выбранный день).",
                        color = Color(0xFF9E9E9E),
                        fontSize = 12.sp
                    )
                }
            }
            itemsIndexed(
                items = filteredPush,
                key = { index, entry -> "push_${index}_${entry.wallTimestampMillis}_${entry.title.hashCode()}" }
            ) { _, entry ->
                val signalId = entry.virtualTapBarTimestampMillis?.let { barTs ->
                    entry.virtualTapSignalType?.let { typeName ->
                        runCatching { StrategySignalType.valueOf(typeName) }.getOrNull()?.let { type ->
                            strategySignalDisplay(
                                barTimestampMillis = barTs,
                                signalType = type,
                                receivedAtMillis = entry.wallTimestampMillis,
                                journalEvents = events,
                            ).signalId
                        }
                    }
                } ?: "—"
                JournalSignalOrPushCard(
                    receivedAtMillis = entry.wallTimestampMillis,
                    signalId = signalId,
                    pushIdText = formatPushNotificationIdDisplay(entry),
                    title = entry.title,
                    messageBody = strategySignalPushBodyForDisplay(entry.body),
                    pushStatusText = pushLogStatusText(entry),
                    pushPosted = entry.posted,
                    correlationTag = entry.correlationTag,
                )
            }
        }
        }
    }
}

private fun pushLogStatusText(entry: PushNotificationLogEntry): String = when {
    entry.posted -> "Показано в шторке"
    entry.skipReason == PushNotificationLogSkipReason.DUPLICATE_WITHIN_WINDOW ->
        "Пропущено (дубликат)"
    entry.skipReason == PushNotificationLogSkipReason.POST_NOTIFICATIONS_DENIED ->
        "Пропущено (нет разрешения)"
    entry.skipReason != null -> "Пропущено (${entry.skipReason})"
    else -> "Не показано"
}

@Composable
private fun JournalSignalOrPushCard(
    receivedAtMillis: Long,
    signalId: String,
    pushIdText: String,
    title: String,
    messageBody: String,
    pushStatusText: String?,
    pushPosted: Boolean?,
    correlationTag: String? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(
            formatMessageReceivedLine(receivedAtMillis),
            color = Color(0xFF81D4FA),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            "Push ID: $pushIdText",
            color = Color(0xFFB3E5FC),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "ID сигнала: $signalId",
            color = Color(0xFFCE93D8),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            title,
            color = Color(0xFFE0E0E0),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        if (pushStatusText != null) {
            Text(
                pushStatusText,
                color = when (pushPosted) {
                    true -> Color(0xFF81C784)
                    false -> Color(0xFFFFAB91)
                    null -> Color(0xFF9E9E9E)
                },
                fontSize = 11.sp
            )
        }
        if (!correlationTag.isNullOrBlank()) {
            Text(
                "Тег: $correlationTag",
                color = Color(0xFF757575),
                fontSize = 10.sp,
                maxLines = 2
            )
        }
        if (messageBody.isNotBlank()) {
            Text(
                messageBody,
                color = Color(0xFF9E9E9E),
                fontSize = 11.sp,
                maxLines = 6
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF1565C0) else Color(0xFF424242),
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, fontSize = 11.sp)
    }
}
