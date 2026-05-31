package com.example.moexmvp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Пояснение: системные экраны Google/Android при sideload нельзя отключить из приложения. */
internal const val APP_UPDATE_ANDROID_INSTALL_HINT =
    "MOEX MVP ставится с GitHub, не из Play Store. Android покажет:\n" +
        "• один раз — «Установка из неизвестных источников» (включите для MOEX MVP);\n" +
        "• при каждой установке — PIN или отпечаток (защита Google, это нормально).\n" +
        "Если в приложении много лишних экранов — проще «Через браузер»: скачайте APK и установите из «Загрузок»."

@Composable
internal fun AppUpdateDialogHost(
    pendingUpdate: AppRemoteUpdate?,
    onDismiss: (AppRemoteUpdate) -> Unit,
    onInstalledOffer: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadIndeterminate by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var readyApk by remember { mutableStateOf<File?>(null) }
    var canInstall by remember { mutableStateOf(canInstallPackages(context)) }
    var sentToInstallSettings by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canInstall = canInstallPackages(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val update = pendingUpdate ?: return

    fun resetDownloadState() {
        downloading = false
        downloadIndeterminate = true
        downloadProgress = 0f
        errorText = null
        readyApk = null
    }

    AlertDialog(
        onDismissRequest = {
            if (!downloading) onDismiss(update)
        },
        title = {
            Text(
                text = if (readyApk != null) "Обновление готово" else "Доступна новая версия",
                color = Color.White
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "На GitHub опубликована сборка ${update.versionName} (${update.versionCode}). " +
                        "У вас ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}).",
                    color = Color(0xFFE0E0E0),
                    fontSize = 14.sp
                )
                changelogSummaryForBuild(update.versionName)?.let { notes ->
                    Text(
                        text = notes,
                        color = Color(0xFF9FA8DA),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Text(
                    text = APP_UPDATE_ANDROID_INSTALL_HINT,
                    color = Color(0xFFBCAAA4),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { if (downloadIndeterminate) 0.3f else downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    )
                    Text(
                        text = formatDownloadProgress(
                            if (downloadIndeterminate) null else downloadProgress
                        ),
                        color = Color(0xFF90CAF9),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                errorText?.let { err ->
                    Text(
                        text = err,
                        color = Color(0xFFEF9A9A),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    if (err.contains("404")) {
                        TextButton(onClick = { openAppUpdateInBrowser(context) }) {
                            Text("Открыть в браузере", color = Color(0xFF64B5F6))
                        }
                    }
                }
                if (!canInstall && sentToInstallSettings) {
                    Text(
                        text = "После включения «Разрешить установку» для MOEX MVP вернитесь сюда и нажмите «Скачать APK».",
                        color = Color(0xFFFFCC80),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            when {
                readyApk != null -> {
                    TextButton(
                        onClick = {
                            val file = readyApk ?: return@TextButton
                            runCatching {
                                installDownloadedAppUpdate(context, file)
                                onInstalledOffer()
                            }.onFailure {
                                errorText = it.message ?: "Не удалось открыть установщик"
                            }
                        }
                    ) {
                        Text("Установить", color = Color(0xFF81C784))
                    }
                }
                downloading -> Unit
                !canInstall -> {
                    TextButton(onClick = {
                        sentToInstallSettings = true
                        openUnknownAppSourcesSettings(context)
                    }) {
                        Text(
                            if (sentToInstallSettings) "Настройки установки" else "Разрешить установку",
                            color = Color(0xFF64B5F6)
                        )
                    }
                }
                else -> {
                    TextButton(
                        onClick = {
                            downloading = true
                            errorText = null
                            scope.launch {
                                try {
                                    val dest = appUpdateApkFile(context)
                                    withContext(Dispatchers.IO) {
                                        downloadAppUpdateApk(update, dest) { p ->
                                            scope.launch(Dispatchers.Main.immediate) {
                                                if (p == null) {
                                                    downloadIndeterminate = true
                                                } else {
                                                    downloadIndeterminate = false
                                                    downloadProgress = p
                                                }
                                            }
                                        }
                                    }
                                    readyApk = dest
                                    downloading = false
                                } catch (e: Exception) {
                                    downloading = false
                                    errorText = e.message?.take(200)
                                        ?: "Ошибка загрузки APK"
                                }
                            }
                        }
                    ) {
                        Text("Скачать APK", color = Color(0xFF64B5F6))
                    }
                }
            }
        },
        dismissButton = {
            if (!downloading) {
                Column {
                    if (readyApk == null) {
                        TextButton(onClick = { openAppUpdateInBrowser(context) }) {
                            Text("Через браузер", color = Color(0xFF81D4FA))
                        }
                    }
                    TextButton(
                        onClick = {
                            if (errorText != null) {
                                resetDownloadState()
                            } else {
                                onDismiss(update)
                            }
                        }
                    ) {
                        Text(
                            text = if (errorText != null) "Закрыть" else "Позже",
                            color = Color(0xFFB0BEC5)
                        )
                    }
                }
            }
        },
        containerColor = Color(0xFF263238)
    )
}

/** Фоновая проверка: push-уведомление раз в versionCode; диалог — только вручную или по тапу push. */
@Composable
internal fun AppUpdateBackgroundChecker(
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) {
                checkRemoteAppUpdateAndNotify(context)
            }
            delay(APP_UPDATE_CHECK_INTERVAL_MS)
        }
    }
}
