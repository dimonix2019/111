/** Test tag-share / monthly PnL chips — split from app.js */
(function (global) {
  'use strict';
  function D() { return global.__TradeDesk.deps; }
  const TAG_SHARE_SPEC = [
    { key: 'base', api: 'main', label: 'База', color: '#22d3ee' },
    { key: 'addon', api: 'addon', label: 'добор', color: '#c084fc' },
    { key: 'extra', api: 'extra', label: 'экстра', color: '#a78bfa' },
    { key: 'shelf', api: 'shelf_ff', label: 'полка', color: '#7c3aed' },
    { key: 'weekend', api: 'weekend', label: 'выходные', color: '#f59e0b' },
  ];

  function emptyTagSharePnl() {
    return { base: 0, addon: 0, extra: 0, shelf: 0, weekend: 0 };
  }

  function isWeekendChipEntry(entryDate) {
    const s = String(entryDate || '').replace('T', ' ').trim();
    if (s.length < 10 || s[4] !== '-' || s[7] !== '-') return false;
    const y = Number(s.slice(0, 4));
    const mo = Number(s.slice(5, 7));
    const d = Number(s.slice(8, 10));
    if (!y || !mo || !d) return false;
    const dow = new Date(Date.UTC(y, mo - 1, d, 12, 0, 0)).getUTCDay();
    if (dow !== 0 && dow !== 6) return false;
    if (s.length < 16) return true;
    const hm = s.slice(11, 16);
    return hm >= '10:00' && hm < '19:00';
  }

  function parseByTagPnl(summary) {
    const raw = summary && (summary.by_tag || summary.byTag);
    if (!raw || typeof raw !== 'object') return null;
    const out = emptyTagSharePnl();
    let any = false;
    for (const spec of TAG_SHARE_SPEC) {
      const cell = raw[spec.api] != null ? raw[spec.api] : raw[spec.key];
      if (cell == null) continue;
      const pnl = typeof cell === 'number' ? Number(cell) : Number(cell.pnlRub ?? cell.pnl ?? 0);
      if (Number.isFinite(pnl)) {
        out[spec.key] = pnl;
        any = true;
      }
    }
    return any ? out : null;
  }

  function collectTagShareFromRows(rows) {
    const out = emptyTagSharePnl();
    const weekendOn = typeof isWeekendTradingMode === 'function' && isWeekendTradingMode();
    for (const r of rows || []) {
      if (!r) continue;
      const k = normalizeTradeSrcFilterKey(r);
      if (k !== 'base' && k !== 'addon' && k !== 'extra' && k !== 'shelf') continue;
      let pnl;
      if (r.status === 'Открыта') {
        pnl = Number(r.openMtm != null ? r.openMtm : r.mtm);
      } else if (r.status === 'Закрыта') {
        pnl = Number(r.netValue);
      } else {
        continue;
      }
      if (!Number.isFinite(pnl)) continue;
      if (weekendOn && D().isWeekendChipEntry(r.entryDate)) out.weekend += pnl;
      else out[k] += pnl;
    }
    return out;
  }

  function formatTagShareRub(rub) {
    const n = Number(rub) || 0;
    const sign = n > 0 ? '+' : '';
    const body = typeof formatAccountRub === 'function'
      ? D().formatAccountRub(n)
      : String(Math.round(n));
    return `${sign}${body} ₽`;
  }

  function formatTagSharePct(pct, rub) {
    if (!Number.isFinite(pct)) return '0%';
    if (Math.abs(Number(rub) || 0) < 1e-9 && Math.abs(pct) < 0.5) return '0%';
    if (Math.abs(pct) < 0.5 && Math.abs(Number(rub) || 0) >= 1e-9) return '<1%';
    return `${Math.round(pct)}%`;
  }

  function renderTagShareDonut() {
    const host = $('tagShareDonut');
    if (!host) return;
    const tipRows = tipSimCache.rows
      ? (typeof tipRowsUpToCursor === 'function'
        ? D().tipRowsUpToCursor(tipSimCache.rows)
        : tipSimCache.rows)
      : [];
    const fromRows = collectTagShareFromRows(tipRows);
    const fromApi = parseByTagPnl(tipSimCache.summary);
    const tagMode = String(tipSimCache.summary?.by_tag_mode || '');
    const pnl = { ...(fromApi || fromRows) };
    if (typeof isWeekendTradingMode === 'function' && !D().isWeekendTradingMode()) {
      pnl.weekend = 0;
    }
    if (tagMode !== 'chip_delta') {
      const rowShelf = Number(fromRows.shelf) || 0;
      const apiShelf = Number(pnl.shelf) || 0;
      if (Math.abs(apiShelf) < 1e-9 && Math.abs(rowShelf) >= 1e-9) {
        pnl.shelf = rowShelf;
        pnl.base = (Number(pnl.base) || 0) - rowShelf;
      }
    }
    const notional = typeof getSimNotionalRub === 'function' ? getSimNotionalRub() : 0;
    const slices = TAG_SHARE_SPEC.map((spec) => {
      const val = Number(pnl[spec.key]) || 0;
      const pct = notional > 0 ? (val / notional) * 100 : 0;
      return { ...spec, val, pct };
    });
    const maxAbs = slices.reduce((m, s) => Math.max(m, Math.abs(s.val)), 0);
    const bars = slices.map((s) => {
      const h = maxAbs > 0 ? (Math.abs(s.val) / maxAbs) * 100 : 0;
      const barH = h > 0 && h < 4 ? 4 : h;
      return (
        `<div class="tag-share-bar-col" title="${s.label}: вклад чипа в итог (вкл − выкл)">`
        + `<div class="tag-share-bar${s.val < 0 ? ' is-neg' : ''}"`
        + ` style="height:${barH.toFixed(1)}%;background:${s.color}"></div>`
        + `</div>`
      );
    }).join('');
    const legend = slices.map((s) => {
      const cls = pnlClass(s.val);
      return (
        `<div class="tag-share-row" title="${s.label}: ${formatTagSharePct(s.pct, s.val)} от капитала · ${formatTagShareRub(s.val)}">`
        + `<span class="tag-share-dot" style="background:${s.color}"></span>`
        + `<span class="tag-share-name">${s.label}</span>`
        + `<span class="tag-share-pct">${formatTagSharePct(s.pct, s.val)}</span>`
        + `<span class="tag-share-rub ${cls}">${formatTagShareRub(s.val)}</span>`
        + `</div>`
      );
    }).join('');
    host.innerHTML =
      `<div class="tag-share-bars" aria-hidden="true">${bars}</div>`
      + `<div class="tag-share-legend">${legend}</div>`;
  }


  function emptyTagSharePnl() {
    return { base: 0, addon: 0, extra: 0, shelf: 0, weekend: 0 };
  }

  function parseByTagPnl(summary) {
    const raw = summary && (summary.by_tag || summary.byTag);
    if (!raw || typeof raw !== 'object') return null;
    const out = emptyTagSharePnl();
    let any = false;
    for (const spec of TAG_SHARE_SPEC) {
      const cell = raw[spec.api] != null ? raw[spec.api] : raw[spec.key];
      if (cell == null) continue;
      const pnl = typeof cell === 'number' ? Number(cell) : Number(cell.pnlRub ?? cell.pnl ?? 0);
      if (Number.isFinite(pnl)) {
        out[spec.key] = pnl;
        any = true;
      }
    }
    return any ? out : null;
  }

  function collectTagShareFromRows(rows) {
    const out = emptyTagSharePnl();
    const weekendOn = typeof isWeekendTradingMode === 'function' && isWeekendTradingMode();
    for (const r of rows || []) {
      if (!r) continue;
      const k = normalizeTradeSrcFilterKey(r);
      if (k !== 'base' && k !== 'addon' && k !== 'extra' && k !== 'shelf') continue;
      let pnl;
      if (r.status === 'Открыта') {
        pnl = Number(r.openMtm != null ? r.openMtm : r.mtm);
      } else if (r.status === 'Закрыта') {
        pnl = Number(r.netValue);
      } else {
        continue;
      }
      if (!Number.isFinite(pnl)) continue;
      if (weekendOn && D().isWeekendChipEntry(r.entryDate)) out.weekend += pnl;
      else out[k] += pnl;
    }
    return out;
  }

  function formatTagShareRub(rub) {
    const n = Number(rub) || 0;
    const sign = n > 0 ? '+' : '';
    const body = typeof formatAccountRub === 'function'
      ? D().formatAccountRub(n)
      : String(Math.round(n));
    return `${sign}${body} ₽`;
  }

  function formatTagSharePct(pct, rub) {
    if (!Number.isFinite(pct)) return '0%';
    if (Math.abs(Number(rub) || 0) < 1e-9 && Math.abs(pct) < 0.5) return '0%';
    if (Math.abs(pct) < 0.5 && Math.abs(Number(rub) || 0) >= 1e-9) return '<1%';
    return `${Math.round(pct)}%`;
  }

  function renderTagShareDonut() {
    const host = $('tagShareDonut');
    if (!host) return;
    const tipRows = tipSimCache.rows
      ? (typeof tipRowsUpToCursor === 'function'
        ? D().tipRowsUpToCursor(tipSimCache.rows)
        : tipSimCache.rows)
      : [];
    const fromRows = collectTagShareFromRows(tipRows);
    const fromApi = parseByTagPnl(tipSimCache.summary);
    const tagMode = String(tipSimCache.summary?.by_tag_mode || '');
    const pnl = { ...(fromApi || fromRows) };
    if (typeof isWeekendTradingMode === 'function' && !D().isWeekendTradingMode()) {
      pnl.weekend = 0;
    }
    if (tagMode !== 'chip_delta') {
      const rowShelf = Number(fromRows.shelf) || 0;
      const apiShelf = Number(pnl.shelf) || 0;
      if (Math.abs(apiShelf) < 1e-9 && Math.abs(rowShelf) >= 1e-9) {
        pnl.shelf = rowShelf;
        pnl.base = (Number(pnl.base) || 0) - rowShelf;
      }
    }
    const notional = typeof getSimNotionalRub === 'function' ? getSimNotionalRub() : 0;
    const slices = TAG_SHARE_SPEC.map((spec) => {
      const val = Number(pnl[spec.key]) || 0;
      const pct = notional > 0 ? (val / notional) * 100 : 0;
      return { ...spec, val, pct };
    });
    const maxAbs = slices.reduce((m, s) => Math.max(m, Math.abs(s.val)), 0);
    const bars = slices.map((s) => {
      const h = maxAbs > 0 ? (Math.abs(s.val) / maxAbs) * 100 : 0;
      const barH = h > 0 && h < 4 ? 4 : h;
      return (
        `<div class="tag-share-bar-col" title="${s.label}: вклад чипа в итог (вкл − выкл)">`
        + `<div class="tag-share-bar${s.val < 0 ? ' is-neg' : ''}"`
        + ` style="height:${barH.toFixed(1)}%;background:${s.color}"></div>`
        + `</div>`
      );
    }).join('');
    const legend = slices.map((s) => {
      const cls = pnlClass(s.val);
      return (
        `<div class="tag-share-row" title="${s.label}: ${formatTagSharePct(s.pct, s.val)} от капитала · ${formatTagShareRub(s.val)}">`
        + `<span class="tag-share-dot" style="background:${s.color}"></span>`
        + `<span class="tag-share-name">${s.label}</span>`
        + `<span class="tag-share-pct">${formatTagSharePct(s.pct, s.val)}</span>`
        + `<span class="tag-share-rub ${cls}">${formatTagShareRub(s.val)}</span>`
        + `</div>`
      );
    }).join('');
    host.innerHTML =
      `<div class="tag-share-bars" aria-hidden="true">${bars}</div>`
      + `<div class="tag-share-legend">${legend}</div>`;
  }

  function monthlyPnlYTicks(minPct, maxPct) {
    let lo = Math.min(0, minPct);
    let hi = Math.max(0, maxPct);
    if (!Number.isFinite(lo) || !Number.isFinite(hi) || hi - lo < 1e-9) {
      return [0];
    }
    const span = hi - lo;
    const rough = span / 4;
    const niceSteps = [1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000];
    let step = niceSteps[niceSteps.length - 1];
    for (const s of niceSteps) {
      if (s >= rough) {
        step = s;
        break;
      }
    }
    const start = Math.floor(lo / step) * step;
    const end = Math.ceil(hi / step) * step;
    const ticks = [];
    for (let v = start; v <= end + step * 0.5; v += step) {
      const t = Math.round(v * 1000) / 1000;
      if (t >= start - 1e-9 && t <= end + 1e-9) ticks.push(t);
    }
    if (!ticks.includes(0)) ticks.push(0);
    ticks.sort((a, b) => a - b);
    return ticks;
  }

  function markMonthlyPnlPending() {
    const el = $('tradesMonthlyPnl');
    if (!el) return;
    el.classList.add('is-pending');
    el.dataset.pending = '1';
    el.removeAttribute('data-month-keys');
    el.removeAttribute('data-mean-pct');
    el.removeAttribute('data-mean-rub');
    el.innerHTML = (
      `<div class="trades-monthly-title">`
      + `<span class="tm-title-text">PnL по месяцам</span>`
      + `<span class="tm-mean-badge">пересчёт…</span>`
      + `</div>`
      + `<div class="trades-monthly-empty">пересчёт сделок…</div>`
    );
  }

  function renderMonthlyPnl(visibleRows) {
    const el = $('tradesMonthlyPnl');
    const scrollEl = $('tradesMonthlyPnlScroll');
    if (!el) return;
    const prevScrollTop = scrollEl ? scrollEl.scrollTop : 0;
    const notional = getSimNotionalRub();
    const compound = getSimCompound();
    const months = typeof buildMonthlyPnl === 'function'
      ? buildMonthlyPnl(visibleRows, { notional, compound })
      : [];
    el.classList.remove('is-pending');
    el.dataset.pending = '0';
    if (!months.length) {
      el.dataset.monthKeys = '';
      el.dataset.meanPct = '';
      el.dataset.meanRub = '';
      el.innerHTML = (
        `<div class="trades-monthly-title">PnL по месяцам</div>`
        + `<div class="trades-monthly-empty">Нет закрытых сделок</div>`
      );
      D().applyMonthlyPnlScrollTop(monthlyPnlScrollRestored ? prevScrollTop : D().readSavedMonthlyPnlScrollTop());
      return;
    }
    const dense = months.length > 8;
    const histCls = `trades-monthly-hist${dense ? ' dense' : ''}`;

    let minPct = 0;
    let maxPct = 0;
    let sumPct = 0;
    let sumPnl = 0;
    for (const m of months) {
      const pct = Number.isFinite(m.pct) ? m.pct : 0;
      if (pct < minPct) minPct = pct;
      if (pct > maxPct) maxPct = pct;
      sumPct += pct;
      sumPnl += m.pnl;
    }
    const meanPct = sumPct / months.length;
    const meanPnl = sumPnl / months.length;
    el.dataset.monthKeys = months.map((m) => m.key).join(',');
    el.dataset.meanPct = String(meanPct);
    el.dataset.meanRub = String(meanPnl);
    const yMin = Math.min(0, minPct);
    const yMax = Math.max(0, maxPct);

    const yTicks = monthlyPnlYTicks(yMin, yMax);
    // Expand plot range to nice tick extents so top/bottom ticks sit on edges.
    const tickMinPct = Math.min(...yTicks, 0);
    const tickMaxPct = Math.max(...yTicks, 0);
    const plotMin = Math.min(yMin, tickMinPct);
    const plotMax = Math.max(yMax, tickMaxPct);
    const plotRange = plotMax - plotMin || 1;
    const zeroBottomPlot = ((0 - plotMin) / plotRange) * 100;
    const meanBottomPlot = ((meanPct - plotMin) / plotRange) * 100;

    const yLabelsHtml = yTicks.map((t) => {
      const bottom = ((t - plotMin) / plotRange) * 100;
      return `<span class="tm-y-label" style="bottom:${bottom.toFixed(2)}%">${D().formatMonthlyYTick(t, notional, compound)}</span>`;
    }).join('');
    const gridHtml = yTicks.map((t) => {
      const bottom = ((t - plotMin) / plotRange) * 100;
      const isZero = Math.abs(t) < 1e-9;
      return `<span class="tm-grid-line${isZero ? ' tm-grid-zero' : ''}" style="bottom:${bottom.toFixed(2)}%"></span>`;
    }).join('');

    const meanCls = meanPct > 0 ? 'pos' : meanPct < 0 ? 'neg' : '';
    /* Mean text lives in the title (outside bar hover / native title tip zone) */
    const meanLabel = formatMonthlyMeanLabel(meanPct, meanPnl);
    const meanTitle = compound
      ? 'Среднее месячных %: PnL месяца / эквити на начало месяца; ₽ — среднее месячных PnL'
      : 'Среднее месячных %: PnL месяца / начальный капитал; ₽ — среднее месячных PnL';
    const titleHint = compound
      ? 'PnL по месяцам · % от эквити на начало месяца (капит.)'
      : 'PnL по месяцам · % от начального капитала';

    el.innerHTML = (
      `<div class="trades-monthly-title" title="${titleHint}">`
      + `<span class="tm-title-text">PnL по месяцам</span>`
      + `<span class="tm-mean-badge ${meanCls}" title="${meanTitle}">${meanLabel}</span>`
      + `</div>`
      + `<div class="${histCls}">`
      + `<div class="tm-hist-scale">`
      + `<div class="tm-hist-y-col" aria-hidden="true">`
      + `<div class="tm-hist-y">${yLabelsHtml}</div>`
      + `<div class="tm-hist-y-spacer"></div>`
      + `</div>`
      + `<div class="tm-hist-plot">`
      + `<div class="tm-hist-grid">`
      + gridHtml
      + `<div class="tm-mean-line ${meanCls}" style="bottom:${meanBottomPlot.toFixed(2)}%"></div>`
      + `</div>`
      + `<div class="tm-hist-bars">`
      + months.map((m, mi) => {
        const pct = Number.isFinite(m.pct) ? m.pct : 0;
        const barCls = pct > 0 ? 'pos' : pct < 0 ? 'neg' : 'flat';
        const barH = Math.max(0, (Math.abs(pct) / plotRange) * 100);
        let barStyle;
        if (pct >= 0) {
          barStyle = `bottom:${zeroBottomPlot.toFixed(2)}%;height:${barH.toFixed(2)}%`;
        } else {
          barStyle = `bottom:${(zeroBottomPlot - barH).toFixed(2)}%;height:${barH.toFixed(2)}%`;
        }
        const pctLabel = formatMonthlyHistPctValue(pct);
        const absLabel = formatMonthlyHistAbs(m.pnl);
        const basisHint = compound
          ? `эквити ${formatRub(m.equityStart)}`
          : `кап. ${formatRub(notional)}`;
        const monthYear = String(m.key || '').slice(0, 4);
        const prevYear = mi > 0 ? String(months[mi - 1].key || '').slice(0, 4) : null;
        const monthLabel = dense
          ? (prevYear && prevYear === monthYear
            ? String(m.label || '').replace(/\s+\d+$/, '')
            : m.label)
          : m.label;
        return (
          `<div class="tm-col" title="${m.label} · ${pctLabel} ${absLabel} · ${basisHint} · ${m.count} сд.">`
          + `<span class="tm-bar-track">`
          + `<span class="tm-bar ${barCls}" style="${barStyle}"></span>`
          + `</span>`
          + `<span class="tm-foot">`
          + `<span class="tm-pnl ${D().pnlClass(m.pnl)}">`
          + `<span class="tm-pnl-pct">${pctLabel}</span>`
          + `<span class="tm-pnl-abs">${absLabel}</span>`
          + `</span>`
          + `<span class="tm-month">${monthLabel}</span>`
          + `</span>`
          + `</div>`
        );
      }).join('')
      + `</div></div></div></div>`
    );
    D().applyMonthlyPnlScrollTop(monthlyPnlScrollRestored ? prevScrollTop : D().readSavedMonthlyPnlScrollTop());
  }

  function formatMonthlyMeanLabel(meanPct, meanRub) {
    const pctText = formatMonthlyHistPctValue(meanPct);
    return `ср. ${pctText} ${D().formatMonthlyHistAbs(meanRub)}`;
  }
  global.__TradeDesk = global.__TradeDesk || { deps: {} };
  global.__TradeDesk.chips = {
    isWeekendChipEntry: isWeekendChipEntry,
    renderTagShareDonut: renderTagShareDonut,
    markMonthlyPnlPending: markMonthlyPnlPending,
    renderMonthlyPnl: renderMonthlyPnl,
    collectTagShareFromRows: collectTagShareFromRows,
    parseByTagPnl: parseByTagPnl
  };
})(typeof window !== 'undefined' ? window : globalThis);
