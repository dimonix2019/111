package com.example.moexmvp

import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** В UI логов на «Настройках» — только хвост; полный журнал остаётся в файле/prefs. */
internal const val SETTINGS_LOG_UI_TAIL = 20

internal fun takeLogUiTail(lines: List<String>, tail: Int = SETTINGS_LOG_UI_TAIL): List<String> =
    if (tail <= 0) lines else lines.takeLast(tail)

internal enum class SettingsSection(val label: String) {
    Thresholds("Пороги"),
    AppLog("Лог приложения"),
    ExchangeLog("Лог биржи"),
}

private data class SettingsLogBackend(
    val title: String,
    val writingHintOn: String,
    val writingHintOff: String,
    val toastWritingOn: String,
    val toastWritingOff: String,
    val toastCleared: String,
    val isWritingEnabled: (Context) -> Boolean,
    val setWritingEnabled: (Context, Boolean) -> Unit,
    val formatPreview: (Context) -> String,
    val lineCount: (Context) -> Int,
    val saveToDownloads: (Context) -> String?,
    val writeToUri: (Context, Uri) -> Boolean,
    val shareFile: (Context) -> Boolean,
    val copyToClipboard: (Context) -> Boolean,
    val shareText: (Context) -> Unit,
    val clear: (Context) -> Unit,
    val exportFileName: () -> String,
)

@Composable
internal fun SettingsTabContent(modifier: Modifier = Modifier) {
    var section by remember { mutableStateOf(SettingsSection.Thresholds) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp),
    ) {
        Text(
            text = "Настройки",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SettingsSection.values().forEach { item ->
                val selected = item == section
                Button(
                    onClick = { section = item },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Color(0xFF1565C0) else Color(0xFF424242),
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = item.label,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        when (section) {
            SettingsSection.Thresholds -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp),
                ) {
                    SpreadLevelAlertsSettingsCard()
                }
            }
            SettingsSection.AppLog -> SettingsLogPanel(
                backend = appEventLogBackend(),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp),
            )
            SettingsSection.ExchangeLog -> SettingsLogPanel(
                backend = exchangeLogBackend(),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp),
            )
        }
    }
}

private fun appEventLogBackend(): SettingsLogBackend = SettingsLogBackend(
    title = "Лог приложения",
    writingHintOn = "Вкл — новые события пишутся в файл журнала.",
    writingHintOff = "Выкл — новые события не копятся (по умолчанию). Logcat всё равно идёт.",
    toastWritingOn = "Запись лога приложения включена",
    toastWritingOff = "Запись лога приложения выключена",
    toastCleared = "Лог приложения очищен",
    isWritingEnabled = { MoexDiagnostics.isEventLogWritingEnabled(it) },
    setWritingEnabled = { ctx, on -> MoexDiagnostics.setEventLogWritingEnabled(ctx, on) },
    formatPreview = { MoexDiagnostics.formatForDisplay(it, tail = SETTINGS_LOG_UI_TAIL) },
    lineCount = { MoexDiagnostics.lineCount(it) },
    saveToDownloads = { MoexDiagnostics.saveExportToDownloads(it) },
    writeToUri = { ctx, uri -> MoexDiagnostics.writeExportToUri(ctx, uri) },
    shareFile = { MoexDiagnostics.shareExportFile(it) },
    copyToClipboard = { MoexDiagnostics.copyToClipboard(it) },
    shareText = { MoexDiagnostics.shareExport(it) },
    clear = { MoexDiagnostics.clear(it) },
    exportFileName = { MoexDiagnostics.eventLogExportFileName() },
)

