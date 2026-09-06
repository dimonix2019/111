/** Desk chart panes, markers, tip1m align — split from trade.js */
(function (global) {
  'use strict';
  function D() { return global.__TradeDesk.deps; }
  let zChart = null;
  let zSeries = null;
  /** Primary pane: CandlestickSeries (Z tip1m · spread dealer). Line only as last-resort fallback. */
  let zSeriesIsLine = false;
  let spreadChart = null;
  let spreadSeries = null;
  let priceLines = [];
  /** Горизонтали порогов спреда % (как Z createPriceLine). */
  let spreadPriceLines = [];
  let tpExitPriceLine = null;
  /**
   * Semi-transparent trade-band fills (BaselineSeries) — same style as Test/chart.js.
   * Bounds from enter/exit levels (L вх/вых · gap · S вых/вх) — not regime cuts.
   * primary* → upper TATN candle pane; spreadRegime* → lower MTLR candle pane.
   */
  let spreadRegimeBands = { narrow: null, transition: null, wide: null };
  let primarySpreadBands = { narrow: null, transition: null, wide: null };
  /** Last bar times for band setData when only levels/lines refresh. */
  let lastSpreadBandTimes = [];
  let lastPrimarySpreadBandTimes = [];
  const SPREAD_REGIME_BAND_COLORS = {
    // Cyan/teal — Long band (L вх … L вых)
    narrow: 'rgba(0, 188, 212, 0.20)',
    // Brownish/orange — gap between L вых and S вых (no entry)
    transition: 'rgba(183, 110, 45, 0.20)',
    // Maroon/red — Short band (S вых … S вх)
    wide: 'rgba(136, 14, 79, 0.22)',
  };
  /** Линия вход→сейчас / hover вход→выход на верхнем TATN. */
  let openHighlightSeries = null;

  function setZEmptyMessage(text) {
    const el = $('tradeZEmptyMsg');
    if (!el) return;
    if (text) {
      el.textContent = text;
      el.classList.remove('hidden');
    } else {
      el.textContent = '';
      el.classList.add('hidden');
    }
  }

  function updateChartPaneLabels(dealer1m, {
    zEmpty = false,
    lookbackDays = null,
    spreadLevels = null,
    mtlrLevels = null,
    mtlrEmpty = false,
  } = {}) {

  function markUserGesture() {
    D().userGestureActive = true;
    if (D().userGestureTimer) clearTimeout(D().userGestureTimer);
    D().userGestureTimer = setTimeout(() => {
      D().userGestureActive = false;
      D().userGestureTimer = 0;
    }, 400);
  }

  function dataEndIndex(barCount) {
    return barCount > 0 ? barCount - 1 : 0;
  }

  function isNearLiveEdge(range, dataEnd) {
    if (!range || !Number.isFinite(range.to) || !Number.isFinite(dataEnd)) return false;
    return Math.abs(range.to - dataEnd) <= LIVE_EDGE_BARS;
  }

  function visibleDataInRange(range, dataEnd) {
    if (!range || !Number.isFinite(range.from) || !Number.isFinite(range.to)
      || !Number.isFinite(dataEnd) || dataEnd < 0) {
      return { span: 0, visibleData: 0, emptyRight: 0 };
    }
    const span = range.to - range.from;
    if (!(span > 0)) return { span: 0, visibleData: 0, emptyRight: 0 };
    const dataFrom = Math.max(0, Math.min(range.from, dataEnd));
    const dataTo = Math.min(dataEnd, range.to);
    const visibleData = dataTo >= dataFrom ? (dataTo - dataFrom + 1) : 0;
    const emptyRight = Math.max(0, range.to - dataEnd);
    return { span, visibleData, emptyRight };
  }

  function isViewportCorrupt(range, dataEnd, barCount) {
    if (!range || !(barCount > 0) || !Number.isFinite(dataEnd)) return true;
    if (range.from > dataEnd) return true;
    const { span, visibleData, emptyRight } = visibleDataInRange(range, dataEnd);
    if (!(span > 0)) return true;
    const minData = Math.min(MIN_VISIBLE_DATA_BARS, barCount);
    if (visibleData < minData) return true;
    const maxEmpty = Math.max(MAX_RIGHT_OVERSCROLL_BARS, span * 0.35);
    if (emptyRight > maxEmpty) return true;
    return false;
  }

  function persistViewport() {
    if (!D().pinnedRange) return;
    try {
      const payload = {
        from: D().pinnedRange.from,
        to: D().pinnedRange.to,
        dataEnd: D().lastDataEnd,
        pinnedAway: !!D().userPinnedAwayFromLive,
      };
      localStorage.setItem(LS_CHART_RANGE, JSON.stringify(payload));
    } catch (_) { /* quota / private mode */ }
  }

  function loadPersistedViewport() {
    try {
      const raw = localStorage.getItem(LS_CHART_RANGE);
      if (!raw) return null;
      const o = JSON.parse(raw);
      if (o && Number.isFinite(o.from) && Number.isFinite(o.to) && o.to > o.from) {
        return {
          from: o.from,
          to: o.to,
          dataEnd: Number.isFinite(o.dataEnd) ? o.dataEnd : null,
          pinnedAway: !!o.pinnedAway,
        };
      }
    } catch (_) {}
    return null;
  }

  function setPinnedRange(range, { fromUser = false } = {}) {

  function clearPinState() {
    D().pinnedRange = null;
    D().userPinnedAwayFromLive = false;
    D().lastDataEnd = null;
    D().lastBarCount = 0;
    D().lastBarsFingerprint = '';
    try { localStorage.removeItem(LS_CHART_RANGE); } catch (_) {}
  }

  function hydratePinFromStorage() {
    const saved = loadPersistedViewport();
    if (!saved) return;
    D().pinnedRange = { from: saved.from, to: saved.to };
    if (saved.dataEnd != null) D().lastDataEnd = saved.dataEnd;
    D().userPinnedAwayFromLive = !!saved.pinnedAway;
  }

  function applyVisibleRange(range) {
    if (!range || !D().zChart) return;
    D().suppressRangeEvents = true;
    try {
      equalizePriceScales();
      D().zChart.timeScale().setVisibleLogicalRange(range);
      syncBottomPaneToTopTime();
    } catch (_) { /* ignore */ }
    // Не снимаем suppress сразу: LC часто шлёт range-change в следующем кадре
    scheduleEndSuppress();
  }

  function syncBottomPaneToTopTime() {
    if (!D().zChart || !D().spreadChart) return;
    try {
      const tr = zChart.timeScale().getVisibleRange();
      if (tr && tr.from != null && tr.to != null) {
        D().spreadChart.timeScale().setVisibleRange(tr);
      }
    } catch (_) { /* ignore */ }
  }

  function syncTopPaneToBottomTime() {
    if (!D().zChart || !D().spreadChart) return;
    try {
      const tr = spreadChart.timeScale().getVisibleRange();
      if (tr && tr.from != null && tr.to != null) {
        D().zChart.timeScale().setVisibleRange(tr);
      }
    } catch (_) { /* ignore */ }
  }

  function scheduleEndSuppress() {
    if (D().reapplyRangeTimer) cancelAnimationFrame(D().reapplyRangeTimer);
    D().reapplyRangeTimer = requestAnimationFrame(() => {
      D().reapplyRangeTimer = requestAnimationFrame(() => {
        D().reapplyRangeTimer = 0;
        D().suppressRangeEvents = false;
      });
    });
  }

  function equalizePriceScales() {
    try {
      const opts = { minimumWidth: PRICE_SCALE_MIN_WIDTH };
      D().zChart?.priceScale('right')?.applyOptions?.(opts);
      D().spreadChart?.priceScale('right')?.applyOptions?.(opts);
    } catch (_) { /* ignore */ }
  }

  function forceSyncAfterPaint() {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        if (!D().pinnedRange || !D().zChart || !D().spreadChart) return;
        equalizePriceScales();
        reassertPinnedRange();
        syncBottomPaneToTopTime();
        try {
          if (window.__tradeChartDebug) {
            window.__tradeChartDebug.zRange = D().zChart.timeScale().getVisibleLogicalRange();
            window.__tradeChartDebug.spRange = D().spreadChart.timeScale().getVisibleLogicalRange();
            window.__tradeChartDebug.ts = Date.now();
          }
        } catch (_) { /* debug only */ }
      });
    });
  }

  function reassertPinnedRange() {
    if (!D().zChart || !D().pinnedRange) return;
    D().suppressRangeEvents = true;
    try {
      equalizePriceScales();
      D().zChart.timeScale().setVisibleLogicalRange(D().pinnedRange);
      syncBottomPaneToTopTime();
    } catch (_) { /* ignore */ }
    scheduleEndSuppress();
  }

  function bindRangeSync() {
    if (D().rangeSyncBound || !D().zChart || !D().spreadChart) return;
    D().rangeSyncBound = true;
    if (!D().pinnedRange) hydratePinFromStorage();

    const bindGesture = (el) => {
      if (!el || el.dataset.tradeGestureBound === '1') return;
      el.dataset.tradeGestureBound = '1';
      el.addEventListener('pointerdown', markUserGesture);
      el.addEventListener('wheel', markUserGesture, { passive: true });
      el.addEventListener('touchstart', markUserGesture, { passive: true });
    };
    bindGesture(D().$('tradeZChart'));
    bindGesture(D().$('tradeSpreadChart'));

    const onRangeChange = (source) => (range) => {
      if (!range || D().suppressRangeEvents) return;

      // Программный сброс от setData/resize — не пишем в pin; если ушли от live — вернуть окно
      if (!D().userGestureActive) {
        if (D().userPinnedAwayFromLive && D().pinnedRange) {
          const jumpedToLive = lastDataEnd != null && isNearLiveEdge(range, lastDataEnd)
            && Math.abs(range.to - D().pinnedRange.to) > LIVE_EDGE_BARS;
          if (jumpedToLive || Math.abs(range.from - D().pinnedRange.from) > 0.01
            || Math.abs(range.to - D().pinnedRange.to) > 0.01) {
            reassertPinnedRange();
          }
        }
        return;
      }

      // Жест: pin по верхнему TATN (logical); низ — тот же календарный интервал.
      D().suppressRangeEvents = true;
      try {
        if (source === 'z') {
          setPinnedRange(range, { fromUser: true });
          syncBottomPaneToTopTime();
        } else {
          syncTopPaneToBottomTime();
          const zr = zChart.timeScale().getVisibleLogicalRange();
          if (zr) setPinnedRange(zr, { fromUser: true });
        }
      } catch (_) { /* ignore */ }
      scheduleEndSuppress();
    };
    D().zChart.timeScale().subscribeVisibleLogicalRangeChange(onRangeChange('z'));
    D().spreadChart.timeScale().subscribeVisibleLogicalRangeChange(onRangeChange('spread'));
  }

  function nearestPriceByTime(map, time) {
    if (!map || time == null) return null;
    if (map.has(time)) return map.get(time);
    let best = null;
    let bestD = Infinity;
    for (const [t, v] of map) {
      const d = Math.abs(t - time);
      if (d < bestD) {
        bestD = d;
        best = v;
      }
    }
    // ~½ m15 bar — enough for tip1m↔m15 crosshair
    return bestD <= 450 ? best : null;
  }

  function bindCrosshairSync() {
    if (D().crosshairSyncBound || !D().zChart || !D().spreadChart) return;
    D().crosshairSyncBound = true;
    let syncing = false;
    const clearOther = (dst) => {
      if (typeof dst.clearCrosshairPosition !== 'function') return;
      syncing = true;
      try { dst.clearCrosshairPosition(); } catch (_) { /* ignore */ }
      syncing = false;
    };
    const onMove = (src) => (param) => {
      if (src === 'z') updateCandleOhlcOverlay(param);
      if (syncing) return;
      const dst = src === 'z' ? spreadChart : zChart;
      const dstSeries = src === 'z' ? spreadSeries : zSeries;
      if (!dst || !dstSeries || !param || param.time == null || !param.point) {
        clearOther(dst);
        return;
      }
      const priceMap = src === 'z' ? spPriceByTime : zPriceByTime;
      const price = nearestPriceByTime(priceMap, param.time);
      if (price == null || typeof dst.setCrosshairPosition !== 'function') {
        clearOther(dst);
        return;
      }
      syncing = true;
      try { dst.setCrosshairPosition(price, param.time, dstSeries); } catch (_) { /* ignore */ }
      syncing = false;
    };
    D().zChart.subscribeCrosshairMove(onMove('z'));
    D().spreadChart.subscribeCrosshairMove(onMove('spread'));
  }

  function fitAndRemember() {
    try {
      // Явный диапазон надёжнее fitContent(): у большого tip1m fit применяется
      // асинхронно, а getVisibleLogicalRange успевал вернуть старое окно.
      D().suppressRangeEvents = true;
      equalizePriceScales();
      let range = null;
      if (D().lastBarCount > 0) {
        const rightGap = Math.max(2, Math.ceil(lastBarCount * 0.01));
        range = { from: 0, to: Math.max(0, D().lastBarCount - 1) + rightGap };
        applyVisibleRange(range);
      } else {
        try {
          D().zChart?.timeScale().fitContent();
          range = D().zChart?.timeScale().getVisibleLogicalRange();
        } catch (_) { /* ignore */ }
      }
      if (range) {
        D().userPinnedAwayFromLive = false;
        setPinnedRange(range, { fromUser: false });
        if (D().lastBarCount <= 0) applyVisibleRange(range);
      }
      scheduleEndSuppress();
      forceSyncAfterPaint();
    } catch (_) {
      try { D().spreadChart?.timeScale().fitContent(); } catch (__) {}
    }
  }

  function restoreOrFitVisibleRange(zCount, spreadCount) {
    // Pin/live follow по верхнему TATN; низ MTLR подтягивается по времени.
    const zN = Math.max(0, zCount | 0);
    const spN = spreadCount != null ? Math.max(0, spreadCount | 0) : zN;
    const n = zN || spN;
    const dataEnd = dataEndIndex(n);
    const previousBarCount = lastBarCount;
    const dataExpanded = previousBarCount > 0 && n > previousBarCount * 1.5;
    D().lastBarCount = n;
    D().lastDataEnd = dataEnd;

    // После смены периода краткий lite-ответ может прийти раньше полного и
    // израсходовать D().forceFitContent. При последующем расширении всё равно
    // показываем весь выбранный период, если пользователь сам не прокручивал.
    if (D().forceFitContent || (dataExpanded && !D().userPinnedAwayFromLive)) {
      D().forceFitContent = false;
      D().userPinnedAwayFromLive = false;
      fitAndRemember();
      return;
    }

    if (!D().pinnedRange) hydratePinFromStorage();

    if (!D().pinnedRange) {
      fitAndRemember();
      return;
    }

    // Битый LS/pin (данные слева, справа пустота) — не уважать, переfit.
    if (isViewportCorrupt(D().pinnedRange, dataEnd, n)) {
      D().userPinnedAwayFromLive = false;
      fitAndRemember();
      return;
    }

    // Строгий pin: poll/tick не имеют права уезжать к правому краю
    if (D().userPinnedAwayFromLive) {
      applyVisibleRange(D().pinnedRange);
      persistViewport();
      forceSyncAfterPaint();
      return;
    }

    if (isNearLiveEdge(D().pinnedRange, dataEnd) && n > 0) {
      const span = Math.max(1, pinnedRange.to - pinnedRange.from);
      const next = { from: dataEnd - span, to: dataEnd };
      // Follow-live тоже может дать почти пустое окно — сразу переfit.
      if (isViewportCorrupt(next, dataEnd, n)) {
        D().userPinnedAwayFromLive = false;
        fitAndRemember();
        return;
      }
      setPinnedRange(next, { fromUser: false });
      applyVisibleRange(next);
      forceSyncAfterPaint();
      return;
    }

    // Не у live, но флаг ещё не стоял (старый LS) — тоже держим окно
    D().userPinnedAwayFromLive = true;
    applyVisibleRange(D().pinnedRange);
    persistViewport();
    forceSyncAfterPaint();
  }

  function hideCandleOhlcOverlay() {
    paintTradeOhlc(null);
  }

  function candleOhlcAtTime(param) {
    if (param && param.time != null && D().zSeries && !D().zSeriesIsLine && param.seriesData) {
      try {
        const sd = param.seriesData.get(zSeries);
        if (sd && (sd.open != null || sd.close != null || sd.value != null)) {
          return sd;
        }
      } catch (_) { /* ignore */ }
    }
    const pts = lastPaintZData;
    if (!pts || !pts.length) return null;
    if (param && param.time != null) {
      const t = Number(param.time);
      for (let i = pts.length - 1; i >= 0; i -= 1) {
        if (Number(pts[i].time) === t) return pts[i];
      }
    }
    return pts[pts.length - 1];
  }

  function paintTradeOhlc(param) {
    const el = $('tradeCandleOhlc');
    if (!el) return;
    if (!D().zChart || !D().zSeries || D().zSeriesIsLine) {
      el.textContent = '—';
      el.classList.add('is-idle');
      return;
    }
    const candle = candleOhlcAtTime(param);
    if (!candle) {
      el.textContent = '—';
      el.classList.add('is-idle');
      return;
    }
    el.textContent = typeof window.formatOhlcLine === 'function'
      ? window.formatOhlcLine(candle)
      : `O: ${D().fmt(candle.open, 2)}  H: ${D().fmt(candle.high, 2)}  L: ${D().fmt(candle.low, 2)}  C: ${D().fmt(candle.close, 2)}`;
    el.classList.remove('is-idle');
  }

  function updateCandleOhlcOverlay(param) {
    const hovering = param && param.point && param.time != null
      && param.point.x >= 0 && param.point.y >= 0;
    paintTradeOhlc(hovering ? param : null);
  }

  function addSeries(chart, type, opts) {
    if (typeof chart.addSeries === 'function' && LightweightCharts[type]) {
      return chart.addSeries(LightweightCharts[type], opts);
    }
    if (type === 'CandlestickSeries' && chart.addCandlestickSeries) return chart.addCandlestickSeries(opts);
    if (type === 'LineSeries' && chart.addLineSeries) return chart.addLineSeries(opts);
    if (type === 'BaselineSeries' && chart.addBaselineSeries) return chart.addBaselineSeries(opts);
    return null;
  }

  function makeSpreadRegimeBand(chart, basePrice, fillColor) {
    return addSeries(chart, 'BaselineSeries', {
      baseValue: { type: 'price', price: basePrice },
      topLineColor: 'rgba(0,0,0,0)',
      topFillColor1: fillColor,
      topFillColor2: fillColor,
      bottomLineColor: 'rgba(0,0,0,0)',
      bottomFillColor1: 'rgba(0,0,0,0)',
      bottomFillColor2: 'rgba(0,0,0,0)',
      lineWidth: 1,
      lastValueVisible: false,
      priceLineVisible: false,
      crosshairMarkerVisible: false,
      // Bands must not stretch the price scale.
      autoscaleInfoProvider: () => null,
    });
  }

  function resolveSpreadTradeBandBounds(levels) {
    const lv = levels || {};
    const cfg = spreadCfgSpread();
    const num = (v, fb) => {
      const n = Number(v);
      return Number.isFinite(n) ? n : fb;
    };
    const enterW = num(lv.enter_wide, cfg.enter_wide);
    const exitW = num(lv.exit_wide, cfg.exit_wide);
    const enterN = num(lv.enter_narrow, cfg.enter_narrow);
    const exitN = num(lv.exit_narrow, cfg.exit_narrow);
    const loHi = (a, b) => (a <= b ? { lo: a, hi: b } : { lo: b, hi: a });
    const narrow = loHi(enterN, exitN);
    const wide = loHi(exitW, enterW);
    const trans = loHi(exitN, exitW);
    return {
      narrowLo: narrow.lo,
      narrowHi: narrow.hi,
      transLo: trans.lo,
      transHi: trans.hi,
      wideLo: wide.lo,
      wideHi: wide.hi,
    };
  }

  function ensureSpreadBandsOnChart(chart, bandsRef, levels) {
    if (!chart || !bandsRef) return false;
    const b = resolveSpreadTradeBandBounds(levels);
    if (!bandsRef.narrow) {
      // Create before candles/line when possible → fills under price series (как Test).
      bandsRef.narrow = makeSpreadRegimeBand(
        chart, b.narrowLo, SPREAD_REGIME_BAND_COLORS.narrow,
      );
      bandsRef.transition = makeSpreadRegimeBand(
        chart, b.transLo, SPREAD_REGIME_BAND_COLORS.transition,
      );
      bandsRef.wide = makeSpreadRegimeBand(
        chart, b.wideLo, SPREAD_REGIME_BAND_COLORS.wide,
      );
      return !!(bandsRef.narrow || bandsRef.transition || bandsRef.wide);
    }
    try {
      bandsRef.narrow.applyOptions({
        baseValue: { type: 'price', price: b.narrowLo },
        topFillColor1: SPREAD_REGIME_BAND_COLORS.narrow,
        topFillColor2: SPREAD_REGIME_BAND_COLORS.narrow,
      });
      bandsRef.transition.applyOptions({
        baseValue: { type: 'price', price: b.transLo },
        topFillColor1: SPREAD_REGIME_BAND_COLORS.transition,
        topFillColor2: SPREAD_REGIME_BAND_COLORS.transition,
      });
      bandsRef.wide.applyOptions({
        baseValue: { type: 'price', price: b.wideLo },
        topFillColor1: SPREAD_REGIME_BAND_COLORS.wide,
        topFillColor2: SPREAD_REGIME_BAND_COLORS.wide,
      });
    } catch (_) { /* */ }
    return false;
  }

  function ensureSpreadRegimeBands(levels) {
    if (!D().spreadChart) return;
    const created = ensureSpreadBandsOnChart(spreadChart, spreadRegimeBands, levels);
    if (created && D().spreadSeries) {
      const wantLine = spreadSeriesIsLine;
      D().spreadPriceLines.forEach((pl) => {
        try { D().spreadSeries.removePriceLine(pl); } catch (_) {}
      });
      D().spreadPriceLines = [];
      D().openSpreadMarkersPlugin = null;
      try {
        if (typeof D().spreadChart.removeSeries === 'function') D().spreadChart.removeSeries(D().spreadSeries);
      } catch (_) {}
      D().spreadSeries = null;
      D().spreadSeriesIsLine = !wantLine;
      ensureSpreadSeriesKind(wantLine);
    }
  }

  function ensurePrimarySpreadBands(levels) {
    if (!D().zChart) return;
    const created = ensureSpreadBandsOnChart(zChart, primarySpreadBands, levels);
    if (created && D().zSeries) {
      const wantLine = zSeriesIsLine;
      D().priceLines.forEach((pl) => {
        try { D().zSeries.removePriceLine(pl); } catch (_) {}
      });
      D().priceLines = [];
      D().openMarkersPlugin = null;
      try {
        if (typeof D().zChart.removeSeries === 'function') D().zChart.removeSeries(D().zSeries);
      } catch (_) {}
      D().zSeries = null;
      D().zSeriesIsLine = !wantLine;
      ensureZSeriesKind(wantLine);
    }
  }

  function setSpreadBandSeriesData(bandsRef, times, levels) {
    if (!bandsRef) return;
    const b = resolveSpreadTradeBandBounds(levels);
    const mk = (value) => times.map((time) => ({ time, value }));
    try {
      if (bandsRef.narrow) {
        bandsRef.narrow.setData(times.length ? mk(b.narrowHi) : []);
      }
      if (bandsRef.transition) {
        bandsRef.transition.setData(times.length ? mk(b.transHi) : []);
      }
      if (bandsRef.wide) {
        bandsRef.wide.setData(times.length ? mk(b.wideHi) : []);
      }
    } catch (_) { /* */ }
  }

  function updateSpreadRegimeBands(spreadPts, levels) {
    if (!D().spreadChart) return;
    ensureSpreadRegimeBands(levels);
    if (Array.isArray(spreadPts)) {
      D().lastSpreadBandTimes = spreadPts
        .map((p) => p && p.time)
        .filter((t) => t != null);
    }
    setSpreadBandSeriesData(D().spreadRegimeBands, D().lastSpreadBandTimes, levels);
  }

  function updatePrimarySpreadBands(candlePts, levels) {
    if (!D().zChart) return;
    ensurePrimarySpreadBands(levels);
    if (Array.isArray(candlePts)) {
      D().lastPrimarySpreadBandTimes = candlePts
        .map((p) => p && p.time)
        .filter((t) => t != null);
    }
    setSpreadBandSeriesData(D().primarySpreadBands, D().lastPrimarySpreadBandTimes, levels);
  }

  function makeZCandleSeries(chart) {
    return addSeries(chart, 'CandlestickSeries', {
      upColor: '#089981', downColor: '#f23645',
      borderUpColor: '#089981', borderDownColor: '#f23645',
      wickUpColor: '#089981', wickDownColor: '#f23645',
      lastValueVisible: true,
      priceLineVisible: true,
      priceLineColor: CURRENT_PRICE_LINE_COLOR,
      lastValueFillColor: CURRENT_PRICE_LINE_COLOR,
    });
  }

  function makeZLineSeries(chart) {
    return addSeries(chart, 'LineSeries', {
      color: '#089981',
      lineWidth: 2,
      lastValueVisible: true,
      priceLineVisible: true,
      priceLineColor: CURRENT_PRICE_LINE_COLOR,
      lastValueFillColor: CURRENT_PRICE_LINE_COLOR,
    });
  }

  function publishChartDebug(extra) {
    try {
      let zRange = null;
      let spRange = null;
      try { zRange = D().zChart?.timeScale()?.getVisibleLogicalRange() || null; } catch (_) {}
      try { spRange = D().spreadChart?.timeScale()?.getVisibleLogicalRange() || null; } catch (_) {}
      window.__tradeChartDebug = {
        D().zSeriesIsLine,
        D().chartDealer1m,
        zPts: (extra && extra.zPts) || 0,
        spPts: (extra && extra.spPts) || 0,
        bodyN: (extra && extra.bodyN) || 0,
        sample: (extra && extra.sample) || null,
        sync: (extra && extra.sync) || null,
        zRange,
        spRange,
        err: (extra && extra.err) || null,
        ts: Date.now(),
      };
    } catch (_) { /* ignore */ }
  }

  function ensureZSeriesKind(wantLine) {
    if (!D().zChart) return false;
    const asLine = !!wantLine;
    if (D().zSeries && D().zSeriesIsLine === asLine) return false;
    D().priceLines.forEach((pl) => {
      try { if (D().zSeries) D().zSeries.removePriceLine(pl); } catch (_) {}
    });
    D().priceLines = [];
    if (D().tpExitPriceLine) {
      try { if (D().zSeries) D().zSeries.removePriceLine(D().tpExitPriceLine); } catch (_) {}
      D().tpExitPriceLine = null;
    }
    D().openMarkersPlugin = null;
    if (D().zSeries) {
      try {
        if (typeof D().zChart.removeSeries === 'function') D().zChart.removeSeries(D().zSeries);
      } catch (_) {}
      D().zSeries = null;
    }
    if (asLine) {
      D().zSeries = makeZLineSeries(D().zChart);
      D().zSeriesIsLine = !!D().zSeries;
    } else {
      const candle = makeZCandleSeries(zChart);
      if (candle) {
        D().zSeries = candle;
        D().zSeriesIsLine = false;
      } else {
        D().zSeries = makeZLineSeries(D().zChart);
        D().zSeriesIsLine = !!D().zSeries;
      }
    }
    D().lastBarsFingerprint = '';
    D().forceFitContent = true;
    publishChartDebug({ zPts: 0, spPts: 0 });
    applyZSeriesCorridorAutoscale();
    return true;
  }

  function ensureSpreadSeriesKind(wantLine) {
    if (!D().spreadChart) return false;
    const asLine = !!wantLine;
    if (D().spreadSeries && D().spreadSeriesIsLine === asLine) return false;
    D().spreadPriceLines.forEach((pl) => {
      try { if (D().spreadSeries) D().spreadSeries.removePriceLine(pl); } catch (_) {}
    });
    D().spreadPriceLines = [];
    D().openSpreadMarkersPlugin = null;
    if (D().spreadSeries) {
      try {
        if (typeof D().spreadChart.removeSeries === 'function') D().spreadChart.removeSeries(D().spreadSeries);
      } catch (_) {}
      D().spreadSeries = null;
    }
    if (asLine) {
      D().spreadSeries = makeZLineSeries(D().spreadChart);
      D().spreadSeriesIsLine = !!D().spreadSeries;
    } else {
      const candle = makeZCandleSeries(spreadChart);
      if (candle) {
        D().spreadSeries = candle;
        D().spreadSeriesIsLine = false;
      } else {
        D().spreadSeries = makeZLineSeries(D().spreadChart);
        D().spreadSeriesIsLine = !!D().spreadSeries;
      }
    }
    D().lastBarsFingerprint = '';
    D().forceFitContent = true;
    return true;
  }

  function ensureCharts() {
    if (typeof LightweightCharts === 'undefined') return;
    const zEl = $('tradeZChart');
    const sEl = $('tradeSpreadChart');
    if (!zEl || !sEl) return;
    const zoom = { minBarSpacing: 0.001, maxBarSpacing: 64 };
    try { D().zChart?.timeScale()?.applyOptions?.(zoom); } catch (_) { /* ignore */ }
    try { D().spreadChart?.timeScale()?.applyOptions?.(zoom); } catch (_) { /* ignore */ }
    if (!D().zChart) {
      D().zChart = LightweightCharts.createChart(zEl, {
        layout: { background: { color: '#161a25' }, textColor: '#d1d4dc' },
        grid: { vertLines: { color: '#2a2e39' }, horzLines: { color: '#2a2e39' } },
        rightPriceScale: { borderColor: '#2a2e39', minimumWidth: PRICE_SCALE_MIN_WIDTH },
        width: zEl.clientWidth,
        height: zEl.clientHeight || 300,
        ...chartTimeOpts(),
        ...CHART_SCROLL_SCALE,
      });
      // Bands first → under candles (как Test chart.js).
      ensurePrimarySpreadBands(spreadCfgLevels(null));
      // Candles by default (dealer + tip1m). Line only if candle API missing.
      const candle = makeZCandleSeries(zChart);
      if (candle) {
        D().zSeries = candle;
        D().zSeriesIsLine = false;
      } else {
        D().zSeries = makeZLineSeries(D().zChart);
        D().zSeriesIsLine = !!D().zSeries;
      }
      D().openHighlightSeries = addSeries(D().zChart, 'LineSeries', {
        color: TRADE_HIGHLIGHT_COLOR,
        lineWidth: 2,
        lineStyle: 0,
        lastValueVisible: false,
        priceLineVisible: false,
        crosshairMarkerVisible: false,
        autoscaleInfoProvider: () => null,
      });
    } else if (D().zChart && D().zSeries && !D().openHighlightSeries) {
      D().openHighlightSeries = addSeries(D().zChart, 'LineSeries', {
        color: TRADE_HIGHLIGHT_COLOR,
        lineWidth: 2,
        lineStyle: 0,
        lastValueVisible: false,
        priceLineVisible: false,
        crosshairMarkerVisible: false,
        autoscaleInfoProvider: () => null,
      });
    }
    if (!D().spreadChart) {
      D().spreadChart = LightweightCharts.createChart(sEl, {
        layout: { background: { color: '#161a25' }, textColor: '#d1d4dc' },
        grid: { vertLines: { color: '#2a2e39' }, horzLines: { color: '#2a2e39' } },
        rightPriceScale: { borderColor: '#2a2e39', minimumWidth: PRICE_SCALE_MIN_WIDTH },
        width: sEl.clientWidth,
        height: sEl.clientHeight || 150,
        ...chartTimeOpts(),
        ...CHART_SCROLL_SCALE,
      });
      // Trade-band fills first (under MTLR candles) — Mechel levels.
      ensureSpreadRegimeBands({
        enter_wide: 8.9, exit_wide: 8.4, enter_narrow: 3.2, exit_narrow: 4.3,
      });
      const candle = makeZCandleSeries(spreadChart);
      if (candle) {
        D().spreadSeries = candle;
        D().spreadSeriesIsLine = false;
      } else {
        D().spreadSeries = makeZLineSeries(D().spreadChart);
        D().spreadSeriesIsLine = !!D().spreadSeries;
      }
    } else if (D().spreadSeries && D().spreadSeriesIsLine) {
      ensureSpreadSeriesKind(false);
    }
    equalizePriceScales();
    bindRangeSync();
    bindCrosshairSync();
    bindMarkerHover();
  }

  function setThresholdLines(entry, exitZ) {
    if (!D().zSeries || typeof D().zSeries.createPriceLine !== 'function') return;
    D().priceLines.forEach((pl) => { try { D().zSeries.removePriceLine(pl); } catch (_) {} });
    D().priceLines = [];
    const mk = (price, color, title) => {
      D().priceLines.push(D().zSeries.createPriceLine({
        price, color, lineWidth: 1, lineStyle: 2, title,
      }));
    };
    mk(entry, '#2962ff', `+вх ${entry}`);
    mk(-entry, '#2962ff', `−вх ${entry}`);
    mk(exitZ, '#089981', `+вых ${exitZ}`);
    mk(-exitZ, '#089981', `−вых ${exitZ}`);
  }

  function setPrimarySpreadThresholdLines(levels) {
    const lv = levels || {};
    // Bands first (may recreate candle series for z-order); then price lines.
    updatePrimarySpreadBands(null, lv);
    if (!D().zSeries || typeof D().zSeries.createPriceLine !== 'function') return;
    D().priceLines.forEach((pl) => { try { D().zSeries.removePriceLine(pl); } catch (_) {} });
    D().priceLines = [];
    const enterW = Number(lv.enter_wide);
    const exitW = Number(lv.exit_wide);
    const enterN = Number(lv.enter_narrow);
    const exitN = Number(lv.exit_narrow);
    const mk = (price, color, title) => {
      if (!Number.isFinite(price)) return;
      D().priceLines.push(D().zSeries.createPriceLine({
        price, color, lineWidth: 1, lineStyle: 2, title,
      }));
    };
    mk(enterW, '#2962ff', `S вх ${D().fmt(enterW, 1)}`);
    mk(exitW, '#26a69a', `S вых ${D().fmt(exitW, 1)}`);
    mk(exitN, '#26a69a', `L вых ${D().fmt(exitN, 1)}`);
    mk(enterN, '#2962ff', `L вх ${D().fmt(enterN, 1)}`);
  }

  function clearTpExitSpreadLine() {
    if (!D().tpExitPriceLine) return;
    try {
      if (D().zSeries && typeof D().zSeries.removePriceLine === 'function') {
        D().zSeries.removePriceLine(D().tpExitPriceLine);
      }
    } catch (_) { /* */ }
    D().tpExitPriceLine = null;
  }

  function setTpExitSpreadLine(openTrade, closeForecast, settings) {
    clearTpExitSpreadLine();
    if (!D().zSeries || typeof D().zSeries.createPriceLine !== 'function') return;
    if (!openTrade) return;
    const tpRaw = Number(settings?.take_profit_pct);
    const tpPct = Number.isFinite(tpRaw) ? tpRaw : 0;
    if (!(tpPct > 0)) return;

    const fc = closeForecast
      || (D().lastCloseForecast && D().lastCloseForecast.exit_level_spread != null
        ? D().lastCloseForecast
        : null);
    const pot = exitLevelPotential(fc, openTrade, {
      settings,
      depositRub: D().entryDepositRub(openTrade, settings),
    });
    if (!pot || !Number.isFinite(pot.spread)) return;

    const title = `ТП ${fmt(tpPct, tpPct % 1 === 0 ? 0 : 1)}%`;
    try {
      D().tpExitPriceLine = D().zSeries.createPriceLine({
        price: Number(pot.spread),
        color: TP_EXIT_LINE_COLOR,
        lineWidth: 1,
        lineStyle: 3,
        title,
        axisLabelVisible: true,
      });
    } catch (_) {
      D().tpExitPriceLine = null;
    }
  }

  function resolveSpreadLevelLines(settings, spreadLevelsPayload) {
    const sl = spreadLevelsPayload || {};
    const lv = sl.levels || {};
    const cuts = sl.cuts || {};
    const cfg = spreadCfgSpread();
    const num = (v, fallback) => {
      const n = Number(v);
      return Number.isFinite(n) ? n : fallback;
    };
    return {
      levels: {
        enter_wide: num(lv.enter_wide ?? settings?.spread_enter_wide, cfg.enter_wide),
        exit_wide: num(lv.exit_wide ?? settings?.spread_exit_wide, cfg.exit_wide),
        enter_narrow: num(lv.enter_narrow ?? settings?.spread_enter_narrow, cfg.enter_narrow),
        exit_narrow: num(lv.exit_narrow ?? settings?.spread_exit_narrow, cfg.exit_narrow),
      },
      cuts: {
        narrow_max: num(cuts.narrow_max, cfg.regime_narrow_max),
        wide_min: num(cuts.wide_min, cfg.regime_wide_min),
      },
    };
  }

  function setSpreadThresholdLines(levels) {
    if (!D().spreadSeries || typeof D().spreadSeries.createPriceLine !== 'function') return;
    D().spreadPriceLines.forEach((pl) => {
      try { D().spreadSeries.removePriceLine(pl); } catch (_) {}
    });
    D().spreadPriceLines = [];
    const lv = levels || {};
    const enterW = Number(lv.enter_wide);
    const exitW = Number(lv.exit_wide);
    const enterN = Number(lv.enter_narrow);
    const exitN = Number(lv.exit_narrow);
    const mk = (price, color, title) => {
      if (!Number.isFinite(price)) return;
      D().spreadPriceLines.push(D().spreadSeries.createPriceLine({
        price, color, lineWidth: 1, lineStyle: 2, title,
      }));
    };
    // Как Z: синий = вход, бирюзовый = выход; подписи Short/Long (запятая ru-RU).
    // Cuts режима (3.5/5.5) не рисуем — только в engine для gating.
    // Порядок сверху вниз как на скрине: S вх → S вых → L вых → L вх.
    mk(enterW, '#2962ff', `S вх ${D().fmt(enterW, 1)}`);
    mk(exitW, '#26a69a', `S вых ${D().fmt(exitW, 1)}`);
    mk(exitN, '#26a69a', `L вых ${D().fmt(exitN, 1)}`);
    mk(enterN, '#2962ff', `L вх ${D().fmt(enterN, 1)}`);
    // Zone fills = торговые полосы L/S вх–вых (не cuts).
    updateSpreadRegimeBands(null, lv);
  }

  function barsFingerprint(bars) {
    if (!bars || !bars.length) return '0';
    const first = bars[0];
    const last = bars[bars.length - 1];
    return [
      bars.length,
      first?.time || '',
      last?.time || '',
      last?.z ?? '',
      last?.spread ?? '',
      last?.timestampMs ?? '',
    ].join('|');
  }

  function liveTipZSpread(data, openTrade = null) {
    const open = openTrade || (data && data.open) || null;
    const mark = (open && open.mark) || {};
    const mon = (data && data.monitor) || {};
    const s = (data && data.summary) || {};
    const dealer = (data && data.dealer) || {};
    const msk = nowMskParts();
    const spreadFrozen = dealer.spread_live === false
      || (msk.spreadLive === false && !msk.weekend);
    // Same source as шапка/оверлей (useDealerPx), not parquet/ISS last close.
    const useDealerPx = !!(dealer.ok || dealer.quotes_ok)
      && dealer.tatn != null && dealer.tatnp != null
      && !spreadFrozen;
    let z = null;
    let sp = null;
    if (mark.z_now != null && Number.isFinite(Number(mark.z_now))) z = Number(mark.z_now);
    else if (mon.last_z != null && Number.isFinite(Number(mon.last_z))) z = Number(mon.last_z);
    else if (s.z != null && Number.isFinite(Number(s.z))) z = Number(s.z);
    if (spreadFrozen) {
      const good = lastSpreadLiveBar((data && data.bars) || []);
      if (good && good.spread != null && Number.isFinite(Number(good.spread))) {
        sp = Number(good.spread);
      } else if (dealer.spread != null && Number.isFinite(Number(dealer.spread))) {
        sp = Number(dealer.spread);
      } else if (s.spread != null && Number.isFinite(Number(s.spread))) {
        sp = Number(s.spread);
      }
    } else if (useDealerPx && dealer.spread != null && Number.isFinite(Number(dealer.spread))) {
      sp = Number(dealer.spread);
    } else if (mark.spread_now != null && Number.isFinite(Number(mark.spread_now))) {
      sp = Number(mark.spread_now);
    } else if (s.spread != null && Number.isFinite(Number(s.spread))) {
      sp = Number(s.spread);
    }
    return { z, sp };
  }

  function alignTip1mBarsToLiveTip(bars, data, openTrade = null) {
    if (!Array.isArray(bars) || !bars.length) return bars || [];
    const live = liveTipZSpread(data, openTrade);
    // Never paint live tip as 0 / null — warmup sentinels make fake candle spikes.
    const liveZ = (live.z != null && Number.isFinite(live.z) && Math.abs(live.z) > 1e-12)
      ? live.z
      : null;
    const liveSp = (live.sp != null && Number.isFinite(live.sp)) ? live.sp : null;
    if (liveZ == null && liveSp == null) return bars;
    const last = bars[bars.length - 1];
    if (!last) return bars;
    const curZ = last.z != null ? Number(last.z) : NaN;
    const curSp = last.spread != null ? Number(last.spread) : NaN;
    const needZ = liveZ != null && (!Number.isFinite(curZ) || Math.abs(curZ - liveZ) > 1e-6);
    const needSp = liveSp != null && (!Number.isFinite(curSp) || Math.abs(curSp - liveSp) > 1e-6);
    if (!needZ && !needSp) return bars;
    const out = bars.slice();
    const row = { ...last };
    if (needZ) {
      row.z = liveZ;
      // Forming bar: flat Z OHLC at live tip — do not leave stale z_open/high/low
      // (or let prevZ→close invent a wick down to entry / sidecar gap).
      row.z_open = liveZ;
      row.z_high = liveZ;
      row.z_low = liveZ;
    }
    if (needSp) {
      row.spread = liveSp;
      // Close = live (жёлтая last-value на оси). Open/high/low: keep the
      // forming minute, do not restore a flattened needle wick.
      const so = row.spread_open != null && Number.isFinite(Number(row.spread_open))
        ? Number(row.spread_open) : liveSp;
      const sh = row.spread_high != null && Number.isFinite(Number(row.spread_high))
        ? Number(row.spread_high) : liveSp;
      const sl = row.spread_low != null && Number.isFinite(Number(row.spread_low))
        ? Number(row.spread_low) : liveSp;
      row.spread_open = so;
      row.spread_high = Math.max(so, sh, sl, liveSp);
      row.spread_low = Math.min(so, sh, sl, liveSp);
    }
    out[out.length - 1] = row;
    return out;
  }

  function syncChartSeriesByTime(zData, spreadPts) {
    const zBy = new Map();
    for (const c of zData || []) {
      if (c && c.time != null && !zBy.has(c.time)) zBy.set(c.time, c);
    }
    const spBy = new Map();
    for (const p of spreadPts || []) {
      if (p && p.time != null && Number.isFinite(Number(p.value)) && !spBy.has(p.time)) {
        spBy.set(p.time, p);
      }
    }
    const times = [];
    for (const t of zBy.keys()) {
      if (spBy.has(t)) times.push(t);
    }
    times.sort((a, b) => a - b);
    return {
      zData: times.map((t) => zBy.get(t)),
      spreadPts: times.map((t) => spBy.get(t)),
    };
  }

  function resolveOpenEntryOnBars(bars, open) {
    if (!open || !bars || !bars.length) return null;
    const entryZ = Number(open.entry_z);
    const entrySp = Number(open.entry_spread);
    const entrySec = toChartTime(open.entry_time);
    if (entrySec == null) return null;

    const barTimes = [];
    const byTime = new Map();
    for (const b of bars) {
      const t = toChartTime(b.time, b.timestampMs);
      if (t == null) continue;
      barTimes.push(t);
      if (!byTime.has(t)) byTime.set(t, b);
    }
    if (!barTimes.length) return null;

    const maxSnap = Math.max(90, Math.round(estimateBarStepSec(barTimes) * 1.5));
    const snapped = snapSecToBarTimes(entrySec, barTimes, maxSnap);
    const z0 = Number.isFinite(entryZ) ? entryZ : NaN;
    const sp0 = Number.isFinite(entrySp) ? entrySp : NaN;

    // Prefer real entry_time on the axis; snap only to a nearby tip bar.
    if (snapped != null) {
      const b = byTime.get(snapped);
      const barSp = b && b.spread != null ? Number(b.spread) : NaN;
      return {
        time: snapped,
        z: Number.isFinite(z0) ? z0 : Number(b && b.z),
        spread: Number.isFinite(sp0) ? sp0 : (Number.isFinite(barSp) ? barSp : null),
        tradeDate: open.entry_time || (b && b.time) || null,
      };
    }

    const first = barTimes[0];
    const last = barTimes[barTimes.length - 1];
    // Inside (or just past) the painted window — keep exact entry time (inject later).
    if (entrySec >= first - maxSnap && entrySec <= last + maxSnap) {
      return {
        time: entrySec,
        z: Number.isFinite(z0) ? z0 : 0,
        spread: Number.isFinite(sp0) ? sp0 : null,
        tradeDate: open.entry_time,
        synthetic: true,
      };
    }
    // Entry AFTER series end (stale tip ending Fri, entry today) — never snap
    // to the last Friday candle; keep real entry_time for axis inject.
    if (entrySec > last + maxSnap) {
      return {
        time: entrySec,
        z: Number.isFinite(z0) ? z0 : 0,
        spread: Number.isFinite(sp0) ? sp0 : null,
        tradeDate: open.entry_time,
        synthetic: true,
      };
    }
    // Entry before chart window — omit marker.
    return null;
  }

  function barSpreadAtChartSec(bars, chartSec) {
    if (chartSec == null || !Array.isArray(bars) || !bars.length) return null;
    for (const b of bars) {
      const t = toChartTime(b.time, b.timestampMs);
      if (t !== chartSec) continue;
      const sp = b.spread != null ? Number(b.spread) : NaN;
      if (Number.isFinite(sp)) return sp;
    }
    return null;
  }

  function deskSpreadChartValue(spreadHint, chartSec, zHint, bars) {
    if (chartPrimarySpread) {
      if (spreadHint != null && Number.isFinite(Number(spreadHint))) return Number(spreadHint);
      const fromBar = barSpreadAtChartSec(bars, chartSec);
      if (fromBar != null) return fromBar;
      if (chartSec != null && D().zPriceByTime.has(chartSec)) {
        const v = Number(zPriceByTime.get(chartSec));
        if (Number.isFinite(v)) return v;
      }
      return NaN;
    }
    if (zHint != null && Number.isFinite(Number(zHint))) return Number(zHint);
    return NaN;
  }

  function injectOpenEntryIntoChartSeries(zData, spreadPts, open, bars, {
    primarySpread = false,
  } = {}) {

  function deskTradeById(id) {
    if (!id) return null;
    return D().lastDeskTrades.find((t) => t.id === id) || null;
  }

  function markerChartPrice(m) {
    const trade = deskTradeById(m.tradeId || m.text);
    if (!trade) return 0;
    const chartSec = m.time;
    if (chartPrimarySpread) {
      if (m.isEntry) {
        const v = deskSpreadChartValue(
          trade.entrySpread, chartSec, trade.entryZ, D().lastDeskPaintBars,
        );
        return Number.isFinite(v) ? v : 0;
      }
      const v = deskSpreadChartValue(
        trade.exitSpread != null ? trade.exitSpread : trade.entrySpread,
        chartSec,
        trade.exitSpread != null ? trade.exitZ : trade.entryZ,
        D().lastDeskPaintBars,
      );
      return Number.isFinite(v) ? v : 0;
    }
    return m.isEntry ? trade.entryZ : (trade.exitZ ?? trade.entryZ);
  }

  function markerScreenPosition(m) {
    if (!D().zChart || !D().zSeries) return null;
    const x = zChart.timeScale().timeToCoordinate(m.time);
    if (x == null) return null;
    const price = markerChartPrice(m);
    let y = zSeries.priceToCoordinate(price);
    if (y == null) return null;
    const yOffset = m.isEntry ? (m.position === 'belowBar' ? 18 : -18) : 0;
    return { x, y: y + yOffset };
  }

  function findNearestDeskMarkerAtPoint(point) {
    if (!D().lastDeskMarkers.length || !D().zChart) return null;
    let bestPixel = null;
    for (const m of D().lastDeskMarkers) {
      const pos = markerScreenPosition(m);
      const x = pos?.x ?? zChart.timeScale().timeToCoordinate(m.time);
      if (x == null) continue;
      const dx = Math.abs(point.x - x);
      const hitRadiusXPx = m.isEntry ? MARKER_ENTRY_HIT_RADIUS_X_PX : MARKER_HIT_RADIUS_X_PX;
      let pixelHit = false;
      let pixelScore = Number.POSITIVE_INFINITY;
      if (pos) {
        const dy = Math.abs(point.y - pos.y);
        const dist = Math.hypot(dx, dy);
        pixelHit = dist <= MARKER_HIT_RADIUS_PX || dx <= hitRadiusXPx;
        pixelScore = dx * 1.5 + dy;
      } else {
        pixelHit = dx <= hitRadiusXPx;
        pixelScore = dx;
      }
      if (pixelHit && (!bestPixel || pixelScore < bestPixel.score)) {
        bestPixel = { marker: m, score: pixelScore };
      }
    }
    return bestPixel?.marker ?? null;
  }

  function buildMarkerRenderData(markers, activeTradeId) {
    return markers.map((m) => {
      const isActive = !!activeTradeId && (m.tradeId === activeTradeId || m.text === activeTradeId);
      return {
        time: m.time,
        position: m.position,
        color: isActive ? TRADE_HIGHLIGHT_COLOR : m.color,
        shape: m.shape,
        text: m.text,
        size: isActive ? Math.max((m.size || 2.5) * 1.15, 2.8) : (m.size || 2.5),
      };
    });
  }

  function setHighlightSeriesData(data) {
    if (!D().openHighlightSeries) return;
    try {
      D().openHighlightSeries.setData(data || []);
    } catch (e) {
      console.warn('trade highlight', e);
    }
  }

  function highlightDataForTrade(trade) {
    if (!trade || trade.entryTime == null) return [];
    const exitTime = trade.exitTime ?? trade.entryTime;
    let entryVal;
    let exitVal;
    if (chartPrimarySpread) {
      entryVal = deskSpreadChartValue(
        trade.entrySpread, trade.entryTime, trade.entryZ, D().lastDeskPaintBars,
      );
      exitVal = deskSpreadChartValue(
        trade.exitSpread != null ? trade.exitSpread : trade.entrySpread,
        exitTime,
        trade.exitSpread != null ? trade.exitZ : trade.entryZ,
        D().lastDeskPaintBars,
      );
    } else {
      entryVal = Number(trade.entryZ);
      exitVal = Number(trade.exitZ ?? trade.entryZ);
    }
    if (!Number.isFinite(entryVal) || exitTime == null || !Number.isFinite(exitVal)) return [];
    return [
      { time: trade.entryTime, value: entryVal },
      { time: exitTime, value: exitVal },
    ].sort((a, b) => a.time - b.time);
  }

  function applyHighlightForActiveTrade() {
    const trade = deskTradeById(hoverTradeId);
    if (trade) {
      setHighlightSeriesData(highlightDataForTrade(trade));
      return;
    }
    setHighlightSeriesData(D().defaultOpenHighlightData || []);
  }

  function refreshDeskMarkers() {
    applyTradeMarkers(
      buildMarkerRenderData(D().lastDeskMarkers, D().hoverTradeId),
      buildMarkerRenderData(lastMtlrMarkers, null),
    );
    applyHighlightForActiveTrade();
  }

  function scheduleRefreshDeskMarkers() {
    if (D().refreshMarkersTimer) clearTimeout(D().refreshMarkersTimer);
    D().refreshMarkersTimer = setTimeout(() => {
      D().refreshMarkersTimer = 0;
      refreshDeskMarkers();
    }, 100);
  }

  function onDeskMarkerCrosshair(param) {
    if (D().userGestureActive || D().suppressRangeEvents) return;
    let nextHover = null;
    if (param.point && param.point.x >= 0 && param.point.y >= 0) {
      const marker = findNearestDeskMarkerAtPoint(param.point);
      if (marker && deskTradeById(marker.tradeId || marker.text)) {
        nextHover = marker.tradeId || marker.text || null;
      }
    }
    if (nextHover === D().hoverTradeId) return;
    D().hoverTradeId = nextHover;
    applyHighlightForActiveTrade();
    scheduleRefreshDeskMarkers();
  }

  function bindMarkerHover() {
    if (D().markerHoverBound || !D().zChart) return;
    D().markerHoverBound = true;
    D().zChart.subscribeCrosshairMove((param) => onDeskMarkerCrosshair(param));
  }

  function clearOpenTradeOnChart() {
    D().lastOpenTradeFp = '';
    D().defaultOpenHighlightData = null;
    if (!D().hoverTradeId) setHighlightSeriesData([]);
    else applyHighlightForActiveTrade();
    const el = $('tradeOpenTradeOverlay');
    if (el) {
      el.classList.add('hidden');
      el.innerHTML = '';
    }
  }

  function clearAllTradeMarkers() {
    D().hoverTradeId = null;
    D().lastDeskMarkers = [];
    D().lastDeskTrades = [];
    clearOpenTradeOnChart();
    try {
      if (D().openMarkersPlugin && typeof D().openMarkersPlugin.setMarkers === 'function') {
        D().openMarkersPlugin.setMarkers([]);
      } else if (D().zSeries && typeof D().zSeries.setMarkers === 'function') {
        D().zSeries.setMarkers([]);
      }
    } catch (_) {}
    try {
      if (D().openSpreadMarkersPlugin && typeof D().openSpreadMarkersPlugin.setMarkers === 'function') {
        D().openSpreadMarkersPlugin.setMarkers([]);
      } else if (D().spreadSeries && typeof D().spreadSeries.setMarkers === 'function') {
        D().spreadSeries.setMarkers([]);
      }
    } catch (_) {}
  }

  function applyMarkersToSeries(series, pluginRef, markerData) {
    if (!series) return pluginRef;
    try {
      if (LightweightCharts.createSeriesMarkers) {
        if (!pluginRef) {
          return LightweightCharts.createSeriesMarkers(series, markerData);
        }
        if (typeof pluginRef.setMarkers === 'function') {
          pluginRef.setMarkers(markerData);
        }
        return pluginRef;
      }
      if (typeof series.setMarkers === 'function') {
        series.setMarkers(markerData);
      }
    } catch (e) {
      console.warn('trade markers', e);
    }
    return pluginRef;
  }

  function applyTradeMarkers(tatnMarkerData, mtlrMarkerData) {
    D().openMarkersPlugin = applyMarkersToSeries(
      D().zSeries, D().openMarkersPlugin, tatnMarkerData || [],
    );
    D().openSpreadMarkersPlugin = applyMarkersToSeries(
      D().spreadSeries, D().openSpreadMarkersPlugin, mtlrMarkerData || [],
    );
  }

  function estimateBarStepSec(barTimes) {
    if (!barTimes || barTimes.length < 2) return 60;
    const diffs = [];
    const n = Math.min(barTimes.length, 80);
    const start = Math.max(1, barTimes.length - n);
    for (let i = start; i < barTimes.length; i++) {
      const d = barTimes[i] - barTimes[i - 1];
      if (d > 0 && d < 7200) diffs.push(d);
    }
    if (!diffs.length) return 60;
    diffs.sort((a, b) => a - b);
    return diffs[Math.floor(diffs.length / 2)] || 60;
  }

  function snapSecToBarTimes(sec, barTimes, maxDeltaSec) {
    if (sec == null || !barTimes.length) return null;
    let best = barTimes[0];
    let bestD = Math.abs(best - sec);
    for (const t of barTimes) {
      const d = Math.abs(t - sec);
      if (d < bestD) {
        best = t;
        bestD = d;
      }
    }
    const lim = maxDeltaSec != null
      ? maxDeltaSec
      : Math.max(90, Math.round(estimateBarStepSec(barTimes) * 1.5));
    if (bestD > lim) return null;
    return best;
  }

  function isManualTradeSource(src) {
    const s = String(src || '').toUpperCase();
    return s === 'MANUAL' || s === 'BROKER' || s.includes('ПОДХВАТ') || s.includes('ADOPT');
  }

  function isSpreadLevelModeOn(settings, mark) {
    if (mark && mark.spread_level_mode === false) return false;
    if (mark && mark.spread_level_mode === true) return true;
    if (!settings) return true;
    if (settings.spread_level_mode === false) return false;
    if (settings.spread_level_mode === true || settings.spread_level_mode == null) return true;
    return String(settings.spread_level_mode) === '1';
  }

  function sanitizeOpenRisk(mark, settings) {
    const flags = Array.isArray(mark && mark.risk_flags) ? mark.risk_flags.slice() : [];
    let score = Number(mark && mark.risk_score);
    if (!Number.isFinite(score)) score = 0;
    const levelIn = mark && mark.risk_level;
    if (!isSpreadLevelModeOn(settings, mark)) {
      return {
        flags,
        score,
        level: levelIn || 'Ok',
        red: !!(mark && mark.risk_red),
      };
    }
    const kept = [];
    for (const f of flags) {
      const s = String(f || '');
      if (/Zвх/i.test(s) || /Zвx/i.test(s)) {
        score -= 2;
        continue;
      }
      if (/\|Z\|/.test(s) || /порог\s*Z/i.test(s)) {
        score -= 1;
        continue;
      }
      kept.push(f);
    }
    if (score < 0) score = 0;
    const level = score >= 6 ? 'Critical' : score >= 4 ? 'High' : score >= 3 ? 'Elevated' : 'Ok';
    return { flags: kept, score, level, red: score >= 4 };
  }

  function deskEntryMarkerColor(isLong, source) {
    if (isManualTradeSource(source)) {
      return isLong ? '#4FC3F7' : '#CE93D8';
    }
    return isLong ? '#69F0AE' : '#FF8A80';
  }

  function buildDeskTradeMarkers(closed, open, bars) {
    const barTimes = [];
    for (const b of bars || []) {
      const t = toChartTime(b.time, b.timestampMs);
      if (t != null) barTimes.push(t);
    }
    const maxSnap = Math.max(90, Math.round(estimateBarStepSec(barTimes) * 1.5));
    const markers = [];
    const trades = [];
    const list = [...(closed || [])].sort((a, b) => {
      const am = toChartTime(a.entry_time) || 0;
      const bm = toChartTime(b.entry_time) || 0;
      return am - bm;
    });
    let n = 0;
    const dirShort = (isLong) => (typeof tradeDirectionShort === 'function'
      ? tradeDirectionShort(isLong ? 'Long' : 'Short')
      : (isLong ? 'L' : 'S'));

    for (const t of list) {
      n += 1;
      const isLong = String(t.direction || '').toUpperCase().includes('LONG');
      // Номера 1L…N как в таблице; маркеры только если вход/выход попадает в окно графика.
      const entrySec = snapSecToBarTimes(toChartTime(t.entry_time), barTimes, maxSnap);
      const exitSec = snapSecToBarTimes(toChartTime(t.exit_time), barTimes, maxSnap);
      const label = `${n}${dirShort(isLong)}`;
      const tradeId = t.id != null ? String(t.id) : `desk-${n}`;
      const entryZ = Number(t.entry_z);
      const exitZ = Number(t.exit_z);
      const entrySp = Number(t.entry_spread);
      const exitSp = Number(t.exit_spread);
      const entrySpreadResolved = deskSpreadChartValue(entrySp, entrySec, entryZ, bars);
      const exitSpreadResolved = deskSpreadChartValue(exitSp, exitSec, exitZ, bars);
      const entryColor = deskEntryMarkerColor(isLong, t.source);
      if (entrySec != null) {
        trades.push({
          id: tradeId,
          entryTime: entrySec,
          entryZ: Number.isFinite(entryZ) ? entryZ : 0,
          exitTime: exitSec != null ? exitSec : entrySec,
          exitZ: Number.isFinite(exitZ) ? exitZ : (Number.isFinite(entryZ) ? entryZ : 0),
          entrySpread: Number.isFinite(entrySpreadResolved) ? entrySpreadResolved : null,
          exitSpread: Number.isFinite(exitSpreadResolved) ? exitSpreadResolved : null,
          open: false,
        });
        markers.push({
          time: entrySec,
          position: isLong ? 'belowBar' : 'aboveBar',
          color: entryColor,
          shape: isLong ? 'arrowUp' : 'arrowDown',
          text: label,
          size: 2.5,
          tradeId,
          isEntry: true,
        });
      }
      if (exitSec != null && exitSec !== entrySec) {
        markers.push({
          time: exitSec,
          position: 'inBar',
          color: isManualTradeSource(t.source) ? '#B39DDB' : '#FFCC80',
          shape: 'circle',
          text: label,
          size: 2.5,
          tradeId,
          isEntry: false,
        });
      }
    }

    if (open) {
      n += 1;
      const isLong = String(open.direction || '').toUpperCase() === 'LONG';
      const entry = resolveOpenEntryOnBars(bars, open);
      const last = bars && bars.length ? bars[bars.length - 1] : null;
      const exitTime = last ? toChartTime(last.time, last.timestampMs) : null;
      const mark = open.mark || {};
      // Prefer aligned last tip bar (same as overlay / last-price), then mark.
      let exitZ = last != null && last.z != null ? Number(last.z) : NaN;
      if (!Number.isFinite(exitZ) && mark.z_now != null) exitZ = Number(mark.z_now);
      let exitSp = last != null && last.spread != null ? Number(last.spread) : NaN;
      if (!Number.isFinite(exitSp) && mark.spread_now != null) exitSp = Number(mark.spread_now);
      const entrySpN = Number(entry && entry.spread != null
        ? entry.spread
        : (mark.fill_spread != null ? mark.fill_spread : open.entry_spread));
      const tradeId = open.id != null ? String(open.id) : `desk-open-${n}`;
      if (entry) {
        trades.push({
          id: tradeId,
          entryTime: entry.time,
          entryZ: entry.z,
          exitTime: exitTime != null ? exitTime : entry.time,
          exitZ: Number.isFinite(exitZ) ? exitZ : entry.z,
          entrySpread: Number.isFinite(entrySpN) ? entrySpN : null,
          exitSpread: Number.isFinite(exitSp) ? exitSp : null,
          open: true,
        });
        markers.push({
          time: entry.time,
          position: isLong ? 'belowBar' : 'aboveBar',
          color: deskEntryMarkerColor(isLong, open.source),
          shape: isLong ? 'arrowUp' : 'arrowDown',
          text: `${n}${dirShort(isLong)}`,
          size: 2.5,
          tradeId,
          isEntry: true,
        });
      }
    }

    markers.sort((a, b) => a.time - b.time || String(a.text).localeCompare(String(b.text)));
    return { markers, trades };
  }

  function updateOpenTradeOnChart(open, bars, closed = [], {
    mtlrOpen = null,
    mtlrBars = null,
    mtlrClosed = null,
  } = {}) {

  function attachDealerMonitorZClient(dealerBars, m15Bars) {
    const src = Array.isArray(dealerBars) ? dealerBars : [];
    if (!src.length) return src;
    if (src.some((b) => b && b.z != null && Number.isFinite(Number(b.z))
      && (b.z_kind === 'dealer_monitor' || (b.source && String(b.source).includes('dealer'))))) {
      return src;
    }
    const m15 = [];
    for (const b of m15Bars || []) {
      if (!b) continue;
      const sp = b.spread != null ? Number(b.spread)
        : (b.spreadPercent != null ? Number(b.spreadPercent) : NaN);
      const ms = Number(b.timestampMs || 0);
      if (!Number.isFinite(sp) || !(ms > 0)) continue;
      m15.push({ ms, sp });
    }
    m15.sort((a, b) => a.ms - b.ms);
    let completedEnd = 0;
    let winStart = 0;
    let total = 0;
    let totalSq = 0;
    const out = [];
    const dayMs = 86_400_000;
    for (const b of src) {
      const row = { ...b, for_z: false, z_kind: 'dealer_monitor' };
      const tipSp = b && b.spread != null ? Number(b.spread) : NaN;
      const tipMs = b ? Number(b.timestampMs || 0) : 0;
      if (!Number.isFinite(tipSp) || !(tipMs > 0)) {
        row.z = null;
        out.push(row);
        continue;
      }
      if (!m15.length) {
        row.z = null;
        out.push(row);
        continue;
      }
      const slotMs = Math.floor(tipMs / M15_MS) * M15_MS;
      while (completedEnd < m15.length && m15[completedEnd].ms < slotMs) {
        const s = m15[completedEnd].sp;
        total += s;
        totalSq += s * s;
        completedEnd += 1;
      }
      const fromMs = tipMs - Z_ROLL_LOOKBACK_DAYS * dayMs;
      while (winStart < completedEnd && m15[winStart].ms < fromMs) {
        const s = m15[winStart].sp;
        total -= s;
        totalSq -= s * s;
        winStart += 1;
      }
      const count = completedEnd - winStart;
      const n = count + 1;
      if (n < Z_ROLL_MIN_BARS) {
        row.z = 0;
      } else {
        const t = total + tipSp;
        const tsq = totalSq + tipSp * tipSp;
        const mean = t / n;
        let std = Math.sqrt(Math.max((tsq / n) - mean * mean, 0));
        if (std <= 1e-12) std = 1;
        row.z = (tipSp - mean) / std;
      }
      out.push(row);
    }
    // Fallback: rolling on dealer spreads alone if M15 window was empty.
    if (out.length && out.every((r) => r.z == null) && out.length >= Z_ROLL_MIN_BARS) {
      for (let i = 0; i < out.length; i += 1) {
        const from = Math.max(0, i - Z_ROLL_MIN_BARS + 1);
        let sum = 0;
        let sumSq = 0;
        let c = 0;
        for (let j = from; j <= i; j += 1) {
          const sp = Number(out[j].spread);
          if (!Number.isFinite(sp)) continue;
          sum += sp;
          sumSq += sp * sp;
          c += 1;
        }
        if (c < 2) {
          out[i].z = 0;
          continue;
        }
        const mean = sum / c;
        let std = Math.sqrt(Math.max((sumSq / c) - mean * mean, 0));
        if (std <= 1e-12) std = 1;
        out[i].z = (Number(out[i].spread) - mean) / std;
      }
    }
    return out;
  }

  function spreadCandleFromBar(b, spClose, prevSp) {
    const sp = Number(spClose);
    if (!Number.isFinite(sp)) return null;
    const so = b && b.spread_open != null ? Number(b.spread_open) : NaN;
    const sh = b && b.spread_high != null ? Number(b.spread_high) : NaN;
    const sl = b && b.spread_low != null ? Number(b.spread_low) : NaN;
    const serverOk = Number.isFinite(so) && Number.isFinite(sh) && Number.isFinite(sl);
    if (serverOk) {
      return {
        open: so,
        high: Math.max(so, sh, sl, sp),
        low: Math.min(so, sh, sl, sp),
        close: sp,
      };
    }
    const open = prevSp == null || !Number.isFinite(prevSp) ? sp : prevSp;
    return {
      open,
      high: Math.max(open, sp),
      low: Math.min(open, sp),
      close: sp,
    };
  }

  function zCandleFromBar(b, zClose, prevZ) {
    const z = Number(zClose);
    if (!Number.isFinite(z)) return null;
    const zo = b && b.z_open != null ? Number(b.z_open) : NaN;
    const zh = b && b.z_high != null ? Number(b.z_high) : NaN;
    const zl = b && b.z_low != null ? Number(b.z_low) : NaN;
    const serverOk = Number.isFinite(zo) && Number.isFinite(zh) && Number.isFinite(zl);
    if (serverOk) {
      // Explicit OHLC (incl. flat live-tip align) — never fall back to prevZ wick.
      return {
        open: zo,
        high: Math.max(zo, zh, zl, z),
        low: Math.min(zo, zh, zl, z),
        close: z,
      };
    }
    const open = prevZ == null || !Number.isFinite(prevZ) ? z : prevZ;
    return {
      open,
      high: Math.max(open, z),
      low: Math.min(open, z),
      close: z,
    };
  }

  function medianBarStepSec(bars) {
    const times = [];
    const seen = new Set();
    for (const b of bars || []) {
      const t = toChartTime(b.time, b.timestampMs);
      if (t == null || seen.has(t)) continue;
      seen.add(t);
      times.push(t);
    }
    if (times.length < 2) return null;
    const diffs = [];
    for (let i = 1; i < Math.min(times.length, 40); i += 1) {
      diffs.push(times[i] - times[i - 1]);
    }
    diffs.sort((a, b) => a - b);
    return diffs[Math.floor(diffs.length / 2)] || 0;
  }

  function buildTip1mZCandles(bars) {
    const pts = [];
    let prevZ = null;
    const seen = new Set();
    const times = [];
    // Warmup sentinel from short M15 (exact 0.0) — skip so price scale isn't
    // pinned to zero while live tip is ~-2.x (looks like a blank Z pane).
    let nonzero = 0;
    for (const b of bars || []) {
      if (b && b.z != null && Math.abs(Number(b.z)) > 1e-12) nonzero += 1;
    }
    const skipExactZero = nonzero >= 8;
    for (const b of bars || []) {
      const t = toChartTime(b.time, b.timestampMs);
      if (t == null || b.z == null || Number.isNaN(Number(b.z)) || seen.has(t)) continue;
      // Dealer mid rows without display Z — skipped. Never use TATN price as Z.
      if (b.interval === '1m' && b.source && String(b.source).includes('dealer') && b.z == null) continue;
      const z = Number(b.z);
      if (!Number.isFinite(z)) continue;
      if (skipExactZero && Math.abs(z) < 1e-12) continue;
      seen.add(t);
      times.push(t);
      const candle = zCandleFromBar(b, z, prevZ);
      if (!candle) continue;
      pts.push({ time: t, ...candle });
      prevZ = z;
    }
    // Forming right-edge bar: OHLC = close (no prevZ body / entry-line wick).
    if (pts.length) {
      const last = pts[pts.length - 1];
      const c = Number(last.close);
      if (Number.isFinite(c)) {
        pts[pts.length - 1] = { time: last.time, open: c, high: c, low: c, close: c };
      }
    }
    if (pts.length < 2) return pts;
    const diffs = [];
    for (let i = 1; i < Math.min(times.length, 40); i += 1) {
      diffs.push(times[i] - times[i - 1]);
    }
    diffs.sort((a, b) => a - b);
    const med = diffs[Math.floor(diffs.length / 2)] || 0;
    // M15 masquerading as tip1m → reject (HARD RULE)
    if (med >= 600) return [];
    return pts;
  }

  function buildTip1mChartSeries(bars) {
    const zPts = buildTip1mZCandles(bars);
    if (zPts.length < 2) return { zPts: [], spreadPts: [] };
    const spByTime = new Map();
    for (const b of bars || []) {
      const t = toChartTime(b.time, b.timestampMs);
      if (t == null || spByTime.has(t)) continue;
      const sp = b.spread != null ? Number(b.spread) : NaN;
      if (!Number.isFinite(sp)) continue;
      spByTime.set(t, sp);
    }
    const zSynced = [];
    const spreadPts = [];
    for (const c of zPts) {
      if (!spByTime.has(c.time)) continue;
      zSynced.push(c);
      spreadPts.push({ time: c.time, value: spByTime.get(c.time) });
    }
    return { zPts: zSynced, spreadPts };
  }

  function tip1mSpanDays(bars) {
    if (!Array.isArray(bars) || bars.length < 2) return 0;
    const msOf = (b) => {
      const ms = Number(b && b.timestampMs);
      if (Number.isFinite(ms) && ms > 0) return ms;
      const s = String((b && (b.time || b.tradeDate)) || '').replace('T', ' ').trim();
      const t = Date.parse(s.length === 16 ? `${s}:00` : s);
      return Number.isFinite(t) ? t : 0;
    };
    let first = 0;
    let last = 0;
    for (let i = 0; i < bars.length; i++) {
      first = msOf(bars[i]);
      if (first > 0) break;
    }
    for (let i = bars.length - 1; i >= 0; i--) {
      last = msOf(bars[i]);
      if (last > 0) break;
    }
    if (first <= 0 || last <= first) return 0;
    return (last - first) / 86_400_000;
  }

  function filterTip1mBarsByDays(bars, wantDays) {
    const d = Math.max(1, Number(wantDays) || 7);
    if (!Array.isArray(bars) || bars.length < 2) return bars || [];
    let lastMs = 0;
    for (let i = bars.length - 1; i >= 0; i--) {
      const ms = Number(bars[i] && bars[i].timestampMs);
      if (Number.isFinite(ms) && ms > 0) { lastMs = ms; break; }
    }
    if (lastMs <= 0) return bars;
    const cut = lastMs - d * 86_400_000;
    return bars.filter((b) => Number(b && b.timestampMs) >= cut);
  }

  function thinDeskTip1mBars(bars, maxPoints = 2500) {
    if (!Array.isArray(bars) || bars.length <= maxPoints) {
      return { bars: bars || [], stepMin: 1, rawCount: (bars || []).length };
    }
    const step = Math.max(2, Math.ceil(bars.length / maxPoints));
    const out = [];
    let group = [];
    let groupDay = '';

    const num = (v) => {
      const n = Number(v);
      return Number.isFinite(n) ? n : null;
    };
    const dayOf = (b) => String((b && (b.time || b.tradeDate)) || '').slice(0, 10);
    const flush = () => {
      if (!group.length) return;
      const first = group[0];
      const last = group[group.length - 1];
      const spVals = [];
      const zVals = [];
      for (const b of group) {
        for (const key of ['spread', 'spread_open', 'spread_high', 'spread_low']) {
          const v = num(b && b[key]);
          if (v != null) spVals.push(v);
        }
        for (const key of ['z', 'z_open', 'z_high', 'z_low']) {
          const v = num(b && b[key]);
          if (v != null) zVals.push(v);
        }
      }
      const row = {
        ...last,
        time: last.time || last.tradeDate,
        timestampMs: last.timestampMs,
        interval: `${step}m`,
        display_step_min: step,
      };
      const spOpen = num(first.spread_open) ?? num(first.spread);
      const spClose = num(last.spread);
      if (spOpen != null && spClose != null && spVals.length) {
        row.spread = spClose;
        row.spread_open = spOpen;
        row.spread_high = Math.max(...spVals, spOpen, spClose);
        row.spread_low = Math.min(...spVals, spOpen, spClose);
      }
      const zOpen = num(first.z_open) ?? num(first.z);
      const zClose = num(last.z);
      if (zOpen != null && zClose != null && zVals.length) {
        row.z = zClose;
        row.z_open = zOpen;
        row.z_high = Math.max(...zVals, zOpen, zClose);
        row.z_low = Math.min(...zVals, zOpen, zClose);
      }
      out.push(row);
      group = [];
    };

    for (const b of bars) {
      const day = dayOf(b);
      if (group.length && (group.length >= step || day !== groupDay)) flush();
      if (!group.length) groupDay = day;
      group.push(b);
    }
    flush();
    return { bars: out, stepMin: step, rawCount: bars.length };
  }

  async function ensureWeekdayTip1mBars(data) {
    if (!data || data.weekend_monitor) return data;
    const mode = String(data.bars_mode || '');
    if (mode.startsWith('dealer')) return data;
    // Chip intent wins over desk payload (old API may coerce 180→7 until reload).
    const wantDays = Number(days || data.days || 7) || 7;
    const med = medianBarStepSec(data.bars);
    const span = tip1mSpanDays(data.bars);
    // Session-only (~<0.6д) is fine for 1Д; multi-day chips need real lookback.
    const coversPeriod = wantDays <= 1
      ? (Array.isArray(data.bars) && data.bars.length >= 2)
      : span >= Math.min(wantDays * 0.45, wantDays - 0.4);
    const looksTip = mode === 'tip1m' && (med == null || med < 600)
      && Array.isArray(data.bars) && data.bars.length >= 2
      && coversPeriod;
    if (looksTip) return data;

    const issKeep = (data.bars_iss && data.bars_iss.length)
      ? data.bars_iss
      : (mode === 'iss_m15' ? (data.bars || []) : (data.bars_iss || []));

    let tipBars = null;
    // 1) Sidecar JSON (works without uvicorn reload while SHORT is open)
    try {
      const r = await fetch(`/static/desk_tip1m.json?v=${Date.now()}`, { cache: 'no-store' });
      if (r.ok) {
        const j = await r.json();
        const bars = filterTip1mBarsByDays(j && j.bars, wantDays);
        const spanSide = tip1mSpanDays(bars);
        const okSpan = wantDays <= 1 || spanSide >= Math.min(wantDays * 0.45, wantDays - 0.4);
        if (Array.isArray(bars) && bars.length >= 5 && (medianBarStepSec(bars) || 9999) < 600 && okSpan) {
          tipBars = bars;
        }
      }
    } catch (_) { /* ignore */ }

    // 2) Testing /api/bars1m parquet (may lag vs live tip)
    if (!tipBars) {
      try {
        const d = wantDays;
        const r = await fetch(`/api/bars1m?csv=${encodeURIComponent('m15_tatn_255d.csv')}&chartDays=${d}`);
        if (r.ok) {
          const j = await r.json();
          const raw = j && j.bars;
          if (Array.isArray(raw) && raw.length >= 5) {
            const mapped = raw.map((b) => ({
              time: b.tradeDate || b.time,
              timestampMs: b.timestampMs,
              z: b.zScore != null ? b.zScore : b.z,
              spread: b.spreadPercent != null ? b.spreadPercent : b.spread,
              tatn: b.tatnClose != null ? b.tatnClose : b.tatn,
              tatnp: b.tatnpClose != null ? b.tatnpClose : b.tatnp,
              interval: '1m',
              source: 'tip1m',
              for_z: true,
            }));
            if ((medianBarStepSec(mapped) || 9999) < 600) tipBars = mapped;
          }
        }
      } catch (_) { /* ignore */ }
    }

    if (tipBars && tipBars.length) {
      const last = tipBars[tipBars.length - 1];
      const summary = { ...(data.summary || {}) };
      summary.bars_mode = 'tip1m';
      summary.window_count = tipBars.length;
      // Do not replace live markets S% with a stale parquet/sidecar tip.
      const tipMs = Number(last && last.timestampMs) || 0;
      const mktTd = String(summary.trade_date || '').replace('T', ' ').trim();
      let mktMs = 0;
      if (mktTd.length >= 16) {
        const p = Date.parse(mktTd.replace(' ', 'T') + '+03:00');
        if (Number.isFinite(p)) mktMs = p;
      }
      const tipStale = tipMs > 0 && mktMs > 0 && (mktMs - tipMs) > 45 * 60 * 1000;
      // Prefer live tip (monitor / open mark) over sidecar/parquet Z on the tail.
      const live = liveTipZSpread(data);
      let barsOut = tipBars;
      if (!tipStale && (live.z != null || live.sp != null)) {
        barsOut = alignTip1mBarsToLiveTip(tipBars, data);
        const aligned = barsOut[barsOut.length - 1] || last;
        if (aligned.z != null) summary.z = aligned.z;
        if (aligned.spread != null) summary.spread = aligned.spread;
        if (aligned.time) summary.trade_date = aligned.time;
      } else if (!tipStale) {
        if (last.z != null) summary.z = last.z;
        if (last.spread != null) summary.spread = last.spread;
        if (last.time) summary.trade_date = last.time;
      }
      return {
        ...data,
        days: wantDays,
        bars: barsOut,
        bars_iss: issKeep,
        bars_mode: 'tip1m',
        tip1m_warming: tipStale ? true : data.tip1m_warming,
        partial: tipStale ? true : data.partial,
        summary,
      };
    }

    // HARD RULE: empty tip1m > M15 under tip1m label
    return {
      ...data,
      bars: [],
      bars_iss: issKeep,
      bars_mode: 'tip1m',
      partial: true,
      tip1m_warming: true,
      summary: { ...(data.summary || {}), bars_mode: 'tip1m' },
    };
  }

  async function ensureMtlrChartBars(bars, wantDays) {
    const d = Math.max(1, Number(wantDays) || 7);
    const span = tip1mSpanDays(bars);
    const covers = d <= 30
      ? (Array.isArray(bars) && bars.length >= 2)
      : span >= Math.min(d * 0.45, d - 0.4);
    if (Array.isArray(bars) && bars.length >= 5 && covers) return bars;
    try {
      const r = await fetch(`/static/desk_mtlr_m15.json?v=${Date.now()}`, { cache: 'no-store' });
      if (r.ok) {
        const j = await r.json();
        const raw = filterTip1mBarsByDays(j && j.bars, d);
        const spanSide = tip1mSpanDays(raw);
        const okSpan = d <= 30 || spanSide >= Math.min(d * 0.45, d - 0.4);
        if (Array.isArray(raw) && raw.length >= 5 && okSpan) return raw;
      }
    } catch (_) { /* keep API bars */ }
    return bars || [];
  }

  function buildSpread1mChartSeries(bars) {
    const candlePts = [];
    const spreadPts = [];
    const seen = new Set();
    let prevSp = null;
    for (const b of bars || []) {
      if (!b) continue;
      const t = toChartTime(b.time, b.timestampMs);
      if (t == null || seen.has(t)) continue;
      const sp = b.spread != null ? Number(b.spread) : NaN;
      if (!Number.isFinite(sp)) continue;
      seen.add(t);
      spreadPts.push({ time: t, value: sp });
      const candle = spreadCandleFromBar(b, sp, prevSp);
      if (!candle) continue;
      candlePts.push({ time: t, ...candle });
      prevSp = sp;
    }
    // Reject M15 masquerading as 1m (≥10m median step), но разрешить
    // явно агрегированный для отрисовки длинный tip1m.
    if (candlePts.length >= 2) {
      const diffs = [];
      for (let i = 1; i < Math.min(candlePts.length, 40); i += 1) {
        diffs.push(candlePts[i].time - candlePts[i - 1].time);
      }
      diffs.sort((a, b) => a - b);
      const med = diffs[Math.floor(diffs.length / 2)] || 0;
      const displayAggregated = (bars || []).some(
        (b) => Number(b && b.display_step_min) > 1,
      );
      if (med >= 600 && !displayAggregated) return { zPts: [], spreadPts: [] };
    }
    return { zPts: candlePts, spreadPts };
  }

  function buildSpreadM15ChartSeries(bars) {
    const candlePts = [];
    const seen = new Set();
    let prevSp = null;
    for (const b of bars || []) {
      if (!b) continue;
      const t = toChartTime(b.time, b.timestampMs);
      if (t == null || seen.has(t)) continue;
      const sp = b.spread != null ? Number(b.spread) : NaN;
      if (!Number.isFinite(sp)) continue;
      seen.add(t);
      const candle = spreadCandleFromBar(b, sp, prevSp);
      if (!candle) continue;
      candlePts.push({ time: t, ...candle });
      prevSp = sp;
    }
    return candlePts;
  }

  function chartPointVal(p) {
    if (!p) return NaN;
    if (p.value != null) return Number(p.value);
    return Number(p.close);
  }

  function chartPrefixEqual(prev, next) {
    if (!prev || !next || !prev.length || next.length < prev.length) return false;
    for (let i = 0; i < prev.length; i += 1) {
      if (prev[i].time !== next[i].time) return false;
      const a = chartPointVal(prev[i]);
      const b = chartPointVal(next[i]);
      if (!Number.isFinite(a) || !Number.isFinite(b)) return false;
      if (Math.abs(a - b) > 1e-9) return false;
    }
    return true;
  }

  function canTailUpdateChart(prev, next) {
    if (!prev || !next || !prev.length) return false;
    if (next.length > prev.length + 1) return false;
    if (next.length === prev.length + 1) {
      return chartPrefixEqual(prev, next.slice(0, prev.length));
    }
    if (next.length === prev.length) return true;
    return false;
  }

  function applyTailChartUpdate(zData, mtlrCandlePts) {
    if (!D().zSeries || !zData.length) return false;
    const tail = zData[zData.length - 1];
    if (D().zSeriesIsLine) {
      D().zSeries.update({
        time: tail.time,
        value: chartPointVal(tail),
      });
    } else {
      D().zSeries.update(tail);
    }
    if (D().spreadSeries && mtlrCandlePts && mtlrCandlePts.length) {
      const mTail = mtlrCandlePts[mtlrCandlePts.length - 1];
      if (D().spreadSeriesIsLine) {
        D().spreadSeries.update({ time: mTail.time, value: chartPointVal(mTail) });
      } else {
        D().spreadSeries.update(mTail);
      }
    }
    D().lastPaintZData = zData;
    D().lastPaintMtlrData = mtlrCandlePts;
    return true;
  }

  function renderCharts(bars, entry, exitZ, openTrade = null, closedTrades = [], {
    dealer1m = false,
    weekendMonitor = false,
    zBars = null,
    spreadLevels = null,
    spreadCuts = null,
    lookbackDays = null,
    mtlrBars = null,
    mtlrLevels = null,
    mtlrOpen = null,
    mtlrClosed = null,
    corridor = null,
    closeForecast = null,
    settings = null,
  } = {}) {

  function renderOpen(open, { barsMode = '', bars = null, settings = null } = {}) {

  function resize() {
    const zEl = $('tradeZChart');
    const sEl = $('tradeSpreadChart');
    D().suppressRangeEvents = true;
    try {
      if (D().zChart && zEl) D().zChart.applyOptions({ width: zEl.clientWidth, height: zEl.clientHeight || 300 });
      if (D().spreadChart && sEl) D().spreadChart.applyOptions({ width: sEl.clientWidth, height: sEl.clientHeight || 150 });
      equalizePriceScales();
    } finally {
      scheduleEndSuppress();
    }
    if (D().pinnedRange) {
      requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          reassertPinnedRange();
          forceSyncAfterPaint();
        });
      });
    }
  }

  function spreadPaneHeadHeight(stackEl) {
    const heads = stackEl.querySelectorAll('.trade-pane-spread-wrap .pnl-head');
    let h = 0;
    heads.forEach((el) => { h += el.offsetHeight || 28; });
    return h || 28;
  }

  function loadSpreadChartHeight() {
    const saved = parseInt(localStorage.getItem(LS_SPREAD_PANE_HEIGHT) || '', 10);
    if (!Number.isFinite(saved)) return TRADE_SPREAD_DEFAULT;
    return Math.max(TRADE_SPREAD_MIN, saved);
  }

  function spreadChartHeightBounds(stackEl) {
    const headH = spreadPaneHeadHeight(stackEl);
    const zHead = stackEl.querySelector('.trade-pane-z-wrap .pnl-head');
    const zHeadH = zHead?.offsetHeight || 28;
    const total = stackEl.clientHeight - CHART_SPLITTER_HEIGHT - headH - zHeadH;
    const max = Math.max(TRADE_SPREAD_MIN, total - TRADE_Z_MIN);
    return { min: TRADE_SPREAD_MIN, max };
  }

  function applySpreadChartHeight(heightPx) {
    const stack = $('tradeChartStack');
    if (!stack) return TRADE_SPREAD_DEFAULT;
    if (stack.clientHeight <= 0) {
      const h = Math.round(Math.max(TRADE_SPREAD_MIN, heightPx));
      stack.style.setProperty('--trade-spread-chart-height', `${h}px`);
      return h;
    }
    const { min, max } = spreadChartHeightBounds(stack);
    const h = Math.round(Math.max(min, Math.min(max, heightPx)));
    stack.style.setProperty('--trade-spread-chart-height', `${h}px`);
    requestAnimationFrame(() => {
      requestAnimationFrame(resize);
    });
    return h;
  }

  function bindTradeChartVerticalSplit() {
    const divider = $('tradeSplitDividerH');
    const stack = $('tradeChartStack');
    const paneChart = $('tradeSpreadChart');
    if (!divider || !stack || !paneChart) return;

    applySpreadChartHeight(loadSpreadChartHeight());

    let dragging = false;
    let startY = 0;
    let startH = 0;

    const onMove = (e) => {
      if (!dragging) return;
      const dy = e.clientY - startY;
      applySpreadChartHeight(startH - dy);
    };

    const endDrag = () => {
      if (!dragging) return;
      dragging = false;
      divider.classList.remove('active');
      document.body.classList.remove('split-dragging-v');
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', endDrag);
      const h = parseInt(getComputedStyle(stack).getPropertyValue('--trade-spread-chart-height'), 10);
      if (h > 0) localStorage.setItem(LS_SPREAD_PANE_HEIGHT, String(h));
      resize();
    };

    divider.addEventListener('mousedown', (e) => {
      e.preventDefault();
      dragging = true;
      startY = e.clientY;
      startH = paneChart.offsetHeight;
      divider.classList.add('active');
      document.body.classList.add('split-dragging-v');
      document.addEventListener('mousemove', onMove);
      document.addEventListener('mouseup', endDrag);
    });

    divider.addEventListener('dblclick', () => {
      const h = applySpreadChartHeight(TRADE_SPREAD_DEFAULT);
      localStorage.setItem(LS_SPREAD_PANE_HEIGHT, String(h));
    });

    window.addEventListener('resize', () => {
      if (document.getElementById('app')?.dataset?.view !== 'trade') return;
      // Re-clamp preferred height; do not overwrite LS with early/hidden clamp.
      applySpreadChartHeight(loadSpreadChartHeight());
    });
  }
  global.__TradeDesk = global.__TradeDesk || { deps: {} };
  global.__TradeDesk.chart = {
    setZEmptyMessage: setZEmptyMessage,
    updateChartPaneLabels: updateChartPaneLabels,
    alignTip1mBarsToLiveTip: alignTip1mBarsToLiveTip,
    setTpExitSpreadLine: setTpExitSpreadLine,
    renderCharts: renderCharts,
    ensureCharts: ensureCharts,
    resize: resize,
    renderOpen: renderOpen,
    updateOpenTradeOnChart: updateOpenTradeOnChart,
    syncBottomPaneToTopTime: syncBottomPaneToTopTime,
    syncTopPaneToBottomTime: syncTopPaneToBottomTime,
    thinDeskTip1mBars: thinDeskTip1mBars,
    ensureWeekdayTip1mBars: ensureWeekdayTip1mBars
  };
})(typeof window !== 'undefined' ? window : globalThis);
