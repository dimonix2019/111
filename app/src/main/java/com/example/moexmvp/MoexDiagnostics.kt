package com.example.moexmvp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Кольцевой журнал событий (файл + logcat) для отладки вылетов на тестовых устройствах. */
internal object MoexDiagnostics {
    const val ENABLED = true

    private const val TAG = "MoexDiagnostics"
    private const val LOG_FILE = "moex_event_log.txt"
    private const val MAX_FILE_BYTES = 512 * 1024
    private val zone = ZoneId.of("Europe/Moscow")
    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val lock = Any()

    fun log(context: Context, category: String, message: String) {
        val line = "${timestamp()} [$category] $message"
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

    fun logError(context: Context, category: String, throwable: Throwable, message: String = "") {
        val head = if (message.isNotBlank()) {
            "$message — ${throwable.javaClass.simpleName}: ${throwable.message?.take(200)}"
        } else {
            "${throwable.javaClass.simpleName}: ${throwable.message?.take(200)}"
        }
        log(context, category, head)
        throwable.stackTraceToString()
            .lineSequence()
            .take(16)
            .forEach { log(context, category, it.take(240)) }
    }

    fun logUncaught(context: Context, thread: Thread, throwable: Throwable) {
        log(context, "crash", "UNCAUGHT thread=${thread.name} ${throwable.javaClass.simpleName}: ${throwable.message?.take(200)}")
        throwable.stackTraceToString()
            .lineSequence()
            .take(40)
            .forEach { appendLine(context.applicationContext, "${timestamp()} [crash] ${it.take(240)}") }
        flushFile(context.applicationContext)
    }

    fun loadLines(context: Context, tail: Int = 200): List<String> {
        val lines = readAllLines(context.applicationContext)
        return if (tail <= 0) lines else lines.takeLast(tail)
    }

    fun lineCount(context: Context): Int = readAllLines(context.applicationContext).size

    fun formatForDisplay(context: Context, tail: Int = 40): String {
        val lines = loadLines(context, tail)
        if (lines.isEmpty()) {
            return "Журнал пуст. Перезапустите приложение или дождитесь событий мониторинга."
        }
        return lines.joinToString("\n")
    }

    fun exportText(context: Context): String {
        val header = buildExportHeader(context)
        val lines = readAllLines(context.applicationContext)
        return buildString {
            append(header)
            append("\n--- events (${lines.size}) ---\n")
            lines.forEach { append(it).append('\n') }
        }
    }

    fun copyToClipboard(context: Context): Boolean {
        val text = exportText(context)
        if (text.isBlank()) return false
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("MOEX MVP event log", text))
        return true
    }

    fun shareExport(context: Context) {
        val text = exportText(context)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "moex-event-log-${BuildConfig.VERSION_NAME}.txt")
        }
        context.startActivity(Intent.createChooser(send, "Экспорт журнала событий"))
    }

    fun shareExportFile(context: Context): Boolean {
        val app = context.applicationContext
        val prepared = prepareExportFile(app) ?: return false
        val uri = FileProvider.getUriForFile(
            app,
            "${app.packageName}.fileprovider",
            prepared,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "moex-event-log-${BuildConfig.VERSION_NAME}.txt")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Экспорт файла журнала"))
        return true
    }

    fun clear(context: Context) {
        synchronized(lock) {
            logFile(context.applicationContext).delete()
        }
        log(context, "diag", "log cleared by user")
    }

    private fun buildExportHeader(context: Context): String {
        val app = context.applicationContext
        return buildString {
            append("MOEX MVP event log\n")
            append("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append("device=${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("exported=${timestamp()}\n")
            append("logBytes=${logFile(app).length()}\n")
        }
    }

    private fun prepareExportFile(context: Context): File? = runCatching {
        val out = File(context.cacheDir, "moex-event-log-export.txt")
        out.writeText(exportText(context))
        out
    }.getOrNull()

    private fun timestamp(): String = Instant.now().atZone(zone).format(timeFmt)

    private fun logFile(context: Context): File = File(context.filesDir, LOG_FILE)

    private fun readAllLines(context: Context): List<String> {
        synchronized(lock) {
            val file = logFile(context)
            if (!file.isFile || file.length() == 0L) return emptyList()
            return runCatching {
                file.readLines().filter { it.isNotBlank() }
            }.getOrElse { emptyList() }
        }
    }

    private fun appendLine(context: Context, line: String) {
        synchronized(lock) {
            val file = logFile(context)
            file.parentFile?.mkdirs()
            file.appendText(line + "\n")
            trimIfNeeded(file)
        }
    }

    private fun flushFile(context: Context) {
        synchronized(lock) {
            runCatching {
                java.io.FileOutputStream(logFile(context), true).use { it.flush() }
            }
        }
    }

    private fun trimIfNeeded(file: File) {
        if (file.length() <= MAX_FILE_BYTES) return
        val lines = file.readLines()
        val keep = lines.takeLast(lines.size / 2).joinToString("\n") + "\n"
        file.writeText(keep)
    }
}
