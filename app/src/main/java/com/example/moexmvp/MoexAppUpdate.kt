package com.example.moexmvp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

internal const val APP_UPDATE_MIN_APK_BYTES = 5_000_000L

internal const val APP_UPDATE_CHECK_INTERVAL_MS = 5 * 60 * 1000L
internal const val APP_UPDATE_GITHUB_RELEASE_TAG = "moexmvp-debug-latest"
internal const val APP_UPDATE_MANIFEST_URL =
    "https://github.com/dimonix2019/111/releases/download/moexmvp-debug-latest/app-update.json"
internal const val APP_UPDATE_GITHUB_API_URL =
    "https://api.github.com/repos/dimonix2019/111/releases/tags/moexmvp-debug-latest"
/** Публичное зеркало (ветка gh-pages). Работает без входа, если репозиторий public. */
internal const val APP_UPDATE_PUBLIC_MANIFEST_URL =
    "https://raw.githubusercontent.com/dimonix2019/111/gh-pages/app-update.json"
internal const val APP_UPDATE_PUBLIC_APK_URL =
    "https://raw.githubusercontent.com/dimonix2019/111/gh-pages/moexmvp-debug.apk"
internal const val PREF_APP_UPDATE_DISMISSED_VERSION_CODE = "app_update_dismissed_version_code"
internal const val PREF_APP_UPDATE_NOTIFIED_VERSION_CODE = "app_update_notified_version_code"
internal const val APP_UPDATE_PUSH_NOTIFICATION_ID = 12_002

private val updateHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .followRedirects(true)
    .addInterceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", "MOEX-MVP-Android/${BuildConfig.VERSION_NAME}")
                .build()
        )
    }
    .build()

internal data class AppRemoteUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkDownloadUrl: String
)

internal data class HttpFetchResult(
    val body: String?,
    val httpCode: Int?,
    val errorMessage: String? = null,
)

