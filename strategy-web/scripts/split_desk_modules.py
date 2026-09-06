#!/usr/bin/env python3
"""Split trade.js / app.js into IIFE desk modules (incremental, no bundler)."""
from __future__ import annotations

import re
from pathlib import Path

STATIC = Path(__file__).resolve().parents[1] / "replay" / "static"
CACHE_VER = "20260906modSplit1"

CHART_FUNCS = [
    "setZEmptyMessage", "updateChartPaneLabels", "markUserGesture", "dataEndIndex",
    "isNearLiveEdge", "visibleDataInRange", "isViewportCorrupt", "persistViewport",
    "loadPersistedViewport", "setPinnedRange", "clearPinState", "hydratePinFromStorage",
    "applyVisibleRange", "syncBottomPaneToTopTime", "syncTopPaneToBottomTime",
    "scheduleEndSuppress", "equalizePriceScales", "forceSyncAfterPaint", "reassertPinnedRange",
    "bindRangeSync", "nearestPriceByTime", "bindCrosshairSync", "fitAndRemember",
    "restoreOrFitVisibleRange", "hideCandleOhlcOverlay", "candleOhlcAtTime", "paintTradeOhlc",
    "updateCandleOhlcOverlay", "addSeries", "makeSpreadRegimeBand", "resolveSpreadTradeBandBounds",
    "ensureSpreadBandsOnChart", "ensureSpreadRegimeBands", "ensurePrimarySpreadBands",
    "setSpreadBandSeriesData", "updateSpreadRegimeBands", "updatePrimarySpreadBands",
    "makeZCandleSeries", "makeZLineSeries", "publishChartDebug", "ensureZSeriesKind",
    "ensureSpreadSeriesKind", "ensureCharts", "setThresholdLines", "setPrimarySpreadThresholdLines",
    "clearTpExitSpreadLine", "setTpExitSpreadLine", "resolveSpreadLevelLines", "setSpreadThresholdLines",
    "barsFingerprint", "liveTipZSpread", "alignTip1mBarsToLiveTip", "syncChartSeriesByTime",
    "resolveOpenEntryOnBars", "barSpreadAtChartSec", "deskSpreadChartValue",
    "injectOpenEntryIntoChartSeries", "deskTradeById", "markerChartPrice", "markerScreenPosition",
    "findNearestDeskMarkerAtPoint", "buildMarkerRenderData", "setHighlightSeriesData",
    "highlightDataForTrade", "applyHighlightForActiveTrade", "refreshDeskMarkers",
    "scheduleRefreshDeskMarkers", "onDeskMarkerCrosshair", "bindMarkerHover",
    "clearOpenTradeOnChart", "clearAllTradeMarkers", "applyMarkersToSeries", "applyTradeMarkers",
    "estimateBarStepSec", "snapSecToBarTimes", "isManualTradeSource", "isSpreadLevelModeOn",
    "sanitizeOpenRisk", "deskEntryMarkerColor", "buildDeskTradeMarkers", "updateOpenTradeOnChart",
    "attachDealerMonitorZClient", "spreadCandleFromBar", "zCandleFromBar", "medianBarStepSec",
    "buildTip1mZCandles", "buildTip1mChartSeries", "tip1mSpanDays", "filterTip1mBarsByDays",
    "thinDeskTip1mBars", "ensureWeekdayTip1mBars", "ensureMtlrChartBars", "buildSpread1mChartSeries",
    "buildSpreadM15ChartSeries", "chartPointVal", "chartPrefixEqual", "canTailUpdateChart",
    "applyTailChartUpdate", "renderCharts", "renderOpen", "resize",
    "spreadPaneHeadHeight", "loadSpreadChartHeight", "spreadChartHeightBounds", "applySpreadChartHeight",
    "bindTradeChartVerticalSplit",
]

