package com.example.moexmvp.desktop

import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

private val moexZoneId: ZoneId = ZoneId.of("Europe/Moscow")

/** Пути к CSV 15м относительно корня репозитория (запуск из MoexMvp). */
internal fun defaultM15CsvCandidates(repoRoot: Path): List<Path> = listOf(
    repoRoot.resolve("strategy-web/data/m15_tatn_255d.csv"),
    repoRoot.resolve("strategy-web/data/m15_tatn_365d.csv"),
    repoRoot.resolve("strategy-web/data/m15_test_3d.csv"),
)

internal fun resolveM15CsvPath(repoRoot: Path, explicit: String? = null): Path? {
    if (!explicit.isNullOrBlank()) {
        val p = Path.of(explicit)
        return p.takeIf { it.isRegularFile() }
    }
    return defaultM15CsvCandidates(repoRoot).firstOrNull { it.isRegularFile() }
}

internal fun loadM15PointsFromCsv(path: Path): List<DataPoint> {
    require(path.isRegularFile()) { "CSV not found: $path" }
    val out = ArrayList<DataPoint>(8_000)
    path.bufferedReader().useLines { lines ->
        val iter = lines.iterator()
        if (!iter.hasNext()) return emptyList()
        val header = iter.next().lowercase()
        val hasZ = header.contains("z_score")
        while (iter.hasNext()) {
            val line = iter.next().trim()
            if (line.isEmpty()) continue
            val cols = line.split(',')
            if (cols.size < 5) continue
            val tsLabel = cols[0].trim()
            val z = if (hasZ) cols[1].trim().toDoubleOrNull() ?: 0.0 else 0.0
            val spreadIdx = if (hasZ) 2 else 1
            val tatnIdx = if (hasZ) 3 else 2
            val tatnpIdx = if (hasZ) 4 else 3
            val spread = cols.getOrNull(spreadIdx)?.trim()?.toDoubleOrNull() ?: 0.0
            val tatn = cols.getOrNull(tatnIdx)?.trim()?.toDoubleOrNull() ?: 0.0
            val tatnp = cols.getOrNull(tatnpIdx)?.trim()?.toDoubleOrNull() ?: 0.0
            val millis = parseCsvTimestampMillis(tsLabel)
            val label = if (tsLabel.length >= 16) {
                tsLabel.replace('T', ' ').take(16)
            } else {
                tsLabel
            }
            out += DataPoint(
                timestampMillis = millis,
                tradeDate = label,
                tatnClose = tatn,
                tatnpClose = tatnp,
                spreadPercent = spread,
                diff = tatn - tatnp,
                zScore = z,
            )
        }
    }
    return out.sortedBy { it.timestampMillis }
}

private fun parseCsvTimestampMillis(raw: String): Long {
    val s = raw.trim().replace('T', ' ')
    runCatching {
        val fmt = if (s.length > 16) {
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        } else {
            portfolio15mLabelFormatter
        }
        LocalDateTime.parse(s.take(19).trim(), fmt)
            .atZone(moexZoneId)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()?.let { return it }
    return 0L
}

internal fun detectRepoRoot(): Path {
    var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    repeat(6) {
        if (dir.resolve("settings.gradle.kts").exists() ||
            dir.resolve("gradlew.bat").exists()
        ) {
            return dir
        }
        dir = dir.parent ?: return dir
    }
    return Path.of(System.getProperty("user.dir")).toAbsolutePath()
}
