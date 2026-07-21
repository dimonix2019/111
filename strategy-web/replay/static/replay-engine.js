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

class BarReplayEngine {
  constructor(points, entry, exit, startIndex = Z_SCORE_ROLLING_MIN_BARS, opts = {}) {
    this.points = points;
    this.entry = entry;
    this.exit = exit;
    this.simOpts = normalizeSimExitOpts(opts);
    /** @deprecated use simOpts.takeProfitPct */
    this.takeProfitPct = this.simOpts.takeProfitPct;
    this.minCursor = Math.min(Math.max(0, startIndex), Math.max(0, points.length - 1));
    this.cursor = this.minCursor;
    this.state = 'Idle';
    this.speed = 1;
    this.position = 'Flat';
    this.openEntrySpread = 0;
    this.openEntryDate = '';
    this.openEntryZ = 0;
    this.peakMtmNetRub = 0;
    this.sizing = createSimSizingState();
    this.edges = [];
    this.manualEdges = [];
    this.manualSeq = 0;
    this.rebuildStateToCursor(this.minCursor);
  }

  get lastIndex() {
    return Math.max(0, this.points.length - 1);
  }

  get progressFraction() {
    const span = Math.max(1, this.lastIndex - this.minCursor);
    return Math.min(1, Math.max(0, (this.cursor - this.minCursor) / span));
  }

  play() {
    if (this.points.length < 2) return;
    if (this.cursor >= this.lastIndex) this.seekTo(this.minCursor);
    this.state = 'Playing';
  }

  pause() {
    if (this.state === 'Playing') this.state = 'Paused';
  }

  stepForward() {
    if (this.points.length < 2 || this.cursor >= this.lastIndex) {
      this.state = 'Idle';
      return null;
    }
    const next = this.cursor + 1;
    const edgesBefore = this.edges.length;
    // Инкремент: один бар, без полного rebuildStateToCursor (O(n) → O(1))
    this._processBarAt(next);
    this.cursor = next;
    const newEdge = this.edges.length > edgesBefore ? this.edges[this.edges.length - 1] : null;
    return this._frame(newEdge?.signal ?? null);
  }

  stepBackward() {
    if (this.state === 'Playing') this.state = 'Paused';
    return this.seekTo(Math.max(this.minCursor, this.cursor - 1));
  }

  seekToStart() {
    return this.seekTo(this.minCursor);
  }

  seekToEnd() {
    return this.seekTo(this.lastIndex);
  }

  seekTo(index) {
    const target = Math.min(this.lastIndex, Math.max(this.minCursor, index));
    this.rebuildStateToCursor(target);
    this.cursor = target;
    if (this.state === 'Playing' && this.cursor >= this.lastIndex) this.state = 'Idle';
    return this._frame(null);
  }

  frameAtCursor() {
    return this._frame(null);
  }

  /**
   * Обработать один бар i (сигналы Z / protective / manual).
   * Требует корректного состояния на баре i-1 (или i==0).
   */
  _processBarAt(i) {
    if (i < 0 || i >= this.points.length) return;
    if (i === 0) {
      this._applyManualEdgesAtIndex(0);
      return;
    }
    const prev = this.points[i - 1];
    const cur = this.points[i];
    let signal = 'None';

    // Вне сессии TQBR — только ручные маркеры (нет AUTO entry/exit / risk-exit).
    if (!isMoexEquitySessionBar(cur.tradeDate)) {
      this._applyManualEdgesAtIndex(i);
      return;
    }

    if (
      (this.position === 'Long' || this.position === 'Short')
      && this.openEntryDate
    ) {
      const constants = this.sizing.constants;
      const netClose = openTradeNetRub(
        cur,
        this.position,
        this.openEntrySpread,
        this.openEntryDate,
        constants,
        true,
      );
      this.peakMtmNetRub = Math.max(this.peakMtmNetRub, netClose);
      signal = resolveProtectiveExit({
        bar: cur,
        position: this.position,
        entrySpread: this.openEntrySpread,
        entryDate: this.openEntryDate,
        entryZ: this.openEntryZ,
        peakMtmNetRub: this.peakMtmNetRub,
        constants,
        opts: this.simOpts,
      });
    }

    if (signal === 'None' && isConsecutiveM15Bar(prev, cur)) {
      signal = determineZSignal(prev.zScore, cur.zScore, this.position, this.entry, this.exit);
    }
    if (signal !== 'None') this._pushEdge(signal, cur, false);
    this._applyManualEdgesAtIndex(i);
  }

  rebuildStateToCursor(target) {
    this.position = 'Flat';
    this.openEntrySpread = 0;
    this.openEntryDate = '';
    this.openEntryZ = 0;
    this.peakMtmNetRub = 0;
    this.sizing = createSimSizingState();
    this.edges = [];
    if (this.points.length < 1 || target < 0) return;

    const lastBar = Math.min(target, this.points.length - 1);
    this._processBarAt(0);
    if (lastBar < 1) return;

    for (let i = 1; i <= lastBar; i++) {
      this._processBarAt(i);
    }
  }

  _applyManualEdgesAtIndex(barIndex) {
    const manualAtBar = this.manualEdges
      .filter((e) => e.barIndex === barIndex)
      .sort((a, b) => a.seq - b.seq);
    for (const me of manualAtBar) {
      this._pushEdge(me.signal, me.bar, true);
    }
  }

