/** Async tip1m sim poll — split from app.js */
(function (global) {
  'use strict';
  function D() { return global.__TradeDesk.deps; }
  const TIP1M_CHART_MAX_POINTS = 150000;

  function thinTip1mBarsForChart(bars) {
    const raw = Array.isArray(bars) ? bars : [];
    const n = raw.length;
    if (n <= TIP1M_CHART_MAX_POINTS) {
      return { bars: raw, stepMin: 1 };
    }
    let minutes = 5;
    if (Math.ceil(n / 5) > TIP1M_CHART_MAX_POINTS) minutes = 15;
    if (Math.ceil(n / 15) > TIP1M_CHART_MAX_POINTS) minutes = 30;
    return { bars: D().aggregateTipBarsByMinutes(raw, minutes), stepMin: minutes };
  }

  function tip1mBarsRequestKey() {
    return [
      D().$('csvSel')?.value || '',
      D().$('startDate')?.value || '',
      D().readWindowEndYmd(),
      String(D().tip1mChartDaysWanted()),
      'final',
    ].join('|');
  }

  function tip1mWindowSpanDays() {
    const start = $('startDate')?.value || '';
    const startMs = startYmdToMs(start);
    if (!Number.isFinite(startMs)) return 0;
    const endYmd = readWindowEndYmd();
    const endMs = Number.isFinite(endYmdToMs(endYmd)) ? endYmdToMs(endYmd) : Date.now();
    return Math.max(1, Math.ceil((endMs - startMs) / 86_400_000) + 1);
  }

  function tip1mFetchTimeoutMs(csv, chartDays) {
    const name = String(csv || '');
    const d = Number(chartDays) || 0;
    const span = d > 0 ? d : tip1mWindowSpanDays();
    // CSV «3 года» + окно 3мес не должен брать 90с refresh MOEX.
    if (span > 0 && span < 200) return 180000;
    if (name.includes('1095') || /_3y/i.test(name) || span >= 900) return 90000;
    if (name.includes('365') || span >= 300) return 60000;
    return 45000;
  }

  /** Сим / heatmap: не общий 90с refresh MOEX. 3мес и 3г могут идти минуты. */
  function tip1mSimTimeoutMs() {
    return 600000;
  }

  /** Poll async tip1m sim job (long windows >90d). */
  async function pollTipSimJob(serverJobId, clientJobId, started, timeoutMs) {
    const pollMs = 600;
    while (Date.now() - started < timeoutMs) {
      if (clientJobId !== tipSimJobId) return null;
      await new Promise((r) => setTimeout(r, pollMs));
      if (clientJobId !== tipSimJobId) return null;
      const sr = await fetch(`/api/sim/tip1m/status/${encodeURIComponent(serverJobId)}`, {
        signal: AbortSignal.timeout(8000),
      });
      if (!sr.ok) {
        const errText = await sr.text();
        throw new Error(errText || sr.statusText);
      }
      const status = await sr.json();
      if (status.status === 'done' && status.result) return status.result;
      if (status.status === 'error') {
        throw new Error(status.error || 'tip1m sim failed');
      }
    }
    const err = new Error(`таймаут ${Math.round(timeoutMs / 1000)} с`);
    err.name = 'AbortError';
    throw err;
  }

  async function ensureTestDeskCorridor({ force = false } = {}) {
    if (!TEST_DRAW_CORRIDOR) return null;
    const now = Date.now();
    if (!force && testDeskCorridor && (now - testDeskCorridorTs) < 60000) {
      return testDeskCorridor;
    }
    try {
      const res = await fetch('/api/trade/desk?days=30&lite=1', { signal: AbortSignal.timeout(20000) });
      if (!res.ok) return testDeskCorridor;
      const data = await res.json();
      if (data && data.corridor) {
        testDeskCorridor = data.corridor;
        testDeskCorridorTs = Date.now();
      }
    } catch (_) { /* ignore */ }
    return testDeskCorridor;
  }

  async function loadTip1mChartBars({ force = false } = {}) {
    const key = tip1mBarsRequestKey();
    if (!force && key === tip1mBarsCacheKey && tip1mChartMeta && allPoints.length
      && tip1mChartMeta._active) {
      return { bars: allPoints, meta: tip1mChartMeta, fromCache: true };
    }
    const jobId = ++tip1mChartJobId;
    const csv = $('csvSel')?.value || 'm15_tatn_255d.csv';
    const start = $('startDate')?.value || '';
    const end = readWindowEndYmd();
    const chartDays = tip1mChartDaysWanted();
    let url = `/api/bars1m?csv=${encodeURIComponent(csv)}&chartDays=${chartDays}`;
    if (start) url += `&start=${encodeURIComponent(start)}`;
    if (end) url += `&end=${encodeURIComponent(end)}`;
    const timeoutMs = tip1mFetchTimeoutMs(csv, chartDays);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    let res;
    try {
      res = await fetch(url, { signal: controller.signal });
    } catch (e) {
      if (e && e.name === 'AbortError') {
        throw new Error(
          `Таймаут графика 1м (${Math.round(timeoutMs / 1000)} с). Обновите страницу или перезапустите сервис.`,
        );
      }
      throw e;
    } finally {
      clearTimeout(timer);
    }
    if (!res.ok) {
      const errText = await res.text();
      throw new Error(errText || res.statusText);
    }
    const data = await res.json();
    if (jobId !== tip1mChartJobId) return null;
    if (!data.ok && !(data.bars && data.bars.length)) {
      throw new Error(data.hintRu || 'Нет 1м баров для графика');
    }
    const rawBars = data.bars || [];
    const thinned = thinTip1mBarsForChart(rawBars);
    const stepMin = Number(data.displayStepMin) > 1
      ? Number(data.displayStepMin)
      : thinned.stepMin;
    const appliedDays = data.chartDays != null ? data.chartDays : chartDays;
    let hintRu = data.hintRu || null;
    if (stepMin > 1) {
      hintRu = (
        `График за выбранное окно (~${appliedDays}д), шаг ${stepMin} мин `
        + `(минутки не рисуем: ${rawBars.length} точек слишком тяжело для экрана). `
        + `Симуляция на сервере — полный период.`
      );
    }
    tip1mBarsCacheKey = key;
    tip1mChartMeta = {
      count: thinned.bars.length,
      rawCount: rawBars.length,
      fullTipCount: data.fullTipCount,
      chartLimited: !!data.chartLimited,
      chartDays: appliedDays,
      displayStepMin: stepMin,
      hintRu,
      first: data.first,
      last: data.last,
      corridor: TEST_DRAW_CORRIDOR ? (data.corridor || null) : null,
      corridor_wide: TEST_DRAW_WIDE_CORRIDOR ? (data.corridor_wide || null) : null,
      _active: true,
    };
    if (TEST_DRAW_CORRIDOR && !tip1mChartMeta.corridor) {
      ensureTestDeskCorridor({ force: true }).then((c) => {
        if (c && tip1mChartMeta) {
          tip1mChartMeta.corridor = tip1mChartMeta.corridor || c;
          if (D().isChartsHidden()) {
            pendingChartRepaint = true;
            return;
          }
          try { D().refreshUi({ light: true }); } catch (_) { /* ignore */ }
        }
      });
    }
    return { bars: thinned.bars, meta: tip1mChartMeta, fromCache: false };
  }


  async function fetchTipSim() {
    if (!D().isTip1mMode()) return;
    const key = tipSimRequestKey();
    if (key === tipSimCache.key && tipSimCache.rows) {
      D().refreshTradesTable();
      return;
    }
    const jobId = ++tipSimJobId;
    D().markMonthlyPnlPending();
    const st = $('status');
    if (st && !tipSimCache.rows) {
      st.textContent = `${D().isWeekendTradingMode() ? 'выходные · ' : ''}касание 1м · считаю…`;
    }
    const t = thresholds();
    const csv = $('csvSel')?.value || 'm15_tatn_255d.csv';
    const body = {
      csv,
      start: D().$('startDate')?.value || null,
      end: D().readWindowEndYmd() || null,
      entry: t.entry,
      exit: t.exit,
      slip: typeof getSimSlippageSpreadPts === 'function' ? getSimSlippageSpreadPts() : 0.02,
      notional: D().getSimNotionalRub(),
      compound: D().getSimCompound(),
      takeProfitPct: t.takeProfitPct || 0,
      maxHoldDaysNoExitTrend: readHoldParams().maxHoldDaysNoExitTrend,
      maxHoldDaysIfLosing: readHoldParams().maxHoldDaysIfLosing,
      as_live: 0,
      regime_z_mode: 0,
      spread_level_mode: 1,
      spread_levels: readHmSelectedSpreadLevels(),
      base: isBaseMode(),
      enable_base: isBaseMode() ? 1 : 0,
      baseMode: isBaseMode() ? 1 : 0,
      addon_mode: isAddon27Mode() ? 1 : 0,
      extreme_addon_mode: isExtremeAddonMode() ? 1 : 0,
      weekend_trading: D().isWeekendTradingMode(),
      transition_swing_mode: isZoneSwingMode() ? 1 : 0,
      adaptive_corridor_mode: isAdaptCorridorMode() ? 1 : 0,
      shelf_floor_ceiling_mode: isShelfFloorCeilingMode() ? 1 : 0,
    };
    const timeoutMs = tip1mSimTimeoutMs();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const started = Date.now();
    const timeoutSec = Math.round(timeoutMs / 1000);
    let busyLive = true;
    let asyncJobId = null;
    const tickBusy = async () => {
      if (!busyLive || jobId !== tipSimJobId) return;
      const sec = Math.round((Date.now() - started) / 1000);
      let phase = '';
      try {
        const br = await fetch('/api/tip/busy', { signal: AbortSignal.timeout(800) });
        if (br.ok) {
          const b = await br.json();
          if (b && b.phaseRu && b.phase !== 'idle') {
            phase = ` · ${b.phaseRu}`;
            if (b.tipBuildLock) phase += ' · ждёт лок 3г';
          }
        }
      } catch (_) { /* health/busy не должен ронять сим */ }
      const asyncNote = asyncJobId ? ' · фоновая задача' : '';
      const line = `${isWeekendTradingMode() ? 'выходные · ' : ''}касание 1м · считаю… ${sec}/${timeoutSec} с${asyncNote}${phase}`;
      if (st && !tipSimCache.rows) st.textContent = line;
      const grid = $('tradesSummaryGrid');
      if (grid && !tipSimCache.rows) {
        grid.innerHTML = `<div class="trades-summary-note wide">${line}</div>`;
      }
    };
    tickBusy();
    const tickTimer = setInterval(tickBusy, 500);
    const stopBusyTicks = () => {
      busyLive = false;
      clearInterval(tickTimer);
    };
    try {
      const res = await fetch('/api/sim/tip1m', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
      if (!res.ok) {
        const errText = await res.text();
        throw new Error(errText || res.statusText);
      }
      let data = await res.json();
      if (data && data.async && data.job_id) {
        asyncJobId = data.job_id;
        tickBusy();
        data = await pollTipSimJob(data.job_id, jobId, started, timeoutMs);
        if (!data) return;
      }
      if (jobId !== tipSimJobId) return;
      const entryTh = t.entry;
      const rows = (data.trades || []).map((tr) => tipTradeToRow(tr, entryTh));
      if (key !== tipSimCache.key) D().clearTipManualOverrides();
      tipSimCache = {
        key,
        rows,
        meta: data.meta || null,
        summary: data.summary || null,
      };
      stopBusyTicks();
      D().refreshTradesTable();
      D().refreshUi({ afterParams: true });
    } catch (e) {
      if (jobId !== tipSimJobId) return;
      const timedOut = e && e.name === 'AbortError';
      const msg = timedOut
        ? `таймаут ${Math.round(timeoutMs / 1000)} с — сервер не ответил (перезапустите сервис / F5)`
        : ((e && e.message) ? String(e.message).slice(0, 180) : String(e));
      if (!timedOut) {
        tipSimCache = { key: '', rows: null, meta: null, summary: null };
        D().clearTipManualOverrides();
      }
      if (st) {
        st.textContent = `${D().isWeekendTradingMode() ? 'выходные · ' : ''}касание 1м · ошибка: ${msg}`;
      }
      const grid = $('tradesSummaryGrid');
      if (grid) {
        grid.innerHTML =
          `<div class="trades-summary-note wide">касание 1м · ошибка: ${msg}</div>`;
      }
      if (!timedOut) alert('Касание 1м (сервер): ' + msg);
    } finally {
      stopBusyTicks();
      clearTimeout(timer);
    }
  }


  function thinTip1mBarsForChart(bars) {
    const raw = Array.isArray(bars) ? bars : [];
    const n = raw.length;
    if (n <= TIP1M_CHART_MAX_POINTS) {
      return { bars: raw, stepMin: 1 };
    }
    let minutes = 5;
    if (Math.ceil(n / 5) > TIP1M_CHART_MAX_POINTS) minutes = 15;
    if (Math.ceil(n / 15) > TIP1M_CHART_MAX_POINTS) minutes = 30;
    return { bars: D().aggregateTipBarsByMinutes(raw, minutes), stepMin: minutes };
  }

  function tip1mBarsRequestKey() {
    return [
      D().$('csvSel')?.value || '',
      D().$('startDate')?.value || '',
      D().readWindowEndYmd(),
      String(D().tip1mChartDaysWanted()),
      'final',
    ].join('|');
  }

  function tip1mWindowSpanDays() {
    const start = $('startDate')?.value || '';
    const startMs = startYmdToMs(start);
    if (!Number.isFinite(startMs)) return 0;
    const endYmd = readWindowEndYmd();
    const endMs = Number.isFinite(endYmdToMs(endYmd)) ? endYmdToMs(endYmd) : Date.now();
    return Math.max(1, Math.ceil((endMs - startMs) / 86_400_000) + 1);
  }

  function tip1mFetchTimeoutMs(csv, chartDays) {
    const name = String(csv || '');
    const d = Number(chartDays) || 0;
    const span = d > 0 ? d : tip1mWindowSpanDays();
    // CSV «3 года» + окно 3мес не должен брать 90с refresh MOEX.
    if (span > 0 && span < 200) return 180000;
    if (name.includes('1095') || /_3y/i.test(name) || span >= 900) return 90000;
    if (name.includes('365') || span >= 300) return 60000;
    return 45000;
  }

  function scheduleTipSimFetch({ immediate = false } = {}) {
  global.__TradeDesk = global.__TradeDesk || { deps: {} };
  global.__TradeDesk.sim = {
    pollTipSimJob: pollTipSimJob,
    tip1mSimTimeoutMs: tip1mSimTimeoutMs,
    loadTip1mChartBars: loadTip1mChartBars,
    ensureTestDeskCorridor: ensureTestDeskCorridor,
    fetchTipSim: fetchTipSim
  };
})(typeof window !== 'undefined' ? window : globalThis);
