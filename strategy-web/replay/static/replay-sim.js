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
/** @deprecated старая %‑модель; Test использует ступени Премиум (см. simOvernightPerDayRub). */
const SIM_OVERNIGHT_FEE_PCT_PER_DAY = 0.033;

/** T‑Invest Премиум: ₽/день по сумме непокрытой (короткая нога). */
function simOvernightPerDayRub(uncoveredRub) {
  const u = Math.max(0, Number(uncoveredRub) || 0);
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

const TRADE_COLUMNS = [
  // слева — сделка и тех. индикаторы
  { key: 'Index', title: '#', width: 28 },
  { key: 'Direction', title: 'Напр.', width: 40 },
  { key: 'Lots', title: 'Лоты', width: 40, hint: 'Лоты пары TATN+TATNP: floor(депозит×плечо / цена пары), без потолка 80. Капитализация — шаг 1 лот.' },
  { key: 'Source', title: 'Src', width: 52, hint: 'Источник: AUTO / BROKER / MANUAL / AUTO_TP (Prod); в Тесте — база / добор / экстра / полка' },
  {
    key: 'Comment',
    title: 'Коммент.',
    width: 140,
    hint: 'Комментарий к сделке (вход / выход). Не путать с «Ком.» = комиссия',
  },
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
  {
    key: 'Invest',
    title: 'Вложения',
    width: 64,
    // Депозит на пару (база %), не номинал×плечо. См. resolveTradeInvestRub.
    hint: 'Капитал на пару (депозит входа): execution_notional/плечо или entry_deposit — та же база %, что в оверлее открытой',
  },
  {
    key: 'Net',
    title: 'Чист.',
    width: 88,
    hint: 'Prod: Δ счёта (После−До). Test: модель вал−ком−овн (или Δ счёта в режиме «как Прод»). Не путать с Max. В итоге: сумма и % от Σ Вложения',
  },
  { key: 'Gross', title: 'Вал.', width: 52, hint: 'PnL по спреду до комиссии и overnight (не Δ счёта; Вал.−Ком.≠Чист. на Prod)' },
  { key: 'Commission', title: 'Ком.', width: 48, hint: 'Оценка комиссии вход+выход; в Prod «Чист.» (Δ счёта) уже внутри брокера' },
  {
    key: 'Overnight',
    title: 'Овн.',
    width: 48,
    hint: 'Оценка overnight (Премиум: ступени ₽/день на ~короткую ногу). В Test «Чист.» вычитается; в Prod «Чист.» (Δ счёта) уже внутри брокера',
  },
  { key: 'AccountBefore', title: 'До', width: 72, hint: 'Сумма на счету на входе (база Чист. = после − до) · только Prod' },
  { key: 'AccountAfter', title: 'После', width: 72, hint: 'Сумма на счету после выхода (= after в Δ Чист.) · только Prod' },
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
  if (ts == null || ts === '' || ts === '—') return null;
  if (typeof ts === 'number') {
    if (!Number.isFinite(ts) || ts <= 0) return null;
    if (ts > 1e12) return ts;
    if (ts > 1e9) return ts * 1000;
    return null;
  }
  const s = String(ts).trim().replace('T', ' ');
  if (!s || s === '—') return null;
  let iso;
  if (s.length >= 16) {
    iso = `${s.slice(0, 16).replace(' ', 'T')}:00+03:00`;
  } else if (s.length >= 10 && s[4] === '-' && s[7] === '-') {
    // sqlite date-only ("2026-03-03"): "…+03:00" → Invalid Date / NaN в LC.
    iso = `${s.slice(0, 10)}T00:00:00+03:00`;
  } else {
    return null;
  }
  const ms = new Date(iso).getTime();
  return Number.isFinite(ms) && ms > 0 ? ms : null;
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

/** Баланс счёта — полные рубли с пробелом тысяч (99 715), без k. */
function formatAccountRub(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '—';
  const sign = n < 0 ? '−' : '';
  return `${sign}${Math.round(Math.abs(n)).toLocaleString('ru-RU', { useGrouping: true })}`;
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
 * Геометрия спреда (S≈вход / S против / нет хода) вместо старых Z<1 и ~порог Z.
 */
const DEFAULT_SPREAD_RISK_LEVELS = {
  enter_wide: 6.1,
  exit_wide: 5.8,
  enter_narrow: 3.2,
  exit_narrow: 4.0,
};
const SPREAD_RISK_WEAK_ENTRY_PP = 0.15;
const SPREAD_RISK_NO_PROGRESS_PP = 0.20;
const SPREAD_RISK_AGAINST_PP = 0.20;

function normalizeSpreadRiskLevels(src) {
  const d = DEFAULT_SPREAD_RISK_LEVELS;
  if (!src || typeof src !== 'object') return { ...d };
  const pick = (...keys) => {
    for (const k of keys) {
      const x = Number(src[k]);
      if (Number.isFinite(x)) return x;
    }
    return null;
  };
  return {
    enter_wide: pick('enter_wide', 'spread_enter_wide') ?? d.enter_wide,
    exit_wide: pick('exit_wide', 'spread_exit_wide') ?? d.exit_wide,
    enter_narrow: pick('enter_narrow', 'spread_enter_narrow') ?? d.enter_narrow,
    exit_narrow: pick('exit_narrow', 'spread_exit_narrow') ?? d.exit_narrow,
  };
}

function spreadLevelsForTrade(t, settings) {
  const pair = String(t?.pair_id || t?.pair || t?.ord_ticker || '').toUpperCase();
  if (pair.includes('MTLR')) {
    const s = settings || {};
    const n = (k, fb) => {
      const x = Number(s[k]);
      return Number.isFinite(x) ? x : fb;
    };
    return {
      enter_wide: n('mtlr_enter_wide', 8.9),
      exit_wide: n('mtlr_exit_wide', 8.4),
      enter_narrow: n('mtlr_enter_narrow', 3.2),
      exit_narrow: n('mtlr_exit_narrow', 4.3),
    };
  }
  return normalizeSpreadRiskLevels(settings);
}

function spreadRiskFlags({ direction, entrySpread, spreadNow, holdHours, levels }) {
  const flags = [];
  let score = 0;
  const d = String(direction || '').toUpperCase();
  const isShort = d.includes('SHORT');
  const isLong = d.includes('LONG');
  const e = Number(entrySpread);
  const n = Number(spreadNow);
  const hold = Number(holdHours) || 0;
  const lv = normalizeSpreadRiskLevels(levels);
  let depth = null;
  let progress = null;
  if (Number.isFinite(e) && (isLong || isShort)) {
    depth = isLong ? lv.enter_narrow - e : e - lv.enter_wide;
  }
  if (Number.isFinite(e) && Number.isFinite(n) && (isLong || isShort)) {
    progress = isLong ? n - e : e - n;
  }
  if (depth != null && depth >= 0 && depth < SPREAD_RISK_WEAK_ENTRY_PP && hold >= 6) {
    flags.push('S≈вход');
    score += 1;
  }
  if (progress != null && progress <= -SPREAD_RISK_AGAINST_PP) {
    flags.push('S против');
    score += 2;
  } else if (progress != null && progress < SPREAD_RISK_NO_PROGRESS_PP && hold >= 24) {
    flags.push('нет хода');
    score += 1;
  }
  return { flags, score };
}

function assessTradeRisk(entryDate, exitDate, entryZ, overnightRub, entryThreshold, extra) {
  const entryMs = parseTradeMs(entryDate);
  const exitMs = parseTradeMs(exitDate);
  const durationMs = entryMs != null && exitMs != null ? exitMs - entryMs : null;
  const flags = [];
  let holdPoints = 0;
  let overnightPoints = 0;
  let weakEntrySpreadPoints = 0;
  let entryHourPoints = 0;
  let fridayLongHoldPoints = 0;
  let spreadPathPoints = 0;
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
  const holdHours = durationMs != null ? durationMs / 3600000 : 0;
  const geo = spreadRiskFlags({
    direction: extra && extra.direction,
    entrySpread: extra && extra.entrySpread,
    spreadNow: extra && (extra.exitSpread != null ? extra.exitSpread : extra.spreadNow),
    holdHours,
    levels: extra && extra.levels,
  });
  for (const f of geo.flags) flags.push(f);
  if (geo.flags.includes('S≈вход')) weakEntrySpreadPoints = 1;
  if (geo.flags.includes('S против')) spreadPathPoints = 2;
  else if (geo.flags.includes('нет хода')) spreadPathPoints = 1;
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

  const score = holdPoints + overnightPoints + weakEntrySpreadPoints
    + entryHourPoints + fridayLongHoldPoints + spreadPathPoints;
  const level = score >= 6 ? 'Critical' : score >= 4 ? 'High' : score >= 3 ? 'Elevated' : 'None';
  return {
    flagsText: flags.length ? flags.join(' ') : '—',
    score,
    level,
    isRed: score >= 4,
    flags,
  };
}

/** Расшифровка флагов риска для tooltip столбца «Риск». */
const TRADE_RISK_FLAG_HELP = {
  '>5д': { text: 'Сделка длилась более 5 календарных дней', points: 4 },
  '>2д': { text: 'Сделка длилась более 2 календарных дней', points: 3 },
  Ovn100: { text: 'Overnight больше 100 ₽', points: 2 },
  Ovn50: { text: 'Overnight больше 50 ₽ при сделке длиннее суток', points: 2 },
  'S≈вход': { text: 'Спред почти у входа — слабое движение от порога (≥6 ч)', points: 1 },
  'S против': { text: 'Спред пошёл против позиции (≥0,2 п.п.)', points: 2 },
  'нет хода': { text: 'За 24+ ч нет прогресса к уровню выхода', points: 1 },
  '13ч': { text: 'Вход около 13:00 МСК', points: 1 },
  '12–14': { text: 'Вход в интервале 12:00–14:00 МСК', points: 1 },
  'Пт>2д': { text: 'Вход в пятницу и удержание более 2 дней', points: 1 },
};

const TRADE_RISK_LEVEL_RU = {
  Critical: 'Критический',
  High: 'Высокий',
  Elevated: 'Повышенный',
  None: 'Норма',
};

/**
 * HTML-подсказка по флагам риска одной сделки (столбец «Риск»).
 * @param {{ risk: string, riskScore?: number, riskLevel?: string, riskRed?: boolean }} row
 */
function buildTradeRiskTipHtml(row) {
  if (!row || !row.risk || row.risk === '—') return '';
  const flags = String(row.risk).split(/\s+/).filter(Boolean);
  const score = Number(row.riskScore) || 0;
  const levelKey = String(row.riskLevel || 'None');
  const levelRu = TRADE_RISK_LEVEL_RU[levelKey] || levelKey;
  const zoneCls = row.riskRed ? 'tm-risk-red' : (score >= 3 ? 'tm-risk-warn' : 'tm-risk-ok');
  const items = flags.length
    ? flags.map((f) => {
      const h = TRADE_RISK_FLAG_HELP[f];
      if (!h) return `<li><strong>${f}</strong></li>`;
      const pts = h.points ? ` (+${h.points})` : '';
      return `<li><strong>${f}</strong>${pts} — ${h.text}</li>`;
    }).join('')
    : '<li class="tm-risk-none">Флагов нет — score 0</li>';
  return (
    `<div class="tm-title">Риск · <strong>${row.risk}</strong></div>`
    + `<div class="tm-risk-summary ${zoneCls}">`
    + `Уровень: ${levelRu} · score ${score}`
    + (row.riskRed ? ' · красная зона' : '')
    + `</div>`
    + `<ul class="tm-risk-flags">${items}</ul>`
    + `<div class="tm-meta">Красная зона: score ≥ 4 (High / Critical). S≈вход / S против — геометрия спреда.</div>`
  );
}

function buildTradeRiskFlags(entryDate, exitDate, entryZ, overnightRub, entryThreshold, extra) {
  return assessTradeRisk(entryDate, exitDate, entryZ, overnightRub, entryThreshold, extra).flagsText;
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
  const liveNone = new Uint8Array(len);
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
    liveNone[i] = String(p?.liveSignal || '').toUpperCase() === 'NONE' ? 1 : 0;
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
  return { z, spread, session, consec, dayNum, liveNone, len };
}

/**
 * Быстрый heatmap: Z-сигналы + опционально TP (как openTradeNetRub MTM).
 * Protective risk (Z±/DD/Time/Money) — не здесь.
 */
function heatmapFastNetFromSeries(series, entry, exit, simOpts) {
  const { z, spread, session, consec, dayNum, liveNone, len } = series;
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
    // decision_bars.signal=NONE → не открывать по geometric cross
    if (liveNone && liveNone[i] && (signal === 1 || signal === 2)) signal = 0;
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

  const REF_ACCOUNT = 100000;
  const refClosed = closed.filter(
    (r) => r.netRef100kValue != null && Number.isFinite(r.netRef100kValue),
  );
  let totalPnlRef100k = 0;
  let maxDdRef100k = 0;
  let retPctRef100k = null;
  if (refClosed.length) {
    let cum = 0;
    let peak = 0;
    for (const t of refClosed) {
      cum += t.netRef100kValue;
      if (cum > peak) peak = cum;
      const dd = peak - cum;
      if (dd > maxDdRef100k) maxDdRef100k = dd;
    }
    totalPnlRef100k = cum;
    retPctRef100k = (totalPnlRef100k / REF_ACCOUNT) * 100;
  }

  return {
    closedCount: closed.length,
    openCount: open.length,
    redCount: redClosed.length,
    totalPnl,
    retPct,
    totalPnlRef100k,
    retPctRef100k,
    maxDdRef100k,
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

/** Calendar month index for sort / gap fill (year*12 + month-1). */
function monthKeyToIndex(ymKey) {
  const [ys, ms] = String(ymKey).split('-');
  const y = parseInt(ys, 10);
  const m = parseInt(ms, 10);
  if (!Number.isFinite(y) || !Number.isFinite(m) || m < 1 || m > 12) return NaN;
  return y * 12 + (m - 1);
}

function indexToMonthKey(idx) {
  const y = Math.floor(idx / 12);
  const m = (idx % 12) + 1;
  return `${y}-${String(m).padStart(2, '0')}`;
}

/** Insert zero-PnL months between first and last trade month (continuous timeline). */
function fillMonthlyGaps(monthBuckets) {
  if (!monthBuckets.length) return monthBuckets;
  const sorted = [...monthBuckets].sort((a, b) => monthKeyToIndex(a.key) - monthKeyToIndex(b.key));
  const first = monthKeyToIndex(sorted[0].key);
  const last = monthKeyToIndex(sorted[sorted.length - 1].key);
  if (!Number.isFinite(first) || !Number.isFinite(last) || last <= first) return sorted;
  const byKey = new Map(sorted.map((m) => [m.key, m]));
  const out = [];
  for (let i = first; i <= last; i += 1) {
    const key = indexToMonthKey(i);
    out.push(byKey.get(key) || { key, pnl: 0, count: 0 });
  }
  return out;
}

/** Calendar YYYY-MM from trade date label (MSK), or null. */
function tradeMonthKeyFromLabel(label) {
  if (label == null || label === '—') return null;
  const s = String(label).trim().replace('T', ' ');
  const iso = s.match(/^(\d{4})-(\d{1,2})/);
  if (iso) {
    const y = parseInt(iso[1], 10);
    const m = parseInt(iso[2], 10);
    if (Number.isFinite(y) && Number.isFinite(m) && m >= 1 && m <= 12) {
      return `${y}-${String(m).padStart(2, '0')}`;
    }
  }
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
  const months = fillMonthlyGaps(Array.from(map.values()));
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
const TRADE_COLUMNS_MIG_VERSION = 13;

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
  if (ver < 9) {
    next = insertAfter(next, 'AccountBefore', 'Overnight');
    // «Сумма после» → короче «После»; колонка До перед После
    next = insertAfter(next, 'AccountAfter', 'AccountBefore');
    next = regroupTradeColumnKeys(next);
  }
  if (ver < 10) {
    // ModelNet («Оц.») больше нет — не вставляем
  }
  if (ver < 11) {
    next = next.filter((k) => k !== 'ModelNet');
    next = regroupTradeColumnKeys(next);
  }
  if (ver < 12) {
    next = insertAfter(next, 'Invest', 'Overnight');
    // Вложения перед Чист. (канон TRADE_COLUMNS)
    next = regroupTradeColumnKeys(next);
  }
  if (ver < 13) {
    next = insertAfter(next, 'Comment', 'Source');
    next = regroupTradeColumnKeys(next);
  }
  try {
    localStorage.setItem('moexReplay.tradeColumnsMig', String(TRADE_COLUMNS_MIG_VERSION));
  } catch (_) { /* ignore */ }
  return next;
}

/** Текст комментария: entry_comment / close_comment → одна ячейка. */
function formatTradeCommentText(entryComment, closeComment) {
  const e = String(entryComment || '').trim();
  const c = String(closeComment || '').trim();
  if (e && c) {
    if (e === c) return e;
    return `вход: ${e} · выход: ${c}`;
  }
  return e || c || '';
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
        {
          direction: dir,
          entrySpread,
          exitSpread,
          levels: opts.spreadLevels,
        },
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
        modelNet: net,
        netFromAccount: false,
        notional: entryConstants.notionalRub,
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
      notional: entryConstants.notionalRub,
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

/**
 * Вложения = депозит на пару (база % как в оверлее / hit1–3), не номинал×плечо.
 * Приоритет: entry_deposit → execution_notional/плечо → notional (Test) → ноги/плечо → settings.
 */
function resolveTradeInvestRub(t, settings = {}) {
  if (!t) return null;
  const fromDep = Number(t.entry_deposit_rub ?? t.entryDepositRub);
  if (Number.isFinite(fromDep) && fromDep > 0) return fromDep;

  const explicit = Number(t.invest);
  if (Number.isFinite(explicit) && explicit > 0) return explicit;

  const lev = Math.max(1, Number(settings.leverage ?? t.leverage) || SIM_LEVERAGE);
  const execNom = Number(t.execution_notional_rub ?? t.notional_rub);
  if (Number.isFinite(execNom) && execNom > 0) return execNom / lev;

  // Test/sim: notionalRub — уже депозит (см. simPnlConstants)
  const simDep = Number(t.notional);
  if (Number.isFinite(simDep) && simDep > 0) return simDep;

  const fromLegs = pairNotionalFromTradeLegs(t);
  if (fromLegs != null && fromLegs > 0) return fromLegs / lev;

  const settingsDep = Number(settings.entry_deposit_rub);
  if (Number.isFinite(settingsDep) && settingsDep > 0) return settingsDep;
  return null;
}

/** Номинал пары по ногам/ценам входа (если нет execution_notional). */
function pairNotionalFromTradeLegs(t) {
  const tatn = Number(t.entry_tatn ?? t.entryTatn);
  const tatnp = Number(t.entry_tatnp ?? t.entryTatnp);
  const lots = Number(t.quantity_lots ?? t.lots);
  const lotSize = Number(t.lot_size ?? t.lotSize) || 1;
  if (Number.isFinite(tatn) && Number.isFinite(tatnp) && Number.isFinite(lots) && lots > 0) {
    return (tatn + tatnp) * lotSize * lots;
  }
  let legs = t.legs || t.legs_json;
  if (typeof legs === 'string') {
    try { legs = JSON.parse(legs); } catch (_) { return null; }
  }
  if (!Array.isArray(legs) || !legs.length) return null;
  let sum = 0;
  let n = 0;
  for (const leg of legs) {
    const phase = String(leg.phase || leg.leg || leg.role || '').toLowerCase();
    if (phase.includes('exit') || phase.includes('close')) continue;
    const px = Number(leg.price ?? leg.average_price ?? leg.fill_price ?? leg.avg_price);
    const qty = Math.abs(Number(leg.quantity_lots ?? leg.lots ?? leg.quantity ?? leg.qty));
    if (!Number.isFinite(px) || !(px > 0) || !Number.isFinite(qty) || !(qty > 0)) continue;
    const ls = Number(leg.lot_size ?? leg.lotSize) || lotSize;
    sum += px * qty * ls;
    n += 1;
  }
  return n > 0 ? sum : null;
}

/** % от вложений в итоге Чист.: 3% / −0.8% (без «+» у плюса). */
function formatInvestPct(pct) {
  if (!Number.isFinite(pct)) return '';
  const abs = Math.abs(pct);
  const digits = abs >= 10 ? 0 : 1;
  return pct >= 0 ? `${abs.toFixed(digits)}%` : `−${abs.toFixed(digits)}%`;
}

function formatNetWithInvestPct(netRub, investRub) {
  const netText = formatRub(netRub);
  if (!(investRub > 0) || !Number.isFinite(netRub)) return netText;
  return `${netText} (${formatInvestPct((netRub / investRub) * 100)})`;
}

function makeTradeRow(t) {
  const closed = t.status === 'Закрыта';
  const hasNet = t.net != null && Number.isFinite(Number(t.net));
  const hasGross = t.gross != null && Number.isFinite(Number(t.gross));
  const hasComm = t.commission != null && Number.isFinite(Number(t.commission));
  const hasOvn = t.overnight != null && Number.isFinite(Number(t.overnight));
  let modelNetNum = t.modelNet != null && Number.isFinite(Number(t.modelNet))
    ? Number(t.modelNet)
    : null;
  if (modelNetNum == null && hasGross && hasComm) {
    modelNetNum = Number(t.gross) - Number(t.commission) - (hasOvn ? Number(t.overnight) : 0);
  }
  if (modelNetNum == null && hasNet && !t.netFromAccount) {
    modelNetNum = Number(t.net);
  }
  const hasModelNet = modelNetNum != null && Number.isFinite(modelNetNum);
  const netFromAccount = !!t.netFromAccount;
  let netTitle = '';
  if (closed && (hasGross || hasComm || hasNet)) {
    const bits = [];
    if (hasGross) bits.push(`вал ${formatRub(t.gross)}`);
    if (hasComm) bits.push(`ком ${formatCostRub(t.commission)}`);
    if (hasOvn && Number(t.overnight) !== 0) bits.push(`овн ${formatCostRub(t.overnight)}`);
    if (hasNet) bits.push(`чист ${formatRub(t.net)}`);
    netTitle = bits.join(' · ');
    if (netFromAccount) {
      netTitle = `Δ счёта (после − до входа)` + (bits.length ? ` · ${bits.join(' · ')}` : '');
      if (hasModelNet && Math.abs(modelNetNum - Number(t.net)) > 0.5) {
        netTitle += ` · оценка по спреду ${formatRub(modelNetNum)}`;
      }
    } else if (hasNet) {
      netTitle = `модель: вал − ком. − overnight` + (bits.length ? ` · ${bits.join(' · ')}` : '');
      const ab = t.accountBefore != null && Number.isFinite(Number(t.accountBefore))
        ? Number(t.accountBefore) : null;
      const aa = t.accountAfter != null && Number.isFinite(Number(t.accountAfter))
        ? Number(t.accountAfter) : null;
      if (ab != null && aa != null) {
        const accDelta = aa - ab;
        if (Math.abs(accDelta - Number(t.net)) > 500) {
          netTitle += ` · Δ счёта ${formatRub(accDelta)} (пополнение/вывод не в Чист.)`;
        }
      }
    }
  }
  const investValue = resolveTradeInvestRub(t, t._settings || {});
  const commentRaw = formatTradeCommentText(t.entryComment, t.closeComment);
  return {
    index: t.index,
    direction: t.direction,
    lots: t.lots ?? null,
    source: t.source || null,
    tag: t.tag || null,
    exitReason: t.exitReason || t.exit_reason || null,
    comment: commentRaw || '—',
    commentTitle: commentRaw || '',
    notional: t.notional != null && Number.isFinite(Number(t.notional))
      ? Number(t.notional)
      : null,
    investValue,
    invest: investValue != null ? formatAccountRub(investValue) : '—',
    entryDate: t.entryDate,
    exitDate: t.exitDate,
    exitFillDate: t.exitFillDate || null,
    exitTitle: t.exitTitle || '',
    duration: closed ? formatSimTradeDuration(t.entryDate, t.exitDate) : '—',
    durationTone: closed ? durationTone(t.entryDate, t.exitDate) : 'neutral',
    net: closed && hasNet
      ? formatNetWithInvestPct(Number(t.net), investValue)
      : '—',
    netValue: hasNet ? Number(t.net) : null,
    netRef100kValue: t.netRef100k != null && Number.isFinite(Number(t.netRef100k))
      ? Number(t.netRef100k)
      : null,
    netTitle: t.netTitle || netTitle,
    netFromAccount,
    chistFromModel: !!t.chistFromModel,
    modelNet: closed && hasModelNet ? formatRub(modelNetNum) : '—',
    modelNetValue: hasModelNet ? modelNetNum : null,
    accountBefore: t.accountBefore != null && Number.isFinite(Number(t.accountBefore))
      ? formatAccountRub(Number(t.accountBefore))
      : '—',
    accountBeforeValue: t.accountBefore != null && Number.isFinite(Number(t.accountBefore))
      ? Number(t.accountBefore)
      : null,
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

/**
 * Legacy fallback: если API ещё не отдал account_delta / account_before,
 * Чист. ≈ Δ «Сумма после» по хронологии. Не трогает netFromAccount.
 * Между сделками (пополнение и т.п.) этот fallback врёт — поэтому API
 * обязан отдавать account_before/delta.
 * Не перетираем Чист., уже выбранный как модель при выводе/пополнении.
 */
function applyAccountDeltaNetToRows(rows) {
  if (!rows || !rows.length) return rows;
  const chrono = [...rows].sort((a, b) => {
    const am = parseTradeMs(a.exitDate === '—' ? null : a.exitDate) || parseTradeMs(a.entryDate) || 0;
    const bm = parseTradeMs(b.exitDate === '—' ? null : b.exitDate) || parseTradeMs(b.entryDate) || 0;
    if (am !== bm) return am - bm;
    return (a.index || 0) - (b.index || 0);
  });
  let prev = null;
  for (const r of chrono) {
    const after = r.accountAfterValue;
    if (r.accountBeforeValue == null && after != null && Number.isFinite(after)) {
      if (r.netFromAccount && r.netValue != null && Number.isFinite(r.netValue)) {
        r.accountBeforeValue = after - r.netValue;
        r.accountBefore = formatAccountRub(r.accountBeforeValue);
      } else if (prev != null && Number.isFinite(prev)) {
        r.accountBeforeValue = prev;
        r.accountBefore = formatAccountRub(prev);
      }
    }
    // Уже посчитано в liveClosedToTradeRow (Δ счёта или модель при выводе) — не трогаем.
    if (r.netFromAccount || r.chistFromModel) {
      if (after != null && Number.isFinite(after)) prev = after;
      continue;
    }
    if (after != null && prev != null && Number.isFinite(after) && Number.isFinite(prev)) {
      const delta = after - prev;
      if (r.modelNetValue == null && r.netValue != null) r.modelNetValue = r.netValue;
      if (r.modelNetValue != null) r.modelNet = formatRub(r.modelNetValue);
      // Если Δ кошелька сильно расходится с моделью — это вывод/пополнение, не PnL.
      if (
        r.modelNetValue != null
        && Number.isFinite(r.modelNetValue)
        && Math.abs(delta - r.modelNetValue) > Math.max(5000, Math.abs(r.modelNetValue) * 3 + 1000)
      ) {
        r.netValue = r.modelNetValue;
        r.net = formatRub(r.modelNetValue);
        r.chistFromModel = true;
        r.netFromAccount = false;
        r.netTitle = `Чист. по модели спреда ${formatRub(r.modelNetValue)} · Δ счёта ${formatRub(delta)} (пополнение/вывод)`;
      } else {
        r.netValue = delta;
        r.net = formatRub(delta);
        r.netFromAccount = true;
        r.netTitle = 'Δ счёта (legacy: после − prev после)';
        if (r.modelNetValue != null && Math.abs(r.modelNetValue - delta) > 0.5) {
          r.netTitle += ` · оценка по спреду ${formatRub(r.modelNetValue)}`;
        }
      }
      if (r.accountBeforeValue == null) {
        r.accountBeforeValue = prev;
        r.accountBefore = formatAccountRub(prev);
      }
    }
    if (after != null && Number.isFinite(after)) prev = after;
  }
  return rows;
}

/** Map Prod closed trade (API / store) → same row shape as Testing table. */
function liveClosedToTradeRow(t, index, entryThreshold = 1.3, settings = {}) {
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
  const accountDelta = t.account_delta_rub != null && Number.isFinite(Number(t.account_delta_rub))
    ? Number(t.account_delta_rub)
    : null;
  const spreadPnl = t.spread_pnl_rub != null && Number.isFinite(Number(t.spread_pnl_rub))
    ? Number(t.spread_pnl_rub)
    : null;
  const storedPnl = t.pnl_rub != null && Number.isFinite(Number(t.pnl_rub)) ? Number(t.pnl_rub) : null;
  // Модель по спреду (вал−ком−овн), если есть
  let modelNet = spreadPnl;
  if (modelNet == null && storedPnl != null && accountDelta != null
      && Math.abs(storedPnl - accountDelta) > 0.05) {
    modelNet = storedPnl;
  }
  // Чист.: Δ счёта, но при пополнении/выводе (|Δ − модель| велик) — модель
  let net = accountDelta != null ? accountDelta : storedPnl;
  let chistFromModel = !!(t.chist_from_model || t.chistFromModel);
  if (accountDelta != null && modelNet != null && Number.isFinite(modelNet)) {
    const dep = Number(t.entry_deposit_rub ?? t.entryDepositRub ?? t.notional_rub ?? 10000) || 10000;
    const gap = Math.abs(accountDelta - modelNet);
    const thr = Math.max(5000, Math.abs(dep) * 0.5, Math.abs(modelNet) * 3 + 1000);
    if (gap > thr) {
      net = modelNet;
      chistFromModel = true;
    }
  }
  if (modelNet == null && accountDelta == null) modelNet = storedPnl;
  if (modelNet == null) modelNet = (accountDelta != null && storedPnl != null && Math.abs(storedPnl - accountDelta) > 0.05)
    ? storedPnl
    : (accountDelta == null ? storedPnl : null);
  const gross = t.gross_rub != null && Number.isFinite(Number(t.gross_rub))
    ? Number(t.gross_rub)
    : (modelNet != null ? modelNet : null);
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
  let entryComment = t.entry_comment || t.entryComment || null;
  let closeComment = t.close_comment || t.closeComment || null;
  if (!String(entryComment || '').trim() && !String(closeComment || '').trim()) {
    const legs = Array.isArray(t.legs) ? t.legs : [];
    const note = legs[0] && typeof legs[0] === 'object' ? String(legs[0].note || '') : '';
    if (/broker flat/i.test(note)) closeComment = 'сверка ghost';
  }
  const exitFill = t.exit_fill_time || t.exitFillTime || null;
  const exitSignal = t.exit_signal_time || t.exitSignalTime || t.exit_time || null;
  // Выход в UI: фактическое закрытие (fill), иначе бар сигнала
  const exitDisplay = (() => {
    const fill = exitFill ? String(exitFill) : '';
    const sig = t.exit_time ? String(t.exit_time) : '';
    if (fill && (!sig || fill.slice(0, 16) !== sig.slice(0, 16))) return fill;
    return sig || fill || t.entry_time;
  })();
  let exitTitle = '';
  if (exitFill && exitSignal && String(exitFill).slice(0, 16) !== String(exitSignal).slice(0, 16)) {
    exitTitle = `Закрытие ${String(exitFill).slice(0, 16)} · сигнал/бар ${String(exitSignal).slice(0, 16)}`;
  }
  if (chistFromModel && accountDelta != null && modelNet != null) {
    const tip = `Чист. по модели спреда ${formatRub(modelNet)} · Δ счёта ${formatRub(accountDelta)} (пополнение/вывод)`;
    exitTitle = exitTitle ? `${exitTitle} · ${tip}` : tip;
  }
  const risk = assessTradeRisk(
    t.entry_time,
    exitDisplay || t.exit_time || t.entry_time,
    entryZ,
    overnight || 0,
    entryThreshold,
    {
      direction,
      entrySpread,
      exitSpread,
      levels: spreadLevelsForTrade(t, settings),
    },
  );
  let accountBefore = t.account_before_rub != null && Number.isFinite(Number(t.account_before_rub))
    ? Number(t.account_before_rub)
    : (t.accountBefore != null && Number.isFinite(Number(t.accountBefore))
      ? Number(t.accountBefore)
      : null);
  const accountAfter = t.account_after_rub != null && Number.isFinite(Number(t.account_after_rub))
    ? Number(t.account_after_rub)
    : (t.accountAfter != null && Number.isFinite(Number(t.accountAfter))
      ? Number(t.accountAfter)
      : null);
  // Исторические Prod-строки: «До» = После − Δ, если before не записали на выходе
  if (accountBefore == null && accountAfter != null && accountDelta != null) {
    accountBefore = accountAfter - accountDelta;
  }
  const execNom = t.execution_notional_rub != null && Number.isFinite(Number(t.execution_notional_rub))
    ? Number(t.execution_notional_rub)
    : (t.notional_rub != null && Number.isFinite(Number(t.notional_rub))
      ? Number(t.notional_rub)
      : null);
  return makeTradeRow({
    index,
    direction,
    lots: t.quantity_lots != null ? Number(t.quantity_lots) : null,
    source: t.source || null,
    entryComment,
    closeComment,
    entryDate: t.entry_time,
    exitDate: exitDisplay || t.exit_time || '—',
    exitFillDate: exitFill,
    exitTitle,
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
    modelNet,
    netFromAccount: accountDelta != null && !chistFromModel,
    chistFromModel,
    accountBefore,
    accountAfter,
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
    execution_notional_rub: execNom,
    entry_deposit_rub: t.entry_deposit_rub,
    entry_tatn: t.entry_tatn,
    entry_tatnp: t.entry_tatnp,
    quantity_lots: t.quantity_lots,
    legs: t.legs || t.legs_json,
    leverage: settings.leverage,
    _settings: settings,
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
    case 'Comment': return row.commentTitle || null;
    case 'Entry': return parseTradeMs(row.entryDate);
    case 'Exit':
      if (row.exitDate === '—') return null;
      return parseTradeMs(row.exitDate);
    case 'EntryZ': return row.entryZ;
    case 'ExitZ': return row.exitZ;
    case 'Duration': return row.durationMs;
    case 'Invest': return row.investValue;
    case 'Net': return row.netValue;
    case 'AccountBefore': return row.accountBeforeValue;
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

/** Чист. / Вложения — сумма; Hit1/2/3 — кол-во; Сумма после — последнее; остальные числовые — среднее. */
const TRADE_AGG_SUM_KEYS = new Set(['Net', 'Invest']);
const TRADE_AGG_COUNT_KEYS = new Set(['Hit1', 'Hit2', 'Hit3']);
const TRADE_AGG_LAST_KEYS = new Set(['AccountBefore', 'AccountAfter']);
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
    case 'Invest':
    case 'AccountBefore':
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
    let text = formatTradeAggValue(key, value);
    let cls = '';
    if (key === 'Net' || key === 'Gross' || key === 'SpreadDelta' || key === 'PnlMin' || key === 'PnlMax') {
      cls = value > 0 ? 'pnl-pos' : value < 0 ? 'pnl-neg' : '';
    }
    let title = mode === 'sum'
      ? `Сумма · ${count} зн.`
      : `Среднее · ${count} зн.`;
    // Итог Чист.: сумма нетто и % от суммы Вложения по видимым строкам
    if (key === 'Net' && mode === 'sum') {
      let invSum = 0;
      let invN = 0;
      for (const row of list) {
        const inv = row.investValue;
        if (inv == null || !Number.isFinite(Number(inv)) || !(Number(inv) > 0)) continue;
        invSum += Number(inv);
        invN += 1;
      }
      if (invSum > 0) {
        text = formatNetWithInvestPct(value, invSum);
        title = `Сумма · ${count} зн. · ${formatInvestPct((value / invSum) * 100)} от Σ Вложения (${formatAccountRub(invSum)}, ${invN} сд.)`;
      }
    }
    return {
      key,
      mode,
      value,
      text,
      cls,
      title,
    };
  });
}

function tradeCellTitle(row, colKey) {
  if (colKey === 'Net' && row.netTitle) return row.netTitle;
  if (colKey === 'Invest' && row.investValue != null) {
    return `Вложения ${formatAccountRub(row.investValue)} · депозит на пару (база %)`;
  }
  if (colKey === 'Direction' && row.source === 'добор') {
    return row.direction === 'Long'
      ? 'добор Long · вход 2% · выход 3.2'
      : 'добор Short · вход 7% · выход 6.1';
  }
  if (colKey === 'Source' && row.source === 'добор') {
    return 'вторая нога варианта 2 (пока база открыта)';
  }
  if (colKey === 'Source' && row.source === 'база') {
    return 'базовая нога (Short 6.1/5.8 · Long 3.2/4.0)';
  }
  if (colKey === 'Exit' && row.exitTitle) return row.exitTitle;
  if (colKey === 'Comment' && row.commentTitle) return row.commentTitle;
  return '';
}

function tradeCellValue(row, colKey) {
  switch (colKey) {
    case 'Index': return String(row.index);
    case 'Direction': {
      const addon = row.source === 'добор';
      if (row.direction === 'Long') return addon ? 'L+' : 'L';
      return addon ? 'S+' : 'S';
    }
    case 'Lots': return row.lots != null ? String(row.lots) : '—';
    case 'Source': return row.source || '—';
    case 'Comment': return row.comment || '—';
    case 'Entry': return compactTradeDateTime(row.entryDate);
    case 'Exit': return row.exitDate === '—' ? '—' : compactTradeDateTime(row.exitDate);
    case 'EntryZ': return row.entryZText;
    case 'ExitZ': return row.exitZText;
    case 'Duration': return row.duration;
    case 'Invest': return row.invest;
    case 'Net': return row.net;
    case 'AccountBefore': return row.accountBefore;
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
  if (colKey === 'Source') {
    const s = String(row.source || '').toUpperCase();
    if (s === 'MANUAL' || s === 'BROKER') return 'src-manual';
    if (s === 'AUTO' || s === 'AUTO_TP' || s === 'AUTO_MTLR' || s === 'AUTO_MTLR_TP') return 'src-auto';
  }
  if (colKey === 'Comment' && row.comment && row.comment !== '—') return 'comment-cell';
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

/** Linear interpolation quantile on a sorted ascending array. */
function quantileSorted(sorted, q) {
  const n = sorted?.length || 0;
  if (!n) return null;
  const qq = Math.max(0, Math.min(1, Number(q)));
  if (!Number.isFinite(qq)) return null;
  if (n === 1) return sorted[0];
  const pos = (n - 1) * qq;
  const lo = Math.floor(pos);
  const hi = Math.ceil(pos);
  if (lo === hi) return sorted[lo];
  const w = pos - lo;
  return sorted[lo] * (1 - w) + sorted[hi] * w;
}

/**
 * Histogram window that drops extreme tails so the shape is readable.
 * Summary stats stay on the full sample; only bins/lo/hi use the window.
 * Prefer Tukey fences (Q1−1.5·IQR … Q3+1.5·IQR); if too few points remain,
 * fall back to [p5, p95], then the full range.
 */
function numericDistHistWindow(sorted, median, mad, p5, p25, p75, p95) {
  const n = sorted.length;
  const min = sorted[0];
  const max = sorted[n - 1];
  const pickInRange = (lo, hi) => {
    const out = [];
    for (const v of sorted) {
      if (v >= lo && v <= hi) out.push(v);
    }
    return out;
  };
  const iqr = (p25 != null && p75 != null) ? (p75 - p25) : 0;
  let fenceLo = min;
  let fenceHi = max;
  if (iqr > 1e-12) {
    fenceLo = p25 - 1.5 * iqr;
    fenceHi = p75 + 1.5 * iqr;
  } else if (mad > 1e-12 && median != null && Number.isFinite(median)) {
    fenceLo = median - 3 * mad;
    fenceHi = median + 3 * mad;
  } else if (p5 != null && p95 != null && p95 > p5) {
    fenceLo = p5;
    fenceHi = p95;
  }
  fenceLo = Math.max(min, fenceLo);
  fenceHi = Math.min(max, fenceHi);
  let histSorted = pickInRange(fenceLo, fenceHi);
  // Tiny n: fences are noisy — keep full range.
  if (n < 8) {
    return { histSorted: sorted, histClipped: false };
  }
  const minKeep = Math.max(8, Math.floor(n * 0.5));
  // Only widen when fences actually dropped too many points.
  if (histSorted.length < n && histSorted.length < minKeep
    && p5 != null && p95 != null && p95 > p5) {
    fenceLo = Math.max(min, p5);
    fenceHi = Math.min(max, p95);
    histSorted = pickInRange(fenceLo, fenceHi);
  }
  if (histSorted.length < 2) {
    return { histSorted: sorted, histClipped: false };
  }
  return { histSorted, histClipped: histSorted.length < n };
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
  const median = quantileSorted(sorted, 0.5);
  const p5 = quantileSorted(sorted, 0.05);
  const p25 = quantileSorted(sorted, 0.25);
  const p75 = quantileSorted(sorted, 0.75);
  const p95 = quantileSorted(sorted, 0.95);
  let mad = 0;
  if (n > 0 && median != null && Number.isFinite(median)) {
    const absDev = sorted.map((v) => Math.abs(v - median)).sort((a, b) => a - b);
    mad = quantileSorted(absDev, 0.5) || 0;
  }
  const { histSorted, histClipped } = numericDistHistWindow(
    sorted, median, mad, p5, p25, p75, p95,
  );
  const nHist = histSorted.length;
  const histMin = histSorted[0];
  const histMax = histSorted[nHist - 1];
  const binCount = Math.min(28, Math.max(10, Math.round(Math.sqrt(nHist) * 2.2) || binTarget));
  let lo = histMin;
  let hi = histMax;
  if (hi <= lo) {
    const pad = Math.max(Math.abs(lo) * 0.05, 1e-6);
    lo -= pad;
    hi += pad;
  }
  const width = (hi - lo) / binCount;
  const bins = new Array(binCount).fill(0);
  for (const v of histSorted) {
    let i = Math.floor((v - lo) / width);
    if (i < 0) i = 0;
    if (i >= binCount) i = binCount - 1;
    bins[i] += 1;
  }
  return {
    n, nHist, min, max, histMin, histMax, mean, stdev, median, mad, p5, p25, p75, p95,
    bins, lo, hi, width, binCount, sorted, histClipped,
  };
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
  // Outside the clipped hist window → no active bar (tail filtered out).
  if (Number.isFinite(dist.lo) && Number.isFinite(dist.hi)
    && (value < dist.lo || value > dist.hi)) {
    return -1;
  }
  let i = Math.floor((value - dist.lo) / dist.width);
  if (i < 0) i = 0;
  if (i >= dist.binCount) i = dist.binCount - 1;
  return i;
}

function tradeMetricPercentile(dist, value) {
  if (!dist || value == null || !Number.isFinite(value)) return null;
  // Exact path when full sample is present (trades table).
  if (Array.isArray(dist.sorted) && dist.sorted.length) {
    let count = 0;
    for (const v of dist.sorted) {
      if (v <= value) count += 1;
    }
    return (count / dist.sorted.length) * 100;
  }
  // Compact server dist (desk 3y): approximate via cumulative bins.
  if (!Array.isArray(dist.bins) || !(dist.n > 0)) return null;
  const idx = tradeMetricBinIndex(dist, value);
  if (idx < 0) return null;
  let count = 0;
  for (let i = 0; i < idx; i += 1) count += dist.bins[i] || 0;
  count += (dist.bins[idx] || 0) * 0.5;
  return (count / dist.n) * 100;
}

/** Directional RU labels for values outside the typical band. */
function tradeMetricDirectionLabels(colKey) {
  if (colKey === 'Duration') {
    return { low: 'короче обычного', high: 'длиннее обычного' };
  }
  return { low: 'ниже обычного', high: 'выше обычного' };
}

/**
 * Placement vs robust center (median + IQR / MAD).
 * Mean±σ is misleading on skewed metrics (esp. Duration).
 * p25–p75 (or |x−med|≤1.5·MAD) → типично;
 * outside but within p5–p95 (or ≤3·MAD) → короче/длиннее / ниже/выше;
 * beyond → выброс.
 *
 * @param {number} value
 * @param {object} dist
 * @param {{ colKey?: string }} [opts]
 */
function classifyTradeMetricPlacement(value, dist, opts) {
  if (!dist || value == null || !Number.isFinite(value)) {
    return { key: 'none', label: 'нет данных', z: null };
  }
  if (dist.n < 2) {
    return { key: 'sparse', label: 'мало данных', z: null };
  }
  const colKey = opts && opts.colKey;
  const dirs = tradeMetricDirectionLabels(colKey);
  const med = Number.isFinite(dist.median) ? dist.median : null;
  const mad = Number.isFinite(dist.mad) ? dist.mad : null;
  const p5 = Number.isFinite(dist.p5) ? dist.p5 : null;
  const p25 = Number.isFinite(dist.p25) ? dist.p25 : null;
  const p75 = Number.isFinite(dist.p75) ? dist.p75 : null;
  const p95 = Number.isFinite(dist.p95) ? dist.p95 : null;
  const scale = (mad != null && mad > 1e-12)
    ? mad
    : (p25 != null && p75 != null && (p75 - p25) > 1e-12 ? (p75 - p25) / 1.349 : 0);
  const robustZ = (med != null && scale > 1e-12) ? (value - med) / scale : null;

  const flatScale = !(scale > 1e-12)
    && !(p25 != null && p75 != null && (p75 - p25) > 1e-12)
    && !(dist.stdev > 1e-12);
  if (flatScale) {
    return { key: 'flat', label: 'как у всех', z: 0 };
  }

  const lowLabel = dirs.low;
  const highLabel = dirs.high;
  const sideLabel = (v) => (med != null && v < med ? lowLabel : highLabel);

  // Prefer percentile bands when available.
  if (p25 != null && p75 != null && (p75 - p25) > 1e-12) {
    if (value >= p25 && value <= p75) {
      return { key: 'typical', label: 'типично', z: robustZ };
    }
    const extremeLow = p5 != null && value < p5;
    const extremeHigh = p95 != null && value > p95;
    if (extremeLow || extremeHigh) {
      return { key: 'outlier', label: 'выброс', z: robustZ };
    }
    // Between IQR edge and p5/p95 (or missing tails): mildly unusual.
    return { key: 'tail', label: sideLabel(value), z: robustZ };
  }

  // MAD fallback (or when percentiles collapsed).
  if (med != null && mad != null && mad > 1e-12) {
    const ad = Math.abs(value - med);
    if (ad <= 1.5 * mad) {
      return { key: 'typical', label: 'ближе к медиане', z: robustZ };
    }
    if (ad <= 3 * mad) {
      return { key: 'tail', label: sideLabel(value), z: robustZ };
    }
    return { key: 'outlier', label: 'выброс', z: robustZ };
  }

  // Last resort: classical mean/σ (near-symmetric series without robust stats).
  if (!(dist.stdev > 1e-12) || !Number.isFinite(dist.mean)) {
    return { key: 'flat', label: 'как у всех', z: 0 };
  }
  const z = (value - dist.mean) / dist.stdev;
  const az = Math.abs(z);
  if (az < 0.5) return { key: 'typical', label: 'типично', z };
  if (az < 2) return { key: 'tail', label: sideLabel(value), z };
  return { key: 'outlier', label: 'выброс', z };
}

/** Absolute spread % cuts: узкий / переход (valley) / широкий. */
const SPREAD_WIDTH_NARROW_MAX = 3.5;
const SPREAD_WIDTH_WIDE_MIN = 5.5;

/**
 * Режим ширины спреда по абсолютному %.
 * A узкий: &lt;3.5 · T переход: 3.5–5.5 · B широкий: &gt;5.5
 */
function classifySpreadWidthRegime(spreadPct) {
  const sp = Number(spreadPct);
  if (!Number.isFinite(sp)) {
    return { key: 'na', label: '—', shortLabel: '—', title: '' };
  }
  if (sp < SPREAD_WIDTH_NARROW_MAX) {
    return {
      key: 'narrow',
      label: 'узкий режим',
      shortLabel: 'узкий',
      title: `спред < ${SPREAD_WIDTH_NARROW_MAX}%`,
    };
  }
  if (sp > SPREAD_WIDTH_WIDE_MIN) {
    return {
      key: 'wide',
      label: 'широкий режим',
      shortLabel: 'широкий',
      title: `спред > ${SPREAD_WIDTH_WIDE_MIN}%`,
    };
  }
  return {
    key: 'transition',
    label: 'переход',
    shortLabel: 'переход',
    title: `${SPREAD_WIDTH_NARROW_MAX}% ≤ спред ≤ ${SPREAD_WIDTH_WIDE_MIN}%`,
  };
}

/** Карта зон Prod: 3.2 / 4.0 / 5.8 / 6.1 (полосы владеют своими уровнями). */
const SPREAD_ZONE_LO = 3.2;
const SPREAD_ZONE_MID_LO = 4.0;
const SPREAD_ZONE_MID_HI = 5.8;
const SPREAD_ZONE_HI = 6.1;

const SPREAD_ZONE_RU = {
  below: 'ниже низа',
  lower: 'нижняя полоса',
  middle: 'середина',
  upper: 'верхняя полоса',
  above: 'выше верха',
  na: '—',
};

/** Типичный выход из зоны (sticky≥15, 3г TATN) — подсказка на бейдже. */
const SPREAD_ZONE_TYPICAL_EXIT = {
  below: 'обычно → нижняя полоса',
  lower: 'обычно → ниже (55%) / середина (45%)',
  middle: 'обычно → верхняя (78%) / нижняя (19%)',
  upper: 'обычно → середина (59%) / выше (41%)',
  above: 'обычно → верхняя полоса',
};

function barSpreadPct(b) {
  if (!b) return NaN;
  const sp = b.spreadPercent != null
    ? Number(b.spreadPercent)
    : (b.spread != null ? Number(b.spread) : NaN);
  return Number.isFinite(sp) ? sp : NaN;
}

function classifySpreadMapZone(spreadPct) {
  const sp = Number(spreadPct);
  if (!Number.isFinite(sp)) {
    return { key: 'na', label: '—', shortLabel: '—', title: '' };
  }
  if (sp < SPREAD_ZONE_LO) {
    return {
      key: 'below',
      label: SPREAD_ZONE_RU.below,
      shortLabel: 'ниже',
      title: `S < ${SPREAD_ZONE_LO}`,
    };
  }
  if (sp <= SPREAD_ZONE_MID_LO) {
    return {
      key: 'lower',
      label: SPREAD_ZONE_RU.lower,
      shortLabel: 'нижн.',
      title: `${SPREAD_ZONE_LO} ≤ S ≤ ${SPREAD_ZONE_MID_LO}`,
    };
  }
  if (sp < SPREAD_ZONE_MID_HI) {
    return {
      key: 'middle',
      label: SPREAD_ZONE_RU.middle,
      shortLabel: 'серед.',
      title: `${SPREAD_ZONE_MID_LO} < S < ${SPREAD_ZONE_MID_HI}`,
    };
  }
  if (sp <= SPREAD_ZONE_HI) {
    return {
      key: 'upper',
      label: SPREAD_ZONE_RU.upper,
      shortLabel: 'верхн.',
      title: `${SPREAD_ZONE_MID_HI} ≤ S ≤ ${SPREAD_ZONE_HI}`,
    };
  }
  return {
    key: 'above',
    label: SPREAD_ZONE_RU.above,
    shortLabel: 'выше',
    title: `S > ${SPREAD_ZONE_HI}`,
  };
}

function spreadMapZoneBounds(key) {
  switch (key) {
    case 'below':
      return { lo: -Infinity, hi: SPREAD_ZONE_LO };
    case 'lower':
      return { lo: SPREAD_ZONE_LO, hi: SPREAD_ZONE_MID_LO };
    case 'middle':
      return { lo: SPREAD_ZONE_MID_LO, hi: SPREAD_ZONE_MID_HI };
    case 'upper':
      return { lo: SPREAD_ZONE_MID_HI, hi: SPREAD_ZONE_HI };
    case 'above':
      return { lo: SPREAD_ZONE_HI, hi: Infinity };
    default:
      return { lo: NaN, hi: NaN };
  }
}

function formatZoneEpisodeDuration(bars) {
  const n = Math.max(0, Math.round(Number(bars) || 0));
  if (n < 60) return `${n} мин`;
  const h = Math.floor(n / 60);
  const m = n % 60;
  if (h < 24) return m > 0 ? `${h} ч ${m} мин` : `${h} ч`;
  const d = Math.floor(h / 24);
  const rh = h % 24;
  return rh > 0 ? `${d} д ${rh} ч` : `${d} д`;
}

/**
 * Текущая зона карты Prod на последнем баре + длина эпизода (минутные бары подряд).
 * @param {Array} bars bars up to cursor
 */
function detectSpreadMapZone(bars, opts = {}) {
  const empty = {
    on: false,
    key: 'na',
    label: '',
    shortLabel: '',
    title: '',
    spread: null,
    barsInZone: 0,
    distToEdge: null,
    nearWall: false,
    typicalExit: '',
  };
  if (!Array.isArray(bars) || !bars.length) return empty;
  const last = bars[bars.length - 1];
  const sp = barSpreadPct(last);
  const z = classifySpreadMapZone(sp);
  if (z.key === 'na') return empty;

  let barsInZone = 1;
  const maxScan = Math.max(60, Number(opts.maxDwellScan) || 20000);
  for (let i = bars.length - 2; i >= 0 && barsInZone < maxScan; i -= 1) {
    const spi = barSpreadPct(bars[i]);
    if (!Number.isFinite(spi)) break;
    if (classifySpreadMapZone(spi).key !== z.key) break;
    barsInZone += 1;
  }
  const dwellCapped = barsInZone >= maxScan;

  const bounds = spreadMapZoneBounds(z.key);
  let distToEdge = null;
  let nearWall = false;
  const wallEps = opts.wallEps != null ? Number(opts.wallEps) : 0.15;
  if (Number.isFinite(bounds.lo) && Number.isFinite(bounds.hi)) {
    if (!Number.isFinite(bounds.lo) || bounds.lo === -Infinity) {
      distToEdge = bounds.hi - sp;
      nearWall = distToEdge <= wallEps;
    } else if (!Number.isFinite(bounds.hi) || bounds.hi === Infinity) {
      distToEdge = sp - bounds.lo;
      nearWall = distToEdge <= wallEps;
    } else {
      const dLo = sp - bounds.lo;
      const dHi = bounds.hi - sp;
      distToEdge = Math.min(dLo, dHi);
      nearWall = distToEdge <= wallEps;
    }
  } else if (bounds.hi === Infinity) {
    distToEdge = sp - bounds.lo;
    nearWall = distToEdge <= wallEps;
  } else if (bounds.lo === -Infinity) {
    distToEdge = bounds.hi - sp;
    nearWall = distToEdge <= wallEps;
  }

  const dur = formatZoneEpisodeDuration(barsInZone);
  const durShow = dwellCapped ? `>${dur}` : dur;
  const distTxt = distToEdge != null && Number.isFinite(distToEdge)
    ? ` · до края ${distToEdge.toFixed(2)} п.п.`
    : '';
  const typical = SPREAD_ZONE_TYPICAL_EXIT[z.key] || '';
  const title = [
    `${z.label} · S=${sp.toFixed(2)}`,
    `эпизод ${durShow}${dwellCapped ? '+' : ''} (${barsInZone}${dwellCapped ? '+' : ''} бар)`,
    distTxt.replace(/^ · /, ''),
    nearWall ? 'у стенки зоны' : '',
    typical,
  ].filter(Boolean).join(' · ');

  const badgeCore = nearWall
    ? `${z.shortLabel} · у стенки · ${durShow}`
    : `${z.shortLabel} · ${durShow}`;

  return {
    on: true,
    key: z.key,
    label: z.label,
    shortLabel: z.shortLabel,
    badgeText: badgeCore,
    title,
    spread: sp,
    barsInZone,
    durationLabel: durShow,
    distToEdge: distToEdge != null && Number.isFinite(distToEdge)
      ? Math.round(distToEdge * 1000) / 1000
      : null,
    nearWall,
    typicalExit: typical,
  };
}

/**
 * Устойчивые эпизоды зоны (подряд ≥ minBars минутных баров).
 * @returns {Array<{ key: string, startIdx: number, endIdx: number, bars: number, startTime: number, endTime: number, startDate: string, endDate: string }>}
 */
function listSpreadMapZoneEpisodes(bars, opts = {}) {
  const minBars = Math.max(1, Number(opts.minBars) || 60);
  if (!Array.isArray(bars) || bars.length < minBars) return [];

  const barUnix = (b) => {
    if (!b) return null;
    const ms = Number(b.timestampMs);
    if (Number.isFinite(ms) && ms > 0) return Math.floor(ms / 1000);
    const lab = String(b.tradeDate || b.trade_date || '').trim();
    if (!lab) return null;
    if (typeof labelToUnixSec === 'function') return labelToUnixSec(lab);
    return null;
  };

  const out = [];
  let i = 0;
  while (i < bars.length) {
    const sp0 = barSpreadPct(bars[i]);
    const key0 = classifySpreadMapZone(sp0).key;
    if (key0 === 'na') {
      i += 1;
      continue;
    }
    let j = i + 1;
    while (j < bars.length) {
      const spj = barSpreadPct(bars[j]);
      if (classifySpreadMapZone(spj).key !== key0) break;
      j += 1;
    }
    const barsN = j - i;
    if (barsN >= minBars) {
      const startT = barUnix(bars[i]);
      const endT = barUnix(bars[j - 1]);
      if (startT != null && endT != null) {
        out.push({
          key: key0,
          startIdx: i,
          endIdx: j - 1,
          bars: barsN,
          startTime: startT,
          endTime: endT,
          startDate: String(bars[i].tradeDate || bars[i].trade_date || '').slice(0, 16),
          endDate: String(bars[j - 1].tradeDate || bars[j - 1].trade_date || '').slice(0, 16),
        });
      }
    }
    i = j;
  }
  return out;
}

/**
 * Вертикали: вход в устойчивую зону (≥ minBars). Только kind=start, чтобы не дублировать.
 * @returns {Array<{ time: number, kind: string, direction: string, family: string, zone: string, label: string, title: string }>}
 */
function buildSpreadZoneChartVlines(bars, opts = {}) {
  const minBars = Math.max(1, Number(opts.minBars) || 60);
  const maxLines = Math.max(10, Number(opts.maxLines) || 40);
  const tMin = opts.tMin != null ? Number(opts.tMin) : -Infinity;
  const tMax = opts.tMax != null ? Number(opts.tMax) : Infinity;
  const episodes = listSpreadMapZoneEpisodes(bars, { minBars });
  const short = {
    below: 'ниже',
    lower: 'нижн.',
    middle: 'серед.',
    upper: 'верхн.',
    above: 'выше',
  };
  const lines = [];
  for (const ep of episodes) {
    if (ep.startTime < tMin || ep.startTime > tMax) continue;
    const name = SPREAD_ZONE_RU[ep.key] || ep.key;
    lines.push({
      time: ep.startTime,
      kind: 'start',
      direction: ep.key,
      family: 'zone',
      zone: ep.key,
      label: `зона · ${short[ep.key] || ep.key}`,
      title: `${name}: вход ${ep.startDate} · ${ep.bars} бар · ${SPREAD_ZONE_TYPICAL_EXIT[ep.key] || ''}`,
    });
  }
  if (lines.length > maxLines) {
    return lines.slice(lines.length - maxLines);
  }
  return lines;
}

/**
 * Каскад смены режима: за ~10 торговых дней спред прошёл обе границы (3.5 и 5.5)
 * в одну сторону. По истории TATN — лучший precision (~75%) среди опережающих флагов.
 * Это фильтр внимания / подтверждение, не автовход Prod.
 *
 * @param {Array<{tradeDate?: string, trade_date?: string, spread?: number, spreadPercent?: number}>} bars
 * @param {{ lookbackDays?: number, narrowMax?: number, wideMin?: number }} [opts]
 */
function detectSpreadRegimeCascade(bars, opts = {}) {
  const lookback = Math.max(5, Number(opts.lookbackDays) || 10);
  const narrowMax = opts.narrowMax != null ? Number(opts.narrowMax) : SPREAD_WIDTH_NARROW_MAX;
  const wideMin = opts.wideMin != null ? Number(opts.wideMin) : SPREAD_WIDTH_WIDE_MIN;
  const empty = {
    on: false,
    key: 'off',
    label: '',
    title: '',
    direction: null,
    hi: null,
    lo: null,
    days: 0,
  };
  if (!Array.isArray(bars) || bars.length < 3) return empty;

  const byDay = new Map();
  for (const b of bars) {
    const td = String(b.tradeDate || b.trade_date || '').trim().slice(0, 10);
    if (td.length < 10) continue;
    const sp = b.spreadPercent != null
      ? Number(b.spreadPercent)
      : (b.spread != null ? Number(b.spread) : NaN);
    if (!Number.isFinite(sp)) continue;
    byDay.set(td, sp);
  }
  const days = Array.from(byDay.keys()).sort();
  if (days.length < 6) return empty;

  const useN = Math.min(lookback, days.length - 1);
  const sliceDays = days.slice(-(useN + 1));
  const vals = sliceDays.map((d) => byDay.get(d));
  const s0 = vals[0];
  const s1 = vals[vals.length - 1];
  let hi = -Infinity;
  let lo = Infinity;
  for (const v of vals) {
    if (v > hi) hi = v;
    if (v < lo) lo = v;
  }
  if (!(hi >= wideMin && lo <= narrowMax)) return empty;

  const n = sliceDays.length;
  if (s1 < s0 - 0.05) {
    return {
      on: true,
      key: 'down',
      label: 'каскад ↓',
      direction: 'down',
      hi,
      lo,
      days: n,
      title: `За ${n}д спред ${hi.toFixed(2)}→${lo.toFixed(2)} п.п.: прошёл ${wideMin} и ${narrowMax} вниз (к узкому). Фильтр внимания, не автовход.`,
    };
  }
  if (s1 > s0 + 0.05) {
    return {
      on: true,
      key: 'up',
      label: 'каскад ↑',
      direction: 'up',
      hi,
      lo,
      days: n,
      title: `За ${n}д спред ${lo.toFixed(2)}→${hi.toFixed(2)} п.п.: прошёл ${narrowMax} и ${wideMin} вверх (к широкому). Фильтр внимания, не автовход.`,
    };
  }
  return empty;
}

/**
 * Эпизоды каскада по дневным закрытиям: непрерывные отрезки, где флаг «on».
 * Для графика Теста: вертикали «начало» / «конец» (unix sec, как у свечей).
 *
 * @param {Array<{tradeDate?: string, trade_date?: string, timestampMs?: number, spread?: number, spreadPercent?: number}>} bars
 * @param {{ lookbackDays?: number, narrowMax?: number, wideMin?: number }} [opts]
 * @returns {Array<{ startDate: string, endDate: string, key: string, direction: string, startTime: number, endTime: number, labelStart: string, labelEnd: string }>}
 */
function listSpreadRegimeCascadeEpisodes(bars, opts = {}) {
  const lookback = Math.max(5, Number(opts.lookbackDays) || 10);
  const narrowMax = opts.narrowMax != null ? Number(opts.narrowMax) : SPREAD_WIDTH_NARROW_MAX;
  const wideMin = opts.wideMin != null ? Number(opts.wideMin) : SPREAD_WIDTH_WIDE_MIN;
  if (!Array.isArray(bars) || bars.length < 3) return [];

  const byDay = new Map();
  const firstBarByDay = new Map();
  const lastBarByDay = new Map();
  for (const b of bars) {
    const td = String(b.tradeDate || b.trade_date || '').trim().slice(0, 10);
    if (td.length < 10) continue;
    const sp = b.spreadPercent != null
      ? Number(b.spreadPercent)
      : (b.spread != null ? Number(b.spread) : NaN);
    if (!Number.isFinite(sp)) continue;
    byDay.set(td, sp);
    if (!firstBarByDay.has(td)) firstBarByDay.set(td, b);
    lastBarByDay.set(td, b);
  }
  const days = Array.from(byDay.keys()).sort();
  if (days.length < 6) return [];

  const flagAt = (dayIdx) => {
    const useN = Math.min(lookback, dayIdx);
    if (useN < 5) return { on: false, key: 'off', direction: null };
    const sliceDays = days.slice(dayIdx - useN, dayIdx + 1);
    const vals = sliceDays.map((d) => byDay.get(d));
    const s0 = vals[0];
    const s1 = vals[vals.length - 1];
    let hi = -Infinity;
    let lo = Infinity;
    for (const v of vals) {
      if (v > hi) hi = v;
      if (v < lo) lo = v;
    }
    if (!(hi >= wideMin && lo <= narrowMax)) return { on: false, key: 'off', direction: null };
    if (s1 < s0 - 0.05) return { on: true, key: 'down', direction: 'down' };
    if (s1 > s0 + 0.05) return { on: true, key: 'up', direction: 'up' };
    return { on: false, key: 'off', direction: null };
  };

  const barUnix = (b) => {
    if (!b) return null;
    const ms = Number(b.timestampMs);
    if (Number.isFinite(ms) && ms > 0) return Math.floor(ms / 1000);
    const lab = String(b.tradeDate || b.trade_date || '').trim();
    if (!lab) return null;
    if (typeof labelToUnixSec === 'function') return labelToUnixSec(lab);
    return null;
  };

  const episodes = [];
  let cur = null;
  for (let i = 0; i < days.length; i++) {
    const flag = flagAt(i);
    if (flag.on) {
      if (!cur || cur.key !== flag.key) {
        if (cur) episodes.push(cur);
        cur = {
          startDate: days[i],
          endDate: days[i],
          key: flag.key,
          direction: flag.direction,
        };
      } else {
        cur.endDate = days[i];
      }
    } else if (cur) {
      episodes.push(cur);
      cur = null;
    }
  }
  if (cur) episodes.push(cur);

  const out = [];
  for (const ep of episodes) {
    const startT = barUnix(firstBarByDay.get(ep.startDate));
    const endT = barUnix(lastBarByDay.get(ep.endDate));
    if (startT == null || endT == null) continue;
    const arrow = ep.direction === 'up' ? '↑' : '↓';
    out.push({
      startDate: ep.startDate,
      endDate: ep.endDate,
      key: ep.key,
      direction: ep.direction,
      startTime: startT,
      endTime: endT,
      labelStart: `каскад ${arrow} · начало`,
      labelEnd: `каскад ${arrow} · конец`,
    });
  }
  return out;
}

/**
 * Вертикали графика: начало и конец каждого эпизода каскада (до курсора).
 * @param {Array} bars bars up to cursor
 * @returns {Array<{ time: number, kind: string, direction: string, label: string, title: string }>}
 */
function buildCascadeChartVlines(bars, opts = {}) {
  const episodes = listSpreadRegimeCascadeEpisodes(bars, opts);
  const lines = [];
  for (const ep of episodes) {
    const title =
      `${ep.startDate} → ${ep.endDate}: ${ep.direction === 'up' ? 'к широкому' : 'к узкому'}`;
    lines.push({
      time: ep.startTime,
      kind: 'start',
      direction: ep.direction,
      label: ep.labelStart,
      title,
    });
    if (ep.endTime !== ep.startTime) {
      lines.push({
        time: ep.endTime,
        kind: 'end',
        direction: ep.direction,
        label: ep.labelEnd,
        title,
      });
    }
  }
  return lines;
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
 * @param {{ title: string, display: string, value: number, dist: object, formatStat?: (v:number)=>string, spreadWidthRegime?: boolean, colKey?: string }} opts
 */
function buildMetricDistTipHtml(opts) {
  const { title, display, value, dist } = opts;
  const formatStat = opts.formatStat || ((v) => (Number.isFinite(v) ? String(Math.round(v * 100) / 100) : '—'));
  const sampleLabel = opts.sampleLabel ? String(opts.sampleLabel) : '';
  if (!dist || value == null || !Number.isFinite(value)) return '';
  const place = classifyTradeMetricPlacement(value, dist, { colKey: opts.colKey });
  const widthReg = opts.spreadWidthRegime ? classifySpreadWidthRegime(value) : null;
  const pct = tradeMetricPercentile(dist, value);
  const activeBin = tradeMetricBinIndex(dist, value);
  const maxBin = Math.max(1, ...dist.bins);
  const medLabel = Number.isFinite(dist.median) ? formatStat(dist.median) : '—';
  const madLabel = Number.isFinite(dist.mad) ? formatStat(dist.mad) : '—';
  const meanLabel = formatStat(dist.mean);
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
  const sampleText = sampleLabel ? ` · ${sampleLabel}` : '';
  const nHist = Number.isFinite(dist.nHist) ? dist.nHist : dist.n;
  const histClipped = !!(dist.histClipped && nHist < dist.n);
  const nText = histClipped ? `n ${nHist}/${dist.n}` : `n=${dist.n}`;
  const clipText = histClipped ? ' · без хвоста' : '';
  const axisLo = Number.isFinite(dist.histMin) ? dist.histMin : dist.lo;
  const axisHi = Number.isFinite(dist.histMax) ? dist.histMax : dist.hi;
  let footerHtml = `<div class="tm-place tm-place-${place.key}">${place.label}</div>`;
  if (widthReg && widthReg.key !== 'na') {
    footerHtml = `<div class="tm-regime tm-regime-${widthReg.key}" title="${widthReg.title}">режим: ${widthReg.shortLabel}</div>`;
  }
  return (
    `<div class="tm-title">${title} · <strong>${display}</strong></div>`
    + `<div class="tm-hist-wrap" aria-hidden="true">`
    + `<div class="tm-hist-scale">`
    + `<div class="tm-hist-y">${yLabelsHtml}</div>`
    + `<div class="tm-hist-plot">`
    + `<div class="tm-hist-grid">${gridHtml}</div>`
    + `<div class="tm-hist">${barsHtml}</div>`
    + `</div></div>`
    + `<div class="tm-hist-x"><span>${formatStat(axisLo)}</span><span>${medLabel}</span><span>${formatStat(axisHi)}</span></div>`
    + `</div>`
    + `<div class="tm-meta">мед. ${medLabel} · MAD ${madLabel} · ср. ${meanLabel} · ${nText}${clipText}${pctText}${sampleText}</div>`
    + footerHtml
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
