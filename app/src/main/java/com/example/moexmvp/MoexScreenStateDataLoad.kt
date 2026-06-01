package com.example.moexmvp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun MoexScreenState.reportDataLoadProgress(progress: DataLoadProgress?) {
    withContext(Dispatchers.Main) {
        dataLoadProgress = progress?.takeIf { it.active }
    }
}

internal fun MoexScreenState.dataLoadProgressSink(): DataLoadProgressCallback = { progress ->
    reportDataLoadProgress(progress)
}
