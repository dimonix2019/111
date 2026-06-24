package com.example.moexmvp

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Вложения к debug-отчёту: скриншоты экрана и файлы из галереи. */
internal object MoexDebugReportAttachments {
    private const val DIR = "debug-report-attachments"
    private val zone = ZoneId.of("Europe/Moscow")
    private val stampFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private fun dir(context: Context): File =
        File(context.applicationContext.cacheDir, DIR).apply { mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.sortedBy { it.name }
            .orEmpty()

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    fun remove(context: Context, file: File) {
        val root = dir(context).absolutePath
        if (file.absolutePath.startsWith(root)) {
            file.delete()
        }
    }

    fun addFromUri(context: Context, uri: Uri): Boolean = runCatching {
        val resolver = context.applicationContext.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val ext = when {
            mime.contains("jpeg", ignoreCase = true) -> "jpg"
            mime.contains("png", ignoreCase = true) -> "png"
            mime.contains("webp", ignoreCase = true) -> "webp"
            else -> "jpg"
        }
        val stamp = Instant.now().atZone(zone).format(stampFmt)
        val out = File(dir(context), "gallery-$stamp.$ext")
        resolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: return false
        out.length() > 0L
    }.getOrDefault(false)

    /** Снимок текущего окна Activity (вызывать с main thread). */
    fun captureActivityWindow(activity: Activity): File? {
        val view = activity.window?.decorView?.rootView ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return try {
            val stamp = Instant.now().atZone(zone).format(stampFmt)
            val out = File(dir(activity.applicationContext), "screen-$stamp.png")
            out.outputStream().use { os ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 92, os)) {
                    throw IOException("compress failed")
                }
            }
            out
        } finally {
            bitmap.recycle()
        }
    }

    fun displayName(file: File): String = file.name
}