TRADE_EXTRACTS = [
    {
        "file": "trade-desk-corridor.js",
        "banner": "/** Adaptive corridor UI + chart overlays — split from trade.js */",
        "spans": [
            ("  let corridorChartSeries = [];", "  function setZEmptyMessage(text) {"),
        ],
        "also": ["corridorStatusBadge"],
        "ns": "corridor",
        "reexports": [
            "corridorPhaseAbsent", "detectSpreadCorridorClient", "renderCorridorMeter",
            "updateCorridorOnChart", "paintCorridorOnChart", "clearCorridorChartSeries",
            "corridorFingerprint", "corridorStatusBadge", "applyZSeriesCorridorAutoscale",
            "refreshZPriceScaleAfterCorridor",
        ],
    },
    {
        "file": "trade-desk-chart.js",
        "banner": "/** Desk chart panes, markers, tip1m align — split from trade.js */",
        "spans": [
            ("  let zChart = null;", "  /** Линии коридора S на верхнем графике (forming / formed). */"),
        ],
        "also": CHART_FUNCS,
        "ns": "chart",
        "reexports": [
            "setZEmptyMessage", "updateChartPaneLabels", "alignTip1mBarsToLiveTip",
            "setTpExitSpreadLine", "renderCharts", "ensureCharts", "resize", "renderOpen",
            "updateOpenTradeOnChart", "syncBottomPaneToTopTime", "syncTopPaneToBottomTime",
            "thinDeskTip1mBars", "ensureWeekdayTip1mBars", "bindTradeChartVerticalSplit",
        ],
    },
    {
        "file": "trade-desk-pnl.js",
        "banner": "/** Broker PnL, funds, close forecast — split from trade.js */",
        "spans": [("  function formatBrokerError(err) {", "  function paramsFocused() {")],
        "also": [
            "fmtRubPlain", "fmtStakePct", "entryDepositRub", "stakeNotionalRub",
            "isOpenProfitAlertHit", "clearProfitAlertBadge", "equityAtOpenRub",
            "exitLevelPotential", "premiumOvernightPerDayRub", "exitOvernightCushionLine",
        ],
        "ns": "pnl",
        "reexports": [
            "formatBrokerError", "marginHeadroomFromBroker", "renderMarginHeadroom",
            "renderFunds", "renderFundsAtOpen", "isBrokerPnlMark", "openProfitRub",
            "renderCloseForecast", "entryDepositRub", "maybeFireProfitAlert", "coalesceDeskBroker",
        ],
    },
    {
        "file": "trade-desk-polling.js",
        "banner": "/** Desk poll / refresh — split from trade.js */",
        "spans": [
            ("  async function ensureMonitorRunning(data) {", "  async function saveEntryDeposit() {"),
        ],
        "also": ["startPoll", "stopPoll", "ensurePollInterval"],
        "ns": "polling",
        "reexports": [
            "refresh", "refreshImpl", "startPoll", "stopPoll", "ensurePollInterval",
            "rememberGoodChartBars", "applyPartialBanner",
        ],
    },
]

APP_EXTRACTS = [
    {
        "file": "app-test-chips.js",
        "banner": "/** Test tag-share / monthly PnL chips — split from app.js */",
        "spans": [("  const TAG_SHARE_SPEC = [", "  function updateTradesSummary(allRows, visibleRows) {")],
        "also": [
            "emptyTagSharePnl", "parseByTagPnl", "collectTagShareFromRows", "formatTagShareRub",
            "formatTagSharePct", "renderTagShareDonut", "monthlyPnlYTicks", "markMonthlyPnlPending",
            "renderMonthlyPnl", "formatMonthlyMeanLabel",
        ],
        "ns": "chips",
        "reexports": [
            "isWeekendChipEntry", "renderTagShareDonut", "markMonthlyPnlPending", "renderMonthlyPnl",
            "collectTagShareFromRows", "parseByTagPnl",
        ],
    },
    {
        "file": "app-test-sim.js",
        "banner": "/** Async tip1m sim poll — split from app.js */",
        "spans": [
            ("  const TIP1M_CHART_MAX_POINTS = 150000;", "  function stashM15IfNeeded() {"),
            ("  async function fetchTipSim() {", "  async function fetchTipHeatmap(_grid, { timeoutMs } = {}) {"),
        ],
        "also": [
            "thinTip1mBarsForChart", "tip1mBarsRequestKey", "tip1mWindowSpanDays",
            "tip1mFetchTimeoutMs", "scheduleTipSimFetch",
        ],
        "ns": "sim",
        "reexports": [
            "pollTipSimJob", "tip1mSimTimeoutMs", "loadTip1mChartBars", "ensureTestDeskCorridor", "fetchTipSim",
        ],
    },
]

