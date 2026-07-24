/** Sim sizing, trade columns/rows, metric tips — split from replay-engine.js */
/** Симуляция PnL — parity с Android/zsim defaults. */
const SIM_NOTIONAL_DEFAULT = 10_000;
const SIM_NOTIONAL_MIN = 1_000;
const SIM_NOTIONAL_MAX = 10_000_000;
/** Adverse slip по спреду (п.п.), калибровка Prod 2026-07-20 (−86 ₽). */
const SIM_SLIPPAGE_SPREAD_PTS_DEFAULT = 0.12;
const SIM_SLIPPAGE_SPREAD_PTS_MIN = 0;
const SIM_SLIPPAGE_SPREAD_PTS_MAX = 0.5;
let _simNotionalRub = SIM_NOTIONAL_DEFAULT;
let _simCompound = false;
let _simSlippageSpreadPts = SIM_SLIPPAGE_SPREAD_PTS_DEFAULT;

function resolveSimNotionalRub(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return SIM_NOTIONAL_DEFAULT;
  return Math.max(SIM_NOTIONAL_MIN, Math.min(SIM_NOTIONAL_MAX, Math.round(n)));
}

function getSimNotionalRub() {
  return _simNotionalRub;
}

function setSimNotionalRub(value) {
  _simNotionalRub = resolveSimNotionalRub(value);
  return _simNotionalRub;
}

function resolveSimSlippageSpreadPts(value) {
  const n = Number(String(value ?? '').replace(',', '.'));
  if (!Number.isFinite(n)) return SIM_SLIPPAGE_SPREAD_PTS_DEFAULT;
  return Math.max(
    SIM_SLIPPAGE_SPREAD_PTS_MIN,
    Math.min(SIM_SLIPPAGE_SPREAD_PTS_MAX, Math.round(n * 100) / 100),
  );
}

function getSimSlippageSpreadPts() {
  return _simSlippageSpreadPts;
}

function setSimSlippageSpreadPts(value) {
  _simSlippageSpreadPts = resolveSimSlippageSpreadPts(value);
  return _simSlippageSpreadPts;
}

/**
 * Спред входа с adverse slip — parity zsim._entry_spread / Android zSimEntrySpread.
 * @param {number} spreadPercent
 * @param {'Long'|'Short'|string} direction
 */
function simEntrySpread(spreadPercent, direction) {
  const sp = Number(spreadPercent) || 0;
  const slip = Math.max(0, getSimSlippageSpreadPts());
  if (direction === 'Long') return sp + slip;
  if (direction === 'Short') return sp - slip;
  return sp;
}

/**
 * Спред выхода с adverse slip — parity zsim._exit_spread / Android zSimExitSpread.
 * @param {number} spreadPercent
 * @param {'Long'|'Short'|string} direction
 */
function simExitSpread(spreadPercent, direction) {
  const sp = Number(spreadPercent) || 0;
  const slip = Math.max(0, getSimSlippageSpreadPts());
  if (direction === 'Long') return sp - slip;
  if (direction === 'Short') return sp + slip;
  return sp;
}

function getSimCompound() {
  return !!_simCompound;
}

function setSimCompound(on) {
  _simCompound = !!on;
  return _simCompound;
}

/** Номинал сделки: при капитализации PnL реинвестируется в размер следующей. */
function createSimSizingState() {
  const baseNotional = getSimNotionalRub();
  const state = {
    baseNotional,
    compound: getSimCompound(),
    realizedRub: 0,
    positionNotional: baseNotional,
    constants: null,
  };
  state.refresh = () => {
    if (state.compound) {
      state.positionNotional = Math.max(1, state.baseNotional + state.realizedRub);
    } else {
      state.positionNotional = state.baseNotional;
    }
    state.constants = simPnlConstants(state.positionNotional);
  };
  state.applyClosedNet = (net) => {
    state.realizedRub += net;
    state.refresh();
  };
  state.refresh();
  return state;
}

const SIM_LEVERAGE = 7;
const SIM_COMMISSION_PCT_PER_SIDE = 0.04;
const SIM_OVERNIGHT_FEE_PCT_PER_DAY = 0.033;

const TRADE_COLUMNS = [
  // слева — сделка и тех. индикаторы
  { key: 'Index', title: '#', width: 28 },
  { key: 'Direction', title: 'Напр.', width: 40 },
  { key: 'Lots', title: 'Лоты', width: 40, hint: 'Лоты спреда (Prod); в Тесте —' },
  { key: 'Source', title: 'Src', width: 52, hint: 'Источник: AUTO / BROKER / … (Prod); в Тесте —' },
  { key: 'Entry', title: 'Вход', width: 96 },
  { key: 'Exit', title: 'Выход', width: 96 },
  { key: 'EntryZ', title: 'Zвх', width: 44, hint: 'Z-score на баре входа' },
  { key: 'ExitZ', title: 'Zвых', width: 44, hint: 'Z-score на баре выхода' },
  { key: 'Duration', title: 'Длит.', width: 52 },
  { key: 'SpreadEntry', title: 'S%вх', width: 44 },
  { key: 'SpreadExit', title: 'S%вых', width: 44 },
  { key: 'SpreadDelta', title: 'Δпп', width: 40 },
  { key: 'Slip', title: 'Slip', width: 44, hint: 'Adverse slip на входе, п.п. спреда — те же единицы, что Slip в Тесте (дефолт 0.12)' },
  { key: 'PnlMin', title: 'Min', width: 48, hint: 'Мин. MTM по сделке (от входа, с комиссией входа и overnight)' },
  { key: 'PnlMax', title: 'Max', width: 48, hint: 'Макс. MTM по сделке (от входа, с комиссией входа и overnight)' },
  { key: 'Hit1', title: '1%', width: 72, hint: 'Первый бар, где PnL ≥ 1% от вложения' },
  { key: 'Hit2', title: '2%', width: 72, hint: 'Первый бар, где PnL ≥ 2% от вложения' },
  { key: 'Hit3', title: '3%', width: 72, hint: 'Первый бар, где PnL ≥ 3% от вложения' },
  { key: 'Risk', title: 'Риск', width: 72 },
  // справа — деньги
  { key: 'Net', title: 'Чист.', width: 56, hint: 'Чистый PnL после комиссий и overnight' },
  { key: 'Gross', title: 'Вал.', width: 52 },
  { key: 'Commission', title: 'Ком.', width: 48 },
  { key: 'Overnight', title: 'Овн.', width: 48 },
  { key: 'AccountAfter', title: 'Сумма после', width: 88, hint: 'Сумма на счету после сделки (оценка портфеля / cash) · только Prod' },
];

const TRADE_COLUMN_KEYS = TRADE_COLUMNS.map((c) => c.key);
const TRADE_COLUMNS_DEFAULT = [...TRADE_COLUMN_KEYS];

/** Видимые колонки в сохранённом порядке (не канон TRADE_COLUMNS). */
function resolveVisibleTradeColumns(orderedKeys) {
  return (orderedKeys || [])
    .map((k) => TRADE_COLUMNS.find((c) => c.key === k))
    .filter(Boolean);
}

function toggleTradeColumnKey(orderedKeys, key) {
  const keys = [...(orderedKeys || [])];
  const idx = keys.indexOf(key);
  if (idx >= 0) {
    if (keys.length <= 1) return keys;
    keys.splice(idx, 1);
    return keys;
  }
  keys.push(key);
  return keys;
}

function moveTradeColumnKey(orderedKeys, fromKey, toKey) {
  if (!fromKey || fromKey === toKey) return [...(orderedKeys || [])];
  const keys = (orderedKeys || []).filter((k) => k !== fromKey);
  const toIdx = keys.indexOf(toKey);
  if (toIdx < 0) {
    keys.push(fromKey);
    return keys;
  }
  keys.splice(toIdx, 0, fromKey);
  return keys;
}

/** Mig 8: сохранить набор видимых, выстроить как новый дефолт (индикаторы | деньги). */
function regroupTradeColumnKeys(keys) {
  const visible = new Set(keys || []);
  const ordered = TRADE_COLUMNS_DEFAULT.filter((k) => visible.has(k));
  for (const k of keys || []) {
    if (TRADE_COLUMN_KEYS.includes(k) && !ordered.includes(k)) ordered.push(k);
  }
  return ordered.length ? ordered : [...TRADE_COLUMNS_DEFAULT];
}

/**
 * DnD чипов колонок. getKeys/setKeys — текущий порядок видимых.
 * onChange() — перерисовать picker + таблицу.
 */