internal data class AppUpdateFetchDiagnostics(
    val update: AppRemoteUpdate?,
    val lastHttpCode: Int?,
    val privateRepoLikely: Boolean,
    val triedUrls: List<String>,
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

/** Сброс «Позже» перед ручной проверкой — диалог обновления снова покажется при наличии сборки. */
internal fun prepareManualAppUpdateCheck(context: Context) {
    saveDismissedAppUpdateVersionCode(context, 0)
}

internal fun loadNotifiedAppUpdateVersionCode(context: Context): Int =
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .getInt(PREF_APP_UPDATE_NOTIFIED_VERSION_CODE, 0)

internal fun saveNotifiedAppUpdateVersionCode(context: Context, versionCode: Int) {
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putInt(PREF_APP_UPDATE_NOTIFIED_VERSION_CODE, versionCode)
        .apply()
}

/** Новая сборка на GitHub, которую пользователь ещё не отклонил в диалоге «Позже». */
internal fun shouldOfferAppUpdateUi(
    remote: AppRemoteUpdate,
    context: Context,
    localVersionCode: Int = BuildConfig.VERSION_CODE
): Boolean =
    isNewerAppUpdateAvailable(remote, localVersionCode) &&
        remote.versionCode > loadDismissedAppUpdateVersionCode(context)

/**
 * Проверяет GitHub Release / app-update.json; при новой версии показывает push (один раз на versionCode).
 * @return remote, если есть более новая сборка и её можно предложить в UI
 */
internal fun checkRemoteAppUpdateAndNotify(context: Context): AppRemoteUpdate? {
    val app = context.applicationContext
    val remote = fetchRemoteAppUpdate() ?: return null
    if (!shouldOfferAppUpdateUi(remote, app)) return null
    val notified = loadNotifiedAppUpdateVersionCode(app)
    if (remote.versionCode > notified) {
        if (showAppUpdatePushNotification(app, remote)) {
            saveNotifiedAppUpdateVersionCode(app, remote.versionCode)
        }
    }
    return remote
}

internal sealed class AppUpdateCheckStatus {
    data class UpdateAvailable(val remote: AppRemoteUpdate) : AppUpdateCheckStatus()
    data object UpToDate : AppUpdateCheckStatus()
    /** На GitHub опубликована сборка старее, чем установленная (часто — CI ещё не обновил Release). */
    data class RemoteOlder(val remote: AppRemoteUpdate) : AppUpdateCheckStatus()
    data class FetchFailed(val hint: String, val openBrowserRecommended: Boolean = true) : AppUpdateCheckStatus()
}

internal fun checkAppUpdateStatus(): AppUpdateCheckStatus {
    val diagnostics = fetchRemoteAppUpdateWithDiagnostics()
    val remote = diagnostics.update
        ?: return AppUpdateCheckStatus.FetchFailed(
            hint = formatAppUpdateFetchFailure(diagnostics),
            openBrowserRecommended = true,
        )
    return when {
        isNewerAppUpdateAvailable(remote) -> AppUpdateCheckStatus.UpdateAvailable(remote)
        remote.versionCode < BuildConfig.VERSION_CODE ->
            AppUpdateCheckStatus.RemoteOlder(remote)
        else -> AppUpdateCheckStatus.UpToDate
    }
}

internal fun formatAppUpdateCheckStatus(status: AppUpdateCheckStatus): String = when (status) {
    is AppUpdateCheckStatus.UpdateAvailable -> {
        val r = status.remote
        "Доступна ${r.versionName} (${r.versionCode}). У вас ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})."
    }
    AppUpdateCheckStatus.UpToDate ->
        "У вас актуальная сборка ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}). " +
            "На GitHub опубликована та же версия."
    is AppUpdateCheckStatus.RemoteOlder -> {
        val r = status.remote
        "На GitHub пока ${r.versionName} (${r.versionCode}) — ниже вашей ${BuildConfig.VERSION_NAME} " +
            "(${BuildConfig.VERSION_CODE}). Откройте «В браузере» или дождитесь публикации новой сборки."
    }
    is AppUpdateCheckStatus.FetchFailed -> status.hint
}

internal fun formatAppUpdateFetchFailure(diagnostics: AppUpdateFetchDiagnostics): String {
    val code = diagnostics.lastHttpCode
    val codePart = code?.let { " (HTTP $it)" }.orEmpty()
    return buildString {
        append("Не удалось проверить обновление$codePart. ")
        if (diagnostics.privateRepoLikely) {
            append(
                "GitHub вернул 404 — часто так бывает у private-репозитория без входа. "
            )
            append("Если repo уже public, проверьте интернет и повторите. ")
        } else {
            append("Проверьте интернет. ")
        }
        append("Release: $APK_GITHUB_RELEASES_PAGE_URL")
    }
}

internal fun appUpdateManifestUrlCandidates(): List<String> = listOf(
    APP_UPDATE_MANIFEST_URL,
    APP_UPDATE_PUBLIC_MANIFEST_URL,
)

/** Из нескольких источников берём сборку с наибольшим versionCode (release может быть новее gh-pages). */
internal fun selectBestRemoteAppUpdate(candidates: List<AppRemoteUpdate>): AppRemoteUpdate? =
    candidates.maxByOrNull { it.versionCode }?.let { best ->
        best.copy(apkDownloadUrl = preferredInAppApkDownloadUrl(best.apkDownloadUrl))
    }

/** In-app загрузка: gh-pages (публичное зеркало), не release URL с возможной HTML-страницей. */
internal fun preferredInAppApkDownloadUrl(primaryUrl: String): String {
    val trimmed = primaryUrl.trim()
    if (trimmed.contains("raw.githubusercontent.com", ignoreCase = true) &&
        trimmed.endsWith(".apk", ignoreCase = true)
    ) {
        return trimmed
    }
    return APP_UPDATE_PUBLIC_APK_URL
}

internal fun resolveApkDownloadUrl(manifestUrl: String, parsed: AppRemoteUpdate): String {
    if (manifestUrl == APP_UPDATE_PUBLIC_MANIFEST_URL) {
        // gh-pages — публичное зеркало; APK всегда с raw.githubusercontent (без входа в GitHub).
        return APP_UPDATE_PUBLIC_APK_URL
    }
    return parsed.apkDownloadUrl.ifBlank { APK_DOWNLOAD_DIRECT_URL }
}

/** Порядок попыток скачивания: зеркало gh-pages → основной URL → Release. */
internal fun apkDownloadUrlCandidates(primaryUrl: String): List<String> =
    buildList {
        if (none { it.equals(APP_UPDATE_PUBLIC_APK_URL, ignoreCase = true) }) {
            add(APP_UPDATE_PUBLIC_APK_URL)
        }
        val primary = preferredInAppApkDownloadUrl(primaryUrl)
        if (primary.isNotBlank() && none { it.equals(primary, ignoreCase = true) }) {
            add(primary)
        }
        if (none { it.equals(APK_DOWNLOAD_DIRECT_URL, ignoreCase = true) }) {
            add(APK_DOWNLOAD_DIRECT_URL)
        }
    }

internal fun parseAppUpdateManifestJson(json: String, manifestUrl: String = APP_UPDATE_MANIFEST_URL): AppRemoteUpdate? {
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val versionCode = o.optInt("versionCode", -1)
    if (versionCode <= 0) return null
    val versionName = o.optString("versionName").trim().ifBlank { "?" }
    val apkUrl = o.optString("apkUrl").trim()
    val parsed = AppRemoteUpdate(
        versionCode,
        versionName,
        apkUrl
    )
    return parsed.copy(apkDownloadUrl = resolveApkDownloadUrl(manifestUrl, parsed))
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

internal fun fetchRemoteAppUpdateWithDiagnostics(): AppUpdateFetchDiagnostics {
    var lastHttpCode: Int? = null
    var privateRepoLikely = false
    val tried = mutableListOf<String>()
    val candidates = mutableListOf<AppRemoteUpdate>()

    for (url in appUpdateManifestUrlCandidates()) {
        tried += url
        val result = fetchText(url)
        lastHttpCode = result.httpCode ?: lastHttpCode
        if (result.httpCode == 404) privateRepoLikely = true
        result.body?.let { body ->
            parseAppUpdateManifestJson(body, manifestUrl = url)?.let { candidates += it }
        }
    }

    tried += APP_UPDATE_GITHUB_API_URL
    fetchText(
        APP_UPDATE_GITHUB_API_URL,
        headers = mapOf("Accept" to "application/vnd.github+json")
    ).let { result ->
        lastHttpCode = result.httpCode ?: lastHttpCode
        if (result.httpCode == 404) privateRepoLikely = true
        result.body?.let { body ->
            parseGitHubReleaseJson(body)?.let { candidates += it }
        }
    }

    selectBestRemoteAppUpdate(candidates)?.let { update ->
        return AppUpdateFetchDiagnostics(update, lastHttpCode, privateRepoLikely = false, triedUrls = tried)
    }

    return AppUpdateFetchDiagnostics(
        update = null,
        lastHttpCode = lastHttpCode,
        privateRepoLikely = privateRepoLikely,
        triedUrls = tried,
    )
}

internal fun fetchRemoteAppUpdate(): AppRemoteUpdate? =
    fetchRemoteAppUpdateWithDiagnostics().update

private fun fetchText(url: String, headers: Map<String, String> = emptyMap()): HttpFetchResult {
    val request = Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> addHeader(k, v) }
    }.build()
    return runCatching {
        updateHttpClient.newCall(request).execute().use { response ->
            val code = response.code
            if (!response.isSuccessful) {
                return@use HttpFetchResult(body = null, httpCode = code)
            }
            HttpFetchResult(
                body = response.body?.string()?.takeIf { it.isNotBlank() },
                httpCode = code,
            )
        }
    }.getOrElse { err ->
        HttpFetchResult(body = null, httpCode = null, errorMessage = err.message)
    }
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

/** Настройки приложения → удаление (если подпись APK не совпадает с установленной). */
internal fun openAppDetailsSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

internal fun installedAppVersionCode(context: Context): Long =
    runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0).versionCode.toLong()
        }
    }.getOrDefault(0L)

