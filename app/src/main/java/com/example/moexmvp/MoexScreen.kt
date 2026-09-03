package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun MoexScreen() {
    val context = LocalContext.current
    val screen = remember(context) { MoexScreenState(context) }
    val scope = rememberCoroutineScope()

    DisposableEffect(screen) {
        MoexMemoryPressure.registerTrimHandler { level ->
            screen.trimMemoryCaches(level)
            MoexDiagnostics.log(
                context,
                "mem",
                "trimCaches level=$level tab=${screen.selectedTab.label} usedAfter=${Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / (1024 * 1024) }}MB",
            )
        }
        onDispose { MoexMemoryPressure.unregisterTrimHandler() }
    }

    // Уводим с скрытых вкладок (Портфель / Тест страт. / Журнал) на «Рынок».
    LaunchedEffect(screen.selectedTab) {
        if (screen.selectedTab !in MainTab.navTabs) {
            screen.selectedTab = MainTab.Markets
        }
    }

    MoexScreenEffects(screen, scope)

    AppUpdateBackgroundChecker(
        onUpdateFound = { remote ->
            if (screen.pendingAppUpdate == null || screen.pendingAppUpdate!!.versionCode < remote.versionCode) {
                screen.pendingAppUpdate = remote
            }
        },
    )

    AppUpdateDialogHost(
        pendingUpdate = screen.pendingAppUpdate,
        onDismiss = { update ->
            saveDismissedAppUpdateVersionCode(context, update.versionCode)
            screen.pendingAppUpdate = null
        },
        onInstalledOffer = { screen.pendingAppUpdate = null }
    )

    MoexScreenDialogs(screen, scope)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            MainTabSelector(
                selected = screen.selectedTab,
                onSelect = { screen.selectedTab = it }
            )
            if (!screen.sandboxSpreadAutoExecute) {
                MoexScreenVirtualTradeCard(screen, scope, Modifier.padding(top = 6.dp))
            }
            when (screen.selectedTab) {
                MainTab.About -> MoexScreenTabAbout(screen, scope, Modifier.weight(1f).fillMaxSize())
                MainTab.Sandbox -> MoexScreenTabSandbox(screen, scope, Modifier.weight(1f).fillMaxSize())
                MainTab.WebDesk -> MoexScreenTabWebDesk(screen, scope, Modifier.weight(1f).fillMaxSize())
                MainTab.Markets -> MoexScreenTabMarketsPhone(screen, scope, Modifier.weight(1f).fillMaxSize())
                MainTab.Trade -> MoexScreenTabTrade(screen, scope, Modifier.weight(1f).fillMaxSize())
                else -> Box(Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}
