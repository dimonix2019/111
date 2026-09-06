package com.example.moexmvp

import android.content.Context
import android.os.Build
import android.util.Base64
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal const val DEBUG_REPORT_GITHUB_REPO = "dimonix2019/111"
internal const val DEBUG_REPORT_PATH_PREFIX = "debug-reports"
private const val PREF_DEBUG_REPORT = "debug_report_prefs"
private const val PREF_UPLOAD_TOKEN = "github_upload_token"

private val debugReportZone = ZoneId.of("Europe/Moscow")
private val debugReportStampFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

private val debugReportHttp = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    .build()

internal data class DebugReportUploadResult(
    val success: Boolean,
    val folderPath: String?,
    val uploadedFiles: List<String>,
    val browserUrl: String?,
    val errorHint: String?,
)

internal fun resolveDebugReportUploadToken(context: Context): String {
    val fromPrefs = context.applicationContext
        .getSharedPreferences(PREF_DEBUG_REPORT, Context.MODE_PRIVATE)
        .getString(PREF_UPLOAD_TOKEN, "")
        ?.trim()
        .orEmpty()
    if (fromPrefs.isNotEmpty()) return fromPrefs
    return BuildConfig.DEBUG_REPORT_UPLOAD_TOKEN.trim()
}

internal fun saveDebugReportUploadToken(context: Context, token: String) {
    context.applicationContext
        .getSharedPreferences(PREF_DEBUG_REPORT, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_UPLOAD_TOKEN, token.trim())
        .apply()
}

internal fun debugReportTokenConfigured(context: Context): Boolean =
    resolveDebugReportUploadToken(context).isNotEmpty()

internal fun sanitizeDebugReportPathSegment(raw: String): String =
    raw.trim()
        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        .trim('_')
        .take(120)
        .ifBlank { "unknown" }

internal fun debugReportTargetBranch(rawBranch: String = BuildConfig.GIT_BRANCH.trim()): String {
    val branch = rawBranch.trim()
    return if (branch.isBlank() || branch.equals("local", ignoreCase = true)) "main" else branch
}

internal fun debugReportUploadFolder(
    timestamp: String = Instant.now().atZone(debugReportZone).format(debugReportStampFmt),
    branch: String = debugReportTargetBranch(),
    versionCode: Int = BuildConfig.VERSION_CODE,
): String {
    val branchSeg = sanitizeDebugReportPathSegment(branch)
    return "$DEBUG_REPORT_PATH_PREFIX/$branchSeg/v$versionCode/$timestamp"
}

internal fun buildDebugReportManifestJson(
    folderPath: String,
    attachmentNames: List<String>,
    userNote: String?,
): String {
    val payload = JSONObject()
    payload.put("versionName", BuildConfig.VERSION_NAME)
    payload.put("versionCode", BuildConfig.VERSION_CODE)
    payload.put("gitBranch", BuildConfig.GIT_BRANCH)
    payload.put("gitSha", BuildConfig.GIT_SHA)
    payload.put("uploadBranch", debugReportTargetBranch())
    payload.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
    payload.put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    payload.put("exportedAt", Instant.now().atZone(debugReportZone).toString())
    payload.put("folder", folderPath)
    payload.put("attachments", attachmentNames)
    if (!userNote.isNullOrBlank()) {
        payload.put("note", userNote.trim().take(2000))
    }
    return payload.toString(2)
}

internal fun debugReportBrowserUrl(folderPath: String, branch: String = debugReportTargetBranch()): String =
    "https://github.com/$DEBUG_REPORT_GITHUB_REPO/tree/$branch/$folderPath"

internal suspend fun uploadDebugReportBundle(
    context: Context,
    userNote: String? = null,
): DebugReportUploadResult = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    val token = resolveDebugReportUploadToken(app)
    if (token.isBlank()) {
        return@withContext DebugReportUploadResult(
            success = false,
            folderPath = null,
            uploadedFiles = emptyList(),
            browserUrl = null,
            errorHint = "Токен GitHub не настроен. Установите CI-сборку или введите PAT в поле ниже " +
                "(scope: Contents read/write для репозитория).",
        )
    }
    val logText = MoexDiagnostics.exportText(app)
    if (logText.isBlank()) {
        return@withContext DebugReportUploadResult(
            success = false,
            folderPath = null,
            uploadedFiles = emptyList(),
            browserUrl = null,
            errorHint = "Журнал событий пуст.",
        )
    }
    val folder = debugReportUploadFolder()
    val branch = debugReportTargetBranch()
    val attachments = MoexDebugReportAttachments.list(app)
    val files = buildList {
        add("event-log.txt" to logText.toByteArray(Charsets.UTF_8))
        attachments.forEachIndexed { index, file ->
            val ext = file.extension.ifBlank { "png" }
            add("screenshot-${(index + 1).toString().padStart(2, '0')}.$ext" to file.readBytes())
        }
    }
    val names = files.map { it.first }.toMutableList()
    names += "manifest.json"
    val manifest = buildDebugReportManifestJson(folder, names.filter { it != "manifest.json" }, userNote)
    val allFiles = files + ("manifest.json" to manifest.toByteArray(Charsets.UTF_8))
    val uploaded = mutableListOf<String>()
    val commitPrefix = "debug report ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    for ((name, bytes) in allFiles) {
        val path = "$folder/$name"
        val err = putGitHubContentsFile(
            token = token,
            repo = DEBUG_REPORT_GITHUB_REPO,
            branch = branch,
            path = path,
            bytes = bytes,
            message = "$commitPrefix — $name",
        )
        if (err != null) {
            MoexDiagnostics.log(app, "debug_report", "upload_fail path=$path err=$err")
            return@withContext DebugReportUploadResult(
                success = false,
                folderPath = folder,
                uploadedFiles = uploaded,
                browserUrl = debugReportBrowserUrl(folder, branch),
                errorHint = err,
            )
        }
        uploaded += name
    }
    MoexDiagnostics.log(app, "debug_report", "upload_ok folder=$folder branch=$branch files=${uploaded.size}")
    DebugReportUploadResult(
        success = true,
        folderPath = folder,
        uploadedFiles = uploaded,
        browserUrl = debugReportBrowserUrl(folder, branch),
        errorHint = null,
    )
}

private fun putGitHubContentsFile(
    token: String,
    repo: String,
    branch: String,
    path: String,
    bytes: ByteArray,
    message: String,
): String? {
    if (bytes.size > 25 * 1024 * 1024) {
        return "Файл ${path.substringAfterLast('/')} слишком большой (${bytes.size / 1024} КБ)."
    }
    val body = JSONObject()
        .put("message", message.take(200))
        .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        .put("branch", branch)
    val url = "https://api.github.com/repos/$repo/contents/$path"
    val request = Request.Builder()
        .url(url)
        .put(body.toString().toRequestBody("application/json".toMediaType()))
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "MOEX-MVP-Android/${BuildConfig.VERSION_NAME}")
        .build()
    return runCatching {
        debugReportHttp.newCall(request).execute().use { response ->
            if (response.isSuccessful) return null
            val errBody = response.body?.string()?.take(400).orEmpty()
            when (response.code) {
                401, 403 -> "GitHub отклонил токен (HTTP ${response.code}). Проверьте PAT и права Contents."
                404 -> "Ветка «$branch» или репозиторий недоступны (HTTP 404)."
                else -> "GitHub HTTP ${response.code}: $errBody"
            }
        }
    }.getOrElse { "Сеть: ${it.message?.take(200) ?: it.javaClass.simpleName}" }
}
