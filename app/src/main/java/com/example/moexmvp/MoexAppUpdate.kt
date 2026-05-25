package com.example.moexmvp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

internal const val APP_UPDATE_CHECK_INTERVAL_MS = 5 * 60 * 1000L
internal const val APP_UPDATE_GITHUB_RELEASE_TAG = "moexmvp-debug-latest"
internal const val APP_UPDATE_MANIFEST_URL =
    "https://github.com/dimonix2019/111/releases/download/moexmvp-debug-latest/app-update.json"
internal const val APP_UPDATE_GITHUB_API_URL =
    "https://api.github.com/repos/dimonix2019/111/releases/tags/moexmvp-debug-latest"
internal const val PREF_APP_UPDATE_DISMISSED_VERSION_CODE = "app_update_dismissed_version_code"

private val updateHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

internal data class AppRemoteUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkDownloadUrl: String
)

internal fun loadDismissedAppUpdateVersionCode(context: Context): Int =
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .getInt(PREF_APP_UPDATE_DISMISSED_VERSION_CODE, 0)

internal fun saveDismissedAppUpdateVersionCode(context: Context, versionCode: Int) {
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putInt(PREF_APP_UPDATE_DISMISSED_VERSION_CODE, versionCode)
        .apply()
}

internal fun parseAppUpdateManifestJson(json: String): AppRemoteUpdate? {
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val versionCode = o.optInt("versionCode", -1)
    if (versionCode <= 0) return null
    val versionName = o.optString("versionName").trim().ifBlank { "?" }
    val apkUrl = o.optString("apkUrl").trim().ifBlank { APK_DOWNLOAD_DIRECT_URL }
    return AppRemoteUpdate(versionCode, versionName, apkUrl)
}

/** «MOEX MVP debug — 1.6.91 (103)» → versionName + versionCode. */
internal fun parseVersionFromReleaseTitle(title: String): Pair<String, Int>? {
    val match = Regex("""([\d.]+)\s*\((\d+)\)\s*$""").find(title.trim()) ?: return null
    val versionName = match.groupValues[1]
    val versionCode = match.groupValues[2].toIntOrNull() ?: return null
    return versionName to versionCode
}

internal fun parseGitHubReleaseJson(json: String): AppRemoteUpdate? {
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val parsed = parseVersionFromReleaseTitle(o.optString("name"))
        ?: parseVersionFromReleaseBody(o.optString("body"))
        ?: return null
    var apkUrl = APK_DOWNLOAD_DIRECT_URL
    val assets = o.optJSONArray("assets")
    if (assets != null) {
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == "moexmvp-debug.apk") {
                apkUrl = asset.optString("browser_download_url", apkUrl)
                break
            }
        }
    }
    return AppRemoteUpdate(parsed.second, parsed.first, apkUrl)
}

private fun parseVersionFromReleaseBody(body: String): Pair<String, Int>? {
    val codeMatch = Regex("""versionCode\s+(\d+)""", RegexOption.IGNORE_CASE).find(body) ?: return null
    val versionCode = codeMatch.groupValues[1].toIntOrNull() ?: return null
    val nameMatch = Regex("""\*\*([\d.]+)\*\*""").find(body)
    val versionName = nameMatch?.groupValues?.get(1) ?: "?"
    return versionName to versionCode
}

internal fun fetchRemoteAppUpdate(): AppRemoteUpdate? {
    fetchText(APP_UPDATE_MANIFEST_URL)?.let { parseAppUpdateManifestJson(it) }?.let { return it }
    fetchText(
        APP_UPDATE_GITHUB_API_URL,
        headers = mapOf("Accept" to "application/vnd.github+json")
    )?.let { parseGitHubReleaseJson(it) }?.let { return it }
    return null
}

private fun fetchText(url: String, headers: Map<String, String> = emptyMap()): String? {
    val request = Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> addHeader(k, v) }
    }.build()
    return runCatching {
        updateHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}

internal fun isNewerAppUpdateAvailable(
    remote: AppRemoteUpdate,
    localVersionCode: Int = BuildConfig.VERSION_CODE
): Boolean = remote.versionCode > localVersionCode

internal fun canInstallPackages(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

internal fun openUnknownAppSourcesSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

internal fun appUpdateApkFile(context: Context): File {
    val dir = File(context.cacheDir, "app-updates").apply { mkdirs() }
    return File(dir, "moexmvp-update.apk")
}

/**
 * @param onProgress 0..1 or null if length unknown
 */
internal fun downloadAppUpdateApk(
    update: AppRemoteUpdate,
    destination: File,
    onProgress: ((Float?) -> Unit)? = null
) {
    destination.parentFile?.mkdirs()
    if (destination.exists()) destination.delete()
    val request = Request.Builder().url(update.apkDownloadUrl).build()
    updateHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}")
        }
        val body = response.body ?: throw IOException("Пустой ответ")
        val total = body.contentLength().takeIf { it > 0L }
        body.byteStream().use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var downloaded = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (total != null) {
                        onProgress?.invoke((downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f))
                    } else {
                        onProgress?.invoke(null)
                    }
                }
                output.flush()
            }
        }
        onProgress?.invoke(1f)
    }
    if (!destination.exists() || destination.length() < 1024) {
        throw IOException("Файл APK слишком мал или не сохранён")
    }
}

internal fun installDownloadedAppUpdate(context: Context, apkFile: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

internal fun formatDownloadProgress(progress: Float?): String =
    when (progress) {
        null -> "Загрузка…"
        else -> "Загрузка ${max(0, (progress * 100).roundToInt())}%"
    }
