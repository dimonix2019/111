package com.example.moexmvp

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
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
    var preview by remember { mutableStateOf("Загрузка журнала…") }
    var lineCount by remember { mutableIntStateOf(0) }
    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { MoexDiagnostics.writeExportToUri(context, uri) }
            Toast.makeText(
                context,
                if (ok) "Журнал сохранён в выбранный файл" else "Не удалось записать файл",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    LaunchedEffect(Unit) {
        val (text, count) = withContext(Dispatchers.IO) {
            MoexDiagnostics.formatForDisplay(context, tail = 12) to MoexDiagnostics.lineCount(context)
        }
        preview = text
        lineCount = count
    }
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
            text = "Записей: $lineCount · ANR «не отвечает» и вылеты пишутся в файл",
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
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        val path = withContext(Dispatchers.IO) {
                            MoexDiagnostics.saveExportToDownloads(context)
                        }
                        when {
                            path != null -> Toast.makeText(
                                context,
                                "Сохранено: $path",
                                Toast.LENGTH_LONG,
                            ).show()
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                                Toast.makeText(context, "Не удалось сохранить в Загрузки", Toast.LENGTH_SHORT).show()
                            else -> saveAsLauncher.launch(MoexDiagnostics.eventLogExportFileName())
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            ) {
                Text("Скачать", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = {
                    saveAsLauncher.launch(MoexDiagnostics.eventLogExportFileName())
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF81D4FA))
            ) {
                Text("Сохранить", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { MoexDiagnostics.shareExportFile(context) }
                        if (!ok) {
                            Toast.makeText(context, "Журнал пуст", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCE93D8))
            ) {
                Text("Файл", fontSize = 11.sp)
            }
        }
        Row(
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
            OutlinedButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { MoexDiagnostics.shareExport(context) }
                        Toast.makeText(context, "Отправьте текст (Telegram, почта…)", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB0BEC5))
            ) {
                Text("Текст", fontSize = 12.sp)
            }
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    val csv = withContext(Dispatchers.IO) { TradeExecutionLog.exportCsv(context) }
                    if (csv.lines().size <= 1) {
                        Toast.makeText(context, "Лог сделок пуст", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clip.setPrimaryClip(
                        android.content.ClipData.newPlainText("moex_trade_log.csv", csv)
                    )
                    Toast.makeText(
                        context,
                        "CSV лога сделок (${csv.lines().size - 1} ног) в буфере",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF80CBC4)),
        ) {
            Text("CSV лог сделок (цена/slip/частично)", fontSize = 12.sp)
        }
        Text(
            text = "Сравнение Prod vs Тест страт.",
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = "Одинаковые столбцы CSV — удобно искать расхождения в Excel перед запуском на больших суммах.",
            color = Color(0xFF9E9E9E),
            fontSize = 10.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val csv = withContext(Dispatchers.IO) { exportProdTradesCompareCsv(context) }
                        if (!copyCsvToClipboard(context, csv, "moex_prod_trades.csv")) {
                            Toast.makeText(context, "Нет закрытых Prod-сделок", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        Toast.makeText(
                            context,
                            "CSV Prod (${tradeCompareRowCount(csv)} сделок) в буфере",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF81D4FA)),
            ) {
                Text("CSV Prod", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val csv = withContext(Dispatchers.IO) {
                            StrategyTestExportStore.loadCompareCsv(context)
                        }
                        if (csv == null || !copyCsvToClipboard(context, csv, "moex_sim_trades.csv")) {
                            Toast.makeText(
                                context,
                                "Сначала откройте «Тест страт.» и дождитесь симуляции",
                                Toast.LENGTH_LONG,
                            ).show()
                            return@launch
                        }
                        Toast.makeText(
                            context,
                            "CSV Тест страт. (${tradeCompareRowCount(csv)} сделок) в буфере",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF80CBC4)),
            ) {
                Text("CSV Тест страт.", fontSize = 11.sp)
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
                    scope.launch {
                        withContext(Dispatchers.IO) { MoexDiagnostics.clear(context) }
                        val (text, count) = withContext(Dispatchers.IO) {
                            MoexDiagnostics.formatForDisplay(context, tail = 12) to
                                MoexDiagnostics.lineCount(context)
                        }
                        preview = text
                        lineCount = count
                        Toast.makeText(context, "Журнал событий очищен", Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Text("Очистить", color = Color(0xFFFFAB91), fontSize = 11.sp)
            }
        }
    }
}
