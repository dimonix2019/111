package com.example.moexmvp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.content.ContentValues
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException

/** Кольцевой журнал событий (файл + logcat) для отладки вылетов на тестовых устройствах. */
internal object MoexDiagnostics {
    const val ENABLED = true

    private const val TAG = "MoexDiagnostics"
    private const val LOG_FILE = "moex_event_log.txt"
    private const val MAX_FILE_BYTES = 512 * 1024
    private val zone = ZoneId.of("Europe/Moscow")
    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val lock = Any()
    private var lastNetworkErrorKey: String? = null
    private var lastNetworkErrorAtMs: Long = 0L
    private const val NETWORK_ERROR_LOG_INTERVAL_MS = 5 * 60_000L

    fun isTransientNetworkError(throwable: Throwable): Boolean {
        var t: Throwable? = throwable
        while (t != null) {
            when (t) {
                is UnknownHostException,
                is SocketTimeoutException,
                is ConnectException,
                is SSLException,
                -> return true
                is IOException -> {
                    val msg = t.message?.lowercase().orEmpty()
                    if (msg.contains("unable to resolve host") ||
                        msg.contains("failed to connect") ||
                        msg.contains("network is unreachable") ||
                        msg.contains("timeout")
                    ) {
                        return true
                    }
                }
            }
            t = t.cause
        }
        return false
    }

    /** Сеть/DNS: одна строка без stack trace, не чаще раза в 5 минут на ключ. */
    fun logNetworkErrorThrottled(context: Context, category: String, throwable: Throwable, message: String) {
        val key = "$category:$message:${throwable.javaClass.simpleName}"
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (key == lastNetworkErrorKey && now - lastNetworkErrorAtMs < NETWORK_ERROR_LOG_INTERVAL_MS) {
                return
            }
            lastNetworkErrorKey = key
            lastNetworkErrorAtMs = now
        }
        val head = "$message — ${throwable.javaClass.simpleName}: ${throwable.message?.take(200)}"
        log(context, category, head)
    }

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
        val kind = if (throwable is Error) "fatal" else "crash"
        log(context, kind, "UNCAUGHT thread=${thread.name} ${throwable.javaClass.simpleName}: ${throwable.message?.take(200)}")
        throwable.stackTraceToString()
            .lineSequence()
            .take(40)
            .forEach { appendLine(context.applicationContext, "${timestamp()} [$kind] ${it.take(240)}") }
        flushFile(context.applicationContext)
    }

    fun logWebViewGone(context: Context, detail: String) {
        log(context, "webview", "render_process_gone $detail")
        logMemory(context, "webview_gone")
        flushFile(context.applicationContext)
    }

    fun loadLines(context: Context, tail: Int = 200): List<String> {
        val app = context.applicationContext
        return if (tail <= 0) readAllLines(app) else readTailLines(app, tail)
    }

    fun lineCount(context: Context): Int = countLines(context.applicationContext)

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
            putExtra(Intent.EXTRA_SUBJECT, eventLogExportFileName())
        }
        context.startActivity(Intent.createChooser(send, "Экспорт журнала событий"))
    }

    /** Имя файла: `moex-event-log-v1.7.192-20260616-214500.txt`. */
    fun eventLogExportFileName(): String {
        val stamp = Instant.now().atZone(zone)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return "moex-event-log-v${BuildConfig.VERSION_NAME}-$stamp.txt"
    }

    /**
     * Сохранить в «Загрузки» (Android 10+, MediaStore, без разрешений).
     * @return путь для пользователя или null
     */
    fun saveExportToDownloads(context: Context): String? {
        val app = context.applicationContext
        val fileName = eventLogExportFileName()
        val content = exportText(context)
        if (content.isBlank()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            } ?: return null
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            log(app, "diag", "log saved Downloads/$fileName bytes=${content.length}")
            "Загрузки/$fileName"
        }.getOrElse {
            logError(app, "diag", it, "saveExportToDownloads failed")
            null
        }
    }

    /** Запись в URI из диалога «Сохранить как…». */
    fun writeExportToUri(context: Context, uri: Uri): Boolean = runCatching {
        val content = exportText(context)
        if (content.isBlank()) return false
        context.contentResolver.openOutputStream(uri)?.use { os ->
            os.write(content.toByteArray(Charsets.UTF_8))
        } ?: return false
        log(context.applicationContext, "diag", "log saved picker uri=$uri")
        true
    }.getOrElse {
        logError(context.applicationContext, "diag", it, "writeExportToUri failed")
        false
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
            putExtra(Intent.EXTRA_SUBJECT, eventLogExportFileName())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(app.contentResolver, "event log", uri)
        }
        context.startActivity(Intent.createChooser(send, "Отправить файл журнала"))
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
            append("gitBranch=${BuildConfig.GIT_BRANCH}\n")
            append("gitSha=${BuildConfig.GIT_SHA}\n")
            append("device=${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("exported=${timestamp()}\n")
            append("logBytes=${logFile(app).length()}\n")
        }
    }

    private fun prepareExportFile(context: Context): File? = runCatching {
        val out = File(context.cacheDir, eventLogExportFileName())
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

    /** Последние N строк без чтения всего файла в память (для превью на UI). */
    private fun readTailLines(context: Context, maxLines: Int): List<String> {
        synchronized(lock) {
            val file = logFile(context)
            if (!file.isFile || file.length() == 0L || maxLines <= 0) return emptyList()
            return runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val length = raf.length()
                    if (length == 0L) return@use emptyList<String>()
                    val chunkSize = 8192
                    val buffer = StringBuilder()
                    var pos = length
                    var newlineCount = 0
                    while (pos > 0 && newlineCount <= maxLines) {
                        val readSize = minOf(chunkSize.toLong(), pos).toInt()
                        pos -= readSize
                        raf.seek(pos)
                        val bytes = ByteArray(readSize)
                        raf.readFully(bytes)
                        buffer.insert(0, String(bytes, Charsets.UTF_8))
                        newlineCount = buffer.count { it == '\n' }
                    }
                    buffer.toString()
                        .split('\n')
                        .filter { it.isNotBlank() }
                        .takeLast(maxLines)
                }
            }.getOrElse { emptyList() }
        }
    }

    private fun countLines(context: Context): Int {
        synchronized(lock) {
            val file = logFile(context)
            if (!file.isFile || file.length() == 0L) return 0
            return runCatching {
                file.bufferedReader().use { reader ->
                    var count = 0
                    while (reader.readLine() != null) count++
                    count
                }
            }.getOrElse { 0 }
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
