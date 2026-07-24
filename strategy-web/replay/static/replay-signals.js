/** Z signals, session, risk-exit opts — split from replay-engine.js */
/**
 * Bar Replay engine — parity с Android BarReplayEngine (Kotlin).
 */
const Z_SCORE_ROLLING_MIN_BARS = 48;
const BAR_REPLAY_BASE_DELAY_MS = 900;
const BAR_REPLAY_SPEEDS = [0.5, 1, 2, 5, 10];

const Z_THRESHOLD_MAX = 3.1;
const Z_THRESHOLD_STEP = 0.1;

function buildZThresholdValues(minVal, maxVal = Z_THRESHOLD_MAX, step = Z_THRESHOLD_STEP) {
  const vals = [];
  for (let v = minVal; v <= maxVal + 1e-9; v += step) {
    vals.push(Math.round(v * 10) / 10);
  }
  return vals;
}

/**
 * Режим спреда по последним M15 барам: сжатие / разжатие / боковик + движение |Z|.
 * lookback по умолчанию ~2ч (8×15м).
 *
 * @param {Array<{spread?: number, z?: number, spreadPercent?: number, zScore?: number}>} bars
 * @param {{ lookback?: number, flatPp?: number, flatAbsZ?: number }} [opts]
 * @returns {{ key: string, label: string, zKey: string, zLabel: string, deltaSpread: number|null, deltaAbsZ: number|null, title: string }}
 */
function classifySpreadRegime(bars, opts = {}) {
  const lookback = Math.max(3, opts.lookback || 8);
  const flatPp = opts.flatPp != null ? opts.flatPp : 0.08;
  const flatAbsZ = opts.flatAbsZ != null ? opts.flatAbsZ : 0.15;
  const empty = {
    key: 'na',
    label: '—',
    zKey: 'na',
    zLabel: '',
    deltaSpread: null,
    deltaAbsZ: null,
    title: '',
  };
  if (!Array.isArray(bars) || bars.length < 3) return empty;

  const slice = bars.slice(-lookback);
  const spreads = [];
  const absZ = [];
  for (const b of slice) {
    const sp = b.spread != null ? Number(b.spread)
      : (b.spreadPercent != null ? Number(b.spreadPercent) : NaN);
    const z = b.z != null ? Number(b.z)
      : (b.zScore != null ? Number(b.zScore) : NaN);
    if (Number.isFinite(sp)) spreads.push(sp);
    if (Number.isFinite(z)) absZ.push(Math.abs(z));
  }
  if (spreads.length < 3) return empty;

  const deltaSpread = spreads[spreads.length - 1] - spreads[0];
  let key;
  let label;
  if (Math.abs(deltaSpread) < flatPp) {
    key = 'flat';
    label = 'боковик';
  } else if (deltaSpread < 0) {
    key = 'compress';
    label = 'сжатие';
  } else {
    key = 'expand';
    label = 'разжатие';
  }

  let zKey = 'na';
  let zLabel = '';
  let deltaAbsZ = null;
  if (absZ.length >= 3) {
    deltaAbsZ = absZ[absZ.length - 1] - absZ[0];
    if (Math.abs(deltaAbsZ) < flatAbsZ) {
      zKey = 'stable';
      zLabel = '|Z| стабилен';
    } else if (deltaAbsZ > 0) {
      zKey = 'away';
      zLabel = 'уход от μ';
    } else {
      zKey = 'revert';
      zLabel = 'к среднему';
    }
  }

  const dSp = deltaSpread >= 0 ? `+${deltaSpread.toFixed(2)}` : deltaSpread.toFixed(2);
  const dZ = deltaAbsZ == null ? '' : ` · Δ|Z| ${deltaAbsZ >= 0 ? '+' : ''}${deltaAbsZ.toFixed(2)}`;
  const title = `за ${spreads.length} бар: Δспред ${dSp} п.п.${dZ}`;

  return { key, label, zKey, zLabel, deltaSpread, deltaAbsZ, title };
}

function populateThresholdSelect(selectEl, minVal, selectedVal) {
  if (!selectEl) return;
  const values = buildZThresholdValues(minVal);
  const want = selectedVal != null ? parseFloat(selectedVal) : parseFloat(selectEl.value);
  selectEl.innerHTML = '';
  for (const v of values) {
    const opt = document.createElement('option');
    const label = v.toFixed(1);
    opt.value = label;
    opt.textContent = label;
    if (Number.isFinite(want) && Math.abs(v - want) < 0.001) opt.selected = true;
    selectEl.appendChild(opt);
  }
  if (!selectEl.value && values.length) {
    selectEl.value = values[0].toFixed(1);
  }
}

