/** Adaptive corridor UI + chart overlays — split from trade.js */
(function (global) {
  'use strict';
  function D() { return global.__TradeDesk.deps; }
  let corridorChartSeries = [];
  /** Активные границы коридора для вертикального автомасштаба (lo/hi). */
  let activeCorridorBounds = null;
  let lastCorridorAutoscalePts = null;
  const CORRIDOR_PHASE_META = {
    none: {
      badge: 'нет',
      status: 'Коридора нет — спред не держится в узкой полосе',
    },
    forming: {
      badge: 'формируется',
      status: 'Края коридора проявляются — ждём устойчивого удержания в полосе',
    },
    formed: {
      badge: 'сформирован',
      status: 'Коридор держится · касания низа и верха подтверждены',
    },
    broken: {
      // Для торговли «сломан» = нет: тот же UI, что у none (без «сломан» / метрик).
      badge: 'нет',
      status: 'Коридора нет — спред вышел за недавние границы',
    },
  };

  /** Фазы, когда коридора для торговли нет (линии/метрики/бейдж «сломан» не показываем). */
  function corridorPhaseAbsent(phase) {
    const p = String(phase || 'none');
    return p === 'none' || p === 'broken';
  }
  const CORRIDOR_CHART_STYLE = {
    forming: {
      // Dotted (1) — частый пунктир; LargeDashed (3) был слишком редким.
      lineStyle: 1,
      color: '#fbbf24',
      width: 1,
      marker: 'Ф',
    },
    formed: {
      lineStyle: 0,
      color: '#34d399',
      width: 1,
      marker: 'С',
    },
  };
  let lastPaintZData = null;
  let lastPaintMtlrData = null;
  let openMarkersPlugin = null;
  /** Маркеры сделок Мечела на нижнем pane (не TATN). */
  let openSpreadMarkersPlugin = null;
  /** Bottom pane: CandlestickSeries (MTLR m15) — not yellow TATN line. */
  let spreadSeriesIsLine = false;
  let lastMtlrBars = [];
  let lastMtlrLevels = null;
  let lastMtlrMarkers = [];
  let lastOpenTradeFp = '';
  /** Режим счёта с последнего desk refresh (prod|sandbox) — для confirm ручного входа. */
  let lastTradeMode = '';
  /** Полные маркеры + сделки для hit-test / yellow highlight (как chart.js). */
  let lastDeskMarkers = [];
  let lastDeskTrades = [];
  /** Бары tip1m последней отрисовки — спред для highlight закрытых сделок. */
  let lastDeskPaintBars = [];
  let hoverTradeId = null;
  let markerHoverBound = false;
  /** Данные линии открытой сделки — восстанавливаем, когда hover снят. */
  let defaultOpenHighlightData = null;
  let refreshMarkersTimer = 0;
  /** true → fitContent после setData (смена периода 1Д/1Н/1М/3М/6М) */
  let forceFitContent = false;
  let pendingPeriodFitDays = 0;
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

  function barSpreadValue(b) {
    if (!b || typeof b !== 'object') return null;
    const v = b.spread != null ? b.spread
      : (b.spreadPercent != null ? b.spreadPercent
        : (b.close != null && b.z == null ? b.close : null));
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }

  function barTradeDay(b) {
    if (!b || typeof b !== 'object') return null;
    for (const key of ['trade_date', 'tradeDate', 'time', 'timestamp']) {
      const raw = b[key];
      if (raw == null) continue;
      const s = String(raw).trim().replace('T', ' ');
      if (s.length >= 10 && s[4] === '-' && s[7] === '-') return s.slice(0, 10);
    }
    const ms = Number(b.timestampMs);
    if (Number.isFinite(ms) && ms > 0) {
      return new Date(ms).toISOString().slice(0, 10);
    }
    return null;
  }

  /** Клиентский fallback: дневной ряд из m15 + упрощённый коридор (если API не отдал). */
  function dailySpreadFromBars(bars) {
    const byDay = new Map();
    for (const b of bars || []) {
      const td = barTradeDay(b);
      const sp = barSpreadValue(b);
      if (td && sp != null) {
        if (!byDay.has(td)) byDay.set(td, []);
        byDay.get(td).push(sp);
      }
    }
    return [...byDay.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .map(([, vals]) => {
        const s = [...vals].sort((a, b) => a - b);
        const m = Math.floor(s.length / 2);
        return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
      });
  }

  function dailySpreadMinMaxFromBars(bars) {
    const byDay = new Map();
    for (const b of bars || []) {
      const td = barTradeDay(b);
      const sp = barSpreadValue(b);
      if (td && sp != null) {
        if (!byDay.has(td)) byDay.set(td, []);
        byDay.get(td).push(sp);
      }
    }
    const mins = [];
    const maxs = [];
    [...byDay.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .forEach(([, vals]) => {
        mins.push(Math.min(...vals));
        maxs.push(Math.max(...vals));
      });
    return { mins, maxs, medians: valsFromDays(byDay) };
    function valsFromDays(map) {
      return [...map.entries()]
        .sort((a, b) => a[0].localeCompare(b[0]))
        .map(([, vals]) => {
          const s = [...vals].sort((a, b) => a - b);
          const m = Math.floor(s.length / 2);
          return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
        });
    }
  }

  function detectSpreadCorridorClient(bars, spreadNow) {
    const loSanity = 0.3;
    const hiSanity = 8.0;
    const touchEps = 0.25;
    const breakEps = 0.08;
    const { mins, maxs, medians } = dailySpreadMinMaxFromBars(bars);
    const values = medians;
    if (values.length < 7 || mins.length < 7 || maxs.length < 7) {
      return { phase: 'none', label_ru: 'нет', title: 'Мало дневных точек', n_days: values.length };
    }
    const pct = (arr, p) => {
      const s = [...arr].sort((a, b) => a - b);
      if (!s.length) return 0;
      const k = (s.length - 1) * (p / 100);
      const f = Math.floor(k);
      const c = Math.min(f + 1, s.length - 1);
      return f === c ? s[f] : s[f] + (s[c] - s[f]) * (k - f);
    };
    const boundsFrom = (meds, dayMins, dayMaxs) => {
      let mode = 'adaptive';
      let lo = Math.max(loSanity, pct(dayMins, 20));
      let hi = Math.min(hiSanity, pct(dayMaxs, 62));
      if (hi > lo + 0.8) mode = 'calculated';
      else {
        lo = Math.max(loSanity, pct(meds, 12));
        hi = Math.min(hiSanity, pct(meds, 88));
        if (hi <= lo + 0.3) hi = lo + 0.5;
      }
      return { lo, hi, mode };
    };
    let { lo, hi, mode: boundsMode } = boundsFrom(values, mins, maxs);
    const touchesLo = mins.filter((v) => v <= lo + touchEps).length;
    const touchesHi = maxs.filter((v) => v >= hi - touchEps).length;
    let width = hi - lo;
    const s = Number.isFinite(Number(spreadNow)) ? Number(spreadNow) : values[values.length - 1];
    const dwellOf = (meds, bandLo, bandHi) => {
      let n = 0;
      for (let i = meds.length - 1; i >= 0; i -= 1) {
        if (meds[i] >= bandLo - breakEps && meds[i] <= bandHi + breakEps) n += 1;
        else break;
      }
      return n;
    };
    let dwell = dwellOf(values, lo, hi);
    let bounces = 0;
    const bot = lo + 0.25 * Math.max(width, 1e-9);
    const top = hi - 0.25 * Math.max(width, 1e-9);
    let prevZone = null;
    for (const v of values) {
      let z = 'mid';
      if (v <= bot) z = 'lo';
      else if (v >= top) z = 'hi';
      if ((z === 'lo' || z === 'hi') && z !== prevZone) bounces += 1;
      if (z !== 'mid') prevZone = z;
    }
    // Липкий слом по предыдущему дню (как на сервере): шип max не расширяет полосу.
    let broken = false;
    let freezeLo = null;
    let freezeHi = null;
    if (values.length > 7) {
      const prevMeds = values.slice(0, -1);
      const prevMins = mins.slice(0, -1);
      const prevMaxs = maxs.slice(0, -1);
      const prevB = boundsFrom(prevMeds, prevMins, prevMaxs);
      const prevWidth = prevB.hi - prevB.lo;
      const prevCalcOk = prevB.mode === 'calculated' && prevWidth <= 4.0;
      const prevAdaptOk = prevB.mode === 'adaptive' && prevWidth <= 1.8;
      const prevDwell = dwellOf(prevMeds, prevB.lo, prevB.hi);
      let prevBounces = 0;
      const pBot = prevB.lo + 0.25 * Math.max(prevWidth, 1e-9);
      const pTop = prevB.hi - 0.25 * Math.max(prevWidth, 1e-9);
      let pz = null;
      for (const v of prevMeds) {
        let z = 'mid';
        if (v <= pBot) z = 'lo';
        else if (v >= pTop) z = 'hi';
        if ((z === 'lo' || z === 'hi') && z !== pz) prevBounces += 1;
        if (z !== 'mid') pz = z;
      }
      const pTouchesLo = prevMins.filter((v) => v <= prevB.lo + touchEps).length;
      const pTouchesHi = prevMaxs.filter((v) => v >= prevB.hi + touchEps).length;
      let prevPhase = 'none';
      if ((prevCalcOk || prevAdaptOk) && pTouchesLo >= 2 && pTouchesHi >= 2 && prevDwell >= 5
          && (prevB.mode === 'calculated' || prevBounces >= 2)) {
        prevPhase = 'formed';
      } else if ((prevCalcOk || prevAdaptOk)
          && (pTouchesLo >= 1 || pTouchesHi >= 1)
          && (pTouchesLo + pTouchesHi >= 3 || prevBounces >= 2)
          && prevDwell >= 2) {
        prevPhase = 'forming';
      }
      const lastMax = maxs[maxs.length - 1];
      const lastMin = mins[mins.length - 1];
      const exited = s > prevB.hi + breakEps || s < prevB.lo - breakEps
        || lastMax > prevB.hi + breakEps || lastMin < prevB.lo - breakEps;
      if ((prevPhase === 'formed' || prevPhase === 'forming') && exited) {
        broken = true;
        freezeLo = prevB.lo;
        freezeHi = prevB.hi;
      }
    }
    const calcOk = boundsMode === 'calculated' && width <= 4.0;
    const adaptOk = boundsMode === 'adaptive' && width <= 1.8;
    const boundsOk = calcOk || adaptOk;
    let phase = 'none';
    let label = 'нет';
    if (broken && freezeLo != null && freezeHi != null) {
      phase = 'broken';
      label = 'нет';
      lo = freezeLo;
      hi = freezeHi;
      width = hi - lo;
      boundsMode = 'frozen';
    } else if (boundsOk && touchesLo >= 2 && touchesHi >= 2 && dwell >= 5
        && (boundsMode === 'calculated' || bounces >= 2)) {
      phase = 'formed';
      label = 'сформирован';
    } else if (boundsOk && (touchesLo >= 1 || touchesHi >= 1) && (touchesLo + touchesHi >= 3 || bounces >= 2) && dwell >= 4) {
      phase = 'forming';
      label = 'формируется';
    }
    const bandSpan = Math.max(hi - lo, 1e-9);
    const pctIn = Math.max(0, Math.min(100, ((s - lo) / bandSpan) * 100));
    const title = broken
      ? `Коридор сломан · была полоса ${lo.toFixed(2)}…${hi.toFixed(2)}% · S сейчас ${s.toFixed(2)}%`
      : `Коридор ${lo.toFixed(2)}…${hi.toFixed(2)}% (расчёт: ${boundsMode}; низ=p20 дн.мин, верх=p62 дн.макс) · касания ${touchesLo}/${touchesHi}`;
    return {
      phase,
      label_ru: label,
      lo,
      hi,
      width: hi - lo,
      spread: s,
      pct_in_band: pctIn,
      dwell_days: dwell,
      bounces,
      touches_lo: touchesLo,
      touches_hi: touchesHi,
      bounds_mode: boundsMode,
      shrink_days: 0,
      n_days: values.length,
      title,
    };
  }

  function corridorPositionText(s, lo, hi, eps = 0.08) {
    if (!Number.isFinite(s) || !Number.isFinite(lo) || !Number.isFinite(hi)) return '—';
    if (s >= lo - eps && s <= hi + eps) {
      const pct = ((s - lo) / Math.max(hi - lo, 1e-9)) * 100;
      return `S внутри коридора · ${D().fmt(pct, 0)}% полосы`;
    }
    if (s < lo - eps) return `S ниже коридора на ${D().fmt(lo - s, 2)} п.п.`;
    return `S выше коридора на ${D().fmt(s - hi, 2)} п.п.`;
  }

  function corridorPhaseStatus(phase) {
    const meta = CORRIDOR_PHASE_META[phase] || CORRIDOR_PHASE_META.none;
    // Удержание (дни) — только в сетке коридора, без дубля в статусе.
    if (phase === 'formed') {
      return meta.status || 'Коридор держится · касания низа и верха подтверждены';
    }
    return meta.status;
  }

  function renderCorridorMeter(corridor, spreadPct, barsIss) {
    const box = $('tradeCorridorBox');
    const badge = $('tradeCorridorBadge');
    const statusEl = $('tradeCorridorStatus');
    const band = $('tradeCorridorBand');
    const mark = $('tradeCorridorMark');
    const edgeLo = $('tradeCorridorEdgeLo');
    const edgeHi = $('tradeCorridorEdgeHi');
    const grid = $('tradeCorridorGrid');
    if (!box) return;
    let c = corridor;
    if (!c || c.phase == null) {
      c = detectSpreadCorridorClient(barsIss, spreadPct);
    }
    const rawPhase = String(c.phase || 'none');
    box.classList.remove(
      'trade-corridor--forming', 'trade-corridor--formed',
      'trade-corridor--broken', 'trade-corridor--none',
    );
    // Карточка только если коридор сформирован — без пустой «не сформирован» / «формируется».
    if (rawPhase !== 'formed') {
      box.hidden = true;
      box.classList.add('trade-corridor--none');
      if (grid) grid.innerHTML = '';
      return;
    }
    box.hidden = false;
    const phase = 'formed';
    const meta = CORRIDOR_PHASE_META.formed;
    box.classList.add('trade-corridor--formed');
    if (badge) badge.textContent = c.label_ru || meta.badge;
    const phaseStatus = corridorPhaseStatus(phase);
    if (statusEl) statusEl.textContent = phaseStatus;
    if (c.title) box.title = c.title;

    const lo = Number(c.lo);
    const hi = Number(c.hi);
    const width = Number(c.width);
    const s = Number.isFinite(Number(c.spread)) ? Number(c.spread) : Number(spreadPct);
    const dwell = Number(c.dwell_days) || 0;
    const bounces = Number(c.bounces) || 0;
    const touchesLo = Number(c.touches_lo) || 0;
    const touchesHi = Number(c.touches_hi) || 0;
    const boundsMode = String(c.bounds_mode || '');
    const nDays = Number(c.n_days) || 0;

    if (!Number.isFinite(lo) || !Number.isFinite(hi) || hi <= lo) {
      if (band) { band.style.left = '0%'; band.style.width = '0%'; }
      if (mark) { mark.style.left = '50%'; mark.classList.remove('trade-corridor-mark--out'); }
      if (edgeLo) edgeLo.textContent = '—';
      if (edgeHi) edgeHi.textContent = '—';
      // Без «S сейчас» / «Режим» — спред на полосе рынка, фаза уже в бейдже.
      if (grid) {
        grid.innerHTML = `
          <div><div class="tcg-k">Дней данных</div><div class="tcg-v">${nDays}</div></div>`;
      }
      return;
    }

    const eps = 0.08;
    const inside = Number.isFinite(s) && s >= lo - eps && s <= hi + eps;
    const wVal = Number.isFinite(width) ? fmt(width, 2) : '—';
    const pad = Math.max(0.2, (hi - lo) * 0.45);
    const viewLo = lo - pad;
    const viewHi = hi + pad;
    const viewSpan = viewHi - viewLo;
    const bandL = ((lo - viewLo) / viewSpan) * 100;
    const bandW = ((hi - lo) / viewSpan) * 100;
    const markP = Number.isFinite(s)
      ? Math.max(0, Math.min(100, ((s - viewLo) / viewSpan) * 100))
      : 50;
    if (band) {
      band.style.left = `${bandL.toFixed(1)}%`;
      band.style.width = `${Math.max(6, bandW).toFixed(1)}%`;
    }
    if (mark) {
      mark.style.left = `${markP.toFixed(1)}%`;
      mark.classList.toggle('trade-corridor-mark--out', !inside);
    }
    if (edgeLo) edgeLo.textContent = `${D().fmt(lo, 2)}%`;
    if (edgeHi) edgeHi.textContent = `${D().fmt(hi, 2)}%`;

    const posText = corridorPositionText(s, lo, hi);
    if (statusEl && phase !== 'none') {
      statusEl.textContent = `${phaseStatus} · ${posText}`;
    } else if (statusEl && c.since_date && Number.isFinite(lo)) {
      statusEl.textContent = `Низкий спред с ${c.since_date} · ${posText}`;
    }

    const sinceKey = c.since_date ? 'С низкого режима' : 'Дней данных';
    const sinceVal = c.since_date
      ? String(c.since_date).slice(5).replace('-', '.')
      : String(nDays);
    // Низ/верх — на шкале; S — на полосе рынка + маркере. В сетке только уникальное.
    if (grid) {
      grid.innerHTML = `
        <div><div class="tcg-k">Ширина</div><div class="tcg-v">${wVal} п.п.</div></div>
        <div><div class="tcg-k">Удержание</div><div class="tcg-v">${D().fmt(dwell, 0)} дн.</div></div>
        <div><div class="tcg-k">Отскоки</div><div class="tcg-v">${D().fmt(bounces, 0)}</div></div>
        <div><div class="tcg-k">Касания низ / верх</div><div class="tcg-v">${D().fmt(touchesLo, 0)} / ${D().fmt(touchesHi, 0)}</div></div>
        <div><div class="tcg-k">${sinceKey}</div><div class="tcg-v">${sinceVal}${boundsMode === 'calculated' ? ' · расчёт' : boundsMode === 'adaptive' ? ' · адаптив' : ''}</div></div>`;
    }
  }

  function dateStrToChartSec(dateStr) {
    const d = String(dateStr || '').slice(0, 10);
    if (d.length < 10) return null;
    return D().toChartTime(`${d} 12:00`);
  }

  function chartSpreadValues(pts) {
    const vals = [];
    for (const c of pts || []) {
      if (!c) continue;
      for (const k of ['low', 'high', 'close', 'value']) {
        const v = Number(c[k]);
        if (Number.isFinite(v)) vals.push(v);
      }
    }
    return vals;
  }

  function zSeriesAutoscaleProvider() {
    const vals = chartSpreadValues(lastCorridorAutoscalePts || lastPaintZData);
    let minV = vals.length ? vals[0] : 0;
    let maxV = vals.length ? vals[0] : 2;
    for (let i = 1; i < vals.length; i += 1) {
      if (vals[i] < minV) minV = vals[i];
      if (vals[i] > maxV) maxV = vals[i];
    }
    const cb = activeCorridorBounds;
    if (cb && Number.isFinite(cb.lo) && Number.isFinite(cb.hi)) {
      minV = Math.min(minV, cb.lo, cb.hi);
      maxV = Math.max(maxV, cb.lo, cb.hi);
    }
    const span = Math.max(maxV - minV, 0.5);
    const pad = Math.max(0.25, span * 0.08);
    return {
      priceRange: {
        minValue: minV - pad,
        maxValue: maxV + pad,
      },
    };
  }

  function applyZSeriesCorridorAutoscale() {
    if (!D().zSeries) return;
    try {
      D().zSeries.applyOptions({ autoscaleInfoProvider: zSeriesAutoscaleProvider });
    } catch (_) { /* ignore */ }
  }

  function refreshZPriceScaleAfterCorridor() {
    if (!D().zChart || !D().zSeries) return;
    applyZSeriesCorridorAutoscale();
    if (!D().activeCorridorBounds) return;
    try {
      D().zChart.priceScale('right').applyOptions({ autoScale: true });
    } catch (_) { /* ignore */ }
    try {
      const pts = lastCorridorAutoscalePts || lastPaintZData;
      if (!pts || !pts.length) return;
      const tail = pts[pts.length - 1];
      if (D().zSeriesIsLine) {
        const v = tail.close != null ? tail.close : tail.value;
        D().zSeries.update({ time: tail.time, value: v });
      } else {
        D().zSeries.update(tail);
      }
    } catch (_) { /* ignore */ }
  }

  function clearCorridorChartSeries() {
    if (!D().zChart) return;
    for (const s of D().corridorChartSeries) {
      try { D().zChart.removeSeries(s); } catch (_) { /* ignore */ }
    }
    D().corridorChartSeries = [];
  }

  function buildDayRangeMap(candlePts) {
    const map = new Map();
    for (const p of candlePts || []) {
      if (!p || p.time == null) continue;
      const d = new Date(p.time * 1000).toLocaleDateString('sv-SE', { timeZone: MSK });
      const prev = map.get(d);
      if (!prev) map.set(d, { from: p.time, to: p.time });
      else {
        if (p.time < prev.from) prev.from = p.time;
        if (p.time > prev.to) prev.to = p.time;
      }
    }
    return map;
  }

  /** Строго возрастающее время — иначе Lightweight Charts молча ломает setData. */
  function sanitizeCorridorPoints(pts) {
    const out = [];
    for (const p of pts || []) {
      if (!p || p.time == null || !Number.isFinite(Number(p.value))) continue;
      const t = Number(p.time);
      const v = Number(p.value);
      if (!Number.isFinite(t) || t <= 1e9) continue;
      if (!out.length) {
        out.push({ time: t, value: v });
        continue;
      }
      const prev = out[out.length - 1];
      if (t < prev.time) continue;
      if (t === prev.time) {
        prev.value = v;
        continue;
      }
      out.push({ time: t, value: v });
    }
    return out;
  }

  function corridorLineStyle(phaseStyle) {
    const raw = phaseStyle && phaseStyle.lineStyle;
    const LS = (typeof LightweightCharts !== 'undefined' && LightweightCharts.LineStyle)
      ? LightweightCharts.LineStyle
      : null;
    if (raw === 'dotted' || raw === 1) return LS ? LS.Dotted : 1;
    if (raw === 'dashed' || raw === 2) return LS ? LS.Dashed : 2;
    if (raw === 'largeDashed' || raw === 3) return LS ? LS.LargeDashed : 3;
    return LS ? LS.Solid : 0;
  }

  function corridorLineTypeSteps() {
    if (typeof LightweightCharts !== 'undefined' && LightweightCharts.LineType) {
      return LightweightCharts.LineType.WithSteps;
    }
    return 1;
  }

  /**
   * Step-точки границ по дням сегмента.
   * extendToLive — только для последнего сегмента (иначе оранжевый тянется до «сейчас»).
   */
  function buildCorridorBoundPoints(rows, dayRangeMap, key, chartFirstSec, chartLastSec, liveVal, extendToLive) {
    const pts = [];
    const chartStart = Number(chartFirstSec);
    for (const row of rows || []) {
      const dkey = String(row.date || '').slice(0, 10);
      const range = dayRangeMap && dayRangeMap.get(dkey);
      if (!range) continue;
      if (range.to < chartStart) continue;
      const val = Number(row[key]);
      if (!Number.isFinite(val)) continue;
      let t0 = range.from;
      let t1 = range.to;
      if (t0 < chartStart) t0 = chartStart;
      if (t1 < t0) t1 = t0;
      pts.push({ time: t0, value: val });
      if (t1 > t0) pts.push({ time: t1, value: val });
    }
    if (extendToLive && Number.isFinite(liveVal) && Number.isFinite(chartLastSec)) {
      if (!pts.length) {
        pts.push({ time: chartFirstSec, value: liveVal });
        pts.push({ time: chartLastSec, value: liveVal });
      } else if (pts[pts.length - 1].time < chartLastSec) {
        pts.push({ time: chartLastSec, value: liveVal });
      } else {
        pts[pts.length - 1].value = liveVal;
      }
    }
    return sanitizeCorridorPoints(pts);
  }

  function splitCorridorHistory(history) {
    const segments = [];
    let cur = null;
    for (const row of history || []) {
      const ph = row && row.phase;
      if (ph !== 'forming' && ph !== 'formed') {
        if (cur) { segments.push(cur); cur = null; }
        continue;
      }
      if (!cur || cur.phase !== ph) {
        if (cur) segments.push(cur);
        cur = { phase: ph, rows: [] };
      }
      cur.rows.push(row);
    }
    if (cur) segments.push(cur);
    return segments;
  }

  /**
   * На графике: реальная фаза «формируется» + первые 2 дня «сформирован»
   * рисуем оранжевым, чтобы переход к зелёному был виден глазу.
   */
  function corridorRowsForChart(history) {
    const out = [];
    let formedRun = 0;
    for (const row of history || []) {
      const ph = row && row.phase;
      if (ph !== 'forming' && ph !== 'formed') {
        formedRun = 0;
        continue;
      }
      let draw = ph;
      if (ph === 'formed') {
        formedRun += 1;
        if (formedRun <= 2) draw = 'forming';
      } else {
        formedRun = 0;
        draw = 'forming';
      }
      out.push(Object.assign({}, row, { phase: draw }));
    }
    return out;
  }

  function paintCorridorOnChart(corridor, candlePts) {
    const ph = String(corridor && corridor.phase || '');
    // none / broken / пусто — без линий и маркеров «Ф»/«С» на графике.
    if (corridorPhaseAbsent(ph) || (ph !== 'forming' && ph !== 'formed')) {
      D().activeCorridorBounds = null;
      D().lastCorridorAutoscalePts = null;
      clearCorridorChartSeries();
      applyZSeriesCorridorAutoscale();
      return;
    }
    const lo = Number(corridor.lo);
    const hi = Number(corridor.hi);
    D().activeCorridorBounds = (Number.isFinite(lo) && Number.isFinite(hi))
      ? { lo, hi }
      : null;
    D().lastCorridorAutoscalePts = candlePts;
    D().updateCorridorOnChart(corridor, candlePts);
    refreshZPriceScaleAfterCorridor();
  }

  function updateCorridorOnChart(corridor, candlePts) {
    clearCorridorChartSeries();
    if (!D().zChart || !candlePts || !candlePts.length) return;

    const livePhase = String(corridor && corridor.phase || '');
    const liveLo = Number(corridor.lo);
    const liveHi = Number(corridor.hi);
    if (!Number.isFinite(liveLo) || !Number.isFinite(liveHi)) return;
    if (livePhase !== 'forming' && livePhase !== 'formed') return;

    const firstSec = candlePts[0].time;
    const lastSec = candlePts[candlePts.length - 1].time;
    const dayRangeMap = buildDayRangeMap(candlePts);

    let rows = [...((corridor && corridor.history) || [])];
    const today = corridor.last_date
      || new Date().toLocaleDateString('sv-SE', { timeZone: MSK });
    const todayRow = { date: today, lo: liveLo, hi: liveHi, phase: livePhase };
    const lastRow = rows[rows.length - 1];
    if (lastRow && lastRow.date === today) rows[rows.length - 1] = todayRow;
    else rows.push(todayRow);

    const segments = splitCorridorHistory(corridorRowsForChart(rows));
    if (!segments.length) return;

    const mkSeries = (style) => addSeries(zChart, 'LineSeries', {
      color: style.color,
      lineWidth: style.width,
      lineStyle: corridorLineStyle(style),
      lineType: corridorLineTypeSteps(),
      lastValueVisible: false,
      priceLineVisible: false,
      crosshairMarkerVisible: false,
      autoscaleInfoProvider: () => null,
    });

    const paint = (style, data) => {
      if (!data || data.length < 2) return null;
      const s = mkSeries(style);
      if (!s) return null;
      try {
        s.setData(data);
      } catch (err) {
        try { D().zChart.removeSeries(s); } catch (_) { /* ignore */ }
        console.warn('corridor setData failed', err);
        return null;
      }
      D().corridorChartSeries.push(s);
      return s;
    };

    let anyLo = false;
    let anyHi = false;
    let formingSegs = 0;
    let formedSegs = 0;
    segments.forEach((seg, idx) => {
      const style = CORRIDOR_CHART_STYLE[seg.phase];
      if (!style) return;
      const isLast = idx === segments.length - 1;
      const loData = buildCorridorBoundPoints(
        seg.rows, dayRangeMap, 'lo', firstSec, lastSec, liveLo, isLast,
      );
      const hiData = buildCorridorBoundPoints(
        seg.rows, dayRangeMap, 'hi', firstSec, lastSec, liveHi, isLast,
      );
      const loS = paint(style, loData);
      const hiS = paint(style, hiData);
      if (loS) anyLo = true;
      if (hiS) anyHi = true;
      if (seg.phase === 'forming') formingSegs += 1;
      if (seg.phase === 'formed') formedSegs += 1;
      if (loS && loData[0] && idx === 0) {
        try {
          loS.setMarkers([{
            time: loData[0].time,
            position: 'aboveBar',
            color: style.color,
            shape: 'circle',
            text: style.marker || '',
          }]);
        } catch (_) { /* ignore */ }
      }
    });

    try {
      window.__corridorChartDebug = {
        phase: livePhase,
        liveLo,
        liveHi,
        segments: segments.map((s) => ({ phase: s.phase, days: s.rows.length })),
        formingSegs,
        formedSegs,
        loOk: anyLo,
        hiOk: anyHi,
        ts: Date.now(),
      };
    } catch (_) { /* ignore */ }
  }

  function corridorFingerprint(corridor) {
    if (!corridor) return 'none';
    const h = corridor.history || [];
    const tail = h.length ? h[h.length - 1] : null;
    return [
      corridor.phase,
      corridor.lo,
      corridor.hi,
      corridor.dwell_days,
      tail && tail.date,
      tail && tail.phase,
      h.length,
    ].join('|');
  }

  let chartPrimarySpread = false;
  /** Одинаковая ширина правой шкалы → одинаковая plot-area → вертикальный кроссхейр */
  const PRICE_SCALE_MIN_WIDTH = 64;

  const $ = (id) => document.getElementById(id);


  function corridorStatusBadge(corridor, spreadPct, barsIss) {
    let c = corridor;
    if (!c || c.phase == null) {
      c = detectSpreadCorridorClient(barsIss, spreadPct);
    }
    if (!c) return '';
    const phase = String(c.phase || 'none');
    // Нет / сломан: плашку не показываем (иначе «коридор · сломан» выглядит как активный индикатор).
    if (corridorPhaseAbsent(phase)) return '';
    const meta = CORRIDOR_PHASE_META[phase] || CORRIDOR_PHASE_META.none;
    const label = c.label_ru || meta.badge;
    const title = corridorPhaseStatus(phase) || c.title || meta.status || '';
    return (
      `<span class="badge-corridor badge-corridor-${D().escapeHtml(phase)}" title="${D().escapeHtml(String(title))}">`
      + `коридор · ${D().escapeHtml(String(label))}</span>`
    );
  }
  global.__TradeDesk = global.__TradeDesk || { deps: {} };
  global.__TradeDesk.corridor = {
    corridorPhaseAbsent: corridorPhaseAbsent,
    detectSpreadCorridorClient: detectSpreadCorridorClient,
    renderCorridorMeter: renderCorridorMeter,
    updateCorridorOnChart: updateCorridorOnChart,
    paintCorridorOnChart: paintCorridorOnChart,
    clearCorridorChartSeries: clearCorridorChartSeries,
    corridorFingerprint: corridorFingerprint,
    corridorStatusBadge: corridorStatusBadge,
    applyZSeriesCorridorAutoscale: applyZSeriesCorridorAutoscale,
    refreshZPriceScaleAfterCorridor: refreshZPriceScaleAfterCorridor
  };
})(typeof window !== 'undefined' ? window : globalThis);
