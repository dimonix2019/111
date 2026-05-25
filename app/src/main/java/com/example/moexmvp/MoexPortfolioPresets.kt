package com.example.moexmvp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val PREFS = "moex_portfolio_presets"
private const val KEY_LIST = "presets_json"
private const val MAX_PRESETS = 8

internal fun loadPortfolioPresets(context: Context): List<PortfolioPreset> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST, null)
        ?: return emptyList()
    val arr = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                PortfolioPreset(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", "Пресет"),
                    leverage = o.optDouble("leverage", 7.0),
                    commissionPercentPerSide = o.optDouble("commission", 0.04),
                    entryThreshold = o.optDouble("entry", DEFAULT_DYNAMIC_Z_ENTRY),
                    exitThreshold = o.optDouble("exit", DEFAULT_DYNAMIC_Z_EXIT),
                    exitMode = parseZStrategyExitMode(o.optString("exitMode", ZStrategyExitMode.FixedThreshold.name)),
                    zPeakTrailZ = o.optDouble(
                        "zPeakTrail",
                        DEFAULT_STRATEGY_TEST_Z_PEAK_TRAIL
                    ).coerceIn(STRATEGY_TEST_Z_PEAK_TRAIL_MIN, STRATEGY_TEST_Z_PEAK_TRAIL_MAX)
                )
            )
        }
    }
}

internal fun savePortfolioPresets(context: Context, presets: List<PortfolioPreset>) {
    val arr = JSONArray()
    presets.take(MAX_PRESETS).forEach { p ->
        arr.put(
            JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("leverage", p.leverage)
                .put("commission", p.commissionPercentPerSide)
                .put("entry", p.entryThreshold)
                .put("exit", p.exitThreshold)
                .put("exitMode", p.exitMode.name)
                .put("zPeakTrail", p.zPeakTrailZ)
        )
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LIST, arr.toString()).apply()
}

internal fun addPortfolioPreset(
    context: Context,
    name: String,
    leverage: Double,
    commissionPercentPerSide: Double,
    entryThreshold: Double,
    exitThreshold: Double,
    exitMode: ZStrategyExitMode = ZStrategyExitMode.FixedThreshold,
    zPeakTrailZ: Double = DEFAULT_STRATEGY_TEST_Z_PEAK_TRAIL
): List<PortfolioPreset> {
    val list = loadPortfolioPresets(context).toMutableList()
    list.add(
        0,
        PortfolioPreset(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Пресет" },
            leverage = leverage,
            commissionPercentPerSide = commissionPercentPerSide,
            entryThreshold = entryThreshold,
            exitThreshold = exitThreshold,
            exitMode = exitMode,
            zPeakTrailZ = zPeakTrailZ.coerceIn(
                STRATEGY_TEST_Z_PEAK_TRAIL_MIN,
                STRATEGY_TEST_Z_PEAK_TRAIL_MAX
            )
        )
    )
    while (list.size > MAX_PRESETS) list.removeAt(list.lastIndex)
    savePortfolioPresets(context, list)
    return list
}

internal fun deletePortfolioPreset(context: Context, id: String): List<PortfolioPreset> {
    val list = loadPortfolioPresets(context).filter { it.id != id }
    savePortfolioPresets(context, list)
    return list
}