  _pushEdge(signal, bar, manual) {
    const before = this.position;
    if (signal === 'EnterLong' || signal === 'EnterShort') {
      if (before === 'Long' || before === 'Short') return null;
    } else if (signal === 'ExitLong') {
      if (before !== 'Long') return null;
    } else if (signal === 'ExitShort') {
      if (before !== 'Short') return null;
    } else {
      return null;
    }
    const edge = {
      signal,
      bar,
      positionBefore: before,
      positionAfter: positionAfter(signal),
      manual: !!manual,
    };
    this.position = edge.positionAfter;
    if (signal === 'EnterLong' || signal === 'EnterShort') {
      const dir = signal === 'EnterLong' ? 'Long' : 'Short';
      this.openEntrySpread = simEntrySpread(bar.spreadPercent ?? 0, dir);
      this.openEntryDate = bar.tradeDate;
      this.openEntryZ = bar.zScore ?? 0;
      this.peakMtmNetRub = openTradeNetRub(
        bar,
        dir,
        this.openEntrySpread,
        this.openEntryDate,
        this.sizing.constants,
        true,
      );
    } else if (signal === 'ExitLong' || signal === 'ExitShort') {
      if (this.openEntryDate && (before === 'Long' || before === 'Short')) {
        const net = openTradeNetRub(
          bar,
          before,
          this.openEntrySpread,
          this.openEntryDate,
          this.sizing.constants,
          true,
        );
        this.sizing.applyClosedNet(net);
      }
      this.openEntrySpread = 0;
      this.openEntryDate = '';
      this.openEntryZ = 0;
      this.peakMtmNetRub = 0;
    }
    this.edges.push(edge);
    return edge;
  }

  /** Текущая сторона по цепочке edges (надёжнее, чем только this.position). */
  openSideFromEdges() {
    let side = 'Flat';
    for (const e of this.edges) {
      if (e.signal === 'EnterLong') side = 'Long';
      else if (e.signal === 'EnterShort') side = 'Short';
      else if (e.signal === 'ExitLong' || e.signal === 'ExitShort') side = 'Flat';
    }
    return side;
  }

  /** Ручной Long на текущем баре (при Short — сначала закрытие). */
  manualLong() {
    this.rebuildStateToCursor(this.cursor);
    const side = this.openSideFromEdges();
    const signals = [];
    if (side === 'Short') signals.push('ExitShort');
    if (side !== 'Long') signals.push('EnterLong');
    return this._injectManualSignals(signals);
  }

  /** Ручной Short на текущем баре (при Long — сначала закрытие). */
  manualShort() {
    this.rebuildStateToCursor(this.cursor);
    const side = this.openSideFromEdges();
    const signals = [];
    if (side === 'Long') signals.push('ExitLong');
    if (side !== 'Short') signals.push('EnterShort');
    return this._injectManualSignals(signals);
  }

  /** Закрыть открытую позицию на текущем баре (ручную или авто). */
  closeAllTrades() {
    this.rebuildStateToCursor(this.cursor);
    let side = this.openSideFromEdges();
    if (side === 'Flat' && (this.position === 'Long' || this.position === 'Short')) {
      side = this.position;
    }
    if (side === 'Flat') return false;

    const signal = side === 'Long' ? 'ExitLong' : 'ExitShort';
    const bar = this.points[this.cursor];
    if (!bar) return false;

    // Убрать отложенные/дублирующие выходы на текущем и будущих барах
    this.manualEdges = this.manualEdges.filter((e) => {
      if (e.barIndex > this.cursor) return false;
      if (
        e.barIndex === this.cursor
        && (e.signal === 'ExitLong' || e.signal === 'ExitShort')
      ) {
        return false;
      }
      return true;
    });

    this.manualEdges.push({
      signal,
      bar,
      barIndex: this.cursor,
      seq: this.manualSeq++,
      manual: true,
    });

    this.rebuildStateToCursor(this.cursor);

    // Если авто-сигнал или порядок дали Flat до manual Exit — всё равно зафиксировать выход
    if (this.openSideFromEdges() !== 'Flat') {
      this.position = side;
      const pushed = this._pushEdge(signal, bar, true);
      if (!pushed) {
        this.edges.push({
          signal,
          bar,
          positionBefore: side,
          positionAfter: 'Flat',
          manual: true,
        });
        this.position = 'Flat';
      }
    }

    return this.openSideFromEdges() === 'Flat';
  }

  _findOpenEntryEdge() {
    let open = null;
    for (const e of this.edges) {
      if (e.signal === 'EnterLong' || e.signal === 'EnterShort') {
        open = e;
        open.barIndex = this.points.findIndex((p) => p.timestampMs === e.bar.timestampMs);
      } else if (e.signal === 'ExitLong' || e.signal === 'ExitShort') {
        open = null;
      }
    }
    return open;
  }

  _injectManualSignals(signals) {
    if (!signals.length || !this.points.length) return false;
    const bar = this.points[this.cursor];
    if (!bar) return false;

    const opens = signals.some((s) => s === 'EnterLong' || s === 'EnterShort');
    if (opens) {
      // Убрать выходы на этом баре — иначе старый «Закрыть» сразу гасит новый вход
      this.manualEdges = this.manualEdges.filter((e) => !(
        e.barIndex === this.cursor
        && (e.signal === 'ExitLong' || e.signal === 'ExitShort')
      ));
    }

    for (const signal of signals) {
      this.manualEdges.push({
        signal,
        bar,
        barIndex: this.cursor,
        seq: this.manualSeq++,
        manual: true,
      });
    }
    this.rebuildStateToCursor(this.cursor);
    return this.openSideFromEdges() !== 'Flat' || signals.some((s) => s === 'ExitLong' || s === 'ExitShort');
  }