function barReplayDelayMs(speed) {
  return Math.max(50, Math.round(BAR_REPLAY_BASE_DELAY_MS / Math.max(0.1, speed)));
}

function isConsecutiveM15Bar(prev, cur) {
  if (!prev.timestampMs || !cur.timestampMs) return false;
  return cur.timestampMs - prev.timestampMs === 15 * 60 * 1000;
}

/**
 * TQBR (TATN/TATNP): торги пн–пт с утренней сессии 07:00 до вечерней ~23:50 МСК.
 * Бары ISS 06:30/06:45 — до открытия; сигналы на них в тесте/live запрещены.
 * Источник: расписание фондового рынка Мосбиржи (утро 07:00–09:50, основная, вечер до 23:50).
 */
function isMoexEquitySessionBar(tradeDate) {
  const s = String(tradeDate || '').replace('T', ' ').trim();
  if (s.length < 16) return false;
  const y = Number(s.slice(0, 4));
  const mo = Number(s.slice(5, 7));
  const d = Number(s.slice(8, 10));
  const hm = s.slice(11, 16);
  if (!Number.isFinite(y) || !Number.isFinite(mo) || !Number.isFinite(d)) return false;
  // UTC noon calendar day → стабильный weekday без сдвига TZ
  const dow = new Date(Date.UTC(y, mo - 1, d, 12, 0, 0)).getUTCDay();
  if (dow === 0 || dow === 6) return false;
  return hm >= '07:00' && hm < '23:50';
}

function determineZSignal(prevZ, curZ, position, entry, exit) {
  if (prevZ == null) return 'None';
  if (position === 'Flat') {
    if (prevZ > -entry && curZ <= -entry) return 'EnterLong';
    if (prevZ < entry && curZ >= entry) return 'EnterShort';
    return 'None';
  }
  if (position === 'Long') {
    return prevZ < -exit && curZ >= -exit ? 'ExitLong' : 'None';
  }
  if (position === 'Short') {
    return prevZ > exit && curZ <= exit ? 'ExitShort' : 'None';
  }
  return 'None';
}

function positionAfter(signal) {
  switch (signal) {
    case 'EnterLong': return 'Long';
    case 'EnterShort': return 'Short';
    case 'ExitLong':
    case 'ExitShort': return 'Flat';
    default: return 'Flat';
  }
}

/** Опции forced-exit — parity Android ZStrategySimOptions (+ TP %). */
function normalizeSimExitOpts(opts = {}) {
  const n = (v) => {
    const x = Number(v);
    return Number.isFinite(x) && x > 0 ? x : 0;
  };
  return {
    takeProfitPct: n(opts.takeProfitPct),
    forcedTimeStopHours: n(opts.forcedTimeStopHours),
    forcedZStopDeviation: n(opts.forcedZStopDeviation),
    /** Z-stop как % от |Z_входа|: deviation = pct/100 × |entryZ| (0 = выкл). */
    forcedZStopPctOfEntryZ: n(opts.forcedZStopPctOfEntryZ),
    maxLossRub: n(opts.maxLossRub),
    /** Экстремальный DD: закрыть, если плавающий убыток ≥ pct% от номинала сделки. */
    maxLossPctOfNotional: n(opts.maxLossPctOfNotional),
    forcedHoldHoursIfLosing: n(opts.forcedHoldHoursIfLosing),
    forcedHoldRequireNeverGreen: !!opts.forcedHoldRequireNeverGreen,
  };
}

