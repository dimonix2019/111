package com.example.moexmvp

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AboutTabContent(
    modifier: Modifier = Modifier,
    onUpdateFound: ((AppRemoteUpdate) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateCheckText by remember { mutableStateOf<String?>(null) }
    var updateChecking by remember { mutableStateOf(false) }
    var showBrowserFallback by remember { mutableStateOf(false) }
    val entries = parseAppChangelog()
    val currentNotes = changelogSummaryForBuild()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(Color.Black)
            .padding(12.dp)
    ) {
        Text(
            text = "MOEX MVP",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Версия приложения",
            color = Color(0xFF9E9E9E),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            text = BuildConfig.VERSION_NAME,
            color = Color(0xFF81D4FA),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp)
        )
        Button(
            onClick = {
                if (updateChecking) return@Button
                updateChecking = true
                updateCheckText = "Проверка GitHub…"
                showBrowserFallback = false
                scope.launch {
                    try {
                        val status = withContext(Dispatchers.IO) {
                            prepareManualAppUpdateCheck(context)
                            checkAppUpdateStatus()
                        }
                        updateCheckText = formatAppUpdateCheckStatus(status)
                        showBrowserFallback = status is AppUpdateCheckStatus.FetchFailed
                        if (status is AppUpdateCheckStatus.UpdateAvailable) {
                            onUpdateFound?.invoke(status.remote)
                        }
                    } catch (e: Exception) {
                        updateCheckText = "Ошибка проверки: ${e.message ?: e.javaClass.simpleName}"
                        showBrowserFallback = true
                    } finally {
                        updateChecking = false
                    }
                }
            },
            enabled = !updateChecking,
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2E7D32),
                contentColor = Color.White,
                disabledContainerColor = Color(0xFF1B5E20),
                disabledContentColor = Color(0xFFBDBDBD)
            )
        ) {
            Text(
                text = if (updateChecking) "Проверка…" else "Обновить приложение",
                fontWeight = FontWeight.SemiBold
            )
        }
        updateCheckText?.let { msg ->
            Text(
                text = msg,
                color = Color(0xFFBCAAA4),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .background(Color(0xFF1A2332), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            )
        }
        if (showBrowserFallback) {
            OutlinedButton(
                onClick = { runCatching { openAppUpdateInBrowser(context) } },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF81D4FA))
            ) {
                Text("Открыть Release в браузере")
            }
        }
        if (currentNotes != null) {
            Text(
                text = "Что сделано в этой версии",
                color = Color.White,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                text = currentNotes,
                color = Color(0xFFE0E0E0),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .background(Color(0xFF1A2332), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            )
        }
        Text(
            text = "История версий",
            color = Color.White,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 16.dp)
        )
        EventLogSection(modifier = Modifier.padding(top = 16.dp))
        Column(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
        ) {
            entries.forEach { entry ->
                Column {
                    Text(
                        text = entry.version,
                        color = Color(0xFF81D4FA),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = entry.summary,
                        color = Color(0xFFE0E0E0),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EventLogSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf(MoexDiagnostics.formatForDisplay(context, tail = 12)) }
    var lineCount by remember { mutableIntStateOf(MoexDiagnostics.lineCount(context)) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(
            text = "Журнал событий (отладка вылетов)",
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp
        )
        Text(
            text = "Записей: $lineCount · сохраняется между перезапусками",
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = preview,
            color = Color(0xFFB0BEC5),
            fontSize = 10.sp,
            lineHeight = 13.sp,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .background(Color(0xFF121212), RoundedCornerShape(6.dp))
                .padding(8.dp)
        )
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { MoexDiagnostics.shareExport(context) }
                        Toast.makeText(context, "Отправьте файл/text себе (Telegram, почта…)", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text("Экспорт", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { MoexDiagnostics.copyToClipboard(context) }
                        Toast.makeText(
                            context,
                            if (ok) "Скопировано в буфер" else "Журнал пуст",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF81D4FA))
            ) {
                Text("Копировать", fontSize = 12.sp)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = {
                    scope.launch {
                        val (text, count) = withContext(Dispatchers.IO) {
                            MoexDiagnostics.formatForDisplay(context, tail = 12) to
                                MoexDiagnostics.lineCount(context)
                        }
                        preview = text
                        lineCount = count
                    }
                }
            ) {
                Text("Обновить", color = Color(0xFF90CAF9), fontSize = 11.sp)
            }
            TextButton(
                onClick = {
                    MoexDiagnostics.clear(context)
                    preview = MoexDiagnostics.formatForDisplay(context, tail = 12)
                    lineCount = MoexDiagnostics.lineCount(context)
                    Toast.makeText(context, "Журнал событий очищен", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Очистить", color = Color(0xFFFFAB91), fontSize = 11.sp)
            }
        }
    }
}
