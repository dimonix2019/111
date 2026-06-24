package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    fun parseAppUpdateManifestJson_publicMirrorUsesGhPagesApkEvenWhenManifestSaysRelease() {
        val json = """
            {"versionCode":136,"versionName":"1.7.18","apkUrl":"https://github.com/dimonix2019/111/releases/download/moexmvp-debug-latest/moexmvp-debug.apk"}
        """.trimIndent()
        val u = parseAppUpdateManifestJson(json, manifestUrl = APP_UPDATE_PUBLIC_MANIFEST_URL)
        assertNotNull(u)
        assertEquals(APP_UPDATE_PUBLIC_APK_URL, u!!.apkDownloadUrl)
    }

    @Test
    fun apkDownloadUrlCandidates_prefersReleaseBeforeGhPages() {
        val urls = apkDownloadUrlCandidates(APK_DOWNLOAD_DIRECT_URL, versionCode = 355)
        assertEquals(APK_DOWNLOAD_DIRECT_URL, urls.first())
        assertTrue(urls.any { it.startsWith(APP_UPDATE_PUBLIC_APK_URL) && it.contains("v=355") })
    }

    @Test
    fun cacheBustPublicMirrorUrl_appendsVersionCodeForApk() {
        val busted = cacheBustPublicMirrorUrl(APP_UPDATE_PUBLIC_APK_URL, versionCode = 355)
        assertTrue(busted.contains("v=355"))
    }

    @Test
    fun preferredInAppApkDownloadUrl_usesGhPagesForReleaseLink() {
        assertTrue(
            preferredInAppApkDownloadUrl(APK_DOWNLOAD_DIRECT_URL, versionCode = 136)
                .startsWith(APP_UPDATE_PUBLIC_APK_URL),
        )
    }

    @Test
    fun selectBestRemoteAppUpdate_rewritesDownloadToCacheBustedGhPages() {
        val best = selectBestRemoteAppUpdate(
            listOf(
                AppRemoteUpdate(135, "1.7.17", APK_DOWNLOAD_DIRECT_URL),
            )
        )
        assertTrue(best!!.apkDownloadUrl.startsWith(APP_UPDATE_PUBLIC_APK_URL))
        assertTrue(best.apkDownloadUrl.contains("v=135"))
    }

    @Test
    fun validateDownloadedAppUpdateApkStructure_rejectsTinyFile() {
        val dir = createTempDir()
        try {
            val f = File(dir, "tiny.apk")
            f.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00))
            assertNotNull(validateDownloadedAppUpdateApkStructure(f))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun validateDownloadedAppUpdateApkStructure_rejectsHtml() {
        val dir = createTempDir()
        try {
            val f = File(dir, "fake.apk")
            f.writeText("<html><body>login</body></html>".padEnd(6_000_000, 'x'))
            assertNotNull(validateDownloadedAppUpdateApkStructure(f))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun parseAppUpdateManifestJson_publicMirrorFallsBackToGhPagesApkWithoutReleaseUrl() {
        val json = """
            {"versionCode":132,"versionName":"1.7.14","apkUrl":""}
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
    fun appUpdateManifestUrlCandidates_prefersReleaseManifest() {
        val urls = appUpdateManifestUrlCandidates()
        assertEquals(APP_UPDATE_MANIFEST_URL, urls.first())
    }

    @Test
    fun selectBestRemoteAppUpdate_picksHighestVersionCode() {
        val ghApk = "https://raw.githubusercontent.com/dimonix2019/111/gh-pages/moexmvp-debug.apk"
        val best = selectBestRemoteAppUpdate(
            listOf(
                AppRemoteUpdate(132, "1.7.14", ghApk),
                AppRemoteUpdate(135, "1.7.17", ghApk),
            )
        )
        assertNotNull(best)
        assertEquals(135, best!!.versionCode)
        assertEquals("1.7.17", best.versionName)
        assertTrue(best.apkDownloadUrl.startsWith(ghApk))
        assertTrue(best.apkDownloadUrl.contains("v=135"))
    }

    @Test
    fun isNewerAppUpdateAvailable_comparesVersionCode() {
        val remote = AppRemoteUpdate(105, "1.6.93", "https://example.com/a.apk")
        assertTrue(isNewerAppUpdateAvailable(remote, localVersionCode = 104))
        assertFalse(isNewerAppUpdateAvailable(remote, localVersionCode = 105))
    }
}