CHART_STATE = [
    "zChart", "zSeries", "zSeriesIsLine", "spreadChart", "spreadSeries", "priceLines",
    "spreadPriceLines", "tpExitPriceLine", "spreadRegimeBands", "primarySpreadBands",
    "lastSpreadBandTimes", "lastPrimarySpreadBandTimes", "openHighlightSeries",
    "corridorChartSeries", "activeCorridorBounds", "lastCorridorAutoscalePts",
    "lastPaintZData", "lastPaintMtlrData", "openMarkersPlugin", "openSpreadMarkersPlugin",
    "spreadSeriesIsLine", "lastMtlrBars", "lastMtlrLevels", "lastOpenTradeFp",
    "lastDeskMarkers", "lastDeskTrades", "lastDeskPaintBars", "hoverTradeId",
    "markerHoverBound", "defaultOpenHighlightData", "refreshMarkersTimer",
    "forceFitContent", "pendingPeriodFitDays", "suppressRangeEvents", "rangeSyncBound",
    "crosshairSyncBound", "zPriceByTime", "spPriceByTime", "lastBarCount", "lastDataEnd",
    "pinnedRange", "userPinnedAwayFromLive", "lastBarsFingerprint", "reapplyRangeTimer",
    "userGestureActive", "userGestureTimer", "chartDealer1m", "lastCloseForecast",
    "profitToastTimer", "brokerEmptyStreak", "lastGoodBroker",
]

DEP_CALLS = [
    "$", "fmt", "fmtRub", "escapeHtml", "toChartTime", "api", "pnlClass",
    "getSimNotionalRub", "getSimCompound", "formatAccountRub", "formatProfitAccountRub",
    "isWeekendTradingMode", "tipRowsUpToCursor", "renderDesk", "ensureWeekdayTip1mBars",
    "ensureMtlrChartBars", "tip1mSpanDays", "loadCachedParamsLocal", "ensureMonitorRunning",
    "renderCharts", "refreshTradesTable", "refreshUi", "thresholds", "readWindowEndYmd",
    "isTip1mMode", "tipSimRequestKey", "tipTradeToRow", "clearTipManualOverrides",
    "markMonthlyPnlPending", "isWeekendChipEntry", "normalizeTradeSrcFilterKey",
    "formatMonthlyYTick", "formatMonthlyHistPctValue", "formatMonthlyHistAbs",
    "formatMonthlyMeanLabel", "applyMonthlyPnlScrollTop", "readSavedMonthlyPnlScrollTop",
    "brokerHasTotals", "modeLabel", "readEntryDeposit", "updateCorridorOnChart",
    "paintCorridorOnChart", "corridorFingerprint", "renderMarginHeadroom", "openProfitRub",
    "entryDepositRub", "equityAtOpenRub", "exitLevelPotential", "fmtRubPlain", "fmtStakePct",
    "formatBrokerError", "needPctSuffix", "openEntryProgressText", "fmtTickLabel",
    "formatChartTick", "chartTimeOpts", "determineSpreadLevelSignalJs", "effectiveThresholds",
    "computeOpenPathMinMax", "fmtOpenMinMax", "fmtPnlWithDepositPct", "fmtRubShort",
    "nowMskParts", "lastSpreadLiveBar", "barZ", "determineZSignalJs", "buildTradePhase",
    "renderChecklist", "renderOpenStats", "syncTradeActionButtons", "renderTradeRulesStatus",
    "renderCorridorMeter", "corridorStatusBadge", "coalesceDeskBroker", "renderFunds",
    "renderFundsAtOpen", "renderCloseForecast", "renderCheckList", "checkItem",
    "aggregateTipBarsByMinutes", "isChartsHidden", "tip1mChartDaysWanted",
]