/** Группы Risk exit — независимый выбор, opts мержатся. */
const RISK_EXIT_GROUPS = [
  {
    id: 'zStop',
    label: 'Z±',
    title: 'Z-stop: фикс ±0.5 или % от |Z входа| в сторону mean-revert',
    selId: 'riskZSel',
    lsKey: 'moexReplay.riskZ',
    options: [
      { id: 'off', label: 'выкл', short: '—', opts: {} },
      { id: 'z05', label: '±0.5', short: 'Z±0.5', opts: { forcedZStopDeviation: 0.5 } },
      { id: 'z20', label: '±20%|Z|', short: 'Z±20%', opts: { forcedZStopPctOfEntryZ: 20 } },
      { id: 'z30', label: '±30%|Z|', short: 'Z±30%', opts: { forcedZStopPctOfEntryZ: 30 } },
      { id: 'z40', label: '±40%|Z|', short: 'Z±40%', opts: { forcedZStopPctOfEntryZ: 40 } },
    ],
  },
  {
    id: 'ddStop',
    label: 'DD stop',
    title: 'Экстремальный DD: закрыть при плавающем убытке ≥ N% от номинала сделки',
    selId: 'riskDdSel',
    lsKey: 'moexReplay.riskDd',
    options: [
      { id: 'off', label: 'выкл', short: '—', opts: {} },
      { id: 'dd15', label: '15% кап.', short: 'DD15%', opts: { maxLossPctOfNotional: 15 } },
      { id: 'dd20', label: '20% кап.', short: 'DD20%', opts: { maxLossPctOfNotional: 20 } },
      { id: 'dd25', label: '25% кап.', short: 'DD25%', opts: { maxLossPctOfNotional: 25 } },
    ],
  },
  {
    id: 'timeStop',
    label: 'Время',
    title: 'Time stop: закрыть по времени (всегда / только в минусе)',
    selId: 'riskTimeSel',
    lsKey: 'moexReplay.riskTime',
    options: [
      { id: 'off', label: 'выкл', short: '—', opts: {} },
      { id: 't24', label: '24ч всегда', short: 'T24', opts: { forcedTimeStopHours: 24 } },
      { id: 't48', label: '48ч всегда', short: 'T48', opts: { forcedTimeStopHours: 48 } },
      { id: 'h48', label: '48ч + минус', short: 'H48−', opts: { forcedHoldHoursIfLosing: 48 } },
      {
        id: 'h48ng',
        label: '48ч− never-green',
        short: 'H48−NG',
        opts: { forcedHoldHoursIfLosing: 48, forcedHoldRequireNeverGreen: true },
      },
    ],
  },
  {
    id: 'moneyStop',
    label: 'Money ₽',
    title: 'Money stop: фиксированный ₽ или 3.5× среднего выигрыша',
    selId: 'riskMoneySel',
    lsKey: 'moexReplay.riskMoney',
    options: [
      { id: 'off', label: 'выкл', short: '—', opts: {} },
      { id: 'm2k', label: '2000₽', short: 'M2k', opts: { maxLossRub: 2000 } },
      { id: 'm4k', label: '4000₽', short: 'M4k', opts: { maxLossRub: 4000 } },
      { id: 'm35w', label: '3.5×avgWin', short: 'M×3.5', opts: { maxLossRubDynamic: true } },
    ],
  },
];

/** Миграция со старого единого riskExitSel. */
const RISK_EXIT_LEGACY_MAP = {
  off: { zStop: 'off', ddStop: 'off', timeStop: 'off', moneyStop: 'off' },
  t24: { zStop: 'off', ddStop: 'off', timeStop: 't24', moneyStop: 'off' },
  z05: { zStop: 'z05', ddStop: 'off', timeStop: 'off', moneyStop: 'off' },
  z20pct: { zStop: 'z20', ddStop: 'off', timeStop: 'off', moneyStop: 'off' },
  z30pct: { zStop: 'z30', ddStop: 'off', timeStop: 'off', moneyStop: 'off' },
  z40pct: { zStop: 'z40', ddStop: 'off', timeStop: 'off', moneyStop: 'off' },
  m4k: { zStop: 'off', ddStop: 'off', timeStop: 'off', moneyStop: 'm4k' },
  dd15: { zStop: 'off', ddStop: 'dd15', timeStop: 'off', moneyStop: 'off' },
  dd20: { zStop: 'off', ddStop: 'dd20', timeStop: 'off', moneyStop: 'off' },
  dd25: { zStop: 'off', ddStop: 'dd25', timeStop: 'off', moneyStop: 'off' },
  m35w: { zStop: 'off', ddStop: 'off', timeStop: 'off', moneyStop: 'm35w' },
  h48: { zStop: 'off', ddStop: 'off', timeStop: 'h48', moneyStop: 'off' },
  h48ng: { zStop: 'off', ddStop: 'off', timeStop: 'h48ng', moneyStop: 'off' },
  antiHold: { zStop: 'z30', ddStop: 'dd20', timeStop: 't24', moneyStop: 'off' },
  z30dd20: { zStop: 'z30', ddStop: 'dd20', timeStop: 'off', moneyStop: 'off' },
  z40dd20: { zStop: 'z40', ddStop: 'dd20', timeStop: 'off', moneyStop: 'off' },
  combo1: { zStop: 'z05', ddStop: 'off', timeStop: 't24', moneyStop: 'm4k' },
  combo2: { zStop: 'off', ddStop: 'off', timeStop: 't24', moneyStop: 'off' },
  combo3: { zStop: 'z05', ddStop: 'off', timeStop: 'h48ng', moneyStop: 'm4k' },
};

