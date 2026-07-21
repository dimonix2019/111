package com.example.moexmvp

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch

/** Card on «Счёт» / Sandbox: Tailscale web desk URL + monitor toggles. */
@Composable
internal fun WebDeskMonitorCard(
    onOrdersOnWebChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var urlInput by remember { mutableStateOf(WebDeskPrefs.baseUrl(context)) }
    var monitorOn by remember { mutableStateOf(WebDeskPrefs.isMonitorEnabled(context)) }
    var ordersOnWeb by remember {
        mutableStateOf(
            if (WebDeskPrefs.isMonitorEnabled(context)) {
                WebDeskPrefs.isOrdersOnWebOnly(context)
            } else {
                true
            },
        )
    }
    var healthLine by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A237E))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Мониторинг web (Tailscale)",
            color = Color(0xFFBBDEFB),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "URL стола на ПК, напр. http://100.x.x.x:8765. Ордера — только на web.",
            color = Color(0xFF90A4AE),
            fontSize = 11.sp,
        )
        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Web desk URL") },
            placeholder = { Text("http://100.64.0.12:8765") },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Опрос + push с web", color = Color.White, fontSize = 12.sp)
            Switch(
                checked = monitorOn,
                onCheckedChange = { enabled ->
                    WebDeskPrefs.setBaseUrl(context, urlInput)
                    WebDeskPrefs.setMonitorEnabled(context, enabled)
                    monitorOn = enabled
                    if (enabled) {
                        WebDeskPrefs.setOrdersOnWebOnly(context, true)
                        ordersOnWeb = true
                        WebDeskPrefs.setLastEventId(context, 0L)
                        forcePhoneAutoOffForWebDesk(context)
                        onOrdersOnWebChanged()
                        SignalForegroundService.start(context)
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF1565C0),
                ),
            )
        }
        if (monitorOn) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Ордера только на web\n(AUTO на телефоне выкл.)",
                    color = Color(0xFFFFCC80),
                    fontSize = 11.sp,
                )
                Switch(
                    checked = ordersOnWeb,
                    onCheckedChange = { v ->
                        WebDeskPrefs.setOrdersOnWebOnly(context, v)
                        ordersOnWeb = v
                        if (v) {
                            forcePhoneAutoOffForWebDesk(context)
                            onOrdersOnWebChanged()
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFFEF6C00),
                    ),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    WebDeskPrefs.setBaseUrl(context, urlInput)
                    scope.launch {
                        checking = true
                        healthLine = null
                        val result = WebDeskApi.fetchHealthLive(context)
                        healthLine = result.fold(
                            onSuccess = { h ->
                                buildString {
                                    append(if (h.ok) "OK" else "FAIL")
                                    append(" · monitor=")
                                    append(if (h.monitorAlive) "alive" else "down")
                                    if (h.stale) append(" · STALE")
                                    h.lastBar?.let { append(" · $it") }
                                    h.lastZ?.let { append(" · Z=${"%.2f".format(it)}") }
                                }
                            },
                            onFailure = { e ->
                                "Недоступен: ${e.message ?: e.javaClass.simpleName}. Проверьте Tailscale и run-replay-web.bat"
                            },
                        )
                        checking = false
                    }
                },
                enabled = !checking,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
            ) {
                Text(if (checking) "…" else "Проверить")
            }
            Button(
                onClick = {
                    WebDeskPrefs.setBaseUrl(context, urlInput)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
            ) {
                Text("Сохранить URL")
            }
        }
        healthLine?.let { line ->
            Text(
                text = line,
                color = if (line.startsWith("OK")) Color(0xFFA5D6A7) else Color(0xFFEF9A9A),
                fontSize = 11.sp,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal fun forcePhoneAutoOffForWebDesk(context: Context) {
    if (!WebDeskPrefs.isOrdersOnWebOnly(context)) return
    TinkoffSandboxStorage.setSandboxSpreadAutoExecute(context, false)
}