private fun archiveApkVersionCode(archiveInfo: android.content.pm.PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        archiveInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        archiveInfo.versionCode.toLong()
    }

internal fun openAppUpdateInBrowser(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(APK_GITHUB_RELEASES_PAGE_URL))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
    val urls = apkDownloadUrlCandidates(update.apkDownloadUrl)
    var lastError: IOException? = null
    for (url in urls) {
        try {
            downloadAppUpdateApkFromUrl(url, destination, onProgress)
            return
        } catch (e: IOException) {
            lastError = e
            if (destination.exists()) destination.delete()
            if (!isApkDownloadRetryable(e)) throw e
        }
    }
    throw lastError ?: IOException("APK недоступен. Попробуйте «Через браузер».")
}

private fun isApkDownloadRetryable(error: IOException): Boolean {
    val msg = error.message.orEmpty()
    return msg.contains("404") ||
        msg.contains("слишком мал") ||
        msg.contains("Пустой ответ") ||
        msg.contains("не APK") ||
        msg.contains("HTML")
}

private fun isLikelyHtmlApkPayload(contentType: String?, peekHead: ByteArray): Boolean {
    if (contentType?.contains("text/html", ignoreCase = true) == true) return true
    if (peekHead.size < 4) return true
    val headText = peekHead.decodeToString(0, minOf(peekHead.size, 64), throwOnInvalidSequence = false)
        .trimStart()
    return headText.startsWith("<!DOCTYPE", ignoreCase = true) ||
        headText.startsWith("<html", ignoreCase = true)
}

