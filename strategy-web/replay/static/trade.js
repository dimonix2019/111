/** Торговля — chart + open trade panel (desk) */
(function () {
  const LS_CHART_RANGE = 'moexReplay.tradeChartRange';
  const LS_SPREAD_PANE_HEIGHT = 'moexReplay.tradeSpreadPaneHeight';
  const LS_TRADE_PARAMS = 'moexReplay.tradeParams';
  /** Мечел m15: график и настройки скрыты из UI Prod. */
  const DESK_MTLR_UI_ENABLED = false;
  const LS_SIDE_SCROLL = 'moexReplay.tradeSideScrollTop';
  const LS_CHECK_SCROLL = 'moexReplay.tradeCheckScrollTop';
  const LS_DESK_SCROLL = 'moexReplay.tradeDeskScrollTop';
  const LS_OPEN_STATS_HIDDEN = 'moexReplay.tradeOpenStatsHidden';
  const LS_MID_TAB = 'moexReplay.tradeMidTab';
  const LS_MID_PANEL_COLLAPSED = 'moexReplay.tradeMidPanelCollapsed';
  const LS_SIDE_TAB = 'moexReplay.tradeSideTab';
  /** Once-per-open-trade toast when MTM ≥ 3% of entry deposit. */
  const LS_PROFIT_ALERT_TRADE = 'moexReplay.profitAlertTradeId';
  const PROFIT_ALERT_PCT = 3;
  const MSK = 'Europe/Moscow';
  /** to ближе к концу данных, чем N баров → считаем «у live» */
  const LIVE_EDGE_BARS = 5;
  /** Макс. пустоты справа от последнего бара (логический overscroll). */
  const MAX_RIGHT_OVERSCROLL_BARS = 48;
  /** Минимум реальных баров в окне — иначе pin считаем битым (линия «схлопнута» влево). */
  const MIN_VISIBLE_DATA_BARS = 24;
  const M15_MS = 15 * 60 * 1000;
  const M1_MS = 60 * 1000;
  /** Legacy M15 close settle (checklist / M15 path only). */
  const BAR_SETTLE_MS = 90 * 1000;
  /** tip1m AUTO: after 1m close — short settle (matches MONITOR_TIP1M_SETTLE_SEC). */
  const TIP1M_SETTLE_MS = 10 * 1000;
  /** Match zsim / tip1m rolling window for display-only dealer Z */
  const Z_ROLL_LOOKBACK_DAYS = 30;
  const Z_ROLL_MIN_BARS = 48;
  /** Z ближе порога на столько → «подготовка» (не шум далеко от края) */
  const PHASE_NEAR_Z = 0.30;
  /** S% ближе уровня входа/выхода → «подготовка» (режим спред-уровней) */
  const PHASE_NEAR_S = 0.30;

  /** Offline fallback — must match live/constants.py + spread_regime.py */
  const SPREAD_CFG_FALLBACK = Object.freeze({
    enter_wide: 6.1,
    exit_wide: 5.8,
    enter_narrow: 3.2,
    exit_narrow: 4.0,
    regime_narrow_max: 3.5,
    regime_wide_min: 5.5,
  });

  function spreadCfgSpread() {
    const sp = window.__strategyConfig?.spread;
    const fb = SPREAD_CFG_FALLBACK;
    return {
      enter_wide: Number(sp?.enter_wide ?? fb.enter_wide),
      exit_wide: Number(sp?.exit_wide ?? fb.exit_wide),
      enter_narrow: Number(sp?.enter_narrow ?? fb.enter_narrow),
      exit_narrow: Number(sp?.exit_narrow ?? fb.exit_narrow),
      regime_narrow_max: Number(sp?.regime_narrow_max ?? fb.regime_narrow_max),
      regime_wide_min: Number(sp?.regime_wide_min ?? fb.regime_wide_min),
    };
  }

  function spreadCfgLevels(lv) {
    const cfg = spreadCfgSpread();
    return {
      enter_wide: Number(lv?.enter_wide ?? cfg.enter_wide),
      exit_wide: Number(lv?.exit_wide ?? cfg.exit_wide),
      enter_narrow: Number(lv?.enter_narrow ?? cfg.enter_narrow),
      exit_narrow: Number(lv?.exit_narrow ?? cfg.exit_narrow),
    };
  }

  let strategyConfigPromise = null;
  async function fetchStrategyConfig() {
    if (window.__strategyConfig?.version && window.__strategyConfig.version !== 'fallback') {
      return window.__strategyConfig;
    }
    if (strategyConfigPromise) return strategyConfigPromise;
    strategyConfigPromise = (async () => {
      try {
        const r = await fetch('/api/live/strategy-config', { cache: 'no-store' });
        if (!r.ok) throw new Error(String(r.status));
        window.__strategyConfig = await r.json();
      } catch (_) {
        window.__strategyConfig = {
          spread: { ...SPREAD_CFG_FALLBACK },
          tp_pct: 2.0,
          version: 'fallback',
        };
      }
      return window.__strategyConfig;
    })();
    return strategyConfigPromise;
  }
  window.__fetchStrategyConfig = fetchStrategyConfig;
  window.__spreadCfgSpread = spreadCfgSpread;
  window.__spreadCfgFallback = SPREAD_CFG_FALLBACK;

  const TRADE_SPREAD_DEFAULT = 150;
  const TRADE_SPREAD_MIN = 48;
  const TRADE_Z_MIN = 120;
  const CHART_SPLITTER_HEIGHT = 6;

  /** Панорама/зум как в TradingView / Replay chart.js */
  const CHART_SCROLL_SCALE = {
    handleScroll: {
      mouseWheel: true,
      pressedMouseMove: true,
      horzTouchDrag: true,
      vertTouchDrag: true,
    },
    handleScale: {
      axisPressedMouseMove: true,
      axisDoubleClickReset: true,
      mouseWheel: true,
      pinch: true,
    },
  };

  /** Hit-test маркеров — как в chart.js (Testing) */
  const MARKER_HIT_RADIUS_PX = 48;
  const MARKER_HIT_RADIUS_X_PX = 44;
  const MARKER_ENTRY_HIT_RADIUS_X_PX = 56;
  const TRADE_HIGHLIGHT_COLOR = '#FACC15';
  /** Плашка + пунктир текущей цены (не цвет «L вых» #26a69a). */
  const CURRENT_PRICE_LINE_COLOR = '#FACC15';
  /** Цель выхода по ТП (не путать с L/S вых и жёлтой текущей ценой). */
  const TP_EXIT_LINE_COLOR = '#FB923C';

  let days = 7;
  let pollTimer = null;
  let pollMs = 12000;
  let refreshWorkCount = 0;
  const POLL_MS_DEFAULT = 12000;
  const POLL_MS_DEALER_1M = 5000;
  /** Full desk (broker) every N lite polls — avoid wedging uvicorn on weekend TInvest. */
  const POLL_FULL_EVERY = 6;
  let pollTick = 0;
  /** Inputs filled from server once; polls must not clobber while typing */
  let formHydrated = false;
  /** User edited params since last successful hydrate/save */
  let formDirty = false;
  let saveStatusTimer = 0;
  /** Ignore late desk responses that would re-hydrate over newer edits */
  let deskFetchSeq = 0;
  /** Last good dealer/ISS chart bars — keep on timeout / empty weekend partial. */
  let lastGoodChartBars = [];
  let lastGoodDeskMeta = null;
  let lastPartialBanner = '';
  /**
   * Last good broker portfolio — lite desk often omits broker (cold / TTL);
   * keep totals so «СРЕДСТВА НА СЧЁТЕ» does not flash «…» / «брокер…».
   */
  let lastGoodBroker = null;
  let brokerEmptyStreak = 0;
  /** > POLL_FULL_EVERY so one full cycle can refill before we drop last good. */
  const BROKER_EMPTY_CLEAR_AFTER = 8;
  /** Линии коридора S на верхнем графике (forming / formed). */

    const zLab = $('tradeZChartLabel');
    const spLab = $('tradeSpreadChartLabel');
    const thLab = $('tradeThreshLabel');
    const mtlrThLab = $('tradeMtlrThreshLabel');
    const lb = (lookbackDays != null && Number(lookbackDays) > 0)
      ? ` · до ${Number(lookbackDays)}д`
      : '';
    if (zLab) {
      if (dealer1m && zEmpty) {
        zLab.textContent = 'Татнефть · спред % · нет баров дилер 1м';
      } else if (dealer1m) {
        zLab.textContent = `Татнефть · спред % · дилер 1м (монитор · не AUTO)${lb}`;
      } else {
        zLab.textContent = 'Татнефть · спред % · tip1m';
      }
      zLab.classList.toggle('pnl-label-dealer', !!dealer1m);
    }
    if (spLab) {
      spLab.textContent = mtlrEmpty
        ? 'Мечел · спред % · нет баров m15'
        : 'Мечел · спред % · m15';
      spLab.classList.remove('pnl-label-dealer');
    }
    if (thLab) {
      thLab.classList.toggle('pnl-label-dealer', !!dealer1m);
      const lv = spreadLevels || {};
      const en = Number(lv.enter_narrow);
      const xn = Number(lv.exit_narrow);
      const xw = Number(lv.exit_wide);
      const ew = Number(lv.enter_wide);
      // «дилер · не AUTO» уже в заголовке графика — здесь только числа уровней.
      if ([en, xn, xw, ew].every((n) => Number.isFinite(n))) {
        thLab.textContent = `уровни L ${fmt(en, 1)}/${fmt(xn, 1)} · S ${fmt(xw, 1)}/${fmt(ew, 1)}`;
      } else {
        const cfg = spreadCfgSpread();
        thLab.textContent = `уровни L ${fmt(cfg.enter_narrow, 1)}/${fmt(cfg.exit_narrow, 1)} · S ${fmt(cfg.exit_wide, 1)}/${fmt(cfg.enter_wide, 1)}`;
      }
    }
    if (mtlrThLab) {
      const lv = mtlrLevels || lastMtlrLevels || {};
      const en = Number(lv.enter_narrow);
      const xn = Number(lv.exit_narrow);
      const xw = Number(lv.exit_wide);
      const ew = Number(lv.enter_wide);
      if ([en, xn, xw, ew].every((n) => Number.isFinite(n))) {
        mtlrThLab.textContent = `уровни L ${fmt(en, 1)}/${fmt(xn, 1)} · S ${fmt(xw, 1)}/${fmt(ew, 1)}`;
      } else {
        mtlrThLab.textContent = 'уровни L 3.2/4.3 · S 8.4/8.9';
      }
    }
  }



  /**
   * У live-края только если правый край окна ≈ конец данных.
   * Старое `to >= dataEnd - N` ошибочно ловило pan с пустотой справа (to >> dataEnd)
   * и на poll схлопывало to=dataEnd — отсюда прыжок.
   */

  /** Сколько реальных баров попало в logical range (пустота справа/слева не считается). */

  /**
   * Битый pin: данные сжаты влево (2 бара на огромном окне) или from за концом ряда.
   * Иначе строгий userPinnedAwayFromLive навсегда держит пустой график.
   */



    if (!range || !Number.isFinite(range.from) || !Number.isFinite(range.to)) return;
    pinnedRange = { from: range.from, to: range.to };
    if (fromUser && lastDataEnd != null) {
      if (isNearLiveEdge(pinnedRange, lastDataEnd)) {
        userPinnedAwayFromLive = false;
      } else {
        userPinnedAwayFromLive = true;
      }
    }
    persistViewport();
  }



  /** Верх TATN (logical pin) + низ MTLR — один календарный интервал по времени. */

  /** TATN tip1m ↔ MTLR m15: sync by calendar time, not logical bar index. */




  /** После paint ещё раз навязать общий time-range (setData/fit асинхронно съезжают). */

  /** Повторно навязать pin после асинхронного сброса timescale от setData/resize */





  /**
   * После обновления данных:
   * - userPinnedAwayFromLive → ВСЕГДА точный restore, never follow
   * - иначе у live-края → сдвинуть окно к новому концу
   * - иначе → точный restore
   * @param {number} zCount
   * @param {number} [spreadCount]
   */

  function __installTradeDeskModules() {
    window.__TradeDesk = window.__TradeDesk || { deps: {} };
    window.__TradeDesk.deps = {
      $, fmt, fmtRub, escapeHtml, toChartTime, api,
      needPctSuffix, openEntryProgressText, fmtTickLabel, formatChartTick, chartTimeOpts,
      determineSpreadLevelSignalJs, effectiveThresholds, nowMskParts, lastSpreadLiveBar,
      barZ, determineZSignalJs, buildTradePhase, renderChecklist, renderOpenStats,
      syncTradeActionButtons, renderTradeRulesStatus, renderCorridorMeter, corridorStatusBadge,
      brokerHasTotals, modeLabel, readEntryDeposit, computeOpenPathMinMax, fmtOpenMinMax,
      fmtPnlWithDepositPct, fmtRubShort, renderCheckList, checkItem,
      renderDesk, ensureWeekdayTip1mBars, ensureMtlrChartBars, tip1mSpanDays,
      loadCachedParamsLocal, thinDeskTip1mBars,
      get pollTimer() { return pollTimer; }, set pollTimer(v) { pollTimer = v; },
      get pollMs() { return pollMs; }, set pollMs(v) { pollMs = v; },
      get pollTick() { return pollTick; }, set pollTick(v) { pollTick = v; },
      get refreshWorkCount() { return refreshWorkCount; },
      set refreshWorkCount(v) { refreshWorkCount = v; },
      get deskFetchSeq() { return deskFetchSeq; }, set deskFetchSeq(v) { deskFetchSeq = v; },
      get days() { return days; },
      get lastGoodChartBars() { return lastGoodChartBars; },
      set lastGoodChartBars(v) { lastGoodChartBars = v; },
      get lastGoodDeskMeta() { return lastGoodDeskMeta; },
      set lastGoodDeskMeta(v) { lastGoodDeskMeta = v; },
      get DESK_MTLR_UI_ENABLED() { return DESK_MTLR_UI_ENABLED; },
      get POLL_FULL_EVERY() { return POLL_FULL_EVERY; },
      get POLL_MS_DEFAULT() { return POLL_MS_DEFAULT; },
      get POLL_MS_DEALER_1M() { return POLL_MS_DEALER_1M; },
      get PROFIT_ALERT_PCT() { return PROFIT_ALERT_PCT; },
      get LS_PROFIT_ALERT_TRADE() { return LS_PROFIT_ALERT_TRADE; },
      get BROKER_EMPTY_CLEAR_AFTER() { return BROKER_EMPTY_CLEAR_AFTER; },
      get SPREAD_REGIME_BAND_COLORS() { return SPREAD_REGIME_BAND_COLORS; },
      get CHART_SCROLL_SCALE() { return CHART_SCROLL_SCALE; },
      get CURRENT_PRICE_LINE_COLOR() { return CURRENT_PRICE_LINE_COLOR; },
      get TP_EXIT_LINE_COLOR() { return TP_EXIT_LINE_COLOR; },
      get TRADE_SPREAD_DEFAULT() { return TRADE_SPREAD_DEFAULT; },
      get TRADE_SPREAD_MIN() { return TRADE_SPREAD_MIN; },
      get TRADE_Z_MIN() { return TRADE_Z_MIN; },
      get CHART_SPLITTER_HEIGHT() { return CHART_SPLITTER_HEIGHT; },
      get LS_SPREAD_PANE_HEIGHT() { return LS_SPREAD_PANE_HEIGHT; },
      get LS_CHART_RANGE() { return LS_CHART_RANGE; },
      get LIVE_EDGE_BARS() { return LIVE_EDGE_BARS; },
      get MAX_RIGHT_OVERSCROLL_BARS() { return MAX_RIGHT_OVERSCROLL_BARS; },
      get MIN_VISIBLE_DATA_BARS() { return MIN_VISIBLE_DATA_BARS; },
      get M15_MS() { return M15_MS; },
      get M1_MS() { return M1_MS; },
      get MARKER_HIT_RADIUS_PX() { return MARKER_HIT_RADIUS_PX; },
      get MARKER_HIT_RADIUS_X_PX() { return MARKER_HIT_RADIUS_X_PX; },
      get MARKER_ENTRY_HIT_RADIUS_X_PX() { return MARKER_ENTRY_HIT_RADIUS_X_PX; },
      get TRADE_HIGHLIGHT_COLOR() { return TRADE_HIGHLIGHT_COLOR; },
      get Z_ROLL_LOOKBACK_DAYS() { return Z_ROLL_LOOKBACK_DAYS; },
      get Z_ROLL_MIN_BARS() { return Z_ROLL_MIN_BARS; },
      updateCorridorOnChart: (...a) => window.__TradeDesk.corridor.updateCorridorOnChart(...a),
      paintCorridorOnChart: (...a) => window.__TradeDesk.corridor.paintCorridorOnChart(...a),
      corridorFingerprint: (...a) => window.__TradeDesk.corridor.corridorFingerprint(...a),
      renderMarginHeadroom: (...a) => window.__TradeDesk.pnl.renderMarginHeadroom(...a),
      openProfitRub: (...a) => window.__TradeDesk.pnl.openProfitRub(...a),
      entryDepositRub: (...a) => window.__TradeDesk.pnl.entryDepositRub(...a),
      equityAtOpenRub: (...a) => window.__TradeDesk.pnl.equityAtOpenRub(...a),
      exitLevelPotential: (...a) => window.__TradeDesk.pnl.exitLevelPotential(...a),
      fmtRubPlain: (...a) => window.__TradeDesk.pnl.fmtRubPlain(...a),
      fmtStakePct: (...a) => window.__TradeDesk.pnl.fmtStakePct(...a),
      coalesceDeskBroker: (...a) => window.__TradeDesk.pnl.coalesceDeskBroker(...a),
      renderFunds: (...a) => window.__TradeDesk.pnl.renderFunds(...a),
      renderCloseForecast: (...a) => window.__TradeDesk.pnl.renderCloseForecast(...a),
      get zChart() { return zChart; }, set zChart(v) { zChart = v; },
      get zSeries() { return zSeries; }, set zSeries(v) { zSeries = v; },
      get zSeriesIsLine() { return zSeriesIsLine; }, set zSeriesIsLine(v) { zSeriesIsLine = v; },
      get spreadChart() { return spreadChart; }, set spreadChart(v) { spreadChart = v; },
      get spreadSeries() { return spreadSeries; }, set spreadSeries(v) { spreadSeries = v; },
      get priceLines() { return priceLines; }, set priceLines(v) { priceLines = v; },
      get spreadPriceLines() { return spreadPriceLines; }, set spreadPriceLines(v) { spreadPriceLines = v; },
      get tpExitPriceLine() { return tpExitPriceLine; }, set tpExitPriceLine(v) { tpExitPriceLine = v; },
      get spreadRegimeBands() { return spreadRegimeBands; }, set spreadRegimeBands(v) { spreadRegimeBands = v; },
      get primarySpreadBands() { return primarySpreadBands; }, set primarySpreadBands(v) { primarySpreadBands = v; },
      get lastSpreadBandTimes() { return lastSpreadBandTimes; }, set lastSpreadBandTimes(v) { lastSpreadBandTimes = v; },
      get lastPrimarySpreadBandTimes() { return lastPrimarySpreadBandTimes; }, set lastPrimarySpreadBandTimes(v) { lastPrimarySpreadBandTimes = v; },
      get openHighlightSeries() { return openHighlightSeries; }, set openHighlightSeries(v) { openHighlightSeries = v; },
      get corridorChartSeries() { return corridorChartSeries; }, set corridorChartSeries(v) { corridorChartSeries = v; },
      get activeCorridorBounds() { return activeCorridorBounds; }, set activeCorridorBounds(v) { activeCorridorBounds = v; },
      get lastCorridorAutoscalePts() { return lastCorridorAutoscalePts; }, set lastCorridorAutoscalePts(v) { lastCorridorAutoscalePts = v; },
      get lastPaintZData() { return lastPaintZData; }, set lastPaintZData(v) { lastPaintZData = v; },
      get lastPaintMtlrData() { return lastPaintMtlrData; }, set lastPaintMtlrData(v) { lastPaintMtlrData = v; },
      get openMarkersPlugin() { return openMarkersPlugin; }, set openMarkersPlugin(v) { openMarkersPlugin = v; },
      get openSpreadMarkersPlugin() { return openSpreadMarkersPlugin; }, set openSpreadMarkersPlugin(v) { openSpreadMarkersPlugin = v; },
      get spreadSeriesIsLine() { return spreadSeriesIsLine; }, set spreadSeriesIsLine(v) { spreadSeriesIsLine = v; },
      get lastMtlrBars() { return lastMtlrBars; }, set lastMtlrBars(v) { lastMtlrBars = v; },
      get lastMtlrLevels() { return lastMtlrLevels; }, set lastMtlrLevels(v) { lastMtlrLevels = v; },
      get lastOpenTradeFp() { return lastOpenTradeFp; }, set lastOpenTradeFp(v) { lastOpenTradeFp = v; },
      get lastDeskMarkers() { return lastDeskMarkers; }, set lastDeskMarkers(v) { lastDeskMarkers = v; },
      get lastDeskTrades() { return lastDeskTrades; }, set lastDeskTrades(v) { lastDeskTrades = v; },
      get lastDeskPaintBars() { return lastDeskPaintBars; }, set lastDeskPaintBars(v) { lastDeskPaintBars = v; },
      get hoverTradeId() { return hoverTradeId; }, set hoverTradeId(v) { hoverTradeId = v; },
      get markerHoverBound() { return markerHoverBound; }, set markerHoverBound(v) { markerHoverBound = v; },
      get defaultOpenHighlightData() { return defaultOpenHighlightData; }, set defaultOpenHighlightData(v) { defaultOpenHighlightData = v; },
      get refreshMarkersTimer() { return refreshMarkersTimer; }, set refreshMarkersTimer(v) { refreshMarkersTimer = v; },
      get forceFitContent() { return forceFitContent; }, set forceFitContent(v) { forceFitContent = v; },
      get pendingPeriodFitDays() { return pendingPeriodFitDays; }, set pendingPeriodFitDays(v) { pendingPeriodFitDays = v; },
      get suppressRangeEvents() { return suppressRangeEvents; }, set suppressRangeEvents(v) { suppressRangeEvents = v; },
      get rangeSyncBound() { return rangeSyncBound; }, set rangeSyncBound(v) { rangeSyncBound = v; },
      get crosshairSyncBound() { return crosshairSyncBound; }, set crosshairSyncBound(v) { crosshairSyncBound = v; },
      get zPriceByTime() { return zPriceByTime; }, set zPriceByTime(v) { zPriceByTime = v; },
      get spPriceByTime() { return spPriceByTime; }, set spPriceByTime(v) { spPriceByTime = v; },
      get lastBarCount() { return lastBarCount; }, set lastBarCount(v) { lastBarCount = v; },
      get lastDataEnd() { return lastDataEnd; }, set lastDataEnd(v) { lastDataEnd = v; },
      get pinnedRange() { return pinnedRange; }, set pinnedRange(v) { pinnedRange = v; },
      get userPinnedAwayFromLive() { return userPinnedAwayFromLive; }, set userPinnedAwayFromLive(v) { userPinnedAwayFromLive = v; },
      get lastBarsFingerprint() { return lastBarsFingerprint; }, set lastBarsFingerprint(v) { lastBarsFingerprint = v; },
      get reapplyRangeTimer() { return reapplyRangeTimer; }, set reapplyRangeTimer(v) { reapplyRangeTimer = v; },
      get userGestureActive() { return userGestureActive; }, set userGestureActive(v) { userGestureActive = v; },
      get userGestureTimer() { return userGestureTimer; }, set userGestureTimer(v) { userGestureTimer = v; },
      get chartDealer1m() { return chartDealer1m; }, set chartDealer1m(v) { chartDealer1m = v; },
      get lastCloseForecast() { return lastCloseForecast; }, set lastCloseForecast(v) { lastCloseForecast = v; },
      get profitToastTimer() { return profitToastTimer; }, set profitToastTimer(v) { profitToastTimer = v; },
      get brokerEmptyStreak() { return brokerEmptyStreak; }, set brokerEmptyStreak(v) { brokerEmptyStreak = v; },
      get lastGoodBroker() { return lastGoodBroker; }, set lastGoodBroker(v) { lastGoodBroker = v; },
    };
  }
  // --- module re-exports (static tests grep trade.js / app.js) ---
  function corridorPhaseAbsent(...args) { return window.__TradeDesk.corridor.corridorPhaseAbsent(...args); }
  function detectSpreadCorridorClient(...args) { return window.__TradeDesk.corridor.detectSpreadCorridorClient(...args); }
  function renderCorridorMeter(...args) { return window.__TradeDesk.corridor.renderCorridorMeter(...args); }
  function updateCorridorOnChart(...args) { return window.__TradeDesk.corridor.updateCorridorOnChart(...args); }
  function paintCorridorOnChart(...args) { return window.__TradeDesk.corridor.paintCorridorOnChart(...args); }
  function clearCorridorChartSeries(...args) { return window.__TradeDesk.corridor.clearCorridorChartSeries(...args); }
  function corridorFingerprint(...args) { return window.__TradeDesk.corridor.corridorFingerprint(...args); }
  function corridorStatusBadge(...args) { return window.__TradeDesk.corridor.corridorStatusBadge(...args); }
  function applyZSeriesCorridorAutoscale(...args) { return window.__TradeDesk.corridor.applyZSeriesCorridorAutoscale(...args); }
  function refreshZPriceScaleAfterCorridor(...args) { return window.__TradeDesk.corridor.refreshZPriceScaleAfterCorridor(...args); }
  function setZEmptyMessage(...args) { return window.__TradeDesk.chart.setZEmptyMessage(...args); }
  function updateChartPaneLabels(...args) { return window.__TradeDesk.chart.updateChartPaneLabels(...args); }
  function alignTip1mBarsToLiveTip(...args) { return window.__TradeDesk.chart.alignTip1mBarsToLiveTip(...args); }
  function setTpExitSpreadLine(...args) { return window.__TradeDesk.chart.setTpExitSpreadLine(...args); }
  function renderCharts(...args) { return window.__TradeDesk.chart.renderCharts(...args); }
  function ensureCharts(...args) { return window.__TradeDesk.chart.ensureCharts(...args); }
  function resize(...args) { return window.__TradeDesk.chart.resize(...args); }
  function renderOpen(...args) { return window.__TradeDesk.chart.renderOpen(...args); }
  function updateOpenTradeOnChart(...args) { return window.__TradeDesk.chart.updateOpenTradeOnChart(...args); }
  function syncBottomPaneToTopTime(...args) { return window.__TradeDesk.chart.syncBottomPaneToTopTime(...args); }
  function syncTopPaneToBottomTime(...args) { return window.__TradeDesk.chart.syncTopPaneToBottomTime(...args); }
  function thinDeskTip1mBars(...args) { return window.__TradeDesk.chart.thinDeskTip1mBars(...args); }
  function ensureWeekdayTip1mBars(...args) { return window.__TradeDesk.chart.ensureWeekdayTip1mBars(...args); }
  function bindTradeChartVerticalSplit(...args) { return window.__TradeDesk.chart.bindTradeChartVerticalSplit(...args); }
  function formatBrokerError(...args) { return window.__TradeDesk.pnl.formatBrokerError(...args); }
  function marginHeadroomFromBroker(...args) { return window.__TradeDesk.pnl.marginHeadroomFromBroker(...args); }
  function renderMarginHeadroom(...args) { return window.__TradeDesk.pnl.renderMarginHeadroom(...args); }
  function renderFunds(...args) { return window.__TradeDesk.pnl.renderFunds(...args); }
  function renderFundsAtOpen(...args) { return window.__TradeDesk.pnl.renderFundsAtOpen(...args); }
  function isBrokerPnlMark(...args) { return window.__TradeDesk.pnl.isBrokerPnlMark(...args); }
  function openProfitRub(...args) { return window.__TradeDesk.pnl.openProfitRub(...args); }
  function renderCloseForecast(...args) { return window.__TradeDesk.pnl.renderCloseForecast(...args); }
  function entryDepositRub(...args) { return window.__TradeDesk.pnl.entryDepositRub(...args); }
  function maybeFireProfitAlert(...args) { return window.__TradeDesk.pnl.maybeFireProfitAlert(...args); }
  function coalesceDeskBroker(...args) { return window.__TradeDesk.pnl.coalesceDeskBroker(...args); }
  function refresh(...args) { return window.__TradeDesk.polling.refresh(...args); }
  function refreshImpl(...args) { return window.__TradeDesk.polling.refreshImpl(...args); }
  function startPoll(...args) { return window.__TradeDesk.polling.startPoll(...args); }
  function stopPoll(...args) { return window.__TradeDesk.polling.stopPoll(...args); }
  function ensurePollInterval(...args) { return window.__TradeDesk.polling.ensurePollInterval(...args); }
  function rememberGoodChartBars(...args) { return window.__TradeDesk.polling.rememberGoodChartBars(...args); }
  function applyPartialBanner(...args) { return window.__TradeDesk.polling.applyPartialBanner(...args); }
  async function api(path, opts) {
    const timeoutMs = (opts && opts.timeoutMs != null) ? Number(opts.timeoutMs) : 0;
    const { timeoutMs: _tm, ...fetchOpts } = opts || {};
    const ctrl = (timeoutMs > 0 && typeof AbortController !== 'undefined')
      ? new AbortController()
      : null;
    let timer = null;
    if (ctrl) timer = setTimeout(() => ctrl.abort(), timeoutMs);
    try {
      const res = await fetch(path, {
        headers: { 'Content-Type': 'application/json', ...(fetchOpts && fetchOpts.headers) },
        ...fetchOpts,
        signal: ctrl ? ctrl.signal : (fetchOpts && fetchOpts.signal),
      });
      const text = await res.text();
      let data;
      try { data = text ? JSON.parse(text) : {}; } catch { data = { detail: text }; }
      if (!res.ok) {
        const msg = data.detail || data.message || text || res.statusText;
        throw new Error(typeof msg === 'string' ? msg : JSON.stringify(msg));
      }
      return data;
    } catch (e) {
      if (e && (e.name === 'AbortError' || /aborted/i.test(String(e.message || '')))) {
        throw new Error(`Таймаут ${timeoutMs}мс: ${path}`);
      }
      throw e;
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  function fmt(n, d = 2) {
    if (n == null || Number.isNaN(Number(n))) return '—';
    return Number(n).toLocaleString('ru-RU', {
      maximumFractionDigits: d,
      useGrouping: true,
    });
  }

  /** % до порога входа/выхода: need/th×100 (100% у дальнего края, 0% у порога). */
  function needPctSuffix(need, th) {
    if (need == null || !(th > 0)) return '';
    return ` (${Math.round((need / th) * 100)}%)`;
  }

  /** Текст «до Long/Short ещё … (N%)» для плашки чеклиста. */
  function openEntryProgressText(dir, needLong, needShort, entryTh) {
    if (dir === 'long' && needLong != null && Number.isFinite(needLong)) {
      return `до Long ещё −${fmt(needLong, 2)}${needPctSuffix(needLong, entryTh)}`;
    }
    if (dir === 'short' && needShort != null && Number.isFinite(needShort)) {
      return `до Short ещё +${fmt(needShort, 2)}${needPctSuffix(needShort, entryTh)}`;
    }
    return '';
  }

  function fmtRub(n) {
    if (n == null || Number.isNaN(Number(n))) return '—';
    const v = Number(n);
    const sign = v > 0 ? '+' : '';
    return sign + v.toLocaleString('ru-RU', { maximumFractionDigits: 0 }) + ' ₽';
  }

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  /** Format bar trade_date → «19.07 13:45» */
  function fmtTickLabel(tradeDate) {
    if (!tradeDate) return '—';
    const s = String(tradeDate).trim();
    const m = s.match(/^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2}))?/);
    if (m) {
      const dd = `${m[3]}.${m[2]}`;
      return m[4] != null ? `${dd} ${m[4]}:${m[5]}` : dd;
    }
    return s;
  }

  function onlineBadge(online) {
    return online
      ? '<span class="badge-online">online</span>'
      : '<span class="badge-offline">offline</span>';
  }

  function dealerBadge(dealer) {
    if (!dealer) return '';
    if (dealer.error && !dealer.ok) {
      return `<span class="badge-dealer-off" title="${escapeHtml(String(dealer.error))}">дилер ?</span>`;
    }
    if (dealer.manual_ok || dealer.quotes_ok) {
      return `<span class="badge-dealer-on" title="Котировки дилера · ручной Long/Short OK · не в Z/AUTO">${escapeHtml(dealer.label || 'дилер / выходные')}</span>`;
    }
    return `<span class="badge-dealer-off" title="Нет дилерских цен">дилер · нет цен</span>`;
  }

  /** Плашка дилера в статус-баре: только когда уместно (выходные / котировки / ошибка). */
  function dealerStatusHtml(dealer, weekendMonitor) {
    if (dealer && (dealer.error && !dealer.ok)) return dealerBadge(dealer);
    if (dealer && (dealer.manual_ok || dealer.quotes_ok)) return dealerBadge(dealer);
    if (weekendMonitor) return dealer ? dealerBadge(dealer) : '<span class="badge-dealer-off">дилер · нет цен</span>';
    return '';
  }

  /** Фаза коридора S — компактная плашка в верхний ряд индикаторов. */

  /** Плашка: спред заморожен после 23:45 МСК (не путать с live tip после сессии). */
  function spreadFrozenBadge(dealer, mskParts) {
    const msk = mskParts || nowMskParts();
    const fromDealer = dealer && dealer.spread_live === false;
    const frozen = fromDealer || (msk.spreadLive === false && !msk.weekend);
    if (!frozen) return '';
    const reason = (dealer && dealer.spread_frozen_reason)
      || 'сессия закрыта · спред не обновляем после 23:45';
    const cutoff = (dealer && dealer.spread_cutoff) || '23:45';
    return `<span class="badge-spread-frozen" title="${escapeHtml(String(reason))}">спред стоп · ${escapeHtml(String(cutoff))}</span>`;
  }

  function monBadge(running) {
    return running
      ? '<span class="badge-mon-on"><span class="badge-quiet">монитор</span> ON</span>'
      : '<span class="badge-mon-off"><span class="badge-quiet">монитор</span> OFF</span>';
  }

  /** Отставание — всегда минуты, шаг 30 с (без прыжков «87 с» ↔ «2 мин»). */
  function formatTipLagSec(sec) {
    const n = Number(sec);
    if (!Number.isFinite(n) || n < 0) return null;
    const q = Math.round(n / 30) * 30;
    const mins = q / 60;
    if (mins < 0.5) return '<0.5 мин';
    if (mins < 10) return `${mins.toFixed(1).replace(/\.0$/, '')} мин`;
    return `${Math.round(mins)} мин`;
  }

  function tipFeedLabel(feed) {
    const f = String(feed || '').toLowerCase();
    if (f === 'tinvest') return 'Т‑Инвест';
    if (f === 'iss') return 'биржа ISS';
    if (f === 'parquet') return 'кэш';
    if (f === 'mixed') return 'смесь';
    return '';
  }

  /** Достать epoch ms бара из «2026-08-05 07:02» / сообщения монитора. */
  function parseMskBarMs(raw) {
    if (raw == null) return null;
    const s = String(raw).replace('T', ' ').trim();
    const m = s.match(/(\d{4})-(\d{2})-(\d{2})[ ](\d{2}):(\d{2})(?::(\d{2}))?/);
    if (!m) return null;
    const barMs = Date.parse(
      `${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}:${m[6] || '00'}+03:00`,
    );
    return Number.isFinite(barMs) ? barMs : null;
  }

  /** Сглаживание: не дёргать плашку на каждом опросе стола. */
  let tipLagSmooth = { sec: null, shownAt: 0, barMs: null };

  /**
   * Badge: отставание tip. Green <3м, warn 3–10м, bad ≥10м.
   * Бар: tip_lag_sec → last_message монитора → last_bar → trade_date.
   */
  function tipLagBadge(mon, tradeDate) {
    const m = (mon && typeof mon === 'object') ? mon : {};
    let lag = Number(m.tip_lag_sec);
    let barMs = null;
    if (!Number.isFinite(lag)) {
      // Сначала монитор (AUTO), не trade_date стола — иначе лаг растёт и скачком падает.
      barMs = parseMskBarMs(m.last_message)
        || parseMskBarMs(m.last_bar)
        || parseMskBarMs(tradeDate);
      if (barMs != null) lag = Math.max(0, (Date.now() - barMs) / 1000);
    }
    if (!Number.isFinite(lag)) return '';

    const now = Date.now();
    const prev = tipLagSmooth.sec;
    const sameBar = barMs != null && tipLagSmooth.barMs === barMs;
    if (
      prev != null
      && sameBar
      && (now - tipLagSmooth.shownAt) < 12000
      && Math.abs(lag - prev) < 90
    ) {
      lag = prev;
    } else {
      tipLagSmooth = { sec: lag, shownAt: now, barMs: barMs ?? tipLagSmooth.barMs };
    }

    const text = formatTipLagSec(lag);
    if (!text) return '';
    let cls = 'badge-tip-lag is-ok';
    if (lag >= 600) cls = 'badge-tip-lag is-bad';
    else if (lag >= 180) cls = 'badge-tip-lag is-warn';
    const feed = tipFeedLabel(m.tip_feed);
    const iss = formatTipLagSec(m.iss_lag_sec);
    const ti = formatTipLagSec(m.ti_lag_sec);
    const bits = [];
    if (feed) bits.push(`источник: ${feed}`);
    if (iss) bits.push(`ISS ${iss}`);
    if (ti) bits.push(`Т‑Инвест ${ti}`);
    bits.push('хвост tip vs часы · шаг 30 с');
    const title = bits.join(' · ');
    return `<span class="${cls}" title="${escapeHtml(title)}">отставание ${escapeHtml(text)}</span>`;
  }

  function autoBadge(on) {
    return on
      ? '<span class="badge-auto-on"><span class="badge-quiet">auto</span> ON</span>'
      : '<span class="badge-auto-off"><span class="badge-quiet">auto</span> OFF</span>';
  }

  function modeBadge(mode) {
    return mode === 'prod'
      ? '<span class="badge-mode-prod">Prod</span>'
      : '<span class="badge-mode-sandbox">Sandbox</span>';
  }

  /** Prod signal path badge — tip1m Mode B (Testing «касание 1м»). */
  function strategyBadge(signalMode) {
    const tip = String(signalMode || 'tip1m') === 'tip1m';
    if (tip) {
      return '<span class="badge-strat-tip1m" title="Mode B: tip1m · settle +10с · дилер не в AUTO">касание tip1m</span>';
    }
    return '<span class="badge-strat-m15" title="Legacy: вход/выход на закрытии M15">M15 close</span>';
  }

  function spreadLevelsBadge(settings, sl) {
    const on = settings && settings.spread_level_mode !== false
      && (settings.spread_level_mode === true || settings.spread_level_mode == null
        || String(settings.spread_level_mode) === '1');
    if (!on && !(sl && sl.spread_level_mode)) return '';
    const lv = (sl && sl.levels) || {};
    const cfg = spreadCfgSpread();
    const levels = spreadCfgLevels(lv);
    const label = (sl && sl.current_label_ru) || '—';
    // S% — на полосе рынка; здесь только зона (узкий / переход / широкий).
    const blocked = sl && sl.entry_blocked ? ' · вход запрещён' : '';
    return (
      `<span class="badge-spread-levels" title="Short ≥${fmt(levels.enter_wide, 1)} / ≤${fmt(levels.exit_wide, 1)} · Long ≤${fmt(levels.enter_narrow, 1)} / ≥${fmt(levels.exit_narrow, 1)} · переход без входа">`
      + `спред-уровни · ${escapeHtml(String(label))}${blocked}</span>`
    );
  }

  /** Z-режим UI снят со стола — бейдж больше не показываем (настройки на сервере остаются). */
  function regimeZBadge() {
    return '';
  }

  function determineSpreadLevelSignalJs(prevS, curS, pos, lv) {
    if (prevS == null || curS == null || !Number.isFinite(prevS) || !Number.isFinite(curS)) {
      return 'NONE';
    }
    const cfg = spreadCfgSpread();
    const levels = spreadCfgLevels(lv);
    const enterW = levels.enter_wide;
    const exitW = levels.exit_wide;
    const enterN = levels.enter_narrow;
    const exitN = levels.exit_narrow;
    const p = String(pos || 'FLAT').toUpperCase();
    if (p === 'FLAT') {
      if (prevS < enterW && curS >= enterW && curS > cfg.regime_wide_min) return 'ENTER_SHORT';
      if (prevS > enterN && curS <= enterN && curS < cfg.regime_narrow_max) return 'ENTER_LONG';
      return 'NONE';
    }
    if (p === 'LONG') {
      if (prevS < exitN && curS >= exitN) return 'EXIT_LONG';
      return 'NONE';
    }
    if (p === 'SHORT') {
      if (prevS > exitW && curS <= exitW) return 'EXIT_SHORT';
      return 'NONE';
    }
    return 'NONE';
  }

  function effectiveThresholds(settings, regime, formEntry, formExit) {
    const spreadOn = !!(settings && settings.spread_level_mode !== false
      && (settings.spread_level_mode === true || settings.spread_level_mode == null
        || String(settings.spread_level_mode) === '1'));
    if (spreadOn) {
      const cfg = spreadCfgSpread();
      return {
        entry: Number(settings?.spread_enter_wide ?? cfg.enter_wide),
        exit: Number(settings?.spread_exit_wide ?? cfg.exit_wide),
        regimeOn: false,
        spreadOn: true,
        allowEntry: true,
        levels: {
          enter_wide: Number(settings?.spread_enter_wide ?? cfg.enter_wide),
          exit_wide: Number(settings?.spread_exit_wide ?? cfg.exit_wide),
          enter_narrow: Number(settings?.spread_enter_narrow ?? cfg.enter_narrow),
          exit_narrow: Number(settings?.spread_exit_narrow ?? cfg.exit_narrow),
        },
      };
    }
    const modeOn = !!(settings && settings.regime_z_mode);
    if (modeOn && regime && regime.effective) {
      const e = Number(regime.effective.entry);
      const x = Number(regime.effective.exit);
      if (Number.isFinite(e) && Number.isFinite(x) && e > 0 && x > 0) {
        return {
          entry: e, exit: x, regimeOn: true, spreadOn: false,
          allowEntry: !!regime.effective.allow_entry,
        };
      }
    }
    const entry = Number(formEntry != null ? formEntry : settings?.entry_z);
    const exitZ = Number(formExit != null ? formExit : settings?.exit_z);
    return {
      entry: Number.isFinite(entry) && entry > 0 ? entry : 1.6,
      exit: Number.isFinite(exitZ) && exitZ > 0 ? exitZ : 1.3,
      regimeOn: modeOn,
      spreadOn: false,
      allowEntry: true,
    };
  }

  /** Как маркеры Z на Тесте: Long #69F0AE, Short #FF8A80; FLAT — серый pill */
  function posBadge(pos) {
    const p = String(pos || 'FLAT').toUpperCase();
    if (p === 'LONG') return '<span class="badge-pos-long">Long</span>';
    if (p === 'SHORT') return '<span class="badge-pos-short">Short</span>';
    return '<span class="badge-pos-flat">FLAT</span>';
  }

  function phaseBadgeHtml(phase) {
    if (!phase || phase.kind === 'idle') return '';
    const cls = phase.kind === 'ready'
      ? 'badge-phase-ready'
      : phase.kind === 'signal'
        ? 'badge-phase-signal'
        : 'badge-phase-prep';
    return `<span class="${cls}" title="${escapeHtml(phase.title || phase.label)}">${escapeHtml(phase.label)}</span>`;
  }

  function tickBadge(tradeDate) {
    return `<span class="badge-tick">тик ${escapeHtml(fmtTickLabel(tradeDate))}</span>`;
  }

  /** Кластер плашек в статус-баре: gap внутри группы меньше, чем между группами. */
  function statusGroupHtml(parts) {
    const items = (parts || []).filter(Boolean);
    if (!items.length) return '';
    return `<span class="status-group">${items.join('')}</span>`;
  }

  /** Компактный уровень спреда без лишних нулей (3.2, 4, 6.1). */
  function fmtRuleLvl(v, fallback) {
    const n = Number(v != null ? v : fallback);
    if (!Number.isFinite(n)) return String(fallback);
    const t = Math.round(n * 10) / 10;
    return Number.isInteger(t) ? String(t) : String(t);
  }

  /**
   * Вторая строка статус-бара: включённые правила из settings (как «Параметры Prod»).
   * Выключенные не показываем; для добора — явно «добор выкл».
   */
  function tradeRulesStatusHtml(settings) {
    if (!settings) return '';
    const items = [];
    const spreadOn = settings.spread_level_mode !== false
      && (settings.spread_level_mode === true || settings.spread_level_mode == null
        || String(settings.spread_level_mode) === '1');
    if (spreadOn) {
      const cfg = spreadCfgSpread();
      const en = fmtRuleLvl(settings.spread_enter_narrow, cfg.enter_narrow);
      const xn = fmtRuleLvl(settings.spread_exit_narrow, cfg.exit_narrow);
      const ew = fmtRuleLvl(settings.spread_enter_wide, cfg.enter_wide);
      const xw = fmtRuleLvl(settings.spread_exit_wide, cfg.exit_wide);
      items.push(
        `<span class="badge-rule badge-rule-spread" title="Уровни спреда Prod: Long вход ${en} → выход ${xn} · Short вход ${ew} → выход ${xw}">`
        + `L ${en}→${xn} · S ${ew}→${xw}</span>`
      );
    }
    const tp = Number(settings.take_profit_pct != null ? settings.take_profit_pct : 2);
    if (tp > 0) {
      items.push(
        `<span class="badge-rule badge-rule-tp" title="Выход по ТП: MTM % от депозита, как tip1m">ТП ${tp}%</span>`
      );
    }
    const noTrend = Number(
      settings.max_hold_days_no_exit_trend != null ? settings.max_hold_days_no_exit_trend : 5
    );
    if (noTrend > 0) {
      items.push(
        `<span class="badge-rule badge-rule-hold" title="Закрыть, если нет хода к выходу за ${noTrend} дн.">`
        + `нет хода ${noTrend}д</span>`
      );
    }
    const losing = Number(
      settings.max_hold_days_if_losing != null ? settings.max_hold_days_if_losing : 0
    );
    if (losing > 0) {
      items.push(
        `<span class="badge-rule badge-rule-losing" title="Закрыть убыточную позицию после ${losing} дн.">`
        + `в минусе ${losing}д</span>`
      );
    }
    if (settings.addon_mode !== false) {
      const aen = fmtRuleLvl(settings.addon_enter_narrow, 2.0);
      const aew = fmtRuleLvl(settings.addon_enter_wide, 7.0);
      const axn = fmtRuleLvl(settings.addon_exit_narrow, 3.2);
      const axw = fmtRuleLvl(settings.addon_exit_wide, 6.1);
      items.push(
        `<span class="badge-rule badge-rule-addon" title="Добор: Long касание ${aen}→выход ${axn} · Short ${aew}→${axw}">`
        + `добор ${aen}/${aew}</span>`
      );
    } else {
      items.push(
        `<span class="badge-rule badge-rule-addon-off" title="Добор выключен в параметрах Prod">добор выкл</span>`
      );
    }
    if (settings.extra_addon_mode !== false) {
      const een = fmtRuleLvl(settings.extra_enter_narrow, 1.0);
      const eew = fmtRuleLvl(settings.extra_enter_wide, 9.0);
      const exn = fmtRuleLvl(settings.extra_exit_narrow, 2.0);
      const exw = fmtRuleLvl(settings.extra_exit_wide, 7.0);
      items.push(
        `<span class="badge-rule badge-rule-addon" title="Экстра независима: Long в зоне ≤${een}→выход ${exn} · Short ≥${eew}→${exw}, если уже есть база или добор">`
        + `экстра ${een}/${eew}</span>`
      );
    } else {
      items.push(
        `<span class="badge-rule badge-rule-addon-off" title="Экстра выключена в параметрах Prod">экстра выкл</span>`
      );
    }
    if (settings.compound !== false) {
      items.push(
        `<span class="badge-rule badge-rule-addon" title="40/30/30 от текущего счёта: прибыль остаётся в рынке">капит.</span>`
      );
    }
    return statusGroupHtml(items);
  }

  function renderTradeRulesStatus(settings) {
    const el = $('tradeRulesStatus');
    if (!el) return;
    el.innerHTML = tradeRulesStatusHtml(settings);
  }

  let lastDeskBars = [];
  let metricTipEl = null;
  let metricTipHideTimer = null;
  let barMetricDists = { z: null, spread: null };
  /** @type {{ source: string, n: number, degraded: boolean, sampleLabel: string }} */
  let metricDistMeta = {
    source: 'none',
    n: 0,
    degraded: true,
    sampleLabel: 'окно графика',
  };

  function ensureMetricTip() {
    if (metricTipEl) return metricTipEl;
    const el = document.createElement('div');
    el.id = 'tradeLiveMetricTip';
    el.className = 'trade-metric-tip hidden';
    el.setAttribute('role', 'tooltip');
    document.body.appendChild(el);
    metricTipEl = el;
    return el;
  }

  function hideMetricTip() {
    if (metricTipHideTimer) {
      clearTimeout(metricTipHideTimer);
      metricTipHideTimer = null;
    }
    if (metricTipEl) metricTipEl.classList.add('hidden');
  }

  function positionMetricTip(tip, anchorEl) {
    const rect = anchorEl.getBoundingClientRect();
    const tipRect = tip.getBoundingClientRect();
    const pad = 8;
    let left = rect.left + rect.width / 2 - tipRect.width / 2;
    let top = rect.top - tipRect.height - 10;
    if (top < pad) top = rect.bottom + 10;
    if (left < pad) left = pad;
    if (left + tipRect.width > window.innerWidth - pad) {
      left = window.innerWidth - tipRect.width - pad;
    }
    if (top + tipRect.height > window.innerHeight - pad) {
      top = Math.max(pad, window.innerHeight - tipRect.height - pad);
    }
    tip.style.left = `${Math.round(left)}px`;
    tip.style.top = `${Math.round(top)}px`;
  }

  function distsFromBars(bars) {
    if (typeof computeNumericDistribution !== 'function') {
      return { z: null, spread: null };
    }
    const zs = [];
    const sps = [];
    for (const b of bars || []) {
      const z = b.z != null ? Number(b.z) : (b.zScore != null ? Number(b.zScore) : NaN);
      const sp = b.spread != null ? Number(b.spread)
        : (b.spreadPercent != null ? Number(b.spreadPercent) : NaN);
      if (Number.isFinite(z)) zs.push(z);
      if (Number.isFinite(sp)) sps.push(sp);
    }
    return {
      z: computeNumericDistribution(zs),
      spread: computeNumericDistribution(sps),
    };
  }

  function hydrateServerDist(raw) {
    if (!raw || !Array.isArray(raw.bins) || !(raw.n > 0)) return null;
    const numOrNaN = (v) => {
      const x = Number(v);
      return Number.isFinite(x) ? x : NaN;
    };
    return {
      n: Number(raw.n) || 0,
      nHist: Number(raw.nHist != null ? raw.nHist : raw.n) || 0,
      min: Number(raw.min),
      max: Number(raw.max),
      histMin: Number(raw.histMin != null ? raw.histMin : raw.lo),
      histMax: Number(raw.histMax != null ? raw.histMax : raw.hi),
      mean: Number(raw.mean),
      stdev: Number(raw.stdev),
      median: numOrNaN(raw.median),
      mad: numOrNaN(raw.mad),
      p5: numOrNaN(raw.p5),
      p25: numOrNaN(raw.p25),
      p75: numOrNaN(raw.p75),
      p95: numOrNaN(raw.p95),
      bins: raw.bins.map((c) => Number(c) || 0),
      lo: Number(raw.lo),
      hi: Number(raw.hi),
      width: Number(raw.width),
      binCount: Number(raw.binCount) || raw.bins.length,
      histClipped: !!(raw.histClipped),
      sorted: [],
    };
  }

  /**
   * Prefer server ≈3y compact dists for tips / «в хвосте»; chart window stays for display.
   * Fallback: window bars (degraded).
   */
  function rebuildBarMetricDists(bars, serverDists) {
    lastDeskBars = Array.isArray(bars) ? bars : [];
    const windowDists = distsFromBars(lastDeskBars);
    const z3 = hydrateServerDist(serverDists && serverDists.z);
    const sp3 = hydrateServerDist(serverDists && serverDists.spread);
    const use3y = !!(serverDists && serverDists.ok && z3 && sp3);
    if (use3y) {
      barMetricDists = { z: z3, spread: sp3 };
      metricDistMeta = {
        source: String(serverDists.source || '3y'),
        n: Number(serverDists.n) || z3.n,
        degraded: false,
        sampleLabel: '3 года',
      };
      return;
    }
    barMetricDists = windowDists;
    const nWin = Math.max(windowDists.z?.n || 0, windowDists.spread?.n || 0);
    metricDistMeta = {
      source: 'window',
      n: nWin,
      degraded: true,
      sampleLabel: nWin > 0 ? 'окно графика' : 'нет данных',
    };
  }

  function formatBarMetricStat(metric, value) {
    if (value == null || !Number.isFinite(value)) return '—';
    if (metric === 'z') {
      const sign = value > 0 ? '+' : '';
      return `${sign}${value.toFixed(2)}`;
    }
    if (metric === 'spread') return `${value.toFixed(2)}%`;
    return String(value);
  }

  function showBarMetricTip(anchor) {
    if (typeof buildMetricDistTipHtml !== 'function') return;
    const metric = anchor.dataset.metric;
    const value = Number(anchor.dataset.value);
    if ((metric !== 'z' && metric !== 'spread') || !Number.isFinite(value)) return;
    const dist = barMetricDists[metric];
    if (!dist) return;
    const title = metric === 'z' ? 'Z' : 'Спред';
    const display = anchor.textContent.trim() || formatBarMetricStat(metric, value);
    const tip = ensureMetricTip();
    tip.innerHTML = buildMetricDistTipHtml({
      title,
      display,
      value,
      dist,
      colKey: metric,
      formatStat: (v) => formatBarMetricStat(metric, v),
      sampleLabel: metricDistMeta.sampleLabel,
      spreadWidthRegime: metric === 'spread',
    });
    tip.classList.remove('hidden');
    positionMetricTip(tip, anchor);
  }

  function metricHoverValue(metric, value, display) {
    if (value == null || !Number.isFinite(Number(value))) {
      return escapeHtml(display);
    }
    return (
      `<span class="metric-hover" data-metric="${escapeHtml(metric)}" data-value="${Number(value)}">`
      + `${escapeHtml(display)}</span>`
    );
  }

  /**
   * Подпись под метрикой: для спреда — узкий/переход/широкий;
   * для Z — «типично» / «ниже|выше обычного» / «выброс» по медиане≈3г.
   */
  function metricPlaceCaption(metric, value) {
    if (value == null || !Number.isFinite(Number(value))) return '';
    if (metric === 'spread') {
      if (typeof classifySpreadWidthRegime !== 'function') return '';
      const reg = classifySpreadWidthRegime(Number(value));
      if (!reg || !reg.key || reg.key === 'na') return '';
      const title = reg.title ? ` title="${escapeHtml(reg.title)}"` : '';
      return (
        `<span class="metric-place metric-place-${escapeHtml(reg.key)}"${title}>`
        + `${escapeHtml(reg.label)}</span>`
      );
    }
    if (typeof classifyTradeMetricPlacement !== 'function') return '';
    const dist = barMetricDists[metric];
    if (!dist) return '';
    const place = classifyTradeMetricPlacement(Number(value), dist, { colKey: metric });
    if (!place || !place.key || place.key === 'none') return '';
    return (
      `<span class="metric-place metric-place-${escapeHtml(place.key)}">`
      + `${escapeHtml(place.label)}</span>`
    );
  }

  function metricStripBlock(label, metric, value, display) {
    const place = metricPlaceCaption(metric, value);
    return (
      `<span class="metric-block">`
      + `<span class="metric-block-row"><b>${escapeHtml(label)}</b> ${metricHoverValue(metric, value, display)}</span>`
      + (place || '')
      + `</span>`
    );
  }

  function regimeBadge(bars) {
    if (typeof classifySpreadRegime !== 'function') return '';
    const r = classifySpreadRegime(bars || []);
    if (!r || r.key === 'na') return '';
    return `<span class="badge-regime badge-regime-${escapeHtml(r.key)}" title="${escapeHtml(r.title)}">`
      + `${escapeHtml(r.label)}</span>`;
  }

  /** Каскад через 3.5 и 5.5 за ~10д — ранний/подтверждающий флаг смены режима. */
  function cascadeBadge(bars) {
    if (typeof detectSpreadRegimeCascade !== 'function') return '';
    const c = detectSpreadRegimeCascade(bars || []);
    if (!c || !c.on) return '';
    return `<span class="badge-cascade badge-cascade-${escapeHtml(c.key)}" title="${escapeHtml(c.title)}">`
      + `${escapeHtml(c.label)}</span>`;
  }

  /** Зона карты Prod 3.2/4/5.8/6.1 + длительность эпизода. */
  function zoneMapBadge(bars) {
    if (typeof detectSpreadMapZone !== 'function') return '';
    const z = detectSpreadMapZone(bars || []);
    if (!z || !z.on) return '';
    const wall = z.nearWall ? ' badge-zone-wall' : '';
    return `<span class="badge-zone badge-zone-${escapeHtml(z.key)}${wall}" title="${escapeHtml(z.title)}">`
      + `${escapeHtml(z.badgeText || z.shortLabel)}</span>`;
  }

  /**
   * MOEX trade_date — wall-clock MSK. Как labelToUnixSec в replay-engine.js:
   * явно +03:00, без Date.parse без зоны (браузер иначе сдвигает на несколько часов).
   */
  function toChartTime(tradeDate, timestampMs) {
    const ms = Number(timestampMs);
    if (Number.isFinite(ms) && ms > 0) {
      if (ms > 1e12) return Math.floor(ms / 1000);
      if (ms > 1e9) return Math.floor(ms);
    }
    if (typeof labelToUnixSec === 'function') {
      const t = labelToUnixSec(tradeDate);
      return (typeof t === 'number' && t > 1e9) ? t : null;
    }
    if (!tradeDate) return null;
    const s = String(tradeDate).trim().replace('T', ' ');
    let iso;
    if (s.length >= 16) iso = `${s.slice(0, 16).replace(' ', 'T')}:00+03:00`;
    else if (s.length >= 10 && s[4] === '-' && s[7] === '-') iso = `${s.slice(0, 10)}T00:00:00+03:00`;
    else return null;
    const parsed = new Date(iso).getTime();
    const sec = Number.isFinite(parsed) ? Math.floor(parsed / 1000) : null;
    return (sec != null && sec > 1e9) ? sec : null;
  }

  /** Подписи оси/кроссхейра в MSK — общий форматтер из chart.js */
  function formatChartTick(time) {
    if (typeof time !== 'number') return '';
    if (typeof window.formatMskAxisDayMonthYear === 'function') {
      return window.formatMskAxisDayMonthYear(time, true);
    }
    return new Date(time * 1000).toLocaleString('ru-RU', {
      timeZone: MSK,
      day: 'numeric',
      month: 'short',
      year: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    });
  }

  function chartTimeOpts() {
    return {
      timeScale: {
        borderColor: '#2a2e39',
        timeVisible: true,
        secondsVisible: false,
        tickMarkFormatter: formatChartTick,
        // 3М tip1m ≈ 70k свечей: без этого fitContent схлопывает ось (как на Тесте).
        minBarSpacing: 0.001,
        maxBarSpacing: 64,
      },
      localization: {
        locale: 'ru-RU',
        timeFormatter: formatChartTick,
      },
    };
  }




  /** Hover = эта свеча, иначе последняя. */


  /** Horizontal price-band fill (BaselineSeries); ignored by Y autoscale. */

  /**
   * Trade-band Y bounds from desk.spread_levels.levels (not regime cuts).
   * Narrow Long: enter_narrow…exit_narrow; Wide Short: exit_wide…enter_wide;
   * Transition: exit_narrow…exit_wide (gap, no entry).
   */

  /**
   * @param {object} chart
   * @param {{narrow:*,transition:*,wide:*}} bandsRef
   * @param {object|null} levels
   * @returns {boolean} true if band series were newly created
   */


  /**
   * Upper pane (spread candles) — same Long/Short zone fills as Test chart.js.
   * If bands are created after candles already exist, recreate candle series so
   * fills stay under OHLC (z-order = add order).
   */


  /**
   * Fill trade bands on lower yellow S% chart from enter/exit levels.
   * @param {Array<{time:number}>|null} spreadPts — times; omit to reuse last
   * @param {{enter_wide?:number,exit_wide?:number,enter_narrow?:number,exit_narrow?:number}|null} levels
   */

  /**
   * Fill Long/Short zones on upper spread-candle pane (parity Test).
   * @param {Array<{time:number}>|null} candlePts
   * @param {object|null} levels
   */




  /**
   * Prefer CandlestickSeries. LineSeries only if candles unavailable.
   * @returns {boolean} true if series was (re)created
   */

  /** Bottom MTLR pane: candles by default (same as TATN top). */



  /** Уровни спреда на верхнем pane (L/S вх/вых — без линий коридора). */

  /** Снять линию цели ТП с верхнего графика Прода. */

  /**
   * Горизонталь «цель выхода по ТП» на графике Прода.
   * Уровень — close_forecast.exit_level_spread. Нет открытой / ТП выкл → линии нет.
   */

  /** Уровни спреда из desk.spread_levels / settings (не хардкод-only). */



  /**
   * Live tip Z/S% from monitor / open mark — not parquet/sidecar chart tail.
   * Desk tip1m bars often lag (cold μ/σ or sidecar ≈−1.9 while tip ≈−2.3).
   */

  /** Patch last tip1m bar to live tip so last-price label ≡ overlay ≡ monitor. */

  /** Keep Z candles + spread line on identical sorted timestamps (1:1). */


  /** Спред % на баре tip1m по chart-time (когда API не отдал entry_spread). */

  /** Y-координата линии highlight: на графике спреда — только %, не Z. */

  /** Ensure LW markers can land on entry_time (series must contain the time). */
    const entry = resolveOpenEntryOnBars(bars, open);
    if (!entry || entry.time == null) {
      return { zData, spreadPts, bars };
    }
    const t = entry.time;
    const hasZ = (zData || []).some((c) => c && c.time === t);
    const dualPane = Array.isArray(spreadPts) && spreadPts.length > 0;
    const hasSp = dualPane && spreadPts.some((p) => p && p.time === t);
    // TATN/MTLR now paint one candle series; zip vs empty spreadPts left 1 bar
    // (all markers stacked at the live tick — «Аид» вместо свечей).
    if (!dualPane) {
      let nextZ = zData ? zData.slice() : [];
      let nextBars = bars ? bars.slice() : [];
      if (!hasZ) {
        const z = Number(entry.z);
        const zVal = Number.isFinite(z) ? z : 0;
        const spN = Number(entry.spread != null ? entry.spread : open.entry_spread);
        const spVal = Number.isFinite(spN) ? spN : zVal;
        const candleVal = primarySpread ? spVal : zVal;
        nextZ.push({
          time: t, open: candleVal, high: candleVal, low: candleVal, close: candleVal,
        });
        nextZ.sort((a, b) => a.time - b.time);
      }
      if (entry.synthetic && !nextBars.some((b) => toChartTime(b.time, b.timestampMs) === t)) {
        const z = Number(entry.z);
        const zVal = Number.isFinite(z) ? z : 0;
        const spN = Number(entry.spread != null ? entry.spread : open.entry_spread);
        const spVal = Number.isFinite(spN) ? spN : zVal;
        nextBars.push({
          time: open.entry_time,
          timestampMs: t * 1000,
          z: zVal,
          spread: spVal,
          interval: '1m',
          source: primarySpread ? 'tinvest_dealer_1m' : 'tip1m',
          for_z: !primarySpread,
        });
        nextBars.sort((a, b) => (
          (toChartTime(a.time, a.timestampMs) || 0) - (toChartTime(b.time, b.timestampMs) || 0)
        ));
      }
      return { zData: nextZ, spreadPts, bars: nextBars };
    }
    if (hasZ && hasSp && !entry.synthetic) {
      const synced = syncChartSeriesByTime(zData, spreadPts);
      return { zData: synced.zData, spreadPts: synced.spreadPts, bars };
    }
    const z = Number(entry.z);
    const zVal = Number.isFinite(z) ? z : 0;
    const spN = Number(entry.spread != null ? entry.spread : open.entry_spread);
    const spVal = Number.isFinite(spN) ? spN : zVal;
    const candleVal = primarySpread ? spVal : zVal;
    let nextZ = zData ? zData.slice() : [];
    let nextSp = spreadPts.slice();
    let nextBars = bars ? bars.slice() : [];
    // Dual pane: inject into BOTH series — never leave Z/spread length mismatch.
    if (!hasZ) {
      nextZ.push({
        time: t, open: candleVal, high: candleVal, low: candleVal, close: candleVal,
      });
    }
    if (!hasSp) {
      nextSp.push({ time: t, value: spVal });
    }
    nextZ.sort((a, b) => a.time - b.time);
    nextSp.sort((a, b) => a.time - b.time);
    const synced = syncChartSeriesByTime(nextZ, nextSp);
    nextZ = synced.zData;
    nextSp = synced.spreadPts;
    if (entry.synthetic && !nextBars.some((b) => toChartTime(b.time, b.timestampMs) === t)) {
      nextBars.push({
        time: open.entry_time,
        timestampMs: t * 1000,
        z: zVal,
        spread: spVal,
        interval: '1m',
        source: primarySpread ? 'tinvest_dealer_1m' : 'tip1m',
        for_z: !primarySpread,
      });
      nextBars.sort((a, b) => (
        (toChartTime(a.time, a.timestampMs) || 0) - (toChartTime(b.time, b.timestampMs) || 0)
      ));
    }
    return { zData: nextZ, spreadPts: nextSp, bars: nextBars };
  }

















  /** Median Δt of chart bars — tip1m ~60s, M15 ~900s. */

  /**
   * Snap entry/exit to a real candle time for LW markers.
   * Returns null if nearest bar is farther than maxDeltaSec — NEVER pile
   * weekend/older trades onto the first Monday tip1m candle.
   */

  /** Маркеры закрытых + открытой сделки (стиль Теста: стрелка входа, круг выхода). */


  /** Prod AUTO is spread-levels: drop leftover Z-risk text/score from old enrich. */



    ensureCharts();
    lastDeskPaintBars = Array.isArray(bars) ? bars : [];
    const tatnBuilt = buildDeskTradeMarkers(closed, open, bars);
    lastDeskMarkers = tatnBuilt.markers;
    lastDeskTrades = tatnBuilt.trades;
    const mtlrBuilt = buildDeskTradeMarkers(
      mtlrClosed || [],
      mtlrOpen || null,
      mtlrBars != null ? mtlrBars : lastMtlrBars,
    );
    lastMtlrMarkers = mtlrBuilt.markers;
    if (hoverTradeId && !deskTradeById(hoverTradeId)) hoverTradeId = null;
    applyTradeMarkers(
      buildMarkerRenderData(tatnBuilt.markers, hoverTradeId),
      buildMarkerRenderData(mtlrBuilt.markers, null),
    );

    if (!open || !bars || !bars.length) {
      clearOpenTradeOnChart();
      return;
    }
    const mark = open.mark || {};
    const entry = resolveOpenEntryOnBars(bars, open);
    const last = bars[bars.length - 1];
    const exitTime = toChartTime(last.time, last.timestampMs);
    // Overlay must match last painted primary candle (Z or spread %).
    let exitVal = NaN;
    if (exitTime != null && zPriceByTime.has(exitTime)) {
      exitVal = Number(zPriceByTime.get(exitTime));
    }
    if (!Number.isFinite(exitVal)) {
      if (chartPrimarySpread) {
        if (last != null && last.spread != null) exitVal = Number(last.spread);
        if (!Number.isFinite(exitVal) && mark.spread_now != null) exitVal = Number(mark.spread_now);
      } else {
        if (last != null && last.z != null) exitVal = Number(last.z);
        if (!Number.isFinite(exitVal) && mark.z_now != null) exitVal = Number(mark.z_now);
      }
    }
    const entryVal = chartPrimarySpread
      ? Number(entry && entry.spread != null
        ? entry.spread
        : (mark.fill_spread != null ? mark.fill_spread : open.entry_spread))
      : Number(entry && entry.z);
    if (!entry || exitTime == null || !Number.isFinite(exitVal) || !Number.isFinite(entryVal)) {
      clearOpenTradeOnChart();
      return;
    }
    const dir = String(open.direction || '').toUpperCase();
    const isLong = dir === 'LONG';
    const fp = [
      open.id, entry.time, entryVal, exitTime, exitVal,
      mark.unrealized_pnl_rub, mark.spread_now, mark.fills_spread,
      (closed || []).length, chartPrimarySpread ? 'sp' : 'z',
      String(open.entry_comment || ''),
    ].join('|');
    lastOpenTradeFp = fp;

    defaultOpenHighlightData = [
      { time: entry.time, value: entryVal },
      { time: exitTime, value: exitVal },
    ].sort((a, b) => a.time - b.time);
    applyHighlightForActiveTrade();

    const dirShort = typeof tradeDirectionShort === 'function'
      ? tradeDirectionShort(isLong ? 'Long' : 'Short')
      : (isLong ? 'L' : 'S');
    const el = $('tradeOpenTradeOverlay');
    if (!el) return;
    const net = openProfitRub(open)
      ?? Number(mark.net_approx_rub ?? mark.unrealized_pnl_rub);
    const pnlClass = net > 0 ? 'pnl-pos' : net < 0 ? 'pnl-neg' : '';
    const deposit = entryDepositRub(open, null);
    const netText = fmtPnlWithDepositPct(net, deposit);
    const srcUp = String(open.source || '').toUpperCase();
    const isManual = isManualTradeSource(open.source);
    const srcTag = isManual
      ? (srcUp === 'BROKER'
        ? ' <span class="ot-src-manual">· ручная (брокер)</span>'
        : ' <span class="ot-src-manual">· ручная</span>')
      : '';
    const entryLabel = typeof compactDateTime === 'function'
      ? compactDateTime(open.entry_time || entry.tradeDate)
      : (open.entry_time || entry.tradeDate || '');
    const entrySpread = mark.fill_spread != null ? mark.fill_spread : open.entry_spread;
    const nowSpread = mark.spread_now;
    const dirLabel = isLong ? 'LONG спрэд' : 'SHORT спрэд';
    const duration = typeof formatSimTradeDuration === 'function'
      ? formatSimTradeDuration(open.entry_time || entry.tradeDate, last.time || mark.trade_date)
      : '';
    const tradeNo = (closed || []).length + 1;
    // Комментарий — только в панели «Сделка», без дубля на оверлее графика.
    el.classList.remove('hidden');
    el.classList.toggle('ot-manual', isManual);
    el.innerHTML = [
      `<div class="ot-trade">${tradeNo} ${dirShort} ${entryLabel}${srcTag} `
        + `<span class="ot-pnl ${pnlClass}">${netText}</span></div>`,
      `<div class="ot-spread">${dirLabel} · ${fmt(entrySpread)}% → ${fmt(nowSpread)}%</div>`,
      duration ? `<div class="ot-duration">${duration}</div>` : '',
    ].join('');
  }

  /**
   * Display-only tip-style Z: rolling μ/σ on ISS M15 + dealer 1m tip as last obs.
   * Never feeds AUTO — UI chart only (for_z stays false / z_kind=dealer_monitor).
   */

  /** Build one spread % OHLC candle: prefer server spread_open/high/low; else prev→close. */

  /** Build one Z OHLC candle: prefer server z_open/high/low when set; else prevZ→currZ. */

  /**
   * Median bar step in chart-time seconds (UTC unix). Null if <2 points.
   */

  /**
   * Z candles from tip1m bars with z (~1m step) — never M15, never TATN mid.
   * Returns [] if series is empty or looks like M15 (≥10m median step).
   * Prefer server z_open/high/low; else open=prevZ, close=currZ (visible bodies).
   * Last (forming) bar is always flat at close — live-tip align must not invent
   * a wick from stale sidecar prevZ down to entry / monitor Z.
   */

  /**
   * Weekday tip1m paint series: Z candles + spread, identical timestamps.
   * Rejects M15-density (HARD RULE). Zip by time — same length, same keys.
   */

  /** Calendar-day span of chart bars (for period chip coverage checks). */


  /**
   * Уменьшить плотность длинного tip1m только для отрисовки.
   * Диапазон дат, open/close и внутригрупповые min/max сохраняются.
   */

  /**
   * If desk still serves iss_m15 under tip1m labels (pre-reload / race),
   * or tip1m is session-only while period chip asks 1Н/1М/3М/6М —
   * replace with real tip1m (static sidecar → bars1m) or empty — never paint M15.
   */

  /**
   * If API still clips MTLR m15 to 2500 bars (~45д), fill 1М/3М/6М from sidecar.
   */

  /**
   * Spread % candles for TATN tip1m/dealer (top). Prefer spread_open/high/low; else prevSp→currSp.
   */

  /**
   * Spread % candles for MTLR m15 (bottom pane). Allows ~15m step.
   */



  /** Только хвост изменился — update() вместо setData на тысячах баров. */


  /**
   * Top: TATN tip1m/dealer spread candles. Bottom: MTLR m15 spread candles + Mechel levels.
   */
    ensureCharts();
    const monMode = !!(dealer1m || weekendMonitor);
    if (zSeriesIsLine || !zSeries) {
      ensureZSeriesKind(false);
    }
    if (spreadSeriesIsLine || !spreadSeries) {
      ensureSpreadSeriesKind(false);
    }
    if (!zSeries || !spreadSeries) return;

    if (monMode !== chartDealer1m) {
      chartDealer1m = monMode;
      forceFitContent = true;
      clearPinState();
    }
    if (!chartPrimarySpread) {
      chartPrimarySpread = true;
      forceFitContent = true;
      clearPinState();
    }

    let zData = [];
    let paintBars = bars;
    const mtlrPaint = Array.isArray(mtlrBars) ? mtlrBars : lastMtlrBars;
    if (Array.isArray(mtlrBars)) lastMtlrBars = mtlrBars.slice();
    const mtlrLv = mtlrLevels || lastMtlrLevels || {
      enter_wide: 8.9, exit_wide: 8.4, enter_narrow: 3.2, exit_narrow: 4.3,
    };
    if (mtlrLevels) lastMtlrLevels = mtlrLevels;

    const built = buildSpread1mChartSeries(paintBars);
    zData = built.zPts;
    if (openTrade) {
      const inj = injectOpenEntryIntoChartSeries(zData, [], openTrade, paintBars, {
        primarySpread: true,
      });
      zData = inj.zData;
      paintBars = inj.bars;
    }

    let mtlrCandlePts = buildSpreadM15ChartSeries(mtlrPaint);
    if (mtlrOpen) {
      const injM = injectOpenEntryIntoChartSeries(
        mtlrCandlePts, [], mtlrOpen, mtlrPaint, { primarySpread: true },
      );
      mtlrCandlePts = injM.zData;
    }

    zPriceByTime = new Map(zData.map((c) => [c.time, c.close != null ? c.close : c.value]));
    spPriceByTime = new Map(mtlrCandlePts.map((c) => [
      c.time, c.close != null ? c.close : c.value,
    ]));

    const zEmpty = zData.length === 0;
    const mtlrEmpty = mtlrCandlePts.length === 0;
    updateChartPaneLabels(monMode, {
      zEmpty: monMode && zEmpty,
      lookbackDays: monMode ? lookbackDays : null,
      spreadLevels,
      mtlrLevels: mtlrLv,
      mtlrEmpty,
    });
    // Не вызывать setData([]) — LC очищает серию; раз в минуту poll с пустым
    // payload мигал пустым графиком при живых осях/линии цены.
    if (zEmpty && lastPaintZData && lastPaintZData.length) {
      setZEmptyMessage('');
      try { window.__deskChartEmptySkip = (window.__deskChartEmptySkip || 0) + 1; } catch (_) {}
      setPrimarySpreadThresholdLines(spreadLevels);
      setTpExitSpreadLine(openTrade, closeForecast, settings);
      setSpreadThresholdLines(mtlrLv);
      paintCorridorOnChart(corridor, lastPaintZData);
      updateOpenTradeOnChart(openTrade, paintBars, closedTrades, {
        mtlrOpen, mtlrBars: mtlrPaint, mtlrClosed,
      });
      paintTradeOhlc(null);
      if (userPinnedAwayFromLive && pinnedRange) reassertPinnedRange();
      return;
    }

    if (monMode && zEmpty) {
      setZEmptyMessage('Нет баров дилер 1м · ISS M15 не рисуем · спред внизу — Мечел');
    } else if (!monMode && zEmpty) {
      setZEmptyMessage('Нет tip1m 1м · ISS M15 не рисуем под лейблом tip1m');
    } else {
      setZEmptyMessage('');
    }

    let bodyN = 0;
    for (const c of zData) {
      if (!c || c.open == null || c.close == null) continue;
      if (Math.abs(Number(c.open) - Number(c.close)) > 1e-9
        || (c.high != null && c.low != null && Math.abs(Number(c.high) - Number(c.low)) > 1e-9)) {
        bodyN += 1;
      }
    }
    const previousFirstTime = lastPaintZData && lastPaintZData.length
      ? Number(lastPaintZData[0].time)
      : null;
    const nextFirstTime = zData.length ? Number(zData[0].time) : null;
    if (!userPinnedAwayFromLive
      && Number.isFinite(previousFirstTime)
      && Number.isFinite(nextFirstTime)
      && nextFirstTime < previousFirstTime - 86400) {
      forceFitContent = true;
    }
    publishChartDebug({
      zPts: zData.length,
      spPts: mtlrCandlePts.length,
      bodyN,
      primary: chartPrimarySpread ? 'spread' : 'z',
      bottom: 'mtlr_m15',
      sample: zData.length ? [zData[0], zData[zData.length - 1]] : null,
      sync: { mode: 'time', zN: zData.length, mtlrN: mtlrCandlePts.length },
    });

    const fp = barsFingerprint(paintBars) + '|zc' + zData.length
      + '|mtlr' + mtlrCandlePts.length + '|' + barsFingerprint(mtlrPaint)
      + '|c' + (closedTrades || []).length + '|o' + (openTrade ? openTrade.id : '')
      + '|mc' + (mtlrClosed || []).length + '|mo' + (mtlrOpen ? mtlrOpen.id : '')
      + (monMode ? '|d1m' : '')
      + (zSeriesIsLine ? '|zl' : '|zcndl')
      + (spreadSeriesIsLine ? '|sl' : '|scndl')
      + (chartPrimarySpread ? '|ps' : '|pz')
      + (zEmpty ? '|ze' : '')
      + (mtlrEmpty ? '|me' : '')
      + (Array.isArray(zBars) ? '|iss' + zBars.length : '')
      + '|cr' + corridorFingerprint(corridor);
    const dataChanged = fp !== lastBarsFingerprint;
    lastBarsFingerprint = fp;

    if (!dataChanged && !forceFitContent) {
      setPrimarySpreadThresholdLines(spreadLevels);
      setTpExitSpreadLine(openTrade, closeForecast, settings);
      setSpreadThresholdLines(mtlrLv);
      paintCorridorOnChart(corridor, zData);
      updateOpenTradeOnChart(openTrade, paintBars, closedTrades, {
        mtlrOpen, mtlrBars: mtlrPaint, mtlrClosed,
      });
      paintTradeOhlc(null);
      if (userPinnedAwayFromLive && pinnedRange) reassertPinnedRange();
      return;
    }

    const tailOnly = !forceFitContent && dataChanged
      && canTailUpdateChart(lastPaintZData, zData)
      && (!mtlrCandlePts.length || !lastPaintMtlrData
        || canTailUpdateChart(lastPaintMtlrData, mtlrCandlePts));
    if (tailOnly && applyTailChartUpdate(zData, mtlrCandlePts)) {
      setPrimarySpreadThresholdLines(spreadLevels);
      setTpExitSpreadLine(openTrade, closeForecast, settings);
      setSpreadThresholdLines(mtlrLv);
      paintCorridorOnChart(corridor, zData);
      updateOpenTradeOnChart(openTrade, paintBars, closedTrades, {
        mtlrOpen, mtlrBars: mtlrPaint, mtlrClosed,
      });
      paintTradeOhlc(null);
      lastBarsFingerprint = fp;
      if (!userGestureActive) {
        if (userPinnedAwayFromLive) reassertPinnedRange();
        else restoreOrFitVisibleRange(zData.length, mtlrCandlePts.length);
      } else {
        scheduleEndSuppress();
      }
      return;
    }

    try {
      suppressRangeEvents = true;
      equalizePriceScales();
      try {
        if (zSeriesIsLine) {
          const linePts = zData.map((c) => (
            c && c.value != null
              ? { time: c.time, value: Number(c.value) }
              : { time: c.time, value: Number(c.close) }
          )).filter((p) => p.time != null && Number.isFinite(p.value));
          zSeries.setData(linePts);
        } else {
          try { zSeries.setData(zData); }
          catch {
            ensureZSeriesKind(true);
            zSeries.setData(zData.map((c) => ({ time: c.time, value: c.close })));
            zSeriesIsLine = true;
          }
        }
        if (spreadSeriesIsLine) {
          spreadSeries.setData(mtlrCandlePts.map((c) => ({
            time: c.time, value: c.close != null ? c.close : c.value,
          })));
        } else {
          try { spreadSeries.setData(mtlrCandlePts); }
          catch {
            ensureSpreadSeriesKind(true);
            spreadSeries.setData(mtlrCandlePts.map((c) => ({
              time: c.time, value: c.close,
            })));
          }
        }
      } catch (e) {
        suppressRangeEvents = false;
        publishChartDebug({
          zPts: zData.length,
          spPts: mtlrCandlePts.length,
          err: String(e && e.message || e),
          sync: { mode: 'time', zN: zData.length, mtlrN: mtlrCandlePts.length },
        });
        throw e;
      }
      updatePrimarySpreadBands(zData, spreadLevels);
      updateSpreadRegimeBands(mtlrCandlePts, mtlrLv);
      setPrimarySpreadThresholdLines(spreadLevels);
      setTpExitSpreadLine(openTrade, closeForecast, settings);
      setSpreadThresholdLines(mtlrLv);
      paintCorridorOnChart(corridor, zData);
      updateOpenTradeOnChart(openTrade, paintBars, closedTrades, {
        mtlrOpen, mtlrBars: mtlrPaint, mtlrClosed,
      });
      lastPaintZData = zData;
      lastPaintMtlrData = mtlrCandlePts;
      paintTradeOhlc(null);
      if (!userGestureActive) {
        restoreOrFitVisibleRange(zData.length, mtlrCandlePts.length);
      } else {
        scheduleEndSuppress();
      }
      forceSyncAfterPaint();
      publishChartDebug({
        zPts: zData.length,
        spPts: mtlrCandlePts.length,
        bodyN,
        primary: chartPrimarySpread ? 'spread' : 'z',
        bottom: 'mtlr_m15',
        sample: zData.length ? [zData[0], zData[zData.length - 1]] : null,
        sync: { mode: 'time', zN: zData.length, mtlrN: mtlrCandlePts.length },
      });
    } catch (e) {
      suppressRangeEvents = false;
      console.warn('trade chart', e);
    }
  }

  function syncTradeActionButtons(data) {
    const open = data && data.open;
    const flat = !open;
    const dealer = data && data.dealer;
    const dealer1m = String(data?.bars_mode || '') === 'dealer_1m'
      || String(data?.bars_mode || '') === 'dealer_weekend';
    const weekend = !!(data?.weekend_monitor || dealer1m || nowMskParts().weekend);
    const dealerPresent = !!(dealer && (dealer.ok || dealer.quotes_ok || dealer.manual_ok != null || dealer.error));
    // Weekend / OTC dealer: enable when quotes present (not only DEALER_NORMAL).
    // Weekday TQBR session: allow if flat. Close always when position open.
    const dealerQuotesOk = !!(dealer && (dealer.manual_ok || dealer.quotes_ok || (dealer.ok && dealer.tatn != null && dealer.tatnp != null)));
    const manualGate = (weekend || dealerPresent) ? dealerQuotesOk : true;
    const canOpen = flat && manualGate;
    const btnLong = $('tradeBtnLong');
    const btnShort = $('tradeBtnShort');
    const btnClose = $('tradeBtnClose');
    if (btnLong) {
      btnLong.disabled = !canOpen;
      btnLong.title = !flat
        ? 'Уже есть открытая позиция'
        : (!manualGate
          ? 'Ручной вход недоступен (нет дилерских котировок)'
          : 'Ручной вход Long (выходные/OTC или TQBR)');
    }
    if (btnShort) {
      btnShort.disabled = !canOpen;
      btnShort.title = !flat
        ? 'Уже есть открытая позиция'
        : (!manualGate
          ? 'Ручной вход недоступен (нет дилерских котировок)'
          : 'Ручной вход Short (выходные/OTC или TQBR)');
    }
    if (btnClose) {
      btnClose.disabled = flat;
      btnClose.title = flat
        ? 'Нет открытой позиции'
        : 'Закрыть открытый спрэд на брокере (OTC/TQBR)';
    }
  }

  /** Path Min/Max MTM entry→now (parity closed pnl_min/max; no exit commission). */
  function overnightDaysOpen(entryTime, barTime) {
    const a = String(entryTime || '').slice(0, 10);
    const b = String(barTime || '').slice(0, 10);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(a) || !/^\d{4}-\d{2}-\d{2}$/.test(b)) return 0;
    const ms = Date.parse(`${b}T00:00:00`) - Date.parse(`${a}T00:00:00`);
    if (!Number.isFinite(ms)) return 0;
    return Math.max(0, Math.round(ms / 86400000));
  }

  function parseTradeMsOpen(ts) {
    if (ts == null) return null;
    if (typeof ts === 'number' && Number.isFinite(ts)) return ts;
    const s = String(ts).trim().replace('T', ' ');
    // MSK wall-clock — same as replay-sim parseTradeMs (avoid browser TZ shift).
    const iso = s.length >= 16
      ? `${s.slice(0, 16).replace(' ', 'T')}:00+03:00`
      : `${s}+03:00`;
    const ms = new Date(iso).getTime();
    return Number.isNaN(ms) ? null : ms;
  }

  /** Compact wall-clock for Min/Max: «ДД.ММ ЧЧ:ММ». */
  function compactExtremeDt(label) {
    if (!label) return '';
    const s = String(label).trim().replace('T', ' ');
    // YYYY-MM-DD HH:MM…
    if (/^\d{4}-\d{2}-\d{2}/.test(s) && s.length >= 16) {
      return `${s.slice(8, 10)}.${s.slice(5, 7)} ${s.slice(11, 16)}`;
    }
    if (s.length >= 16) return s.slice(0, 16);
    return s;
  }

  function computeOpenPathMinMax(open, bars, settings) {
    const mark = (open && open.mark) || {};
    const fromApiMin = Number(mark.pnl_min_rub);
    const fromApiMax = Number(mark.pnl_max_rub);
    const apiMinT = mark.pnl_min_time || null;
    const apiMaxT = mark.pnl_max_time || null;
    if (
      Number.isFinite(fromApiMin) && Number.isFinite(fromApiMax)
      && apiMinT && apiMaxT
    ) {
      return { min: fromApiMin, max: fromApiMax, minTime: apiMinT, maxTime: apiMaxT };
    }
    if (!open || !Array.isArray(bars) || !bars.length) {
      if (Number.isFinite(fromApiMin) && Number.isFinite(fromApiMax)) {
        return { min: fromApiMin, max: fromApiMax, minTime: apiMinT, maxTime: apiMaxT };
      }
      return { min: null, max: null, minTime: null, maxTime: null };
    }
    const entrySp = Number(mark.fill_spread != null ? mark.fill_spread : open.entry_spread);
    const entryMs = parseTradeMsOpen(open.entry_time);
    if (!Number.isFinite(entrySp) || entryMs == null) {
      if (Number.isFinite(fromApiMin) && Number.isFinite(fromApiMax)) {
        return { min: fromApiMin, max: fromApiMax, minTime: apiMinT, maxTime: apiMaxT };
      }
      return { min: null, max: null, minTime: null, maxTime: null };
    }
    const direction = String(open.direction || '').toUpperCase();
    const isLong = direction.includes('LONG');
    const notional = Number(mark.notional_rub ?? open.execution_notional_rub);
    const lev = Number(settings?.leverage ?? 7);
    const L = Number.isFinite(lev) && lev > 0 ? lev : 7;
    const eff = Number.isFinite(notional) && notional > 0
      ? notional
      : (Number(settings?.entry_deposit_rub) || 10000) * L;
    const deposit = Number.isFinite(notional) && notional > 0 ? notional / L : (eff / L);
    const commPerSide = eff * (0.04 / 100);
    // T‑Invest Премиум: ступени на короткую ногу (не deposit×(L−1)×0.033%).
    const fillTn = Number(mark.fill_tatn ?? open.entry_tatn);
    const fillTp = Number(mark.fill_tatnp ?? open.entry_tatnp);
    const lots = Number(open.quantity_lots) || 0;
    let uncovered = 0;
    if (lots > 0 && Number.isFinite(fillTn) && Number.isFinite(fillTp) && fillTn > 0 && fillTp > 0) {
      uncovered = isLong ? lots * fillTp : lots * fillTn;
    } else if (Number.isFinite(notional) && notional > 0) {
      uncovered = notional / 2;
    }
    const overnightPerDay = (() => {
      const u = Math.max(0, uncovered);
      if (u <= 0) return 0;
      if (u <= 5000) return 0;
      if (u <= 50000) return 35;
      if (u <= 100000) return 70;
      if (u <= 250000) return 175;
      if (u <= 500000) return 340;
      if (u <= 1000000) return 680;
      if (u <= 2500000) return 1700;
      if (u <= 5000000) return 3400;
      if (u <= 10000000) return 6800;
      if (u <= 25000000) return u * 0.00066;
      if (u <= 50000000) return u * 0.00063;
      return u * 0.00055;
    })();
    let min = Infinity;
    let max = -Infinity;
    let minTime = null;
    let maxTime = null;
    let found = false;
    for (const b of bars) {
      if (!b) continue;
      const ms = b.timestampMs != null ? Number(b.timestampMs) : parseTradeMsOpen(b.time || b.tradeDate);
      if (!Number.isFinite(ms) || ms < entryMs) continue;
      const sp = Number(b.spreadPercent != null ? b.spreadPercent : b.spread);
      if (!Number.isFinite(sp)) continue;
      const barTime = b.tradeDate || b.time || '';
      const pnlPts = isLong ? (sp - entrySp) : (entrySp - sp);
      const gross = eff * (pnlPts / 100);
      const ovn = overnightPerDay * overnightDaysOpen(open.entry_time, barTime);
      const net = gross - commPerSide - ovn;
      found = true;
      if (net < min) {
        min = net;
        minTime = barTime || null;
      }
      if (net > max) {
        max = net;
        maxTime = barTime || null;
      }
    }
    if (!found) {
      if (Number.isFinite(fromApiMin) && Number.isFinite(fromApiMax)) {
        return { min: fromApiMin, max: fromApiMax, minTime: apiMinT, maxTime: apiMaxT };
      }
      return { min: null, max: null, minTime: null, maxTime: null };
    }
    // Prefer API rub magnitudes when present (parity), keep path times for UI.
    return {
      min: Number.isFinite(fromApiMin) ? fromApiMin : min,
      max: Number.isFinite(fromApiMax) ? fromApiMax : max,
      minTime: apiMinT || minTime,
      maxTime: apiMaxT || maxTime,
    };
  }

  function fmtOpenMinMax(rub, depositRub, atTime) {
    if (rub == null || !Number.isFinite(Number(rub))) return { text: '—', cls: '' };
    const v = Number(rub);
    const cls = v > 0 ? 'pnl-pos' : v < 0 ? 'pnl-neg' : '';
    let text = `≈ ${fmtRub(v)}`;
    const base = Number(depositRub);
    const pct = Number.isFinite(base) && base > 0 ? fmtStakePct((v / base) * 100) : '';
    const when = compactExtremeDt(atTime);
    if (pct && when) text += ` (${pct}, ${when})`;
    else if (pct) text += ` (${pct})`;
    else if (when) text += ` (${when})`;
    return { text, cls };
  }

  function fmtPnlWithDepositPct(rub, depositRub) {
    if (rub == null || !Number.isFinite(Number(rub))) return '—';
    const v = Number(rub);
    let text = fmtRub(v);
    const base = Number(depositRub);
    if (Number.isFinite(base) && base > 0) {
      text += ` (${fmtStakePct((v / base) * 100)})`;
    }
    return text;
  }

    const box = $('tradeOpenBox');
    if (!open) {
      box.innerHTML = '<div class="trade-open-empty">Нет открытой позиции</div>';
      clearProfitAlertBadge();
      return;
    }
    const m = open.mark || {};
    const displayPnl = openProfitRub(open);
    const pnlCls = (displayPnl ?? m.unrealized_pnl_rub ?? 0) >= 0 ? 'pnl-pos' : 'pnl-neg';
    const netCls = (m.net_approx_rub ?? displayPnl ?? 0) >= 0 ? 'pnl-pos' : 'pnl-neg';
    const spreadEntry = m.fill_spread != null ? m.fill_spread : open.entry_spread;
    const spreadLabel = m.pnl_source === 'tinkoff_expected_yield'
      ? 'Спред'
      : (m.pnl_source === 'broker_fills' ? 'Спред (fill→сейч)' : 'Спред');
    const pnlNote = m.pnl_source === 'tinkoff_expected_yield'
      ? 'PnL = expectedYield Тинькофф'
      : (m.pnl_source === 'broker_fills'
        ? 'по fill vs котировки (не брокер)'
        : (String(barsMode) === 'dealer_1m' ? 'по спреду дилер 1м' : 'по спреду ISS'));
    const pathMm = computeOpenPathMinMax(open, bars || [], settings || {});
    const deposit = entryDepositRub(open, settings);
    const fundsNow = Number(lastGoodBroker?.total_rub);
    const atOpen = equityAtOpenRub(open, {
      fundsTotal: Number.isFinite(fundsNow) ? fundsNow : null,
    });
    const minLine = fmtOpenMinMax(pathMm.min, deposit, pathMm.minTime);
    const maxLine = fmtOpenMinMax(pathMm.max, deposit, pathMm.maxTime);
    const depLabel = Number.isFinite(deposit) && deposit > 0
      ? `от вложения ${fmt(deposit, 0)} ₽`
      : '% от депозита';
    const profitHit = isOpenProfitAlertHit(open, settings);
    const srcRaw = String(open.source || '');
    const srcUp = srcRaw.toUpperCase();
    const isManual = isManualTradeSource(open.source);
    const srcLabel = isManual
      ? (srcUp === 'BROKER' ? 'ручная (брокер)' : 'ручная')
      : (srcRaw || 'AUTO');
    const dirCls = isManual ? 'trade-open-dir trade-open-dir--manual' : 'trade-open-dir';
    const srcBadge = isManual
      ? ` <span class="trade-manual-badge">${escapeHtml(srcLabel)}</span>`
      : ` · ${escapeHtml(srcLabel)}`;
    const manualPlaque = isManual
      ? `<div class="trade-manual-plaque" role="status">MANUAL · не AUTO</div>`
      : '';
    const risk = sanitizeOpenRisk(m, settings);
    const riskCls = risk.red ? 'risk-red' : (risk.level === 'Elevated' ? 'risk-warn' : 'risk-ok');
    const entryComment = String(open.entry_comment || '').trim();
    const commentHtml = entryComment
      ? `<div class="trade-open-comment"><span class="trade-open-comment-label">Комментарий</span>${escapeHtml(entryComment)}</div>`
      : '';
    box.innerHTML =
      `<div class="${dirCls}">${escapeHtml(String(open.direction || ''))} · ${open.quantity_lots}+${open.quantity_lots} лот${srcBadge}</div>` +
      manualPlaque +
      commentHtml +
      (profitHit
        ? `<div class="trade-profit-alert" role="status">Прибыль ≥${PROFIT_ALERT_PCT}% ${depLabel}</div>`
        : '') +
      `<div class="trade-open-grid">` +
      `<span>Вход</span><b>${open.entry_time || '—'}</b>` +
      `<span>${spreadLabel}</span><b>${fmt(spreadEntry)}% → ${fmt(m.spread_now)}%</b>` +
      (m.entry_slip_pts != null
        ? `<span>Slip вх</span><b>${fmt(m.entry_slip_pts, 2)} п.п.</b>`
        : '') +
      (m.fill_tatn != null
        ? `<span>Fill TATN/P</span><b>${fmt(m.fill_tatn, 2)} / ${fmt(m.fill_tatnp, 2)}</b>`
        : '') +
      `<span>Notional</span><b>${fmt(m.notional_rub, 0)} ₽</b>` +
      `<span>Депозит</span><b>${fmt(deposit, 0)} ₽</b>` +
      (atOpen != null
        ? `<span title="Сумма на счету на входе (база Чист.)">До</span><b>${fmt(atOpen, 0)} ₽</b>`
        : '') +
      `<span>PnL</span><b class="${pnlCls}">${fmtPnlWithDepositPct(displayPnl, deposit)}</b>` +
      `<span>Нетто</span><b class="${netCls}">${fmtPnlWithDepositPct(m.net_approx_rub ?? displayPnl, deposit)}</b>` +
      `<span>Min</span><b class="${minLine.cls}">${minLine.text}</b>` +
      `<span>Max</span><b class="${maxLine.cls}">${maxLine.text}</b>` +
      `<span>Овн</span><b>${fmtRub(m.overnight_rub)} · ${m.overnight_days || 0}д</b>` +
      `<span>Hold</span><b>${m.hold_hours != null ? fmt(m.hold_hours, 1) + ' ч' : '—'}</b>` +
      `</div>` +
      `<div class="trade-open-pnl-src meta">${pnlNote} · % ${depLabel}</div>` +
      `<div class="trade-risk ${riskCls}">Риск ${risk.level || '—'} · score ${risk.score ?? '—'}` +
      (risk.flags && risk.flags.length ? ` · ${risk.flags.join(', ')}` : '') +
      `</div>` +
      `<div class="trade-open-stats-mini meta" id="tradeOpenStatsMini">ориентиры — см. блок под графиками</div>`;
    maybeFireProfitAlert(open, settings);
  }

  function fmtRubShort(n) {
    if (n == null || Number.isNaN(Number(n))) return '—';
    const v = Number(n);
    const sign = v > 0 ? '+' : '';
    if (Math.abs(v) >= 1000) return `${sign}${(v / 1000).toFixed(1)}k ₽`;
    return `${sign}${v.toFixed(0)} ₽`;
  }

  let openStatsTabBound = false;
  function bindOpenStatsTabs() {
    if (openStatsTabBound) return;
    const tabs = $('tradeOpenStatsTabs');
    if (!tabs) return;
    openStatsTabBound = true;
    tabs.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-tos-tab]');
      if (!btn) return;
      const id = btn.getAttribute('data-tos-tab');
      tabs.querySelectorAll('[data-tos-tab]').forEach((b) => {
        const on = b === btn;
        b.classList.toggle('active', on);
        b.setAttribute('aria-selected', on ? 'true' : 'false');
      });
      document.querySelectorAll('#tradeOpenStats [data-tos-pane]').forEach((pane) => {
        const on = pane.getAttribute('data-tos-pane') === id;
        pane.classList.toggle('active', on);
        if (on) pane.removeAttribute('hidden');
        else pane.setAttribute('hidden', '');
      });
      const body = $('tradeOpenStatsBody');
      if (body) body.scrollTop = 0;
    });
  }

  const MID_TAB_IDS = new Set(['check', 'params']);
  let midTabsBound = false;
  function setMidTab(id) {
    const tabId = MID_TAB_IDS.has(id) ? id : 'check';
    const tabs = $('tradeMidTabs');
    if (!tabs) return;
    tabs.querySelectorAll('[data-mid-tab]').forEach((b) => {
      const on = b.getAttribute('data-mid-tab') === tabId;
      b.classList.toggle('active', on);
      b.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    document.querySelectorAll('#tradeMidTabsPanel [data-mid-pane]').forEach((pane) => {
      const on = pane.getAttribute('data-mid-pane') === tabId;
      pane.classList.toggle('active', on);
      if (on) pane.removeAttribute('hidden');
      else pane.setAttribute('hidden', '');
    });
  }
  function bindMidTabs() {
    if (midTabsBound) return;
    const tabs = $('tradeMidTabs');
    if (!tabs) return;
    midTabsBound = true;
    let saved = 'check';
    try {
      const raw = localStorage.getItem(LS_MID_TAB) || 'check';
      if (MID_TAB_IDS.has(raw)) saved = raw;
    } catch (_) { /* */ }
    setMidTab(saved);
    tabs.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-mid-tab]');
      if (!btn || !tabs.contains(btn)) return;
      const id = btn.getAttribute('data-mid-tab');
      if (!MID_TAB_IDS.has(id)) return;
      setMidTab(id);
      try { localStorage.setItem(LS_MID_TAB, id); } catch (_) { /* */ }
    });
  }

  const SIDE_TAB_IDS = new Set(['account', 'situation']);
  let sideTabsBound = false;
  function setSideTab(id) {
    const tabId = SIDE_TAB_IDS.has(id) ? id : 'account';
    const tabs = $('tradeSideTabs');
    if (!tabs) return;
    tabs.querySelectorAll('[data-side-tab]').forEach((b) => {
      const on = b.getAttribute('data-side-tab') === tabId;
      b.classList.toggle('active', on);
      b.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    document.querySelectorAll('#tradeSidePanel [data-side-pane]').forEach((pane) => {
      const on = pane.getAttribute('data-side-pane') === tabId;
      pane.classList.toggle('active', on);
      if (on) pane.removeAttribute('hidden');
      else pane.setAttribute('hidden', '');
    });
  }
  function bindSideTabs() {
    if (sideTabsBound) return;
    const tabs = $('tradeSideTabs');
    if (!tabs) return;
    sideTabsBound = true;
    let saved = 'account';
    try {
      const raw = localStorage.getItem(LS_SIDE_TAB) || 'account';
      if (SIDE_TAB_IDS.has(raw)) saved = raw;
    } catch (_) { /* */ }
    setSideTab(saved);
    tabs.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-side-tab]');
      if (!btn || !tabs.contains(btn)) return;
      const id = btn.getAttribute('data-side-tab');
      if (!SIDE_TAB_IDS.has(id)) return;
      setSideTab(id);
      try { localStorage.setItem(LS_SIDE_TAB, id); } catch (_) { /* */ }
    });
  }

  let midPanelCollapseBound = false;
  function isMidPanelCollapsed() {
    return !!$('tradeView')?.classList.contains('mid-panel-collapsed');
  }
  function setMidPanelCollapsed(collapsed) {
    const desk = $('tradeView');
    const collapseBtn = $('btnCollapseMidPanel');
    const restoreBtn = $('btnRestoreMidPanel');
    if (!desk) return;
    desk.classList.toggle('mid-panel-collapsed', !!collapsed);
    if (collapseBtn) {
      collapseBtn.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
      collapseBtn.title = collapsed ? 'Показать панель' : 'Скрыть панель вправо';
    }
    if (restoreBtn) restoreBtn.hidden = !collapsed;
    try {
      if (collapsed) localStorage.setItem(LS_MID_PANEL_COLLAPSED, '1');
      else localStorage.removeItem(LS_MID_PANEL_COLLAPSED);
    } catch (_) { /* */ }
    resize();
    requestAnimationFrame(resize);
    setTimeout(resize, 120);
  }
  function bindMidPanelCollapse() {
    if (midPanelCollapseBound) return;
    const collapseBtn = $('btnCollapseMidPanel');
    const restoreBtn = $('btnRestoreMidPanel');
    if (!collapseBtn && !restoreBtn) return;
    midPanelCollapseBound = true;
    let saved = false;
    try { saved = localStorage.getItem(LS_MID_PANEL_COLLAPSED) === '1'; } catch (_) { /* */ }
    setMidPanelCollapsed(saved);
    collapseBtn?.addEventListener('click', () => setMidPanelCollapsed(true));
    restoreBtn?.addEventListener('click', () => setMidPanelCollapsed(false));
  }

  let openStatsCollapseBound = false;
  function isOpenStatsCollapsed() {
    return !!$('tradeOpenStats')?.classList.contains('is-collapsed');
  }
  function setOpenStatsCollapsed(collapsed) {
    const root = $('tradeOpenStats');
    if (!root) return;
    root.classList.toggle('is-collapsed', !!collapsed);
    const btn = $('btnCollapseTradeOpenStats');
    if (!btn) return;
    btn.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
    btn.textContent = collapsed ? '+' : '−';
    btn.title = collapsed ? 'Показать' : 'Скрыть';
  }
  function bindOpenStatsCollapse() {
    if (openStatsCollapseBound) return;
    openStatsCollapseBound = true;
    let saved = false;
    try { saved = localStorage.getItem(LS_OPEN_STATS_HIDDEN) === '1'; } catch (_) {}
    setOpenStatsCollapsed(saved);
    $('btnCollapseTradeOpenStats')?.addEventListener('click', () => {
      const next = !isOpenStatsCollapsed();
      setOpenStatsCollapsed(next);
      try { localStorage.setItem(LS_OPEN_STATS_HIDDEN, next ? '1' : ''); } catch (_) {}
    });
  }

  const TRADE_FS_PANES = {
    z: { paneId: 'tradeZPane', btnId: 'btnExpandTradeZ' },
    spread: { paneId: 'tradeSpreadPane', btnId: 'btnExpandTradeSpread' },
  };

  function isTradeChartFullscreen(which) {
    const cfg = TRADE_FS_PANES[which];
    return !!(cfg && $(cfg.paneId)?.classList.contains('is-fullscreen'));
  }

  function activeTradeChartFullscreen() {
    if (isTradeChartFullscreen('z')) return 'z';
    if (isTradeChartFullscreen('spread')) return 'spread';
    return null;
  }

  function syncTradeFsButton(which, on) {
    const cfg = TRADE_FS_PANES[which];
    const btn = cfg && $(cfg.btnId);
    if (!btn) return;
    btn.setAttribute('aria-pressed', on ? 'true' : 'false');
    btn.title = on ? 'Свернуть с экрана (Esc)' : 'На весь экран';
    btn.textContent = on ? '✕' : '⛶';
  }

  function scheduleTradeFsResize() {
    requestAnimationFrame(() => {
      resize();
      requestAnimationFrame(() => {
        resize();
        setTimeout(resize, 100);
      });
    });
  }

  function setTradeChartFullscreen(which, on) {
    const cfg = TRADE_FS_PANES[which];
    if (!cfg) return;
    const pane = $(cfg.paneId);
    if (!pane) return;
    const next = !!on;
    if (next) {
      const other = which === 'z' ? 'spread' : 'z';
      if (isTradeChartFullscreen(other)) setTradeChartFullscreen(other, false);
    }
    pane.classList.toggle('is-fullscreen', next);
    syncTradeFsButton(which, next);
    const anyFs = !!(isTradeChartFullscreen('z') || isTradeChartFullscreen('spread'));
    document.body.classList.toggle('trade-chart-fs-open', anyFs);
    scheduleTradeFsResize();
  }

  let tradeChartFsBound = false;
  function bindTradeChartFullscreen() {
    if (tradeChartFsBound) return;
    tradeChartFsBound = true;
    $('btnExpandTradeZ')?.addEventListener('click', () => {
      setTradeChartFullscreen('z', !isTradeChartFullscreen('z'));
    });
    $('btnExpandTradeSpread')?.addEventListener('click', () => {
      setTradeChartFullscreen('spread', !isTradeChartFullscreen('spread'));
    });
    document.addEventListener('keydown', (e) => {
      if (e.key !== 'Escape') return;
      const active = activeTradeChartFullscreen();
      if (!active) return;
      setTradeChartFullscreen(active, false);
    });
    if (typeof ResizeObserver !== 'undefined') {
      ['tradeZChart', 'tradeSpreadChart'].forEach((id) => {
        const el = $(id);
        if (!el) return;
        const ro = new ResizeObserver(() => {
          if (document.getElementById('app')?.dataset?.view !== 'trade') return;
          resize();
        });
        ro.observe(el);
      });
    }
  }

  /** Не перехватывать PgUp/PgDn/стрелки, пока фокус в поле ввода (депозит, параметры…). */
  function isTradeTypingTarget(el) {
    if (!el || el === document.body || el === document.documentElement) return false;
    const tag = (el.tagName || '').toLowerCase();
    if (tag === 'input' || tag === 'textarea' || tag === 'select') return true;
    if (el.isContentEditable) return true;
    return !!(el.closest && el.closest('input, textarea, select, [contenteditable="true"]'));
  }

  function getTradeLogicalRange() {
    try {
      const r = zChart?.timeScale()?.getVisibleLogicalRange();
      if (r && Number.isFinite(r.from) && Number.isFinite(r.to) && r.to > r.from) {
        return { from: r.from, to: r.to };
      }
    } catch (_) { /* ignore */ }
    if (pinnedRange && Number.isFinite(pinnedRange.from) && Number.isFinite(pinnedRange.to)
      && pinnedRange.to > pinnedRange.from) {
      return { from: pinnedRange.from, to: pinnedRange.to };
    }
    return null;
  }

  /**
   * Клавиатура Trade Desk: PgDn/PgUp — на экран вперёд/назад по времени;
   * → конец (свежие), ← начало (старые). Z и Спред через applyVisibleRange.
   * @param {'pageForward'|'pageBack'|'end'|'start'} action
   */
  function navigateTradeChartByKeyboard(action) {
    if (!zChart || lastBarCount <= 0) return false;
    const cur = getTradeLogicalRange();
    if (!cur) return false;
    const dataEnd = lastDataEnd != null ? lastDataEnd : dataEndIndex(lastBarCount);
    let span = Math.max(1, cur.to - cur.from);
    if (span > dataEnd) span = Math.max(1, dataEnd);

    let from;
    let to;
    if (action === 'pageForward') {
      from = cur.from + span;
      to = cur.to + span;
    } else if (action === 'pageBack') {
      from = cur.from - span;
      to = cur.to - span;
    } else if (action === 'end') {
      to = dataEnd;
      from = to - span;
    } else if (action === 'start') {
      from = 0;
      to = span;
    } else {
      return false;
    }

    if (from < 0) {
      from = 0;
      to = span;
    }
    if (to > dataEnd) {
      to = dataEnd;
      from = to - span;
      if (from < 0) from = 0;
    }
    if (!(to > from)) return false;

    markUserGesture();
    setPinnedRange({ from, to }, { fromUser: true });
    applyVisibleRange({ from, to });
    return true;
  }

  let tradeChartKeyNavBound = false;
  function bindTradeChartKeyboardNav() {
    if (tradeChartKeyNavBound) return;
    tradeChartKeyNavBound = true;
    const stack = $('tradeChartStack');
    if (stack && stack.tabIndex < 0) stack.tabIndex = 0;
    ['tradeZChart', 'tradeSpreadChart'].forEach((id) => {
      const el = $(id);
      if (el && el.tabIndex < 0) el.tabIndex = 0;
    });
    document.addEventListener('keydown', (e) => {
      if (document.getElementById('app')?.dataset?.view !== 'trade') return;
      if (e.ctrlKey || e.altKey || e.metaKey) return;
      if (isTradeTypingTarget(e.target)) return;
      let action = null;
      if (e.key === 'PageDown') action = 'pageForward';
      else if (e.key === 'PageUp') action = 'pageBack';
      else if (e.key === 'ArrowRight') action = 'end';
      else if (e.key === 'ArrowLeft') action = 'start';
      if (!action) return;
      if (!navigateTradeChartByKeyboard(action)) return;
      e.preventDefault();
    });
  }

  function renderOpenStats(stats, open) {
    bindOpenStatsTabs();
    const root = $('tradeOpenStats');
    const mini = $('tradeOpenStatsMini');
    if (!root) return;
    if (!open || !stats || !stats.ok) {
      root.classList.add('hidden');
      if (mini) mini.textContent = stats && stats.error
        ? `статистика: ${stats.error}`
        : '';
      return;
    }
    root.classList.remove('hidden');
    const s = stats.summary || {};
    const meta = $('tradeOpenStatsMeta');
    if (meta) {
      meta.textContent =
        `${stats.direction || ''} · ${s.trade_count || 0} сделок sim` +
        (stats.params?.slippage_spread_pts != null
          ? ` · slip ${stats.params.slippage_spread_pts}`
          : '');
    }
    const kpis = $('tradeOpenStatsKpis');
    if (kpis) {
      const wr = s.win_rate_pct != null ? `${fmt(s.win_rate_pct, 0)}%` : '—';
      kpis.innerHTML =
        `<div class="tos-kpi"><span>До плюса (мед.)</span><b>${s.median_hold_winners_label || '—'}</b></div>` +
        `<div class="tos-kpi"><span>Ср. PnL (все)</span><b class="${(s.avg_pnl_rub || 0) >= 0 ? 'pnl-pos' : 'pnl-neg'}">${fmtRubShort(s.avg_pnl_rub)}</b></div>` +
        `<div class="tos-kpi"><span>Ср. PnL (+)</span><b class="pnl-pos">${fmtRubShort(s.avg_win_rub)}</b></div>` +
        `<div class="tos-kpi"><span>Win rate</span><b>${wr}</b></div>` +
        `<div class="tos-kpi"><span>MAE мед.</span><b class="pnl-neg">${fmtRubShort(s.median_mae_rub)}</b></div>` +
        `<div class="tos-kpi"><span>P+ 1ч / 1д</span><b>${(() => {
          const pp = stats.p_profit || [];
          const h1 = pp.find((x) => x.label === '1ч');
          const d1 = pp.find((x) => x.label === '1д');
          const a = h1 && h1.pct_in_profit != null ? fmt(h1.pct_in_profit, 0) + '%' : '—';
          const b = d1 && d1.pct_in_profit != null ? fmt(d1.pct_in_profit, 0) + '%' : '—';
          return `${a} / ${b}`;
        })()}</b></div>`;
      kpis.classList.add('tos-kpis-6');
    }
    if (mini) {
      mini.textContent =
        `история: до плюса ${s.median_hold_winners_label || '—'} · ср. PnL ${fmtRubShort(s.avg_pnl_rub)} · WR ${s.win_rate_pct != null ? fmt(s.win_rate_pct, 0) + '%' : '—'}`;
    }

    const hbar = $('tradeStatsHbar');
    if (hbar) {
      const rows = stats.typical_mtm || [];
      const maxAbs = Math.max(
        0.01,
        ...rows.map((r) => Math.abs(Number(r.typical_pnl_rub) || 0)),
      );
      hbar.innerHTML = rows.map((r) => {
        const rub = Number(r.typical_pnl_rub);
        const w = Number.isFinite(rub) ? Math.min(100, (Math.abs(rub) / maxAbs) * 100) : 0;
        const pp = r.median_fav_pp != null ? fmt(r.median_fav_pp, 2) : '—';
        const pct = r.pct_in_profit != null ? ` · ${fmt(r.pct_in_profit, 0)}% в плюсе` : '';
        const cls = rub >= 0 ? 'pos' : 'neg';
        return (
          `<div class="tos-hrow">` +
          `<span class="tos-hlab">${r.label}</span>` +
          `<div class="tos-htrack"><div class="tos-hfill ${cls}" style="width:${w}%"></div></div>` +
          `<span class="tos-hval">~${fmtRubShort(rub)} · ${pp} п.п.${pct}</span>` +
          `</div>`
        );
      }).join('');
    }

    const vbar = $('tradeStatsVbar');
    if (vbar) {
      const rows = stats.spread_move || [];
      const maxY = Math.max(
        0.05,
        ...rows.flatMap((r) => [Number(r.median_abs_pp) || 0, Number(r.p90_abs_pp) || 0]),
      );
      vbar.innerHTML =
        `<div class="tos-vgrid">` +
        rows.map((r) => {
          const med = Number(r.median_abs_pp) || 0;
          const p90 = Number(r.p90_abs_pp) || 0;
          const hm = Math.max(4, (med / maxY) * 100);
          const hp = Math.max(4, (p90 / maxY) * 100);
          return (
            `<div class="tos-vcol" title="n=${r.n || 0}">` +
            `<div class="tos-vpair">` +
            `<div class="tos-vbar-med" style="height:${hm}%"></div>` +
            `<div class="tos-vbar-p90" style="height:${hp}%"></div>` +
            `</div>` +
            `<span class="tos-vlab">${r.label}</span>` +
            `</div>`
          );
        }).join('') +
        `</div>`;
    }

    const hint = $('tradeOpenStatsHint');
    if (hint) hint.textContent = stats.hint || '';

    const ppBox = $('tradeStatsPProfit');
    if (ppBox) {
      const rows = stats.p_profit || [];
      const maxP = Math.max(1, ...rows.map((r) => Number(r.pct_in_profit) || 0));
      ppBox.innerHTML =
        `<div class="tos-vgrid">` +
        rows.map((r) => {
          const p = Number(r.pct_in_profit) || 0;
          const h = Math.max(4, (p / maxP) * 100);
          const hi = r.label === '1ч' || r.label === '1д' ? ' is-hi' : '';
          return (
            `<div class="tos-vcol${hi}" title="n=${r.n || 0}">` +
            `<div class="tos-vpair tos-vpair-single">` +
            `<div class="tos-vbar-med" style="height:${h}%"></div>` +
            `</div>` +
            `<span class="tos-vlab">${r.label}</span>` +
            `<span class="tos-vpct">${p ? fmt(p, 0) + '%' : '—'}</span>` +
            `</div>`
          );
        }).join('') +
        `</div>`;
    }

    const maeBox = $('tradeStatsMae');
    if (maeBox) {
      const mae = stats.mae || {};
      maeBox.innerHTML =
        `<div class="tos-kpi"><span>Медиана Min</span><b class="pnl-neg">${fmtRubShort(mae.median_rub)}</b></div>` +
        `<div class="tos-kpi"><span>Редко p10</span><b class="pnl-neg">${fmtRubShort(mae.p10_rub)}</b></div>` +
        `<div class="tos-kpi"><span>Среднее Min</span><b class="pnl-neg">${fmtRubShort(mae.mean_rub)}</b></div>` +
        `<div class="tos-kpi"><span>n</span><b>${mae.n != null ? mae.n : '—'}</b></div>`;
    }

    const ovnBox = $('tradeStatsOvn');
    if (ovnBox) {
      const rows = stats.overnight_share || [];
      ovnBox.innerHTML = rows.map((r) => {
        const p = Number(r.median_overnight_share_pct);
        const w = Number.isFinite(p) ? Math.min(100, Math.max(0, p)) : 0;
        return (
          `<div class="tos-hrow">` +
          `<span class="tos-hlab" title="${r.label}">${r.label}</span>` +
          `<div class="tos-htrack"><div class="tos-hfill" style="width:${w}%"></div></div>` +
          `<span class="tos-hval">${Number.isFinite(p) ? fmt(p, 0) + '%' : '—'} · n=${r.n || 0}</span>` +
          `</div>`
        );
      }).join('');
    }

    const slipBox = $('tradeStatsSlip');
    if (slipBox) {
      const rows = stats.slip_sensitivity || [];
      slipBox.innerHTML =
        `<div class="tos-slip-head"><span>Slip</span><span>WR</span><span>Ср.PnL</span><span>До+</span></div>` +
        rows.map((r) => (
          `<div class="tos-slip-row">` +
          `<span>${fmt(r.slip, 2)}</span>` +
          `<span>${r.win_rate_pct != null ? fmt(r.win_rate_pct, 0) + '%' : '—'}</span>` +
          `<span class="${(r.avg_pnl_rub || 0) >= 0 ? 'pnl-pos' : 'pnl-neg'}">${fmtRubShort(r.avg_pnl_rub)}</span>` +
          `<span>${r.median_hold_winners_label || '—'}</span>` +
          `</div>`
        )).join('');
    }

    function paintHit(boxId, metaId, hit) {
      const box = $(boxId);
      const meta = $(metaId);
      if (!box || !hit) return;
      const buckets = hit.buckets || [];
      const maxC = Math.max(1, ...buckets.map((b) => Number(b.count) || 0));
      box.innerHTML = buckets.map((b) => {
        const c = Number(b.count) || 0;
        const w = (c / maxC) * 100;
        return (
          `<div class="tos-hrow">` +
          `<span class="tos-hlab" title="${b.label}">${b.label}</span>` +
          `<div class="tos-htrack"><div class="tos-hfill" style="width:${w}%"></div></div>` +
          `<span class="tos-hval">${c} · ${b.pct != null ? fmt(b.pct, 0) + '%' : '—'}</span>` +
          `</div>`
        );
      }).join('');
      if (meta) {
        meta.textContent =
          `hit ${hit.hit_count || 0}/${hit.n || 0}` +
          (hit.hit_rate_pct != null ? ` (${fmt(hit.hit_rate_pct, 0)}%)` : '') +
          ` · медиана ${hit.median_label || '—'}` +
          (hit.miss_count ? ` · без hit ${hit.miss_count}` : '');
      }
    }
    paintHit('tradeStatsHit1', 'tradeStatsHit1Meta', stats.hit1);
    paintHit('tradeStatsHit2', 'tradeStatsHit2Meta', stats.hit2);
  }

  function modeLabel(mode) {
    return mode === 'prod' ? 'Боевой (Prod)' : 'Песочница';
  }

  function brokerHasTotals(broker) {
    if (!broker || broker.error) return false;
    return Number.isFinite(Number(broker.total_rub))
      || Number.isFinite(Number(broker.cash_rub));
  }

  /**
   * Lite/full race: lite may omit broker while full previously painted funds.
   * Keep last good unless full confirms logout/no-token, or N empty polls, or
   * a persistent broker.error (after streak). Mutates data.broker in place.
   */
  function coalesceDeskBroker(data) {
    if (!data || typeof data !== 'object') return data;
    const incoming = data.broker;
    const lite = !!data.lite;

    if (brokerHasTotals(incoming)) {
      lastGoodBroker = {
        mode: incoming.mode,
        total_rub: incoming.total_rub,
        cash_rub: incoming.cash_rub,
        margin: incoming.margin ?? lastGoodBroker?.margin ?? null,
      };
      brokerEmptyStreak = 0;
      return data;
    }

    const missing = incoming == null;
    const isErr = !!(incoming && incoming.error);

    // Full desk with no broker object ⇒ logout / нет токена — clear.
    // Skip partial/timeout stubs — they omit broker without meaning logout.
    if (!lite && missing && !data.partial) {
      lastGoodBroker = null;
      brokerEmptyStreak = BROKER_EMPTY_CLEAR_AFTER;
      return data;
    }

    brokerEmptyStreak += 1;

    if (lastGoodBroker && brokerEmptyStreak < BROKER_EMPTY_CLEAR_AFTER) {
      // Keep painted totals; do not flash «…» on lite cold / brief error.
      data.broker = lastGoodBroker;
      data.broker_from_last_good = true;
      return data;
    }

    if (isErr && brokerEmptyStreak >= BROKER_EMPTY_CLEAR_AFTER) {
      lastGoodBroker = null;
      return data;
    }

    if (missing && brokerEmptyStreak >= BROKER_EMPTY_CLEAR_AFTER) {
      lastGoodBroker = null;
    }
    return data;
  }

  /** Short RU hint instead of urllib3/SSL traceback spam. */
  function paramsFocused() {
    const ae = document.activeElement;
    return !!(ae && (ae.id === 'tradeLeverage' || ae.id === 'tradeAutoExec'
      || ae.id === 'tradeTpSel'
      || ae.id === 'tradeEntryDeposit'
      || ae.id === 'tradeAddonDeposit'
      || ae.id === 'tradeExtraDeposit'
      || ae.id === 'tradeCompound'
      || ae.id === 'tradeSpreadEnterNarrow' || ae.id === 'tradeSpreadExitNarrow'
      || ae.id === 'tradeSpreadEnterWide' || ae.id === 'tradeSpreadExitWide'));
  }

  function setParamsStatus(msg, kind) {
    const el = $('tradeParamsStatus');
    if (!el) return;
    el.textContent = msg || '';
    el.classList.remove('is-ok', 'is-err', 'is-pending');
    if (kind) el.classList.add(`is-${kind}`);
    if (saveStatusTimer) clearTimeout(saveStatusTimer);
    if (msg && kind === 'ok') {
      saveStatusTimer = setTimeout(() => {
        if (el.textContent === msg) {
          el.textContent = '';
          el.classList.remove('is-ok');
        }
      }, 4000);
    }
  }

  function setDepositStatus(msg, kind) {
    const el = $('tradeDepositStatus');
    if (!el) return;
    el.textContent = msg || '';
    el.classList.remove('is-ok', 'is-err', 'is-pending');
    if (kind) el.classList.add(`is-${kind}`);
  }

  function readEntryDeposit() {
    const n = parseFloat(String($('tradeEntryDeposit')?.value || '').replace(',', '.'));
    if (!Number.isFinite(n)) return null;
    return Math.max(1000, Math.min(10_000_000, Math.round(n)));
  }

  function readNamedDeposit(id) {
    const n = parseFloat(String($(id)?.value || '').replace(',', '.'));
    if (!Number.isFinite(n)) return null;
    return Math.max(1000, Math.min(10_000_000, Math.round(n)));
  }

  function readMtlrDeposit() {
    const n = parseFloat(String($('tradeMtlrDeposit')?.value || '').replace(',', '.'));
    if (!Number.isFinite(n)) return null;
    return Math.max(1000, Math.min(10_000_000, Math.round(n)));
  }

  function readSpreadLevelInput(id, fallback) {
    const n = parseFloat(String($(id)?.value || '').replace(',', '.'));
    if (!Number.isFinite(n)) return fallback;
    return Math.max(0.1, Math.min(20, Math.round(n * 10) / 10));
  }

  function readFormParams() {
    const leverage = parseFloat(String($('tradeLeverage')?.value || '').replace(',', '.'));
    const tpRaw = parseFloat(String($('tradeTpSel')?.value || '0').replace(',', '.'));
    const tpAllowed = [0, 1, 2, 3];
    const takeProfit = tpAllowed.includes(tpRaw) ? tpRaw : 0;
    const noTrendRaw = parseInt(String($('tradeHoldNoTrendSel')?.value || '5'), 10);
    const losingRaw = parseInt(String($('tradeHoldLosingSel')?.value || '0'), 10);
    const holdNoTrend = [0, 5, 7, 10].includes(noTrendRaw) ? noTrendRaw : 5;
    const holdLosing = [0, 7, 10].includes(losingRaw) ? losingRaw : 0;
    // entry_z / exit_z / regime_z_mode не шлём с UI — серверные значения не трогаем.
    const params = {
      leverage: Number.isFinite(leverage) ? leverage : null,
      take_profit_pct: takeProfit,
      max_hold_days_no_exit_trend: holdNoTrend,
      max_hold_days_if_losing: holdLosing,
      addon_mode: !!$('tradeAddonMode')?.checked,
      addon_enter_narrow: readSpreadLevelInput('tradeAddonEnterNarrow', 2.0),
      addon_exit_narrow: readSpreadLevelInput('tradeAddonExitNarrow', 3.2),
      addon_enter_wide: readSpreadLevelInput('tradeAddonEnterWide', 7.0),
      addon_exit_wide: readSpreadLevelInput('tradeAddonExitWide', 6.1),
      extra_addon_mode: !!$('tradeExtraAddonMode')?.checked,
      extra_enter_narrow: readSpreadLevelInput('tradeExtraEnterNarrow', 1.0),
      extra_exit_narrow: readSpreadLevelInput('tradeExtraExitNarrow', 2.0),
      extra_enter_wide: readSpreadLevelInput('tradeExtraEnterWide', 9.0),
      extra_exit_wide: readSpreadLevelInput('tradeExtraExitWide', 7.0),
      auto_execute: !!$('tradeAutoExec')?.checked,
      spread_level_mode: true,
      entry_deposit_rub: readEntryDeposit(),
      addon_deposit_rub: readNamedDeposit('tradeAddonDeposit'),
      extra_deposit_rub: readNamedDeposit('tradeExtraDeposit'),
      compound: !!$('tradeCompound')?.checked,
      spread_enter_narrow: readSpreadLevelInput('tradeSpreadEnterNarrow', 3.2),
      spread_exit_narrow: readSpreadLevelInput('tradeSpreadExitNarrow', 4.0),
      spread_enter_wide: readSpreadLevelInput('tradeSpreadEnterWide', 6.1),
      spread_exit_wide: readSpreadLevelInput('tradeSpreadExitWide', 5.8),
    };
    if (DESK_MTLR_UI_ENABLED) {
      params.mtlr_enabled = !!$('tradeMtlrEnabled')?.checked;
      params.mtlr_auto_execute = !!$('tradeMtlrAuto')?.checked;
      params.mtlr_deposit_rub = readMtlrDeposit();
    }
    return params;
  }

  function cacheParamsLocal(settings) {
    if (!settings) return;
    try {
      localStorage.setItem(LS_TRADE_PARAMS, JSON.stringify({
        entry_z: settings.entry_z,
        exit_z: settings.exit_z,
        leverage: settings.leverage,
        take_profit_pct: settings.take_profit_pct != null ? settings.take_profit_pct : 2,
        max_hold_days_no_exit_trend: settings.max_hold_days_no_exit_trend != null
          ? settings.max_hold_days_no_exit_trend : 5,
        max_hold_days_if_losing: settings.max_hold_days_if_losing != null
          ? settings.max_hold_days_if_losing : 0,
        addon_mode: settings.addon_mode !== false,
        addon_enter_narrow: settings.addon_enter_narrow != null ? settings.addon_enter_narrow : 2.0,
        addon_exit_narrow: settings.addon_exit_narrow != null ? settings.addon_exit_narrow : 3.2,
        addon_enter_wide: settings.addon_enter_wide != null ? settings.addon_enter_wide : 7.0,
        addon_exit_wide: settings.addon_exit_wide != null ? settings.addon_exit_wide : 6.1,
        extra_addon_mode: settings.extra_addon_mode !== false,
        extra_enter_narrow: settings.extra_enter_narrow != null ? settings.extra_enter_narrow : 1.0,
        extra_exit_narrow: settings.extra_exit_narrow != null ? settings.extra_exit_narrow : 2.0,
        extra_enter_wide: settings.extra_enter_wide != null ? settings.extra_enter_wide : 9.0,
        extra_exit_wide: settings.extra_exit_wide != null ? settings.extra_exit_wide : 7.0,
        auto_execute: !!settings.auto_execute,
        spread_level_mode: settings.spread_level_mode !== false,
        regime_z_mode: !!settings.regime_z_mode,
        entry_deposit_rub: settings.entry_deposit_rub != null ? settings.entry_deposit_rub : 40000,
        addon_deposit_rub: settings.addon_deposit_rub != null ? settings.addon_deposit_rub : 30000,
        extra_deposit_rub: settings.extra_deposit_rub != null ? settings.extra_deposit_rub : 30000,
        compound: settings.compound !== false,
        spread_enter_narrow: settings.spread_enter_narrow,
        spread_exit_narrow: settings.spread_exit_narrow,
        spread_enter_wide: settings.spread_enter_wide,
        spread_exit_wide: settings.spread_exit_wide,
      }));
    } catch (_) { /* ignore quota */ }
  }

  function loadCachedParamsLocal() {
    try {
      const raw = localStorage.getItem(LS_TRADE_PARAMS);
      if (!raw) return null;
      const o = JSON.parse(raw);
      if (!o || o.leverage == null) return null;
      if (localStorage.getItem('moexReplay.tradeSpreadEnterWideV61') !== '1') {
        const ew = Number(o.spread_enter_wide);
        if (!Number.isFinite(ew) || Math.abs(ew - 6.2) < 1e-9) o.spread_enter_wide = 6.1;
        const ax = Number(o.addon_exit_wide);
        if (Number.isFinite(ax) && Math.abs(ax - 6.2) < 1e-9) o.addon_exit_wide = 6.1;
        localStorage.setItem('moexReplay.tradeSpreadEnterWideV61', '1');
        try { localStorage.setItem(LS_TRADE_PARAMS, JSON.stringify(o)); } catch (_) { /* quota */ }
      }
      if (o.entry_deposit_rub == null) o.entry_deposit_rub = 10000;
      if (o.take_profit_pct == null) o.take_profit_pct = 2;
      if (o.max_hold_days_no_exit_trend == null) o.max_hold_days_no_exit_trend = 5;
      if (o.max_hold_days_if_losing == null) o.max_hold_days_if_losing = 0;
      if (o.addon_mode == null) o.addon_mode = true;
      if (o.addon_enter_narrow == null) o.addon_enter_narrow = 2.0;
      if (o.addon_exit_narrow == null) o.addon_exit_narrow = 3.2;
      if (o.addon_enter_wide == null) o.addon_enter_wide = 7.0;
      if (o.addon_exit_wide == null) o.addon_exit_wide = 6.1;
      if (o.extra_addon_mode == null) o.extra_addon_mode = true;
      if (o.extra_enter_narrow == null) o.extra_enter_narrow = 1.0;
      if (o.extra_exit_narrow == null) o.extra_exit_narrow = 2.0;
      if (o.extra_enter_wide == null) o.extra_enter_wide = 9.0;
      if (o.extra_exit_wide == null) o.extra_exit_wide = 7.0;
      if (o.addon_deposit_rub == null) o.addon_deposit_rub = 30000;
      if (o.extra_deposit_rub == null) o.extra_deposit_rub = 30000;
      if (o.compound == null) o.compound = true;
      return o;
    } catch (_) {
      return null;
    }
  }

  function applyParamsToForm(settings) {
    if (!settings) return;
    if (settings.leverage != null && $('tradeLeverage')) {
      $('tradeLeverage').value = String(settings.leverage);
    }
    if ($('tradeTpSel')) {
      const tp = settings.take_profit_pct != null ? Number(settings.take_profit_pct) : 2;
      const v = [0, 1, 2, 3].includes(tp) ? String(tp) : '2';
      $('tradeTpSel').value = v;
    }
    if ($('tradeHoldNoTrendSel')) {
      const n = settings.max_hold_days_no_exit_trend != null
        ? Number(settings.max_hold_days_no_exit_trend) : 5;
      const v = [0, 5, 7, 10].includes(n) ? String(n) : '5';
      $('tradeHoldNoTrendSel').value = v;
    }
    if ($('tradeHoldLosingSel')) {
      const n = settings.max_hold_days_if_losing != null
        ? Number(settings.max_hold_days_if_losing) : 0;
      const v = [0, 7, 10].includes(n) ? String(n) : '0';
      $('tradeHoldLosingSel').value = v;
    }
    if ($('tradeAddonMode')) {
      $('tradeAddonMode').checked = settings.addon_mode !== false;
    }
    if ($('tradeAddonEnterNarrow')) {
      $('tradeAddonEnterNarrow').value = String(
        settings.addon_enter_narrow != null ? settings.addon_enter_narrow : 2.0
      );
    }
    if ($('tradeAddonExitNarrow')) {
      $('tradeAddonExitNarrow').value = String(
        settings.addon_exit_narrow != null ? settings.addon_exit_narrow : 3.2
      );
    }
    if ($('tradeAddonEnterWide')) {
      $('tradeAddonEnterWide').value = String(
        settings.addon_enter_wide != null ? settings.addon_enter_wide : 7.0
      );
    }
    if ($('tradeAddonExitWide')) {
      $('tradeAddonExitWide').value = String(
        settings.addon_exit_wide != null ? settings.addon_exit_wide : 6.1
      );
    }
    if ($('tradeExtraAddonMode')) {
      $('tradeExtraAddonMode').checked = settings.extra_addon_mode !== false;
    }
    if ($('tradeExtraEnterNarrow')) {
      $('tradeExtraEnterNarrow').value = String(
        settings.extra_enter_narrow != null ? settings.extra_enter_narrow : 1.0
      );
    }
    if ($('tradeExtraExitNarrow')) {
      $('tradeExtraExitNarrow').value = String(
        settings.extra_exit_narrow != null ? settings.extra_exit_narrow : 2.0
      );
    }
    if ($('tradeExtraEnterWide')) {
      $('tradeExtraEnterWide').value = String(
        settings.extra_enter_wide != null ? settings.extra_enter_wide : 9.0
      );
    }
    if ($('tradeExtraExitWide')) {
      $('tradeExtraExitWide').value = String(
        settings.extra_exit_wide != null ? settings.extra_exit_wide : 7.0
      );
    }
    if ($('tradeEntryDeposit')) {
      const dep = settings.entry_deposit_rub != null ? settings.entry_deposit_rub : 40000;
      $('tradeEntryDeposit').value = String(dep);
    }
    if ($('tradeAddonDeposit')) {
      const dep = settings.addon_deposit_rub != null ? settings.addon_deposit_rub : 30000;
      $('tradeAddonDeposit').value = String(dep);
    }
    if ($('tradeExtraDeposit')) {
      const dep = settings.extra_deposit_rub != null ? settings.extra_deposit_rub : 30000;
      $('tradeExtraDeposit').value = String(dep);
    }
    if ($('tradeCompound')) {
      $('tradeCompound').checked = settings.compound !== false;
    }
    if ($('tradeAutoExec')) $('tradeAutoExec').checked = !!settings.auto_execute;
    const spEn = settings.spread_enter_narrow != null ? settings.spread_enter_narrow : 3.2;
    const spXn = settings.spread_exit_narrow != null ? settings.spread_exit_narrow : 4.0;
    const spEw = settings.spread_enter_wide != null ? settings.spread_enter_wide : 6.1;
    const spXw = settings.spread_exit_wide != null ? settings.spread_exit_wide : 5.8;
    if ($('tradeSpreadEnterNarrow')) $('tradeSpreadEnterNarrow').value = String(spEn);
    if ($('tradeSpreadExitNarrow')) $('tradeSpreadExitNarrow').value = String(spXn);
    if ($('tradeSpreadEnterWide')) $('tradeSpreadEnterWide').value = String(spEw);
    if ($('tradeSpreadExitWide')) $('tradeSpreadExitWide').value = String(spXw);
    if (DESK_MTLR_UI_ENABLED) {
      if ($('tradeMtlrEnabled')) {
        $('tradeMtlrEnabled').checked = settings.mtlr_enabled !== false;
      }
      if ($('tradeMtlrAuto')) {
        $('tradeMtlrAuto').disabled = false;
        $('tradeMtlrAuto').checked = !!settings.mtlr_auto_execute;
      }
      if ($('tradeMtlrDeposit')) {
        const dep = settings.mtlr_deposit_rub != null ? settings.mtlr_deposit_rub : 12000;
        $('tradeMtlrDeposit').value = String(dep);
      }
    }
  }

  /** Server (or LS backup) → inputs + chart lines. Skips if user is mid-edit. */
  function hydrateParams(settings, { force = false } = {}) {
    if (!settings) return false;
    if (!force && (formDirty || paramsFocused())) return false;
    applyParamsToForm(settings);
    cacheParamsLocal(settings);
    ensureCharts();
    applyThresholdVisuals(settings.entry_z, settings.exit_z, { settings });
    formHydrated = true;
    formDirty = false;
    return true;
  }

  async function hydrateParamsFromServer() {
    if (formDirty || paramsFocused()) return null;
    try {
      // lite: settings only — full /status waits on TInvest (~5s) and blocked Trade
      const data = await api('/api/live/status?lite=1');
      const settings = data.settings || {};
      if (settings.leverage != null || settings.spread_level_mode != null) {
        hydrateParams(settings, { force: true });
        return settings;
      }
    } catch (_) { /* fall through to LS */ }
    const cached = loadCachedParamsLocal();
    if (cached) hydrateParams(cached, { force: true });
    return cached;
  }

  function applyThresholdVisuals(entry, exitZ, {
    dealer1m = false,
    zEmpty = false,
    settings = null,
    spreadLevelsPayload = null,
  } = {}) {
    const e = Number(entry);
    const x = Number(exitZ);
    const entryN = Number.isFinite(e) && e > 0 ? e : 1.3;
    const exitN = Number.isFinite(x) && x > 0 ? x : 1.2;
    const spreadGuide = resolveSpreadLevelLines(settings, spreadLevelsPayload);
    const label = $('tradeThreshLabel');
    if (label) {
      if (dealer1m && zEmpty) {
        label.textContent = 'нет баров дилер 1м · ISS M15 не рисуем';
        label.classList.add('pnl-label-dealer');
      } else {
        const lv = spreadGuide.levels || {};
        const en = Number(lv.enter_narrow);
        const xn = Number(lv.exit_narrow);
        const xw = Number(lv.exit_wide);
        const ew = Number(lv.enter_wide);
        if ([en, xn, xw, ew].every((n) => Number.isFinite(n))) {
          label.textContent = `уровни L ${fmt(en, 1)}/${fmt(xn, 1)} · S ${fmt(xw, 1)}/${fmt(ew, 1)}`;
        } else {
          const cfg = spreadCfgSpread();
          label.textContent = `уровни L ${fmt(cfg.enter_narrow, 1)}/${fmt(cfg.exit_narrow, 1)} · S ${fmt(cfg.exit_wide, 1)}/${fmt(cfg.enter_wide, 1)}`;
        }
        label.classList.toggle('pnl-label-dealer', !!dealer1m);
      }
    }
    setPrimarySpreadThresholdLines(spreadGuide.levels);
    if (DESK_MTLR_UI_ENABLED) {
      setSpreadThresholdLines(lastMtlrLevels || {
        enter_wide: 8.9, exit_wide: 8.4, enter_narrow: 3.2, exit_narrow: 4.3,
      });
    }
    return { entry: entryN, exitZ: exitN, spreadLevels: spreadGuide.levels, spreadCuts: spreadGuide.cuts };
  }

  function markParamsDirty() {
    formDirty = true;
    if ($('tradeParamsStatus')?.classList.contains('is-ok')) setParamsStatus('');
  }

  function readScrollLs(key) {
    const n = parseInt(localStorage.getItem(key) || '0', 10);
    return Number.isFinite(n) && n > 0 ? n : 0;
  }

  function bindScrollPersist(el, key) {
    if (!el || el.dataset.scrollBound === '1') return;
    el.dataset.scrollBound = '1';
    let timer = 0;
    el.addEventListener('scroll', () => {
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => {
        timer = 0;
        try { localStorage.setItem(key, String(el.scrollTop | 0)); } catch (_) { /* */ }
      }, 80);
    }, { passive: true });
  }

  function restoreScroll(el, key) {
    if (!el) return;
    const top = readScrollLs(key);
    if (top <= 0) return;
    const apply = () => { el.scrollTop = top; };
    apply();
    requestAnimationFrame(() => {
      apply();
      requestAnimationFrame(apply);
    });
  }

  function bindTradeScrolls() {
    bindScrollPersist($('tradeChecklistPanel'), LS_CHECK_SCROLL);
    bindScrollPersist($('tradeSideAccountPane') || $('tradeSidePanel'), LS_SIDE_SCROLL);
    bindScrollPersist($('tradeView'), LS_DESK_SCROLL);
  }

  function restoreTradeScrolls() {
    restoreScroll($('tradeChecklistPanel'), LS_CHECK_SCROLL);
    restoreScroll($('tradeSideAccountPane') || $('tradeSidePanel'), LS_SIDE_SCROLL);
    restoreScroll($('tradeView'), LS_DESK_SCROLL);
  }

  function isTqbrSessionBar(tradeDate) {
    const s = String(tradeDate || '').replace('T', ' ').trim();
    if (s.length < 16) return false;
    const m = s.match(/^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})/);
    if (!m) return false;
    // tradeDate already MSK wall-clock
    const wd = new Date(+m[1], +m[2] - 1, +m[3]).getDay();
    const mins = (+m[4]) * 60 + (+m[5]);
    if (wd === 0 || wd === 6) return true;
    return mins >= 7 * 60 && mins < 23 * 60 + 50;
  }

  /** Бар в окне live-спреда (будни до 23:45; выходные — дилер OK). */
  function isSpreadLiveBar(tradeDateOrBar) {
    let s = '';
    if (tradeDateOrBar && typeof tradeDateOrBar === 'object') {
      s = String(tradeDateOrBar.time || tradeDateOrBar.tradeDate || tradeDateOrBar.trade_date || '');
    } else {
      s = String(tradeDateOrBar || '');
    }
    s = s.replace('T', ' ').trim();
    if (s.length < 16) return false;
    const m = s.match(/^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})/);
    if (!m) return false;
    const wd = new Date(+m[1], +m[2] - 1, +m[3]).getDay();
    if (wd === 0 || wd === 6) return true;
    const mins = (+m[4]) * 60 + (+m[5]);
    return mins >= 7 * 60 && mins < 23 * 60 + 45;
  }

  /** Последний бар спреда до отсечки 23:45 (не after-hours tip). */
  function lastSpreadLiveBar(bars) {
    const list = Array.isArray(bars) ? bars : [];
    for (let i = list.length - 1; i >= 0; i -= 1) {
      const b = list[i];
      if (!b) continue;
      const src = String(b.source || '');
      if (src === 'tinvest_dealer_1m_tip' && !isSpreadLiveBar(b)) continue;
      if (isSpreadLiveBar(b)) return b;
    }
    return list.length ? list[list.length - 1] : null;
  }

  function nowMskParts() {
    const parts = new Intl.DateTimeFormat('en-GB', {
      timeZone: MSK,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
      weekday: 'short',
    }).formatToParts(new Date());
    const get = (t) => parts.find((p) => p.type === t)?.value;
    const y = +get('year');
    const mo = +get('month');
    const d = +get('day');
    const h = +get('hour');
    const mi = +get('minute');
    const wd = get('weekday'); // Mon..Sun
    const weekend = wd === 'Sat' || wd === 'Sun';
    const mins = h * 60 + mi;
    // AUTO tip1m: будни 07:00–23:50; сб/вс — дилер (без отсечки 10:00)
    const inSession = weekend
      ? true
      : (mins >= 7 * 60 && mins < 23 * 60 + 50);
    // Live tip-спред на столе: до 23:45 (выходные — дилер OK)
    const spreadLive = weekend
      ? true
      : (mins >= 7 * 60 && mins < 23 * 60 + 45);
    return {
      y, mo, d, h, mi, weekend, inSession, spreadLive,
      label: `${String(h).padStart(2, '0')}:${String(mi).padStart(2, '0')}`,
    };
  }

  function barZ(bar) {
    if (!bar) return null;
    const z = bar.zScore ?? bar.z;
    return z == null || Number.isNaN(Number(z)) ? null : Number(z);
  }

  function determineZSignalJs(prevZ, curZ, pos, entry, exitZ) {
    if (prevZ == null || curZ == null) return 'NONE';
    if (pos === 'FLAT') {
      if (prevZ > -entry && curZ <= -entry) return 'ENTER_LONG';
      if (prevZ < entry && curZ >= entry) return 'ENTER_SHORT';
      return 'NONE';
    }
    if (pos === 'LONG') {
      if (prevZ < -exitZ && curZ >= -exitZ) return 'EXIT_LONG';
      return 'NONE';
    }
    if (pos === 'SHORT') {
      if (prevZ > exitZ && curZ <= exitZ) return 'EXIT_SHORT';
      return 'NONE';
    }
    return 'NONE';
  }

  function checkItem(state, text) {
    const mark = state === 'ok' ? '✓' : state === 'wait' ? '…' : state === 'block' ? '!' : '–';
    return {
      state,
      html: `<li class="trade-check-item is-${state}"><span class="trade-check-mark">${mark}</span><span class="trade-check-text">${text}</span></li>`,
    };
  }

  /** Visible by default: ! / … ; ✓ (+ N/A) go into collapsed spoiler. Hide section if only N/A or empty. */
  function renderCheckList(listEl, items) {
    if (!listEl) return;
    const section = listEl.closest('.trade-check-section');
    const active = [];
    const done = [];
    const na = [];
    for (const it of items || []) {
      if (!it) continue;
      const state = it.state || 'wait';
      const html = it.html || String(it);
      if (state === 'ok') done.push(html);
      else if (state === 'na') na.push(html);
      else active.push(html);
    }

    // Only N/A or empty → hide whole section (e.g. Закрытие while FLAT)
    if (!active.length && !done.length) {
      listEl.innerHTML = '';
      listEl.hidden = true;
      const spoilRm = section && section.querySelector(':scope > details.trade-check-done');
      if (spoilRm) spoilRm.remove();
      if (section) section.hidden = true;
      return;
    }
    if (section) section.hidden = false;

    listEl.innerHTML = active.join('');
    listEl.hidden = active.length === 0;

    const spoilItems = done.concat(na);
    let spoilEl = section && section.querySelector(':scope > details.trade-check-done');
    if (spoilItems.length && section) {
      const wasOpen = !!spoilEl?.open;
      if (!spoilEl) {
        spoilEl = document.createElement('details');
        spoilEl.className = 'trade-check-done';
        section.appendChild(spoilEl);
      }
      const label = done.length
        ? (na.length ? `OK · ${done.length}` : `Выполнено (${done.length})`)
        : `N/A · ${na.length}`;
      spoilEl.innerHTML =
        `<summary>${label}</summary>` +
        `<ul class="trade-check-list">${spoilItems.join('')}</ul>`;
      spoilEl.open = wasOpen;
    } else if (spoilEl) {
      spoilEl.remove();
    }
  }

  /**
   * Фазы: idle → prep (почти всё OK, метрика у порога) → signal (edge есть) → ready (AUTO может взять).
   * spreadOn: пороги по S% (Long вых / Short вых), не Z ±exit.
   */
  function buildTradePhase({
    pos, curZ, curS, entryN, exitN, signal,
    monOn, autoOn, settled, consecutive, sessionOk, brokerOk, ghost,
    needLong, needShort, needExitLong, needExitShort, settleLeftSec,
    tip1mMode, spreadOn, enterNarrow, enterWide, exitNarrow, exitWide,
  }) {
    const hardOk = monOn && sessionOk && consecutive && brokerOk && !ghost;
    const softWait = [];
    if (!autoOn) softWait.push('авто');
    if (!settled) {
      softWait.push(tip1mMode
        ? (settleLeftSec > 0 ? `закрытие минутки tip (~${settleLeftSec}с)` : 'закрытие минутки tip')
        : (settleLeftSec > 0 ? `закрытие бара (~${settleLeftSec}с)` : 'закрытие бара'));
    }

    const waitingText = (extra) => {
      const extras = Array.isArray(extra) ? extra : (extra ? [extra] : []);
      const all = [...softWait, ...extras].filter(Boolean);
      return all.length ? `ждём: ${all.join(', ')}` : '';
    };

    const nearTh = spreadOn ? PHASE_NEAR_S : PHASE_NEAR_Z;
    const sOk = Number.isFinite(curS);

    if (pos === 'FLAT') {
      const nearLong = needLong != null && needLong <= nearTh;
      const nearShort = needShort != null && needShort <= nearTh;
      const atLevel = spreadOn
        ? (sOk && (curS <= enterNarrow || curS >= enterWide))
        : (curZ != null && (curZ <= -entryN || curZ >= entryN));
      const hasEdge = signal.startsWith('ENTER');
      const approach = hasEdge || atLevel || nearLong || nearShort;

      if (!hardOk || !approach) {
        return {
          kind: 'idle',
          label: 'ожидание',
          title: 'Условия входа ещё далеко',
        };
      }

      const sideHint = hasEdge
        ? signal
        : (nearLong || (spreadOn ? (sOk && curS <= enterNarrow) : (curZ != null && curZ <= -entryN))
          ? 'Long'
          : (nearShort || (spreadOn ? (sOk && curS >= enterWide) : (curZ != null && curZ >= entryN))
            ? 'Short' : ''));

      if (hasEdge && softWait.length === 0) {
        return {
          kind: 'ready',
          side: 'open',
          label: 'AUTO · открытие',
          title: `Готово к AUTO: ${signal}`,
          detail: signal,
        };
      }
      if (hasEdge) {
        return {
          kind: 'signal',
          side: 'open',
          label: 'сигнал · открытие',
          title: `${signal} · ${waitingText()}`,
          detail: signal,
          waiting: waitingText(),
        };
      }
      return {
        kind: 'prep',
        side: 'open',
        label: 'подготовка · открытие',
        title: sideHint
          ? `Подготовка к открытию (${sideHint}) · ${waitingText(!hasEdge ? 'edge' : '')}`
          : `Подготовка к открытию · ${waitingText('edge')}`,
        waiting: waitingText('edge'),
        detail: sideHint,
      };
    }

    // LONG / SHORT — подготовка к закрытию (spread: S→exit; иначе Z→±exit)
    const needExit = pos === 'LONG' ? needExitLong : needExitShort;
    const atExit = spreadOn
      ? (pos === 'LONG'
        ? (sOk && curS >= exitNarrow)
        : (sOk && curS <= exitWide))
      : (pos === 'LONG'
        ? (curZ != null && curZ >= -exitN)
        : (curZ != null && curZ <= exitN));
    const nearExit = needExit != null && needExit <= nearTh;
    const hasEdge = signal.startsWith('EXIT');
    const approach = hasEdge || atExit || nearExit;

    if (!hardOk || !approach) {
      return {
        kind: 'idle',
        label: 'в позиции',
        title: 'До выхода ещё далеко',
      };
    }

    if (hasEdge && softWait.length === 0) {
      return {
        kind: 'ready',
        side: 'close',
        label: 'AUTO · закрытие',
        title: `Готово к AUTO: ${signal}`,
        detail: signal,
      };
    }
    if (hasEdge) {
      return {
        kind: 'signal',
        side: 'close',
        label: 'сигнал · закрытие',
        title: `${signal} · ${waitingText()}`,
        detail: signal,
        waiting: waitingText(),
      };
    }
    return {
      kind: 'prep',
      side: 'close',
      label: 'подготовка · закрытие',
      title: `Подготовка к закрытию · ${waitingText('edge')}`,
      waiting: waitingText('edge'),
    };
  }

  function renderChecklist(data) {
    const hintEl = $('tradeCheckHint');
    const generalEl = $('tradeCheckGeneral');
    const openEl = $('tradeCheckOpen');
    const closeEl = $('tradeCheckClose');
    if (!hintEl || !generalEl || !openEl || !closeEl) return;

    const settings = data.settings || {};
    const mon = data.monitor || {};
    const pos = String(data.position || 'FLAT').toUpperCase();
    const open = data.open || null;
    const broker = data.broker;
    const bs = data.broker_spread;
    const bars = data.bars || [];
    const regime = data.regime || {};
    const sl = data.spread_levels || {};
    const thEff = effectiveThresholds(settings, regime, settings.entry_z, settings.exit_z);
    const entryN = thEff.entry;
    const exitN = thEff.exit;
    const autoOn = !!settings.auto_execute;
    const monOn = !!mon.running;
    const nowMs = Date.now();
    const msk = nowMskParts();

    const last = bars.length ? bars[bars.length - 1] : null;
    const spreadBar = lastSpreadLiveBar(bars) || last;
    const prev = bars.length >= 2 ? bars[bars.length - 2] : null;
    const lastMs = last ? Number(last.timestampMs || 0) : 0;
    const prevMs = prev ? Number(prev.timestampMs || 0) : 0;
    // Prod tip1m: consecutive 1m tips; chart may still show M15 — treat either step as OK for prep.
    const consecutive = prevMs > 0 && (lastMs - prevMs === M1_MS || lastMs - prevMs === M15_MS);
    const tip1mMode = String(settings.signal_mode || 'tip1m') === 'tip1m';
    const hasNext = false;
    const settled = tip1mMode
      ? (lastMs > 0 && nowMs >= lastMs + M1_MS + TIP1M_SETTLE_MS)
      : (lastMs > 0 && (hasNext || nowMs >= lastMs + M15_MS + BAR_SETTLE_MS));
    const settleLeftSec = lastMs > 0
      ? Math.max(0, Math.ceil(
        ((tip1mMode
          ? (lastMs + M1_MS + TIP1M_SETTLE_MS)
          : (lastMs + M15_MS + BAR_SETTLE_MS)) - nowMs) / 1000,
      ))
      : 0;
    const curZ = barZ(spreadBar);
    const prevZ = barZ(prev);
    const curS = spreadBar && (spreadBar.spread != null || spreadBar.spreadPercent != null)
      ? Number(spreadBar.spread != null ? spreadBar.spread : spreadBar.spreadPercent)
      : (sl.spread != null ? Number(sl.spread) : null);
    const prevS = prev && (prev.spread != null || prev.spreadPercent != null)
      ? Number(prev.spread != null ? prev.spread : prev.spreadPercent)
      : null;
    const barTd = spreadBar?.time || spreadBar?.tradeDate || spreadBar?.trade_date
      || last?.time || last?.tradeDate || last?.trade_date || data.summary?.trade_date || '';
    const barInSession = isTqbrSessionBar(barTd);
    const sessionOk = msk.inSession && barInSession;
    const dealer = data.dealer;
    const spreadFrozen = (msk.spreadLive === false && !msk.weekend)
      || (dealer && dealer.spread_live === false);
    let signal = 'NONE';
    if (consecutive) {
      if (thEff.spreadOn) {
        signal = determineSpreadLevelSignalJs(prevS, curS, pos, thEff.levels || sl.levels);
        if (sl.entry_blocked && (signal === 'ENTER_LONG' || signal === 'ENTER_SHORT')) {
          signal = 'NONE';
        }
      } else {
        signal = determineZSignalJs(prevZ, curZ, pos, entryN, exitN);
        if (thEff.regimeOn && !thEff.allowEntry
          && (signal === 'ENTER_LONG' || signal === 'ENTER_SHORT')) {
          signal = 'NONE';
        }
      }
    }
    const tpPct = Number(settings.take_profit_pct);
    const tpOn = Number.isFinite(tpPct) && tpPct > 0;

    const brokerOk = !!(broker && !broker.error);
    const ghost = !!(!open && bs && !bs.error && bs.direction);
    const dealerManualOk = !!(dealer && (dealer.manual_ok || dealer.quotes_ok
      || (dealer.ok && dealer.tatn != null && dealer.tatnp != null)));

    const general = [];
    general.push(checkItem(monOn ? 'ok' : 'block', monOn ? 'Монитор ON' : 'Монитор OFF — старт на вкладке Счёт'));
    general.push(checkItem(autoOn ? 'ok' : 'wait', autoOn ? 'Авто ON (ордера)' : 'Авто OFF — сигналы без ордеров'));
    general.push(checkItem('ok', tip1mMode
      ? 'Стратегия: касание tip1m (Mode B)'
      : 'Стратегия: M15 close (legacy)'));
    if (spreadFrozen) {
      const sTxt = Number.isFinite(curS) ? fmt(curS, 2) : '—';
      const asof = escapeHtml(String(
        (dealer && dealer.spread_asof) || barTd || msk.label,
      ));
      general.push(checkItem('wait',
        `Спред заморожен после 23:45 · S=${sTxt}% на ${asof} · не after-hours tip`));
    }
    if (thEff.spreadOn) {
      const lv = thEff.levels || sl.levels || {};
      const curLab = sl.current_label_ru || '—';
      const sTxt = Number.isFinite(curS) ? fmt(curS, 2) : '—';
      general.push(checkItem('ok',
        `Спред-уровни ON · сейчас ${escapeHtml(String(curLab))} · S=${sTxt}%`
        + ` · Short ${fmt(lv.enter_wide ?? 6.1, 1)}/${fmt(lv.exit_wide ?? 5.8, 1)}`
        + ` · Long ${fmt(lv.enter_narrow ?? 3.2, 1)}/${fmt(lv.exit_narrow ?? 4, 1)}`));
      if (pos === 'FLAT' && sl.entry_blocked) {
        general.push(checkItem('block', 'Вход запрещён · переход'));
      }
    } else {
      general.push(checkItem('wait',
        'Спред-уровни выкл · включите в Параметрах (AUTO без Z-порогов)'));
    }
    general.push(checkItem(tpOn ? 'ok' : 'wait',
      tpOn ? `ТП ${tpPct}% (MTM % депозита, как tip1m)` : 'ТП выкл'));
    if (msk.weekend || dealer) {
      const stShort = escapeHtml(String(dealer?.status_tatn || '—').replace('SECURITY_TRADING_STATUS_', ''));
      general.push(checkItem(dealerManualOk ? 'ok' : (dealer && dealer.error ? 'block' : 'wait'),
        dealerManualOk
          ? `Дилер OK · ${escapeHtml(dealer.label || 'выходные · 1м')} · ручной Long/Short · статус ${stShort}`
          : (dealer && dealer.error
            ? `Дилер: ${escapeHtml(String(dealer.error))}`
            : `Дилер: нет котировок (${msk.label} МСК)`)));
      general.push(checkItem(
        (dealer && dealer.bars_count > 0) || String(data.bars_mode || '') === 'dealer_1m'
          ? 'ok' : 'wait',
        (dealer && dealer.bars_count > 0) || String(data.bars_mode || '') === 'dealer_1m'
          ? `Спред: дилер 1м (${dealer?.bars_count || bars.length} бар) · не в AUTO`
          : 'Спред: ждём 1м дилерские свечи'
      ));
      general.push(checkItem(
        msk.weekend
          ? (dealerManualOk ? 'ok' : 'wait')
          : (msk.inSession ? 'ok' : 'wait'),
        msk.weekend
          ? (dealerManualOk
            ? `Выходные · дилер живой · AUTO tip1m (${msk.label} МСК)`
            : `Выходные · нет котировок дилера (${msk.label} МСК)`)
          : (msk.inSession
            ? `AUTO tip1m в сессии (${msk.label} МСК)`
            : `TQBR закрыта (${msk.label} МСК) — AUTO tip1m только в сессии · дилер = монитор/ручной`)));
      const hasMonSp = (bars || []).some((b) => b && b.spread != null
        && (b.source && String(b.source).includes('dealer')));
      general.push(checkItem(hasMonSp || ((bars || []).some((b) => b && b.spread != null)) ? 'ok' : 'wait',
        hasMonSp || ((bars || []).some((b) => b && b.spread != null))
          ? 'График: свечи спреда % (дилер 1м · монитор · не AUTO)'
          : 'График: ждём бары спреда дилера · ISS M15 не рисуем'));
    } else {
      general.push(checkItem(msk.inSession ? 'ok' : 'block',
        msk.inSession ? `Сессия TQBR сейчас (${msk.label} МСК)` : `Вне сессии TQBR (${msk.label} МСК)`));
    }
    const dealerUi = String(data.bars_mode || '') === 'dealer_1m' || !!(msk.weekend && dealer);
    if (dealerUi) {
      general.push(checkItem(last ? 'ok' : 'wait',
        last
          ? `Дилер 1м бар · ${escapeHtml(fmtTickLabel(barTd))} · не tip1m-сигнал`
          : 'Нет дилерского 1м бара'));
      const dGapOk = prevMs > 0 && lastMs > 0 && (lastMs - prevMs) <= 5 * M1_MS;
      general.push(checkItem(dGapOk ? 'ok' : (bars.length >= 2 ? 'wait' : 'wait'),
        dGapOk
          ? 'Ряд дилер 1м достаточно плотный'
          : (bars.length >= 2 ? 'Дилер 1м с пропусками (нормально вне сессии)' : 'Мало дилерских баров')));
    } else {
      general.push(checkItem(barInSession ? 'ok' : (last ? 'block' : 'wait'),
        last
          ? (barInSession
            ? (tip1mMode
              ? `Tip-бар в сессии · ${escapeHtml(fmtTickLabel(barTd))}`
              : `Бар в сессии · ${escapeHtml(fmtTickLabel(barTd))}`)
            : `Бар вне сессии · ${escapeHtml(fmtTickLabel(barTd))}`)
          : (tip1mMode ? 'Нет tip-бара' : 'Нет бара')));
      if (tip1mMode) {
        general.push(checkItem(settled ? 'ok' : 'wait',
          settled
            ? `Минутка tip закрыта (+${Math.round(TIP1M_SETTLE_MS / 1000)}с)`
            : `Ждём закрытие минутки tip${settleLeftSec > 0 ? ` · ещё ~${settleLeftSec}с` : ''}`));
        general.push(checkItem(consecutive ? 'ok' : 'wait',
          consecutive
            ? 'Ряд tip1m без дыры'
            : (bars.length >= 2 ? 'Дыра в UI-барах (сигнал считает сервер по 1м tip)' : 'Мало баров')));
      } else {
        general.push(checkItem(settled ? 'ok' : 'wait',
          settled
            ? `Бар закрыт (+${Math.round(BAR_SETTLE_MS / 1000)}с)`
            : `Ждём закрытие бара${settleLeftSec > 0 ? ` · ещё ~${settleLeftSec}с` : ''}`));
        general.push(checkItem(consecutive ? 'ok' : (bars.length >= 2 ? 'block' : 'wait'),
          consecutive ? 'Ряд баров без дыры (15м)' : (bars.length >= 2 ? 'Дыра в барах — AUTO пропустит' : 'Мало баров')));
      }
    }
    general.push(checkItem(brokerOk ? 'ok' : 'block',
      brokerOk ? `Брокер OK · ${escapeHtml(String(settings.mode || '—'))}` : `Брокер: ${escapeHtml(broker?.error || 'нет данных')}`));
    if (ghost) {
      general.push(checkItem('block', `Призрак брокера: ${escapeHtml(bs.direction)} ${bs.quantity_lots}+${bs.quantity_lots}`));
    }

    const openItems = [];
    const closeItems = [];
    const zTxt = curZ == null ? '—' : fmt(curZ, 2);
    const sTxt = Number.isFinite(curS) ? fmt(curS, 2) : '—';
    const lv = thEff.levels || sl.levels || {};
    const enterW = Number(lv.enter_wide ?? 6.1);
    const exitW = Number(lv.exit_wide ?? 5.8);
    const enterNlv = Number(lv.enter_narrow ?? 3.2);
    const exitNlv = Number(lv.exit_narrow ?? 4.0);
    const needLong = thEff.spreadOn
      ? (Number.isFinite(curS) ? Math.max(0, curS - enterNlv) : null)
      : (curZ == null ? null : Math.max(0, curZ + entryN));
    const needShort = thEff.spreadOn
      ? (Number.isFinite(curS) ? Math.max(0, enterW - curS) : null)
      : (curZ == null ? null : Math.max(0, entryN - curZ));
    const needExitLong = thEff.spreadOn
      ? (Number.isFinite(curS) ? Math.max(0, exitNlv - curS) : null)
      : (curZ == null ? null : Math.max(0, (-exitN) - curZ));
    const needExitShort = thEff.spreadOn
      ? (Number.isFinite(curS) ? Math.max(0, curS - exitW) : null)
      : (curZ == null ? null : Math.max(0, curZ - exitN));

    const phase = buildTradePhase({
      pos, curZ, curS, entryN, exitN, signal,
      monOn, autoOn, settled, consecutive, sessionOk, brokerOk, ghost,
      needLong, needShort, needExitLong, needExitShort, settleLeftSec,
      tip1mMode,
      spreadOn: !!thEff.spreadOn,
      enterNarrow: enterNlv,
      enterWide: enterW,
      exitNarrow: exitNlv,
      exitWide: exitW,
    });

    const settleBlocker = tip1mMode
      ? `закрытие минутки tip (+${Math.round(TIP1M_SETTLE_MS / 1000)}с)`
      : `закрытие бара (+${Math.round(BAR_SETTLE_MS / 1000)}с)`;
    const edgeOpenHint = tip1mMode
      ? (thEff.spreadOn
        ? 'Нужен edge: касание уровня входа на tip1m (S)'
        : 'Нужен edge: касание порога входа на tip1m')
      : 'Нужен edge: пересечение порога входа на закрытом баре';
    const edgeCloseHint = tip1mMode
      ? (thEff.spreadOn
        ? 'Нужен edge: касание уровня выхода на tip1m (S)'
        : 'Нужен edge: касание порога выхода на tip1m')
      : 'Нужен edge: пересечение порога выхода на закрытом баре';

    if (pos === 'FLAT') {
      openItems.push(checkItem('ok', 'Позиция FLAT — можно открыть'));
      if (ghost) {
        openItems.push(checkItem('block', 'Сначала сверить призрак с брокером'));
      }
      if (thEff.spreadOn) {
        openItems.push(checkItem(
          Number.isFinite(curS) && curS <= enterNlv ? 'ok' : 'wait',
          Number.isFinite(curS) && curS <= enterNlv
            ? `S ≤ ${fmt(enterNlv, 1)}% для Long · сейчас ${sTxt}%`
            : `До Long: S ≤ ${fmt(enterNlv, 1)}% · сейчас ${sTxt}%`
              + `${needLong != null && needLong > 0 ? ` · ещё −${fmt(needLong, 2)}` : ''}`
        ));
        openItems.push(checkItem(
          Number.isFinite(curS) && curS >= enterW ? 'ok' : 'wait',
          Number.isFinite(curS) && curS >= enterW
            ? `S ≥ ${fmt(enterW, 1)}% для Short · сейчас ${sTxt}%`
            : `До Short: S ≥ ${fmt(enterW, 1)}% · сейчас ${sTxt}%`
              + `${needShort != null && needShort > 0 ? ` · ещё +${fmt(needShort, 2)}` : ''}`
        ));
      } else {
        openItems.push(checkItem(
          curZ != null && curZ <= -entryN ? 'ok' : 'wait',
          curZ != null && curZ <= -entryN
            ? `Z ≤ −${fmt(entryN, 2)} для Long · сейчас ${zTxt}`
            : `До Long: Z ≤ −${fmt(entryN, 2)} · сейчас ${zTxt}${needLong != null && needLong > 0 ? ` · ещё −${fmt(needLong, 2)}${needPctSuffix(needLong, entryN)}` : ''}`
        ));
        openItems.push(checkItem(
          curZ != null && curZ >= entryN ? 'ok' : 'wait',
          curZ != null && curZ >= entryN
            ? `Z ≥ +${fmt(entryN, 2)} для Short · сейчас ${zTxt}`
            : `До Short: Z ≥ +${fmt(entryN, 2)} · сейчас ${zTxt}${needShort != null && needShort > 0 ? ` · ещё +${fmt(needShort, 2)}${needPctSuffix(needShort, entryN)}` : ''}`
        ));
      }
      openItems.push(checkItem(
        signal.startsWith('ENTER') ? 'ok' : 'wait',
        signal.startsWith('ENTER')
          ? `Edge готов: ${signal}`
          : edgeOpenHint
      ));
      if (phase.kind === 'prep' || phase.kind === 'signal') {
        openItems.push(checkItem('wait', phase.title || phase.label));
      } else if (phase.kind === 'ready') {
        openItems.push(checkItem('ok', autoOn ? 'AUTO откроет на следующем тике tip1m' : 'Сигнал готов — включите Авто'));
      }
      const blockers = [];
      if (!monOn) blockers.push('монитор');
      if (!autoOn) blockers.push('авто');
      if (!sessionOk) blockers.push('сессия');
      if (!settled) blockers.push(settleBlocker);
      if (!consecutive) blockers.push('дыра');
      if (!brokerOk) blockers.push('брокер');
      if (ghost) blockers.push('призрак');
      if (msk.weekend && !dealerManualOk) {
        blockers.push('нет котировок дилера');
      }
      if (phase.kind !== 'ready' && blockers.length && !(phase.kind === 'prep' || phase.kind === 'signal')) {
        openItems.push(checkItem('block', `Блокируют: ${blockers.join(', ')}`));
      } else if (phase.kind === 'signal' && blockers.length) {
        openItems.push(checkItem('block', `Блокируют AUTO: ${blockers.join(', ')}`));
      }

      closeItems.push(checkItem('na', 'Нет позиции — закрытие не нужно'));
    } else {
      openItems.push(checkItem('na', `Уже ${escapeHtml(pos)} — новое открытие ждут FLAT`));

      closeItems.push(checkItem('ok', `Открыто: ${escapeHtml(pos)}${open?.source ? ` · ${escapeHtml(open.source)}` : ''}`));
      if (thEff.spreadOn) {
        if (pos === 'LONG') {
          closeItems.push(checkItem(
            Number.isFinite(curS) && curS >= exitNlv ? 'ok' : 'wait',
            Number.isFinite(curS) && curS >= exitNlv
              ? `S ≥ ${fmt(exitNlv, 1)}% для EXIT_LONG · сейчас ${sTxt}%`
              : `До EXIT_LONG: S ≥ ${fmt(exitNlv, 1)}% · сейчас ${sTxt}%`
                + `${needExitLong != null && needExitLong > 0 ? ` · ещё +${fmt(needExitLong, 2)}` : ''}`
          ));
        } else {
          closeItems.push(checkItem(
            Number.isFinite(curS) && curS <= exitW ? 'ok' : 'wait',
            Number.isFinite(curS) && curS <= exitW
              ? `S ≤ ${fmt(exitW, 1)}% для EXIT_SHORT · сейчас ${sTxt}%`
              : `До EXIT_SHORT: S ≤ ${fmt(exitW, 1)}% · сейчас ${sTxt}%`
                + `${needExitShort != null && needExitShort > 0 ? ` · ещё −${fmt(needExitShort, 2)}` : ''}`
          ));
        }
      } else if (pos === 'LONG') {
        const exitLongProg = needExitLong != null && Number.isFinite(needExitLong)
          ? ` · ещё +${fmt(needExitLong, 2)}${needPctSuffix(needExitLong, exitN)}`
          : '';
        closeItems.push(checkItem(
          curZ != null && curZ >= -exitN ? 'ok' : 'wait',
          curZ != null && curZ >= -exitN
            ? `Z ≥ −${fmt(exitN, 2)} для EXIT_LONG · сейчас ${zTxt}${exitLongProg}`
            : `До EXIT_LONG: Z ≥ −${fmt(exitN, 2)} · сейчас ${zTxt}${exitLongProg}`
        ));
      } else {
        const exitShortProg = needExitShort != null && Number.isFinite(needExitShort)
          ? ` · ещё −${fmt(needExitShort, 2)}${needPctSuffix(needExitShort, exitN)}`
          : '';
        closeItems.push(checkItem(
          curZ != null && curZ <= exitN ? 'ok' : 'wait',
          curZ != null && curZ <= exitN
            ? `Z ≤ +${fmt(exitN, 2)} для EXIT_SHORT · сейчас ${zTxt}${exitShortProg}`
            : `До EXIT_SHORT: Z ≤ +${fmt(exitN, 2)} · сейчас ${zTxt}${exitShortProg}`
        ));
      }
      closeItems.push(checkItem(
        signal.startsWith('EXIT') ? 'ok' : 'wait',
        signal.startsWith('EXIT')
          ? `Edge готов: ${signal}`
          : edgeCloseHint
      ));
      if (tpOn) {
        closeItems.push(checkItem('ok', `ТП ${tpPct}% — выход по MTM без ожидания M15`));
      }
      if (phase.kind === 'prep' || phase.kind === 'signal') {
        closeItems.push(checkItem('wait', phase.title || phase.label));
      } else if (phase.kind === 'ready') {
        closeItems.push(checkItem('ok', autoOn ? 'AUTO закроет на следующем тике tip1m' : 'Сигнал готов — Авто или «Закрыть сделку»'));
      }
      const blockers = [];
      if (!monOn) blockers.push('монитор');
      if (!autoOn) blockers.push('авто');
      if (!sessionOk) blockers.push('сессия');
      if (!settled) blockers.push(settleBlocker);
      if (!consecutive) blockers.push('дыра');
      if (!brokerOk) blockers.push('брокер');
      if (phase.kind === 'signal' && blockers.length) {
        closeItems.push(checkItem('block', `Блокируют AUTO: ${blockers.join(', ')}`));
      } else if (phase.kind === 'idle' && blockers.length) {
        closeItems.push(checkItem('block', `Блокируют AUTO: ${blockers.join(', ')}`));
      }
    }

    renderCheckList(generalEl, general);
    renderCheckList(openEl, openItems);
    renderCheckList(closeEl, closeItems);

    let hint = '';
    let hintCls = 'trade-check-hint';
    let hintIco = '';
    const metricTxt = thEff.spreadOn ? `S ${sTxt}%` : `Z ${zTxt}`;
    const exitThLong = thEff.spreadOn ? exitNlv : exitN;
    const exitThShort = thEff.spreadOn ? exitW : exitN;
    const entryThLong = thEff.spreadOn ? enterNlv : entryN;
    const entryThShort = thEff.spreadOn ? enterW : entryN;
    const openDir = (() => {
      const d = `${phase.detail || ''} ${phase.title || ''} ${signal || ''}`;
      if (/ENTER_LONG|\bLong\b/i.test(d)) return 'long';
      if (/ENTER_SHORT|\bShort\b/i.test(d)) return 'short';
      return null;
    })();
    if (phase.kind === 'ready') {
      const prog = phase.side === 'open'
        ? openEntryProgressText(openDir, needLong, needShort,
          openDir === 'short' ? entryThShort : entryThLong)
        : '';
      hint = prog ? `${phase.title} · ${prog} · ${metricTxt}` : `${phase.title} · ${metricTxt}`;
      hintCls += ' is-ready';
      hintIco = phase.side === 'close' ? '↘' : '↗';
    } else if (phase.kind === 'signal') {
      const prog = phase.side === 'open'
        ? openEntryProgressText(openDir, needLong, needShort,
          openDir === 'short' ? entryThShort : entryThLong)
        : '';
      hint = prog ? `${phase.title} · ${prog} · ${metricTxt}` : `${phase.title} · ${metricTxt}`;
      hintCls += ' is-block';
      hintIco = phase.side === 'close' ? '↘' : '↗';
    } else if (phase.kind === 'prep') {
      const prog = phase.side === 'open'
        ? openEntryProgressText(openDir, needLong, needShort,
          openDir === 'short' ? entryThShort : entryThLong)
        : '';
      hint = prog ? `${phase.title} · ${prog} · ${metricTxt}` : `${phase.title} · ${metricTxt}`;
      if (phase.side === 'close') {
        hintCls += ' is-prep-close';
        hintIco = '↘';
      } else if (openDir === 'short') {
        hintCls += ' is-prep-open-short';
        hintIco = '↗';
      } else if (openDir === 'long') {
        hintCls += ' is-prep-open-long';
        hintIco = '↗';
      } else {
        hintCls += ' is-prep-open';
        hintIco = '↗';
      }
    } else if (pos === 'FLAT') {
      const nearer = (needLong == null || needShort == null)
        ? null
        : (needLong <= needShort
          ? (thEff.spreadOn
            ? `до Long ещё −${fmt(needLong, 2)}${needPctSuffix(needLong, entryThLong)} по S`
            : `до Long ещё −${fmt(needLong, 2)}${needPctSuffix(needLong, entryThLong)} по Z`)
          : (thEff.spreadOn
            ? `до Short ещё +${fmt(needShort, 2)}${needPctSuffix(needShort, entryThShort)} по S`
            : `до Short ещё +${fmt(needShort, 2)}${needPctSuffix(needShort, entryThShort)} по Z`));
      hint = nearer ? `Ожидание входа · ${nearer} · ${metricTxt}` : `Ожидание входа · ${metricTxt}`;
    } else if (pos === 'LONG') {
      hint = needExitLong != null && Number.isFinite(needExitLong)
        ? (thEff.spreadOn
          ? `В позиции Long · до выхода ещё +${fmt(needExitLong, 2)}${needPctSuffix(needExitLong, exitThLong)} по S · ${metricTxt}`
          : `В позиции Long · до выхода ещё +${fmt(needExitLong, 2)}${needPctSuffix(needExitLong, exitThLong)} по Z · ${metricTxt}`)
        : `В позиции Long · ${metricTxt}`;
    } else {
      hint = needExitShort != null && Number.isFinite(needExitShort)
        ? (thEff.spreadOn
          ? `В позиции Short · до выхода ещё −${fmt(needExitShort, 2)}${needPctSuffix(needExitShort, exitThShort)} по S · ${metricTxt}`
          : `В позиции Short · до выхода ещё −${fmt(needExitShort, 2)}${needPctSuffix(needExitShort, exitThShort)} по Z · ${metricTxt}`)
        : `В позиции Short · ${metricTxt}`;
    }
    hintEl.className = hintCls;
    if (hintIco) {
      hintEl.innerHTML = `<span class="trade-check-hint-ico" aria-hidden="true">${hintIco}</span>${escapeHtml(hint)}`;
    } else {
      hintEl.textContent = hint;
    }

    const sideStatus = $('tradeSideStatus');
    if (sideStatus) {
      const phaseHtml = phaseBadgeHtml(phase.kind === 'idle' ? null : phase);
      sideStatus.innerHTML = `${posBadge(pos)}${phaseHtml ? ` ${phaseHtml}` : ''}`;
    }
  }

  function renderDesk(data, { hydrateForm = false } = {}) {
    // Lite omit / cold broker must not wipe painted «СРЕДСТВА НА СЧЁТЕ».
    data = coalesceDeskBroker(data || {});
    const s = data.summary || {};
    const settings = data.settings || {};
    const mon = data.monitor || {};
    const pos = data.position || 'FLAT';
    lastTradeMode = String(settings.mode || lastTradeMode || '').toLowerCase();
    const monHtml = monBadge(!!mon.running);
    const lagHtml = tipLagBadge(mon, s.trade_date || mon.last_bar || mon.last_message);
    const autoHtml = autoBadge(!!settings.auto_execute);
    const modeHtml = modeBadge(settings.mode);
    const stratHtml = strategyBadge(settings.signal_mode);
    const slHtml = spreadLevelsBadge(settings, data.spread_levels);
    const regimeZHtml = regimeZBadge(settings, data.regime);

    const dealer1mMode = String(data.bars_mode || '') === 'dealer_1m'
      || String(data.bars_mode || '') === 'dealer_weekend';
    const weekendMonitor = !!(data.weekend_monitor || dealer1mMode || nowMskParts().weekend);

    // Weekend display-only Z (server or client tip-style) — never into AUTO.
    // Prefer dealer bars whenever payload looks like dealer_1m / has dealer z_kind,
    // even if bars_mode lagged behind on a lite race.
    const barsLookDealer = (data.bars || []).some((b) => b && (
      b.z_kind === 'dealer_monitor'
      || (b.source && String(b.source).includes('dealer'))
    ));
    let chartBars = data.bars || [];
    if (dealer1mMode || barsLookDealer) {
      chartBars = data.bars || [];
    } else if (weekendMonitor) {
      // Раньше здесь было [] — раз в ~1 мин lite без dealer-source стирал график.
      // Держим последний хороший ряд, пока снова не придут дилерские бары.
      chartBars = lastGoodChartBars.length ? lastGoodChartBars.slice() : [];
    }
    // Пустой/короткий ответ (таймаут, кэш, дыра tip) — не затирать уже нарисованное.
    if ((!chartBars || chartBars.length < 5) && lastGoodChartBars.length >= 5) {
      chartBars = lastGoodChartBars.slice();
      data = {
        ...data,
        bars: chartBars,
        chart_preserved: true,
        partial: true,
      };
    }
    if ((dealer1mMode || weekendMonitor || barsLookDealer) && chartBars.length) {
      chartBars = attachDealerMonitorZClient(chartBars, data.bars_iss || []);
      data = { ...data, bars: chartBars };
      const lastMon = [...chartBars].reverse().find((b) => b && b.z != null);
      if (lastMon) {
        s.z = lastMon.z;
        s.z_kind = 'dealer_monitor';
      }
    }
    if (chartBars.length) {
      // Жёлтая last-value = шапка/оверлей (дилер 3,12), не last close parquet/игла (3,01).
      // Выходные раньше пропускали align — ось жила на lastGood / flattened 1m.
      chartBars = alignTip1mBarsToLiveTip(chartBars, data, data.open || null);
      data = { ...data, bars: chartBars };
      const lastTip = chartBars[chartBars.length - 1];
      if (lastTip) {
        if (lastTip.z != null && !(dealer1mMode || weekendMonitor || barsLookDealer)) {
          s.z = lastTip.z;
        }
        if (lastTip.spread != null) s.spread = lastTip.spread;
      }
    }

    const bars = data.bars || [];
    const barsForDist = (dealer1mMode && (data.bars_iss || []).length)
      ? data.bars_iss
      : bars;
    rebuildBarMetricDists(barsForDist, data.metric_dists);
    const regimeHtml = regimeBadge(bars);
    const cascadeHtml = cascadeBadge(bars);
    const zoneHtml = zoneMapBadge(bars);
    const dealer = data.dealer;
    const mskNow = nowMskParts();
    const spreadFrozen = (dealer && dealer.spread_live === false)
      || (mskNow.spreadLive === false && !mskNow.weekend);
    const liveSpreadBar = lastSpreadLiveBar(bars);
    const useDealerPx = !!(dealer && (dealer.ok || dealer.quotes_ok) && dealer.tatn != null && dealer.tatnp != null)
      && !spreadFrozen;
    let spVal = useDealerPx ? dealer.spread : s.spread;
    if (spreadFrozen && liveSpreadBar && liveSpreadBar.spread != null) {
      spVal = liveSpreadBar.spread;
    } else if (spreadFrozen && dealer && dealer.spread != null) {
      spVal = dealer.spread;
    }
    const spDisp = `${fmt(spVal)}%`;
    const tatnDisp = useDealerPx ? dealer.tatn : (spreadFrozen && dealer?.tatn != null ? dealer.tatn : s.tatn);
    const tatnpDisp = useDealerPx ? dealer.tatnp : (spreadFrozen && dealer?.tatnp != null ? dealer.tatnp : s.tatnp);
    const spreadFreezeHtml = spreadFrozenBadge(dealer, mskNow);

    // Шапка: только счётчики (плашки связи/рынка — в статус-баре)
    const distHint = metricDistMeta.degraded
      ? ` · dist:окно`
      : ` · dist:3г n=${metricDistMeta.n || '—'}`;
    $('tradeMeta').innerHTML =
      `${s.window_count || 0} баров · ${escapeHtml(s.source || '—')}${distHint}`;

    const dealerHtml = dealerStatusHtml(dealer, weekendMonitor);
    const corridorHtml = corridorStatusBadge(
      data.corridor || null,
      spVal,
      Array.isArray(data.bars_iss) ? data.bars_iss : barsForDist,
    );

    // Верхний ряд: 4 смысловые группы (система · данные · источник · рынок)
    const partialBanner = applyPartialBanner(data);
    const partialHtml = partialBanner
      ? `<span class="badge-quiet badge-partial">${escapeHtml(partialBanner)}</span>`
      : '';
    $('tradeStatus').innerHTML = [
      statusGroupHtml([monHtml, autoHtml, modeHtml, onlineBadge(!!s.online)]),
      statusGroupHtml([
        lagHtml,
        partialHtml,
        s.trade_date ? tickBadge(s.trade_date) : '',
      ]),
      statusGroupHtml([dealerHtml, stratHtml, spreadFreezeHtml]),
      statusGroupHtml([
        slHtml,
        zoneHtml,
        regimeHtml,
        cascadeHtml,
        corridorHtml,
        regimeZHtml,
      ]),
    ].filter(Boolean).join('');
    renderTradeRulesStatus(settings);

    // Рынок — у графиков: цены (зона/режим/каскад — в статус-баре)
    const stripSpLabel = useDealerPx
      ? (dealer1mMode ? 'Спред · дилер 1м' : 'Спред · дилер')
      : 'Спред';
    const lookbackDays = dealer?.lookback_days ?? data.dealer_lookback_days;
    const lookbackNote = dealer?.lookback_note || data.dealer_lookback_note || '';
    const lookbackCapped = !!(dealer?.lookback_capped || data.dealer_lookback_capped);
    $('tradeStrip').innerHTML = [
      metricStripBlock(stripSpLabel, 'spread', spVal, spDisp),
      `<span><b>TATN</b> ${fmt(tatnDisp)}</span>`,
      `<span><b>TATNP</b> ${fmt(tatnpDisp)}${useDealerPx ? ' <span class="badge-quiet">дилер</span>' : ''}</span>`,
      // Один блок глубины: без второй строки «дилер 1м: последние Nd».
      (dealer && dealer.bars_count != null)
        ? `<span class="badge-quiet" title="${escapeHtml(String(lookbackNote))}">${dealer.bars_count}×1м${
            lookbackDays != null ? ` · до ${lookbackDays}д` : ''
          }${lookbackCapped ? ' · чип урезан' : ''}</span>`
        : '',
    ].filter(Boolean).join(' ');

    renderCorridorMeter(
      data.corridor || null,
      spVal,
      Array.isArray(data.bars_iss) ? data.bars_iss : barsForDist,
    );

    // Hydrate once from server; never while user is editing (poll / late desk).
    const shouldHydrate = (hydrateForm || !formHydrated) && !formDirty && !paramsFocused();
    if (shouldHydrate && (settings.leverage != null || settings.spread_level_mode != null)) {
      hydrateParams(settings);
    } else if (!formHydrated && !formDirty) {
      const cached = loadCachedParamsLocal();
      if (cached) hydrateParams(cached);
    }

    renderMtlrCard(data.mtlr || null, settings);
    if (DESK_MTLR_UI_ENABLED && settings.mtlr_enabled !== false && (!data.mtlr || data.mtlr.warming)) {
      refreshMtlrShadow({ force: false }).catch(() => {});
    }

    const entry = settings.entry_z;
    const exitZ = settings.exit_z;
    const thEffDesk = effectiveThresholds(settings, data.regime, entry, exitZ);
    const guideEntry = thEffDesk.regimeOn ? thEffDesk.entry : entry;
    const guideExit = thEffDesk.regimeOn ? thEffDesk.exit : exitZ;
    const hasDealerMonSp = (bars || []).some((b) => b && b.spread != null
      && (b.source && String(b.source).includes('dealer')));
    const hasTip1mSp = (bars || []).some((b) => b && b.spread != null
      && !(b.source && String(b.source).includes('dealer')));
    const zEmptyWeekend = weekendMonitor && !hasTip1mSp && !hasDealerMonSp
      && !(bars || []).some((b) => b && b.spread != null);
    const th = applyThresholdVisuals(guideEntry, guideExit, {
      dealer1m: dealer1mMode || weekendMonitor,
      zEmpty: zEmptyWeekend,
      settings,
      spreadLevelsPayload: data.spread_levels,
    });

    renderOpen(data.open, {
      barsMode: data.bars_mode || '',
      bars: data.bars || [],
      settings,
    });
    syncTradeActionButtons(data);
    renderOpenStats(data.open_stats, data.open);
    const fundsPending = !!data.lite && !brokerHasTotals(data.broker)
      && !brokerHasTotals(lastGoodBroker);
    renderFunds(data.broker, { pending: fundsPending });
    const fundsTotal = brokerHasTotals(data.broker)
      ? data.broker.total_rub
      : (brokerHasTotals(lastGoodBroker) ? lastGoodBroker.total_rub : null);
    renderFundsAtOpen(data.open || null, fundsTotal);
    renderCloseForecast(data.close_forecast, {
      hasOpen: !!data.open,
      fundsTotal,
      open: data.open || null,
      depositRub: entryDepositRub(data.open, settings),
      settings,
    });
    renderChecklist(data);

    const mtlr = DESK_MTLR_UI_ENABLED ? (data.mtlr || null) : null;
    renderCharts(chartBars, th.entry, th.exitZ, data.open || null, data.closed || [], {
      dealer1m: dealer1mMode || barsLookDealer,
      weekendMonitor,
      zBars: data.bars_iss || null,
      spreadLevels: th.spreadLevels,
      spreadCuts: th.spreadCuts,
      lookbackDays: data.dealer_lookback_days
        || (data.dealer && data.dealer.lookback_days)
        || (data.dealer && data.dealer.want_days)
        || null,
      mtlrBars: DESK_MTLR_UI_ENABLED && mtlr && Array.isArray(mtlr.bars) ? mtlr.bars : [],
      mtlrLevels: DESK_MTLR_UI_ENABLED && mtlr && mtlr.levels ? mtlr.levels : null,
      mtlrOpen: DESK_MTLR_UI_ENABLED && mtlr && mtlr.open ? mtlr.open : null,
      mtlrClosed: DESK_MTLR_UI_ENABLED && mtlr && Array.isArray(mtlr.closed) ? mtlr.closed : [],
      corridor: data.corridor || null,
      closeForecast: data.close_forecast || lastCloseForecast,
      settings,
    });
  }

  async function saveEntryDeposit() {
    const deposit = readEntryDeposit();
    const addonDep = readNamedDeposit('tradeAddonDeposit');
    const extraDep = readNamedDeposit('tradeExtraDeposit');
    if (deposit == null || addonDep == null || extraDep == null) {
      setDepositStatus('Проверьте число', 'err');
      throw new Error('Некорректный депозит');
    }
    setDepositStatus('Сохранение…', 'pending');
    const btn = $('tradeBtnSaveDeposit');
    if (btn) btn.disabled = true;
    try {
      const res = await api('/api/portfolio/params', {
        method: 'POST',
        body: JSON.stringify({
          entry_deposit_rub: deposit,
          addon_deposit_rub: addonDep,
          extra_deposit_rub: extraDep,
        }),
      });
      const saved = res.settings || {
        entry_deposit_rub: deposit,
        addon_deposit_rub: addonDep,
        extra_deposit_rub: extraDep,
      };
      if ($('tradeEntryDeposit') && saved.entry_deposit_rub != null) {
        $('tradeEntryDeposit').value = String(saved.entry_deposit_rub);
      }
      if ($('tradeAddonDeposit') && saved.addon_deposit_rub != null) {
        $('tradeAddonDeposit').value = String(saved.addon_deposit_rub);
      }
      if ($('tradeExtraDeposit') && saved.extra_deposit_rub != null) {
        $('tradeExtraDeposit').value = String(saved.extra_deposit_rub);
      }
      const cached = loadCachedParamsLocal() || {};
      cacheParamsLocal({
        ...cached,
        ...saved,
        entry_deposit_rub: saved.entry_deposit_rub ?? deposit,
        addon_deposit_rub: saved.addon_deposit_rub ?? addonDep,
        extra_deposit_rub: saved.extra_deposit_rub ?? extraDep,
      });
      setDepositStatus('Сохранено', 'ok');
      setTimeout(() => {
        if ($('tradeDepositStatus')?.textContent === 'Сохранено') setDepositStatus('');
      }, 4000);
      return saved;
    } catch (e) {
      setDepositStatus('Ошибка', 'err');
      throw e;
    } finally {
      if (btn) btn.disabled = false;
    }
  }

  async function saveParams() {
    const params = readFormParams();
    if (params.leverage == null) {
      setParamsStatus('Проверьте числа', 'err');
      throw new Error('Некорректное плечо');
    }
    setParamsStatus('Сохранение…', 'pending');
    const btn = $('tradeBtnSaveParams');
    if (btn) btn.disabled = true;
    try {
      const res = await api('/api/portfolio/params', {
        method: 'POST',
        body: JSON.stringify(params),
      });
      const saved = res.settings || params;
      hydrateParams(saved, { force: true });
      renderTradeRulesStatus(saved);
      setParamsStatus('Сохранено', 'ok');
      // Desk poll updates MTM/chart without clobbering inputs
      refresh({ hydrateForm: false }).catch(() => {});
      return saved;
    } catch (e) {
      setParamsStatus('Ошибка сохранения', 'err');
      throw e;
    } finally {
      if (btn) btn.disabled = false;
    }
  }










  function fmtMtlrSig(sig) {
    const s = String(sig || '');
    if (s === 'ENTER_LONG') return 'вход Long';
    if (s === 'ENTER_SHORT') return 'вход Short';
    if (s === 'EXIT_LONG') return 'выход Long';
    if (s === 'EXIT_SHORT') return 'выход Short';
    return s || '—';
  }

  function renderMtlrCard(mtlr, settings) {
    const card = $('tradeMtlrCard');
    const main = $('tradeMtlrMain');
    const meta = $('tradeMtlrMeta');
    const sigEl = $('tradeMtlrSig');
    const badge = $('tradeMtlrBadge');
    const openEl = $('tradeMtlrOpen');
    if (!card) return;
    const enabled = mtlr
      ? (mtlr.enabled !== false && !mtlr.disabled)
      : (settings ? settings.mtlr_enabled !== false : true);
    if (!enabled) {
      card.hidden = true;
      syncMtlrActionButtons(null);
      return;
    }
    card.hidden = false;
    const autoOn = !!(mtlr && mtlr.auto_execute)
      || !!(settings && settings.mtlr_auto_execute);
    if (badge) {
      badge.textContent = (mtlr && mtlr.badge_ru) || (autoOn ? 'AUTO · m15' : 'тень · m15');
    }
    if (!mtlr || mtlr.warming) {
      if (main) main.textContent = 'прогрев m15…';
      if (meta) {
        meta.textContent = `Short 8.9→8.4 · Long 3.2→4.3 · ${autoOn ? 'AUTO вкл' : 'AUTO выкл'}`;
      }
      if (openEl) {
        openEl.hidden = true;
        openEl.textContent = '';
      }
      if (sigEl) {
        sigEl.textContent = '';
        sigEl.classList.remove('is-edge');
      }
      syncMtlrActionButtons(mtlr || null);
      return;
    }
    if (mtlr.ok === false) {
      if (main) main.textContent = `ошибка: ${mtlr.error || '—'}`;
      if (meta) meta.textContent = mtlr.note_ru || '';
      if (openEl) {
        openEl.hidden = true;
        openEl.textContent = '';
      }
      if (sigEl) {
        sigEl.textContent = '';
        sigEl.classList.remove('is-edge');
      }
      syncMtlrActionButtons(mtlr);
      return;
    }
    const s = mtlr.spread != null ? Number(mtlr.spread).toFixed(2) : '—';
    const livePos = mtlr.live_position || (mtlr.open ? mtlr.open.direction : null);
    const pos = livePos || mtlr.position || 'FLAT';
    const reg = mtlr.regime_label_ru || mtlr.regime || '—';
    if (main) {
      main.textContent = `S ${s}% · ${pos} · ${reg}`;
    }
    const lv = mtlr.levels || {};
    const dep = mtlr.deposit_rub != null
      ? mtlr.deposit_rub
      : (settings && settings.mtlr_deposit_rub != null ? settings.mtlr_deposit_rub : 12000);
    if (meta) {
      meta.textContent = [
        `Short ${lv.enter_wide ?? 8.9}→${lv.exit_wide ?? 8.4}`,
        `Long ${lv.enter_narrow ?? 3.2}→${lv.exit_narrow ?? 4.3}`,
        autoOn ? 'AUTO вкл' : 'AUTO выкл',
        `деп ${Math.round(Number(dep) || 12000)}`,
        mtlr.last_bar ? `бар ${String(mtlr.last_bar).slice(0, 16)}` : '',
        (Number(mtlr.last_bar_lag_sec) >= 20 * 60
          ? `отставание ${Math.round(Number(mtlr.last_bar_lag_sec) / 60)} мин`
          : ''),
      ].filter(Boolean).join(' · ');
    }
    if (openEl) {
      if (mtlr.open) {
        const o = mtlr.open;
        const lots = o.quantity_lots != null ? o.quantity_lots : '—';
        const es = o.entry_spread != null ? Number(o.entry_spread).toFixed(2) : '—';
        openEl.hidden = false;
        openEl.textContent = `открыто ${o.direction || '—'} · ${lots}+${lots} лот · вход S ${es}%`
          + (o.entry_time ? ` · ${String(o.entry_time).slice(0, 16)}` : '');
      } else {
        openEl.hidden = true;
        openEl.textContent = '';
      }
    }
    if (sigEl) {
      if (mtlr.last_signal) {
        sigEl.textContent = `последний сигнал: ${fmtMtlrSig(mtlr.last_signal)}`
          + (mtlr.last_signal_bar ? ` @ ${String(mtlr.last_signal_bar).slice(0, 16)}` : '');
        sigEl.classList.add('is-edge');
      } else {
        sigEl.textContent = 'сигналов на истории нет / вне уровней';
        sigEl.classList.remove('is-edge');
      }
    }
    syncMtlrActionButtons(mtlr);
  }

  function syncMtlrActionButtons(mtlr) {
    const btnLong = $('tradeMtlrBtnLong');
    const btnShort = $('tradeMtlrBtnShort');
    const btnClose = $('tradeMtlrBtnClose');
    if (!btnLong && !btnShort && !btnClose) return;
    const liveOpen = !!(mtlr && mtlr.open);
    const livePos = (mtlr && (mtlr.live_position || (mtlr.open && mtlr.open.direction))) || 'FLAT';
    const flat = !liveOpen && String(livePos).toUpperCase() === 'FLAT';
    const basketOpen = mtlr && mtlr.basket_open != null ? Number(mtlr.basket_open) : 0;
    const basketMax = mtlr && mtlr.basket_max != null ? Number(mtlr.basket_max) : 2;
    const canOpen = flat && basketOpen < basketMax;
    let openTitle = 'Ручной Long Мечел (брокер, отдельно от Татнефть)';
    if (!flat) openTitle = 'Уже есть открытый Мечел';
    else if (basketOpen >= basketMax) openTitle = `Корзина заполнена (${basketOpen}/${basketMax})`;
    if (btnLong) {
      btnLong.disabled = !canOpen;
      btnLong.title = openTitle;
    }
    if (btnShort) {
      btnShort.disabled = !canOpen;
      btnShort.title = !flat
        ? 'Уже есть открытый Мечел'
        : (basketOpen >= basketMax
          ? `Корзина заполнена (${basketOpen}/${basketMax})`
          : 'Ручной Short Мечел');
    }
    if (btnClose) {
      btnClose.disabled = !liveOpen;
      btnClose.title = liveOpen
        ? 'Закрыть спред Мечел на брокере'
        : 'Нет открытого Мечела';
    }
  }

  async function refreshMtlrShadow({ force = false } = {}) {
    try {
      const q = force ? `?force=1&days=${days}` : `?days=${days}`;
      const data = await api(`/api/live/mtlr/shadow${q}`);
      renderMtlrCard(data || {}, null);
      if (data && Array.isArray(data.bars) && data.bars.length) {
        lastMtlrBars = await ensureMtlrChartBars(data.bars.slice(), days);
        if (data.levels) lastMtlrLevels = data.levels;
        // Soft re-paint bottom pane without waiting for next desk poll.
        if (zSeries && spreadSeries) {
          const mtlrLv = data.levels || lastMtlrLevels || {
            enter_wide: 8.9, exit_wide: 8.4, enter_narrow: 3.2, exit_narrow: 4.3,
          };
          const pts = buildSpreadM15ChartSeries(lastMtlrBars);
          try {
            suppressRangeEvents = true;
            if (!spreadSeriesIsLine) spreadSeries.setData(pts);
            else {
              spreadSeries.setData(pts.map((c) => ({ time: c.time, value: c.close })));
            }
            updateSpreadRegimeBands(pts, mtlrLv);
            setSpreadThresholdLines(mtlrLv);
            spPriceByTime = new Map(pts.map((c) => [c.time, c.close]));
            updateChartPaneLabels(chartDealer1m, {
              spreadLevels: null,
              mtlrLevels: mtlrLv,
              mtlrEmpty: pts.length === 0,
            });
            syncBottomPaneToTopTime();
            scheduleEndSuppress();
          } catch (_) {
            suppressRangeEvents = false;
            /* next desk refresh will paint */
          }
        }
      }
      return data;
    } catch (_) {
      return null;
    }
  }

  function onShow() {
    // Сначала сеть desk (lite) — не ждать Мечел.
    if (!formDirty) formHydrated = false;
    ensureCharts();
    applySpreadChartHeight(loadSpreadChartHeight());
    restoreTradeScrolls();
    resize();
    // Instant LS thresholds; params also arrive with desk.settings
    const cached = loadCachedParamsLocal();
    if (cached && !formDirty) hydrateParams(cached, { force: true });
    // Повторный вызов onShow не должен запускать ещё одну цепочку загрузки,
    // способную отменить уже идущий полный запрос выбранного периода.
    const alreadyLoading = !!(pollTimer || refreshWorkCount > 0);
    // Do NOT wait on /status → desk (was ~10s waterfall via TInvest×2).
    // 1) lite desk: bars/markers/settings without broker (~markets time)
    // 2) full desk: broker/funds + dealer in background (never block lite paint)
    hydrateParamsFromServer().catch(() => {});
    if (!alreadyLoading) {
      const bootDays = days;
      const bootLite = () => refresh({ hydrateForm: !formDirty, lite: true });
      bootLite()
        .catch((e) => {
          // Watchdog hard-restart mid-fetch → native "Failed to fetch"; one retry.
          const msg = String((e && e.message) || e || '');
          if (/Failed to fetch|NetworkError|fetch|Таймаут|timeout/i.test(msg)) {
            if (lastGoodChartBars.length) {
              const el = $('tradeStatus');
              if (el) {
                el.textContent = `Ошибка: ${msg} · кэш / частичные данные`;
              }
            }
            return new Promise((r) => setTimeout(r, 1200)).then(() => bootLite());
          }
          throw e;
        })
        .then(() => {
          restoreTradeScrolls();
          // Пользователь уже выбрал другой период: его полную загрузку не
          // перебиваем завершающим запросом начального семидневного запуска.
          if (days !== bootDays) return null;
          startPoll();
          // Full desk is best-effort; lite already painted charts/checklist.
          return refresh({ hydrateForm: false, lite: false }).catch((e) => {
            const el = $('tradeStatus');
            if (el && /Таймаут|timeout|Failed to fetch/i.test(String(e && e.message))) {
              el.textContent = (el.textContent || '') + ' · дилер/брокер: таймаут (частичные данные)';
            }
          });
        })
        .catch((e) => {
          $('tradeStatus').textContent = `Ошибка: ${e.message}`
            + (lastGoodChartBars.length ? ' · кэш / частичные данные' : '');
          startPoll();
        });
    }
    // Тяжёлые боковые панели — после первого кадра, не конкурируют с desk.
    setTimeout(() => {
      refreshMtlrShadow({ force: false }).catch(() => {});
    }, 0);
    requestAnimationFrame(() => {
      resize();
      restoreTradeScrolls();
      requestAnimationFrame(resize);
    });
    // Phone/WebView: layout settles after paint / keyboard / orientation
    setTimeout(() => { resize(); restoreTradeScrolls(); }, 120);
    setTimeout(() => { resize(); restoreTradeScrolls(); }, 400);
  }

  function onHide() {
    stopPoll();
    const active = activeTradeChartFullscreen();
    if (active) setTradeChartFullscreen(active, false);
  }

  function bind() {
    __installTradeDeskModules();
    document.querySelectorAll('#tradePeriodChips .chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('#tradePeriodChips .chip').forEach((b) => b.classList.remove('active'));
        btn.classList.add('active');
        days = parseInt(btn.dataset.days, 10) || 7;
        forceFitContent = true;
        pendingPeriodFitDays = days;
        clearPinState();
        stopPoll();
        refresh()
          .catch((e) => alert(e.message))
          .finally(() => startPoll());
      });
    });
    $('tradeBtnRefresh')?.addEventListener('click', () => {
      refresh({ forceMoex: true }).catch((e) => alert(e.message));
    });
    $('tradeBtnSaveParams')?.addEventListener('click', () => {
      saveParams().catch((e) => alert(e.message));
    });
    $('tradeBtnSaveDeposit')?.addEventListener('click', () => {
      saveEntryDeposit().catch((e) => alert(e.message));
    });
    $('tradeEntryDeposit')?.addEventListener('keydown', (ev) => {
      if (ev.key === 'Enter') {
        ev.preventDefault();
        saveEntryDeposit().catch((e) => alert(e.message));
      }
    });
    ['tradeAddonDeposit', 'tradeExtraDeposit'].forEach((id) => {
      $(id)?.addEventListener('keydown', (ev) => {
        if (ev.key === 'Enter') {
          ev.preventDefault();
          saveEntryDeposit().catch((e) => alert(e.message));
        }
      });
    });
    ['tradeLeverage', 'tradeTpSel', 'tradeSpreadEnterNarrow', 'tradeSpreadExitNarrow',
      'tradeSpreadEnterWide', 'tradeSpreadExitWide'].forEach((id) => {
      const el = $(id);
      if (!el) return;
      el.addEventListener('input', markParamsDirty);
      el.addEventListener('change', markParamsDirty);
      el.addEventListener('keydown', (ev) => {
        if (ev.key === 'Enter') {
          ev.preventDefault();
          saveParams().catch((e) => alert(e.message));
        }
      });
    });
    $('tradeAutoExec')?.addEventListener('change', () => {
      markParamsDirty();
      setParamsStatus('Сохранение…', 'pending');
      api('/api/portfolio/params', {
        method: 'POST',
        body: JSON.stringify({ auto_execute: $('tradeAutoExec').checked }),
      }).then((res) => {
        formDirty = false;
        formHydrated = true;
        if (res.settings) hydrateParams(res.settings, { force: true });
        setParamsStatus('Сохранено', 'ok');
        return refresh({ hydrateForm: false });
      }).catch((e) => {
        setParamsStatus('Ошибка сохранения', 'err');
        alert(e.message);
      });
    });
    $('tradeMtlrEnabled')?.addEventListener('change', () => {
      markParamsDirty();
      setParamsStatus('Сохранение…', 'pending');
      api('/api/portfolio/params', {
        method: 'POST',
        body: JSON.stringify({ mtlr_enabled: $('tradeMtlrEnabled').checked }),
      }).then((res) => {
        formDirty = false;
        formHydrated = true;
        if (res.settings) hydrateParams(res.settings, { force: true });
        setParamsStatus('Сохранено', 'ok');
        renderMtlrCard(null, res.settings || { mtlr_enabled: $('tradeMtlrEnabled').checked });
        if ($('tradeMtlrEnabled').checked) {
          return refreshMtlrShadow({ force: true });
        }
        return null;
      }).catch((e) => {
        setParamsStatus('Ошибка сохранения', 'err');
        alert(e.message);
      });
    });
    $('tradeMtlrAuto')?.addEventListener('change', () => {
      markParamsDirty();
      setParamsStatus('Сохранение…', 'pending');
      api('/api/portfolio/params', {
        method: 'POST',
        body: JSON.stringify({ mtlr_auto_execute: !!$('tradeMtlrAuto').checked }),
      }).then((res) => {
        formDirty = false;
        formHydrated = true;
        if (res.settings) hydrateParams(res.settings, { force: true });
        setParamsStatus('Сохранено', 'ok');
        renderMtlrCard(null, res.settings || {});
        return refreshMtlrShadow({ force: true });
      }).catch((e) => {
        setParamsStatus('Ошибка сохранения', 'err');
        alert(e.message);
      });
    });
    $('tradeMtlrDeposit')?.addEventListener('change', () => {
      const dep = readMtlrDeposit();
      if (dep == null) return;
      markParamsDirty();
      setParamsStatus('Сохранение…', 'pending');
      api('/api/portfolio/params', {
        method: 'POST',
        body: JSON.stringify({ mtlr_deposit_rub: dep }),
      }).then((res) => {
        formDirty = false;
        formHydrated = true;
        if (res.settings) hydrateParams(res.settings, { force: true });
        setParamsStatus('Сохранено', 'ok');
      }).catch((e) => {
        setParamsStatus('Ошибка сохранения', 'err');
        alert(e.message);
      });
    });
    let tradeCommentIdleTimer = null;
    let tradeCommentBound = false;
    let tradeCommentCtx = { kind: 'entry', tradeId: null };
    const TRADE_COMMENT_IDLE_MS = 10_000;

    function hideTradeCommentPopup() {
      if (tradeCommentIdleTimer) {
        clearTimeout(tradeCommentIdleTimer);
        tradeCommentIdleTimer = null;
      }
      const pop = $('tradeCommentPopup');
      const input = $('tradeCommentInput');
      if (pop) {
        pop.classList.add('hidden');
        pop.hidden = true;
      }
      if (input) input.value = '';
    }

    function resetTradeCommentIdle() {
      if (tradeCommentIdleTimer) clearTimeout(tradeCommentIdleTimer);
      tradeCommentIdleTimer = setTimeout(() => {
        const input = $('tradeCommentInput');
        const text = input ? String(input.value || '').trim() : '';
        if (text) {
          saveTradeComment({ dismiss: true }).catch(() => hideTradeCommentPopup());
        } else {
          hideTradeCommentPopup();
        }
      }, TRADE_COMMENT_IDLE_MS);
    }

    async function saveTradeComment({ dismiss = true } = {}) {
      const input = $('tradeCommentInput');
      const text = input ? String(input.value || '').trim() : '';
      const kind = tradeCommentCtx.kind === 'close' ? 'close' : 'entry';
      const body = { kind, comment: text };
      if (tradeCommentCtx.tradeId != null) body.trade_id = tradeCommentCtx.tradeId;
      if (text) {
        try {
          await api('/api/live/trade/comment', {
            method: 'POST',
            body: JSON.stringify(body),
          });
        } catch (e) {
          if (!dismiss) throw e;
          console.warn('trade comment', e);
        }
      }
      if (dismiss) hideTradeCommentPopup();
      if (text) {
        try { await refresh({ hydrateForm: false }); } catch (_) { /* ignore */ }
      }
    }

    function showTradeCommentPopup({ kind = 'entry', tradeId = null, placeholder = '' } = {}) {
      const pop = $('tradeCommentPopup');
      const input = $('tradeCommentInput');
      const title = $('tradeCommentTitle');
      if (!pop || !input) return;
      tradeCommentCtx = { kind: kind === 'close' ? 'close' : 'entry', tradeId };
      if (title) {
        title.textContent = kind === 'close' ? 'Комментарий к закрытию' : 'Комментарий';
      }
      input.placeholder = placeholder
        || (kind === 'close' ? 'почему закрыл…' : 'почему открыл…');
      input.value = '';
      pop.classList.remove('hidden');
      pop.hidden = false;
      resetTradeCommentIdle();
      requestAnimationFrame(() => {
        try { input.focus(); } catch (_) { /* ignore */ }
      });
    }

    function bindTradeCommentPopup() {
      if (tradeCommentBound) return;
      tradeCommentBound = true;
      const input = $('tradeCommentInput');
      const saveBtn = $('tradeCommentSave');
      if (input) {
        const bump = () => resetTradeCommentIdle();
        input.addEventListener('input', bump);
        input.addEventListener('keydown', (ev) => {
          if (ev.key === 'Enter' && !ev.shiftKey) {
            ev.preventDefault();
            saveTradeComment({ dismiss: true }).catch((e) => alert(e.message));
            return;
          }
          bump();
        });
        input.addEventListener('focus', bump);
      }
      saveBtn?.addEventListener('click', () => {
        saveTradeComment({ dismiss: true }).catch((e) => alert(e.message));
      });
    }

    const manualTrade = async (side) => {
      const warn = lastTradeMode === 'prod'
        ? `Боевой счёт: открыть ${side}?`
        : `Открыть ${side} (ручной вход)?`;
      if (!window.confirm(warn)) return;
      const btn = side === 'LONG' ? $('tradeBtnLong') : $('tradeBtnShort');
      if (btn) btn.disabled = true;
      let openedId = null;
      try {
        const res = await api('/api/live/trade', { method: 'POST', body: JSON.stringify({ side }) });
        const tid = res && res.trade_id != null ? Number(res.trade_id) : null;
        openedId = Number.isFinite(tid) ? tid : null;
        await refresh();
      } finally {
        // состояние кнопок вернёт refresh / applyTradeModeUi
        try { await refresh(); } catch (_) { if (btn) btn.disabled = false; }
      }
      showTradeCommentPopup({
        kind: 'entry',
        tradeId: openedId,
        placeholder: side === 'LONG' ? 'почему открыл Long…' : 'почему открыл Short…',
      });
    };
    $('tradeBtnLong')?.addEventListener('click', () => {
      manualTrade('LONG').catch((e) => alert(e.message));
    });
    $('tradeBtnShort')?.addEventListener('click', () => {
      manualTrade('SHORT').catch((e) => alert(e.message));
    });
    $('tradeBtnClose')?.addEventListener('click', async () => {
      if (!window.confirm('Закрыть открытый спрэд на брокере?')) return;
      try {
        const res = await api('/api/portfolio/close', { method: 'POST' });
        await refresh();
        const closed = res && res.closed;
        const tid = closed && closed.id != null ? Number(closed.id) : null;
        showTradeCommentPopup({
          kind: 'close',
          tradeId: Number.isFinite(tid) ? tid : null,
          placeholder: 'почему закрыл…',
        });
      } catch (e) {
        alert(e.message);
      }
    });
    bindTradeCommentPopup();
    const manualMtlrTrade = async (side) => {
      const label = side === 'CLOSE' ? 'закрыть Мечел' : `Мечел ${side}`;
      const warn = lastTradeMode === 'prod'
        ? `Боевой счёт: ${label}?`
        : `${label} (ручной)?`;
      if (!window.confirm(warn)) return;
      await api('/api/live/mtlr/trade', { method: 'POST', body: JSON.stringify({ side }) });
      await Promise.all([
        refresh({ hydrateForm: false }).catch(() => {}),
        refreshMtlrShadow({ force: true }).catch(() => {}),
      ]);
    };
    $('tradeMtlrBtnLong')?.addEventListener('click', () => {
      manualMtlrTrade('LONG').catch((e) => alert(e.message));
    });
    $('tradeMtlrBtnShort')?.addEventListener('click', () => {
      manualMtlrTrade('SHORT').catch((e) => alert(e.message));
    });
    $('tradeMtlrBtnClose')?.addEventListener('click', () => {
      manualMtlrTrade('CLOSE').catch((e) => alert(e.message));
    });
    window.addEventListener('resize', () => {
      if (document.getElementById('app')?.dataset?.view === 'trade') resize();
    });
    bindTradeChartVerticalSplit();
    bindTradeScrolls();
    bindOpenStatsCollapse();
    bindMidTabs();
    bindSideTabs();
    bindMidPanelCollapse();
    bindTradeChartFullscreen();
    bindTradeChartKeyboardNav();
    const tipRoots = [$('tradeStrip')].filter(Boolean);
    tipRoots.forEach((root) => {
      root.addEventListener('pointerover', (e) => {
        const cell = e.target.closest('.metric-hover');
        if (!cell || !root.contains(cell)) return;
        if (metricTipHideTimer) {
          clearTimeout(metricTipHideTimer);
          metricTipHideTimer = null;
        }
        showBarMetricTip(cell);
      });
      root.addEventListener('pointerout', (e) => {
        const cell = e.target.closest('.metric-hover');
        if (!cell || !root.contains(cell)) return;
        const related = e.relatedTarget;
        if (related && (cell.contains(related) || related.closest?.('#tradeLiveMetricTip'))) return;
        metricTipHideTimer = setTimeout(hideMetricTip, 80);
      });
    });
    // Prefetch порогов сразу (Ctrl+F5 → не ждать открытия вкладки / desk)
    fetchStrategyConfig().catch(() => {});
    const cached = loadCachedParamsLocal();
    if (cached) hydrateParams(cached, { force: true });
    hydrateParamsFromServer().catch(() => {});
  }

  window.MoexTrade = { onShow, onHide, refresh, bind, resize };
  // alias for old name
  window.MoexMarkets = window.MoexTrade;
  /** Хуки для Playwright ui_tests (не для прод-логики). */
  window.__deskUiTest = {
    renderDesk,
    rememberGoodChartBars,
    lastGoodBarCount: () => lastGoodChartBars.length,
    lastPaintZCount: () => (lastPaintZData && lastPaintZData.length) || 0,
    emptySkipCount: () => Number(window.__deskChartEmptySkip || 0),
  };

  document.addEventListener('DOMContentLoaded', bind);
})();
