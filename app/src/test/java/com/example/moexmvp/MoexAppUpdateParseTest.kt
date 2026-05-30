package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexAppUpdateParseTest {

    @Test
    fun parseAppUpdateManifestJson_readsVersionAndUrl() {
        val json = """
            {"versionCode":104,"versionName":"1.6.92","apkUrl":"https://example.com/app.apk"}
        """.trimIndent()
        val u = parseAppUpdateManifestJson(json)
        assertNotNull(u)
        assertEquals(104, u!!.versionCode)
        assertEquals("1.6.92", u.versionName)
        assertEquals("https://example.com/app.apk", u.apkDownloadUrl)
    }

    @Test
    fun parseVersionFromReleaseTitle_parsesNameAndCode() {
        val parsed = parseVersionFromReleaseTitle("MOEX MVP debug — 1.6.91 (103)")
        assertNotNull(parsed)
        assertEquals("1.6.91", parsed!!.first)
        assertEquals(103, parsed.second)
    }

    @Test
    fun parseGitHubReleaseJson_parsesAssets() {
        val json = """
            {
              "name": "MOEX MVP debug — 1.6.92 (104)",
              "body": "Сборка **1.6.92** (versionCode 104).",
              "assets": [
                {"name": "moexmvp-debug.apk", "browser_download_url": "https://github.com/x/y.apk"}
              ]
            }
        """.trimIndent()
        val u = parseGitHubReleaseJson(json)
        assertNotNull(u)
        assertEquals(104, u!!.versionCode)
        assertEquals("1.6.92", u.versionName)
        assertEquals("https://github.com/x/y.apk", u.apkDownloadUrl)
    }

    @Test
    fun checkAppUpdateStatus_remoteOlderWhenLocalIsNewer() {
        val status = AppUpdateCheckStatus.RemoteOlder(
            AppRemoteUpdate(114, "1.7.02", "https://example.com/a.apk")
        )
        val text = formatAppUpdateCheckStatus(status)
        assertTrue(text.contains("114"))
        assertTrue(text.contains("GitHub"))
    }

    @Test
    fun parseAppUpdateManifestJson_publicMirrorUsesGhPagesApk() {
        val json = """
            {"versionCode":132,"versionName":"1.7.14","apkUrl":"https://github.com/x/y.apk"}
        """.trimIndent()
        val u = parseAppUpdateManifestJson(json, manifestUrl = APP_UPDATE_PUBLIC_MANIFEST_URL)
        assertNotNull(u)
        assertEquals(APP_UPDATE_PUBLIC_APK_URL, u!!.apkDownloadUrl)
    }

    @Test
    fun formatAppUpdateFetchFailure_mentionsPrivateRepoOn404() {
        val text = formatAppUpdateFetchFailure(
            AppUpdateFetchDiagnostics(
                update = null,
                lastHttpCode = 404,
                privateRepoLikely = true,
                triedUrls = listOf(APP_UPDATE_PUBLIC_MANIFEST_URL),
            )
        )
        assertTrue(text.contains("private"))
        assertTrue(text.contains("404"))
    }

    @Test
    fun appUpdateManifestUrlCandidates_prefersPublicMirror() {
        val urls = appUpdateManifestUrlCandidates()
        assertEquals(APP_UPDATE_PUBLIC_MANIFEST_URL, urls.first())
    }

    @Test
    fun isNewerAppUpdateAvailable_comparesVersionCode() {
        val remote = AppRemoteUpdate(105, "1.6.93", "https://example.com/a.apk")
        assertTrue(isNewerAppUpdateAvailable(remote, localVersionCode = 104))
        assertFalse(isNewerAppUpdateAvailable(remote, localVersionCode = 105))
    }
}
