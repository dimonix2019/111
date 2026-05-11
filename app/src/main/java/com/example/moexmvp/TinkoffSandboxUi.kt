package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun TinkoffSandboxTabContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tokenInput by remember { mutableStateOf("") }
    var accountInput by remember { mutableStateOf("") }
    var payInRub by remember { mutableStateOf("100000") }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf<List<SandboxAccountRow>>(emptyList()) }
    var showToken by remember { mutableStateOf(false) }
    fun hasToken(): Boolean =
        tokenInput.isNotBlank() || !TinkoffSandboxStorage.getToken(context).isNullOrBlank()

    LaunchedEffect(Unit) {
        val saved = TinkoffSandboxStorage.getToken(context)
        if (!saved.isNullOrEmpty()) {
            tokenInput = saved
        }
        val acc = TinkoffSandboxStorage.getAccountId(context)
        if (!acc.isNullOrEmpty()) {
            accountInput = acc
        }
    }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            loading = true
            status = ""
            try {
                block()
            } catch (e: Exception) {
                status = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .background(Color.Black)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "T‑Invest · песочница (только клиент)",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Токен песочницы выпускается в настройках Т‑Инвест (раздел API). Хранится зашифрованно на устройстве. Без вашего сервера.",
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp
        )

        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text("Sandbox token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Button(
            onClick = { showToken = !showToken },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242))
        ) {
            Text(if (showToken) "Скрыть токен" else "Показать токен", fontSize = 11.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    TinkoffSandboxStorage.setToken(context, tokenInput)
                    status = "Токен сохранён локально."
                },
                enabled = !loading && tokenInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
            ) {
                Text("Сохранить токен")
            }
            Button(
                onClick = {
                    TinkoffSandboxStorage.setToken(context, null)
                    TinkoffSandboxStorage.setAccountId(context, null)
                    tokenInput = ""
                    accountInput = ""
                    accounts = emptyList()
                    status = "Токен и счёт очищены."
                },
                enabled = !loading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D4C41))
            ) {
                Text("Очистить")
            }
        }

        OutlinedTextField(
            value = accountInput,
            onValueChange = { accountInput = it },
            label = { Text("Account ID (подставится после «Открыть счёт»)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    run {
                        val t = TinkoffSandboxStorage.getToken(context) ?: tokenInput.trim()
                        if (t.isEmpty()) throw IllegalArgumentException("Нет токена")
                        val id = tinkoffOpenSandboxAccount(t, "MOEX MVP sandbox")
                        TinkoffSandboxStorage.setAccountId(context, id)
                        accountInput = id
                        status = "Счёт открыт: $id"
                    }
                },
                enabled = !loading && hasToken(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
            ) {
                Text("Открыть счёт")
            }
            Button(
                onClick = {
                    run {
                        val t = TinkoffSandboxStorage.getToken(context) ?: tokenInput.trim()
                        if (t.isEmpty()) throw IllegalArgumentException("Нет токена")
                        accounts = tinkoffGetSandboxAccounts(t)
                        status = "Счетов: ${accounts.size}"
                    }
                },
                enabled = !loading && hasToken(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))
            ) {
                Text("Список счетов")
            }
        }

        if (accounts.isNotEmpty()) {
            Text("Счета:", color = Color(0xFFB3E5FC), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            accounts.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(row.name.ifBlank { "—" }, color = Color(0xFFE0E0E0), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            accountInput = row.id
                            TinkoffSandboxStorage.setAccountId(context, row.id)
                            status = "Выбран счёт ${row.id}"
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Выбрать", fontSize = 10.sp)
                    }
                }
                Text(row.id, color = Color(0xFF9E9E9E), fontSize = 10.sp)
            }
        }

        OutlinedTextField(
            value = payInRub,
            onValueChange = { payInRub = it.filter { ch -> ch.isDigit() } },
            label = { Text("Пополнение, ₽ (целое)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Button(
            onClick = {
                run {
                    val t = TinkoffSandboxStorage.getToken(context) ?: tokenInput.trim()
                    val acc = TinkoffSandboxStorage.getAccountId(context) ?: accountInput.trim()
                    if (t.isEmpty()) throw IllegalArgumentException("Нет токена")
                    if (acc.isEmpty()) throw IllegalArgumentException("Нет accountId")
                    val rub = payInRub.toLongOrNull() ?: throw IllegalArgumentException("Сумма не число")
                    val resp = tinkoffSandboxPayIn(t, acc, rub)
                    status = formatPayInResult(resp)
                }
            },
            enabled = !loading && hasToken() && accountInput.isNotBlank() && payInRub.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A))
        ) {
            Text("Пополнить песочницу")
        }

        if (loading) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Color(0xFF64B5F6), strokeWidth = 2.dp)
            }
        }
        if (status.isNotBlank()) {
            Text(status, color = Color(0xFFFFCC80), fontSize = 12.sp)
        }
    }
}

private fun formatPayInResult(resp: JSONObject): String {
    val bal = resp.optJSONObject("balance")
        ?: resp.optJSONObject("Balance")
        ?: resp.optJSONObject("portfolio")
    if (bal != null) {
        val units = bal.optString("units", bal.optString("Units", ""))
        val cur = bal.optString("currency", bal.optString("Currency", ""))
        if (units.isNotEmpty()) return "Ок. Баланс: $units ${cur.ifBlank { "RUB" }}"
    }
    return "Ок: $resp"
}