  _frame(newSignal) {
    if (!this.points.length) {
      return {
        cursorIndex: 0,
        visiblePoints: [],
        position: 'Flat',
        newSignalThisBar: null,
        barLabel: '',
        signalEdgesSoFar: [],
      };
    }
    const idx = Math.min(this.lastIndex, Math.max(0, this.cursor));
    return {
      cursorIndex: idx,
      visiblePoints: this.points.slice(0, idx + 1),
      position: this.position,
      newSignalThisBar: newSignal,
      barLabel: this.points[idx].tradeDate,
      signalEdgesSoFar: [...this.edges],
    };
  }
}

function barReplayVisibleIndexRange(points, cursorIndex, visibleDays = 30, includeIndex = null) {
  if (!points.length) return { start: 0, end: 0 };
  const cursor = Math.min(cursorIndex, points.length - 1);
  // «Всё» (400+) — от начала данных до курсора симуляции
  if (!visibleDays || visibleDays >= 400) {
    return { start: 0, end: cursor };
  }
  const till = points[cursor].timestampMs;
  const dayMs = 24 * 60 * 60 * 1000;
  let from = till - visibleDays * dayMs;
  // Расширить окно влево, чтобы сделка (includeIndex) попала в данные графика
  if (includeIndex != null && includeIndex >= 0 && includeIndex < points.length) {
    const focusMs = points[includeIndex].timestampMs;
    const padMs = Math.max(visibleDays * dayMs, visibleBarsOnScreen(visibleDays) * 15 * 60 * 1000);
    from = Math.min(from, focusMs - padMs / 2);
  }
  let start = points.findIndex((p) => p.timestampMs >= from);
  if (start < 0) start = 0;
  return { start, end: cursor };
}

/** Сколько баров держать на экране (фикс. zoom); slice = visibleDays календарных дней. */
function visibleBarsOnScreen(visibleDays) {
  // «Всё» — уместить весь загруженный период (zoom-out без потолка)
  if (!visibleDays || visibleDays >= 400) return 1_000_000;
  const map = { 30: 200, 90: 280, 180: 340 };
  if (map[visibleDays]) return map[visibleDays];
  return Math.min(500, Math.max(120, Math.round(visibleDays * 6.5)));
}

function buildZCandles(points) {
  return points.map((p, i) => {
    const close = p.zScore;
    const open = i > 0 ? points[i - 1].zScore : close;
    return {
      label: p.tradeDate,
      open,
      high: Math.max(open, close),
      low: Math.min(open, close),
      close,
    };
  });
}

function labelToUnixSec(label) {
  const s = label.trim().replace('T', ' ');
  const iso = s.length >= 16 ? `${s.slice(0, 16).replace(' ', 'T')}:00+03:00` : `${s}+03:00`;
  return Math.floor(new Date(iso).getTime() / 1000);
}

/** Подпись направления — как столбец «Напр.» в таблице (L / S). */
function tradeDirectionShort(direction) {
  return direction === 'Long' ? 'L' : 'S';
}

/** Уникальный id сделки для связи маркеров, сегментов и строк таблицы. */
function tradeSelectId(tradeNo) {
  return String(tradeNo);
}

function tradeDirectionFromSignal(signal) {
  if (signal === 'EnterLong' || signal === 'ExitLong') return 'Long';
  if (signal === 'EnterShort' || signal === 'ExitShort') return 'Short';
  return null;
}

function buildTradeSegments(edges, currentPoint) {
  const segments = [];
  let tradeNo = 0;
  let openEntry = null;

  for (const edge of edges) {
    if (edge.signal === 'EnterLong' || edge.signal === 'EnterShort') {
      tradeNo++;
      openEntry = edge;
    } else if (edge.signal === 'ExitLong' || edge.signal === 'ExitShort') {
      if (!openEntry) continue;
      const direction = tradeDirectionFromSignal(openEntry.signal);
      segments.push({
        id: tradeSelectId(tradeNo),
        entryTime: labelToUnixSec(openEntry.bar.tradeDate),
        entryZ: openEntry.bar.zScore ?? 0,
        exitTime: labelToUnixSec(edge.bar.tradeDate),
        exitZ: edge.bar.zScore ?? 0,
        open: false,
      });
      openEntry = null;
    }
  }

  if (openEntry && currentPoint) {
    const direction = tradeDirectionFromSignal(openEntry.signal);
    segments.push({
      id: tradeSelectId(tradeNo),
      entryTime: labelToUnixSec(openEntry.bar.tradeDate),
      entryZ: openEntry.bar.zScore ?? 0,
      exitTime: labelToUnixSec(currentPoint.tradeDate),
      exitZ: currentPoint.zScore ?? 0,
      open: true,
    });
  }
  return segments;
}

