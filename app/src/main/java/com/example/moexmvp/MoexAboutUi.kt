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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
@Composable
internal fun AboutTabContent(modifier: Modifier = Modifier) {
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
        val uriHandler = LocalUriHandler.current
        Text(
            text = "Скачать debug APK",
            color = Color.White,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 18.dp)
        )
        Text(
            text = "Если по ссылке 404 на телефоне: чаще всего репозиторий на GitHub закрытый (private) — для него без входа GitHub отдаёт 404. Откройте ссылку в браузере под аккаунтом с доступом к репозиторию или сделайте репозиторий публичным. Файл появляется только после успешного прогона Actions «Build APK» (релиз moexmvp-debug-latest).",
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp,
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
            text = "Изменения",
            color = Color.White,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = APP_CHANGELOG.trim(),
            color = Color(0xFFE0E0E0),
            fontSize = 12.sp,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                .padding(10.dp)
        )
    }
}