function bindColumnChipDrag(btn, getKeys, setKeys, onChange) {
  btn.draggable = true;
  btn.dataset.col = btn.dataset.col || '';
  let didDrag = false;
  btn.addEventListener('dragstart', (e) => {
    didDrag = false;
    btn.classList.add('col-dragging');
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', btn.dataset.col || '');
  });
  btn.addEventListener('drag', () => { didDrag = true; });
  btn.addEventListener('dragend', () => btn.classList.remove('col-dragging'));
  btn.addEventListener('dragover', (e) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    btn.classList.add('col-drop-target');
  });
  btn.addEventListener('dragleave', () => btn.classList.remove('col-drop-target'));
  btn.addEventListener('drop', (e) => {
    e.preventDefault();
    btn.classList.remove('col-drop-target');
    const fromKey = e.dataTransfer.getData('text/plain');
    const toKey = btn.dataset.col;
    if (!fromKey || !toKey || fromKey === toKey) return;
    setKeys(moveTradeColumnKey(getKeys(), fromKey, toKey));
    onChange();
  });
  btn.addEventListener('click', (e) => {
    if (!didDrag) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    didDrag = false;
  }, true);
}

/**
 * DnD заголовков таблицы. Клик без drag — sortClick(colKey).
 */
function bindTableHeaderDrag(th, colKey, getKeys, setKeys, onReorder, onSortClick) {
  th.draggable = true;
  th.classList.add('col-draggable');
  th.title = (th.title ? `${th.title} · ` : '') + 'перетащите для порядка';
  let dragged = false;
  th.addEventListener('dragstart', (e) => {
    dragged = false;
    th.classList.add('col-dragging');
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', colKey);
  });
  th.addEventListener('drag', () => { dragged = true; });
  th.addEventListener('dragend', () => th.classList.remove('col-dragging'));
  th.addEventListener('dragover', (e) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    th.classList.add('col-drop-target');
  });
  th.addEventListener('dragleave', () => th.classList.remove('col-drop-target'));
  th.addEventListener('drop', (e) => {
    e.preventDefault();
    e.stopPropagation();
    th.classList.remove('col-drop-target');
    const fromKey = e.dataTransfer.getData('text/plain');
    if (!fromKey || fromKey === colKey) return;
    setKeys(moveTradeColumnKey(getKeys(), fromKey, colKey));
    onReorder();
  });
  th.addEventListener('click', (e) => {
    if (dragged) {
      e.stopPropagation();
      dragged = false;
      return;
    }
    onSortClick?.(e);
  });
}

function spreadPnlToRub(pnlSpreadPts, effectiveNotionalRub) {
  return effectiveNotionalRub * (pnlSpreadPts / 100);
}

function overnightDays(entryTs, exitTs) {
  const e = parseDay(entryTs);
  const x = parseDay(exitTs);
  if (!e || !x) return 0;
  const msPerDay = 24 * 60 * 60 * 1000;
  return Math.max(0, Math.round((x - e) / msPerDay));
}

function parseDay(ts) {
  const s = String(ts).trim().replace('T', ' ').slice(0, 10);
  const d = new Date(`${s}T00:00:00+03:00`);
  return Number.isNaN(d.getTime()) ? null : d.getTime();
}

function parseTradeMs(ts) {
  const s = String(ts).trim().replace('T', ' ');
  const iso = s.length >= 16 ? `${s.slice(0, 16).replace(' ', 'T')}:00+03:00` : `${s}+03:00`;
  const ms = new Date(iso).getTime();
  return Number.isNaN(ms) ? null : ms;
}

function formatDurationMinutes(totalMinutes) {
  if (!Number.isFinite(totalMinutes)) return '—';
  const m = Math.max(0, Math.round(totalMinutes));
  if (m === 0) return totalMinutes > 0 ? '< 1 мин' : '0 мин';
  const days = Math.floor(m / (24 * 60));
  const hours = Math.floor((m % (24 * 60)) / 60);
  const minutes = m % 60;
  if (days > 0) {
    let out = `${days} дн.`;
    if (hours > 0) out += ` ${hours} ч`;
    return out;
  }
  if (hours > 0) {
    return minutes > 0 ? `${hours} ч ${minutes} мин` : `${hours} ч`;
  }
  return `${minutes} мин`;
}

function formatSimTradeDuration(entryDate, exitDate) {
  const entryMs = parseTradeMs(entryDate);
  const exitMs = parseTradeMs(exitDate);
  if (entryMs == null || exitMs == null) return '—';
  const diffMs = exitMs - entryMs;
  if (diffMs < 0) return '—';
  if (diffMs === 0) return '0 мин';
  const totalMinutes = Math.floor(diffMs / 60000);
  if (totalMinutes === 0) return '< 1 мин';
  return formatDurationMinutes(totalMinutes);
}

function durationTone(entryDate, exitDate) {
  const entryMs = parseTradeMs(entryDate);
  const exitMs = parseTradeMs(exitDate);
  if (entryMs == null || exitMs == null) return 'neutral';
  const diffMs = exitMs - entryMs;
  if (diffMs < 24 * 60 * 60 * 1000) return 'short';
  if (diffMs >= 24 * 60 * 60 * 1000) return 'long';
  return 'neutral';
}

function formatRub(value) {
  const sign = value > 0 ? '+' : value < 0 ? '−' : '';
  const abs = Math.abs(value);
  if (abs >= 1000) return `${sign}${(abs / 1000).toFixed(1)}k`;
  return `${sign}${abs.toFixed(0)}`;
}

/** Баланс счёта — без «+» как у PnL; 2 знака в «k», чтобы 99978 → 100.0k не путало. */
function formatAccountRub(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '—';
  const abs = Math.abs(n);
  const sign = n < 0 ? '−' : '';
  if (abs >= 1000) return `${sign}${(abs / 1000).toFixed(2)}k`;
  return `${sign}${Math.round(abs)}`;
}

function formatCostRub(value) {
  if (value <= 0) return '0';
  return `−${value.toFixed(0)}`;
}

function entryHourMsk(entryDate) {
  const ms = parseTradeMs(entryDate);
  if (ms == null) return null;
  const d = new Date(ms);
  const utcH = d.getUTCHours();
  return (utcH + 3) % 24;
}

function isFridayEntryMsk(entryDate) {
  const ms = parseTradeMs(entryDate);
  if (ms == null) return false;
  const d = new Date(ms);
  const day = (d.getUTCDay() + Math.floor((d.getUTCHours() + 3) / 24)) % 7;
  return day === 5;
}

/**
 * Оценка риска сделки — parity Android buildTradeRiskAssessmentFromInputs.
 * Красная зона = High/Critical (score ≥ 4).
 */
function assessTradeRisk(entryDate, exitDate, entryZ, overnightRub, entryThreshold) {
  const entryMs = parseTradeMs(entryDate);
  const exitMs = parseTradeMs(exitDate);
  const durationMs = entryMs != null && exitMs != null ? exitMs - entryMs : null;
  const flags = [];
  let holdPoints = 0;
  let overnightPoints = 0;
  let weakEntryZPoints = 0;
  let entryHourPoints = 0;
  let fridayLongHoldPoints = 0;
  let nearThresholdPoints = 0;
  const dayMs = 24 * 60 * 60 * 1000;
  const sixH = 6 * 60 * 60 * 1000;

  if (durationMs != null && durationMs > 5 * dayMs) {
    flags.push('>5д');
    holdPoints = 4;
  } else if (durationMs != null && durationMs > 2 * dayMs) {
    flags.push('>2д');
    holdPoints = 3;
  }
  if (overnightRub > 100) {
    flags.push('Ovn100');
    overnightPoints = 2;
  } else if (overnightRub > 50 && durationMs != null && durationMs > dayMs) {
    flags.push('Ovn50');
    overnightPoints = 2;
  }
  if (entryZ != null && Math.abs(entryZ) < 1.0 && durationMs != null && durationMs > sixH) {
    flags.push('Z<1');
    weakEntryZPoints = 1;
  }
  if (durationMs != null && durationMs > sixH) {
    const h = entryHourMsk(entryDate);
    if (h === 13) {
      flags.push('13ч');
      entryHourPoints = 1;
    } else if (h >= 12 && h <= 14) {
      flags.push('12–14');
      entryHourPoints = 1;
    }
  }
  if (durationMs != null && durationMs > 2 * dayMs && isFridayEntryMsk(entryDate)) {
    flags.push('Пт>2д');
    fridayLongHoldPoints = 1;
  }
  if (
    entryZ != null
    && Math.abs(entryZ) < entryThreshold + 0.05
    && durationMs != null
    && durationMs > dayMs
  ) {
    nearThresholdPoints = 1;
    flags.push('~порог');
  }

  const score = holdPoints + overnightPoints + weakEntryZPoints
    + entryHourPoints + fridayLongHoldPoints + nearThresholdPoints;
  const level = score >= 6 ? 'Critical' : score >= 4 ? 'High' : score >= 3 ? 'Elevated' : 'None';
  return {
    flagsText: flags.length ? flags.join(' ') : '—',
    score,
    level,
    isRed: score >= 4,
  };
}

