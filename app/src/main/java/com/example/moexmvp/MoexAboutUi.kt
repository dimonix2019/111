package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
@Composable
internal fun AboutTabContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
            text = "Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            color = Color(0xFFB3E5FC),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
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