private fun isZipApkMagic(head: ByteArray): Boolean =
    head.size >= 4 &&
        head[0] == 0x50.toByte() &&
        head[1] == 0x4B.toByte() &&
        (head[2] == 0x03.toByte() || head[2] == 0x05.toByte() || head[2] == 0x07.toByte()) &&
        (head[3] == 0x04.toByte() || head[3] == 0x06.toByte() || head[3] == 0x08.toByte())

private fun downloadAppUpdateApkFromUrl(
    url: String,
    destination: File,
    onProgress: ((Float?) -> Unit)? = null
) {
    destination.parentFile?.mkdirs()
    if (destination.exists()) destination.delete()
    val request = Request.Builder().url(url).build()
    updateHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException(
                when (response.code) {
                    404 -> "HTTP 404 — APK недоступен по $url"
                    else -> "HTTP ${response.code} — $url"
                }
            )
        }
        val body = response.body ?: throw IOException("Пустой ответ")
        val contentType = response.header("Content-Type")
        val total = body.contentLength().takeIf { it > 0L }
        body.byteStream().use { input ->
            val header = ByteArray(512)
            val headerRead = input.read(header)
            if (headerRead < 4) throw IOException("Пустой ответ")
            val headSlice = header.copyOf(headerRead)
            if (!isZipApkMagic(headSlice) || isLikelyHtmlApkPayload(contentType, headSlice)) {
                throw IOException(
                    "Ответ не APK (возможно HTML GitHub). Используйте «Через браузер»."
                )
            }
            destination.outputStream().use { output ->
                output.write(headSlice, 0, headerRead)
                var downloaded = headerRead.toLong()
                val buffer = ByteArray(8192)
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
    val validation = validateDownloadedAppUpdateApkStructure(destination)
    if (validation != null) {
        destination.delete()
        throw IOException(validation)
    }
}

internal sealed class AppUpdateApkValidation {
    data object Valid : AppUpdateApkValidation()
    data class Failed(
        val message: String,
        val signatureMismatch: Boolean = false,
    ) : AppUpdateApkValidation()
}

/** Проверка APK перед установкой (битая загрузка / чужая подпись). */
internal fun validateDownloadedAppUpdateApk(context: Context, apkFile: File): AppUpdateApkValidation {
    validateDownloadedAppUpdateApkStructure(apkFile)?.let { msg ->
        return AppUpdateApkValidation.Failed(msg)
    }
    val pm = context.packageManager
    val archiveFlags = signingPackageInfoFlags()
    val archiveInfo = loadArchivePackageInfo(pm, apkFile.absolutePath, archiveFlags)
        ?: return AppUpdateApkValidation.Failed(
            "Android не может прочитать APK. Скачайте заново или через браузер."
        )
    if (archiveInfo.packageName != context.packageName) {
        return AppUpdateApkValidation.Failed("APK предназначен для другого приложения.")
    }
    val apkVersionCode = archiveApkVersionCode(archiveInfo)
    val installedCode = installedAppVersionCode(context)
    if (installedCode > 0L && apkVersionCode <= installedCode) {
        return AppUpdateApkValidation.Failed(
            "На GitHub сборка ${archiveInfo.versionName ?: "?"} (versionCode $apkVersionCode) — " +
                "не новее установленной ${BuildConfig.VERSION_NAME} ($installedCode). " +
                "Android не даёт «откатить» версию. Дождитесь публикации новой сборки или установите через браузер.",
        )
    }
    val apkCerts = packageSigningSha256Digests(archiveInfo)
    if (apkCerts.isEmpty()) {
        return AppUpdateApkValidation.Failed("В APK не найдена подпись.")
    }
    val installedCerts = installedPackageSigningSha256Digests(context)
    if (installedCerts.isNotEmpty() && apkCerts.none { it in installedCerts }) {
        return AppUpdateApkValidation.Failed(
            "Подпись APK не совпадает с установленной (часто после локальной сборки в Android Studio). " +
                "Удалите MOEX MVP: Настройки → Приложения → MOEX MVP → Удалить, " +
                "затем установите APK с GitHub заново.",
            signatureMismatch = true,
        )
    }
    return AppUpdateApkValidation.Valid
}

internal fun validateDownloadedAppUpdateApkStructure(apkFile: File): String? {
    if (!apkFile.exists()) return "Файл обновления не найден."
    val size = apkFile.length()
    if (size < APP_UPDATE_MIN_APK_BYTES) {
        return "Загруженный файл слишком мал (${size / 1024} КБ). " +
            "Вместо APK могла прийти страница ошибки — повторите или «Через браузер»."
    }
    apkFile.inputStream().use { input ->
        val magic = ByteArray(4)
        if (input.read(magic) != 4 || !isZipApkMagic(magic)) {
            return "Файл не является APK (битая загрузка)."
        }
    }
    return null
}

private fun signingPackageInfoFlags(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

private fun loadArchivePackageInfo(
    pm: PackageManager,
    apkPath: String,
    flags: Int,
) = runCatching {
    @Suppress("DEPRECATION")
    pm.getPackageArchiveInfo(apkPath, flags)?.also { info ->
        info.applicationInfo?.let { appInfo ->
            appInfo.sourceDir = apkPath
            appInfo.publicSourceDir = apkPath
        }
    }?.let { first ->
        @Suppress("DEPRECATION")
        pm.getPackageArchiveInfo(apkPath, flags) ?: first
    }
}.getOrNull()

private fun installedPackageSigningSha256Digests(context: Context): Set<String> =
    runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, signingPackageInfoFlags())
    }.getOrNull()?.let(::packageSigningSha256Digests).orEmpty()