function buildTradeRiskFlags(entryDate, exitDate, entryZ, overnightRub, entryThreshold) {
  return assessTradeRisk(entryDate, exitDate, entryZ, overnightRub, entryThreshold).flagsText;
}

/**
 * Быстрый net PnL по edges без buildTradeRows (heatmap / sweep).
 * Та же формула комиссий / overnight / slip / капитализации, что у закрытых сделок.
 */
function sumClosedNetFromEdges(edges) {
  const sizing = createSimSizingState();
  let openEntry = null;
  let entryCommission = 0;
  let entryConstants = sizing.constants;
  let totalPnl = 0;
  let closedCount = 0;
  if (!edges || !edges.length) return { totalPnl: 0, closedCount: 0 };

  for (const edge of edges) {
    if (edge.signal === 'EnterLong' || edge.signal === 'EnterShort') {
      openEntry = edge;
      entryConstants = sizing.constants;
      entryCommission = entryConstants.commPerSide;
    } else if (edge.signal === 'ExitLong' || edge.signal === 'ExitShort') {
      if (!openEntry) continue;
      const isLong = openEntry.signal === 'EnterLong';
      const dir = isLong ? 'Long' : 'Short';
      const entrySpread = simEntrySpread(openEntry.bar.spreadPercent ?? 0, dir);
      const exitSpread = simExitSpread(edge.bar.spreadPercent ?? 0, dir);
      const pnlPts = isLong ? exitSpread - entrySpread : entrySpread - exitSpread;
      const { effNotional, commPerSide, overnightPerDay } = entryConstants;
      const gross = spreadPnlToRub(pnlPts, effNotional);
      const ovn = overnightPerDay * overnightDays(openEntry.bar.tradeDate, edge.bar.tradeDate);
      const net = gross - (entryCommission + commPerSide) - ovn;
      totalPnl += net;
      closedCount += 1;
      sizing.applyClosedNet(net);
      openEntry = null;
    }
  }
  return { totalPnl, closedCount };
}

/** Risk-exit выкл (TP можно) — клетка heatmap на быстром пути. */
function simOptsAreHeatmapFast(opts) {
  const o = typeof normalizeSimExitOpts === 'function'
    ? normalizeSimExitOpts(opts || {})
    : (opts || {});
  return !(
    o.forcedTimeStopHours
    || o.forcedZStopDeviation
    || o.forcedZStopPctOfEntryZ
    || o.maxLossRub
    || o.maxLossPctOfNotional
    || o.forcedHoldHoursIfLosing
  );
}

/** @deprecated use simOptsAreHeatmapFast */
function simOptsAreZOnly(opts) {
  const o = typeof normalizeSimExitOpts === 'function'
    ? normalizeSimExitOpts(opts || {})
    : (opts || {});
  return simOptsAreHeatmapFast(opts) && !(o.takeProfitPct > 0);
}

/**
 * Один проход по барам → typed arrays для быстрого heatmap.
 * dayNum — номер суток (MSK) для overnight без Date на каждой сделке.
 */
function prepareHeatmapSeries(points, endIdx) {
  const last = Math.min(Math.max(0, endIdx | 0), (points?.length || 1) - 1);
  const len = last + 1;
  const z = new Float64Array(len);
  const spread = new Float64Array(len);
  const session = new Uint8Array(len);
  const consec = new Uint8Array(len);
  const dayNum = new Int32Array(len);
  const ts = new Float64Array(len);
  for (let i = 0; i < len; i++) {
    const p = points[i];
    const zs = p?.zScore;
    z[i] = zs != null && Number.isFinite(Number(zs)) ? Number(zs) : NaN;
    spread[i] = p?.spreadPercent != null ? Number(p.spreadPercent) : 0;
    const td = String(p?.tradeDate || '');
    const dayMs = parseDay(td);
    dayNum[i] = dayMs != null ? (dayMs / 86400000) | 0 : 0;
    const tsm = p?.timestampMs;
    ts[i] = tsm != null && Number.isFinite(Number(tsm)) ? Number(tsm) : NaN;
    // session inline (без Date на каждый бар): пн–пт 07:00–23:50
    let sess = 0;
    if (td.length >= 16) {
      const y = Number(td.slice(0, 4));
      const mo = Number(td.slice(5, 7));
      const d = Number(td.slice(8, 10));
      const hm = td.slice(11, 16);
      if (Number.isFinite(y) && Number.isFinite(mo) && Number.isFinite(d)) {
        const dow = new Date(Date.UTC(y, mo - 1, d, 12, 0, 0)).getUTCDay();
        if (dow !== 0 && dow !== 6 && hm >= '07:00' && hm < '23:50') sess = 1;
      }
    }
    session[i] = sess;
    consec[i] = (i > 0 && Number.isFinite(ts[i]) && Number.isFinite(ts[i - 1])
      && (ts[i] - ts[i - 1]) === 15 * 60 * 1000) ? 1 : 0;
  }
  return { z, spread, session, consec, dayNum, len };
}

/**
 * Быстрый heatmap: Z-сигналы + опционально TP (как openTradeNetRub MTM).
 * Protective risk (Z±/DD/Time/Money) — не здесь.
 */
function heatmapFastNetFromSeries(series, entry, exit, simOpts) {
  const { z, spread, session, consec, dayNum, len } = series;
  const opts = typeof normalizeSimExitOpts === 'function'
    ? normalizeSimExitOpts(simOpts || {})
    : (simOpts || {});
  const tpPct = opts.takeProfitPct > 0 ? opts.takeProfitPct : 0;
  const slip = Math.max(0, typeof getSimSlippageSpreadPts === 'function' ? getSimSlippageSpreadPts() : 0);
  const sizing = createSimSizingState();
  let pos = 0; // 0 Flat, 1 Long, 2 Short
  let entrySpread = 0;
  let entryDay = 0;
  let entryCommission = 0;
  let effNotional = 0;
  let overnightPerDay = 0;
  let commPerSide = 0;
  let notionalRub = 0;
  let totalPnl = 0;
  let closedCount = 0;

  const closeAt = (i, isLong) => {
    const sp = spread[i];
    const exitSpread = isLong ? sp - slip : sp + slip;
    const pnlPts = isLong ? exitSpread - entrySpread : entrySpread - exitSpread;
    const gross = effNotional * (pnlPts / 100);
    const ovn = overnightPerDay * Math.max(0, dayNum[i] - entryDay);
    const net = gross - (entryCommission + commPerSide) - ovn;
    totalPnl += net;
    closedCount += 1;
    sizing.applyClosedNet(net);
    pos = 0;
  };

  for (let i = 1; i < len; i++) {
    if (!session[i]) continue;

    // TP на каждом session-баре в позе (как engine: protective до Z)
    if (pos && tpPct > 0) {
      const sp = spread[i];
      // MTM без exit-slip: mark = сырой спред, комиссия только входа
      const pnlPts = pos === 1 ? (sp - entrySpread) : (entrySpread - sp);
      const gross = effNotional * (pnlPts / 100);
      const ovn = overnightPerDay * Math.max(0, dayNum[i] - entryDay);
      const netMtm = gross - entryCommission - ovn;
      const pct = (netMtm / Math.max(1, notionalRub)) * 100;
      if (pct >= tpPct) {
        closeAt(i, pos === 1);
        // после TP на этом баре вход по Z не делаем (как один signal/бар в engine)
        continue;
      }
    }

    if (!consec[i]) continue;
    const prevZ = z[i - 1];
    const curZ = z[i];
    if (!Number.isFinite(prevZ) || !Number.isFinite(curZ)) continue;

    let signal = 0; // 1 EL, 2 ES, 3 XL, 4 XS
    if (pos === 0) {
      if (prevZ > -entry && curZ <= -entry) signal = 1;
      else if (prevZ < entry && curZ >= entry) signal = 2;
    } else if (pos === 1) {
      if (prevZ < -exit && curZ >= -exit) signal = 3;
    } else if (pos === 2) {
      if (prevZ > exit && curZ <= exit) signal = 4;
    }
    if (!signal) continue;

    const sp = spread[i];
    if (signal === 1 || signal === 2) {
      const c = sizing.constants;
      entryCommission = c.commPerSide;
      effNotional = c.effNotional;
      overnightPerDay = c.overnightPerDay;
      commPerSide = c.commPerSide;
      notionalRub = c.notionalRub;
      entrySpread = signal === 1 ? sp + slip : sp - slip;
      entryDay = dayNum[i];
      pos = signal === 1 ? 1 : 2;
    } else {
      closeAt(i, pos === 1);
    }
  }
  return { totalPnl, closedCount };
}

/** @deprecated */
function heatmapZOnlyNetFromSeries(series, entry, exit) {
  return heatmapFastNetFromSeries(series, entry, exit, {});
}

