package com.example.moexmvp

import android.app.Activity
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale


@Composable
internal fun MoexScreenTabSandbox(
    screen: MoexScreenState,
    scope: CoroutineScope,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        with(screen) {
            Column(modifier = Modifier.fillMaxWidth()) {
                    TinkoffSandboxTabContent(
                        executionMode = executionMode,
                        onExecutionModeChange = { mode -> executionMode = mode },
                        tokenInput = sandboxTokenInput,
                        onTokenInputChange = { sandboxTokenInput = it },
                        accountInput = sandboxAccountInput,
                        onAccountInputChange = { sandboxAccountInput = it },
                        onSandboxPrefsChanged = {
                            sandboxExecState = TinkoffSandboxStorage.resolveExecUiState(context, executionMode)
                            executeSignalsOnSandbox = TinkoffSandboxStorage.isExecuteSignalsOnSandbox(context)
                            sandboxSpreadAutoExecute = TinkoffSandboxStorage.isSandboxSpreadAutoExecute(context)
                        },
                        onSandboxAccountRecreated = { sandboxSpreadExecReload++ }
                    )
            }
        }
    }
}
