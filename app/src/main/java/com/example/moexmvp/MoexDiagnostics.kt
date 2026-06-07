package com.example.moexmvp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Кольцевой журнал для отладки вылетов (logcat + «О приложении»). */
internal object MoexDiagnostics {
    /** Временно выключено: не пишем в prefs; UI в «О приложении» скрыт. */
    const val ENABLED = false

    private const val TAG = "MoexDiagnostics"
    private const val PREFS = "moex_diagnostics_log"
    private const val KEY_LINES = "lines_json"
    private const val MAX_LINES = 100
    private val zone = ZoneId.of("Europe/Moscow")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun log(context: Context, category: String, message: String) {
        val line = "${Instant.now().atZone(zone).format(timeFmt)} [$category] $message"
        Log.i(TAG, line)
        if (!ENABLED) return
        appendLine(context.applicationContext, line)
    }

    fun logMemory(context: Context, label: String = "heap") {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        log(context, "mem", "$label used=${usedMb}MB max=${maxMb}MB")
    }

    fun logUncaught(context: Context, thread: Thread, throwable: Throwable) {
        log(
            context,
            "crash",
            "thread=${thread.name} ${throwable.javaClass.simpleName}: ${throwable.message?.take(200)}",
        )
        throwable.stackTraceToString()
            .lineSequence()
            .take(12)
            .forEach { log(context, "crash", it.take(240)) }
    }

    fun loadLines(context: Context): List<String> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LINES, null)
            ?: return emptyList()
        return runCatching {
            org.json.JSONArray(raw).let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        add(arr.optString(i))
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    fun formatForDisplay(context: Context, tail: Int = 40): String {
        val lines = loadLines(context)
        if (lines.isEmpty()) return "Журнал пуст. Откройте «Тест страт.» или воспроизведите вылет."
        return lines.takeLast(tail).joinToString("\n")
    }

    fun copyToClipboard(context: Context): Boolean {
        val text = formatForDisplay(context, tail = MAX_LINES)
        if (text.isBlank()) return false
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("MOEX MVP diagnostics", text))
        return true
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LINES)
            .apply()
    }

    private fun appendLine(context: Context, line: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = loadLines(context).toMutableList()
        existing += line
        while (existing.size > MAX_LINES) {
            existing.removeAt(0)
        }
        val arr = org.json.JSONArray()
        existing.forEach { arr.put(it) }
        prefs.edit().putString(KEY_LINES, arr.toString()).apply()
    }
}
