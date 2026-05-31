#!/usr/bin/env python3
"""Split oversized Moex MVP Kotlin files into focused units (same package, no import churn)."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path("app/src/main/java/com/example/moexmvp")


def read(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines(keepends=True)


def header_end(lines: list[str]) -> int:
    for i, ln in enumerate(lines):
        s = ln.strip()
        if s and not s.startswith("import ") and not s.startswith("package "):
            return i
    return len(lines)


def write_file(name: str, import_block: list[str], body: list[str]) -> None:
    imports = [ln for ln in import_block if ln.startswith("import ")]
    body_clean = [
        ln for ln in body if not ln.startswith("package ") and not ln.startswith("import ")
    ]
    (ROOT / name).write_text(
        "package com.example.moexmvp\n\n" + "".join(imports) + "\n" + "".join(body_clean),
        encoding="utf-8",
    )
    print(f"  {name}: {len(body_clean)} lines")


def split_file(src_name: str, parts: list[tuple[str, int]]) -> None:
    src = ROOT / src_name
    lines = read(src)
    import_block = lines[: header_end(lines)]
    for i, (name, start) in enumerate(parts):
        end = parts[i + 1][1] - 1 if i + 1 < len(parts) else len(lines)
        write_file(name, import_block, lines[start - 1 : end])
    src.unlink()
    print(f"  removed {src_name}")


def method_to_extension(block: str) -> str:
    return re.sub(
        r"^    suspend fun (\w+)",
        r"internal suspend fun MoexScreenState.\1",
        block,
        flags=re.M,
    )


def split_screen_state() -> None:
    src = ROOT / "MoexScreenState.kt"
    text = src.read_text(encoding="utf-8")

    marker = "    var initialMarketsRefreshDone by mutableStateOf(false)\n"
    pos = text.index(marker) + len(marker)
    props = text[text.index("@Stable") : pos] + "}\n"

    (ROOT / "MoexScreenState.kt").write_text(
        "package com.example.moexmvp\n\n"
        "import android.content.Context\n"
        "import androidx.compose.runtime.Stable\n"
        "import androidx.compose.runtime.getValue\n"
        "import androidx.compose.runtime.mutableStateOf\n"
        "import androidx.compose.runtime.setValue\n"
        "import kotlinx.coroutines.sync.Mutex\n"
        "import java.time.LocalDate\n\n"
        + props,
        encoding="utf-8",
    )

    ext_imports = (
        "package com.example.moexmvp\n\n"
        "import android.widget.Toast\n"
        "import kotlinx.coroutines.CoroutineScope\n"
        "import kotlinx.coroutines.Dispatchers\n"
        "import kotlinx.coroutines.launch\n"
        "import kotlinx.coroutines.sync.withLock\n"
        "import kotlinx.coroutines.withContext\n"
        "import java.time.LocalDate\n"
        "import java.util.Locale\n\n"
    )

    h0 = text.index("    /** Быстрый старт")
    h1 = text.index("    /** Только дневной ряд")
    m1 = text.index("    suspend fun refreshPortfolioUnlocked")
    p1 = text.rindex("\n}")

    (ROOT / "MoexScreenStateHydrate.kt").write_text(
        ext_imports + method_to_extension(text[h0:h1].rstrip()) + "\n", encoding="utf-8"
    )
    (ROOT / "MoexScreenStateMarketsRefresh.kt").write_text(
        ext_imports + method_to_extension(text[h1:m1].rstrip()) + "\n", encoding="utf-8"
    )
    (ROOT / "MoexScreenStatePortfolioRefresh.kt").write_text(
        ext_imports + method_to_extension(text[m1:p1].rstrip()) + "\n", encoding="utf-8"
    )
    src.unlink()
    print("  removed MoexScreenState.kt (split into 4)")


def main() -> None:
    print("Splitting MoexChartsCanvas.kt …")
    split_file(
        "MoexChartsCanvas.kt",
        [
            ("MoexChartsCanvasLine.kt", 59),
            ("MoexChartsCanvasCandlestick.kt", 409),
            ("MoexChartsCanvasEquity.kt", 860),
        ],
    )
    print("Splitting MoexChartsHelpers.kt …")
    split_file(
        "MoexChartsHelpers.kt",
        [
            ("MoexChartsMarkers.kt", 66),
            ("MoexChartsM15Series.kt", 220),
            ("MoexChartsAxis.kt", 397),
        ],
    )
    print("Splitting MoexChartsUi.kt …")
    split_file(
        "MoexChartsUi.kt",
        [
            ("MoexChartsUiStates.kt", 66),
            ("MoexChartsUiControls.kt", 133),
            ("MoexChartsUiCards.kt", 248),
            ("MoexChartsUiLandscape.kt", 585),
        ],
    )
    print("Splitting MoexIssData.kt …")
    split_file(
        "MoexIssData.kt",
        [
            ("MoexIssParse.kt", 24),
            ("MoexIssFetch.kt", 152),
            ("MoexIssPortfolio15m.kt", 339),
            ("MoexIssPrefs.kt", 526),
            ("MoexIssStrategy.kt", 789),
        ],
    )
    print("Splitting MoexPortfolioUiCommon.kt …")
    split_file(
        "MoexPortfolioUiCommon.kt",
        [
            ("MoexPortfolioUiNav.kt", 58),
            ("MoexPortfolioUiHeader.kt", 108),
            ("MoexPortfolioUiTables.kt", 336),
            ("MoexPortfolioUiMetrics.kt", 638),
        ],
    )
    print("Splitting MoexScreenState.kt …")
    split_screen_state()
    print("Done.")


if __name__ == "__main__":
    main()
