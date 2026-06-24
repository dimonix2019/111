package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexDebugReportUploadTest {

    @Test
    fun sanitizeDebugReportPathSegment_replacesUnsafeChars() {
        assertEquals("cursor_fix-chart-forming-bar-gap-915f", sanitizeDebugReportPathSegment("cursor/fix-chart-forming-bar-gap-915f"))
    }

    @Test
    fun debugReportUploadFolder_includesBranchVersionAndTimestamp() {
        val folder = debugReportUploadFolder(
            timestamp = "20260624-065440",
            branch = "cursor/test-branch",
            versionCode = 357,
        )
        assertEquals("debug-reports/cursor_test-branch/v357/20260624-065440", folder)
    }

    @Test
    fun debugReportTargetBranch_mapsLocalAndBlankToMain() {
        assertEquals("main", debugReportTargetBranch(""))
        assertEquals("main", debugReportTargetBranch("local"))
        assertEquals(
            "cursor/fix-chart-forming-bar-gap-915f",
            debugReportTargetBranch("cursor/fix-chart-forming-bar-gap-915f"),
        )
    }

    @Test
    fun buildDebugReportManifestJson_includesAttachments() {
        val json = buildDebugReportManifestJson(
            folderPath = "debug-reports/test/v1/t",
            attachmentNames = listOf("event-log.txt", "screenshot-01.png"),
            userNote = "chart gap",
        )
        assertTrue(json.contains("event-log.txt"))
        assertTrue(json.contains("chart gap"))
    }

    @Test
    fun debugReportBrowserUrl_pointsToGithubTree() {
        val url = debugReportBrowserUrl("debug-reports/cursor/x/v1/t", "cursor/x")
        assertTrue(url.startsWith("https://github.com/dimonix2019/111/tree/cursor/x/debug-reports/"))
    }
}
