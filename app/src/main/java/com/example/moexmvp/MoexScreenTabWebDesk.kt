package com.example.moexmvp

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope

/** WebView of strategy-web desk ({base}/) over Tailscale/LAN. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MoexScreenTabWebDesk(
    @Suppress("UNUSED_PARAMETER") screen: MoexScreenState,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val base = remember { WebDeskPrefs.normalizedBaseUrl(context) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    if (base.isNullOrBlank()) {
        Box(
            modifier = modifier.fillMaxSize().background(Color.Black).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Задайте URL стола на вкладке «Песочница» → «Мониторинг web (Tailscale)».\nПример: http://100.x.x.x:8765",
                color = Color(0xFFFFCC80),
                fontSize = 14.sp,
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        loadError?.let { err ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF4E342E))
                    .padding(10.dp),
            ) {
                Text(
                    text = "Tailscale / сервер недоступен",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Text(text = err, color = Color(0xFFFFCCBC), fontSize = 11.sp)
                Button(
                    onClick = {
                        loadError = null
                        reloadKey++
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Text("Повторить")
                }
            }
        }

        key(reloadKey) {
            AndroidView(
                modifier = Modifier.fillMaxSize().weight(1f, fill = true),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loadError = null
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loadError = error?.description?.toString()
                                        ?: "Ошибка загрузки $base"
                                }
                            }
                        }
                        loadUrl("$base/")
                    }
                },
            )
        }
    }
}
