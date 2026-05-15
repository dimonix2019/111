package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun TinkoffSandboxTabContent(
    tokenInput: String,
    onTokenInputChange: (String) -> Unit,
    accountInput: String,
    onAccountInputChange: (String) -> Unit,
    onSandboxPrefsChanged: () -> Unit = {},
    onSandboxAccountRecreated: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var payInRub by remember { mutableStateOf("100000") }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf<List<SandboxAccountRow>>(emptyList()) }
    var showToken by remember { mutableStateOf(false) }
    var executeSignalsOnSandbox by remember {
        mutableStateOf(TinkoffSandboxStorage.isExecuteSignalsOnSandbox(context))
    }
    var entryModeAuto by remember {
        mutableStateOf(TinkoffSandboxStorage.isSandboxEntryAuto(context))
    }
    var portfolioRubLine by remember { mutableStateOf<String?>(null) }
    var showResetSandboxDialog by remember { mutableStateOf(false) }
    fun hasToken(): Boolean =
        tokenInput.isNotBlank() || !TinkoffSandboxStorage.getToken(context).isNullOrBlank()

    LaunchedEffect(tokenInput) {
        if (tokenInput.isBlank()) return@LaunchedEffect
        delay(550)
        val err = withContext(Dispatchers.IO) {
            runCatching { TinkoffSandboxStorage.setToken(context, tokenInput) }
                .exceptionOrNull()?.let { "Сохранение токена: ${it.message ?: it.javaClass.simpleName}" }
        }
        if (!err.isNullOrEmpty()) status = err
        onSandboxPrefsChanged()
    }

    LaunchedEffect(accountInput) {
        if (accountInput.isBlank()) return@LaunchedEffect
        delay(550)
        val err = withContext(Dispatchers.IO) {
            runCatching { TinkoffSandboxStorage.setAccountId(context, accountInput) }
                .exceptionOrNull()?.let { "Сохранение счёта: ${it.message ?: it.javaClass.simpleName}" }
        }
        if (!err.isNullOrEmpty()) status = err
        onSandboxPrefsChanged()
    }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            loading = true
            status = ""
            try {
                block()
            } catch (e: Throwable) {
                status = e.message?.takeIf { it.isNotBlank() }
                    ?: "${e.javaClass.simpleName} (см. logcat: TinkoffSandbox)"
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
        if (showResetSandboxDialog) {
            AlertDialog(
                onDismissRequest = { if (!loading) showResetSandboxDialog = false },
                title = { Text("Сбросить счёт песочницы?", color = Color.White) },
                text = {
                    Text(
                        "Текущий демо-счёт будет закрыт на стороне T‑Invest (все позиции и баланс этого счёта удалятся), " +
                            "затем откроется новый пустой счёт. Локальный токен не меняется. Блок «последнее Принять» в портфеле очищается.",
                        color = Color(0xFFB0BEC5),
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showResetSandboxDialog = false
                            run {
                                val typedTok = tokenInput.trim()
                                val newId = withContext(Dispatchers.IO) {
                                    val t = TinkoffSandboxStorage.getToken(context) ?: typedTok
                                    val acc = TinkoffSandboxStorage.getAccountId(context) ?: accountInput.trim()
                                    if (t.isBlank()) throw IllegalArgumentException("Нет токена")
                                    if (acc.isBlank()) throw IllegalArgumentException("Нет accountId — введите или выберите счёт")
                                    val n = tinkoffCloseAndOpenSandboxAccount(t, acc)
                                    TinkoffSandboxStorage.setAccountId(context, n)
                                    TinkoffSandboxSpreadExecLog.clear(context.applicationContext)
                                    n
                                }
                                onAccountInputChange(newId)
                                accounts = withContext(Dispatchers.IO) {
                                    val t = TinkoffSandboxStorage.getToken(context) ?: typedTok
                                    tinkoffGetSandboxAccounts(t)
                                }
                                portfolioRubLine = null
                                status =
                                    "Старый счёт закрыт. Новый accountId (хвост): …${newId.takeLast(10)}"
                                onSandboxPrefsChanged()
                                onSandboxAccountRecreated()
                            }
                        },
                        enabled = !loading
                    ) {
                        Text("Сбросить", color = Color(0xFFFFAB91))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showResetSandboxDialog = false },
                        enabled = !loading
                    ) {
                        Text("Отмена", color = Color(0xFF90CAF9))
                    }
                },
                containerColor = Color(0xFF263238)
            )
        }
        Text(
            text = "T‑Invest · песочница (только клиент)",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Токен режима «песочница» (Т‑Инвест → API). Сохраняется на устройстве (в т.ч. резервная копия для пережития обновлений APK); поля не сбрасываются при переходе на другие вкладки. После ввода — автосохранение ~0,5 с. Локальная сборка: sandbox-token.properties (см. пример). «Bearer …» у токена отрежется само.",
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Исполнять вход по сигналу на демо-счёт (покупка 1 лота + продажа 1 лота по спрэду TATN/TATNP)",
                color = Color(0xFFE0E0E0),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            Switch(
                checked = executeSignalsOnSandbox,
                onCheckedChange = { newVal ->
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                TinkoffSandboxStorage.setExecuteSignalsOnSandbox(context, newVal)
                            }
                            executeSignalsOnSandbox = newVal
                            onSandboxPrefsChanged()
                        } catch (e: Throwable) {
                            status = e.message ?: e.javaClass.simpleName
                        }
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF81D4FA),
                    checkedTrackColor = Color(0xFF0277BD)
                )
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Режим входа (тест)",
                color = Color(0xFFE0E0E0),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ручной",
                    color = if (!entryModeAuto) Color(0xFF81D4FA) else Color(0xFF757575),
                    fontSize = 12.sp,
                    fontWeight = if (!entryModeAuto) FontWeight.SemiBold else FontWeight.Normal
                )
                Switch(
                    checked = entryModeAuto,
                    onCheckedChange = { auto ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    TinkoffSandboxStorage.setSandboxEntryAuto(context, auto)
                                }
                                entryModeAuto = auto
                                onSandboxPrefsChanged()
                            } catch (e: Throwable) {
                                status = e.message ?: e.javaClass.simpleName
                            }
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF81D4FA),
                        checkedTrackColor = Color(0xFF0277BD),
                        uncheckedThumbColor = Color(0xFFB0BEC5),
                        uncheckedTrackColor = Color(0xFF455A64)
                    )
                )
                Text(
                    text = "Авто",
                    color = if (entryModeAuto) Color(0xFF81D4FA) else Color(0xFF757575),
                    fontSize = 12.sp,
                    fontWeight = if (entryModeAuto) FontWeight.SemiBold else FontWeight.Normal
                )
            }
            Text(
                text = if (entryModeAuto) {
                    "Авто: после сигнала входа заявки на песочницу отправляются сами (нужны токен и счёт). По каждой ноге — отдельное уведомление: время, тикер, заявка, номинал и плечо, баланс портфеля после ноги. Карточка «Принять» не показывается."
                } else {
                    "Ручной: уведомление о сигнале и карточка «Принять». Плечо в тексте уведомлений о сделке берётся с вкладки «Портфель» (параметр плеча)."
                },
                color = Color(0xFF757575),
                fontSize = 10.sp
            )
        }

        OutlinedTextField(
            value = tokenInput,
            onValueChange = onTokenInputChange,
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
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                TinkoffSandboxStorage.setToken(context, tokenInput)
                            }
                            status = "Токен сохранён локально."
                            onSandboxPrefsChanged()
                        } catch (e: Throwable) {
                            status = e.message ?: e.javaClass.simpleName
                        }
                    }
                },
                enabled = !loading && tokenInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
            ) {
                Text("Сохранить сейчас")
            }
            Button(
                onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                TinkoffSandboxStorage.setToken(context, null)
                                TinkoffSandboxStorage.setAccountId(context, null)
                            }
                            onTokenInputChange("")
                            onAccountInputChange("")
                            accounts = emptyList()
                            executeSignalsOnSandbox = withContext(Dispatchers.IO) {
                                TinkoffSandboxStorage.isExecuteSignalsOnSandbox(context)
                            }
                            status = "Токен и счёт очищены."
                            onSandboxPrefsChanged()
                        } catch (e: Throwable) {
                            status = e.message ?: e.javaClass.simpleName
                        }
                    }
                },
                enabled = !loading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D4C41))
            ) {
                Text("Очистить")
            }
        }

        OutlinedTextField(
            value = accountInput,
            onValueChange = onAccountInputChange,
            label = { Text("Account ID (подставится после «Открыть счёт»)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    run {
                        val typedTok = tokenInput.trim()
                        val id = withContext(Dispatchers.IO) {
                            val t = TinkoffSandboxStorage.getToken(context) ?: typedTok
                            if (t.isEmpty()) throw IllegalArgumentException("Нет токена")
                            val opened = tinkoffOpenSandboxAccount(t, "MOEX MVP sandbox")
                            TinkoffSandboxStorage.setAccountId(context, opened)
                            opened
                        }
                        onAccountInputChange(id)
                        status = "Счёт открыт: $id"
                        onSandboxPrefsChanged()
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
                        val typedTok = tokenInput.trim()
                        accounts = withContext(Dispatchers.IO) {
                            val t = TinkoffSandboxStorage.getToken(context) ?: typedTok
                            if (t.isEmpty()) throw IllegalArgumentException("Нет токена")
                            tinkoffGetSandboxAccounts(t)
                        }
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
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        TinkoffSandboxStorage.setAccountId(context, row.id)
                                    }
                                    onAccountInputChange(row.id)
                                    status = "Выбран счёт ${row.id}"
                                    onSandboxPrefsChanged()
                                } catch (e: Throwable) {
                                    status = e.message ?: e.javaClass.simpleName
                                }
                            }
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Выбрать", fontSize = 10.sp)
                    }
                }
                Text(row.id, color = Color(0xFF9E9E9E), fontSize = 10.sp)
            }
        }

        OutlinedButton(
            onClick = { showResetSandboxDialog = true },
            enabled = !loading && hasToken() && accountInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFAB91))
        ) {
            Text(
                "Сбросить счёт песочницы (закрыть текущий и открыть новый пустой)",
                fontSize = 12.sp
            )
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
                    val typedTok = tokenInput.trim()
                    val typedAcc = accountInput.trim()
                    val rubStr = payInRub
                    val resp = withContext(Dispatchers.IO) {
                        val t = TinkoffSandboxStorage.getToken(context) ?: typedTok
                        val acc = TinkoffSandboxStorage.getAccountId(context) ?: typedAcc
                        if (t.isEmpty()) throw IllegalArgumentException("Нет токена")
                        if (acc.isEmpty()) throw IllegalArgumentException("Нет accountId")
                        val rub = rubStr.toLongOrNull() ?: throw IllegalArgumentException("Сумма не число")
                        tinkoffSandboxPayIn(t, acc, rub)
                    }
                    status = formatPayInResult(resp)
                    onSandboxPrefsChanged()
                }
            },
            enabled = !loading && hasToken() && accountInput.isNotBlank() && payInRub.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A))
        ) {
            Text("Пополнить песочницу")
        }

        Button(
            onClick = {
                run {
                    portfolioRubLine = null
                    val typedTok = tokenInput.trim()
                    val typedAcc = accountInput.trim()
                    val port = withContext(Dispatchers.IO) {
                        val t = TinkoffSandboxStorage.getToken(context) ?: typedTok
                        val acc = TinkoffSandboxStorage.getAccountId(context) ?: typedAcc
                        if (t.isEmpty()) throw IllegalArgumentException("Нет токена")
                        if (acc.isEmpty()) throw IllegalArgumentException("Нет accountId")
                        tinkoffGetSandboxPortfolio(t, acc)
                    }
                    portfolioRubLine = formatSandboxPortfolioLinesForUi(port)
                }
            },
            enabled = !loading && hasToken() && accountInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00695C))
        ) {
            Text("Запросить портфель песочницы (оценка в ₽)")
        }
        portfolioRubLine?.let { line ->
            Text(line, color = Color(0xFFB2DFDB), fontSize = 12.sp)
        }

        Text(
            text = "Тестовые рыночные заявки — 1 лот TATN. Ниже: всего по портфелю и строка «Деньги (₽)» (после продажи деньги обычно растут).",
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    run {
                        val typedTok = tokenInput.trim()
                        val typedAcc = accountInput.trim()
                        val (order, port) = withContext(Dispatchers.IO) {
                            val t = TinkoffSandboxStorage.getToken(context) ?: typedTok
                            val acc = TinkoffSandboxStorage.getAccountId(context) ?: typedAcc
                            if (t.isEmpty()) throw IllegalArgumentException("Нет токена")
                            if (acc.isEmpty()) throw IllegalArgumentException("Нет accountId")
                            val ord = tinkoffSandboxPostTestSingleLegOrder(t, acc, buy = true)
                            val p = tinkoffGetSandboxPortfolio(t, acc)
                            ord to p
                        }
                        status = "Тест: покупка · ${formatPostSandboxOrderBrief(order)}"
                        portfolioRubLine = formatSandboxPortfolioLinesForUi(port)
                        onSandboxPrefsChanged()
                    }
                },
                enabled = !loading && hasToken() && accountInput.isNotBlank(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
            ) {
                Icon(
                    imageVector = Icons.Filled.TrendingUp,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                    tint = Color.White
                )
                Text("Купить 1 лот", fontSize = 12.sp)
            }
            Button(
                onClick = {
                    run {
                        val typedTok = tokenInput.trim()
                        val typedAcc = accountInput.trim()
                        val (order, port) = withContext(Dispatchers.IO) {
                            val t = TinkoffSandboxStorage.getToken(context) ?: typedTok
                            val acc = TinkoffSandboxStorage.getAccountId(context) ?: typedAcc
                            if (t.isEmpty()) throw IllegalArgumentException("Нет токена")
                            if (acc.isEmpty()) throw IllegalArgumentException("Нет accountId")
                            val ord = tinkoffSandboxPostTestSingleLegOrder(t, acc, buy = false)
                            val p = tinkoffGetSandboxPortfolio(t, acc)
                            ord to p
                        }
                        status = "Тест: продажа · ${formatPostSandboxOrderBrief(order)}"
                        portfolioRubLine = formatSandboxPortfolioLinesForUi(port)
                        onSandboxPrefsChanged()
                    }
                },
                enabled = !loading && hasToken() && accountInput.isNotBlank(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
            ) {
                Icon(
                    imageVector = Icons.Filled.TrendingDown,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                    tint = Color.White
                )
                Text("Продать 1 лот", fontSize = 12.sp)
            }
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