/**
 * Одна клетка heatmap.
 * Быстрый путь: Z + TP. Иначе полный BarReplayEngine.
 */
function heatmapCellNetPnl(points, entry, exit, endIdx, simOpts, preparedSeries) {
  if (preparedSeries) {
    return heatmapFastNetFromSeries(preparedSeries, entry, exit, simOpts);
  }
  const eng = new BarReplayEngine(points, entry, exit, endIdx, simOpts);
  return sumClosedNetFromEdges(eng.edges);
}

/** Почему нет быстрого пути — для статуса UI. */
function heatmapSlowReason(opts) {
  const o = typeof normalizeSimExitOpts === 'function'
    ? normalizeSimExitOpts(opts || {})
    : (opts || {});
  const bits = [];
  if (o.forcedZStopDeviation || o.forcedZStopPctOfEntryZ) bits.push('Z±');
  if (o.maxLossPctOfNotional) bits.push('DD');
  if (o.forcedTimeStopHours || o.forcedHoldHoursIfLosing) bits.push('Время');
  if (o.maxLossRub) bits.push('Money');
  return bits.length ? bits.join('+') : '';
}

/** Сводка симуляции по строкам сделок (закрытые; обновляется на каждом баре). */
function buildTradeSimSummary(rows, notionalRub = getSimNotionalRub()) {
  const closed = rows
    .filter((r) => r.status === 'Закрыта' && r.netValue != null && Number.isFinite(r.netValue))
    .slice()
    .sort((a, b) => {
      const ea = parseTradeMs(a.exitDate) ?? 0;
      const eb = parseTradeMs(b.exitDate) ?? 0;
      return ea - eb;
    });
  const open = rows.filter((r) => r.status === 'Открыта');
  const redClosed = closed.filter((r) => r.riskRed);
  const longs = closed.filter((r) => r.direction === 'Long');
  const shorts = closed.filter((r) => r.direction === 'Short');

  // Equity = депо + накопленный PnL. DD% — от пика equity (не от стартового депо),
  // иначе при капитализации/−росте счёта −7k от 10k выглядит как «−70%».
  let cumulative = 0;
  let peakPnl = 0;
  let maxDd = 0;
  let maxDdPct = 0;
  for (const t of closed) {
    cumulative += t.netValue;
    if (cumulative > peakPnl) peakPnl = cumulative;
    const dd = peakPnl - cumulative;
    if (dd > maxDd) maxDd = dd;
    const peakEquity = notionalRub + peakPnl;
    if (peakEquity > 0 && dd > 0) {
      const pct = (dd / peakEquity) * 100;
      if (pct > maxDdPct) maxDdPct = pct;
    }
  }

  const wins = closed.filter((r) => r.netValue > 0);
  const losses = closed.filter((r) => r.netValue < 0);
  const totalPnl = closed.reduce((s, r) => s + r.netValue, 0);
  const longPnl = longs.reduce((s, r) => s + r.netValue, 0);
  const shortPnl = shorts.reduce((s, r) => s + r.netValue, 0);
  const grossWin = wins.reduce((s, r) => s + r.netValue, 0);
  const grossLossAbs = losses.reduce((s, r) => s + Math.abs(r.netValue), 0);
  const avgTrade = closed.length ? totalPnl / closed.length : 0;
  const avgWin = wins.length ? grossWin / wins.length : 0;
  const avgLoss = losses.length ? losses.reduce((s, r) => s + r.netValue, 0) / losses.length : 0;
  const winRate = closed.length ? (wins.length * 100) / closed.length : 0;
  const profitFactor = grossLossAbs > 0 ? grossWin / grossLossAbs : (grossWin > 0 ? Infinity : null);
  const retPct = notionalRub > 0 ? (totalPnl / notionalRub) * 100 : 0;

  return {
    closedCount: closed.length,
    openCount: open.length,
    redCount: redClosed.length,
    totalPnl,
    retPct,
    maxDd,
    maxDdPct,
    winCount: wins.length,
    lossCount: losses.length,
    winRate,
    avgTrade,
    avgWin,
    avgLoss,
    longCount: longs.length,
    longPnl,
    shortCount: shorts.length,
    shortPnl,
    profitFactor,
    largestWin: wins.length ? Math.max(...wins.map((r) => r.netValue)) : 0,
    largestLoss: losses.length ? Math.min(...losses.map((r) => r.netValue)) : 0,
  };
}

const MONTH_SHORT_RU = [
  'Янв', 'Фев', 'Мар', 'Апр', 'Май', 'Июн',
  'Июл', 'Авг', 'Сен', 'Окт', 'Ноя', 'Дек',
];

/** Calendar YYYY-MM from trade date label (MSK), or null. */
function tradeMonthKeyFromLabel(label) {
  if (label == null || label === '—') return null;
  const s = String(label).trim().replace('T', ' ');
  if (/^\d{4}-\d{2}/.test(s)) return s.slice(0, 7);
  const ms = parseTradeMs(s);
  if (ms == null) return null;
  // Labels are MSK (+03); derive calendar month in that zone.
  const shifted = new Date(ms + 3 * 60 * 60 * 1000);
  const y = shifted.getUTCFullYear();
  const m = shifted.getUTCMonth() + 1;
  return `${y}-${String(m).padStart(2, '0')}`;
}

function formatMonthPnlLabel(ymKey) {
  const [ys, ms] = String(ymKey).split('-');
  const y = parseInt(ys, 10);
  const m = parseInt(ms, 10);
  if (!Number.isFinite(y) || !Number.isFinite(m) || m < 1 || m > 12) return ymKey;
  const yy = String(y).slice(-2);
  return `${MONTH_SHORT_RU[m - 1]} ${yy}`;
}

/**
 * Net PnL by calendar month for closed trades (same filter as summary).
 * Month key = exitDate preferred, else entryDate. Field: netValue.
 *
 * % basis:
 * - compound OFF → monthPnl / initialNotional (fixed deposit)
 * - compound ON  → monthPnl / equity at month start
 *   (= initialNotional + Σ previous months' pnl), floored at 1 ₽ like sim sizing
 *
 * `pct` is always the display return for that month; mean of `pct` = «ср.» badge.
 */
