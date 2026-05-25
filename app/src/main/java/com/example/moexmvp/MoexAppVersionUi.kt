package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class ChangelogEntry(val version: String, val summary: String)

internal fun parseAppChangelog(raw: String = APP_CHANGELOG): List<ChangelogEntry> =
    raw.trim().lines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val sep = trimmed.indexOf(" — ")
        if (sep < 0) return@mapNotNull null
        ChangelogEntry(
            version = trimmed.substring(0, sep).trim(),
            summary = trimmed.substring(sep + 3).trim()
        )
    }

internal fun changelogSummaryForBuild(versionName: String = BuildConfig.VERSION_NAME): String? =
    parseAppChangelog().firstOrNull { it.version == versionName }?.summary

/** Компактный блок «версия + что сделано» (как на «О приложении») для вкладок. */
@Composable
internal fun AppVersionBriefCard(
    modifier: Modifier = Modifier,
    tabHint: String? = null
) {
    val releaseNotes = changelogSummaryForBuild()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1A2332), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Версия ${BuildConfig.VERSION_NAME} · сборка ${BuildConfig.VERSION_CODE}",
            color = Color(0xFF81D4FA),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (releaseNotes != null) {
            Text(
                text = "В этой версии: $releaseNotes",
                color = Color(0xFFB0BEC5),
                fontSize = 10.sp,
                lineHeight = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (tabHint != null) {
            Text(
                text = tabHint,
                color = Color(0xFF757575),
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