private fun packageSigningSha256Digests(packageInfo: android.content.pm.PackageInfo): Set<String> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val signers = packageInfo.signingInfo?.apkContentsSigners
        if (!signers.isNullOrEmpty()) {
            return signers.map { sha256Hex(it.toByteArray()) }.toSet()
        }
    }
    @Suppress("DEPRECATION")
    return packageInfo.signatures?.map { sha256Hex(it.toByteArray()) }?.toSet().orEmpty()
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { b -> "%02x".format(b) }
}

internal fun installDownloadedAppUpdate(context: Context, apkFile: File) {
    when (val validation = validateDownloadedAppUpdateApk(context, apkFile)) {
        is AppUpdateApkValidation.Valid -> Unit
        is AppUpdateApkValidation.Failed -> throw IOException(validation.message)
    }
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
    val installers = context.packageManager.queryIntentActivities(
        intent,
        PackageManager.MATCH_DEFAULT_ONLY,
    )
    if (installers.isEmpty()) {
        throw IOException(
            "На устройстве нет установщика APK. Включите «Установку из неизвестных источников» " +
                "для MOEX MVP или скачайте через браузер."
        )
    }
    for (resolveInfo in installers) {
        context.grantUriPermission(
            resolveInfo.activityInfo.packageName,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
    val chooser = Intent.createChooser(intent, "Установить MOEX MVP").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

internal fun formatDownloadProgress(progress: Float?): String =
    when (progress) {
        null -> "Загрузка…"
        else -> "Загрузка ${max(0, (progress * 100).roundToInt())}%"
    }