function buildMarkers(edges, windowPoints) {
  const markers = [];
  let tradeNo = 0;
  const windowLabels = new Set(windowPoints.map((p) => p.tradeDate));
  for (const edge of edges) {
    const inWindow = windowLabels.has(edge.bar.tradeDate);
    const time = labelToUnixSec(edge.bar.tradeDate);
    switch (edge.signal) {
      case 'EnterLong':
        tradeNo++;
        if (inWindow) {
          markers.push({
            time,
            position: 'belowBar',
            color: '#69F0AE',
            shape: 'arrowUp',
            text: `${tradeNo}${tradeDirectionShort('Long')}`,
            tradeId: tradeSelectId(tradeNo),
            isEntry: true,
            size: 2.5,
          });
        }
        break;
      case 'EnterShort':
        tradeNo++;
        if (inWindow) {
          markers.push({
            time,
            position: 'aboveBar',
            color: '#FF8A80',
            shape: 'arrowDown',
            text: `${tradeNo}${tradeDirectionShort('Short')}`,
            tradeId: tradeSelectId(tradeNo),
            isEntry: true,
            size: 2.5,
          });
        }
        break;
      case 'ExitLong':
        if (inWindow && tradeNo > 0) {
          markers.push({
            time,
            position: 'inBar',
            color: '#FFCC80',
            shape: 'circle',
            text: `${tradeNo}${tradeDirectionShort('Long')}`,
            tradeId: tradeSelectId(tradeNo),
            isEntry: false,
            size: 2.5,
          });
        }
        break;
      case 'ExitShort':
        if (inWindow && tradeNo > 0) {
          markers.push({
            time,
            position: 'inBar',
            color: '#FFCC80',
            shape: 'circle',
            text: `${tradeNo}${tradeDirectionShort('Short')}`,
            tradeId: tradeSelectId(tradeNo),
            isEntry: false,
            size: 2.5,
          });
        }
        break;
      default:
        break;
    }
  }
  return markers;
}

/**
 * Точки на Z: первый бар, где PnL сделки (% от вложения) ≥ 1% / 2% / 3%.
 * Квадраты над свечой; на одном баре снизу вверх: 1% → 2% → 3%.
 */
function buildPnlMilestoneMarkers(edges, allPoints, windowPoints, cursorIndex) {
  if (!allPoints.length || !windowPoints.length || !edges.length) return [];

  const markers = [];
  const windowLabels = new Set(windowPoints.map((p) => p.tradeDate));
  const edgesByMs = groupEdgesByBarMs(edges);
  const lastIdx = Math.min(cursorIndex, allPoints.length - 1);
  const sizing = createSimSizingState();

  let position = 'Flat';
  let entrySpread = 0;
  let entryDate = '';
  let tradeNo = 0;
  /** @type {{ entryDate: string, direction: string, entrySpread: number, tradeNo: number, constants: object } | null} */
  let open = null;

  const flushTrade = (endDate, closedAtEnd) => {
    if (!open) return;
    const ms = computeTradePnlMilestones(
      allPoints,
      open.entryDate,
      endDate,
      open.direction,
      open.entrySpread,
      closedAtEnd,
      open.constants,
    );
    if (closedAtEnd) {
      const endBar = allPoints.find((p) => p.tradeDate === endDate) || allPoints[lastIdx];
      if (endBar) {
        const net = openTradeNetRub(
          endBar,
          open.direction,
          open.entrySpread,
          open.entryDate,
          open.constants,
          true,
        );
        sizing.applyClosedNet(net);
      }
    }
    const tid = tradeSelectId(open.tradeNo);
    const push = (date, level) => {
      if (!date || !windowLabels.has(date)) return;
      const style = level === 1
        ? { color: '#69F0AE', text: '▲1%', size: 2.0 }
        : level === 2
          ? { color: '#40C4FF', text: '▲2%', size: 2.2 }
          : { color: '#E040FB', text: '▲3%', size: 2.4 };
      markers.push({
        time: labelToUnixSec(date),
        position: 'aboveBar',
        color: style.color,
        shape: 'square',
        text: style.text,
        tradeId: tid,
        isEntry: false,
        size: style.size,
        milestone: level,
      });
    };
    push(ms.hit1Date, 1);
    push(ms.hit2Date, 2);
    push(ms.hit3Date, 3);
  };

  for (let i = 0; i <= lastIdx; i++) {
    const p = allPoints[i];
    const barEdges = edgesByMs.get(p.timestampMs) || [];

    for (const edge of barEdges) {
      if (edge.signal === 'EnterLong' || edge.signal === 'EnterShort') {
        if (open) flushTrade(p.tradeDate, false);
        position = edge.positionAfter;
        entrySpread = simEntrySpread(edge.bar.spreadPercent ?? 0, position);
        entryDate = edge.bar.tradeDate;
        tradeNo += 1;
        open = {
          entryDate,
          direction: position,
          entrySpread,
          tradeNo,
          constants: sizing.constants,
        };
      } else if (edge.signal === 'ExitLong' || edge.signal === 'ExitShort') {
        if (open) {
          flushTrade(edge.bar.tradeDate, true);
          open = null;
        }
        position = 'Flat';
        entrySpread = 0;
        entryDate = '';
      }
    }
  }

  if (open) {
    const end = allPoints[lastIdx]?.tradeDate || open.entryDate;
    flushTrade(end, false);
  }

  markers.sort((a, b) => a.time - b.time || (a.milestone || 0) - (b.milestone || 0));
  return markers;
}

