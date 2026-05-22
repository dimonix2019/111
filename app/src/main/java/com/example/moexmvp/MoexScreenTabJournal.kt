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
internal fun MoexScreenTabJournal(
    screen: MoexScreenState,
    scope: CoroutineScope,
    modifier: Modifier,
) {
    with(screen) {
                JournalTabContent(
                    events = signalEvents,
                    pushNotifications = pushNotificationLog,
                    modifier = modifier.fillMaxWidth(),
                    onClearHistoryRequest = {
                        clearStrategySignalJournalAndLocalStrategyState(context)
                        signalEvents = loadStrategySignalEvents(context)
                        pendingVirtualTrade = loadPendingVirtualTradeProposal(context)
                        zStrategyPosition = ZStrategyPosition.Flat
                        sandboxSpreadExecReload++
                        Toast.makeText(
                            context,
                            "Журнал очищен; позиция Z — FLAT; карточка «Принять» и локальный лог спрэда сброшены.",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    onClearPushLogRequest = {
                        clearPushNotificationLog(context)
                        pushNotificationLog = loadPushNotificationLog(context)
                        Toast.makeText(
                            context,
                            "Журнал уведомлений (push) очищен.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
    }
}
