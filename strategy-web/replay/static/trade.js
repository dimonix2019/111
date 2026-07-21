/** Торговля — chart + open trade panel (desk) */
(function () {
  const LS_CHART_RANGE = 'moexReplay.tradeChartRange';
  const LS_SPREAD_PANE_HEIGHT = 'moexReplay.tradeSpreadPaneHeight';
  const LS_TRADE_PARAMS = 'moexReplay.tradeParams';
  const MSK = 'Europe/Moscow';
  /** to ближе к концу данных, чем N баров → считаем «у live» */
  const LIVE_EDGE_BARS = 5;

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

  function renderCharts(bars, entry, exitZ) {
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

    const fp = barsFingerprint(bars);
    const dataChanged = fp !== lastBarsFingerprint;
    lastBarsFingerprint = fp;

    // Типичный poll без новых баров: не трогаем timescale вообще
    if (!dataChanged && !forceFitContent && pinnedRange) {
      setThresholdLines(entry, exitZ);
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
    box.innerHTML =
      `<div class="trade-open-dir">${open.direction} · ${open.quantity_lots}+${open.quantity_lots} лот · ${open.source || ''}</div>` +
      `<div class="trade-open-grid">` +
      `<span>Вход</span><b>${open.entry_time || '—'}</b>` +
      `<span>Z вх → сейч</span><b>${fmt(open.entry_z)} → ${fmt(m.z_now)}</b>` +
      `<span>Спред</span><b>${fmt(open.entry_spread)}% → ${fmt(m.spread_now)}%</b>` +
      `<span>Notional</span><b>${fmt(m.notional_rub, 0)} ₽</b>` +
      `<span>PnL ≈</span><b class="${pnlCls}">${fmtRub(m.unrealized_pnl_rub)}</b>` +
      `<span>Нетто ≈</span><b class="${pnlCls}">${fmtRub(m.net_approx_rub)}</b>` +
      `<span>Овн</span><b>${fmtRub(m.overnight_rub)} · ${m.overnight_days || 0}д</b>` +
      `<span>Hold</span><b>${m.hold_hours != null ? fmt(m.hold_hours, 1) + ' ч' : '—'}</b>` +
      `</div>` +
      `<div class="trade-risk ${riskCls}">Риск ${m.risk_level || '—'} · score ${m.risk_score ?? '—'}` +
      (m.risk_flags && m.risk_flags.length ? ` · ${m.risk_flags.join(', ')}` : '') +
      `</div>`;
  }

  function modeLabel(mode) {
    return mode === 'prod' ? 'Боевой (Prod)' : 'Песочница';
  }

  function renderFunds(broker) {
    const box = $('tradeFundsBox');
    const totalEl = $('tradeFundsTotal');
    const cashEl = $('tradeFundsCash');
    const brokerEl = $('tradeBrokerBox');
    if (!box || !totalEl || !cashEl) return;

    box.classList.remove('is-prod', 'is-error');
    if (!broker) {
      totalEl.textContent = '—';
      cashEl.textContent = 'нет токена — вкладка «Счёт»';
      if (brokerEl) brokerEl.textContent = 'Брокер: нет токена — вкладка «Счёт»';
      return;
    }
    if (broker.error) {
      box.classList.add('is-error');
      totalEl.textContent = broker.error;
      cashEl.textContent = modeLabel(broker.mode);
      if (brokerEl) brokerEl.textContent = `Брокер: ${broker.error}`;
      return;
    }
    if (broker.mode === 'prod') box.classList.add('is-prod');
    const mode = modeLabel(broker.mode);
    totalEl.textContent = `${fmt(broker.total_rub, 0)} ₽`;
    cashEl.textContent = `${mode} · cash ${fmt(broker.cash_rub, 0)} ₽`;
    if (brokerEl) {
      brokerEl.textContent =
        `Брокер [${broker.mode}]: ${fmt(broker.total_rub, 0)} ₽ · cash ${fmt(broker.cash_rub, 0)} ₽`;
    }
  }

  function paramsFocused() {
    const ae = document.activeElement;
    return !!(ae && (ae.id === 'tradeEntryZ' || ae.id === 'tradeExitZ'
      || ae.id === 'tradeLeverage' || ae.id === 'tradeAutoExec'));
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

  function readFormParams() {
    const entry = parseFloat(String($('tradeEntryZ')?.value || '').replace(',', '.'));
    const exitZ = parseFloat(String($('tradeExitZ')?.value || '').replace(',', '.'));
    const leverage = parseFloat(String($('tradeLeverage')?.value || '').replace(',', '.'));
    return {
      entry_z: Number.isFinite(entry) ? entry : null,
      exit_z: Number.isFinite(exitZ) ? exitZ : null,
      leverage: Number.isFinite(leverage) ? leverage : null,
      auto_execute: !!$('tradeAutoExec')?.checked,
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
      }));
    } catch (_) { /* ignore quota */ }
  }

  function loadCachedParamsLocal() {
    try {
      const raw = localStorage.getItem(LS_TRADE_PARAMS);
      if (!raw) return null;
      const o = JSON.parse(raw);
      if (!o || o.entry_z == null || o.exit_z == null) return null;
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
      const data = await api('/api/live/status');
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

  function renderDesk(data, { hydrateForm = false } = {}) {
    const s = data.summary || {};
    const settings = data.settings || {};
    const mon = data.monitor || {};
    const pos = data.position || 'FLAT';
    const monHtml = monBadge(!!mon.running);
    const autoHtml = autoBadge(!!settings.auto_execute);
    const modeHtml = modeBadge(settings.mode);

    const b = data.broker;
    const fundsShort = (!b || b.error)
      ? ''
      : ` · <b>${fmt(b.total_rub, 0)} ₽</b>`;

    const bars = data.bars || [];
    rebuildBarMetricDists(bars);
    const regimeHtml = regimeBadge(bars);
    const zDisp = fmt(s.z);
    const spDisp = `${fmt(s.spread)}%`;

    $('tradeStatus').innerHTML =
      `${tickBadge(s.trade_date)} · Z ${metricHoverValue('z', s.z, zDisp)} · спред ${metricHoverValue('spread', s.spread, spDisp)} · ${regimeHtml} · ${escapeHtml(pos)} · ` +
      `TATN ${fmt(s.tatn)} / TATNP ${fmt(s.tatnp)} · ${monHtml} · ${autoHtml} · ${modeHtml}` +
      fundsShort + ` · ` + onlineBadge(!!s.online);

    $('tradeMeta').innerHTML =
      `Торговля · ${s.window_count || 0} баров · ${escapeHtml(s.source || '')} · ${onlineBadge(!!s.online)}` +
      ((!b || b.error) ? '' : ` · ${fmt(b.total_rub, 0)} ₽`);

    $('tradeStrip').innerHTML = [
      metricStripBlock('Z', 'z', s.z, zDisp),
      metricStripBlock('Спред', 'spread', s.spread, spDisp),
      regimeHtml ? `<span><b>Режим</b> ${regimeHtml}</span>` : '',
      `<span><b>Поз.</b> ${escapeHtml(pos)}</span>`,
      `<span><b>TATN</b> ${fmt(s.tatn)}</span>`,
      `<span><b>TATNP</b> ${fmt(s.tatnp)}</span>`,
    ].filter(Boolean).join(' ');

    $('tradeSideStatus').innerHTML =
      `${escapeHtml(pos)} · ${monHtml} · ${autoHtml} · ${modeHtml}`;

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
    renderFunds(data.broker);

    let monMsg = mon.last_message || '';
    const bs = data.broker_spread;
    if (data.open && data.open.source === 'BROKER') {
      monMsg = (monMsg ? `${monMsg} · ` : '') + 'позиция с брокера (sync)';
    } else if (bs && !bs.error && !data.open) {
      monMsg = (monMsg ? `${monMsg} · ` : '')
        + `брокер: спред ${bs.direction} ${bs.quantity_lots}+${bs.quantity_lots} лот`;
    }
    $('tradeMonMsg').textContent = monMsg;
    const parity = data.parity || {};
    const latest = parity.latest;
    if (latest && latest.status && latest.status !== 'pending') {
      const tag = latest.status === 'matched' ? 'Parity OK' : `Parity ${latest.status}`;
      const detail = (latest.result && latest.result.detail) || latest.signal || '';
      $('tradeMonMsg').textContent =
        (monMsg || '') + ` · ${tag}: ${latest.bar_ts || ''} ${detail}`.trim();
    } else if (parity.pending > 0) {
      $('tradeMonMsg').textContent =
        (monMsg || '') + ` · parity ждёт: ${parity.pending} (через ~${parity.delay_min || 45} мин)`;
    }

    renderCharts(data.bars || [], th.entry, th.exitZ);
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

  async function refresh({ hydrateForm = false, forceMoex = false } = {}) {
    if (forceMoex) {
      await api(`/api/markets/refresh?days=${days}`, { method: 'POST' });
    }
    const seq = ++deskFetchSeq;
    let data = await api(`/api/trade/desk?days=${days}`);
    if (seq !== deskFetchSeq) return data;
    if (await ensureMonitorRunning(data)) {
      data = await api(`/api/trade/desk?days=${days}`);
      if (seq !== deskFetchSeq) return data;
    }
    // Late responses: never force-hydrate over newer in-flight work
    const hydrate = hydrateForm && seq === deskFetchSeq;
    renderDesk(data, { hydrateForm: hydrate });
    return data;
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
    resize();
    // Instant LS + /api/live/status (не зависит от тяжёлого desk/MOEX)
    const cached = loadCachedParamsLocal();
    if (cached && !formDirty) hydrateParams(cached, { force: true });
    hydrateParamsFromServer()
      .then(() => refresh({ hydrateForm: !formDirty }))
      .then(() => startPoll())
      .catch((e) => {
        $('tradeStatus').textContent = `Ошибка: ${e.message}`;
        startPoll();
      });
    requestAnimationFrame(() => {
      resize();
      requestAnimationFrame(resize);
    });
    // Phone/WebView: layout settles after paint / keyboard / orientation
    setTimeout(resize, 120);
    setTimeout(resize, 400);
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
    const tipRoots = [$('tradeStrip'), $('tradeStatus')].filter(Boolean);
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
