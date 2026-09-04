package com.example.moexmvp

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope

/** Cache-bust so phone WebView picks up latest strategy-web mobile CSS/JS. */
private const val WEB_DESK_UI_CACHE = "apk=1.7.307&v=20260722a1"

/** Full-screen strategy-web desk over Tailscale/LAN — phone-first layout. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MoexScreenTabWebDesk(
    screen: MoexScreenState,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
    modifier: Modifier,
) {
    val context = LocalContext.current
    // Re-read each recomposition so URL saved on «Песочница» applies without restart.
    val base = WebDeskPrefs.normalizedBaseUrl(context)
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    if (base.isNullOrBlank()) {
        Box(
            modifier = modifier.fillMaxSize().background(Color.Black).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Задайте URL стола на вкладке «Песочница» → «Мониторинг web (Tailscale)».\n" +
                    "Пример: http://100.119.122.31:8765\n\n" +
                    "После сохранения откройте «Стол web» — тот же стол, что на ПК, под телефон.",
                color = Color(0xFFFFCC80),
                fontSize = 14.sp,
            )
        }
        return
    }

    val deskUrl = "$base/?$WEB_DESK_UI_CACHE"

    Column(modifier = modifier.fillMaxSize().background(Color(0xFF131722))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E222D))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = base.removePrefix("http://").removePrefix("https://"),
                color = Color(0xFF90A4AE),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            TextButton(
                onClick = {
                    loadError = null
                    val wv = webViewRef
                    if (wv != null) wv.reload() else reloadKey++
                },
            ) {
                Text("Обновить", color = Color(0xFF90CAF9), fontSize = 12.sp)
            }
            TextButton(
                onClick = { screen.showCloseAllPortfolioDialog = true },
            ) {
                Text("Закрыть пару", color = Color(0xFFFFAB91), fontSize = 12.sp)
            }
        }

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

        key(base, reloadKey) {
            AndroidView(
                modifier = Modifier.fillMaxSize().weight(1f, fill = true),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.setSupportZoom(true)
                        // Mobile CSS @media max-width:900px — keep phone viewport.
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = false
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.mediaPlaybackRequiresUserGesture = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }
                        val ua = settings.userAgentString
                        if (!ua.contains("Mobile", ignoreCase = true)) {
                            settings.userAgentString = "$ua Mobile"
                        }
                        isVerticalScrollBarEnabled = true
                        isHorizontalScrollBarEnabled = false
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
                                    val desc = error?.description?.toString().orEmpty()
                                    loadError = when {
                                        desc.contains("ERR_NAME_NOT_RESOLVED", ignoreCase = true) ||
                                            desc.contains("NAME_NOT_RESOLVED", ignoreCase = true) ->
                                            "$desc\n\nИмя хоста не резолвится. В «Песочница» → Web desk URL укажите IP Tailscale ПК, напр. http://100.119.122.31:8765 (не имя note-ai)."
                                        else -> desc.ifBlank { "Ошибка загрузки $base" }
                                    }
                                }
                            }
                        }
                        webViewRef = this
                        loadUrl(deskUrl)
                    }
                },
                update = { view ->
                    webViewRef = view
                },
            )
        }
    }
}
