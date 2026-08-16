/** Торговля — chart + open trade panel (desk) */
(function () {
  const LS_CHART_RANGE = 'moexReplay.tradeChartRange';
  const LS_SPREAD_PANE_HEIGHT = 'moexReplay.tradeSpreadPaneHeight';
  const LS_TRADE_PARAMS = 'moexReplay.tradeParams';
  const LS_SIDE_SCROLL = 'moexReplay.tradeSideScrollTop';
  const LS_CHECK_SCROLL = 'moexReplay.tradeCheckScrollTop';
  const LS_DESK_SCROLL = 'moexReplay.tradeDeskScrollTop';
  const LS_OPEN_STATS_HIDDEN = 'moexReplay.tradeOpenStatsHidden';
  const LS_MID_TAB = 'moexReplay.tradeMidTab';
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

  let days = 7;
  let pollTimer = null;
  let pollMs = 12000;
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
  let zChart = null;
  let zSeries = null;
  /** Primary pane: CandlestickSeries (Z tip1m · spread dealer). Line only as last-resort fallback. */
  let zSeriesIsLine = false;
  let spreadChart = null;
  let spreadSeries = null;
  let priceLines = [];
  /** Горизонтали порогов спреда % (как Z createPriceLine). */
  let spreadPriceLines = [];
  /**
   * Semi-transparent trade-band fills (BaselineSeries) — same style as Test/chart.js.
   * Bounds from desk.spread_levels.levels (L вх/вых · gap · S вых/вх) — not regime cuts.
   * primary* → upper candle pane; spreadRegime* → lower yellow S% line.
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
  /** Линия вход→сейчас / hover вход→выход на Z (parity Тест). */
  let openHighlightSeries = null;
  let openMarkersPlugin = null;
  /** Те же маркеры сделок на жёлтой линии S% (sync с Z). */
  let openSpreadMarkersPlugin = null;
  let lastOpenTradeFp = '';
  /** Режим счёта с последнего desk refresh (prod|sandbox) — для confirm ручного входа. */
  let lastTradeMode = '';
  /** Полные маркеры + сделки для hit-test / yellow highlight (как chart.js). */
  let lastDeskMarkers = [];
  let lastDeskTrades = [];
  let hoverTradeId = null;
  let markerHoverBound = false;
  /** Данные линии открытой сделки — восстанавливаем, когда hover снят. */
  let defaultOpenHighlightData = null;
  let refreshMarkersTimer = 0;
  /** true → fitContent после setData (смена периода 1Д/1Н/1М/3М/6М) */
  let forceFitContent = false;
  /** Глушим save/sync на время setData / apply / resize — LC шлёт range-change асинхронно */
  let suppressRangeEvents = false;
  let rangeSyncBound = false;
  let crosshairSyncBound = false;
  /** price @ UTC sec — для sync кроссхейра Z↔спред */
  let zPriceByTime = new Map();
  let spPriceByTime = new Map();
  let lastBarCount = 0;
  let lastDataEnd = null;
  /** Точный logical range, который пользователь выставил руками */
  let pinnedRange = null;
  /**
   * Строгий pin: после ручного pan влево poll НИКОГДА не follow-live,
   * пока пользователь сам не вернётся к правому краю (или chip периода).
   */
  let userPinnedAwayFromLive = false;
  /** Отпечаток последних баров — без изменений setData не трогаем (типичный poll) */
  let lastBarsFingerprint = '';
  let reapplyRangeTimer = 0;
  /** true только во время/сразу после реального pan/zoom пользователя */
  let userGestureActive = false;
  let userGestureTimer = 0;
  /** Выходные / дилер 1м: верхний pane = свечи спреда (как Test), не Z */
  let chartDealer1m = false;
  /** Верхний pane рисует спред % (свечи + уровни L/S), не Z ±вх/вых */
  let chartPrimarySpread = false;
  /** Одинаковая ширина правой шкалы → одинаковая plot-area → вертикальный кроссхейр */
  const PRICE_SCALE_MIN_WIDTH = 64;

  const $ = (id) => document.getElementById(id);

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
  } = {}) {
    const zLab = $('tradeZChartLabel');
    const spLab = $('tradeSpreadChartLabel');
    const thLab = $('tradeThreshLabel');
    const lb = (lookbackDays != null && Number(lookbackDays) > 0)
      ? ` · до ${Number(lookbackDays)}д`
      : '';
    if (zLab) {
      if (dealer1m && zEmpty) {
        zLab.textContent = 'Спред % · нет баров дилер 1м';
      } else if (dealer1m) {
        zLab.textContent = `Спред % · дилер 1м (монитор · не AUTO)${lb}`;
      } else {
        zLab.textContent = 'Спред % · tip1m';
      }
      zLab.classList.toggle('pnl-label-dealer', !!dealer1m);
    }
    if (spLab) {
      spLab.textContent = dealer1m
        ? `Спред % · дилер 1м${lb}`
        : 'Спред % · tip1m';
      spLab.classList.toggle('pnl-label-dealer', !!dealer1m);
    }
    if (thLab) {
      thLab.classList.toggle('pnl-label-dealer', !!dealer1m);
      const lv = spreadLevels || {};
      const en = Number(lv.enter_narrow);
      const xn = Number(lv.exit_narrow);
      const xw = Number(lv.exit_wide);
      const ew = Number(lv.enter_wide);
      if ([en, xn, xw, ew].every((n) => Number.isFinite(n))) {
        thLab.textContent = dealer1m
          ? `уровни L ${fmt(en, 1)}/${fmt(xn, 1)} · S ${fmt(xw, 1)}/${fmt(ew, 1)} · дилер монитор (не AUTO)`
          : `уровни L ${fmt(en, 1)}/${fmt(xn, 1)} · S ${fmt(xw, 1)}/${fmt(ew, 1)}`;
      } else {
        thLab.textContent = dealer1m
          ? 'уровни спреда · дилер монитор (не AUTO)'
          : 'уровни спреда';
      }
    }
  }

  function markUserGesture() {
    userGestureActive = true;
    if (userGestureTimer) clearTimeout(userGestureTimer);
    userGestureTimer = setTimeout(() => {
      userGestureActive = false;
      userGestureTimer = 0;
    }, 400);
  }

  function dataEndIndex(barCount) {
    return barCount > 0 ? barCount - 1 : 0;
  }

  /**
   * У live-края только если правый край окна ≈ конец данных.
   * Старое `to >= dataEnd - N` ошибочно ловило pan с пустотой справа (to >> dataEnd)
   * и на poll схлопывало to=dataEnd — отсюда прыжок.
   */
  function isNearLiveEdge(range, dataEnd) {
    if (!range || !Number.isFinite(range.to) || !Number.isFinite(dataEnd)) return false;
    return Math.abs(range.to - dataEnd) <= LIVE_EDGE_BARS;
  }

  /** Сколько реальных баров попало в logical range (пустота справа/слева не считается). */
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

  /**
   * Битый pin: данные сжаты влево (2 бара на огромном окне) или from за концом ряда.
   * Иначе строгий userPinnedAwayFromLive навсегда держит пустой график.
   */
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
    if (!pinnedRange) return;
    try {
      const payload = {
        from: pinnedRange.from,
        to: pinnedRange.to,
        dataEnd: lastDataEnd,
        pinnedAway: !!userPinnedAwayFromLive,
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

  function clearPinState() {
    pinnedRange = null;
    userPinnedAwayFromLive = false;
    lastDataEnd = null;
    lastBarCount = 0;
    lastBarsFingerprint = '';
    try { localStorage.removeItem(LS_CHART_RANGE); } catch (_) {}
  }

  function hydratePinFromStorage() {
    const saved = loadPersistedViewport();
    if (!saved) return;
    pinnedRange = { from: saved.from, to: saved.to };
    if (saved.dataEnd != null) lastDataEnd = saved.dataEnd;
    userPinnedAwayFromLive = !!saved.pinnedAway;
  }

  /** Z и Spread = одинаковые time keys → один logical range (пиксель в пиксель). */
  function applyVisibleRange(range) {
    if (!range || !zChart) return;
    suppressRangeEvents = true;
    try {
      equalizePriceScales();
      zChart.timeScale().setVisibleLogicalRange(range);
      spreadChart?.timeScale().setVisibleLogicalRange(range);
    } catch (_) { /* ignore */ }
    // Не снимаем suppress сразу: LC часто шлёт range-change в следующем кадре
    scheduleEndSuppress();
  }

  function scheduleEndSuppress() {
    if (reapplyRangeTimer) cancelAnimationFrame(reapplyRangeTimer);
    reapplyRangeTimer = requestAnimationFrame(() => {
      reapplyRangeTimer = requestAnimationFrame(() => {
        reapplyRangeTimer = 0;
        suppressRangeEvents = false;
      });
    });
  }

  function equalizePriceScales() {
    try {
      const opts = { minimumWidth: PRICE_SCALE_MIN_WIDTH };
      zChart?.priceScale('right')?.applyOptions?.(opts);
      spreadChart?.priceScale('right')?.applyOptions?.(opts);
    } catch (_) { /* ignore */ }
  }

  /** После paint ещё раз навязать общий logical range (setData/fit асинхронно съезжают). */
  function forceSyncAfterPaint() {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        if (!pinnedRange || !zChart || !spreadChart) return;
        equalizePriceScales();
        reassertPinnedRange();
        try {
          const zr = zChart.timeScale().getVisibleLogicalRange();
          const sr = spreadChart.timeScale().getVisibleLogicalRange();
          if (zr && sr) {
            const mid = {
              from: (zr.from + sr.from) / 2,
              to: (zr.to + sr.to) / 2,
            };
            // Если разошлись >0.05 бара — взять Z как источник истины
            if (Math.abs(zr.from - sr.from) > 0.05 || Math.abs(zr.to - sr.to) > 0.05) {
              setPinnedRange(zr, { fromUser: false });
              applyVisibleRange(zr);
            } else if (!pinnedRange) {
              setPinnedRange(mid, { fromUser: false });
            }
          }
        } catch (_) { /* ignore */ }
      });
    });
  }

  /** Повторно навязать pin после асинхронного сброса timescale от setData/resize */
  function reassertPinnedRange() {
    if (!zChart || !pinnedRange) return;
    suppressRangeEvents = true;
    try {
      equalizePriceScales();
      zChart.timeScale().setVisibleLogicalRange(pinnedRange);
      spreadChart?.timeScale().setVisibleLogicalRange(pinnedRange);
    } catch (_) { /* ignore */ }
    scheduleEndSuppress();
  }

  function bindRangeSync() {
    if (rangeSyncBound || !zChart || !spreadChart) return;
    rangeSyncBound = true;
    if (!pinnedRange) hydratePinFromStorage();

    const bindGesture = (el) => {
      if (!el || el.dataset.tradeGestureBound === '1') return;
      el.dataset.tradeGestureBound = '1';
      el.addEventListener('pointerdown', markUserGesture);
      el.addEventListener('wheel', markUserGesture, { passive: true });
      el.addEventListener('touchstart', markUserGesture, { passive: true });
    };
    bindGesture($('tradeZChart'));
    bindGesture($('tradeSpreadChart'));

    const onRangeChange = (source) => (range) => {
      if (!range || suppressRangeEvents) return;

      // Программный сброс от setData/resize — не пишем в pin; если ушли от live — вернуть окно
      if (!userGestureActive) {
        if (userPinnedAwayFromLive && pinnedRange) {
          const jumpedToLive = lastDataEnd != null && isNearLiveEdge(range, lastDataEnd)
            && Math.abs(range.to - pinnedRange.to) > LIVE_EDGE_BARS;
          if (jumpedToLive || Math.abs(range.from - pinnedRange.from) > 0.01
            || Math.abs(range.to - pinnedRange.to) > 0.01) {
            reassertPinnedRange();
          }
        }
        return;
      }

      // Реальный жест — общий logical range на оба графика (Z↔Spread)
      setPinnedRange(range, { fromUser: true });
      suppressRangeEvents = true;
      try {
        if (source !== 'z') zChart.timeScale().setVisibleLogicalRange(range);
        if (source !== 'spread') spreadChart.timeScale().setVisibleLogicalRange(range);
      } catch (_) { /* ignore */ }
      scheduleEndSuppress();
    };
    zChart.timeScale().subscribeVisibleLogicalRangeChange(onRangeChange('z'));
    spreadChart.timeScale().subscribeVisibleLogicalRangeChange(onRangeChange('spread'));
  }

  function bindCrosshairSync() {
    if (crosshairSyncBound || !zChart || !spreadChart) return;
    crosshairSyncBound = true;
    let syncing = false;
    const clearOther = (dst) => {
      if (typeof dst.clearCrosshairPosition !== 'function') return;
      syncing = true;
      try { dst.clearCrosshairPosition(); } catch (_) { /* ignore */ }
      syncing = false;
    };
    const onMove = (src) => (param) => {
      if (syncing) return;
      const dst = src === 'z' ? spreadChart : zChart;
      const dstSeries = src === 'z' ? spreadSeries : zSeries;
      if (!dst || !dstSeries || !param || param.time == null || !param.point) {
        clearOther(dst);
        return;
      }
      const price = (src === 'z' ? spPriceByTime : zPriceByTime).get(param.time);
      if (price == null || typeof dst.setCrosshairPosition !== 'function') {
        clearOther(dst);
        return;
      }
      syncing = true;
      try { dst.setCrosshairPosition(price, param.time, dstSeries); } catch (_) { /* ignore */ }
      syncing = false;
    };
    zChart.subscribeCrosshairMove(onMove('z'));
    spreadChart.subscribeCrosshairMove(onMove('spread'));
  }

  function fitAndRemember() {
    try {
      // Один fit → общий logical range. Независимый fitContent на обоих давал разный viewport.
      suppressRangeEvents = true;
      equalizePriceScales();
      let range = null;
      try {
        zChart?.timeScale().fitContent();
        range = zChart?.timeScale().getVisibleLogicalRange();
      } catch (_) { /* ignore */ }
      if (!range) {
        try {
          spreadChart?.timeScale().fitContent();
          range = spreadChart?.timeScale().getVisibleLogicalRange();
        } catch (_) { /* ignore */ }
      }
      if (range) {
        userPinnedAwayFromLive = false;
        setPinnedRange(range, { fromUser: false });
        applyVisibleRange(range);
      }
      scheduleEndSuppress();
      forceSyncAfterPaint();
    } catch (_) {
      try { spreadChart?.timeScale().fitContent(); } catch (__) {}
    }
  }

  /**
   * После обновления данных:
   * - userPinnedAwayFromLive → ВСЕГДА точный restore, never follow
   * - иначе у live-края → сдвинуть окно к новому концу
   * - иначе → точный restore
   * @param {number} zCount
   * @param {number} [spreadCount]
   */
  function restoreOrFitVisibleRange(zCount, spreadCount) {
    // Z и Spread 1:1 по времени — длина берём по минимуму (должны совпадать)
    const zN = Math.max(0, zCount | 0);
    const spN = spreadCount != null ? Math.max(0, spreadCount | 0) : zN;
    const n = Math.min(zN, spN) || zN || spN;
    const dataEnd = dataEndIndex(n);
    lastBarCount = n;
    lastDataEnd = dataEnd;

    if (forceFitContent) {
      forceFitContent = false;
      userPinnedAwayFromLive = false;
      fitAndRemember();
      return;
    }

    if (!pinnedRange) hydratePinFromStorage();

    if (!pinnedRange) {
      fitAndRemember();
      return;
    }

    // Битый LS/pin (данные слева, справа пустота) — не уважать, переfit.
    if (isViewportCorrupt(pinnedRange, dataEnd, n)) {
      userPinnedAwayFromLive = false;
      fitAndRemember();
      return;
    }

    // Строгий pin: poll/tick не имеют права уезжать к правому краю
    if (userPinnedAwayFromLive) {
      applyVisibleRange(pinnedRange);
      persistViewport();
      forceSyncAfterPaint();
      return;
    }

    if (isNearLiveEdge(pinnedRange, dataEnd) && n > 0) {
      const span = Math.max(1, pinnedRange.to - pinnedRange.from);
      const next = { from: dataEnd - span, to: dataEnd };
      // Follow-live тоже может дать почти пустое окно — сразу переfit.
      if (isViewportCorrupt(next, dataEnd, n)) {
        userPinnedAwayFromLive = false;
        fitAndRemember();
        return;
      }
      setPinnedRange(next, { fromUser: false });
      applyVisibleRange(next);
      forceSyncAfterPaint();
      return;
    }

    // Не у live, но флаг ещё не стоял (старый LS) — тоже держим окно
    userPinnedAwayFromLive = true;
    applyVisibleRange(pinnedRange);
    persistViewport();
    forceSyncAfterPaint();
  }

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
    return Number(n).toLocaleString('ru-RU', { maximumFractionDigits: d });
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
    const label = (sl && sl.current_label_ru) || '—';
    const s = sl && sl.spread != null ? Number(sl.spread) : null;
    const sTxt = Number.isFinite(s) ? ` S=${fmt(s, 2)}%` : '';
    const blocked = sl && sl.entry_blocked ? ' · вход запрещён' : '';
    return (
      `<span class="badge-spread-levels" title="Short ≥${fmt(lv.enter_wide ?? 6.2, 1)} / ≤${fmt(lv.exit_wide ?? 5.8, 1)} · Long ≤${fmt(lv.enter_narrow ?? 3.2, 1)} / ≥${fmt(lv.exit_narrow ?? 4, 1)} · переход без входа">`
      + `спред-уровни · ${escapeHtml(String(label))}${sTxt}${blocked}</span>`
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
    const enterW = Number(lv?.enter_wide ?? 6.2);
    const exitW = Number(lv?.exit_wide ?? 5.8);
    const enterN = Number(lv?.enter_narrow ?? 3.2);
    const exitN = Number(lv?.exit_narrow ?? 4.0);
    const p = String(pos || 'FLAT').toUpperCase();
    if (p === 'FLAT') {
      if (prevS < enterW && curS >= enterW && curS > 5.5) return 'ENTER_SHORT';
      if (prevS > enterN && curS <= enterN && curS < 3.5) return 'ENTER_LONG';
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
      return {
        entry: Number(settings?.spread_enter_wide ?? 6.2),
        exit: Number(settings?.spread_exit_wide ?? 5.8),
        regimeOn: false,
        spreadOn: true,
        allowEntry: true,
        levels: {
          enter_wide: Number(settings?.spread_enter_wide ?? 6.2),
          exit_wide: Number(settings?.spread_exit_wide ?? 5.8),
          enter_narrow: Number(settings?.spread_enter_narrow ?? 3.2),
          exit_narrow: Number(settings?.spread_exit_narrow ?? 4.0),
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
    const zPart = r.zLabel
      ? ` <span class="badge-quiet">· ${escapeHtml(r.zLabel)}</span>`
      : '';
    return `<span class="badge-regime badge-regime-${escapeHtml(r.key)}" title="${escapeHtml(r.title)}">`
      + `${escapeHtml(r.label)}${zPart}</span>`;
  }

  /**
   * MOEX trade_date — wall-clock MSK. Как labelToUnixSec в replay-engine.js:
   * явно +03:00, без Date.parse без зоны (браузер иначе сдвигает на несколько часов).
   */
  function toChartTime(tradeDate, timestampMs) {
    if (timestampMs != null && Number.isFinite(Number(timestampMs))) {
      return Math.floor(Number(timestampMs) / 1000);
    }
    if (!tradeDate) return null;
    const s = String(tradeDate).trim().replace('T', ' ');
    const iso = s.length >= 16
      ? `${s.slice(0, 16).replace(' ', 'T')}:00+03:00`
      : `${s}+03:00`;
    const ms = new Date(iso).getTime();
    return Number.isFinite(ms) ? Math.floor(ms / 1000) : null;
  }

  /** Подписи оси/кроссхейра в MSK — как chart-frame.html */
  function formatChartTick(time) {
    if (typeof time !== 'number') return '';
    return new Date(time * 1000).toLocaleString('ru-RU', {
      timeZone: MSK,
      day: 'numeric',
      month: 'short',
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
      },
      localization: {
        locale: 'ru-RU',
        timeFormatter: formatChartTick,
      },
    };
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

  /** Horizontal price-band fill (BaselineSeries); ignored by Y autoscale. */
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

  /**
   * Trade-band Y bounds from desk.spread_levels.levels (not regime cuts).
   * Narrow Long: enter_narrow…exit_narrow; Wide Short: exit_wide…enter_wide;
   * Transition: exit_narrow…exit_wide (gap, no entry).
   */
  function resolveSpreadTradeBandBounds(levels) {
    const lv = levels || {};
    const num = (v, fb) => {
      const n = Number(v);
      return Number.isFinite(n) ? n : fb;
    };
    const enterW = num(lv.enter_wide, 6.2);
    const exitW = num(lv.exit_wide, 5.8);
    const enterN = num(lv.enter_narrow, 3.2);
    const exitN = num(lv.exit_narrow, 4.0);
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

  /**
   * @param {object} chart
   * @param {{narrow:*,transition:*,wide:*}} bandsRef
   * @param {object|null} levels
   * @returns {boolean} true if band series were newly created
   */
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
    ensureSpreadBandsOnChart(spreadChart, spreadRegimeBands, levels);
  }

  /**
   * Upper pane (spread candles) — same Long/Short zone fills as Test chart.js.
   * If bands are created after candles already exist, recreate candle series so
   * fills stay under OHLC (z-order = add order).
   */
  function ensurePrimarySpreadBands(levels) {
    if (!zChart) return;
    const created = ensureSpreadBandsOnChart(zChart, primarySpreadBands, levels);
    if (created && zSeries) {
      const wantLine = zSeriesIsLine;
      priceLines.forEach((pl) => {
        try { zSeries.removePriceLine(pl); } catch (_) {}
      });
      priceLines = [];
      openMarkersPlugin = null;
      try {
        if (typeof zChart.removeSeries === 'function') zChart.removeSeries(zSeries);
      } catch (_) {}
      zSeries = null;
      zSeriesIsLine = !wantLine;
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

  /**
   * Fill trade bands on lower yellow S% chart from enter/exit levels.
   * @param {Array<{time:number}>|null} spreadPts — times; omit to reuse last
   * @param {{enter_wide?:number,exit_wide?:number,enter_narrow?:number,exit_narrow?:number}|null} levels
   */
  function updateSpreadRegimeBands(spreadPts, levels) {
    if (!spreadChart) return;
    ensureSpreadRegimeBands(levels);
    if (Array.isArray(spreadPts)) {
      lastSpreadBandTimes = spreadPts
        .map((p) => p && p.time)
        .filter((t) => t != null);
    }
    setSpreadBandSeriesData(spreadRegimeBands, lastSpreadBandTimes, levels);
  }

  /**
   * Fill Long/Short zones on upper spread-candle pane (parity Test).
   * @param {Array<{time:number}>|null} candlePts
   * @param {object|null} levels
   */
  function updatePrimarySpreadBands(candlePts, levels) {
    if (!zChart) return;
    ensurePrimarySpreadBands(levels);
    if (Array.isArray(candlePts)) {
      lastPrimarySpreadBandTimes = candlePts
        .map((p) => p && p.time)
        .filter((t) => t != null);
    }
    setSpreadBandSeriesData(primarySpreadBands, lastPrimarySpreadBandTimes, levels);
  }

  function makeZCandleSeries(chart) {
    return addSeries(chart, 'CandlestickSeries', {
      upColor: '#089981', downColor: '#f23645',
      borderUpColor: '#089981', borderDownColor: '#f23645',
      wickUpColor: '#089981', wickDownColor: '#f23645',
      lastValueVisible: true,
      priceLineVisible: true,
    });
  }

  function makeZLineSeries(chart) {
    return addSeries(chart, 'LineSeries', {
      color: '#089981',
      lineWidth: 2,
      lastValueVisible: true,
      priceLineVisible: false,
    });
  }

  function publishChartDebug(extra) {
    try {
      let zRange = null;
      let spRange = null;
      try { zRange = zChart?.timeScale()?.getVisibleLogicalRange() || null; } catch (_) {}
      try { spRange = spreadChart?.timeScale()?.getVisibleLogicalRange() || null; } catch (_) {}
      window.__tradeChartDebug = {
        zSeriesIsLine,
        chartDealer1m,
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

  /**
   * Prefer CandlestickSeries. LineSeries only if candles unavailable.
   * @returns {boolean} true if series was (re)created
   */
  function ensureZSeriesKind(wantLine) {
    if (!zChart) return false;
    const asLine = !!wantLine;
    if (zSeries && zSeriesIsLine === asLine) return false;
    priceLines.forEach((pl) => {
      try { if (zSeries) zSeries.removePriceLine(pl); } catch (_) {}
    });
    priceLines = [];
    openMarkersPlugin = null;
    if (zSeries) {
      try {
        if (typeof zChart.removeSeries === 'function') zChart.removeSeries(zSeries);
      } catch (_) {}
      zSeries = null;
    }
    if (asLine) {
      zSeries = makeZLineSeries(zChart);
      zSeriesIsLine = !!zSeries;
    } else {
      const candle = makeZCandleSeries(zChart);
      if (candle) {
        zSeries = candle;
        zSeriesIsLine = false;
      } else {
        zSeries = makeZLineSeries(zChart);
        zSeriesIsLine = !!zSeries;
      }
    }
    lastBarsFingerprint = '';
    forceFitContent = true;
    publishChartDebug({ zPts: 0, spPts: 0 });
    return true;
  }

  function ensureCharts() {
    if (typeof LightweightCharts === 'undefined') return;
    const zEl = $('tradeZChart');
    const sEl = $('tradeSpreadChart');
    if (!zEl || !sEl) return;
    if (!zChart) {
      zChart = LightweightCharts.createChart(zEl, {
        layout: { background: { color: '#161a25' }, textColor: '#d1d4dc' },
        grid: { vertLines: { color: '#2a2e39' }, horzLines: { color: '#2a2e39' } },
        rightPriceScale: { borderColor: '#2a2e39', minimumWidth: PRICE_SCALE_MIN_WIDTH },
        width: zEl.clientWidth,
        height: zEl.clientHeight || 300,
        ...chartTimeOpts(),
        ...CHART_SCROLL_SCALE,
      });
      // Bands first → under candles (как Test chart.js).
      ensurePrimarySpreadBands({
        enter_wide: 6.2, exit_wide: 5.8, enter_narrow: 3.2, exit_narrow: 4.0,
      });
      // Candles by default (dealer + tip1m). Line only if candle API missing.
      const candle = makeZCandleSeries(zChart);
      if (candle) {
        zSeries = candle;
        zSeriesIsLine = false;
      } else {
        zSeries = makeZLineSeries(zChart);
        zSeriesIsLine = !!zSeries;
      }
      openHighlightSeries = addSeries(zChart, 'LineSeries', {
        color: TRADE_HIGHLIGHT_COLOR,
        lineWidth: 2,
        lineStyle: 0,
        lastValueVisible: false,
        priceLineVisible: false,
        crosshairMarkerVisible: false,
        autoscaleInfoProvider: () => null,
      });
    } else if (zChart && zSeries && !openHighlightSeries) {
      openHighlightSeries = addSeries(zChart, 'LineSeries', {
        color: TRADE_HIGHLIGHT_COLOR,
        lineWidth: 2,
        lineStyle: 0,
        lastValueVisible: false,
        priceLineVisible: false,
        crosshairMarkerVisible: false,
        autoscaleInfoProvider: () => null,
      });
    }
    if (!spreadChart) {
      spreadChart = LightweightCharts.createChart(sEl, {
        layout: { background: { color: '#161a25' }, textColor: '#d1d4dc' },
        grid: { vertLines: { color: '#2a2e39' }, horzLines: { color: '#2a2e39' } },
        rightPriceScale: { borderColor: '#2a2e39', minimumWidth: PRICE_SCALE_MIN_WIDTH },
        width: sEl.clientWidth,
        height: sEl.clientHeight || 150,
        ...chartTimeOpts(),
        ...CHART_SCROLL_SCALE,
      });
      // Trade-band fills first (drawn under yellow S% line).
      ensureSpreadRegimeBands({
        enter_wide: 6.2, exit_wide: 5.8, enter_narrow: 3.2, exit_narrow: 4.0,
      });
      const dashStyle = (typeof LightweightCharts !== 'undefined'
        && LightweightCharts.LineStyle
        && LightweightCharts.LineStyle.Dashed != null)
        ? LightweightCharts.LineStyle.Dashed
        : 2;
      spreadSeries = addSeries(spreadChart, 'LineSeries', {
        color: '#f0b90b',
        lineWidth: 2,
        lastValueVisible: true,
        priceLineVisible: true,
        priceLineWidth: 1,
        priceLineColor: '#f0b90b',
        priceLineStyle: dashStyle,
      });
    } else if (spreadSeries && typeof spreadSeries.applyOptions === 'function') {
      const dashStyle = (typeof LightweightCharts !== 'undefined'
        && LightweightCharts.LineStyle
        && LightweightCharts.LineStyle.Dashed != null)
        ? LightweightCharts.LineStyle.Dashed
        : 2;
      try {
        spreadSeries.applyOptions({
          lastValueVisible: true,
          priceLineVisible: true,
          priceLineWidth: 1,
          priceLineColor: '#f0b90b',
          priceLineStyle: dashStyle,
        });
      } catch (_) { /* */ }
    }
    equalizePriceScales();
    bindRangeSync();
    bindCrosshairSync();
    bindMarkerHover();
  }

  function setThresholdLines(entry, exitZ) {
    if (!zSeries || typeof zSeries.createPriceLine !== 'function') return;
    priceLines.forEach((pl) => { try { zSeries.removePriceLine(pl); } catch (_) {} });
    priceLines = [];
    const mk = (price, color, title) => {
      priceLines.push(zSeries.createPriceLine({
        price, color, lineWidth: 1, lineStyle: 2, title,
      }));
    };
    mk(entry, '#2962ff', `+вх ${entry}`);
    mk(-entry, '#2962ff', `−вх ${entry}`);
    mk(exitZ, '#089981', `+вых ${exitZ}`);
    mk(-exitZ, '#089981', `−вых ${exitZ}`);
  }

  /** Уровни спреда на верхнем pane (как Test / нижний S%) — вместо Z ±вх/вых. */
  function setPrimarySpreadThresholdLines(levels) {
    const lv = levels || {};
    // Bands first (may recreate candle series for z-order); then price lines.
    updatePrimarySpreadBands(null, lv);
    if (!zSeries || typeof zSeries.createPriceLine !== 'function') return;
    priceLines.forEach((pl) => { try { zSeries.removePriceLine(pl); } catch (_) {} });
    priceLines = [];
    const enterW = Number(lv.enter_wide);
    const exitW = Number(lv.exit_wide);
    const enterN = Number(lv.enter_narrow);
    const exitN = Number(lv.exit_narrow);
    const mk = (price, color, title) => {
      if (!Number.isFinite(price)) return;
      priceLines.push(zSeries.createPriceLine({
        price, color, lineWidth: 1, lineStyle: 2, title,
      }));
    };
    mk(enterW, '#2962ff', `S вх ${fmt(enterW, 1)}`);
    mk(exitW, '#26a69a', `S вых ${fmt(exitW, 1)}`);
    mk(exitN, '#26a69a', `L вых ${fmt(exitN, 1)}`);
    mk(enterN, '#2962ff', `L вх ${fmt(enterN, 1)}`);
  }

  /** Уровни спреда из desk.spread_levels / settings (не хардкод-only). */
  function resolveSpreadLevelLines(settings, spreadLevelsPayload) {
    const sl = spreadLevelsPayload || {};
    const lv = sl.levels || {};
    const cuts = sl.cuts || {};
    const num = (v, fallback) => {
      const n = Number(v);
      return Number.isFinite(n) ? n : fallback;
    };
    return {
      levels: {
        enter_wide: num(lv.enter_wide ?? settings?.spread_enter_wide, 6.2),
        exit_wide: num(lv.exit_wide ?? settings?.spread_exit_wide, 5.8),
        enter_narrow: num(lv.enter_narrow ?? settings?.spread_enter_narrow, 3.2),
        exit_narrow: num(lv.exit_narrow ?? settings?.spread_exit_narrow, 4.0),
      },
      cuts: {
        narrow_max: num(cuts.narrow_max, 3.5),
        wide_min: num(cuts.wide_min, 5.5),
      },
    };
  }

  function setSpreadThresholdLines(levels) {
    if (!spreadSeries || typeof spreadSeries.createPriceLine !== 'function') return;
    spreadPriceLines.forEach((pl) => {
      try { spreadSeries.removePriceLine(pl); } catch (_) {}
    });
    spreadPriceLines = [];
    const lv = levels || {};
    const enterW = Number(lv.enter_wide);
    const exitW = Number(lv.exit_wide);
    const enterN = Number(lv.enter_narrow);
    const exitN = Number(lv.exit_narrow);
    const mk = (price, color, title) => {
      if (!Number.isFinite(price)) return;
      spreadPriceLines.push(spreadSeries.createPriceLine({
        price, color, lineWidth: 1, lineStyle: 2, title,
      }));
    };
    // Как Z: синий = вход, бирюзовый = выход; подписи Short/Long (запятая ru-RU).
    // Cuts режима (3.5/5.5) не рисуем — только в engine для gating.
    // Порядок сверху вниз как на скрине: S вх → S вых → L вых → L вх.
    mk(enterW, '#2962ff', `S вх ${fmt(enterW, 1)}`);
    mk(exitW, '#26a69a', `S вых ${fmt(exitW, 1)}`);
    mk(exitN, '#26a69a', `L вых ${fmt(exitN, 1)}`);
    mk(enterN, '#2962ff', `L вх ${fmt(enterN, 1)}`);
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

  /**
   * Live tip Z/S% from monitor / open mark — not parquet/sidecar chart tail.
   * Desk tip1m bars often lag (cold μ/σ or sidecar ≈−1.9 while tip ≈−2.3).
   */
  function liveTipZSpread(data, openTrade = null) {
    const open = openTrade || (data && data.open) || null;
    const mark = (open && open.mark) || {};
    const mon = (data && data.monitor) || {};
    const s = (data && data.summary) || {};
    const dealer = (data && data.dealer) || {};
    const msk = nowMskParts();
    const spreadFrozen = dealer.spread_live === false
      || (msk.spreadLive === false && !msk.weekend);
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
    } else if (mark.spread_now != null && Number.isFinite(Number(mark.spread_now))) {
      sp = Number(mark.spread_now);
    } else if (s.spread != null && Number.isFinite(Number(s.spread))) {
      sp = Number(s.spread);
    }
    return { z, sp };
  }

  /** Patch last tip1m bar to live tip so last-price label ≡ overlay ≡ monitor. */
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
    if (needSp) row.spread = liveSp;
    out[out.length - 1] = row;
    return out;
  }

  /** Keep Z candles + spread line on identical sorted timestamps (1:1). */
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

  /** Ensure LW markers can land on entry_time (series must contain the time). */
  function injectOpenEntryIntoChartSeries(zData, spreadPts, open, bars, {
    primarySpread = false,
  } = {}) {
    const entry = resolveOpenEntryOnBars(bars, open);
    if (!entry || entry.time == null) {
      return { zData, spreadPts, bars };
    }
    const t = entry.time;
    const hasZ = (zData || []).some((c) => c && c.time === t);
    const hasSp = (spreadPts || []).some((p) => p && p.time === t);
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
    let nextSp = spreadPts ? spreadPts.slice() : [];
    let nextBars = bars ? bars.slice() : [];
    // Always inject into BOTH panes — never leave Z/spread length mismatch.
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

  function deskTradeById(id) {
    if (!id) return null;
    return lastDeskTrades.find((t) => t.id === id) || null;
  }

  function markerChartPrice(m) {
    const trade = deskTradeById(m.tradeId || m.text);
    if (!trade) return 0;
    if (chartPrimarySpread) {
      if (m.isEntry) {
        return trade.entrySpread != null ? trade.entrySpread : (trade.entryZ ?? 0);
      }
      return trade.exitSpread != null
        ? trade.exitSpread
        : (trade.entrySpread != null ? trade.entrySpread : (trade.exitZ ?? trade.entryZ ?? 0));
    }
    return m.isEntry ? trade.entryZ : (trade.exitZ ?? trade.entryZ);
  }

  function markerScreenPosition(m) {
    if (!zChart || !zSeries) return null;
    const x = zChart.timeScale().timeToCoordinate(m.time);
    if (x == null) return null;
    const price = markerChartPrice(m);
    let y = zSeries.priceToCoordinate(price);
    if (y == null) return null;
    const yOffset = m.isEntry ? (m.position === 'belowBar' ? 18 : -18) : 0;
    return { x, y: y + yOffset };
  }

  function findNearestDeskMarkerAtPoint(point) {
    if (!lastDeskMarkers.length || !zChart) return null;
    let bestPixel = null;
    for (const m of lastDeskMarkers) {
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
    if (!openHighlightSeries) return;
    try {
      openHighlightSeries.setData(data || []);
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
      entryVal = trade.entrySpread != null ? Number(trade.entrySpread) : Number(trade.entryZ);
      exitVal = trade.exitSpread != null
        ? Number(trade.exitSpread)
        : (trade.entrySpread != null ? Number(trade.entrySpread) : Number(trade.exitZ ?? trade.entryZ));
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
    setHighlightSeriesData(defaultOpenHighlightData || []);
  }

  function refreshDeskMarkers() {
    applyTradeMarkers(buildMarkerRenderData(lastDeskMarkers, hoverTradeId));
    applyHighlightForActiveTrade();
  }

  function scheduleRefreshDeskMarkers() {
    if (refreshMarkersTimer) clearTimeout(refreshMarkersTimer);
    refreshMarkersTimer = setTimeout(() => {
      refreshMarkersTimer = 0;
      refreshDeskMarkers();
    }, 100);
  }

  function onDeskMarkerCrosshair(param) {
    if (userGestureActive || suppressRangeEvents) return;
    let nextHover = null;
    if (param.point && param.point.x >= 0 && param.point.y >= 0) {
      const marker = findNearestDeskMarkerAtPoint(param.point);
      if (marker && deskTradeById(marker.tradeId || marker.text)) {
        nextHover = marker.tradeId || marker.text || null;
      }
    }
    if (nextHover === hoverTradeId) return;
    hoverTradeId = nextHover;
    applyHighlightForActiveTrade();
    scheduleRefreshDeskMarkers();
  }

  function bindMarkerHover() {
    if (markerHoverBound || !zChart) return;
    markerHoverBound = true;
    zChart.subscribeCrosshairMove((param) => onDeskMarkerCrosshair(param));
  }

  function clearOpenTradeOnChart() {
    lastOpenTradeFp = '';
    defaultOpenHighlightData = null;
    if (!hoverTradeId) setHighlightSeriesData([]);
    else applyHighlightForActiveTrade();
    const el = $('tradeOpenTradeOverlay');
    if (el) {
      el.classList.add('hidden');
      el.innerHTML = '';
    }
  }

  function clearAllTradeMarkers() {
    hoverTradeId = null;
    lastDeskMarkers = [];
    lastDeskTrades = [];
    clearOpenTradeOnChart();
    try {
      if (openMarkersPlugin && typeof openMarkersPlugin.setMarkers === 'function') {
        openMarkersPlugin.setMarkers([]);
      } else if (zSeries && typeof zSeries.setMarkers === 'function') {
        zSeries.setMarkers([]);
      }
    } catch (_) {}
    try {
      if (openSpreadMarkersPlugin && typeof openSpreadMarkersPlugin.setMarkers === 'function') {
        openSpreadMarkersPlugin.setMarkers([]);
      } else if (spreadSeries && typeof spreadSeries.setMarkers === 'function') {
        spreadSeries.setMarkers([]);
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

  function applyTradeMarkers(markerData) {
    openMarkersPlugin = applyMarkersToSeries(zSeries, openMarkersPlugin, markerData);
    openSpreadMarkersPlugin = applyMarkersToSeries(
      spreadSeries, openSpreadMarkersPlugin, markerData,
    );
  }

  /** Median Δt of chart bars — tip1m ~60s, M15 ~900s. */
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

  /**
   * Snap entry/exit to a real candle time for LW markers.
   * Returns null if nearest bar is farther than maxDeltaSec — NEVER pile
   * weekend/older trades onto the first Monday tip1m candle.
   */
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

  /** Маркеры закрытых + открытой сделки (стиль Теста: стрелка входа, круг выхода). */
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
      if (entrySec != null) {
        trades.push({
          id: tradeId,
          entryTime: entrySec,
          entryZ: Number.isFinite(entryZ) ? entryZ : 0,
          exitTime: exitSec != null ? exitSec : entrySec,
          exitZ: Number.isFinite(exitZ) ? exitZ : (Number.isFinite(entryZ) ? entryZ : 0),
          entrySpread: Number.isFinite(entrySp) ? entrySp : null,
          exitSpread: Number.isFinite(exitSp) ? exitSp : null,
          open: false,
        });
        markers.push({
          time: entrySec,
          position: isLong ? 'belowBar' : 'aboveBar',
          color: isLong ? '#69F0AE' : '#FF8A80',
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
          color: '#FFCC80',
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
          color: isLong ? '#69F0AE' : '#FF8A80',
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

  function updateOpenTradeOnChart(open, bars, closed = []) {
    ensureCharts();
    const built = buildDeskTradeMarkers(closed, open, bars);
    lastDeskMarkers = built.markers;
    lastDeskTrades = built.trades;
    if (hoverTradeId && !deskTradeById(hoverTradeId)) hoverTradeId = null;
    applyTradeMarkers(buildMarkerRenderData(lastDeskMarkers, hoverTradeId));

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
    const net = Number(mark.net_approx_rub ?? mark.unrealized_pnl_rub);
    const pnlClass = net > 0 ? 'pnl-pos' : net < 0 ? 'pnl-neg' : '';
    const netText = typeof formatRub === 'function' ? formatRub(net) : `${Math.round(net || 0)} ₽`;
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
    el.classList.remove('hidden');
    el.innerHTML = [
      `<div class="ot-trade">${tradeNo} ${dirShort} ${entryLabel} `
        + `<span class="ot-pnl ${pnlClass}">${netText}</span></div>`,
      `<div class="ot-spread">${dirLabel} · ${fmt(entrySpread)}% → ${fmt(nowSpread)}%</div>`,
      duration ? `<div class="ot-duration">${duration}</div>` : '',
    ].join('');
  }

  /**
   * Display-only tip-style Z: rolling μ/σ on ISS M15 + dealer 1m tip as last obs.
   * Never feeds AUTO — UI chart only (for_z stays false / z_kind=dealer_monitor).
   */
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

  /** Build one spread % OHLC candle: prefer server spread_open/high/low; else prev→close. */
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

  /** Build one Z OHLC candle: prefer server z_open/high/low when set; else prevZ→currZ. */
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

  /**
   * Median bar step in chart-time seconds (UTC unix). Null if <2 points.
   */
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

  /**
   * Z candles from tip1m bars with z (~1m step) — never M15, never TATN mid.
   * Returns [] if series is empty or looks like M15 (≥10m median step).
   * Prefer server z_open/high/low; else open=prevZ, close=currZ (visible bodies).
   * Last (forming) bar is always flat at close — live-tip align must not invent
   * a wick from stale sidecar prevZ down to entry / monitor Z.
   */
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

  /**
   * Weekday tip1m paint series: Z candles + spread, identical timestamps.
   * Rejects M15-density (HARD RULE). Zip by time — same length, same keys.
   */
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

  /** Calendar-day span of chart bars (for period chip coverage checks). */
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

  /**
   * If desk still serves iss_m15 under tip1m labels (pre-reload / race),
   * or tip1m is session-only while period chip asks 1Н/1М/3М/6М —
   * replace with real tip1m (static sidecar → bars1m) or empty — never paint M15.
   */
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

  /**
   * Spread % candles (top, Test-style) + yellow spread line (bottom).
   * tip1m и дилер 1м — один путь. Prefer spread_open/high/low; else prevSp→currSp.
   * Z не используется для отрисовки.
   */
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
    // Reject M15 masquerading as 1m (≥10m median step).
    if (candlePts.length >= 2) {
      const diffs = [];
      for (let i = 1; i < Math.min(candlePts.length, 40); i += 1) {
        diffs.push(candlePts[i].time - candlePts[i - 1].time);
      }
      diffs.sort((a, b) => a - b);
      const med = diffs[Math.floor(diffs.length / 2)] || 0;
      if (med >= 600) return { zPts: [], spreadPts: [] };
    }
    return { zPts: candlePts, spreadPts };
  }

  function renderCharts(bars, entry, exitZ, openTrade = null, closedTrades = [], {
    dealer1m = false,
    weekendMonitor = false,
    zBars = null,
    spreadLevels = null,
    spreadCuts = null,
    lookbackDays = null,
  } = {}) {
    ensureCharts();
    const monMode = !!(dealer1m || weekendMonitor);
    // Always prefer candles (dealer + tip1m). Line only if candle series missing.
    if (zSeriesIsLine || !zSeries) {
      ensureZSeriesKind(false);
    }
    if (!zSeries || !spreadSeries) return;

    // lite M15 → full dealer_1m: сбросить pin M15 и переfit, иначе спред выглядит как 15м
    if (monMode !== chartDealer1m) {
      chartDealer1m = monMode;
      forceFitContent = true;
      clearPinState();
    }
    // Всегда верхний pane = свечи спреда (как Test) — без Z.
    if (!chartPrimarySpread) {
      chartPrimarySpread = true;
      forceFitContent = true;
      clearPinState();
    }

    let zData = [];
    let spreadPts = [];
    let paintBars = bars;

    const built = buildSpread1mChartSeries(paintBars);
    zData = built.zPts;
    spreadPts = built.spreadPts;
    if (openTrade) {
      const inj = injectOpenEntryIntoChartSeries(zData, spreadPts, openTrade, paintBars, {
        primarySpread: true,
      });
      zData = inj.zData;
      spreadPts = inj.spreadPts;
      paintBars = inj.bars;
    }
    const synced = syncChartSeriesByTime(zData, spreadPts);
    zData = synced.zData;
    spreadPts = synced.spreadPts;

    zPriceByTime = new Map(zData.map((c) => [c.time, c.close != null ? c.close : c.value]));
    spPriceByTime = new Map(spreadPts.map((p) => [p.time, p.value]));

    const zEmpty = zData.length === 0;
    updateChartPaneLabels(monMode, {
      zEmpty: monMode && zEmpty,
      lookbackDays: monMode ? lookbackDays : null,
      spreadLevels,
    });
    if (monMode && zEmpty) {
      setZEmptyMessage('Нет баров дилер 1м · ISS M15 не рисуем · спред внизу тоже пуст');
    } else if (!monMode && zEmpty) {
      setZEmptyMessage('Нет tip1m 1м · ISS M15 не рисуем под лейблом tip1m');
    } else {
      setZEmptyMessage('');
    }

    // Bodies with open≠close (or real wick) count as visible candles.
    let bodyN = 0;
    for (const c of zData) {
      if (!c || c.open == null || c.close == null) continue;
      if (Math.abs(Number(c.open) - Number(c.close)) > 1e-9
        || (c.high != null && c.low != null && Math.abs(Number(c.high) - Number(c.low)) > 1e-9)) {
        bodyN += 1;
      }
    }
    const timesEqual = zData.length === spreadPts.length
      && (zData.length === 0 || (
        zData[0].time === spreadPts[0].time
        && zData[zData.length - 1].time === spreadPts[spreadPts.length - 1].time
      ));
    publishChartDebug({
      zPts: zData.length,
      spPts: spreadPts.length,
      bodyN,
      primary: chartPrimarySpread ? 'spread' : 'z',
      sample: zData.length ? [zData[0], zData[zData.length - 1]] : null,
      sync: { timesEqual, zN: zData.length, spN: spreadPts.length },
    });

    const fp = barsFingerprint(paintBars) + '|zc' + zData.length + '|sp' + spreadPts.length
      + '|c' + (closedTrades || []).length + '|o' + (openTrade ? openTrade.id : '')
      + (monMode ? '|d1m' : '')
      + (zSeriesIsLine ? '|zl' : '|zcndl')
      + (chartPrimarySpread ? '|ps' : '|pz')
      + (zEmpty ? '|ze' : '')
      + (Array.isArray(zBars) ? `|iss${zBars.length}` : '');
    const dataChanged = fp !== lastBarsFingerprint;
    lastBarsFingerprint = fp;

    // Типичный poll без новых баров: не трогаем timescale вообще
    if (!dataChanged && !forceFitContent && pinnedRange) {
      setPrimarySpreadThresholdLines(spreadLevels);
      setSpreadThresholdLines(spreadLevels);
      updateOpenTradeOnChart(openTrade, paintBars, closedTrades);
      if (userPinnedAwayFromLive) reassertPinnedRange();
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
        spreadSeries.setData(spreadPts);
      } catch (e) {
        suppressRangeEvents = false;
        publishChartDebug({
          zPts: zData.length,
          spPts: spreadPts.length,
          err: String(e && e.message || e),
          sync: { timesEqual, zN: zData.length, spN: spreadPts.length },
        });
        throw e;
      }
      // Trade-band fills need bar times before enter/exit price-lines refresh.
      updatePrimarySpreadBands(zData, spreadLevels);
      updateSpreadRegimeBands(spreadPts, spreadLevels);
      setPrimarySpreadThresholdLines(spreadLevels);
      setSpreadThresholdLines(spreadLevels);
      updateOpenTradeOnChart(openTrade, paintBars, closedTrades);
      // Dealer monitor / series swap: always refit once after first paint.
      if (monMode && (dataChanged || forceFitContent)) {
        forceFitContent = true;
      }
      restoreOrFitVisibleRange(zData.length, spreadPts.length);
      forceSyncAfterPaint();
      publishChartDebug({
        zPts: zData.length,
        spPts: spreadPts.length,
        bodyN,
        primary: chartPrimarySpread ? 'spread' : 'z',
        sample: zData.length ? [zData[0], zData[zData.length - 1]] : null,
        sync: { timesEqual, zN: zData.length, spN: spreadPts.length },
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

  function renderOpen(open, { barsMode = '', bars = null, settings = null } = {}) {
    const box = $('tradeOpenBox');
    if (!open) {
      box.innerHTML = '<div class="trade-open-empty">Нет открытой позиции</div>';
      clearProfitAlertBadge();
      return;
    }
    const m = open.mark || {};
    const pnlCls = (m.unrealized_pnl_rub || 0) >= 0 ? 'pnl-pos' : 'pnl-neg';
    const netCls = (m.net_approx_rub || 0) >= 0 ? 'pnl-pos' : 'pnl-neg';
    const riskCls = m.risk_red ? 'risk-red' : (m.risk_level === 'Elevated' ? 'risk-warn' : 'risk-ok');
    const spreadEntry = m.fill_spread != null ? m.fill_spread : open.entry_spread;
    const spreadLabel = m.pnl_source === 'broker_fills' ? 'Спред (fill→сейч)' : 'Спред';
    const pnlNote = m.pnl_source === 'broker_fills'
      ? 'по ценам Тинькофф'
      : (String(barsMode) === 'dealer_1m' ? 'по спреду дилер 1м' : 'по спреду ISS');
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
    box.innerHTML =
      `<div class="trade-open-dir">${open.direction} · ${open.quantity_lots}+${open.quantity_lots} лот · ${open.source || ''}</div>` +
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
      `<span>PnL ≈</span><b class="${pnlCls}">${fmtPnlWithDepositPct(m.unrealized_pnl_rub, deposit)}</b>` +
      `<span>Нетто ≈</span><b class="${netCls}">${fmtPnlWithDepositPct(m.net_approx_rub, deposit)}</b>` +
      `<span>Min</span><b class="${minLine.cls}">${minLine.text}</b>` +
      `<span>Max</span><b class="${maxLine.cls}">${maxLine.text}</b>` +
      `<span>Овн</span><b>${fmtRub(m.overnight_rub)} · ${m.overnight_days || 0}д</b>` +
      `<span>Hold</span><b>${m.hold_hours != null ? fmt(m.hold_hours, 1) + ' ч' : '—'}</b>` +
      `</div>` +
      `<div class="trade-open-pnl-src meta">${pnlNote} · % ${depLabel}</div>` +
      `<div class="trade-risk ${riskCls}">Риск ${m.risk_level || '—'} · score ${m.risk_score ?? '—'}` +
      (m.risk_flags && m.risk_flags.length ? ` · ${m.risk_flags.join(', ')}` : '') +
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

  const MID_TAB_IDS = new Set(['check', 'hang', 'params']);
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
  function formatBrokerError(err) {
    const s = String(err || '');
    if (/SSL:\s*цепочка/i.test(s)) return s.length > 220 ? `${s.slice(0, 217)}…` : s;
    if (/certificate verify failed|SSLCertVerification|self-signed certificate|CERTIFICATE_VERIFY_FAILED|Max retries exceeded.*tinkoff|Max retries exceeded.*tbank|Russian Trusted/i.test(s)) {
      return 'SSL: цепочка сертификатов (Russian Trusted CA / антивирус) — см. live/certs или MOEX_SSL_VERIFY=0';
    }
    return s.length > 220 ? `${s.slice(0, 217)}…` : s;
  }

  function renderFunds(broker, { pending = false } = {}) {
    const box = $('tradeFundsBox');
    const totalEl = $('tradeFundsTotal');
    const cashEl = $('tradeFundsCash');
    const brokerEl = $('tradeBrokerBox');
    if (!box || !totalEl || !cashEl) return;

    box.classList.remove('is-prod', 'is-error');
    if (brokerEl) {
      brokerEl.textContent = '';
      brokerEl.hidden = true;
    }
    if (!broker) {
      // Prefer last good over pending flash when coalesce kept nothing usable.
      if (pending && lastGoodBroker && brokerHasTotals(lastGoodBroker)) {
        broker = lastGoodBroker;
      } else {
        totalEl.textContent = pending ? '…' : '—';
        cashEl.textContent = pending ? 'брокер…' : 'нет токена — вкладка «Счёт»';
        return;
      }
    }
    if (broker.error) {
      box.classList.add('is-error');
      totalEl.textContent = '—';
      cashEl.textContent = modeLabel(broker.mode);
      if (brokerEl) {
        brokerEl.hidden = false;
        brokerEl.textContent = `Брокер: ${formatBrokerError(broker.error)}`;
      }
      return;
    }
    if (broker.mode === 'prod') box.classList.add('is-prod');
    const mode = modeLabel(broker.mode);
    const total = Number(broker.total_rub);
    const cash = Number(broker.cash_rub);
    totalEl.textContent = Number.isFinite(total) ? `${fmt(total, 0)} ₽` : '—';
    // Не дублировать ту же сумму в cash, если она ≈ total
    if (Number.isFinite(cash) && Number.isFinite(total) && Math.abs(cash - total) > 1) {
      cashEl.textContent = `${mode} · cash ${fmt(cash, 0)} ₽`;
    } else {
      cashEl.textContent = mode;
    }
  }

  function renderFundsAtOpen(open, fundsTotal) {
    const el = $('tradeFundsAtOpen');
    if (!el) return;
    if (!open) {
      el.hidden = true;
      el.textContent = '';
      return;
    }
    const atOpen = equityAtOpenRub(open, { fundsTotal });
    if (atOpen == null) {
      el.hidden = true;
      el.textContent = '';
      return;
    }
    el.hidden = false;
    el.textContent = `До ${fmt(atOpen, 0)} ₽`;
    el.title = 'Сумма на счету на входе (база Чист.)';
  }

  function fmtRubPlain(n, digits = 0) {
    if (n == null || !Number.isFinite(Number(n))) return '—';
    return `${fmt(Number(n), digits)} ₽`;
  }

  /**
   * % of entry deposit (вложение), not account equity and not deposit×leverage.
   * 1 decimal for |pct|≥1 (desk style); 2 decimals for tiny close-Δ.
   */
  function fmtStakePct(pct) {
    if (!Number.isFinite(pct)) return '';
    const abs = Math.abs(pct);
    const digits = abs >= 1 ? 1 : 2;
    return `${pct >= 0 ? '+' : '−'}${abs.toFixed(digits)}%`;
  }

  /** Entry deposit ₽ (settings / UI), never notional. */
  function entryDepositRub(open, settings) {
    const fromOpen = Number(open?.entry_deposit_rub);
    if (Number.isFinite(fromOpen) && fromOpen > 0) return fromOpen;
    const dep = Number(
      settings?.entry_deposit_rub
      ?? readEntryDeposit()
      ?? 10000,
    );
    return Number.isFinite(dep) && dep > 0 ? dep : 10000;
  }

  /** @deprecated kept for any external callers — prefer entryDepositRub for %. */
  function stakeNotionalRub(open, settings) {
    return entryDepositRub(open, settings);
  }

  function openProfitRub(open) {
    const mark = (open && open.mark) || {};
    const mtm = Number(mark.unrealized_pnl_rub);
    if (Number.isFinite(mtm)) return mtm;
    const net = Number(mark.net_approx_rub);
    return Number.isFinite(net) ? net : null;
  }

  function isOpenProfitAlertHit(open, settings) {
    if (!open) return false;
    const deposit = entryDepositRub(open, settings);
    const profit = openProfitRub(open);
    if (!(deposit > 0) || profit == null) return false;
    return profit >= deposit * (PROFIT_ALERT_PCT / 100);
  }

  function clearProfitAlertBadge() {
    const toast = $('tradeProfitToast');
    if (toast) {
      toast.hidden = true;
      toast.textContent = '';
    }
  }

  let profitToastTimer = null;
  function maybeFireProfitAlert(open, settings) {
    if (!isOpenProfitAlertHit(open, settings)) return;
    const tid = String(open.id ?? open.entry_time ?? '');
    if (!tid) return;
    let prev = '';
    try { prev = localStorage.getItem(LS_PROFIT_ALERT_TRADE) || ''; } catch (_) {}
    if (prev === tid) return;
    try { localStorage.setItem(LS_PROFIT_ALERT_TRADE, tid); } catch (_) {}
    const deposit = entryDepositRub(open, settings);
    const profit = openProfitRub(open);
    const pct = deposit > 0 && profit != null ? (profit / deposit) * 100 : PROFIT_ALERT_PCT;
    const msg =
      `Прибыль ≥${PROFIT_ALERT_PCT}% от вложения ${fmt(deposit, 0)} ₽` +
      ` · сейчас ${fmtRub(profit)} (${fmtStakePct(pct)})`;
    let toast = $('tradeProfitToast');
    if (!toast) {
      toast = document.createElement('div');
      toast.id = 'tradeProfitToast';
      toast.className = 'trade-profit-toast';
      toast.setAttribute('role', 'status');
      const host = $('tradeSidePanel') || $('tradeOpenBox') || document.body;
      host.prepend(toast);
    }
    toast.hidden = false;
    toast.textContent = msg;
    if (profitToastTimer) clearTimeout(profitToastTimer);
    profitToastTimer = setTimeout(() => {
      if (toast && toast.textContent === msg) toast.hidden = true;
    }, 12000);
  }

  /** Last good close forecast — lite polls must not wipe full-desk number. */
  let lastCloseForecast = null;

  /**
   * Equity / funds right after this trade opened («До» / Δ vs forecast).
   * Not the % base — percent-of-investment uses entry deposit.
   * Prefer last entry-leg portfolio_total; else API field; else current − mark PnL.
   */
  function equityAtOpenRub(open, {
    fundsTotal = null,
    forecastEquityOpen = null,
  } = {}) {
    const fromApi = Number(
      forecastEquityOpen
      ?? open?.equity_at_open_rub
      ?? open?.account_after_rub,
    );
    if (Number.isFinite(fromApi) && fromApi > 0) return fromApi;

    let legs = open?.legs ?? open?.legs_json;
    if (typeof legs === 'string') {
      try { legs = JSON.parse(legs); } catch (_) { legs = null; }
    }
    if (Array.isArray(legs)) {
      for (let i = legs.length - 1; i >= 0; i -= 1) {
        const leg = legs[i];
        if (!leg || typeof leg !== 'object') continue;
        const total = Number(leg.portfolio_total_rub);
        if (Number.isFinite(total) && total > 0) return total;
        const cash = Number(leg.portfolio_cash_rub);
        if (Number.isFinite(cash) && cash > 0) return cash;
      }
    }

    const now = Number(fundsTotal);
    const mtm = openProfitRub(open);
    if (Number.isFinite(now) && Number.isFinite(mtm)) {
      const derived = now - mtm;
      if (Number.isFinite(derived) && derived > 0) return derived;
    }
    return null;
  }

  /**
   * Потенциал при выходе на L вых / S вых (спред-% уровни).
   * notional×ΔS/100 − комиссия выхода − overnight (как в прогнозе закрытия).
   */
  function exitLevelPotential(fc, open, {
    depositRub = null,
    settings = null,
  } = {}) {
    if (fc && fc.exit_level_pnl_rub != null && Number.isFinite(Number(fc.exit_level_pnl_rub))
        && fc.exit_level_spread != null && Number.isFinite(Number(fc.exit_level_spread))) {
      const pnl = Number(fc.exit_level_pnl_rub);
      const spread = Number(fc.exit_level_spread);
      const dep = Number(fc.exit_level_deposit_rub);
      const deposit = (Number.isFinite(dep) && dep > 0)
        ? dep
        : (Number.isFinite(Number(depositRub)) && Number(depositRub) > 0
          ? Number(depositRub)
          : entryDepositRub(open, settings));
      return { pnl, spread, deposit };
    }

    const dir = String(open?.direction || '').toUpperCase();
    const mark = open?.mark && typeof open.mark === 'object' ? open.mark : {};
    const entrySp = Number(mark.fill_spread != null ? mark.fill_spread : open?.entry_spread);
    if (!Number.isFinite(entrySp)) return null;

    const lv = settings?.spread_exit_narrow != null || settings?.spread_exit_wide != null
      ? {
        exitNarrow: Number(settings?.spread_exit_narrow ?? 4.0),
        exitWide: Number(settings?.spread_exit_wide ?? 5.8),
      }
      : {
        exitNarrow: 4.0,
        exitWide: 5.8,
      };
    let exitSp = null;
    if (dir.startsWith('L')) exitSp = lv.exitNarrow;
    else if (dir.startsWith('S')) exitSp = lv.exitWide;
    if (exitSp == null || !Number.isFinite(exitSp)) return null;

    const notional = Number(
      mark.notional_rub
      ?? open?.execution_notional_rub
      ?? open?.notional_rub
      ?? 0,
    );
    const deposit = (() => {
      const d = Number(depositRub);
      if (Number.isFinite(d) && d > 0) return d;
      return entryDepositRub(open, settings);
    })();
    const lev = Number(settings?.leverage ?? open?.leverage ?? 7) || 7;
    const eff = (Number.isFinite(notional) && notional > 0) ? notional : deposit * lev;
    if (!(eff > 0)) return null;

    const pnlPts = dir.startsWith('L') ? (exitSp - entrySp) : (entrySp - exitSp);
    const gross = eff * (pnlPts / 100);
    const exitComm = (fc && fc.exit_commission_rub != null && Number.isFinite(Number(fc.exit_commission_rub)))
      ? Number(fc.exit_commission_rub)
      : eff * 0.0004;
    const ovn = (fc && fc.overnight_rub != null && Number.isFinite(Number(fc.overnight_rub)))
      ? Number(fc.overnight_rub)
      : (Number(mark.overnight_rub) || 0);
    return { pnl: gross - exitComm - ovn, spread: exitSp, deposit };
  }

  /** Премиум ₽/день по короткой ноге (как mark / overnight_fee). */
  function premiumOvernightPerDayRub(open, fc = null) {
    const fromFc = Number(fc?.overnight_per_day_rub);
    if (Number.isFinite(fromFc) && fromFc >= 0) return fromFc;
    const mark = open?.mark && typeof open.mark === 'object' ? open.mark : {};
    const fromMark = Number(mark.overnight_per_day_rub);
    if (Number.isFinite(fromMark) && fromMark >= 0) return fromMark;
    const days = Number(mark.overnight_days ?? fc?.overnight_days);
    const total = Number(mark.overnight_rub ?? fc?.overnight_rub);
    if (Number.isFinite(days) && days > 0 && Number.isFinite(total) && total >= 0) {
      return total / days;
    }
    const dir = String(open?.direction || '').toUpperCase();
    const fillTn = Number(mark.fill_tatn ?? open?.entry_tatn);
    const fillTp = Number(mark.fill_tatnp ?? open?.entry_tatnp);
    const lots = Number(open?.quantity_lots) || 0;
    const notional = Number(
      mark.notional_rub
      ?? open?.execution_notional_rub
      ?? open?.notional_rub
      ?? 0,
    );
    let uncovered = 0;
    if (lots > 0 && Number.isFinite(fillTn) && Number.isFinite(fillTp) && fillTn > 0 && fillTp > 0) {
      uncovered = dir.startsWith('L') ? lots * fillTp : lots * fillTn;
    } else if (Number.isFinite(notional) && notional > 0) {
      uncovered = notional / 2;
    }
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
  }

  /**
   * Сколько overnight-суток съедят подушку «при выходе ≈ +X».
   * days_to_red = ceil(X / ₽/д) — после стольких полуночей плюс при выходе ≤ 0.
   * floor(X / ₽/д) = ещё полных дней с остатком > 0 (если не кратно).
   */
  function exitOvernightCushionLine(cushionRub, perDayRub) {
    const perDay = Number(perDayRub);
    const cushion = Number(cushionRub);
    if (!Number.isFinite(perDay) || perDay < 0) return null;
    if (!Number.isFinite(cushion)) return null;
    const rateTxt = Number(perDay).toLocaleString('ru-RU', {
      maximumFractionDigits: perDay % 1 === 0 ? 0 : 1,
    });
    if (cushion <= 0) {
      return {
        text: 'овернайт: уже в минусе по модели',
        cls: 'pnl-neg',
        days: 0,
        perDay,
      };
    }
    if (perDay <= 0) {
      return {
        text: 'овернайт 0 ₽/д — подушка при выходе не съедается',
        cls: '',
        days: null,
        perDay: 0,
      };
    }
    const daysToRed = Math.ceil(cushion / perDay);
    const redOn = new Date();
    redOn.setHours(0, 0, 0, 0);
    redOn.setDate(redOn.getDate() + Math.max(1, daysToRed));
    const redDateTxt = `${String(redOn.getDate()).padStart(2, '0')}.${String(redOn.getMonth() + 1).padStart(2, '0')}`;
    const text = daysToRed <= 0
      ? `овернайт → минус через < 1 дн. (${redDateTxt}, ${rateTxt} ₽/д)`
      : `овернайт → минус через ≈ ${daysToRed} дн. (${redDateTxt}, ${rateTxt} ₽/д)`;
    return { text, cls: '', days: daysToRed, perDay, redDate: redDateTxt };
  }

  function renderCloseForecast(fc, {
    hasOpen = false,
    fundsTotal = null,
    open = null,
    depositRub = null,
    stakeNotional = null,
    settings = null,
  } = {}) {
    const root = $('tradeCloseForecast');
    const main = $('tradeCloseForecastMain');
    const deltaEl = $('tradeCloseForecastDelta');
    const exitEl = $('tradeCloseForecastExit');
    const ovnEl = $('tradeCloseForecastOvn');
    const sub = $('tradeCloseForecastSub');
    if (!root || !main || !sub) return;

    const clearDelta = () => {
      if (!deltaEl) return;
      deltaEl.hidden = true;
      deltaEl.textContent = '';
      deltaEl.className = 'account-funds-forecast-delta';
    };
    const clearExit = () => {
      if (!exitEl) return;
      exitEl.hidden = true;
      exitEl.textContent = '';
      exitEl.className = 'account-funds-forecast-exit';
    };
    const clearOvn = () => {
      if (!ovnEl) return;
      ovnEl.hidden = true;
      ovnEl.textContent = '';
      ovnEl.className = 'account-funds-forecast-ovn';
    };

    if (!hasOpen) {
      lastCloseForecast = null;
      root.hidden = true;
      main.textContent = 'Прогноз после закрытия ≈ —';
      clearDelta();
      clearExit();
      clearOvn();
      sub.textContent = '';
      return;
    }

    const incomingOk = fc
      && fc.forecast_total_rub != null
      && Number.isFinite(Number(fc.forecast_total_rub));
    if (incomingOk) {
      lastCloseForecast = fc;
    } else if (lastCloseForecast && lastCloseForecast.forecast_total_rub != null) {
      fc = lastCloseForecast;
    }

    root.hidden = false;
    const total = fc && fc.forecast_total_rub != null ? Number(fc.forecast_total_rub) : null;
    // Same total as «СРЕДСТВА НА СЧЁТЕ» (broker.total_rub), not cash / float equity alone.
    let equityNow = null;
    if (fundsTotal != null && Number.isFinite(Number(fundsTotal))) {
      equityNow = Number(fundsTotal);
    } else if (fc && fc.equity_now_rub != null && Number.isFinite(Number(fc.equity_now_rub))) {
      equityNow = Number(fc.equity_now_rub);
    }
    // Round first so on-screen lines and «ожид.» share the same integers.
    const forecastRub = (total != null && Number.isFinite(total)) ? Math.round(total) : null;
    const accountNowRub = (equityNow != null && Number.isFinite(equityNow))
      ? Math.round(equityNow)
      : null;
    const equityOpen = equityAtOpenRub(open, {
      fundsTotal: equityNow,
      forecastEquityOpen: fc?.equity_at_open_rub,
    });
    const equityOpenRub = (equityOpen != null && Number.isFinite(equityOpen) && equityOpen > 0)
      ? Math.round(equityOpen)
      : null;
    if (forecastRub != null) {
      main.textContent = `Прогноз после закрытия ≈ ${fmt(forecastRub, 0)} ₽`;
    } else {
      main.textContent = 'Прогноз после закрытия ≈ —';
    }

    // Primary Δ = round(прогноз) − round(средства на открытии) — account change.
    // % «от вложения» must use entry deposit (params / open.entry_deposit_rub), not «До».
    const depositBase = (() => {
      const d = Number(depositRub);
      if (Number.isFinite(d) && d > 0) return Math.round(d);
      const fallback = entryDepositRub(open, null);
      return Number.isFinite(fallback) && fallback > 0 ? Math.round(fallback) : null;
    })();
    let primaryDelta = null;
    let pctBase = depositBase;
    let usedMarkFallback = false;
    if (forecastRub != null && equityOpenRub != null) {
      primaryDelta = forecastRub - equityOpenRub;
    } else {
      const markNet = openProfitRub(open);
      if (markNet != null && Number.isFinite(markNet)) {
        primaryDelta = Math.round(markNet);
        usedMarkFallback = true;
      }
    }
    if (deltaEl) {
      if (primaryDelta != null && Number.isFinite(primaryDelta)) {
        const cls = primaryDelta > 0 ? 'pnl-pos' : primaryDelta < 0 ? 'pnl-neg' : '';
        deltaEl.hidden = false;
        deltaEl.className = `account-funds-forecast-delta${cls ? ` ${cls}` : ''}`;
        let text = `ожид. ≈ ${fmtRub(primaryDelta)}`;
        if (Number.isFinite(pctBase) && pctBase > 0) {
          text += ` (${fmtStakePct((primaryDelta / pctBase) * 100)} от вложения ${fmt(pctBase, 0)} ₽)`;
        } else if (usedMarkFallback) {
          text += ' (по MTM к открытию)';
        }
        deltaEl.textContent = text;
      } else {
        clearDelta();
      }
    }

    let exitPot = null;
    if (exitEl) {
      const pot = exitLevelPotential(fc, open, { depositRub, settings });
      if (pot && Number.isFinite(pot.pnl) && Number.isFinite(pot.spread)) {
        exitPot = pot;
        const pnlRound = Math.round(pot.pnl);
        const cls = pnlRound > 0 ? 'pnl-pos' : pnlRound < 0 ? 'pnl-neg' : '';
        exitEl.hidden = false;
        exitEl.className = `account-funds-forecast-exit${cls ? ` ${cls}` : ''}`;
        const spTxt = Number(pot.spread).toLocaleString('ru-RU', {
          minimumFractionDigits: 1,
          maximumFractionDigits: 1,
        });
        let text = `при выходе S ${spTxt}% ≈ ${fmtRub(pnlRound)}`;
        const dep = Math.round(Number(pot.deposit));
        if (Number.isFinite(dep) && dep > 0) {
          text += ` (${fmtStakePct((pnlRound / dep) * 100)} от вложения ${fmt(dep, 0)} ₽)`;
        }
        exitEl.textContent = text;
      } else {
        clearExit();
      }
    }

    if (ovnEl) {
      if (exitPot && Number.isFinite(exitPot.pnl)) {
        const perDay = (fc && fc.overnight_per_day_rub != null
          && Number.isFinite(Number(fc.overnight_per_day_rub)))
          ? Number(fc.overnight_per_day_rub)
          : premiumOvernightPerDayRub(open, fc);
        const line = exitOvernightCushionLine(Math.round(exitPot.pnl), perDay);
        if (line) {
          ovnEl.hidden = false;
          ovnEl.className = `account-funds-forecast-ovn${line.cls ? ` ${line.cls}` : ''}`;
          ovnEl.textContent = line.text;
        } else {
          clearOvn();
        }
      } else {
        clearOvn();
      }
    }

    const bits = [];
    if (forecastRub != null && accountNowRub != null) {
      const vsNow = forecastRub - accountNowRub;
      bits.push(`к текущим средствам ≈ ${fmtRub(vsNow)} (комиссии/закрытие)`);
    }
    if (fc && fc.exit_commission_rub != null) {
      bits.push(`комиссии ≈ ${fmtRubPlain(fc.exit_commission_rub, 0)}`);
    }
    if (fc && fc.overnight_rub != null) {
      const d = fc.overnight_days != null ? ` · ${fc.overnight_days}д` : '';
      bits.push(`overnight ≈ ${fmtRubPlain(fc.overnight_rub, 0)}${d}`);
    }
    if (fc && fc.vs_mid_rub != null) {
      const v = Number(fc.vs_mid_rub);
      const sign = v > 0 ? '+' : '';
      bits.push(`vs mid ${sign}${fmt(v, 0)} ₽`);
    }
    if (fc && fc.note) bits.push(fc.note);
    sub.textContent = bits.join(' · ');
  }

  function paramsFocused() {
    const ae = document.activeElement;
    return !!(ae && (ae.id === 'tradeLeverage' || ae.id === 'tradeAutoExec'
      || ae.id === 'tradeTpSel' || ae.id === 'tradeSpreadLevels'
      || ae.id === 'tradeEntryDeposit'));
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

  function readFormParams() {
    const leverage = parseFloat(String($('tradeLeverage')?.value || '').replace(',', '.'));
    const tpRaw = parseFloat(String($('tradeTpSel')?.value || '0').replace(',', '.'));
    const tpAllowed = [0, 1, 2, 3];
    const takeProfit = tpAllowed.includes(tpRaw) ? tpRaw : 0;
    // entry_z / exit_z / regime_z_mode не шлём с UI — серверные значения не трогаем.
    return {
      leverage: Number.isFinite(leverage) ? leverage : null,
      take_profit_pct: takeProfit,
      auto_execute: !!$('tradeAutoExec')?.checked,
      spread_level_mode: !!$('tradeSpreadLevels')?.checked,
      entry_deposit_rub: readEntryDeposit(),
    };
  }

  function cacheParamsLocal(settings) {
    if (!settings) return;
    try {
      localStorage.setItem(LS_TRADE_PARAMS, JSON.stringify({
        entry_z: settings.entry_z,
        exit_z: settings.exit_z,
        leverage: settings.leverage,
        take_profit_pct: settings.take_profit_pct != null ? settings.take_profit_pct : 0,
        auto_execute: !!settings.auto_execute,
        spread_level_mode: settings.spread_level_mode !== false,
        regime_z_mode: !!settings.regime_z_mode,
        entry_deposit_rub: settings.entry_deposit_rub != null ? settings.entry_deposit_rub : 10000,
      }));
    } catch (_) { /* ignore quota */ }
  }

  function loadCachedParamsLocal() {
    try {
      const raw = localStorage.getItem(LS_TRADE_PARAMS);
      if (!raw) return null;
      const o = JSON.parse(raw);
      if (!o || o.leverage == null) return null;
      if (o.entry_deposit_rub == null) o.entry_deposit_rub = 10000;
      if (o.take_profit_pct == null) o.take_profit_pct = 0;
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
      const tp = settings.take_profit_pct != null ? Number(settings.take_profit_pct) : 0;
      const v = [0, 1, 2, 3].includes(tp) ? String(tp) : '0';
      $('tradeTpSel').value = v;
    }
    if ($('tradeEntryDeposit')) {
      const dep = settings.entry_deposit_rub != null ? settings.entry_deposit_rub : 10000;
      $('tradeEntryDeposit').value = String(dep);
    }
    if ($('tradeAutoExec')) $('tradeAutoExec').checked = !!settings.auto_execute;
    if ($('tradeSpreadLevels')) {
      $('tradeSpreadLevels').checked = settings.spread_level_mode !== false;
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
          label.textContent = dealer1m
            ? `уровни L ${fmt(en, 1)}/${fmt(xn, 1)} · S ${fmt(xw, 1)}/${fmt(ew, 1)} · дилер монитор (не AUTO)`
            : `уровни L ${fmt(en, 1)}/${fmt(xn, 1)} · S ${fmt(xw, 1)}/${fmt(ew, 1)}`;
        } else {
          label.textContent = dealer1m
            ? 'уровни спреда · дилер монитор (не AUTO)'
            : 'уровни спреда';
        }
        label.classList.toggle('pnl-label-dealer', !!dealer1m);
      }
    }
    setPrimarySpreadThresholdLines(spreadGuide.levels);
    setSpreadThresholdLines(spreadGuide.levels);
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
    bindScrollPersist($('tradeSidePanel'), LS_SIDE_SCROLL);
    bindScrollPersist($('tradeView'), LS_DESK_SCROLL);
  }

  function restoreTradeScrolls() {
    restoreScroll($('tradeChecklistPanel'), LS_CHECK_SCROLL);
    restoreScroll($('tradeSidePanel'), LS_SIDE_SCROLL);
    restoreScroll($('tradeView'), LS_DESK_SCROLL);
  }

  function isTqbrSessionBar(tradeDate) {
    const s = String(tradeDate || '').replace('T', ' ').trim();
    if (s.length < 16) return false;
    const m = s.match(/^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})/);
    if (!m) return false;
    // tradeDate already MSK wall-clock
    const wd = new Date(+m[1], +m[2] - 1, +m[3]).getDay();
    if (wd === 0 || wd === 6) return false;
    const mins = (+m[4]) * 60 + (+m[5]);
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
    // AUTO tip1m / TQBR: до 23:50
    const inSession = !weekend && mins >= 7 * 60 && mins < 23 * 60 + 50;
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
        + ` · Short ${fmt(lv.enter_wide ?? 6.2, 1)}/${fmt(lv.exit_wide ?? 5.8, 1)}`
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
      general.push(checkItem('wait',
        `TQBR закрыта (${msk.label} МСК) — AUTO tip1m только в сессии · дилер = монитор/ручной`));
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
    const enterW = Number(lv.enter_wide ?? 6.2);
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
      if (msk.weekend || dealer) blockers.push('дилер/выходные (нет AUTO tip)');
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
    let chartBars = (dealer1mMode || barsLookDealer)
      ? (data.bars || [])
      : (weekendMonitor ? [] : (data.bars || []));
    if ((dealer1mMode || weekendMonitor || barsLookDealer) && chartBars.length) {
      chartBars = attachDealerMonitorZClient(chartBars, data.bars_iss || []);
      data = { ...data, bars: chartBars };
      const lastMon = [...chartBars].reverse().find((b) => b && b.z != null);
      if (lastMon) {
        s.z = lastMon.z;
        s.z_kind = 'dealer_monitor';
      }
    } else if (!weekendMonitor && chartBars.length) {
      // tip1m: last candle/overlay must track live tip (monitor / mark), not sidecar Z.
      chartBars = alignTip1mBarsToLiveTip(chartBars, data, data.open || null);
      data = { ...data, bars: chartBars };
      const lastTip = chartBars[chartBars.length - 1];
      if (lastTip) {
        if (lastTip.z != null) s.z = lastTip.z;
        if (lastTip.spread != null) s.spread = lastTip.spread;
      }
    }

    const bars = data.bars || [];
    const barsForDist = (dealer1mMode && (data.bars_iss || []).length)
      ? data.bars_iss
      : bars;
    rebuildBarMetricDists(barsForDist, data.metric_dists);
    const regimeHtml = regimeBadge(bars);
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
    const dealerHtml = dealerBadge(dealer);
    const spreadFreezeHtml = spreadFrozenBadge(dealer, mskNow);

    // Данные / связь — только в шапке
    const distHint = metricDistMeta.degraded
      ? ` · dist:окно`
      : ` · dist:3г n=${metricDistMeta.n || '—'}`;
    $('tradeMeta').innerHTML =
      `${s.window_count || 0} баров · ${escapeHtml(s.source || '—')} · ${onlineBadge(!!s.online)}`
      + (dealerHtml ? ` · ${dealerHtml}` : '')
      + (spreadFreezeHtml ? ` · ${spreadFreezeHtml}` : '')
      + (dealer1mMode || data.weekend_monitor
        ? ' · <span class="badge-quiet">спред 1м дилер · монитор (не AUTO)</span>'
        : '')
      + distHint;

    // Исполнение — один раз в статус-баре (без рынка и без баланса)
    const partialBanner = applyPartialBanner(data);
    $('tradeStatus').innerHTML =
      `${monHtml} · ${autoHtml} · ${modeHtml} · ${stratHtml}` +
      (slHtml ? ` · ${slHtml}` : '') +
      (regimeZHtml ? ` · ${regimeZHtml}` : '') +
      (lagHtml ? ` · ${lagHtml}` : '') +
      (dealerHtml ? ` · ${dealerHtml}` : '') +
      (partialBanner ? ` · <span class="badge-quiet">${escapeHtml(partialBanner)}</span>` : '') +
      (s.trade_date ? ` · ${tickBadge(s.trade_date)}` : '');

    // Рынок — у графиков (свечи спреда; выходные — дилер 1м)
    const stripSpLabel = useDealerPx
      ? (dealer1mMode ? 'Спред · дилер 1м' : 'Спред · дилер')
      : 'Спред';
    $('tradeStrip').innerHTML = [
      metricStripBlock(stripSpLabel, 'spread', spVal, spDisp),
      regimeHtml ? `<span><b>Режим</b> ${regimeHtml}</span>` : '',
      `<span><b>TATN</b> ${fmt(tatnDisp)}${useDealerPx ? ' <span class="badge-quiet">дилер</span>' : ''}</span>`,
      `<span><b>TATNP</b> ${fmt(tatnpDisp)}${useDealerPx ? ' <span class="badge-quiet">дилер</span>' : ''}</span>`,
      useDealerPx && dealer.tatn_bid != null
        ? `<span class="trade-dealer-ba"><b>bid/ask</b> ${fmt(dealer.tatn_bid)}/${fmt(dealer.tatn_ask)} · ${fmt(dealer.tatnp_bid)}/${fmt(dealer.tatnp_ask)}</span>`
        : '',
      (dealer && dealer.bars_count != null)
        ? `<span class="badge-quiet" title="${escapeHtml(String(dealer.lookback_note || data.dealer_lookback_note || ''))}">${dealer.bars_count}×1м${
            (dealer.lookback_days != null || data.dealer_lookback_days != null)
              ? ` · до ${dealer.lookback_days || data.dealer_lookback_days}д`
              : ''
          }</span>`
        : '',
      (dealer1mMode || data.weekend_monitor) && (dealer?.lookback_note || data.dealer_lookback_capped)
        ? `<span class="badge-quiet">${escapeHtml(String(
            dealer?.lookback_note
            || (data.dealer_lookback_capped
              ? `дилер 1м: до ${data.dealer_lookback_days || 7}д (чип урезан)`
              : '')
          ))}</span>`
        : '',
    ].filter(Boolean).join(' ');

    // Hydrate once from server; never while user is editing (poll / late desk).
    const shouldHydrate = (hydrateForm || !formHydrated) && !formDirty && !paramsFocused();
    if (shouldHydrate && (settings.leverage != null || settings.spread_level_mode != null)) {
      hydrateParams(settings);
    } else if (!formHydrated && !formDirty) {
      const cached = loadCachedParamsLocal();
      if (cached) hydrateParams(cached);
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
    });
  }

  async function ensureMonitorRunning(data) {
    const settings = data.settings || {};
    const mon = data.monitor || {};
    if (settings.monitor_running && !mon.running) {
      try {
        await api('/api/live/monitor/start', { method: 'POST' });
        return true;
      } catch (_) { /* keep status as-is */ }
    }
    return false;
  }

  function dealerHistBarCount(bars) {
    let n = 0;
    for (const b of bars || []) {
      if (b && String(b.source || '') === 'tinvest_dealer_1m') n += 1;
    }
    return n;
  }

  function rememberGoodChartBars(bars, data) {
    const n = dealerHistBarCount(bars);
    const total = Array.isArray(bars) ? bars.length : 0;
    if (n >= 5 || (total >= 5 && !(data && data.weekend_monitor))) {
      lastGoodChartBars = bars.slice();
      lastGoodDeskMeta = {
        bars_mode: data && data.bars_mode,
        weekend_monitor: !!(data && data.weekend_monitor),
        partial: !!(data && data.partial),
      };
    }
  }

  function applyPartialBanner(data, extraMsg) {
    const parts = [];
    if (data && (data.partial || data.dealer_warming || data.tip1m_warming
      || (data.dealer && data.dealer.warming))) {
      parts.push('кэш / частичные данные');
    }
    if (data && data.tip1m_warming) parts.push('tip1m греется');
    if (data && data.dealer && data.dealer.from_cache) parts.push('дилер из кэша');
    if (extraMsg) parts.push(extraMsg);
    lastPartialBanner = parts.filter(Boolean).join(' · ');
    return lastPartialBanner;
  }

  async function refresh({ hydrateForm = false, forceMoex = false, lite = false } = {}) {
    if (forceMoex) {
      await api(`/api/markets/refresh?days=${days}`, { method: 'POST', timeoutMs: 20000 });
    }
    const seq = ++deskFetchSeq;
    const liteQ = lite ? '&lite=1' : '';
    // Lite must stay snappy; full desk capped so UI never hangs forever.
    const timeoutMs = lite ? 8000 : 28000;
    const fetchDesk = () => api(`/api/trade/desk?days=${days}${liteQ}`, { timeoutMs });
    let data;
    try {
      data = await fetchDesk();
    } catch (e) {
      const msg = String((e && e.message) || e || '');
      if (/Таймаут|timeout|Failed to fetch|NetworkError/i.test(msg)) {
        await new Promise((r) => setTimeout(r, 900));
        if (seq !== deskFetchSeq) return null;
        try {
          data = await fetchDesk();
        } catch (e2) {
          if (seq !== deskFetchSeq) return null;
          // Desk wedged: still upgrade tip1m charts from sidecar (never paint M15).
          try {
            const stub = await ensureWeekdayTip1mBars({
              ok: true,
              days,
              lite: !!lite,
              bars_mode: 'iss_m15',
              bars: lastGoodChartBars.length ? lastGoodChartBars : [],
              bars_iss: [],
              settings: loadCachedParamsLocal() || {},
              summary: {},
              position: 'FLAT',
              partial: true,
              tip1m_warming: true,
            });
            if (stub && (stub.bars || []).length) {
              rememberGoodChartBars(stub.bars || [], stub);
              renderDesk(stub, { hydrateForm: false });
              const el = $('tradeStatus');
              if (el) {
                el.textContent = `Торговля · tip1m sidecar · ${msg.replace(/^Таймаут \d+мс: /, 'таймаут ')}`;
              }
              return stub;
            }
          } catch (_) { /* fall through */ }
          // Keep last good charts — never wipe to empty nonsense on timeout.
          if (lastGoodChartBars.length) {
            const el = $('tradeStatus');
            const banner = applyPartialBanner(
              { partial: true, dealer_warming: true },
              msg.replace(/^Таймаут \d+мс: /, 'таймаут '),
            );
            if (el) {
              el.textContent = `${el.textContent || 'Торговля'} · ${banner}`;
            }
            // Keep existing chart series — do not wipe to empty.
            return lastGoodDeskMeta;
          }
          throw e2;
        }
      } else {
        throw e;
      }
    }
    if (seq !== deskFetchSeq) return data;
    // Monitor start only on full desk (lite is first-paint charts)
    if (!lite && await ensureMonitorRunning(data)) {
      data = await api(`/api/trade/desk?days=${days}`, { timeoutMs: 20000 });
      if (seq !== deskFetchSeq) return data;
    }
    // Weekday: never paint iss_m15 under tip1m labels (sidecar / bars1m upgrade).
    // Keep chip days even if running API still coerces unknown windows → 7.
    data.days = days;
    data = await ensureWeekdayTip1mBars(data);
    if (seq !== deskFetchSeq) return data;
    rememberGoodChartBars(data.bars || [], data);
    // Late responses: never force-hydrate over newer in-flight work
    const hydrate = hydrateForm && seq === deskFetchSeq;
    renderDesk(data, { hydrateForm: hydrate });
    ensurePollInterval(data);
    return data;
  }

  async function saveEntryDeposit() {
    const deposit = readEntryDeposit();
    if (deposit == null) {
      setDepositStatus('Проверьте число', 'err');
      throw new Error('Некорректный депозит');
    }
    setDepositStatus('Сохранение…', 'pending');
    const btn = $('tradeBtnSaveDeposit');
    if (btn) btn.disabled = true;
    try {
      const res = await api('/api/portfolio/params', {
        method: 'POST',
        body: JSON.stringify({ entry_deposit_rub: deposit }),
      });
      const saved = res.settings || { entry_deposit_rub: deposit };
      if ($('tradeEntryDeposit') && saved.entry_deposit_rub != null) {
        $('tradeEntryDeposit').value = String(saved.entry_deposit_rub);
      }
      const cached = loadCachedParamsLocal() || {};
      cacheParamsLocal({ ...cached, ...saved, entry_deposit_rub: saved.entry_deposit_rub ?? deposit });
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

  function resize() {
    const zEl = $('tradeZChart');
    const sEl = $('tradeSpreadChart');
    suppressRangeEvents = true;
    try {
      if (zChart && zEl) zChart.applyOptions({ width: zEl.clientWidth, height: zEl.clientHeight || 300 });
      if (spreadChart && sEl) spreadChart.applyOptions({ width: sEl.clientWidth, height: sEl.clientHeight || 150 });
      equalizePriceScales();
    } finally {
      scheduleEndSuppress();
    }
    if (pinnedRange) {
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

  function startPoll(ms) {
    stopPoll();
    if (ms != null && Number.isFinite(ms) && ms >= 2000) pollMs = ms;
    pollTimer = setInterval(() => {
      pollTick += 1;
      const doFull = (pollTick % POLL_FULL_EVERY) === 0;
      refresh({ lite: !doFull }).catch(() => {});
    }, pollMs);
  }

  function stopPoll() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  }

  function ensurePollInterval(data) {
    const want = (
      String(data?.bars_mode || '').startsWith('dealer')
      || data?.weekend_monitor
      || (data?.dealer && data.dealer.ok)
    )
      ? POLL_MS_DEALER_1M
      : POLL_MS_DEFAULT;
    if (want !== pollMs) startPoll(want);
  }

  let hangLoading = false;
  let hangLast = null;

  function fmtHangNum(n, d = 2) {
    if (n == null || Number.isNaN(Number(n))) return '—';
    return Number(n).toFixed(d);
  }

  function hangStatusClass(st) {
    const s = String(st || '');
    if (s === 'полка') return 'hang-st-shelf';
    if (s === 'край') return 'hang-st-edge';
    if (s === 'сжатие') return 'hang-st-compress';
    if (s === 'норма') return 'hang-st-ok';
    return 'hang-st-na';
  }

  function renderHangPanel(data) {
    const body = $('tradeHangBody');
    const meta = $('tradeHangMeta');
    const rules = $('tradeHangRules');
    if (!body) return;
    hangLast = data;
    const pairs = (data && data.pairs) || [];
    if (!pairs.length) {
      body.innerHTML = '<tr><td colspan="7" class="trade-hang-empty">нет данных</td></tr>';
    } else {
      body.innerHTML = pairs.map((p) => {
        if (!p.ok) {
          return `<tr class="trade-hang-err"><td>${p.pair || (p.ord + '/' + p.pref)}</td>`
            + `<td colspan="6">${p.error || 'ошибка'}</td></tr>`;
        }
        const days = Math.max(Number(p.days_hang) || 0, Number(p.days_compression) || 0);
        const daysTitle = `полка ${p.days_hang ?? '—'} · сжатие ${p.days_compression ?? '—'}`;
        const st = p.status || '—';
        return `<tr title="${p.note ? String(p.note).replace(/"/g, '&quot;') : ''}">`
          + `<td class="trade-hang-pair">${p.pair}<div class="trade-hang-name">${p.name || ''}</div></td>`
          + `<td>${fmtHangNum(p.s_now, 2)}</td>`
          + `<td>${fmtHangNum(p.median, 2)}</td>`
          + `<td>${fmtHangNum(p.p10, 2)}</td>`
          + `<td>${fmtHangNum(p.p90, 2)}</td>`
          + `<td title="${daysTitle}">${days || '—'}</td>`
          + `<td><span class="hang-badge ${hangStatusClass(st)}">${st}</span></td>`
          + `</tr>`;
      }).join('');
    }
    if (meta) {
      const age = data.cached ? `кэш ${fmtHangNum(data.age_sec, 0)}с` : 'свежий';
      const win = data.window ? `${data.window.from} → ${data.window.till}` : '';
      meta.textContent = [
        data.definition || 'S = (обычка/преф − 1)×100',
        win,
        age,
      ].filter(Boolean).join(' · ');
    }
    if (rules) {
      rules.textContent = data.basket_rules || '';
    }
  }

  async function refreshHangPanel({ force = false } = {}) {
    const meta = $('tradeHangMeta');
    if (hangLoading) return hangLast;
    hangLoading = true;
    if (meta && !hangLast) meta.textContent = 'загрузка дневных закрытий…';
    try {
      const q = force ? '?force=1' : '';
      const data = await api(`/api/live/pref-hang${q}`);
      renderHangPanel(data || {});
      return data;
    } catch (e) {
      if (meta) {
        meta.textContent = `ошибка: ${(e && e.message) || e}`;
      }
      throw e;
    } finally {
      hangLoading = false;
    }
  }

  function bindHangPanel() {
    $('tradeHangRefresh')?.addEventListener('click', () => {
      const btn = $('tradeHangRefresh');
      if (btn) btn.disabled = true;
      refreshHangPanel({ force: true })
        .catch((e) => alert(e.message || e))
        .finally(() => {
          if (btn) btn.disabled = false;
        });
    });
  }

  function onShow() {
    // Экран зависаний — тихо обновить при входе на вкладку (кэш 10 мин на сервере)
    refreshHangPanel({ force: false }).catch(() => {});
    // Re-load server params when opening the tab, unless user has unsaved edits
    if (!formDirty) formHydrated = false;
    ensureCharts();
    applySpreadChartHeight(loadSpreadChartHeight());
    restoreTradeScrolls();
    resize();
    // Instant LS thresholds; params also arrive with desk.settings
    const cached = loadCachedParamsLocal();
    if (cached && !formDirty) hydrateParams(cached, { force: true });
    // Do NOT wait on /status → desk (was ~10s waterfall via TInvest×2).
    // 1) lite desk: bars/markers/settings without broker (~markets time)
    // 2) full desk: broker/funds + dealer in background (never block lite paint)
    hydrateParamsFromServer().catch(() => {});
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
    document.querySelectorAll('#tradePeriodChips .chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('#tradePeriodChips .chip').forEach((b) => b.classList.remove('active'));
        btn.classList.add('active');
        days = parseInt(btn.dataset.days, 10) || 7;
        forceFitContent = true;
        clearPinState();
        refresh().catch((e) => alert(e.message));
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
    ['tradeLeverage', 'tradeTpSel', 'tradeSpreadLevels'].forEach((id) => {
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
    const manualTrade = async (side) => {
      const warn = lastTradeMode === 'prod'
        ? `Боевой счёт: открыть ${side}?`
        : `Открыть ${side} (ручной вход)?`;
      if (!window.confirm(warn)) return;
      await api('/api/live/trade', { method: 'POST', body: JSON.stringify({ side }) });
      await refresh();
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
        await api('/api/portfolio/close', { method: 'POST' });
        await refresh();
      } catch (e) {
        alert(e.message);
      }
    });
    window.addEventListener('resize', () => {
      if (document.getElementById('app')?.dataset?.view === 'trade') resize();
    });
    bindTradeChartVerticalSplit();
    bindTradeScrolls();
    bindOpenStatsCollapse();
    bindMidTabs();
    bindTradeChartFullscreen();
    bindTradeChartKeyboardNav();
    // hang panel bound in bindHangPanel below
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
    const cached = loadCachedParamsLocal();
    if (cached) hydrateParams(cached, { force: true });
    hydrateParamsFromServer().catch(() => {});
    bindHangPanel();
    // Фоновая загрузка экрана зависаний (не блокирует стол)
    refreshHangPanel({ force: false }).catch(() => {});
  }

  window.MoexTrade = { onShow, onHide, refresh, bind, resize, refreshHangPanel };
  // alias for old name
  window.MoexMarkets = window.MoexTrade;

  document.addEventListener('DOMContentLoaded', bind);
})();