/** Первый бар удержания, где PnL (% от номинала сделки) достиг 1% / 2% / 3%. */
function computeTradePnlMilestones(allPoints, entryDate, endDate, direction, entrySpread, closedAtEnd, constants) {
  const c = constants || simPnlConstants();
  const notional = Math.max(1, c.notionalRub);
  const entryMs = parseTradeMs(entryDate);
  const endMs = parseTradeMs(endDate);
  const out = {
    hit1Date: null, hit2Date: null, hit3Date: null,
    hit1Ms: null, hit2Ms: null, hit3Ms: null,
  };
  if (entryMs == null || endMs == null || !allPoints.length) return out;

  let hit1 = false;
  let hit2 = false;
  let hit3 = false;
  for (const p of allPoints) {
    if (p.timestampMs < entryMs || p.timestampMs > endMs) continue;
    const includeExit = closedAtEnd && p.timestampMs === endMs;
    const net = openTradeNetRub(p, direction, entrySpread, entryDate, c, includeExit);
    const pct = (net / notional) * 100;
    if (!hit1 && pct >= 1) {
      hit1 = true;
      out.hit1Date = p.tradeDate;
      out.hit1Ms = p.timestampMs;
    }
    if (!hit2 && pct >= 2) {
      hit2 = true;
      out.hit2Date = p.tradeDate;
      out.hit2Ms = p.timestampMs;
    }
    if (!hit3 && pct >= 3) {
      hit3 = true;
      out.hit3Date = p.tradeDate;
      out.hit3Ms = p.timestampMs;
    }
    if (hit1 && hit2 && hit3) break;
  }
  return out;
}

/** Открытая сделка — parity шторки Android (formatSignalMonitorOpenTradeLine). */
function buildOpenTradeOverlay(edges, currentPoint, positionHint = null) {
  if (!currentPoint) return null;
  const sizing = createSimSizingState();
  let tradeNo = 0;
  let openEntry = null;
  let entryConstants = sizing.constants;
  for (const edge of edges) {
    if (edge.signal === 'EnterLong' || edge.signal === 'EnterShort') {
      tradeNo++;
      openEntry = edge;
      entryConstants = sizing.constants;
    } else if (edge.signal === 'ExitLong' || edge.signal === 'ExitShort') {
      if (openEntry) {
        const isLong = openEntry.signal === 'EnterLong';
        const dir = isLong ? 'Long' : 'Short';
        const net = openTradeNetRub(
          edge.bar,
          dir,
          simEntrySpread(openEntry.bar.spreadPercent ?? 0, dir),
          openEntry.bar.tradeDate,
          entryConstants,
          true,
        );
        sizing.applyClosedNet(net);
      }
      openEntry = null;
    }
  }
  // Fallback: позиция открыта, а парный Exit «съел» Enter на том же баре
  if (!openEntry && (positionHint === 'Long' || positionHint === 'Short')) {
    tradeNo = 0;
    for (const edge of edges) {
      if (edge.signal === 'EnterLong' || edge.signal === 'EnterShort') {
        tradeNo++;
        if (
          (positionHint === 'Long' && edge.signal === 'EnterLong')
          || (positionHint === 'Short' && edge.signal === 'EnterShort')
        ) {
          openEntry = edge;
          entryConstants = sizing.constants;
        }
      }
    }
  }
  if (!openEntry) return null;

  const isLong = openEntry.signal === 'EnterLong';
  const direction = isLong ? 'Long' : 'Short';
  const dirShort = tradeDirectionShort(direction);
  const entrySpread = simEntrySpread(openEntry.bar.spreadPercent ?? 0, direction);
  const lastSpread = simExitSpread(currentPoint.spreadPercent ?? 0, direction);
  const pnlPts = isLong ? lastSpread - entrySpread : entrySpread - lastSpread;
  const { effNotional, commPerSide, overnightPerDay } = entryConstants;
  const gross = spreadPnlToRub(pnlPts, effNotional);
  const ovn = overnightPerDay * overnightDays(openEntry.bar.tradeDate, currentPoint.tradeDate);
  const net = gross - commPerSide - ovn;
  const z = currentPoint.zScore;
  const zText = z != null ? (z >= 0 ? `+${z.toFixed(2)}` : z.toFixed(2)) : '—';
  const dirLabel = isLong ? 'LONG спрэд' : 'SHORT спрэд';
  const entryZ = openEntry.bar.zScore ?? 0;

  return {
    tradeId: tradeSelectId(tradeNo),
    zLine: `Z=${zText}`,
    tradePrefix: `${tradeNo} ${dirShort} ${compactDateTime(openEntry.bar.tradeDate)} Z₀${entryZ.toFixed(2)}`,
    net,
    netText: formatRub(net),
    spreadLine: `${dirLabel} · ${entrySpread.toFixed(2)}% → ${lastSpread.toFixed(2)}%`,
    duration: formatSimTradeDuration(openEntry.bar.tradeDate, currentPoint.tradeDate),
  };
}

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
  { key: 'Index', title: '#', width: 28 },
  { key: 'Direction', title: 'Напр.', width: 40 },
  { key: 'Entry', title: 'Вход', width: 96 },
  { key: 'Exit', title: 'Выход', width: 96 },
  { key: 'EntryZ', title: 'Zвх', width: 44, hint: 'Z-score на баре входа' },
  { key: 'ExitZ', title: 'Zвых', width: 44, hint: 'Z-score на баре выхода' },
  { key: 'Duration', title: 'Длит.', width: 52 },
  { key: 'Net', title: 'Чист.', width: 56 },
  { key: 'PnlMin', title: 'Min', width: 48, hint: 'Мин. MTM по сделке (от входа, с комиссией входа и overnight)' },
  { key: 'PnlMax', title: 'Max', width: 48, hint: 'Макс. MTM по сделке (от входа, с комиссией входа и overnight)' },
  { key: 'Hit1', title: '1%', width: 72, hint: 'Первый бар, где PnL ≥ 1% от вложения' },
  { key: 'Hit2', title: '2%', width: 72, hint: 'Первый бар, где PnL ≥ 2% от вложения' },
  { key: 'Hit3', title: '3%', width: 72, hint: 'Первый бар, где PnL ≥ 3% от вложения' },
  { key: 'SpreadEntry', title: 'S%вх', width: 44 },
  { key: 'SpreadExit', title: 'S%вых', width: 44 },
  { key: 'SpreadDelta', title: 'Δпп', width: 40 },
  { key: 'Gross', title: 'Вал.', width: 52 },
  { key: 'Commission', title: 'Ком.', width: 48 },
  { key: 'Overnight', title: 'Овн.', width: 48 },
  { key: 'Risk', title: 'Риск', width: 72 },
];

