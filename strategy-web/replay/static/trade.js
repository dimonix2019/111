/** Торговля — chart + open trade panel (desk) */
(function () {
  const LS_CHART_RANGE = 'moexReplay.tradeChartRange';
  const LS_SPREAD_PANE_HEIGHT = 'moexReplay.tradeSpreadPaneHeight';
  const LS_TRADE_PARAMS = 'moexReplay.tradeParams';
  const LS_SIDE_SCROLL = 'moexReplay.tradeSideScrollTop';
  const LS_CHECK_SCROLL = 'moexReplay.tradeCheckScrollTop';
  const LS_DESK_SCROLL = 'moexReplay.tradeDeskScrollTop';
  const LS_OPEN_STATS_HIDDEN = 'moexReplay.tradeOpenStatsHidden';
  const MSK = 'Europe/Moscow';
  /** to ближе к концу данных, чем N баров → считаем «у live» */
  const LIVE_EDGE_BARS = 5;
  const M15_MS = 15 * 60 * 1000;
  const M1_MS = 60 * 1000;
  const BAR_SETTLE_MS = 90 * 1000;
  /** Match zsim / tip1m rolling window for display-only dealer Z */
  const Z_ROLL_LOOKBACK_DAYS = 30;
  const Z_ROLL_MIN_BARS = 48;
  /** Z ближе порога на столько → «подготовка» (не шум далеко от края) */
  const PHASE_NEAR_Z = 0.30;

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
  let zChart = null;
  let zSeries = null;
  /** Z pane: CandlestickSeries (dealer + tip1m). Line only as last-resort fallback. */
  let zSeriesIsLine = false;
  let spreadChart = null;
  let spreadSeries = null;
  let priceLines = [];
  /** Линия вход→сейчас / hover вход→выход на Z (parity Тест). */
  let openHighlightSeries = null;
  let openMarkersPlugin = null;
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
  /** true → fitContent после setData (смена периода 1Д/1Н/…) */
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
  /** Выходные: Spread = дилер 1м; Z = tip1m если есть, иначе display-only Z дилер 1м (не AUTO) */
  let chartDealer1m = false;
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

  function updateChartPaneLabels(dealer1m, { zEmpty = false, zMonitor = false } = {}) {
    const zLab = $('tradeZChartLabel');
    const spLab = $('tradeSpreadChartLabel');
    const thLab = $('tradeThreshLabel');
    if (zLab) {
      if (dealer1m && zMonitor && !zEmpty) {
        zLab.textContent = 'Z-score · дилер 1м (монитор · не AUTO)';
      } else if (dealer1m && zEmpty) {
        zLab.textContent = 'Z-score · нет tip1m в выходные';
      } else {
        zLab.textContent = 'Z-score · tip1m';
      }
      zLab.classList.toggle('pnl-label-dealer', !!dealer1m);
    }
    if (spLab) {
      spLab.textContent = dealer1m
        ? 'Спред % · дилер 1м'
        : 'Спред % · tip1m';
      spLab.classList.toggle('pnl-label-dealer', !!dealer1m);
    }
    if (thLab) {
      thLab.classList.toggle('pnl-label-dealer', !!dealer1m);
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

  function monBadge(running) {
    return running
      ? '<span class="badge-mon-on"><span class="badge-quiet">монитор</span> ON</span>'
      : '<span class="badge-mon-off"><span class="badge-quiet">монитор</span> OFF</span>';
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
      return '<span class="badge-strat-tip1m" title="Mode B: tip-Z касание на 1м · fill сразу, не ждём M15 close · дилер не в Z/AUTO">касание tip1m</span>';
    }
    return '<span class="badge-strat-m15" title="Legacy: вход/выход на закрытии M15">M15 close</span>';
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
    return {
      n: Number(raw.n) || 0,
      min: Number(raw.min),
      max: Number(raw.max),
      mean: Number(raw.mean),
      stdev: Number(raw.stdev),
      bins: raw.bins.map((c) => Number(c) || 0),
      lo: Number(raw.lo),
      hi: Number(raw.hi),
      width: Number(raw.width),
      binCount: Number(raw.binCount) || raw.bins.length,
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
   * для Z — «ближе к среднему» / «в хвосте» … по распределению ≈3г.
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
    const place = classifyTradeMetricPlacement(Number(value), dist);
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
    return null;
  }

  function makeZCandleSeries(chart) {
    return addSeries(chart, 'CandlestickSeries', {
      upColor: '#089981', downColor: '#f23645',
      borderUpColor: '#089981', borderDownColor: '#f23645',
      wickUpColor: '#089981', wickDownColor: '#f23645',
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
      });
    } else if (zChart && zSeries && !openHighlightSeries) {
      openHighlightSeries = addSeries(zChart, 'LineSeries', {
        color: TRADE_HIGHLIGHT_COLOR,
        lineWidth: 2,
        lineStyle: 0,
        lastValueVisible: false,
        priceLineVisible: false,
        crosshairMarkerVisible: false,
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
      spreadSeries = addSeries(spreadChart, 'LineSeries', { color: '#f0b90b', lineWidth: 2 });
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

  function resolveOpenEntryOnBars(bars, open) {
    if (!open || !bars || !bars.length) return null;
    const entryZ = Number(open.entry_z);
    const entrySec = toChartTime(open.entry_time);
    let best = null;
    let bestScore = Infinity;
    for (const b of bars) {
      if (b.z == null) continue;
      const t = toChartTime(b.time, b.timestampMs);
      if (t == null) continue;
      const dz = Number.isFinite(entryZ) ? Math.abs(Number(b.z) - entryZ) : 0;
      const dtMin = entrySec != null ? Math.abs(t - entrySec) / 60 : 0;
      if (entrySec != null && dtMin > 180) continue; // ±3ч от времени входа
      const score = dz * 500 + dtMin;
      if (score < bestScore) {
        bestScore = score;
        best = { time: t, z: Number.isFinite(entryZ) ? entryZ : Number(b.z), tradeDate: b.time };
      }
    }
    if (best) return best;
    // fallback: последний бар не позже входа
    for (let i = bars.length - 1; i >= 0; i--) {
      const b = bars[i];
      const t = toChartTime(b.time, b.timestampMs);
      if (t == null) continue;
      if (entrySec != null && t > entrySec) continue;
      return {
        time: t,
        z: Number.isFinite(entryZ) ? entryZ : Number(b.z),
        tradeDate: b.time,
      };
    }
    return null;
  }

  function deskTradeById(id) {
    if (!id) return null;
    return lastDeskTrades.find((t) => t.id === id) || null;
  }

  function markerChartPrice(m) {
    const trade = deskTradeById(m.tradeId || m.text);
    if (!trade) return 0;
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
    if (!trade || trade.entryTime == null || !Number.isFinite(trade.entryZ)) return [];
    const exitTime = trade.exitTime ?? trade.entryTime;
    const exitZ = trade.exitZ ?? trade.entryZ;
    if (exitTime == null || !Number.isFinite(exitZ)) return [];
    return [
      { time: trade.entryTime, value: trade.entryZ },
      { time: exitTime, value: exitZ },
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
  }

  function applyTradeMarkers(markerData) {
    if (!zSeries) return;
    try {
      if (LightweightCharts.createSeriesMarkers) {
        if (!openMarkersPlugin) {
          openMarkersPlugin = LightweightCharts.createSeriesMarkers(zSeries, markerData);
        } else if (typeof openMarkersPlugin.setMarkers === 'function') {
          openMarkersPlugin.setMarkers(markerData);
        }
      } else if (typeof zSeries.setMarkers === 'function') {
        zSeries.setMarkers(markerData);
      }
    } catch (e) {
      console.warn('trade markers', e);
    }
  }

  function snapSecToBarTimes(sec, barTimes) {
    if (sec == null || !barTimes.length) return sec;
    let best = barTimes[0];
    let bestD = Math.abs(best - sec);
    for (const t of barTimes) {
      const d = Math.abs(t - sec);
      if (d < bestD) {
        best = t;
        bestD = d;
      }
    }
    return best;
  }

  /** Маркеры закрытых + открытой сделки (стиль Теста: стрелка входа, круг выхода). */
  function buildDeskTradeMarkers(closed, open, bars) {
    const barTimes = [];
    for (const b of bars || []) {
      const t = toChartTime(b.time, b.timestampMs);
      if (t != null) barTimes.push(t);
    }
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
      const entrySec = snapSecToBarTimes(toChartTime(t.entry_time), barTimes);
      const exitSec = snapSecToBarTimes(toChartTime(t.exit_time), barTimes);
      const label = `${n}${dirShort(isLong)}`;
      const tradeId = t.id != null ? String(t.id) : `desk-${n}`;
      const entryZ = Number(t.entry_z);
      const exitZ = Number(t.exit_z);
      if (entrySec != null) {
        trades.push({
          id: tradeId,
          entryTime: entrySec,
          entryZ: Number.isFinite(entryZ) ? entryZ : 0,
          exitTime: exitSec != null ? exitSec : entrySec,
          exitZ: Number.isFinite(exitZ) ? exitZ : (Number.isFinite(entryZ) ? entryZ : 0),
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
      const exitZ = mark.z_now != null ? Number(mark.z_now) : (last != null ? Number(last.z) : NaN);
      const tradeId = open.id != null ? String(open.id) : `desk-open-${n}`;
      if (entry) {
        trades.push({
          id: tradeId,
          entryTime: entry.time,
          entryZ: entry.z,
          exitTime: exitTime != null ? exitTime : entry.time,
          exitZ: Number.isFinite(exitZ) ? exitZ : entry.z,
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
    const exitZ = mark.z_now != null ? Number(mark.z_now) : Number(last.z);
    if (!entry || exitTime == null || !Number.isFinite(exitZ)) {
      clearOpenTradeOnChart();
      return;
    }
    const dir = String(open.direction || '').toUpperCase();
    const isLong = dir === 'LONG';
    const fp = [
      open.id, entry.time, entry.z, exitTime, exitZ,
      mark.unrealized_pnl_rub, mark.spread_now, mark.fills_spread,
      (closed || []).length,
    ].join('|');
    lastOpenTradeFp = fp;

    defaultOpenHighlightData = [
      { time: entry.time, value: entry.z },
      { time: exitTime, value: exitZ },
    ].sort((a, b) => a.time - b.time);
    applyHighlightForActiveTrade();

    const dirShort = typeof tradeDirectionShort === 'function'
      ? tradeDirectionShort(isLong ? 'Long' : 'Short')
      : (isLong ? 'L' : 'S');
    const el = $('tradeOpenTradeOverlay');
    if (!el) return;
    const zNow = exitZ;
    const zText = Number.isFinite(zNow)
      ? (zNow >= 0 ? `+${zNow.toFixed(2)}` : zNow.toFixed(2))
      : '—';
    const net = Number(mark.net_approx_rub ?? mark.unrealized_pnl_rub);
    const pnlClass = net > 0 ? 'pnl-pos' : net < 0 ? 'pnl-neg' : '';
    const netText = typeof formatRub === 'function' ? formatRub(net) : `${Math.round(net || 0)} ₽`;
    const entryLabel = typeof compactDateTime === 'function'
      ? compactDateTime(entry.tradeDate || open.entry_time)
      : (entry.tradeDate || open.entry_time || '');
    const entrySpread = mark.fill_spread != null ? mark.fill_spread : open.entry_spread;
    const nowSpread = mark.spread_now;
    const dirLabel = isLong ? 'LONG спрэд' : 'SHORT спрэд';
    const duration = typeof formatSimTradeDuration === 'function'
      ? formatSimTradeDuration(entry.tradeDate || open.entry_time, last.time || mark.trade_date)
      : '';
    const tradeNo = (closed || []).length + 1;
    el.classList.remove('hidden');
    el.innerHTML = [
      `<div class="ot-z">Z=${zText}</div>`,
      `<div class="ot-trade">${tradeNo} ${dirShort} ${entryLabel} Z₀${Number(entry.z).toFixed(2)} `
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

  /** Build one Z OHLC candle: prefer server z_open/high/low when they vary; else prevZ→currZ. */
  function zCandleFromBar(b, zClose, prevZ) {
    const z = Number(zClose);
    const zo = b && b.z_open != null ? Number(b.z_open) : NaN;
    const zh = b && b.z_high != null ? Number(b.z_high) : NaN;
    const zl = b && b.z_low != null ? Number(b.z_low) : NaN;
    const serverOk = Number.isFinite(zo) && Number.isFinite(zh) && Number.isFinite(zl);
    const serverVaries = serverOk && (
      Math.abs(zo - z) > 1e-9
      || Math.abs(zh - zl) > 1e-9
    );
    if (serverVaries) {
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
   * Z candles from tip1m bars with z (~1m step) — never M15, never TATN mid.
   * Returns [] if series is empty or looks like M15 (≥10m median step).
   * Prefer server z_open/high/low; else open=prevZ, close=currZ (visible bodies).
   */
  function buildTip1mZCandles(bars) {
    const pts = [];
    let prevZ = null;
    const seen = new Set();
    const times = [];
    for (const b of bars || []) {
      const t = toChartTime(b.time, b.timestampMs);
      if (t == null || b.z == null || Number.isNaN(Number(b.z)) || seen.has(t)) continue;
      // Dealer mid rows without display Z — skipped. Never use TATN price as Z.
      if (b.interval === '1m' && b.source && String(b.source).includes('dealer') && b.z == null) continue;
      seen.add(t);
      times.push(t);
      const z = Number(b.z);
      const candle = zCandleFromBar(b, z, prevZ);
      pts.push({ time: t, ...candle });
      prevZ = z;
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
   * Dealer 1m monitor: Z candles + Spread line, identical timestamps (1:1).
   * Z never for AUTO / for_z. Prefer z_open/high/low; else prevZ→currZ bodies.
   */
  function buildDealer1mChartSeries(bars) {
    const zPts = [];
    const spreadPts = [];
    const seen = new Set();
    let prevZ = null;
    for (const b of bars || []) {
      if (!b) continue;
      const t = toChartTime(b.time, b.timestampMs);
      if (t == null || seen.has(t)) continue;
      const sp = b.spread != null ? Number(b.spread) : NaN;
      const z = b.z != null ? Number(b.z) : NaN;
      if (!Number.isFinite(sp) || !Number.isFinite(z)) continue;
      seen.add(t);
      spreadPts.push({ time: t, value: sp });
      const candle = zCandleFromBar(b, z, prevZ);
      zPts.push({ time: t, ...candle });
      prevZ = z;
    }
    return { zPts, spreadPts };
  }

  function renderCharts(bars, entry, exitZ, openTrade = null, closedTrades = [], {
    dealer1m = false,
    weekendMonitor = false,
    zBars = null,
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

    let zData = [];
    let spreadPts = [];

    // TOP = Z-score candles, BOTTOM = spread % — identical UTC keys (1:1).
    // monMode: dealer 1m monitor Z; else tip1m/ISS bars with both z+spread.
    // Never mix M15 Z with 1m spread (that broke crosshair / tick density).
    {
      const built = buildDealer1mChartSeries(bars);
      zData = built.zPts;
      spreadPts = built.spreadPts;
    }

    zPriceByTime = new Map(zData.map((c) => [c.time, c.close != null ? c.close : c.value]));
    spPriceByTime = new Map(spreadPts.map((p) => [p.time, p.value]));

    const zEmpty = monMode && zData.length === 0;
    const zMonitor = monMode && !zEmpty && (bars || []).some((b) => b
      && b.z != null
      && (b.z_kind === 'dealer_monitor'
        || (b.source && String(b.source).includes('dealer'))));
    updateChartPaneLabels(monMode, { zEmpty, zMonitor });
    setZEmptyMessage(zEmpty
      ? 'Нет tip1m Z и нет дилерского display-Z · ISS M15 не рисуем · спред внизу = дилер 1м'
      : '');

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
      sample: zData.length ? [zData[0], zData[zData.length - 1]] : null,
      sync: { timesEqual, zN: zData.length, spN: spreadPts.length },
    });

    const fp = barsFingerprint(bars) + '|zc' + zData.length + '|sp' + spreadPts.length
      + '|c' + (closedTrades || []).length + '|o' + (openTrade ? openTrade.id : '')
      + (monMode ? '|d1m' : '')
      + (zSeriesIsLine ? '|zl' : '|zcndl')
      + (zMonitor ? '|zm' : '')
      + (zEmpty ? '|ze' : '')
      + (Array.isArray(zBars) ? `|iss${zBars.length}` : '');
    const dataChanged = fp !== lastBarsFingerprint;
    lastBarsFingerprint = fp;

    // Типичный poll без новых баров: не трогаем timescale вообще
    if (!dataChanged && !forceFitContent && pinnedRange) {
      setThresholdLines(entry, exitZ);
      if (monMode) {
        clearAllTradeMarkers();
      } else {
        updateOpenTradeOnChart(openTrade, bars, closedTrades);
      }
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
      setThresholdLines(entry, exitZ);
      // Weekday markers on dealer-only spread window look like M15 — hide in monitor mode.
      if (monMode) {
        clearAllTradeMarkers();
      } else {
        updateOpenTradeOnChart(openTrade, bars, closedTrades);
      }
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

  function renderOpen(open, { barsMode = '' } = {}) {
    const box = $('tradeOpenBox');
    if (!open) {
      box.innerHTML = '<div class="trade-open-empty">Нет открытой позиции</div>';
      return;
    }
    const m = open.mark || {};
    const pnlCls = (m.unrealized_pnl_rub || 0) >= 0 ? 'pnl-pos' : 'pnl-neg';
    const riskCls = m.risk_red ? 'risk-red' : (m.risk_level === 'Elevated' ? 'risk-warn' : 'risk-ok');
    const spreadEntry = m.fill_spread != null ? m.fill_spread : open.entry_spread;
    const spreadLabel = m.pnl_source === 'broker_fills' ? 'Спред (fill→сейч)' : 'Спред';
    const pnlNote = m.pnl_source === 'broker_fills'
      ? 'по ценам Тинькофф'
      : (String(barsMode) === 'dealer_1m' ? 'по спреду дилер 1м' : 'по спреду ISS');
    box.innerHTML =
      `<div class="trade-open-dir">${open.direction} · ${open.quantity_lots}+${open.quantity_lots} лот · ${open.source || ''}</div>` +
      `<div class="trade-open-grid">` +
      `<span>Вход</span><b>${open.entry_time || '—'}</b>` +
      `<span>Z вх → сейч</span><b>${fmt(open.entry_z)} → ${fmt(m.z_now)}</b>` +
      `<span>${spreadLabel}</span><b>${fmt(spreadEntry)}% → ${fmt(m.spread_now)}%</b>` +
      (m.entry_slip_pts != null
        ? `<span>Slip вх</span><b>${fmt(m.entry_slip_pts, 2)} п.п.</b>`
        : '') +
      (m.fill_tatn != null
        ? `<span>Fill TATN/P</span><b>${fmt(m.fill_tatn, 2)} / ${fmt(m.fill_tatnp, 2)}</b>`
        : '') +
      `<span>Notional</span><b>${fmt(m.notional_rub, 0)} ₽</b>` +
      `<span>PnL ≈</span><b class="${pnlCls}">${fmtRub(m.unrealized_pnl_rub)}</b>` +
      `<span>Нетто ≈</span><b class="${pnlCls}">${fmtRub(m.net_approx_rub)}</b>` +
      `<span>Овн</span><b>${fmtRub(m.overnight_rub)} · ${m.overnight_days || 0}д</b>` +
      `<span>Hold</span><b>${m.hold_hours != null ? fmt(m.hold_hours, 1) + ' ч' : '—'}</b>` +
      `</div>` +
      `<div class="trade-open-pnl-src meta">${pnlNote}</div>` +
      `<div class="trade-risk ${riskCls}">Риск ${m.risk_level || '—'} · score ${m.risk_score ?? '—'}` +
      (m.risk_flags && m.risk_flags.length ? ` · ${m.risk_flags.join(', ')}` : '') +
      `</div>` +
      `<div class="trade-open-stats-mini meta" id="tradeOpenStatsMini">ориентиры — см. блок под графиками</div>`;
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
      totalEl.textContent = pending ? '…' : '—';
      cashEl.textContent = pending ? 'брокер…' : 'нет токена — вкладка «Счёт»';
      return;
    }
    if (broker.error) {
      box.classList.add('is-error');
      totalEl.textContent = '—';
      cashEl.textContent = modeLabel(broker.mode);
      if (brokerEl) {
        brokerEl.hidden = false;
        brokerEl.textContent = `Брокер: ${broker.error}`;
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

  function fmtRubPlain(n, digits = 0) {
    if (n == null || !Number.isFinite(Number(n))) return '—';
    return `${fmt(Number(n), digits)} ₽`;
  }

  /** Last good close forecast — lite polls must not wipe full-desk number. */
  let lastCloseForecast = null;

  function renderCloseForecast(fc, { hasOpen = false } = {}) {
    const root = $('tradeCloseForecast');
    const main = $('tradeCloseForecastMain');
    const sub = $('tradeCloseForecastSub');
    if (!root || !main || !sub) return;

    if (!hasOpen) {
      lastCloseForecast = null;
      root.hidden = true;
      main.textContent = 'Прогноз после закрытия ≈ —';
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
    if (total != null && Number.isFinite(total)) {
      main.textContent = `Прогноз после закрытия ≈ ${fmt(total, 0)} ₽`;
    } else {
      main.textContent = 'Прогноз после закрытия ≈ —';
    }

    const bits = [];
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
    return !!(ae && (ae.id === 'tradeEntryZ' || ae.id === 'tradeExitZ'
      || ae.id === 'tradeLeverage' || ae.id === 'tradeAutoExec'
      || ae.id === 'tradeTpSel'
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
    const entry = parseFloat(String($('tradeEntryZ')?.value || '').replace(',', '.'));
    const exitZ = parseFloat(String($('tradeExitZ')?.value || '').replace(',', '.'));
    const leverage = parseFloat(String($('tradeLeverage')?.value || '').replace(',', '.'));
    const tpRaw = parseFloat(String($('tradeTpSel')?.value || '0').replace(',', '.'));
    const tpAllowed = [0, 1, 2, 3];
    const takeProfit = tpAllowed.includes(tpRaw) ? tpRaw : 0;
    return {
      entry_z: Number.isFinite(entry) ? entry : null,
      exit_z: Number.isFinite(exitZ) ? exitZ : null,
      leverage: Number.isFinite(leverage) ? leverage : null,
      take_profit_pct: takeProfit,
      auto_execute: !!$('tradeAutoExec')?.checked,
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
        entry_deposit_rub: settings.entry_deposit_rub != null ? settings.entry_deposit_rub : 10000,
      }));
    } catch (_) { /* ignore quota */ }
  }

  function loadCachedParamsLocal() {
    try {
      const raw = localStorage.getItem(LS_TRADE_PARAMS);
      if (!raw) return null;
      const o = JSON.parse(raw);
      if (!o || o.entry_z == null || o.exit_z == null) return null;
      if (o.entry_deposit_rub == null) o.entry_deposit_rub = 10000;
      if (o.take_profit_pct == null) o.take_profit_pct = 0;
      return o;
    } catch (_) {
      return null;
    }
  }

  function applyParamsToForm(settings) {
    if (!settings) return;
    if (settings.entry_z != null && $('tradeEntryZ')) {
      $('tradeEntryZ').value = String(settings.entry_z);
    }
    if (settings.exit_z != null && $('tradeExitZ')) {
      $('tradeExitZ').value = String(settings.exit_z);
    }
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
  }

  /** Server (or LS backup) → inputs + chart lines. Skips if user is mid-edit. */
  function hydrateParams(settings, { force = false } = {}) {
    if (!settings) return false;
    if (!force && (formDirty || paramsFocused())) return false;
    applyParamsToForm(settings);
    cacheParamsLocal(settings);
    ensureCharts();
    applyThresholdVisuals(settings.entry_z, settings.exit_z);
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
      if (settings.entry_z != null && settings.exit_z != null) {
        hydrateParams(settings, { force: true });
        return settings;
      }
    } catch (_) { /* fall through to LS */ }
    const cached = loadCachedParamsLocal();
    if (cached) hydrateParams(cached, { force: true });
    return cached;
  }

  function applyThresholdVisuals(entry, exitZ, { dealer1m = false, zEmpty = false, zMonitor = false } = {}) {
    const e = Number(entry);
    const x = Number(exitZ);
    const entryN = Number.isFinite(e) && e > 0 ? e : 1.3;
    const exitN = Number.isFinite(x) && x > 0 ? x : 1.2;
    const label = $('tradeThreshLabel');
    if (label) {
      if (dealer1m && zMonitor && !zEmpty) {
        label.textContent = `пороги ±${fmt(entryN, 2)} / ±${fmt(exitN, 2)} · гиды · дилер Z монитор (не AUTO)`;
        label.classList.add('pnl-label-dealer');
      } else if (dealer1m && zEmpty) {
        label.textContent = `пороги ±${fmt(entryN, 2)} / ±${fmt(exitN, 2)} · нет tip1m · ISS M15 не рисуем`;
        label.classList.add('pnl-label-dealer');
      } else {
        label.textContent = `пороги ±${fmt(entryN, 2)} / ±${fmt(exitN, 2)}`;
        label.classList.remove('pnl-label-dealer');
      }
    }
    setThresholdLines(entryN, exitN);
    return { entry: entryN, exitZ: exitN };
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
    const inSession = !weekend && mins >= 7 * 60 && mins < 23 * 60 + 50;
    return { y, mo, d, h, mi, weekend, inSession, label: `${String(h).padStart(2, '0')}:${String(mi).padStart(2, '0')}` };
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
   * Фазы: idle → prep (почти всё OK, Z у порога) → signal (edge есть) → ready (AUTO может взять).
   */
  function buildTradePhase({
    pos, curZ, entryN, exitN, signal,
    monOn, autoOn, settled, consecutive, sessionOk, brokerOk, ghost,
    needLong, needShort, needExitLong, needExitShort, settleLeftSec,
    tip1mMode,
  }) {
    const hardOk = monOn && sessionOk && consecutive && brokerOk && !ghost;
    const softWait = [];
    if (!autoOn) softWait.push('авто');
    if (!settled) {
      softWait.push(tip1mMode
        ? 'закрытие минутки tip'
        : (settleLeftSec > 0 ? `закрытие бара (~${settleLeftSec}с)` : 'закрытие бара'));
    }

    const waitingText = (extra) => {
      const extras = Array.isArray(extra) ? extra : (extra ? [extra] : []);
      const all = [...softWait, ...extras].filter(Boolean);
      return all.length ? `ждём: ${all.join(', ')}` : '';
    };

    if (pos === 'FLAT') {
      const nearLong = needLong != null && needLong <= PHASE_NEAR_Z;
      const nearShort = needShort != null && needShort <= PHASE_NEAR_Z;
      const atLevel = curZ != null && (curZ <= -entryN || curZ >= entryN);
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
        : (nearLong || (curZ != null && curZ <= -entryN)
          ? 'Long'
          : (nearShort || (curZ != null && curZ >= entryN) ? 'Short' : ''));

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

    // LONG / SHORT — подготовка к закрытию
    const needExit = pos === 'LONG' ? needExitLong : needExitShort;
    const atExit = pos === 'LONG'
      ? (curZ != null && curZ >= -exitN)
      : (curZ != null && curZ <= exitN);
    const nearExit = needExit != null && needExit <= PHASE_NEAR_Z;
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
    const entry = Number(settings.entry_z);
    const exitZ = Number(settings.exit_z);
    const entryN = Number.isFinite(entry) && entry > 0 ? entry : 1.3;
    const exitN = Number.isFinite(exitZ) && exitZ > 0 ? exitZ : 1.2;
    const autoOn = !!settings.auto_execute;
    const monOn = !!mon.running;
    const nowMs = Date.now();
    const msk = nowMskParts();

    const last = bars.length ? bars[bars.length - 1] : null;
    const prev = bars.length >= 2 ? bars[bars.length - 2] : null;
    const lastMs = last ? Number(last.timestampMs || 0) : 0;
    const prevMs = prev ? Number(prev.timestampMs || 0) : 0;
    // Prod tip1m: consecutive 1m tips; chart may still show M15 — treat either step as OK for prep.
    const consecutive = prevMs > 0 && (lastMs - prevMs === M1_MS || lastMs - prevMs === M15_MS);
    const tip1mMode = String(settings.signal_mode || 'tip1m') === 'tip1m';
    const hasNext = false;
    const settled = tip1mMode
      ? (lastMs > 0 && (Date.now() >= lastMs + M1_MS || consecutive))
      : (lastMs > 0 && (hasNext || nowMs >= lastMs + M15_MS + BAR_SETTLE_MS));
    const settleLeftSec = (!tip1mMode && lastMs > 0)
      ? Math.max(0, Math.ceil((lastMs + M15_MS + BAR_SETTLE_MS - nowMs) / 1000))
      : 0;
    const curZ = barZ(last);
    const prevZ = barZ(prev);
    const barTd = last?.time || last?.tradeDate || last?.trade_date || data.summary?.trade_date || '';
    const barInSession = isTqbrSessionBar(barTd);
    const sessionOk = msk.inSession && barInSession;
    const signal = consecutive
      ? determineZSignalJs(prevZ, curZ, pos, entryN, exitN)
      : 'NONE';
    const tpPct = Number(settings.take_profit_pct);
    const tpOn = Number.isFinite(tpPct) && tpPct > 0;

    const brokerOk = !!(broker && !broker.error);
    const ghost = !!(!open && bs && !bs.error && bs.direction);
    const dealer = data.dealer;
    const dealerManualOk = !!(dealer && (dealer.manual_ok || dealer.quotes_ok
      || (dealer.ok && dealer.tatn != null && dealer.tatnp != null)));

    const general = [];
    general.push(checkItem(monOn ? 'ok' : 'block', monOn ? 'Монитор ON' : 'Монитор OFF — старт на вкладке Счёт'));
    general.push(checkItem(autoOn ? 'ok' : 'wait', autoOn ? 'Авто ON (ордера)' : 'Авто OFF — сигналы без ордеров'));
    general.push(checkItem('ok', tip1mMode
      ? 'Стратегия: касание tip1m (Mode B)'
      : 'Стратегия: M15 close (legacy)'));
    general.push(checkItem('ok',
      `Пороги Z: вход ±${fmt(entryN, 2)} · выход ±${fmt(exitN, 2)}`));
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
      const hasMonZ = (bars || []).some((b) => b && b.z != null
        && (b.z_kind === 'dealer_monitor' || (b.source && String(b.source).includes('dealer'))));
      general.push(checkItem(hasMonZ ? 'ok' : 'wait',
        hasMonZ
          ? 'Z сверху: дилер 1м display-Z (монитор · не AUTO) · ISS M15 / TATN mid не рисуем'
          : 'Z сверху: ждём display-Z по дилеру · ISS M15 / TATN mid не рисуем'));
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
          settled ? 'Минутка tip закрыта (касание без ожидания M15)' : 'Ждём закрытие минутки tip'));
        general.push(checkItem(consecutive ? 'ok' : 'wait',
          consecutive
            ? 'Ряд tip1m без дыры'
            : (bars.length >= 2 ? 'Дыра в UI-барах (сигнал считает сервер по 1м tip)' : 'Мало баров')));
      } else {
        general.push(checkItem(settled ? 'ok' : 'wait',
          settled
            ? `Бар закрыт (+90с)`
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
    const needLong = curZ == null ? null : Math.max(0, curZ + entryN);
    const needShort = curZ == null ? null : Math.max(0, entryN - curZ);
    const needExitLong = curZ == null ? null : Math.max(0, (-exitN) - curZ);
    const needExitShort = curZ == null ? null : Math.max(0, curZ - exitN);

    const phase = buildTradePhase({
      pos, curZ, entryN, exitN, signal,
      monOn, autoOn, settled, consecutive, sessionOk, brokerOk, ghost,
      needLong, needShort, needExitLong, needExitShort, settleLeftSec,
      tip1mMode,
    });

    const settleBlocker = tip1mMode ? 'закрытие минутки tip' : 'закрытие бара (+90с)';
    const edgeOpenHint = tip1mMode
      ? 'Нужен edge: касание порога входа на tip1m'
      : 'Нужен edge: пересечение порога входа на закрытом баре';
    const edgeCloseHint = tip1mMode
      ? 'Нужен edge: касание порога выхода на tip1m'
      : 'Нужен edge: пересечение порога выхода на закрытом баре';

    if (pos === 'FLAT') {
      openItems.push(checkItem('ok', 'Позиция FLAT — можно открыть'));
      if (ghost) {
        openItems.push(checkItem('block', 'Сначала сверить призрак с брокером'));
      }
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
      if (pos === 'LONG') {
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
    const openDir = (() => {
      const d = `${phase.detail || ''} ${phase.title || ''} ${signal || ''}`;
      if (/ENTER_LONG|\bLong\b/i.test(d)) return 'long';
      if (/ENTER_SHORT|\bShort\b/i.test(d)) return 'short';
      return null;
    })();
    if (phase.kind === 'ready') {
      const prog = phase.side === 'open'
        ? openEntryProgressText(openDir, needLong, needShort, entryN)
        : '';
      hint = prog ? `${phase.title} · ${prog} · Z ${zTxt}` : `${phase.title} · Z ${zTxt}`;
      hintCls += ' is-ready';
      hintIco = phase.side === 'close' ? '↘' : '↗';
    } else if (phase.kind === 'signal') {
      const prog = phase.side === 'open'
        ? openEntryProgressText(openDir, needLong, needShort, entryN)
        : '';
      hint = prog ? `${phase.title} · ${prog} · Z ${zTxt}` : `${phase.title} · Z ${zTxt}`;
      hintCls += ' is-block';
      hintIco = phase.side === 'close' ? '↘' : '↗';
    } else if (phase.kind === 'prep') {
      const prog = phase.side === 'open'
        ? openEntryProgressText(openDir, needLong, needShort, entryN)
        : '';
      hint = prog ? `${phase.title} · ${prog} · Z ${zTxt}` : `${phase.title} · Z ${zTxt}`;
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
          ? `до Long ещё −${fmt(needLong, 2)}${needPctSuffix(needLong, entryN)} по Z`
          : `до Short ещё +${fmt(needShort, 2)}${needPctSuffix(needShort, entryN)} по Z`);
      hint = nearer ? `Ожидание входа · ${nearer} · Z ${zTxt}` : `Ожидание входа · Z ${zTxt}`;
    } else if (pos === 'LONG') {
      hint = needExitLong != null && Number.isFinite(needExitLong)
        ? `В позиции Long · до выхода ещё +${fmt(needExitLong, 2)}${needPctSuffix(needExitLong, exitN)} по Z · Z ${zTxt}`
        : `В позиции Long · Z ${zTxt}`;
    } else {
      hint = needExitShort != null && Number.isFinite(needExitShort)
        ? `В позиции Short · до выхода ещё −${fmt(needExitShort, 2)}${needPctSuffix(needExitShort, exitN)} по Z · Z ${zTxt}`
        : `В позиции Short · Z ${zTxt}`;
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
    const s = data.summary || {};
    const settings = data.settings || {};
    const mon = data.monitor || {};
    const pos = data.position || 'FLAT';
    lastTradeMode = String(settings.mode || lastTradeMode || '').toLowerCase();
    const monHtml = monBadge(!!mon.running);
    const autoHtml = autoBadge(!!settings.auto_execute);
    const modeHtml = modeBadge(settings.mode);
    const stratHtml = strategyBadge(settings.signal_mode);

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
    }

    const bars = data.bars || [];
    const barsForDist = (dealer1mMode && (data.bars_iss || []).length)
      ? data.bars_iss
      : bars;
    rebuildBarMetricDists(barsForDist, data.metric_dists);
    const regimeHtml = regimeBadge(bars);
    const dealer = data.dealer;
    const useDealerPx = !!(dealer && (dealer.ok || dealer.quotes_ok) && dealer.tatn != null && dealer.tatnp != null);
    const zDisp = fmt(s.z);
    const spVal = useDealerPx ? dealer.spread : s.spread;
    const spDisp = `${fmt(spVal)}%`;
    const tatnDisp = useDealerPx ? dealer.tatn : s.tatn;
    const tatnpDisp = useDealerPx ? dealer.tatnp : s.tatnp;
    const dealerHtml = dealerBadge(dealer);
    const monZUi = String(s.z_kind || '') === 'dealer_monitor'
      || (bars || []).some((b) => b && b.z_kind === 'dealer_monitor');

    // Данные / связь — только в шапке
    const distHint = metricDistMeta.degraded
      ? ` · dist:окно`
      : ` · dist:3г n=${metricDistMeta.n || '—'}`;
    $('tradeMeta').innerHTML =
      `${s.window_count || 0} баров · ${escapeHtml(s.source || '—')} · ${onlineBadge(!!s.online)}`
      + (dealerHtml ? ` · ${dealerHtml}` : '')
      + (dealer1mMode || data.weekend_monitor
        ? (monZUi
          ? ' · <span class="badge-quiet">спред 1м дилер · Z монитор дилер</span>'
          : ' · <span class="badge-quiet">спред 1м дилер · Z без M15</span>')
        : '')
      + distHint;

    // Исполнение — один раз в статус-баре (без рынка и без баланса)
    const partialBanner = applyPartialBanner(data);
    $('tradeStatus').innerHTML =
      `${monHtml} · ${autoHtml} · ${modeHtml} · ${stratHtml}` +
      (dealerHtml ? ` · ${dealerHtml}` : '') +
      (partialBanner ? ` · <span class="badge-quiet">${escapeHtml(partialBanner)}</span>` : '') +
      (s.trade_date ? ` · ${tickBadge(s.trade_date)}` : '');

    // Рынок — у графиков (выходные: Z = дилер monitor display; цены/спред — дилер)
    const stripSpLabel = useDealerPx
      ? (dealer1mMode ? 'Спред · дилер 1м' : 'Спред · дилер')
      : 'Спред';
    const stripZLabel = monZUi ? 'Z · дилер монитор' : 'Z';
    $('tradeStrip').innerHTML = [
      metricStripBlock(stripZLabel, 'z', s.z, zDisp),
      metricStripBlock(stripSpLabel, 'spread', spVal, spDisp),
      regimeHtml ? `<span><b>Режим</b> ${regimeHtml}</span>` : '',
      `<span><b>TATN</b> ${fmt(tatnDisp)}${useDealerPx ? ' <span class="badge-quiet">дилер</span>' : ''}</span>`,
      `<span><b>TATNP</b> ${fmt(tatnpDisp)}${useDealerPx ? ' <span class="badge-quiet">дилер</span>' : ''}</span>`,
      useDealerPx && dealer.tatn_bid != null
        ? `<span class="trade-dealer-ba"><b>bid/ask</b> ${fmt(dealer.tatn_bid)}/${fmt(dealer.tatn_ask)} · ${fmt(dealer.tatnp_bid)}/${fmt(dealer.tatnp_ask)}</span>`
        : '',
      (dealer && dealer.bars_count != null)
        ? `<span class="badge-quiet">${dealer.bars_count}×1м</span>`
        : '',
    ].filter(Boolean).join(' ');

    // Hydrate once from server; never while user is editing (poll / late desk).
    const shouldHydrate = (hydrateForm || !formHydrated) && !formDirty && !paramsFocused();
    if (shouldHydrate && settings.entry_z != null) {
      hydrateParams(settings);
    } else if (!formHydrated && !formDirty) {
      const cached = loadCachedParamsLocal();
      if (cached) hydrateParams(cached);
    }

    const entry = formHydrated && (formDirty || paramsFocused())
      ? (readFormParams().entry_z ?? settings.entry_z)
      : settings.entry_z;
    const exitZ = formHydrated && (formDirty || paramsFocused())
      ? (readFormParams().exit_z ?? settings.exit_z)
      : settings.exit_z;
    const hasDealerMonZ = (bars || []).some((b) => b && b.z != null
      && (b.z_kind === 'dealer_monitor'
        || (b.source && String(b.source).includes('dealer'))));
    const hasTip1mZ = (bars || []).some((b) => b && b.z != null
      && !(b.source && String(b.source).includes('dealer')));
    const zEmptyWeekend = weekendMonitor && !hasTip1mZ && !hasDealerMonZ;
    const th = applyThresholdVisuals(entry, exitZ, {
      dealer1m: dealer1mMode || weekendMonitor,
      zEmpty: zEmptyWeekend,
      zMonitor: weekendMonitor && hasDealerMonZ && !hasTip1mZ,
    });

    renderOpen(data.open, { barsMode: data.bars_mode || '' });
    syncTradeActionButtons(data);
    renderOpenStats(data.open_stats, data.open);
    renderFunds(data.broker, { pending: !!data.lite && !data.broker });
    renderCloseForecast(data.close_forecast, { hasOpen: !!data.open });
    renderChecklist(data);

    renderCharts(chartBars, th.entry, th.exitZ, data.open || null, data.closed || [], {
      dealer1m: dealer1mMode || barsLookDealer,
      weekendMonitor,
      zBars: data.bars_iss || null,
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
    if (data && (data.partial || data.dealer_warming || (data.dealer && data.dealer.warming))) {
      parts.push('кэш / частичные данные');
    }
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
    if (params.entry_z == null || params.exit_z == null || params.leverage == null) {
      setParamsStatus('Проверьте числа', 'err');
      throw new Error('Некорректные пороги или плечо');
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

  function onShow() {
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

  function onHide() { stopPoll(); }

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
    ['tradeEntryZ', 'tradeExitZ', 'tradeLeverage', 'tradeTpSel'].forEach((id) => {
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
  }

  window.MoexTrade = { onShow, onHide, refresh, bind, resize };
  // alias for old name
  window.MoexMarkets = window.MoexTrade;

  document.addEventListener('DOMContentLoaded', bind);
})();