private fun exchangeLogBackend(): SettingsLogBackend = SettingsLogBackend(
    title = "Лог биржи",
    writingHintOn = "Вкл — ответы PostOrder / GetMaxLots пишутся в журнал биржи.",
    writingHintOff = "Выкл — ответы биржи не копятся. Logcat всё равно идёт.",
    toastWritingOn = "Запись лога биржи включена",
    toastWritingOff = "Запись лога биржи выключена",
    toastCleared = "Лог биржи очищен",
    isWritingEnabled = { BrokerExchangeReplyLog.isWritingEnabled(it) },
    setWritingEnabled = { ctx, on -> BrokerExchangeReplyLog.setWritingEnabled(ctx, on) },
    formatPreview = { BrokerExchangeReplyLog.formatForDisplay(it, tail = SETTINGS_LOG_UI_TAIL) },
    lineCount = { BrokerExchangeReplyLog.lineCount(it) },
    saveToDownloads = { BrokerExchangeReplyLog.saveExportToDownloads(it) },
    writeToUri = { ctx, uri -> BrokerExchangeReplyLog.writeExportToUri(ctx, uri) },
    shareFile = { BrokerExchangeReplyLog.shareExportFile(it) },
    copyToClipboard = { BrokerExchangeReplyLog.copyToClipboard(it) },
    shareText = { BrokerExchangeReplyLog.shareExport(it) },
    clear = { BrokerExchangeReplyLog.clear(it) },
    exportFileName = { BrokerExchangeReplyLog.exportFileName() },
)

@Composable
private fun SettingsLogPanel(
    backend: SettingsLogBackend,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf("Загрузка журнала…") }
    var lineCount by remember { mutableIntStateOf(0) }
    var writingEnabled by remember { mutableStateOf(backend.isWritingEnabled(context)) }
    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { backend.writeToUri(context, uri) }
            Toast.makeText(
                context,
                if (ok) "Журнал сохранён в выбранный файл" else "Не удалось записать файл",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    suspend fun reload() {
        val (text, count) = withContext(Dispatchers.IO) {
            backend.formatPreview(context) to backend.lineCount(context)
        }
        preview = text
        lineCount = count
        writingEnabled = backend.isWritingEnabled(context)
    }
    LaunchedEffect(backend.title) { reload() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(
            text = backend.title,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "Запись в журнал",
                    color = Color(0xFFE0E0E0),
                    fontSize = 12.sp,
                )
                Text(
                    text = if (writingEnabled) backend.writingHintOn else backend.writingHintOff,
                    color = Color(0xFF9E9E9E),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(
                checked = writingEnabled,
                onCheckedChange = { enabled ->
                    backend.setWritingEnabled(context, enabled)
                    writingEnabled = enabled
                    Toast.makeText(
                        context,
                        if (enabled) backend.toastWritingOn else backend.toastWritingOff,
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF2E7D32),
                ),
            )
        }
        Text(
            text = "Записей: $lineCount · в приложении последние $SETTINGS_LOG_UI_TAIL",
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
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
                .padding(8.dp),
        )
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = {
                    scope.launch {
                        val path = withContext(Dispatchers.IO) { backend.saveToDownloads(context) }
                        when {
                            path != null -> Toast.makeText(context, "Сохранено: $path", Toast.LENGTH_LONG).show()
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                                Toast.makeText(context, "Не удалось сохранить в Загрузки", Toast.LENGTH_SHORT).show()
                            else -> saveAsLauncher.launch(backend.exportFileName())
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
            ) {
                Text("Скачать", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = { saveAsLauncher.launch(backend.exportFileName()) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF81D4FA)),
            ) {
                Text("Сохранить", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { backend.shareFile(context) }
                        if (!ok) {
                            Toast.makeText(context, "Журнал пуст", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCE93D8)),
            ) {
                Text("Файл", fontSize = 11.sp)
            }
        }
        Row(
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { backend.copyToClipboard(context) }
                        Toast.makeText(
                            context,
                            if (ok) "Скопировано в буфер" else "Журнал пуст",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF81D4FA)),
            ) {
                Text("Копировать", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { backend.shareText(context) }
                        Toast.makeText(context, "Отправьте текст (Telegram, почта…)", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB0BEC5)),
            ) {
                Text("Текст", fontSize = 12.sp)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { scope.launch { reload() } },
            ) {
                Text("Обновить", color = Color(0xFF90CAF9), fontSize = 11.sp)
            }
            TextButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { backend.clear(context) }
                        reload()
                        Toast.makeText(context, backend.toastCleared, Toast.LENGTH_SHORT).show()
                    }
                },
            ) {
                Text("Очистить", color = Color(0xFFFFAB91), fontSize = 11.sp)
            }
        }
    }
}
