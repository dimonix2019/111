package com.example.moexmvp

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun MoexScreenTabAbout(
    screen: MoexScreenState,
    scope: CoroutineScope,
    modifier: Modifier,
) {
    AboutTabContent(
        modifier = modifier.fillMaxWidth(),
        onUpdateFound = { remote ->
            if (screen.pendingAppUpdate == null || screen.pendingAppUpdate!!.versionCode < remote.versionCode) {
                screen.pendingAppUpdate = remote
            }
        }
    )
}
