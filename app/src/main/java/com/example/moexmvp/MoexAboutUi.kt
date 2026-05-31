package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
        Text(
            text = "Сборка ${BuildConfig.VERSION_CODE} · ${BuildConfig.APPLICATION_ID}",
            color = Color(0xFFB3E5FC),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp)
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
        val uriHandler = LocalUriHandler.current
        Text(
            text = "Скачать debug APK",
            color = Color.White,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 18.dp)
        )
        Text(
            text = "Сборки публикуются на GitHub Release moexmvp-debug-latest. " +
                "«Обновить приложение» проверяет версию и скачивает APK. " +
                "При установке Android может запросить «неизвестный источник» (один раз) и PIN/отпечаток — это защита Google, не ошибка приложения.",
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
        Row(modifier = Modifier.padding(top = 10.dp)) {
            Text(
                text = "Прямая ссылка (APK)",
                color = Color(0xFF81D4FA),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable {
                    runCatching { uriHandler.openUri(APK_DOWNLOAD_DIRECT_URL) }
                }
            )
            Spacer(Modifier.width(20.dp))
            Text(
                text = "Все релизы на GitHub",
                color = Color(0xFF81D4FA),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable {
                    runCatching { uriHandler.openUri(APK_GITHUB_RELEASES_PAGE_URL) }
                }
            )
        }
        Text(
            text = "История версий",
            color = Color.White,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 16.dp)
        )
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
