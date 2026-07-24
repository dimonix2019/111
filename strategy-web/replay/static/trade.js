/** Торговля — chart + open trade panel (desk) */
(function () {
  const LS_CHART_RANGE = 'moexReplay.tradeChartRange';
  const LS_SPREAD_PANE_HEIGHT = 'moexReplay.tradeSpreadPaneHeight';
  const LS_TRADE_PARAMS = 'moexReplay.tradeParams';
  const LS_SIDE_SCROLL = 'moexReplay.tradeSideScrollTop';
  const LS_CHECK_SCROLL = 'moexReplay.tradeCheckScrollTop';
  const LS_DESK_SCROLL = 'moexReplay.tradeDeskScrollTop';
  const MSK = 'Europe/Moscow';
  /** to ближе к концу данных, чем N баров → считаем «у live» */
  const LIVE_EDGE_BARS = 5;
  const M15_MS = 15 * 60 * 1000;
  const BAR_SETTLE_MS = 45 * 1000;
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
  /** Inputs filled from server once; polls must not clobber while typing */
  let formHydrated = false;
  /** User edited params since last successful hydrate/save */
  let formDirty = false;
  let saveStatusTimer = 0;
  /** Ignore late desk responses that would re-hydrate over newer edits */
  let deskFetchSeq = 0;
  let zChart = null;
  let zSeries = null;
  let spreadChart = null;
  let spreadSeries = null;
  let priceLines = [];
  /** Линия вход→сейчас / hover вход→выход на Z (parity Тест). */
  let openHighlightSeries = null;
  let openMarkersPlugin = null;
  let lastOpenTradeFp = '';
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

  const $ = (id) => document.getElementById(id);

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

  function applyVisibleRange(range) {
    if (!range || !zChart) return;
    suppressRangeEvents = true;
    try {
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

  /** Повторно навязать pin после асинхронного сброса timescale от setData/resize */
  function reassertPinnedRange() {
    if (!pinnedRange || !zChart) return;
    suppressRangeEvents = true;
    try {
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

      // Реальный жест пользователя — единственный путь смены pin/follow
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

  function fitAndRemember() {
    try {
      zChart?.timeScale().fitContent();
      const range = zChart?.timeScale().getVisibleLogicalRange();
      if (range) {
        userPinnedAwayFromLive = false;
        setPinnedRange(range, { fromUser: false });
        applyVisibleRange(range);
      } else {
        spreadChart?.timeScale().fitContent();
      }
    } catch (_) {
      try { spreadChart?.timeScale().fitContent(); } catch (__) {}
    }
  }

  /**
   * После обновления данных:
   * - userPinnedAwayFromLive → ВСЕГДА точный restore, never follow
   * - иначе у live-края → сдвинуть окно к новому концу
   * - иначе → точный restore
   */
  function restoreOrFitVisibleRange(barCount) {
    const n = Math.max(0, barCount | 0);
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
      // Ещё раз после paint — setData часто сбрасывает range асинхронно
      requestAnimationFrame(() => {
        requestAnimationFrame(reassertPinnedRange);
      });
      return;
    }

    if (isNearLiveEdge(pinnedRange, dataEnd) && n > 0) {
      const span = Math.max(1, pinnedRange.to - pinnedRange.from);
      const next = { from: dataEnd - span, to: dataEnd };
      setPinnedRange(next, { fromUser: false });
      applyVisibleRange(next);
      return;
    }

    // Не у live, но флаг ещё не стоял (старый LS) — тоже держим окно
    userPinnedAwayFromLive = true;
    applyVisibleRange(pinnedRange);
    persistViewport();
    requestAnimationFrame(() => {
      requestAnimationFrame(reassertPinnedRange);
    });
  }

  async function api(path, opts) {
    const res = await fetch(path, {
      headers: { 'Content-Type': 'application/json', ...(opts && opts.headers) },
      ...opts,
    });
    const text = await res.text();
    let data;
    try { data = text ? JSON.parse(text) : {}; } catch { data = { detail: text }; }
    if (!res.ok) {
      const msg = data.detail || data.message || text || res.statusText;
      throw new Error(typeof msg === 'string' ? msg : JSON.stringify(msg));
    }
    return data;
  }

  function fmt(n, d = 2) {
    if (n == null || Number.isNaN(Number(n))) return '—';
    return Number(n).toLocaleString('ru-RU', { maximumFractionDigits: d });
  }

  /** % до порога входа: need/entry×100 (100% у Z≈0, 0% у порога). */
  function entryNeedPctSuffix(need, entryTh) {
    if (need == null || !(entryTh > 0)) return '';
    return ` (${Math.round((need / entryTh) * 100)}%)`;
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

  function rebuildBarMetricDists(bars) {
    lastDeskBars = Array.isArray(bars) ? bars : [];
    if (typeof computeNumericDistribution !== 'function') {
      barMetricDists = { z: null, spread: null };
      return;
    }
    const zs = [];
    const sps = [];
    for (const b of lastDeskBars) {
      const z = b.z != null ? Number(b.z) : (b.zScore != null ? Number(b.zScore) : NaN);
      const sp = b.spread != null ? Number(b.spread)
        : (b.spreadPercent != null ? Number(b.spreadPercent) : NaN);
      if (Number.isFinite(z)) zs.push(z);
      if (Number.isFinite(sp)) sps.push(sp);
    }
    barMetricDists = {
      z: computeNumericDistribution(zs),
      spread: computeNumericDistribution(sps),
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

  /** Подпись «ближе к среднему» / «в хвосте» … по распределению окна. */
  function metricPlaceCaption(metric, value) {
    if (value == null || !Number.isFinite(Number(value))) return '';
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

  function ensureCharts() {
    if (typeof LightweightCharts === 'undefined') return;
    const zEl = $('tradeZChart');
    const sEl = $('tradeSpreadChart');
    if (!zEl || !sEl) return;
    if (!zChart) {
      zChart = LightweightCharts.createChart(zEl, {
        layout: { background: { color: '#161a25' }, textColor: '#d1d4dc' },
        grid: { vertLines: { color: '#2a2e39' }, horzLines: { color: '#2a2e39' } },
        rightPriceScale: { borderColor: '#2a2e39' },
        width: zEl.clientWidth,
        height: zEl.clientHeight || 300,
        ...chartTimeOpts(),
        ...CHART_SCROLL_SCALE,
      });
      zSeries = addSeries(zChart, 'CandlestickSeries', {
        upColor: '#089981', downColor: '#f23645',
        borderUpColor: '#089981', borderDownColor: '#f23645',
        wickUpColor: '#089981', wickDownColor: '#f23645',
      }) || addSeries(zChart, 'LineSeries', { color: '#2962ff', lineWidth: 2 });
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
        rightPriceScale: { borderColor: '#2a2e39' },
        width: sEl.clientWidth,
        height: sEl.clientHeight || 150,
        ...chartTimeOpts(),
        ...CHART_SCROLL_SCALE,
      });
      spreadSeries = addSeries(spreadChart, 'LineSeries', { color: '#f0b90b', lineWidth: 2 });
    }
    bindRangeSync();
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

  function renderCharts(bars, entry, exitZ, openTrade = null, closedTrades = []) {
    ensureCharts();
    if (!zSeries || !spreadSeries) return;
    const zCandles = [];
    const spreadPts = [];
    let prevZ = null;
    const seen = new Set();
    for (const b of bars) {
      const t = toChartTime(b.time, b.timestampMs);
      if (t == null || b.z == null || seen.has(t)) continue;
      seen.add(t);
      const z = Number(b.z);
      const open = prevZ == null ? z : prevZ;
      zCandles.push({ time: t, open, high: Math.max(open, z), low: Math.min(open, z), close: z });
      if (b.spread != null) spreadPts.push({ time: t, value: Number(b.spread) });
      prevZ = z;
    }

    const fp = barsFingerprint(bars) + '|c' + (closedTrades || []).length + '|o' + (openTrade ? openTrade.id : '');
    const dataChanged = fp !== lastBarsFingerprint;
    lastBarsFingerprint = fp;

    // Типичный poll без новых баров: не трогаем timescale вообще
    if (!dataChanged && !forceFitContent && pinnedRange) {
      setThresholdLines(entry, exitZ);
      updateOpenTradeOnChart(openTrade, bars, closedTrades);
      if (userPinnedAwayFromLive) reassertPinnedRange();
      return;
    }

    try {
      suppressRangeEvents = true;
      try {
        try { zSeries.setData(zCandles); }
        catch { zSeries.setData(zCandles.map((c) => ({ time: c.time, value: c.close }))); }
        spreadSeries.setData(spreadPts);
      } catch (e) {
        suppressRangeEvents = false;
        throw e;
      }
      setThresholdLines(entry, exitZ);
      updateOpenTradeOnChart(openTrade, bars, closedTrades);
      restoreOrFitVisibleRange(zCandles.length);
    } catch (e) {
      suppressRangeEvents = false;
      console.warn('trade chart', e);
    }
  }

  function renderOpen(open) {
    const box = $('tradeOpenBox');
    const btn = $('tradeBtnClose');
    if (!open) {
      box.innerHTML = '<div class="trade-open-empty">Нет открытой позиции</div>';
      if (btn) btn.disabled = true;
      return;
    }
    if (btn) btn.disabled = false;
    const m = open.mark || {};
    const pnlCls = (m.unrealized_pnl_rub || 0) >= 0 ? 'pnl-pos' : 'pnl-neg';
    const riskCls = m.risk_red ? 'risk-red' : (m.risk_level === 'Elevated' ? 'risk-warn' : 'risk-ok');
    const spreadEntry = m.fill_spread != null ? m.fill_spread : open.entry_spread;
    const spreadLabel = m.pnl_source === 'broker_fills' ? 'Спред (fill→сейч)' : 'Спред';
    const pnlNote = m.pnl_source === 'broker_fills' ? 'по ценам Тинькофф' : 'по спреду ISS';
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

  function paramsFocused() {
    const ae = document.activeElement;
    return !!(ae && (ae.id === 'tradeEntryZ' || ae.id === 'tradeExitZ'
      || ae.id === 'tradeLeverage' || ae.id === 'tradeAutoExec'
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
    return {
      entry_z: Number.isFinite(entry) ? entry : null,
      exit_z: Number.isFinite(exitZ) ? exitZ : null,
      leverage: Number.isFinite(leverage) ? leverage : null,
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

  function applyThresholdVisuals(entry, exitZ) {
    const e = Number(entry);
    const x = Number(exitZ);
    const entryN = Number.isFinite(e) && e > 0 ? e : 1.3;
    const exitN = Number.isFinite(x) && x > 0 ? x : 1.2;
    const label = $('tradeThreshLabel');
    if (label) label.textContent = `пороги ±${fmt(entryN, 2)} / ±${fmt(exitN, 2)}`;
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
    return `<li class="trade-check-item is-${state}"><span class="trade-check-mark">${mark}</span><span class="trade-check-text">${text}</span></li>`;
  }

  function renderCheckList(el, items) {
    if (!el) return;
    el.innerHTML = items.join('');
  }

  /**
   * Фазы: idle → prep (почти всё OK, Z у порога) → signal (edge есть) → ready (AUTO может взять).
   */
  function buildTradePhase({
    pos, curZ, entryN, exitN, signal,
    monOn, autoOn, settled, consecutive, sessionOk, brokerOk, ghost,
    needLong, needShort, needExitLong, needExitShort, settleLeftSec,
  }) {
    const hardOk = monOn && sessionOk && consecutive && brokerOk && !ghost;
    const softWait = [];
    if (!autoOn) softWait.push('авто');
    if (!settled) softWait.push(settleLeftSec > 0 ? `закрытие бара (~${settleLeftSec}с)` : 'закрытие бара');

    const waitingText = (extra) => {
      const all = [...softWait, ...(extra || [])].filter(Boolean);
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
    const consecutive = prevMs > 0 && lastMs - prevMs === M15_MS;
    const hasNext = false;
    const settled = lastMs > 0 && (hasNext || nowMs >= lastMs + M15_MS + BAR_SETTLE_MS);
    const settleLeftSec = lastMs > 0
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

    const brokerOk = !!(broker && !broker.error);
    const ghost = !!(!open && bs && !bs.error && bs.direction);

    const general = [];
    general.push(checkItem(monOn ? 'ok' : 'block', monOn ? 'Монитор ON' : 'Монитор OFF — старт на вкладке Счёт'));
    general.push(checkItem(autoOn ? 'ok' : 'wait', autoOn ? 'Авто ON (ордера)' : 'Авто OFF — сигналы без ордеров'));
    general.push(checkItem(msk.inSession ? 'ok' : 'block',
      msk.inSession ? `Сессия TQBR сейчас (${msk.label} МСК)` : `Вне сессии TQBR (${msk.label} МСК)`));
    general.push(checkItem(barInSession ? 'ok' : (last ? 'block' : 'wait'),
      last ? (barInSession ? `Бар в сессии · ${escapeHtml(fmtTickLabel(barTd))}` : `Бар вне сессии · ${escapeHtml(fmtTickLabel(barTd))}`) : 'Нет бара'));
    general.push(checkItem(settled ? 'ok' : 'wait',
      settled
        ? `Бар закрыт (+45с)`
        : `Ждём закрытие бара${settleLeftSec > 0 ? ` · ещё ~${settleLeftSec}с` : ''}`));
    general.push(checkItem(consecutive ? 'ok' : (bars.length >= 2 ? 'block' : 'wait'),
      consecutive ? 'Ряд баров без дыры (15м)' : (bars.length >= 2 ? 'Дыра в барах — AUTO пропустит' : 'Мало баров')));
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
    });

    if (pos === 'FLAT') {
      openItems.push(checkItem('ok', 'Позиция FLAT — можно открыть'));
      if (ghost) {
        openItems.push(checkItem('block', 'Сначала сверить призрак с брокером'));
      }
      openItems.push(checkItem(
        curZ != null && curZ <= -entryN ? 'ok' : 'wait',
        curZ != null && curZ <= -entryN
          ? `Z ≤ −${fmt(entryN, 2)} для Long · сейчас ${zTxt}`
          : `До Long: Z ≤ −${fmt(entryN, 2)} · сейчас ${zTxt}${needLong != null && needLong > 0 ? ` · ещё −${fmt(needLong, 2)}${entryNeedPctSuffix(needLong, entryN)}` : ''}`
      ));
      openItems.push(checkItem(
        curZ != null && curZ >= entryN ? 'ok' : 'wait',
        curZ != null && curZ >= entryN
          ? `Z ≥ +${fmt(entryN, 2)} для Short · сейчас ${zTxt}`
          : `До Short: Z ≥ +${fmt(entryN, 2)} · сейчас ${zTxt}${needShort != null && needShort > 0 ? ` · ещё +${fmt(needShort, 2)}${entryNeedPctSuffix(needShort, entryN)}` : ''}`
      ));
      openItems.push(checkItem(
        signal.startsWith('ENTER') ? 'ok' : 'wait',
        signal.startsWith('ENTER')
          ? `Edge готов: ${signal}`
          : 'Нужен edge: пересечение порога входа на закрытом баре'
      ));
      if (phase.kind === 'prep' || phase.kind === 'signal') {
        openItems.push(checkItem('wait', phase.title || phase.label));
      } else if (phase.kind === 'ready') {
        openItems.push(checkItem('ok', autoOn ? 'AUTO откроет на следующем тике' : 'Сигнал готов — включите Авто'));
      }
      const blockers = [];
      if (!monOn) blockers.push('монитор');
      if (!autoOn) blockers.push('авто');
      if (!sessionOk) blockers.push('сессия');
      if (!settled) blockers.push('закрытие бара (+45с)');
      if (!consecutive) blockers.push('дыра');
      if (!brokerOk) blockers.push('брокер');
      if (ghost) blockers.push('призрак');
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
        closeItems.push(checkItem(
          curZ != null && curZ >= -exitN ? 'ok' : 'wait',
          curZ != null && curZ >= -exitN
            ? `Z ≥ −${fmt(exitN, 2)} для EXIT_LONG · сейчас ${zTxt}`
            : `До EXIT_LONG: Z ≥ −${fmt(exitN, 2)} · сейчас ${zTxt}${needExitLong != null && needExitLong > 0 ? ` · ещё +${fmt(needExitLong, 2)}` : ''}`
        ));
      } else {
        closeItems.push(checkItem(
          curZ != null && curZ <= exitN ? 'ok' : 'wait',
          curZ != null && curZ <= exitN
            ? `Z ≤ +${fmt(exitN, 2)} для EXIT_SHORT · сейчас ${zTxt}`
            : `До EXIT_SHORT: Z ≤ +${fmt(exitN, 2)} · сейчас ${zTxt}${needExitShort != null && needExitShort > 0 ? ` · ещё −${fmt(needExitShort, 2)}` : ''}`
        ));
      }
      closeItems.push(checkItem(
        signal.startsWith('EXIT') ? 'ok' : 'wait',
        signal.startsWith('EXIT')
          ? `Edge готов: ${signal}`
          : 'Нужен edge: пересечение порога выхода на закрытом баре'
      ));
      if (phase.kind === 'prep' || phase.kind === 'signal') {
        closeItems.push(checkItem('wait', phase.title || phase.label));
      } else if (phase.kind === 'ready') {
        closeItems.push(checkItem('ok', autoOn ? 'AUTO закроет на следующем тике' : 'Сигнал готов — Авто или «Закрыть сделку»'));
      }
      const blockers = [];
      if (!monOn) blockers.push('монитор');
      if (!autoOn) blockers.push('авто');
      if (!sessionOk) blockers.push('сессия');
      if (!settled) blockers.push('закрытие бара (+45с)');
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
    if (phase.kind === 'ready') {
      hint = `${phase.title} · Z ${zTxt}`;
      hintCls += ' is-ready';
    } else if (phase.kind === 'signal') {
      hint = `${phase.title} · Z ${zTxt}`;
      hintCls += ' is-block';
    } else if (phase.kind === 'prep') {
      hint = `${phase.title} · Z ${zTxt}`;
      hintCls += ' is-prep';
    } else if (pos === 'FLAT') {
      const nearer = (needLong == null || needShort == null)
        ? null
        : (needLong <= needShort
          ? `до Long ещё −${fmt(needLong, 2)}${entryNeedPctSuffix(needLong, entryN)} по Z`
          : `до Short ещё +${fmt(needShort, 2)}${entryNeedPctSuffix(needShort, entryN)} по Z`);
      hint = nearer ? `Ожидание входа · ${nearer} · Z ${zTxt}` : `Ожидание входа · Z ${zTxt}`;
    } else if (pos === 'LONG') {
      hint = needExitLong != null && needExitLong > 0
        ? `В позиции Long · до выхода ещё +${fmt(needExitLong, 2)} по Z · Z ${zTxt}`
        : `В позиции Long · у порога выхода · Z ${zTxt}`;
    } else {
      hint = needExitShort != null && needExitShort > 0
        ? `В позиции Short · до выхода ещё −${fmt(needExitShort, 2)} по Z · Z ${zTxt}`
        : `В позиции Short · у порога выхода · Z ${zTxt}`;
    }
    hintEl.className = hintCls;
    hintEl.textContent = hint;

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
    const monHtml = monBadge(!!mon.running);
    const autoHtml = autoBadge(!!settings.auto_execute);
    const modeHtml = modeBadge(settings.mode);

    const bars = data.bars || [];
    rebuildBarMetricDists(bars);
    const regimeHtml = regimeBadge(bars);
    const zDisp = fmt(s.z);
    const spDisp = `${fmt(s.spread)}%`;

    // Данные / связь — только в шапке
    $('tradeMeta').innerHTML =
      `${s.window_count || 0} баров · ${escapeHtml(s.source || '—')} · ${onlineBadge(!!s.online)}`;

    // Исполнение — один раз в статус-баре (без рынка и без баланса)
    $('tradeStatus').innerHTML =
      `${monHtml} · ${autoHtml} · ${modeHtml}` +
      (s.trade_date ? ` · ${tickBadge(s.trade_date)}` : '');

    // Рынок — у графиков
    $('tradeStrip').innerHTML = [
      metricStripBlock('Z', 'z', s.z, zDisp),
      metricStripBlock('Спред', 'spread', s.spread, spDisp),
      regimeHtml ? `<span><b>Режим</b> ${regimeHtml}</span>` : '',
      `<span><b>TATN</b> ${fmt(s.tatn)}</span>`,
      `<span><b>TATNP</b> ${fmt(s.tatnp)}</span>`,
    ].filter(Boolean).join(' ');

    // Позиция + фаза — в renderChecklist (после poll данных)

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
    const th = applyThresholdVisuals(entry, exitZ);

    renderOpen(data.open);
    renderOpenStats(data.open_stats, data.open);
    renderFunds(data.broker, { pending: !!data.lite && !data.broker });
    renderChecklist(data);

    renderCharts(data.bars || [], th.entry, th.exitZ, data.open || null, data.closed || []);
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

  async function refresh({ hydrateForm = false, forceMoex = false, lite = false } = {}) {
    if (forceMoex) {
      await api(`/api/markets/refresh?days=${days}`, { method: 'POST' });
    }
    const seq = ++deskFetchSeq;
    const liteQ = lite ? '&lite=1' : '';
    let data = await api(`/api/trade/desk?days=${days}${liteQ}`);
    if (seq !== deskFetchSeq) return data;
    // Monitor start only on full desk (lite is first-paint charts)
    if (!lite && await ensureMonitorRunning(data)) {
      data = await api(`/api/trade/desk?days=${days}`);
      if (seq !== deskFetchSeq) return data;
    }
    // Late responses: never force-hydrate over newer in-flight work
    const hydrate = hydrateForm && seq === deskFetchSeq;
    renderDesk(data, { hydrateForm: hydrate });
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
    } finally {
      scheduleEndSuppress();
    }
    if (userPinnedAwayFromLive && pinnedRange) {
      requestAnimationFrame(() => {
        requestAnimationFrame(reassertPinnedRange);
      });
    } else if (pinnedRange) {
      reassertPinnedRange();
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

  function startPoll() {
    stopPoll();
    pollTimer = setInterval(() => { refresh().catch(() => {}); }, 12000);
  }

  function stopPoll() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
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
    // 2) full desk: broker/funds in background (cached 20s on server)
    hydrateParamsFromServer().catch(() => {});
    refresh({ hydrateForm: !formDirty, lite: true })
      .then(() => refresh({ hydrateForm: false, lite: false }))
      .then(() => {
        restoreTradeScrolls();
        startPoll();
      })
      .catch((e) => {
        $('tradeStatus').textContent = `Ошибка: ${e.message}`;
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
    ['tradeEntryZ', 'tradeExitZ', 'tradeLeverage'].forEach((id) => {
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