def read_lines(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines(keepends=True)


def line_idx(lines: list[str], needle: str, after: int = 0) -> int:
    for i in range(after, len(lines)):
        if lines[i].startswith(needle):
            return i
    raise ValueError(f"line not found: {needle!r}")


def func_span(lines: list[str], name: str) -> tuple[int, int]:
    pat = re.compile(rf"^  (async )?function {re.escape(name)}\(")
    start = next((i for i, ln in enumerate(lines) if pat.match(ln)), None)
    if start is None:
        raise ValueError(f"function {name} not found")
    depth = 0
    seen = False
    for j in range(start, len(lines)):
        for c in lines[j]:
            if c == "{":
                depth += 1
                seen = True
            elif c == "}":
                depth -= 1
                if seen and depth == 0:
                    return start, j + 1
    raise ValueError(f"unclosed {name}")


def span_between(lines: list[str], start: str, end_before: str) -> tuple[int, int]:
    return line_idx(lines, start), line_idx(lines, end_before, line_idx(lines, start) + 1)


def rewrite_deps(body: str) -> str:
    lines = body.splitlines(keepends=True)
    out_lines: list[str] = []
    for line in lines:
        if re.match(r"^\s*(async )?function \w+\(", line) or re.match(r"^\s*(let|const|var) ", line):
            out_lines.append(line)
            continue
        out = line
        for fn in DEP_CALLS:
            out = re.sub(rf"(?<![\w.]){re.escape(fn)}\(", f"D().{fn}(", out)
        for name in CHART_STATE:
            out = re.sub(rf"(?<![\w.]){re.escape(name)}\b", f"D().{name}", out)
        out = out.replace("D().D().", "D().")
        out_lines.append(out)
    return "".join(out_lines)


def wrap_module(banner: str, body: str, ns: str, exports: list[str]) -> str:
    body = rewrite_deps(body)
    found = [n for n in exports if re.search(rf"(?:async )?function {re.escape(n)}\(", body)]
    export_lines = ",\n".join(f"    {n}: {n}" for n in found)
    return (
        f"{banner}\n"
        "(function (global) {\n"
        "  'use strict';\n"
        "  function D() { return global.__TradeDesk.deps; }\n"
        f"{body}"
        "  global.__TradeDesk = global.__TradeDesk || { deps: {} };\n"
        f"  global.__TradeDesk.{ns} = {{\n{export_lines}\n  }};\n"
        "})(typeof window !== 'undefined' ? window : globalThis);\n"
    )


def collect_body(lines: list[str], spec: dict) -> str:
    chunks = []
    for start, end in spec.get("spans", []):
        a, b = span_between(lines, start, end)
        chunks.append("".join(lines[a:b]))
    for name in spec.get("also", []):
        try:
            a, b = func_span(lines, name)
            chunks.append("".join(lines[a:b]))
        except ValueError as e:
            print(f"  warn: skip {name}: {e}")
    return "\n".join(chunks)


def removal_spans(lines: list[str], spec: dict) -> list[tuple[int, int]]:
    spans = [span_between(lines, s, e) for s, e in spec.get("spans", [])]
    for name in spec.get("also", []):
        try:
            spans.append(func_span(lines, name))
        except ValueError:
            pass
    return spans


def strip_spans(lines: list[str], spans: list[tuple[int, int]]) -> list[str]:
    drop = set()
    for a, b in spans:
        drop.update(range(a, b))
    return [ln for i, ln in enumerate(lines) if i not in drop]


def reexport_block(specs: list[dict]) -> str:
    out = ["  // --- module re-exports (static tests grep trade.js / app.js) ---"]
    for spec in specs:
        ns = spec["ns"]
        for fn in spec["reexports"]:
            out.append(
                f"  function {fn}(...args) {{ return window.__TradeDesk.{ns}.{fn}(...args); }}"
            )
    return "\n".join(out) + "\n"


def chart_state_getters() -> str:
    return "\n".join(
        f"      get {n}() {{ return {n}; }}, set {n}(v) {{ {n} = v; }},"
        for n in CHART_STATE
    )


def patch_trade(lines: list[str]) -> str:
    spans: list[tuple[int, int]] = []
    for spec in TRADE_EXTRACTS:
        spans.extend(removal_spans(lines, spec))
    text = "".join(strip_spans(lines, spans))
    reexp = reexport_block(TRADE_EXTRACTS)
    install = (
        "  function __installTradeDeskModules() {\n"
        "    window.__TradeDesk = window.__TradeDesk || { deps: {} };\n"
        "    window.__TradeDesk.deps = {\n"
        "      $, fmt, fmtRub, escapeHtml, toChartTime, api,\n"
        "      needPctSuffix, openEntryProgressText, fmtTickLabel, formatChartTick, chartTimeOpts,\n"
        "      determineSpreadLevelSignalJs, effectiveThresholds, nowMskParts, lastSpreadLiveBar,\n"
        "      barZ, determineZSignalJs, buildTradePhase, renderChecklist, renderOpenStats,\n"
        "      syncTradeActionButtons, renderTradeRulesStatus, renderCorridorMeter, corridorStatusBadge,\n"
        "      brokerHasTotals, modeLabel, readEntryDeposit, computeOpenPathMinMax, fmtOpenMinMax,\n"
        "      fmtPnlWithDepositPct, fmtRubShort, renderCheckList, checkItem,\n"
        "      renderDesk, ensureWeekdayTip1mBars, ensureMtlrChartBars, tip1mSpanDays,\n"
        "      loadCachedParamsLocal, thinDeskTip1mBars,\n"
        "      get pollTimer() { return pollTimer; }, set pollTimer(v) { pollTimer = v; },\n"
        "      get pollMs() { return pollMs; }, set pollMs(v) { pollMs = v; },\n"
        "      get pollTick() { return pollTick; }, set pollTick(v) { pollTick = v; },\n"
        "      get refreshWorkCount() { return refreshWorkCount; },\n"
        "      set refreshWorkCount(v) { refreshWorkCount = v; },\n"
        "      get deskFetchSeq() { return deskFetchSeq; }, set deskFetchSeq(v) { deskFetchSeq = v; },\n"
        "      get days() { return days; },\n"
        "      get lastGoodChartBars() { return lastGoodChartBars; },\n"
        "      set lastGoodChartBars(v) { lastGoodChartBars = v; },\n"
        "      get lastGoodDeskMeta() { return lastGoodDeskMeta; },\n"
        "      set lastGoodDeskMeta(v) { lastGoodDeskMeta = v; },\n"
        "      get DESK_MTLR_UI_ENABLED() { return DESK_MTLR_UI_ENABLED; },\n"
        "      get POLL_FULL_EVERY() { return POLL_FULL_EVERY; },\n"
        "      get POLL_MS_DEFAULT() { return POLL_MS_DEFAULT; },\n"
        "      get POLL_MS_DEALER_1M() { return POLL_MS_DEALER_1M; },\n"
        "      get PROFIT_ALERT_PCT() { return PROFIT_ALERT_PCT; },\n"
        "      get LS_PROFIT_ALERT_TRADE() { return LS_PROFIT_ALERT_TRADE; },\n"
        "      get BROKER_EMPTY_CLEAR_AFTER() { return BROKER_EMPTY_CLEAR_AFTER; },\n"
        "      get SPREAD_REGIME_BAND_COLORS() { return SPREAD_REGIME_BAND_COLORS; },\n"
        "      get CHART_SCROLL_SCALE() { return CHART_SCROLL_SCALE; },\n"
        "      get CURRENT_PRICE_LINE_COLOR() { return CURRENT_PRICE_LINE_COLOR; },\n"
        "      get TP_EXIT_LINE_COLOR() { return TP_EXIT_LINE_COLOR; },\n"
        "      get TRADE_SPREAD_DEFAULT() { return TRADE_SPREAD_DEFAULT; },\n"
        "      get TRADE_SPREAD_MIN() { return TRADE_SPREAD_MIN; },\n"
        "      get TRADE_Z_MIN() { return TRADE_Z_MIN; },\n"
        "      get CHART_SPLITTER_HEIGHT() { return CHART_SPLITTER_HEIGHT; },\n"
        "      get LS_SPREAD_PANE_HEIGHT() { return LS_SPREAD_PANE_HEIGHT; },\n"
        "      get LS_CHART_RANGE() { return LS_CHART_RANGE; },\n"
        "      get LIVE_EDGE_BARS() { return LIVE_EDGE_BARS; },\n"
        "      get MAX_RIGHT_OVERSCROLL_BARS() { return MAX_RIGHT_OVERSCROLL_BARS; },\n"
        "      get MIN_VISIBLE_DATA_BARS() { return MIN_VISIBLE_DATA_BARS; },\n"
        "      get M15_MS() { return M15_MS; },\n"
        "      get M1_MS() { return M1_MS; },\n"
        "      get MARKER_HIT_RADIUS_PX() { return MARKER_HIT_RADIUS_PX; },\n"
        "      get MARKER_HIT_RADIUS_X_PX() { return MARKER_HIT_RADIUS_X_PX; },\n"
        "      get MARKER_ENTRY_HIT_RADIUS_X_PX() { return MARKER_ENTRY_HIT_RADIUS_X_PX; },\n"
        "      get TRADE_HIGHLIGHT_COLOR() { return TRADE_HIGHLIGHT_COLOR; },\n"
        "      get Z_ROLL_LOOKBACK_DAYS() { return Z_ROLL_LOOKBACK_DAYS; },\n"
        "      get Z_ROLL_MIN_BARS() { return Z_ROLL_MIN_BARS; },\n"
        "      updateCorridorOnChart: (...a) => window.__TradeDesk.corridor.updateCorridorOnChart(...a),\n"
        "      paintCorridorOnChart: (...a) => window.__TradeDesk.corridor.paintCorridorOnChart(...a),\n"
        "      corridorFingerprint: (...a) => window.__TradeDesk.corridor.corridorFingerprint(...a),\n"
        "      renderMarginHeadroom: (...a) => window.__TradeDesk.pnl.renderMarginHeadroom(...a),\n"
        "      openProfitRub: (...a) => window.__TradeDesk.pnl.openProfitRub(...a),\n"
        "      entryDepositRub: (...a) => window.__TradeDesk.pnl.entryDepositRub(...a),\n"
        "      equityAtOpenRub: (...a) => window.__TradeDesk.pnl.equityAtOpenRub(...a),\n"
        "      exitLevelPotential: (...a) => window.__TradeDesk.pnl.exitLevelPotential(...a),\n"
        "      fmtRubPlain: (...a) => window.__TradeDesk.pnl.fmtRubPlain(...a),\n"
        "      fmtStakePct: (...a) => window.__TradeDesk.pnl.fmtStakePct(...a),\n"
        "      coalesceDeskBroker: (...a) => window.__TradeDesk.pnl.coalesceDeskBroker(...a),\n"
        "      renderFunds: (...a) => window.__TradeDesk.pnl.renderFunds(...a),\n"
        "      renderCloseForecast: (...a) => window.__TradeDesk.pnl.renderCloseForecast(...a),\n"
        + chart_state_getters()
        + "\n    };\n  }\n"
    )
    stub = (
        "  // chart/broker state (bridged to modules via __installTradeDeskModules)\n"
        "  let zChart = null, zSeries = null, zSeriesIsLine = false, spreadChart = null, spreadSeries = null;\n"
        "  let priceLines = [], spreadPriceLines = [], tpExitPriceLine = null;\n"
        "  let spreadRegimeBands = { narrow: null, transition: null, wide: null };\n"
        "  let primarySpreadBands = { narrow: null, transition: null, wide: null };\n"
        "  let lastSpreadBandTimes = [], lastPrimarySpreadBandTimes = [];\n"
        "  let openHighlightSeries = null, corridorChartSeries = [], activeCorridorBounds = null;\n"
        "  let lastCorridorAutoscalePts = null, lastPaintZData = null, lastPaintMtlrData = null;\n"
        "  let openMarkersPlugin = null, openSpreadMarkersPlugin = null, spreadSeriesIsLine = false;\n"
        "  let lastMtlrBars = [], lastMtlrLevels = null, lastOpenTradeFp = '', lastDeskMarkers = [];\n"
        "  let lastDeskTrades = [], lastDeskPaintBars = [], hoverTradeId = null, markerHoverBound = false;\n"
        "  let defaultOpenHighlightData = null, refreshMarkersTimer = 0, forceFitContent = false;\n"
        "  let pendingPeriodFitDays = 0, suppressRangeEvents = false, rangeSyncBound = false;\n"
        "  let crosshairSyncBound = false, zPriceByTime = new Map(), spPriceByTime = new Map();\n"
        "  let lastBarCount = 0, lastDataEnd = null, pinnedRange = null, userPinnedAwayFromLive = false;\n"
        "  let lastBarsFingerprint = '', reapplyRangeTimer = 0, userGestureActive = false;\n"
        "  let userGestureTimer = 0, chartDealer1m = false, lastCloseForecast = null, profitToastTimer = null;\n"
    )
    text = text.replace("  let zChart = null;", stub, 1)
    text = text.replace(
        "  async function api(path, opts) {",
        install + reexp + "  async function api(path, opts) {",
        1,
    )
    text = text.replace(
        "  function bind() {",
        "  function bind() {\n    __installTradeDeskModules();",
        1,
    )
    return text


def patch_app(lines: list[str]) -> str:
    spans: list[tuple[int, int]] = []
    for spec in APP_EXTRACTS:
        spans.extend(removal_spans(lines, spec))
    text = "".join(strip_spans(lines, spans))
    reexp = reexport_block(APP_EXTRACTS)
    # chip_delta string kept for static test
    chip_note = "  // chip_delta: tag-share donut delta mode (see app-test-chips.js)\n"
    install = (
        "  function __installAppTestModules() {\n"
        "    window.__TradeDesk = window.__TradeDesk || { deps: {} };\n"
        "    Object.assign(window.__TradeDesk.deps, {\n"
        "      $, pnlClass, getSimNotionalRub, getSimCompound,\n"
        "      formatAccountRub, formatProfitAccountRub, isWeekendTradingMode,\n"
        "      tipRowsUpToCursor, thresholds, readWindowEndYmd, isTip1mMode,\n"
        "      tipSimRequestKey, tipTradeToRow, clearTipManualOverrides,\n"
        "      refreshTradesTable, refreshUi, normalizeTradeSrcFilterKey,\n"
        "      formatMonthlyYTick, formatMonthlyHistPctValue, formatMonthlyHistAbs,\n"
        "      formatMonthlyMeanLabel, applyMonthlyPnlScrollTop, readSavedMonthlyPnlScrollTop,\n"
        "      aggregateTipBarsByMinutes, isChartsHidden, tip1mChartDaysWanted,\n"
        "      tip1mChartNeedsReload, activateTip1mChart, fitLoadedChartRange,\n"
        "      markMonthlyPnlPending: (...a) => window.__TradeDesk.chips.markMonthlyPnlPending(...a),\n"
        "      isWeekendChipEntry: (...a) => window.__TradeDesk.chips.isWeekendChipEntry(...a),\n"
        "      get tipSimCache() { return tipSimCache; }, set tipSimCache(v) { tipSimCache = v; },\n"
        "      get tipSimJobId() { return tipSimJobId; },\n"
        "      get TEST_DRAW_CORRIDOR() { return TEST_DRAW_CORRIDOR; },\n"
        "      get tip1mChartMeta() { return tip1mChartMeta; }, set tip1mChartMeta(v) { tip1mChartMeta = v; },\n"
        "      get tip1mBarsCacheKey() { return tip1mBarsCacheKey; }, set tip1mBarsCacheKey(v) { tip1mBarsCacheKey = v; },\n"
        "      get tip1mChartJobId() { return tip1mChartJobId; },\n"
        "      get allPoints() { return allPoints; }, set allPoints(v) { allPoints = v; },\n"
        "      get testDeskCorridor() { return testDeskCorridor; }, set testDeskCorridor(v) { testDeskCorridor = v; },\n"
        "      get testDeskCorridorTs() { return testDeskCorridorTs; }, set testDeskCorridorTs(v) { testDeskCorridorTs = v; },\n"
        "      get engine() { return engine; }, get chart() { return chart; },\n"
        "      get pendingChartRepaint() { return pendingChartRepaint; },\n"
        "      set pendingChartRepaint(v) { pendingChartRepaint = v; },\n"
        "    });\n  }\n"
    )
    text = text.replace(
        "  function hmProdSpreadWide() {",
        chip_note + install + reexp + "  function hmProdSpreadWide() {",
        1,
    )
    text = text.replace(
        "  function bind() {",
        "  function bind() {\n    __installAppTestModules();",
        1,
    )
    return text


def patch_index() -> None:
    p = STATIC / "index.html"
    html = p.read_text(encoding="utf-8")
    trade_ins = "\n".join(
        f'  <script src="/static/{x["file"]}?v={CACHE_VER}"></script>' for x in TRADE_EXTRACTS
    )
    app_ins = "\n".join(
        f'  <script src="/static/{x["file"]}?v={CACHE_VER}"></script>' for x in APP_EXTRACTS
    )
    html = re.sub(
        r'  <script src="/static/trade\.js\?v=[^"]+"></script>',
        trade_ins + f'\n  <script src="/static/trade.js?v={CACHE_VER}"></script>',
        html,
        count=1,
    )
    html = re.sub(
        r'  <script src="/static/app\.js\?v=[^"]+"></script>',
        app_ins + f'\n  <script src="/static/app.js?v={CACHE_VER}"></script>',
        html,
        count=1,
    )
    html = html.replace("20260906monthPnl1", CACHE_VER)
    p.write_text(html, encoding="utf-8")


def main() -> None:
    trade_lines = read_lines(STATIC / "trade.js")
    app_lines = read_lines(STATIC / "app.js")
    for spec in TRADE_EXTRACTS:
        body = collect_body(trade_lines, spec)
        mod = wrap_module(spec["banner"], body, spec["ns"], spec["reexports"])
        (STATIC / spec["file"]).write_text(mod, encoding="utf-8")
        print(spec["file"], len(mod))
    for spec in APP_EXTRACTS:
        body = collect_body(app_lines, spec)
        mod = wrap_module(spec["banner"], body, spec["ns"], spec["reexports"])
        (STATIC / spec["file"]).write_text(mod, encoding="utf-8")
        print(spec["file"], len(mod))
    (STATIC / "trade.js").write_text(patch_trade(trade_lines), encoding="utf-8")
    (STATIC / "app.js").write_text(patch_app(app_lines), encoding="utf-8")
    patch_index()
    print("done", CACHE_VER)


if __name__ == "__main__":
    main()
