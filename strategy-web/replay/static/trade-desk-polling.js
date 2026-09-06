/** Desk poll / refresh — split from trade.js */
(function (global) {
  'use strict';
  function D() { return global.__TradeDesk.deps; }
  async function ensureMonitorRunning(data) {
    const settings = data.settings || {};
    const mon = data.monitor || {};
    if (settings.monitor_running && !mon.running) {
      try {
        await D().api('/api/live/monitor/start', { method: 'POST' });
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

  function spreadBarCount(bars) {
    let n = 0;
    for (const b of bars || []) {
      if (!b || b.spread == null) continue;
      if (Number.isFinite(Number(b.spread))) n += 1;
    }
    return n;
  }

  function rememberGoodChartBars(bars, data) {
    const n = dealerHistBarCount(bars);
    const spreadN = spreadBarCount(bars);
    const weekend = !!(data && (data.weekend_monitor
      || String(data.bars_mode || '').startsWith('dealer')
      || (typeof nowMskParts === 'function' && D().nowMskParts().weekend)));
    // ≥5 баров со спредом (не «длина массива» и не только tinvest_dealer_1m) —
    // иначе lastGood пуст / затирается мусором и следующий poll стирает график.
    if (n >= 5 || spreadN >= 5) {
      lastGoodChartBars = bars.slice();
      lastGoodDeskMeta = {
        bars_mode: data && data.bars_mode,
        weekend_monitor: !!(data && data.weekend_monitor) || weekend,
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

  async function refreshImpl({ hydrateForm = false, forceMoex = false, lite = false } = {}) {
    if (forceMoex) {
      await D().api(`/api/markets/refresh?days=${days}`, { method: 'POST', timeoutMs: 20000 });
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
              settings: D().loadCachedParamsLocal() || {},
              summary: {},
              position: 'FLAT',
              partial: true,
              tip1m_warming: true,
            });
            if (stub && (stub.bars || []).length) {
              rememberGoodChartBars(stub.bars || [], stub);
              D().renderDesk(stub, { hydrateForm: false });
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
    if (!lite && await D().ensureMonitorRunning(data)) {
      data = await D().api(`/api/trade/desk?days=${days}`, { timeoutMs: 20000 });
      if (seq !== deskFetchSeq) return data;
    }
    // Weekday: never paint iss_m15 under tip1m labels (sidecar / bars1m upgrade).
    // Keep chip days even if running API still coerces unknown windows → 7.
    data.days = days;
    data = await D().ensureWeekdayTip1mBars(data);
    if (seq !== deskFetchSeq) return data;
    // Короткий lite-ответ не должен стирать уже загруженный длинный период.
    // Это особенно заметно на 3М: после полного ответа следующий опрос мог
    // заменить 5 000 точек одной текущей свечой.
    if (lite && days > 1) {
      const needSpan = Math.min(days * 0.45, days - 0.4);
      const currentSpan = tip1mSpanDays(data.bars);
      const savedSpan = tip1mSpanDays(lastGoodChartBars);
      if (currentSpan < needSpan && savedSpan >= needSpan) {
        data = {
          ...data,
          bars: lastGoodChartBars,
          bars_mode: 'tip1m',
          chart_preserved_from_full: true,
          summary: {
            ...(data.summary || {}),
            window_count: lastGoodChartBars.length,
          },
        };
      }
    }
    if ((data.bars_mode === 'tip1m' || data.bars_mode === 'dealer_1m') && Array.isArray(data.bars)) {
      const thinned = thinDeskTip1mBars(data.bars);
      if (thinned.bars.length !== data.bars.length) {
        data = {
          ...data,
          bars: thinned.bars,
          chart_raw_count: thinned.rawCount,
          chart_step_min: thinned.stepMin,
          summary: {
            ...(data.summary || {}),
            chart_raw_count: thinned.rawCount,
            chart_step_min: thinned.stepMin,
          },
        };
      }
    }
    if (seq !== deskFetchSeq) return data;
    if (data.mtlr && Array.isArray(data.mtlr.bars) && DESK_MTLR_UI_ENABLED) {
      const mtlrBars = await ensureMtlrChartBars(data.mtlr.bars, days);
      data.mtlr = { ...data.mtlr, bars: mtlrBars };
    }
    if (seq !== deskFetchSeq) return data;
    if (D().pendingPeriodFitDays > 0) {
      const requiredSpan = pendingPeriodFitDays <= 1
        ? 0
        : Math.min(D().pendingPeriodFitDays * 0.45, D().pendingPeriodFitDays - 0.4);
      if (D().tip1mSpanDays(data.bars) >= requiredSpan) {
        D().forceFitContent = true;
        D().pendingPeriodFitDays = 0;
      }
    }
    rememberGoodChartBars(data.bars || [], data);
    // Late responses: never force-hydrate over newer in-flight work
    const hydrate = hydrateForm && seq === deskFetchSeq;
    D().renderDesk(data, { hydrateForm: hydrate });
    ensurePollInterval(data);
    return data;
  }

  async function refresh(opts = {}) {
    if (opts.lite && refreshWorkCount > 0) return lastGoodDeskMeta;
    refreshWorkCount += 1;
    try {
      return await refreshImpl(opts);
    } finally {
      refreshWorkCount = Math.max(0, refreshWorkCount - 1);
    }
  }


  function startPoll(ms) {
    stopPoll();
    if (ms != null && Number.isFinite(ms) && ms >= 2000) pollMs = ms;
    pollTimer = setInterval(() => {
      if (refreshWorkCount > 0) return;
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
  global.__TradeDesk = global.__TradeDesk || { deps: {} };
  global.__TradeDesk.polling = {
    refresh: refresh,
    refreshImpl: refreshImpl,
    startPoll: startPoll,
    stopPoll: stopPoll,
    ensurePollInterval: ensurePollInterval,
    rememberGoodChartBars: rememberGoodChartBars,
    applyPartialBanner: applyPartialBanner
  };
})(typeof window !== 'undefined' ? window : globalThis);