const TRADE_COLUMN_KEYS = TRADE_COLUMNS.map((c) => c.key);
const TRADE_COLUMNS_DEFAULT = [...TRADE_COLUMN_KEYS];

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
 */
function buildMonthlyPnl(rows) {
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
  for (const m of months) {
    const a = Math.abs(m.pnl);
    if (a > maxAbs) maxAbs = a;
  }
  return months.map((m) => ({
    key: m.key,
    label: formatMonthPnlLabel(m.key),
    pnl: m.pnl,
    count: m.count,
    barPct: maxAbs > 0 ? (Math.abs(m.pnl) / maxAbs) * 100 : 0,
  }));
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
const TRADE_COLUMNS_MIG_VERSION = 4;

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
  return {
    index: t.index,
    direction: t.direction,
    entryDate: t.entryDate,
    exitDate: t.exitDate,
    duration: closed ? formatSimTradeDuration(t.entryDate, t.exitDate) : '—',
    durationTone: closed ? durationTone(t.entryDate, t.exitDate) : 'neutral',
    net: closed ? formatRub(t.net) : '—',
    netValue: t.net,
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
    spreadEntry: t.entrySpread != null ? t.entrySpread.toFixed(2) : '—',
    spreadExit: t.exitSpread != null ? t.exitSpread.toFixed(2) : '—',
    spreadDelta: closed && t.pnlPts != null ? `${t.pnlPts >= 0 ? '+' : ''}${t.pnlPts.toFixed(2)}` : '—',
    gross: closed ? formatRub(t.gross) : '—',
    grossValue: t.gross,
    commission: closed ? formatCostRub(t.commission) : '—',
    overnight: closed ? formatCostRub(t.overnight) : '—',
    spreadEntryValue: t.entrySpread ?? null,
    spreadExitValue: t.exitSpread ?? null,
    spreadDeltaValue: t.pnlPts ?? null,
    commissionValue: t.commission ?? null,
    overnightValue: t.overnight ?? null,
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
    case 'Entry': return parseTradeMs(row.entryDate);
    case 'Exit':
      if (row.exitDate === '—') return null;
      return parseTradeMs(row.exitDate);
    case 'EntryZ': return row.entryZ;
    case 'ExitZ': return row.exitZ;
    case 'Duration': return row.durationMs;
    case 'Net': return row.netValue;
    case 'PnlMin': return row.pnlMinValue;
    case 'PnlMax': return row.pnlMaxValue;
    case 'Hit1': return row.hit1Ms;
    case 'Hit2': return row.hit2Ms;
    case 'Hit3': return row.hit3Ms;
    case 'SpreadEntry': return row.spreadEntryValue;
    case 'SpreadExit': return row.spreadExitValue;
    case 'SpreadDelta': return row.spreadDeltaValue;
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

function tradeCellValue(row, colKey) {
  switch (colKey) {
    case 'Index': return String(row.index);
    case 'Direction': return row.direction === 'Long' ? 'L' : 'S';
    case 'Entry': return compactTradeDateTime(row.entryDate);
    case 'Exit': return row.exitDate === '—' ? '—' : compactTradeDateTime(row.exitDate);
    case 'EntryZ': return row.entryZText;
    case 'ExitZ': return row.exitZText;
    case 'Duration': return row.duration;
    case 'Net': return row.net;
    case 'PnlMin': return row.pnlMin;
    case 'PnlMax': return row.pnlMax;
    case 'Hit1': return row.hit1;
    case 'Hit2': return row.hit2;
    case 'Hit3': return row.hit3;
    case 'SpreadEntry': return row.spreadEntry;
    case 'SpreadExit': return row.spreadExit;
    case 'SpreadDelta': return row.spreadDelta;
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
            : null;
    if (v == null) return '';
    if (v > 0) return 'pnl-pos';
    if (v < 0) return 'pnl-neg';
  }
  if (colKey === 'Hit1' || colKey === 'Hit2' || colKey === 'Hit3') {
    const v = colKey === 'Hit1' ? row.hit1Ms : (colKey === 'Hit2' ? row.hit2Ms : row.hit3Ms);
    return v != null ? 'pnl-pos' : '';
  }
  if (colKey === 'Commission' || colKey === 'Overnight') return 'cost';
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
    const h = Math.max(2, Math.round((count / maxBin) * 100));
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

/** Кривая equity (realized + MTM) — parity MoexZStrategySim.equityRubAt. */
function simPnlConstants(notionalRub = getSimNotionalRub()) {
  const effNotional = notionalRub * SIM_LEVERAGE;
  const commPerSide = effNotional * (SIM_COMMISSION_PCT_PER_SIDE / 100);
  const overnightPerDay = notionalRub * Math.max(0, SIM_LEVERAGE - 1) * (SIM_OVERNIGHT_FEE_PCT_PER_DAY / 100);
  return { effNotional, commPerSide, overnightPerDay, notionalRub };
}

function openTradeNetRub(bar, position, entrySpread, entryDate, constants, includeExitComm = false) {
  const { effNotional, commPerSide, overnightPerDay } = constants;
  // MTM (includeExitComm=false): сырой спред — parity zsim.mtm_rub (без второго slip).
  // Закрытие (true): adverse exit-slip, как zsim._exit_spread / close_*.
  const markSp = includeExitComm
    ? simExitSpread(bar.spreadPercent ?? 0, position)
    : (Number(bar.spreadPercent) || 0);
  const pnlPts = position === 'Long'
    ? markSp - entrySpread
    : entrySpread - markSp;
  const gross = spreadPnlToRub(pnlPts, effNotional);
  const ovn = overnightPerDay * overnightDays(entryDate, bar.tradeDate);
  const comm = commPerSide * (includeExitComm ? 2 : 1);
  return gross - comm - ovn;
}

function equitySeriesToWindow(allPoints, windowPoints, cursorIndex, equityByMs) {
  const lastIdx = Math.min(cursorIndex, allPoints.length - 1);
  const cursorMs = allPoints[lastIdx]?.timestampMs ?? 0;
  const out = [];
  const seen = new Set();
  for (const p of windowPoints) {
    if (p.timestampMs > cursorMs) break;
    const time = labelToUnixSec(p.tradeDate);
    if (seen.has(time)) continue;
    seen.add(time);
    out.push({
      time,
      value: equityByMs.get(p.timestampMs) ?? 0,
    });
  }
  return out;
}

function groupEdgesByBarMs(edges) {
  const map = new Map();
  for (const edge of edges) {
    const ms = edge.bar.timestampMs;
    if (!map.has(ms)) map.set(ms, []);
    map.get(ms).push(edge);
  }
  return map;
}

function applyEdgesOnBar(edgesOnBar, state, handlers) {
  if (!edgesOnBar?.length) return;
  for (const edge of edgesOnBar) {
    if (edge.signal === 'EnterLong' || edge.signal === 'EnterShort') {
      handlers.onEnter?.(edge, state);
    } else if (edge.signal === 'ExitLong' || edge.signal === 'ExitShort') {
      handlers.onExit?.(edge, state);
    }
  }
}

function buildEquitySeries(allPoints, edges, windowPoints, cursorIndex) {
  if (!allPoints.length || !windowPoints.length) return [];

  const sizing = createSimSizingState();
  let { effNotional, commPerSide, overnightPerDay } = sizing.constants;
  const edgesByMs = groupEdgesByBarMs(edges);

  const state = {
    position: 'Flat',
    entrySpread: 0,
    entryDate: '',
    realizedRub: 0,
  };
  const equityByMs = new Map();
  const lastIdx = Math.min(cursorIndex, allPoints.length - 1);

  for (let i = 0; i <= lastIdx; i++) {
    const p = allPoints[i];
    applyEdgesOnBar(edgesByMs.get(p.timestampMs), state, {
      onEnter: (edge, s) => {
        ({ effNotional, commPerSide, overnightPerDay } = sizing.constants);
        s.position = edge.positionAfter;
        s.entrySpread = simEntrySpread(edge.bar.spreadPercent ?? 0, s.position);
        s.entryDate = edge.bar.tradeDate;
        s.realizedRub -= commPerSide;
      },
      onExit: (edge, s) => {
        const isLong = edge.signal === 'ExitLong';
        const dir = isLong ? 'Long' : 'Short';
        const exitSpread = simExitSpread(edge.bar.spreadPercent ?? 0, dir);
        const pnlPts = isLong ? exitSpread - s.entrySpread : s.entrySpread - exitSpread;
        const gross = spreadPnlToRub(pnlPts, effNotional);
        const ovn = overnightPerDay * overnightDays(s.entryDate, edge.bar.tradeDate);
        const fullNet = gross - commPerSide * 2 - ovn;
        s.realizedRub += gross - commPerSide - ovn;
        sizing.applyClosedNet(fullNet);
        ({ effNotional, commPerSide, overnightPerDay } = sizing.constants);
        s.position = 'Flat';
        s.entrySpread = 0;
        s.entryDate = '';
      },
    });
    const exitSp = state.position === 'Long' || state.position === 'Short'
      ? simExitSpread(p.spreadPercent ?? 0, state.position)
      : (p.spreadPercent ?? 0);
    const mtmSpread = state.position === 'Long'
      ? exitSp - state.entrySpread
      : state.position === 'Short'
        ? state.entrySpread - exitSp
        : 0;
    equityByMs.set(p.timestampMs, state.realizedRub + spreadPnlToRub(mtmSpread, effNotional));
  }

  return equitySeriesToWindow(allPoints, windowPoints, cursorIndex, equityByMs);
}

/**
 * Базис % на оси PnL / подписях: пик капитала = депо + пик кумулятивного PnL.
 * Не стартовый notional — иначе −5.2k при счёте ~60k выглядит как «−52%».
 */
function resolvePnlPctBasisRub(equityTotalSeries, notionalRub = getSimNotionalRub()) {
  let peakPnl = 0;
  for (const p of equityTotalSeries || []) {
    const v = typeof p?.value === 'number' ? p.value : Number(p?.value);
    if (Number.isFinite(v) && v > peakPnl) peakPnl = v;
  }
  return Math.max(1, notionalRub + Math.max(0, peakPnl));
}

/** PnL одной открытой сделки: 0 вне позиции и на баре выхода. */
function buildPerTradeEquitySeries(allPoints, edges, windowPoints, cursorIndex) {
  if (!allPoints.length || !windowPoints.length) return [];

  const sizing = createSimSizingState();
  let constants = sizing.constants;
  const edgesByMs = groupEdgesByBarMs(edges);

  const state = {
    position: 'Flat',
    entrySpread: 0,
    entryDate: '',
  };
  const equityByMs = new Map();
  const lastIdx = Math.min(cursorIndex, allPoints.length - 1);

  for (let i = 0; i <= lastIdx; i++) {
    const p = allPoints[i];
    let equity = 0;
    const barEdges = edgesByMs.get(p.timestampMs) || [];

    for (const edge of barEdges) {
      if (edge.signal === 'ExitLong' || edge.signal === 'ExitShort') {
        if (state.position === 'Long' || state.position === 'Short') {
          const net = openTradeNetRub(
            edge.bar,
            state.position,
            state.entrySpread,
            state.entryDate,
            constants,
            true,
          );
          sizing.applyClosedNet(net);
          constants = sizing.constants;
        }
        state.position = 'Flat';
        state.entrySpread = 0;
        state.entryDate = '';
        equity = 0;
      } else if (edge.signal === 'EnterLong' || edge.signal === 'EnterShort') {
        constants = sizing.constants;
        state.position = edge.positionAfter;
        state.entrySpread = simEntrySpread(edge.bar.spreadPercent ?? 0, state.position);
        state.entryDate = edge.bar.tradeDate;
        equity = 0;
      }
    }
    if (state.position === 'Long' || state.position === 'Short') {
      const exitedThisBar = barEdges.some(
        (e) => e.signal === 'ExitLong' || e.signal === 'ExitShort',
      );
      if (!exitedThisBar) {
        equity = openTradeNetRub(p, state.position, state.entrySpread, state.entryDate, constants);
      }
    }
    equityByMs.set(p.timestampMs, equity);
  }

  return equitySeriesToWindow(allPoints, windowPoints, cursorIndex, equityByMs);
}

/** Δпп по сделкам: изменение спреда от входа (как столбец Δпп), 0 вне позиции. */
function buildDeltaPpSeries(allPoints, edges, windowPoints, cursorIndex) {
  if (!allPoints.length || !windowPoints.length) return [];

  const edgesByMs = groupEdgesByBarMs(edges);
  let position = 'Flat';
  let entrySpread = 0;
  const deltaByMs = new Map();
  const lastIdx = Math.min(cursorIndex, allPoints.length - 1);

  for (let i = 0; i <= lastIdx; i++) {
    const p = allPoints[i];
    const spread = p.spreadPercent ?? 0;
    let delta = 0;
    const barEdges = edgesByMs.get(p.timestampMs) || [];
    let closedDelta = null;

    for (const edge of barEdges) {
      if (edge.signal === 'EnterLong' || edge.signal === 'EnterShort') {
        position = edge.positionAfter;
        entrySpread = simEntrySpread(edge.bar.spreadPercent ?? 0, position);
        delta = 0;
      } else if (edge.signal === 'ExitLong' || edge.signal === 'ExitShort') {
        const isLong = edge.signal === 'ExitLong';
        const dir = isLong ? 'Long' : 'Short';
        const exitSp = simExitSpread(spread, dir);
        closedDelta = isLong ? exitSp - entrySpread : entrySpread - exitSp;
        position = 'Flat';
        entrySpread = 0;
      }
    }

    if (closedDelta != null) {
      delta = closedDelta;
    } else if (position === 'Long') {
      delta = simExitSpread(spread, 'Long') - entrySpread;
    } else if (position === 'Short') {
      delta = entrySpread - simExitSpread(spread, 'Short');
    }

    deltaByMs.set(p.timestampMs, delta);
  }

  return equitySeriesToWindow(allPoints, windowPoints, cursorIndex, deltaByMs);
}

function buildChartPayload(candles, entry, exit, markers, trades, playing, opts = {}) {
  const candleArr = [];
  const seen = new Set();
  for (const c of candles) {
    const time = labelToUnixSec(c.label);
    if (seen.has(time)) continue;
    seen.add(time);
    candleArr.push({
      time,
      open: c.open,
      high: c.high,
      low: c.low,
      close: c.close,
    });
  }
  const hlines = [
    { value: entry, color: '#EF5350', title: '+Entry' },
    { value: -entry, color: '#66BB6A', title: '−Entry' },
    { value: exit, color: '#FFB74D', title: '+Exit' },
    { value: -exit, color: '#4DD0E1', title: '−Exit' },
    { value: 0, color: '#616161', title: '0' },
  ];
  return {
    candles: candleArr,
    hlines,
    markers,
    trades,
    equity: opts.equity || [],
    deltaPp: opts.deltaPp || [],
    pnlBasisRub: typeof opts.pnlBasisRub === 'number' ? opts.pnlBasisRub : getSimNotionalRub(),
    fitFull: !!opts.fitFull,
    light: !!opts.light,
    markersChanged: opts.markersChanged !== false,
    windowWidth: typeof opts.windowWidth === 'number' ? opts.windowWidth : 1,
    maxVisibleBars: typeof opts.maxVisibleBars === 'number' ? opts.maxVisibleBars : 200,
    playing: !!playing,
  };
}