function getRiskExitGroup(groupId) {
  return RISK_EXIT_GROUPS.find((g) => g.id === groupId) || null;
}

function getRiskExitGroupOption(groupId, optionId) {
  const g = getRiskExitGroup(groupId);
  if (!g) return { id: 'off', label: 'выкл', short: '—', opts: {} };
  return g.options.find((o) => o.id === optionId) || g.options[0];
}

/** Слить выбранные опции групп → raw sim opts. */
function mergeRiskExitGroupOpts(selectionByGroup) {
  const merged = {};
  for (const g of RISK_EXIT_GROUPS) {
    const opt = getRiskExitGroupOption(g.id, selectionByGroup[g.id] || 'off');
    Object.assign(merged, opt.opts);
  }
  return merged;
}

function formatRiskExitSelectionShort(selectionByGroup) {
  const parts = [];
  for (const g of RISK_EXIT_GROUPS) {
    const opt = getRiskExitGroupOption(g.id, selectionByGroup[g.id] || 'off');
    if (opt.id !== 'off') parts.push(opt.short || opt.label);
  }
  return parts.length ? parts.join('+') : 'выкл';
}

function simTradeDurationMs(entryDate, barDate) {
  const a = parseTradeMs(entryDate);
  const b = parseTradeMs(barDate);
  if (a == null || b == null) return null;
  return b - a;
}

/** Money / DD stop — плавающий убыток (комиссия выхода + overnight). */
function resolveMaxLossRubLimit(opts, constants) {
  const limits = [];
  if (opts.maxLossRub > 0) limits.push(opts.maxLossRub);
  if (opts.maxLossPctOfNotional > 0) {
    limits.push(Math.max(1, constants.notionalRub) * (opts.maxLossPctOfNotional / 100));
  }
  if (!limits.length) return 0;
  return Math.min(...limits);
}

function stopLossRubHit(bar, position, entrySpread, entryDate, constants, maxLossRub) {
  if (!(maxLossRub > 0) || (position !== 'Long' && position !== 'Short')) return false;
  // Parity zsim._stop_loss_hit: MTM без exit-slip (slip только в entrySpread / на реальном выходе).
  const net = openTradeNetRub(bar, position, entrySpread, entryDate, constants, false);
  return -net >= maxLossRub;
}

/**
 * Forced exits — parity MoexZStrategySim.forcedExitHit + TP + money/DD stop.
 * @returns {'ExitLong'|'ExitShort'|'None'}
 */
function resolveProtectiveExit(ctx) {
  const {
    bar, position, entrySpread, entryDate, entryZ, peakMtmNetRub, constants, opts,
  } = ctx;
  if (position !== 'Long' && position !== 'Short') return 'None';
  const exitSig = position === 'Long' ? 'ExitLong' : 'ExitShort';
  const netClose = openTradeNetRub(bar, position, entrySpread, entryDate, constants, true);
  const netMtm = openTradeNetRub(bar, position, entrySpread, entryDate, constants, false);

  if (opts.takeProfitPct > 0) {
    const pct = (netMtm / Math.max(1, constants.notionalRub)) * 100;
    if (pct >= opts.takeProfitPct) return exitSig;
  }
  const maxLossRub = resolveMaxLossRubLimit(opts, constants);
  if (stopLossRubHit(bar, position, entrySpread, entryDate, constants, maxLossRub)) {
    return exitSig;
  }

  const durationMs = simTradeDurationMs(entryDate, bar.tradeDate);
  if (durationMs == null) return 'None';

  if (opts.forcedTimeStopHours > 0
    && durationMs >= opts.forcedTimeStopHours * 3_600_000) {
    return exitSig;
  }
  if (opts.forcedZStopPctOfEntryZ > 0 || opts.forcedZStopDeviation > 0) {
    const z = bar.zScore ?? 0;
    // % от |Z_входа| имеет приоритет над фикс. deviation, если задан.
    const dev = opts.forcedZStopPctOfEntryZ > 0
      ? (opts.forcedZStopPctOfEntryZ / 100) * Math.abs(entryZ)
      : opts.forcedZStopDeviation;
    if (dev > 0) {
      if (position === 'Long' && z > entryZ + dev) return exitSig;
      if (position === 'Short' && z < entryZ - dev) return exitSig;
    }
  }
  if (opts.forcedHoldHoursIfLosing > 0
    && durationMs >= opts.forcedHoldHoursIfLosing * 3_600_000) {
    if (netClose < 0 && (!opts.forcedHoldRequireNeverGreen || peakMtmNetRub <= 0)) {
      return exitSig;
    }
  }
  return 'None';
}