function buildMonthlyPnl(rows, opts) {
  const initialNotional = opts && Number.isFinite(opts.notional) && opts.notional > 0
    ? opts.notional
    : (typeof getSimNotionalRub === 'function' ? getSimNotionalRub() : 0);
  const compound = opts && opts.compound != null
    ? !!opts.compound
    : (typeof getSimCompound === 'function' ? getSimCompound() : false);

  const map = new Map();
  for (const r of rows) {
    if (r.status !== 'Закрыта' || r.netValue == null || !Number.isFinite(r.netValue)) continue;
    const key = tradeMonthKeyFromLabel(r.exitDate) || tradeMonthKeyFromLabel(r.entryDate);
    if (!key) continue;
    const cur = map.get(key) || { key, pnl: 0, count: 0 };
    cur.pnl += r.netValue;
    cur.count += 1;
    map.set(key, cur);
  }
  const months = Array.from(map.values()).sort((a, b) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
  let maxAbs = 0;
  let cumPnl = 0;
  const enriched = [];
  for (const m of months) {
    const a = Math.abs(m.pnl);
    if (a > maxAbs) maxAbs = a;
    const equityStart = compound
      ? Math.max(1, initialNotional + cumPnl)
      : initialNotional;
    const pct = equityStart > 0 ? (m.pnl / equityStart) * 100 : 0;
    enriched.push({
      key: m.key,
      label: formatMonthPnlLabel(m.key),
      pnl: m.pnl,
      count: m.count,
      equityStart,
      pct,
      barPct: 0,
    });
    cumPnl += m.pnl;
  }
  for (const m of enriched) {
    m.barPct = maxAbs > 0 ? (Math.abs(m.pnl) / maxAbs) * 100 : 0;
  }
  return enriched;
}

function decodeTradeColumns(raw) {
  if (!raw) return [...TRADE_COLUMNS_DEFAULT];
  const loaded = raw.split(',').map((t) => t.trim()).filter((k) => TRADE_COLUMN_KEYS.includes(k));
  if (!loaded.length) return [...TRADE_COLUMNS_DEFAULT];
  // Только сохранённый набор — новые столбцы не форсируем при каждом reload
  // (иначе после обновления версии «выключенные» поля снова появляются).
  return loaded;
}

/**
 * Однократная миграция: добавить новые столбцы в сохранение пользователя.
 * version bump только когда реально появляются новые колонки.
 */
const TRADE_COLUMNS_MIG_VERSION = 8;

function migrateTradeColumnsOnce(keys) {
  let next = [...keys];
  const insertAfter = (arr, key, after) => {
    if (arr.includes(key)) return arr;
    if (!TRADE_COLUMN_KEYS.includes(key)) return arr;
    const i = arr.indexOf(after);
    const out = [...arr];
    out.splice(i >= 0 ? i + 1 : out.length, 0, key);
    return out;
  };
  const ver = parseInt(
    (typeof localStorage !== 'undefined' && localStorage.getItem('moexReplay.tradeColumnsMig')) || '0',
    10,
  ) || 0;
  if (ver >= TRADE_COLUMNS_MIG_VERSION) return next;
  if (ver < 1) {
    next = insertAfter(next, 'Hit2', 'PnlMax');
    next = insertAfter(next, 'Hit3', 'Hit2');
  }
  if (ver < 3) {
    next = insertAfter(next, 'EntryZ', 'Exit');
    next = insertAfter(next, 'ExitZ', 'EntryZ');
  }
  if (ver < 4) {
    next = insertAfter(next, 'Hit1', 'PnlMax');
  }
  if (ver < 5) {
    next = insertAfter(next, 'Lots', 'Direction');
    next = insertAfter(next, 'Source', 'Lots');
  }
  if (ver < 6) {
    next = insertAfter(next, 'Slip', 'SpreadDelta');
  }
  if (ver < 7) {
    next = insertAfter(next, 'AccountAfter', 'Net');
  }
  if (ver < 8) {
    next = regroupTradeColumnKeys(next);
  }
  try {
    localStorage.setItem('moexReplay.tradeColumnsMig', String(TRADE_COLUMNS_MIG_VERSION));
  } catch (_) { /* ignore */ }
  return next;
}

function encodeTradeColumns(keys) {
  const valid = keys.filter((k) => TRADE_COLUMN_KEYS.includes(k));
  return (valid.length ? valid : TRADE_COLUMNS_DEFAULT).join(',');
}

/** Min/max чистого PnL по барам удержания (MTM; на выходе — с комиссией выхода). */
function computeTradePnlMinMax(allPoints, entryDate, endDate, direction, entrySpread, closedAtEnd, constants) {
  const c = constants || simPnlConstants();
  const entryMs = parseTradeMs(entryDate);
  const endMs = parseTradeMs(endDate);
  if (entryMs == null || endMs == null || !allPoints.length) return { min: null, max: null };

  let min = Infinity;
  let max = -Infinity;
  let found = false;

  for (const p of allPoints) {
    if (p.timestampMs < entryMs || p.timestampMs > endMs) continue;
    const includeExit = closedAtEnd && p.timestampMs === endMs;
    const net = openTradeNetRub(p, direction, entrySpread, entryDate, c, includeExit);
    found = true;
    if (net < min) min = net;
    if (net > max) max = net;
  }

  if (!found) return { min: null, max: null };
  return { min, max };
}

function buildTradeRows(edges, entryThreshold = 0.7, allPoints = [], cursorIndex = -1, opts = {}) {
  const skipExtras = !!opts.skipExtras;
  const sizing = createSimSizingState();
  const rows = [];
  let tradeNo = 0;
  let openEntry = null;
  let entryCommission = 0;
  let entryConstants = sizing.constants;

  for (const edge of edges) {
    if (edge.signal === 'EnterLong' || edge.signal === 'EnterShort') {
      tradeNo++;
      openEntry = edge;
      entryConstants = sizing.constants;
      entryCommission = entryConstants.commPerSide;
    } else if (edge.signal === 'ExitLong' || edge.signal === 'ExitShort') {
      if (!openEntry) continue;
      const isLong = openEntry.signal === 'EnterLong';
      const dir = isLong ? 'Long' : 'Short';
      const entrySpread = simEntrySpread(openEntry.bar.spreadPercent ?? 0, dir);
      const exitSpread = simExitSpread(edge.bar.spreadPercent ?? 0, dir);
      const pnlPts = isLong ? exitSpread - entrySpread : entrySpread - exitSpread;
      const { effNotional, commPerSide, overnightPerDay } = entryConstants;
      const gross = spreadPnlToRub(pnlPts, effNotional);
      const ovn = overnightPerDay * overnightDays(openEntry.bar.tradeDate, edge.bar.tradeDate);
      const commTotal = entryCommission + commPerSide;
      const net = gross - commTotal - ovn;
      let pnlMin = null;
      let pnlMax = null;
      let milestones = {
        hit1Date: null, hit2Date: null, hit3Date: null,
        hit1Ms: null, hit2Ms: null, hit3Ms: null,
      };
      if (!skipExtras) {
        ({ min: pnlMin, max: pnlMax } = computeTradePnlMinMax(
          allPoints,
          openEntry.bar.tradeDate,
          edge.bar.tradeDate,
          dir,
          entrySpread,
          true,
          entryConstants,
        ));
        milestones = computeTradePnlMilestones(
          allPoints,
          openEntry.bar.tradeDate,
          edge.bar.tradeDate,
          dir,
          entrySpread,
          true,
          entryConstants,
        );
      }
      const risk = assessTradeRisk(
        openEntry.bar.tradeDate,
        edge.bar.tradeDate,
        openEntry.bar.zScore,
        ovn,
        entryThreshold,
      );
      rows.push(makeTradeRow({
        index: tradeNo,
        direction: dir,
        entryDate: openEntry.bar.tradeDate,
        exitDate: edge.bar.tradeDate,
        entryZ: openEntry.bar.zScore,
        exitZ: edge.bar.zScore,
        entrySpread,
        exitSpread,
        entrySlip: getSimSlippageSpreadPts(),
        pnlPts,
        gross,
        commission: commTotal,
        overnight: ovn,
        net,
        pnlMin,
        pnlMax,
        hit1Date: milestones.hit1Date,
        hit2Date: milestones.hit2Date,
        hit3Date: milestones.hit3Date,
        hit1Ms: milestones.hit1Ms,
        hit2Ms: milestones.hit2Ms,
        hit3Ms: milestones.hit3Ms,
        status: 'Закрыта',
        entryThreshold,
        _risk: risk,
      }));
      sizing.applyClosedNet(net);
      openEntry = null;
      entryCommission = 0;
    }
  }
  if (openEntry) {
    const isLong = openEntry.signal === 'EnterLong';
    const dir = isLong ? 'Long' : 'Short';
    const entrySpread = simEntrySpread(openEntry.bar.spreadPercent ?? 0, dir);
    const cursorBar = cursorIndex >= 0 ? allPoints[cursorIndex] : null;
    const endDate = cursorBar?.tradeDate ?? openEntry.bar.tradeDate;
    let pnlMin = null;
    let pnlMax = null;
    let milestones = {
      hit1Date: null, hit2Date: null, hit3Date: null,
      hit1Ms: null, hit2Ms: null, hit3Ms: null,
    };
    if (!skipExtras) {
      ({ min: pnlMin, max: pnlMax } = computeTradePnlMinMax(
        allPoints,
        openEntry.bar.tradeDate,
        endDate,
        dir,
        entrySpread,
        false,
        entryConstants,
      ));
      milestones = computeTradePnlMilestones(
        allPoints,
        openEntry.bar.tradeDate,
        endDate,
        dir,
        entrySpread,
        false,
        entryConstants,
      );
    }
    rows.push(makeTradeRow({
      index: tradeNo,
      direction: dir,
      entryDate: openEntry.bar.tradeDate,
      exitDate: '—',
      entryZ: openEntry.bar.zScore,
      exitZ: cursorBar?.zScore ?? null,
      entrySpread,
      exitSpread: null,
      entrySlip: getSimSlippageSpreadPts(),
      pnlPts: null,
      gross: null,
      commission: null,
      overnight: null,
      net: null,
      pnlMin,
      pnlMax,
      hit1Date: milestones.hit1Date,
      hit2Date: milestones.hit2Date,
      hit3Date: milestones.hit3Date,
      hit1Ms: milestones.hit1Ms,
      hit2Ms: milestones.hit2Ms,
      hit3Ms: milestones.hit3Ms,
      status: 'Открыта',
      entryThreshold,
    }));
  }
  return rows;
}

function formatZScore(z) {
  if (z == null || !Number.isFinite(z)) return '—';
  const sign = z > 0 ? '+' : '';
  return `${sign}${z.toFixed(2)}`;
}

function makeTradeRow(t) {
  const closed = t.status === 'Закрыта';
  const hasNet = t.net != null && Number.isFinite(Number(t.net));
  const hasGross = t.gross != null && Number.isFinite(Number(t.gross));
  const hasComm = t.commission != null && Number.isFinite(Number(t.commission));
  const hasOvn = t.overnight != null && Number.isFinite(Number(t.overnight));
  return {
    index: t.index,
    direction: t.direction,
    lots: t.lots ?? null,
    source: t.source || null,
    entryDate: t.entryDate,
    exitDate: t.exitDate,
    duration: closed ? formatSimTradeDuration(t.entryDate, t.exitDate) : '—',
    durationTone: closed ? durationTone(t.entryDate, t.exitDate) : 'neutral',
    net: closed && hasNet ? formatRub(t.net) : '—',
    netValue: hasNet ? Number(t.net) : null,
    accountAfter: t.accountAfter != null && Number.isFinite(Number(t.accountAfter))
      ? formatAccountRub(Number(t.accountAfter))
      : '—',
    accountAfterValue: t.accountAfter != null && Number.isFinite(Number(t.accountAfter))
      ? Number(t.accountAfter)
      : null,
    pnlMin: t.pnlMin != null ? formatRub(t.pnlMin) : '—',
    pnlMax: t.pnlMax != null ? formatRub(t.pnlMax) : '—',
    pnlMinValue: t.pnlMin,
    pnlMaxValue: t.pnlMax,
    hit1Date: t.hit1Date || null,
    hit2Date: t.hit2Date || null,
    hit3Date: t.hit3Date || null,
    hit1Ms: t.hit1Ms ?? null,
    hit2Ms: t.hit2Ms ?? null,
    hit3Ms: t.hit3Ms ?? null,
    hit1: t.hit1Date ? compactDateTime(t.hit1Date) : '—',
    hit2: t.hit2Date ? compactDateTime(t.hit2Date) : '—',
    hit3: t.hit3Date ? compactDateTime(t.hit3Date) : '—',
    hitPnl1: !!(t.hit1Ms ?? t.hit1Date),
    hitPnl2: !!(t.hit2Ms ?? t.hit2Date),
    hitPnl3: !!(t.hit3Ms ?? t.hit3Date),
    spreadEntry: t.entrySpread != null ? Number(t.entrySpread).toFixed(2) : '—',
    spreadExit: t.exitSpread != null ? Number(t.exitSpread).toFixed(2) : '—',
    spreadDelta: closed && t.pnlPts != null ? `${t.pnlPts >= 0 ? '+' : ''}${Number(t.pnlPts).toFixed(2)}` : '—',
    gross: closed && hasGross ? formatRub(t.gross) : '—',
    grossValue: hasGross ? Number(t.gross) : null,
    commission: closed && hasComm ? formatCostRub(t.commission) : '—',
    overnight: closed && hasOvn ? formatCostRub(t.overnight) : '—',
    spreadEntryValue: t.entrySpread != null ? Number(t.entrySpread) : null,
    spreadExitValue: t.exitSpread != null ? Number(t.exitSpread) : null,
    spreadDeltaValue: t.pnlPts ?? null,
    slip: t.entrySlip != null && Number.isFinite(Number(t.entrySlip))
      ? Number(t.entrySlip).toFixed(2)
      : '—',
    slipValue: t.entrySlip != null && Number.isFinite(Number(t.entrySlip))
      ? Number(t.entrySlip)
      : null,
    commissionValue: hasComm ? Number(t.commission) : null,
    overnightValue: hasOvn ? Number(t.overnight) : null,
    durationMs: t.exitDate === '—' ? null : computeTradeDurationMs(t.entryDate, t.exitDate),
    risk: closed
      ? (t._risk?.flagsText ?? '—')
      : '—',
    riskScore: closed ? (t._risk?.score ?? 0) : 0,
    riskLevel: closed ? (t._risk?.level ?? 'None') : 'None',
    riskRed: closed ? !!t._risk?.isRed : false,
    status: t.status,
    entryZ: t.entryZ,
    exitZ: t.exitZ ?? null,
    entryZText: formatZScore(t.entryZ),
    exitZText: formatZScore(t.exitZ),
  };
}

/** Map Prod closed trade (API / store) → same row shape as Testing table. */
function liveClosedToTradeRow(t, index, entryThreshold = 1.3) {
  const dirRaw = String(t.direction || '').toUpperCase();
  const direction = dirRaw.includes('SHORT') ? 'Short' : 'Long';
  const entrySpread = t.entry_spread != null && Number.isFinite(Number(t.entry_spread))
    ? Number(t.entry_spread) : null;
  const exitSpread = t.exit_spread != null && Number.isFinite(Number(t.exit_spread))
    ? Number(t.exit_spread) : null;
  let pnlPts = t.pnl_pts != null && Number.isFinite(Number(t.pnl_pts))
    ? Number(t.pnl_pts)
    : null;
  if (pnlPts == null && entrySpread != null && exitSpread != null) {
    pnlPts = direction === 'Long' ? exitSpread - entrySpread : entrySpread - exitSpread;
  }
  const net = t.pnl_rub != null && Number.isFinite(Number(t.pnl_rub)) ? Number(t.pnl_rub) : null;
  const gross = t.gross_rub != null && Number.isFinite(Number(t.gross_rub))
    ? Number(t.gross_rub)
    : (net != null ? net : null);
  const commission = t.commission_rub != null && Number.isFinite(Number(t.commission_rub))
    ? Number(t.commission_rub) : null;
  const overnight = t.overnight_rub != null && Number.isFinite(Number(t.overnight_rub))
    ? Number(t.overnight_rub) : null;
  const pnlMin = t.pnl_min_rub != null && Number.isFinite(Number(t.pnl_min_rub))
    ? Number(t.pnl_min_rub) : null;
  const pnlMax = t.pnl_max_rub != null && Number.isFinite(Number(t.pnl_max_rub))
    ? Number(t.pnl_max_rub) : null;
  const entryZ = t.entry_z != null && Number.isFinite(Number(t.entry_z)) ? Number(t.entry_z) : null;
  const exitZ = t.exit_z != null && Number.isFinite(Number(t.exit_z)) ? Number(t.exit_z) : null;
  const entrySlip = t.entry_slip_pts != null && Number.isFinite(Number(t.entry_slip_pts))
    ? Number(t.entry_slip_pts)
    : null;
  const risk = assessTradeRisk(
    t.entry_time,
    t.exit_time || t.entry_time,
    entryZ,
    overnight || 0,
    entryThreshold,
  );
  return makeTradeRow({
    index,
    direction,
    lots: t.quantity_lots != null ? Number(t.quantity_lots) : null,
    source: t.source || null,
    entryDate: t.entry_time,
    exitDate: t.exit_time || '—',
    entryZ,
    exitZ,
    entrySpread,
    exitSpread,
    entrySlip,
    pnlPts,
    gross,
    commission,
    overnight,
    net,
    accountAfter: t.account_after_rub != null && Number.isFinite(Number(t.account_after_rub))
      ? Number(t.account_after_rub)
      : (t.accountAfter != null && Number.isFinite(Number(t.accountAfter))
        ? Number(t.accountAfter)
        : null),
    pnlMin,
    pnlMax,
    hit1Date: t.hit1_time || null,
    hit2Date: t.hit2_time || null,
    hit3Date: t.hit3_time || null,
    hit1Ms: t.hit1_time ? parseTradeMs(t.hit1_time) : null,
    hit2Ms: t.hit2_time ? parseTradeMs(t.hit2_time) : null,
    hit3Ms: t.hit3_time ? parseTradeMs(t.hit3_time) : null,
    status: 'Закрыта',
    entryThreshold,
    _risk: risk,
  });
}

function computeTradeDurationMs(entryDate, exitDate) {
  const entryMs = parseTradeMs(entryDate);
  const exitMs = parseTradeMs(exitDate);
  if (entryMs == null || exitMs == null) return null;
  return exitMs - entryMs;
}

function tradeSortValue(row, colKey) {
  switch (colKey) {
    case 'Index': return row.index;
    case 'Direction': return row.direction === 'Long' ? 0 : 1;
    case 'Lots': return row.lots;
    case 'Source': return row.source || null;
    case 'Entry': return parseTradeMs(row.entryDate);
    case 'Exit':
      if (row.exitDate === '—') return null;
      return parseTradeMs(row.exitDate);
    case 'EntryZ': return row.entryZ;
    case 'ExitZ': return row.exitZ;
    case 'Duration': return row.durationMs;
    case 'Net': return row.netValue;
    case 'AccountAfter': return row.accountAfterValue;
    case 'PnlMin': return row.pnlMinValue;
    case 'PnlMax': return row.pnlMaxValue;
    case 'Hit1': return row.hit1Ms;
    case 'Hit2': return row.hit2Ms;
    case 'Hit3': return row.hit3Ms;
    case 'SpreadEntry': return row.spreadEntryValue;
    case 'SpreadExit': return row.spreadExitValue;
    case 'SpreadDelta': return row.spreadDeltaValue;
    case 'Slip': return row.slipValue;
    case 'Gross': return row.grossValue;
    case 'Commission': return row.commissionValue;
    case 'Overnight': return row.overnightValue;
    case 'Risk':
      return row.risk === '—' ? null : row.risk;
    default:
      return null;
  }
}

function compareTradeRows(a, b, colKey, dir) {
  const va = tradeSortValue(a, colKey);
  const vb = tradeSortValue(b, colKey);
  const asc = dir === 'asc';

  const nullish = (v) => v == null || (typeof v === 'number' && Number.isNaN(v));
  const aNull = nullish(va);
  const bNull = nullish(vb);
  if (aNull && bNull) return a.index - b.index;
  if (aNull) return 1;
  if (bNull) return -1;

  let cmp = 0;
  if (typeof va === 'string' && typeof vb === 'string') {
    cmp = va.localeCompare(vb, 'ru', { numeric: true, sensitivity: 'base' });
  } else {
    cmp = va < vb ? -1 : va > vb ? 1 : 0;
  }
  if (cmp === 0) return a.index - b.index;
  return asc ? cmp : -cmp;
}

/** Чист. — сумма; Hit1/2/3 — кол-во; Сумма после — последнее; остальные числовые — среднее. */
const TRADE_AGG_SUM_KEYS = new Set(['Net']);
const TRADE_AGG_COUNT_KEYS = new Set(['Hit1', 'Hit2', 'Hit3']);
const TRADE_AGG_LAST_KEYS = new Set(['AccountAfter']);
const TRADE_AGG_AVG_KEYS = new Set([
  'Lots',
  'EntryZ',
  'ExitZ',
  'Duration',
  'PnlMin',
  'PnlMax',
  'SpreadEntry',
  'SpreadExit',
  'SpreadDelta',
  'Slip',
  'Gross',
  'Commission',
  'Overnight',
  'Risk',
]);

function tradeAggMode(colKey) {
  if (TRADE_AGG_SUM_KEYS.has(colKey)) return 'sum';
  if (TRADE_AGG_COUNT_KEYS.has(colKey)) return 'count';
  if (TRADE_AGG_LAST_KEYS.has(colKey)) return 'last';
  if (TRADE_AGG_AVG_KEYS.has(colKey)) return 'avg';
  return null;
}

function formatTradeAggValue(colKey, value) {
  if (value == null || !Number.isFinite(value)) return '—';
  switch (colKey) {
    case 'Hit1':
    case 'Hit2':
    case 'Hit3':
      return String(Math.round(value));
    case 'Lots':
      return (Math.round(value * 10) / 10).toFixed(value % 1 ? 1 : 0);
    case 'EntryZ':
    case 'ExitZ':
      return formatZScore(value);
    case 'Duration':
      return formatDurationMinutes(value / 60000);
    case 'SpreadEntry':
    case 'SpreadExit':
    case 'Slip':
      return value.toFixed(2);
    case 'SpreadDelta': {
      const sign = value > 0 ? '+' : value < 0 ? '' : '';
      return `${sign}${value.toFixed(2)}`;
    }
    case 'Net':
    case 'Gross':
    case 'PnlMin':
    case 'PnlMax':
      return formatRub(value);
    case 'Commission':
    case 'Overnight':
      return formatCostRub(Math.max(0, value));
    case 'AccountAfter':
      return formatAccountRub(value);
    case 'Risk':
      return (Math.round(value * 10) / 10).toFixed(value % 1 ? 1 : 0);
    default:
      return String(value);
  }
}

/**
 * Сводка по видимым колонкам: { key, mode, value, text, cls }.
 * mode: 'sum' | 'avg' | 'count' | null
 */
function buildTradeColumnSummary(rows, colKeys) {
  const list = rows || [];
  const n = list.length;
  return (colKeys || []).map((key) => {
    const mode = tradeAggMode(key);
    if (!mode) {
      if (key === 'Index') {
        return {
          key,
          mode: null,
          value: n,
          text: n ? `n=${n}` : '—',
          cls: 'history-agg-label',
          title: 'Число сделок в выборке',
        };
      }
      return { key, mode: null, value: null, text: '', cls: '', title: '' };
    }
    if (mode === 'count') {
      let count = 0;
      for (const row of list) {
        const hit = key === 'Hit1' ? row.hitPnl1 || row.hit1Ms || (row.hit1Date && row.hit1 !== '—')
          : key === 'Hit2' ? row.hitPnl2 || row.hit2Ms || (row.hit2Date && row.hit2 !== '—')
            : row.hitPnl3 || row.hit3Ms || (row.hit3Date && row.hit3 !== '—');
        if (hit) count += 1;
      }
      return {
        key,
        mode,
        value: count,
        text: String(count),
        cls: count > 0 ? 'pnl-pos' : '',
        title: `Сработало · ${count} из ${n}`,
      };
    }
    if (mode === 'last') {
      // Последняя по времени выхода (или входа) ненулевая сумма на счету
      let bestMs = -1;
      let bestVal = null;
      for (const row of list) {
        const v = tradeSortValue(row, key);
        if (v == null || !Number.isFinite(Number(v))) continue;
        const exitMs = row.exitDate && row.exitDate !== '—'
          ? (parseTradeMs(row.exitDate) || 0)
          : 0;
        const entryMs = parseTradeMs(row.entryDate) || 0;
        const ms = Math.max(exitMs, entryMs);
        if (ms >= bestMs) {
          bestMs = ms;
          bestVal = Number(v);
        }
      }
      if (bestVal == null) {
        return { key, mode, value: null, text: '—', cls: '', title: 'Последняя сумма после' };
      }
      return {
        key,
        mode,
        value: bestVal,
        text: formatTradeAggValue(key, bestVal),
        cls: '',
        title: 'Последняя сумма на счету после сделки',
      };
    }
    let sum = 0;
    let count = 0;
    for (const row of list) {
      let v = tradeSortValue(row, key);
      if (key === 'Risk') {
        const num = typeof v === 'string' ? parseFloat(v) : Number(v);
        v = Number.isFinite(num) ? num : null;
      }
      if (v == null || !Number.isFinite(Number(v))) continue;
      sum += Number(v);
      count += 1;
    }
    if (!count) {
      return {
        key,
        mode,
        value: null,
        text: '—',
        cls: '',
        title: mode === 'sum' ? 'Сумма' : 'Среднее',
      };
    }
    const value = mode === 'sum' ? sum : sum / count;
    const text = formatTradeAggValue(key, value);
    let cls = '';
    if (key === 'Net' || key === 'Gross' || key === 'SpreadDelta' || key === 'PnlMin' || key === 'PnlMax') {
      cls = value > 0 ? 'pnl-pos' : value < 0 ? 'pnl-neg' : '';
    }
    return {
      key,
      mode,
      value,
      text,
      cls,
      title: mode === 'sum'
        ? `Сумма · ${count} зн.`
        : `Среднее · ${count} зн.`,
    };
  });
}

function tradeCellValue(row, colKey) {
  switch (colKey) {
    case 'Index': return String(row.index);
    case 'Direction': return row.direction === 'Long' ? 'L' : 'S';
    case 'Lots': return row.lots != null ? String(row.lots) : '—';
    case 'Source': return row.source || '—';
    case 'Entry': return compactTradeDateTime(row.entryDate);
    case 'Exit': return row.exitDate === '—' ? '—' : compactTradeDateTime(row.exitDate);
    case 'EntryZ': return row.entryZText;
    case 'ExitZ': return row.exitZText;
    case 'Duration': return row.duration;
    case 'Net': return row.net;
    case 'AccountAfter': return row.accountAfter;
    case 'PnlMin': return row.pnlMin;
    case 'PnlMax': return row.pnlMax;
    case 'Hit1': return row.hit1;
    case 'Hit2': return row.hit2;
    case 'Hit3': return row.hit3;
    case 'SpreadEntry': return row.spreadEntry;
    case 'SpreadExit': return row.spreadExit;
    case 'SpreadDelta': return row.spreadDelta;
    case 'Slip': return row.slip;
    case 'Gross': return row.gross;
    case 'Commission': return row.commission;
    case 'Overnight': return row.overnight;
    case 'Risk': return row.risk;
    default: return '';
  }
}

function tradeCellClass(row, colKey) {
  if (colKey === 'Direction') return row.direction === 'Long' ? 'side-long' : 'side-short';
  if (colKey === 'Duration') {
    if (row.durationTone === 'short') return 'tone-short';
    if (row.durationTone === 'long') return 'tone-long';
  }
  if (colKey === 'Net' || colKey === 'Gross' || colKey === 'SpreadDelta' || colKey === 'PnlMin' || colKey === 'PnlMax') {
    const v = colKey === 'Net'
      ? row.netValue
      : colKey === 'Gross'
        ? row.grossValue
        : colKey === 'PnlMin'
          ? row.pnlMinValue
          : colKey === 'PnlMax'
            ? row.pnlMaxValue
            : row.spreadDeltaValue;
    if (v == null) return '';
    if (v > 0) return 'pnl-pos';
    if (v < 0) return 'pnl-neg';
  }
  if (colKey === 'Hit1' || colKey === 'Hit2' || colKey === 'Hit3') {
    const v = colKey === 'Hit1' ? row.hit1Ms : (colKey === 'Hit2' ? row.hit2Ms : row.hit3Ms);
    return v != null ? 'pnl-pos' : '';
  }
  if ((colKey === 'Commission' || colKey === 'Overnight') && row[colKey] !== '—') return 'cost';
  if (colKey === 'Risk' && row.risk !== '—') {
    return row.riskRed ? 'risk-flagged risk-red' : 'risk-flagged';
  }
  return '';
}

/** Numeric trade metrics that support hover distribution tips. */
const TRADE_METRIC_KEYS = new Set([
  'EntryZ',
  'ExitZ',
  'Duration',
  'Net',
  'PnlMin',
  'PnlMax',
  'SpreadEntry',
  'SpreadExit',
  'SpreadDelta',
  'Gross',
  'Commission',
  'Overnight',
]);

function isTradeMetricColumn(colKey) {
  return TRADE_METRIC_KEYS.has(colKey);
}

/** Raw numeric value used for histogram stats (Duration → minutes). */
function tradeMetricRawValue(row, colKey) {
  if (!TRADE_METRIC_KEYS.has(colKey)) return null;
  const v = tradeSortValue(row, colKey);
  if (v == null || typeof v !== 'number' || !Number.isFinite(v)) return null;
  if (colKey === 'Duration') return v / 60000;
  return v;
}

function formatTradeMetricStat(colKey, value) {
  if (value == null || !Number.isFinite(value)) return '—';
  switch (colKey) {
    case 'Duration':
      return formatDurationMinutes(value);
    case 'EntryZ':
    case 'ExitZ':
      return formatZScore(value);
    case 'Net':
    case 'PnlMin':
    case 'PnlMax':
    case 'Gross':
      return formatRub(value);
    case 'Commission':
    case 'Overnight':
      return formatCostRub(Math.abs(value));
    case 'SpreadEntry':
    case 'SpreadExit':
      return value.toFixed(2);
    case 'SpreadDelta': {
      const sign = value > 0 ? '+' : '';
      return `${sign}${value.toFixed(2)}`;
    }
    default:
      return String(Math.round(value * 100) / 100);
  }
}

function computeNumericDistribution(values, binTarget = 24) {
  const n = values.length;
  if (n === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const min = sorted[0];
  const max = sorted[n - 1];
  let sum = 0;
  for (const v of values) sum += v;
  const mean = sum / n;
  let varAcc = 0;
  for (const v of values) varAcc += (v - mean) ** 2;
  const stdev = n > 1 ? Math.sqrt(varAcc / (n - 1)) : 0;
  const binCount = Math.min(28, Math.max(10, Math.round(Math.sqrt(n) * 2.2) || binTarget));
  let lo = min;
  let hi = max;
  if (hi <= lo) {
    const pad = Math.max(Math.abs(lo) * 0.05, 1e-6);
    lo -= pad;
    hi += pad;
  }
  const width = (hi - lo) / binCount;
  const bins = new Array(binCount).fill(0);
  for (const v of values) {
    let i = Math.floor((v - lo) / width);
    if (i < 0) i = 0;
    if (i >= binCount) i = binCount - 1;
    bins[i] += 1;
  }
  return { n, min, max, mean, stdev, bins, lo, hi, width, binCount, sorted };
}

function buildTradeMetricDistributions(rows, colKeys) {
  const out = {};
  for (const key of colKeys) {
    if (!TRADE_METRIC_KEYS.has(key)) continue;
    const values = [];
    for (const row of rows) {
      const v = tradeMetricRawValue(row, key);
      if (v != null) values.push(v);
    }
    const dist = computeNumericDistribution(values);
    if (dist) out[key] = dist;
  }
  return out;
}

function tradeMetricBinIndex(dist, value) {
  if (!dist || value == null || !Number.isFinite(value)) return -1;
  let i = Math.floor((value - dist.lo) / dist.width);
  if (i < 0) i = 0;
  if (i >= dist.binCount) i = dist.binCount - 1;
  return i;
}

function tradeMetricPercentile(dist, value) {
  if (!dist || !dist.sorted.length || value == null || !Number.isFinite(value)) return null;
  let count = 0;
  for (const v of dist.sorted) {
    if (v <= value) count += 1;
  }
  return (count / dist.sorted.length) * 100;
}

/**
 * Placement of a value vs sample mean/σ.
 * |z|<0.5 near mean · <1 shoulder · <2 tail · ≥2 outside.
 */
function classifyTradeMetricPlacement(value, dist) {
  if (!dist || value == null || !Number.isFinite(value)) {
    return { key: 'none', label: 'нет данных', z: null };
  }
  if (dist.n < 2) {
    return { key: 'sparse', label: 'мало данных', z: null };
  }
  if (!(dist.stdev > 1e-12)) {
    return { key: 'flat', label: 'как у всех', z: 0 };
  }
  const z = (value - dist.mean) / dist.stdev;
  const az = Math.abs(z);
  if (az < 0.5) return { key: 'mean', label: 'ближе к среднему', z };
  if (az < 1) return { key: 'shoulder', label: 'на плече распределения', z };
  if (az < 2) return { key: 'tail', label: 'в хвосте', z };
  return { key: 'outside', label: 'вне типичного диапазона', z };
}

/** Y ticks 0 … maxBin for metric histogram tip. */
function metricDistCountTicks(maxCount) {
  const max = Math.max(1, Math.round(Number(maxCount) || 1));
  if (max <= 2) return [0, max];
  const mid = Math.round(max / 2);
  if (mid === 0 || mid === max) return [0, max];
  return [0, mid, max];
}

/**
 * HTML гистограммы распределения (как tip в таблице сделок).
 * @param {{ title: string, display: string, value: number, dist: object, formatStat?: (v:number)=>string }} opts
 */
function buildMetricDistTipHtml(opts) {
  const { title, display, value, dist } = opts;
  const formatStat = opts.formatStat || ((v) => (Number.isFinite(v) ? String(Math.round(v * 100) / 100) : '—'));
  if (!dist || value == null || !Number.isFinite(value)) return '';
  const place = classifyTradeMetricPlacement(value, dist);
  const pct = tradeMetricPercentile(dist, value);
  const activeBin = tradeMetricBinIndex(dist, value);
  const maxBin = Math.max(1, ...dist.bins);
  const meanLabel = formatStat(dist.mean);
  const sigmaLabel = formatStat(dist.stdev);
  const barsHtml = dist.bins.map((count, i) => {
    const h = Math.max(4, Math.round((count / maxBin) * 100));
    const cls = i === activeBin ? ' active' : '';
    return `<span class="tm-bar${cls}" style="height:${h}%" title="${count}"></span>`;
  }).join('');
  const countTicks = metricDistCountTicks(maxBin);
  const yLabelsHtml = countTicks.map((t) => {
    const p = (t / maxBin) * 100;
    return `<span class="tm-y-label" style="bottom:${p}%">${t}</span>`;
  }).join('');
  const gridHtml = countTicks.map((t) => {
    const p = (t / maxBin) * 100;
    return `<span class="tm-grid-line" style="bottom:${p}%"></span>`;
  }).join('');
  const pctText = pct != null ? ` · p${Math.round(pct)}` : '';
  return (
    `<div class="tm-title">${title} · <strong>${display}</strong></div>`
    + `<div class="tm-hist-wrap" aria-hidden="true">`
    + `<div class="tm-hist-scale">`
    + `<div class="tm-hist-y">${yLabelsHtml}</div>`
    + `<div class="tm-hist-plot">`
    + `<div class="tm-hist-grid">${gridHtml}</div>`
    + `<div class="tm-hist">${barsHtml}</div>`
    + `</div></div>`
    + `<div class="tm-hist-x"><span>${formatStat(dist.min)}</span><span>${meanLabel}</span><span>${formatStat(dist.max)}</span></div>`
    + `</div>`
    + `<div class="tm-meta">ср. ${meanLabel} · σ ${sigmaLabel} · n=${dist.n}${pctText}</div>`
    + `<div class="tm-place tm-place-${place.key}">${place.label}</div>`
  );
}

function compactDateTime(label) {
  if (!label || label === '—') return '—';
  const s = String(label).replace('T', ' ');
  if (s.length >= 16) return `${s.slice(5, 10)} ${s.slice(11, 16)}`;
  return s;
}

/** Вход/выход в таблице сделок — с годом (для длинной истории). */
function compactTradeDateTime(label) {
  if (!label || label === '—') return '—';
  const s = String(label).replace('T', ' ');
  if (s.length >= 16) return `${s.slice(0, 10)} ${s.slice(11, 16)}`;
  return s;
}
