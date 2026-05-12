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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import java.util.Locale

@Composable
internal fun JournalTabContent(
    events: List<StrategySignalEvent>,
    modifier: Modifier = Modifier
) {
    var typeFilter by remember { mutableIntStateOf(0) }
    var dayFilter by remember { mutableStateOf<LocalDate?>(null) }
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
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "Журнал сигналов",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip("Все", selected = typeFilter == 0) { typeFilter = 0 }
                FilterChip("Вход LONG", selected = typeFilter == 1) { typeFilter = 1 }
                FilterChip("Вход SHORT", selected = typeFilter == 2) { typeFilter = 2 }
                FilterChip("Выходы", selected = typeFilter == 3) { typeFilter = 3 }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val today = LocalDate.now(zone)
                FilterChip("Сегодня", selected = dayFilter == today) { dayFilter = today }
                FilterChip("Все дни", selected = dayFilter == null) { dayFilter = null }
            }
        }
        itemsIndexed(
            items = filtered,
            key = { index, ev -> "${index}_${ev.timestampMillis}_${ev.signalType.name}" }
        ) { _, ev ->
            val ts = Instant.ofEpochMilli(ev.timestampMillis).atZone(zone)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    "${ts.toLocalDate()} ${ts.toLocalTime()} · ${ev.signalType.name}",
                    color = Color(0xFFE0E0E0),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    String.format(Locale.US, "Z = %.2f", ev.zScore),
                    color = Color(0xFF9E9E9E),
                    fontSize = 12.sp
                )
            }
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
