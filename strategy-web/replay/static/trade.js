/** Торговля — chart + open trade panel (desk) */
(function () {
  let days = 7;
  let pollTimer = null;
  let formHydrated = false;
  let zChart = null;
  let zSeries = null;
  let spreadChart = null;
  let spreadSeries = null;
  let priceLines = [];

  const $ = (id) => document.getElementById(id);

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

  function toChartTime(tradeDate) {
    if (!tradeDate) return null;
    const s = String(tradeDate).replace(' ', 'T');
    const ms = Date.parse(s.length === 10 ? s + 'T00:00:00' : s);
    return Number.isFinite(ms) ? Math.floor(ms / 1000) : null;
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
        timeScale: { borderColor: '#2a2e39', timeVisible: true, secondsVisible: false },
        width: zEl.clientWidth,
        height: zEl.clientHeight || 300,
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
        timeScale: { borderColor: '#2a2e39', timeVisible: true, secondsVisible: false },
        width: sEl.clientWidth,
        height: sEl.clientHeight || 150,
      });
      spreadSeries = addSeries(spreadChart, 'LineSeries', { color: '#f0b90b', lineWidth: 2 });
    }
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

  function renderCharts(bars, entry, exitZ) {
    ensureCharts();
    if (!zSeries || !spreadSeries) return;
    const zCandles = [];
    const spreadPts = [];
    let prevZ = null;
    const seen = new Set();
    for (const b of bars) {
      const t = toChartTime(b.time);
      if (t == null || b.z == null || seen.has(t)) continue;
      seen.add(t);
      const z = Number(b.z);
      const open = prevZ == null ? z : prevZ;
      zCandles.push({ time: t, open, high: Math.max(open, z), low: Math.min(open, z), close: z });
      if (b.spread != null) spreadPts.push({ time: t, value: Number(b.spread) });
      prevZ = z;
    }
    try {
      try { zSeries.setData(zCandles); }
      catch { zSeries.setData(zCandles.map((c) => ({ time: c.time, value: c.close }))); }
      spreadSeries.setData(spreadPts);
      setThresholdLines(entry, exitZ);
      zChart?.timeScale().fitContent();
      spreadChart?.timeScale().fitContent();
    } catch (e) {
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

  function renderDesk(data, { hydrateForm = false } = {}) {
    const s = data.summary || {};
    const settings = data.settings || {};
    const mon = data.monitor || {};
    const modeRu = settings.mode === 'prod' ? 'Prod' : 'Sandbox';
    const monRu = mon.running ? 'монитор ON' : 'монитор OFF';
    const autoRu = settings.auto_execute ? 'auto ON' : 'auto OFF';
    const pos = data.position || 'FLAT';

    $('tradeStatus').textContent =
      `${s.trade_date || '—'} · Z ${fmt(s.z)} · спред ${fmt(s.spread)}% · ${pos} · ` +
      `TATN ${fmt(s.tatn)} / TATNP ${fmt(s.tatnp)} · ${monRu} · ${autoRu} · ${modeRu}`;

    $('tradeMeta').textContent =
      `Торговля · ${s.window_count || 0} баров · ${s.source || ''}${s.online ? ' · online' : ''}`;

    $('tradeThreshLabel').textContent =
      `пороги ±${fmt(settings.entry_z, 2)} / ±${fmt(settings.exit_z, 2)}`;

    $('tradeStrip').innerHTML =
      `<span><b>Z</b> ${fmt(s.z)}</span>` +
      `<span><b>Спред</b> ${fmt(s.spread)}%</span>` +
      `<span><b>Поз.</b> ${pos}</span>` +
      `<span><b>TATN</b> ${fmt(s.tatn)}</span>` +
      `<span><b>TATNP</b> ${fmt(s.tatnp)}</span>`;

    $('tradeSideStatus').textContent =
      `${pos} · ${monRu} · ${autoRu} · ${modeRu}`;

    if (hydrateForm || !formHydrated) {
      if (settings.entry_z != null) $('tradeEntryZ').value = settings.entry_z;
      if (settings.exit_z != null) $('tradeExitZ').value = settings.exit_z;
      if (settings.leverage != null) $('tradeLeverage').value = settings.leverage;
      $('tradeAutoExec').checked = !!settings.auto_execute;
      formHydrated = true;
    }

    renderOpen(data.open);

    const b = data.broker;
    if (!b) $('tradeBrokerBox').textContent = 'Брокер: нет токена — вкладка «Счёт»';
    else if (b.error) $('tradeBrokerBox').textContent = `Брокер: ${b.error}`;
    else $('tradeBrokerBox').textContent =
      `Брокер [${b.mode}]: ${fmt(b.total_rub)} ₽ · cash ${fmt(b.cash_rub)} ₽`;

    $('tradeMonMsg').textContent = mon.last_message || '';

    const entry = Number(settings.entry_z) || 1.3;
    const exitZ = Number(settings.exit_z) || 1.2;
    renderCharts(data.bars || [], entry, exitZ);
  }

  async function refresh({ hydrateForm = false, forceMoex = false } = {}) {
    if (forceMoex) {
      await api(`/api/markets/refresh?days=${days}`, { method: 'POST' });
    }
    const data = await api(`/api/trade/desk?days=${days}`);
    renderDesk(data, { hydrateForm });
  }

  async function saveParams() {
    await api('/api/portfolio/params', {
      method: 'POST',
      body: JSON.stringify({
        entry_z: parseFloat($('tradeEntryZ').value),
        exit_z: parseFloat($('tradeExitZ').value),
        leverage: parseFloat($('tradeLeverage').value),
        auto_execute: $('tradeAutoExec').checked,
      }),
    });
    await refresh({ hydrateForm: true });
  }

  function resize() {
    const zEl = $('tradeZChart');
    const sEl = $('tradeSpreadChart');
    if (zChart && zEl) zChart.applyOptions({ width: zEl.clientWidth, height: zEl.clientHeight || 300 });
    if (spreadChart && sEl) spreadChart.applyOptions({ width: sEl.clientWidth, height: sEl.clientHeight || 150 });
  }

  function startPoll() {
    stopPoll();
    pollTimer = setInterval(() => { refresh().catch(() => {}); }, 12000);
  }

  function stopPoll() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  }

  function onShow() {
    formHydrated = false;
    ensureCharts();
    resize();
    refresh({ hydrateForm: true }).then(() => startPoll()).catch((e) => {
      $('tradeStatus').textContent = `Ошибка: ${e.message}`;
    });
    requestAnimationFrame(resize);
  }

  function onHide() { stopPoll(); }

  function bind() {
    document.querySelectorAll('#tradePeriodChips .chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('#tradePeriodChips .chip').forEach((b) => b.classList.remove('active'));
        btn.classList.add('active');
        days = parseInt(btn.dataset.days, 10) || 7;
        refresh().catch((e) => alert(e.message));
      });
    });
    $('tradeBtnRefresh')?.addEventListener('click', () => {
      refresh({ forceMoex: true }).catch((e) => alert(e.message));
    });
    $('tradeBtnSaveParams')?.addEventListener('click', () => {
      saveParams().catch((e) => alert(e.message));
    });
    $('tradeAutoExec')?.addEventListener('change', () => {
      api('/api/portfolio/params', {
        method: 'POST',
        body: JSON.stringify({ auto_execute: $('tradeAutoExec').checked }),
      }).then(() => refresh()).catch((e) => alert(e.message));
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
  }

  window.MoexTrade = { onShow, onHide, refresh, bind, resize };
  // alias for old name
  window.MoexMarkets = window.MoexTrade;

  document.addEventListener('DOMContentLoaded', bind);
})();
