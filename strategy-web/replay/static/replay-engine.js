/**
 * Bar Replay engine — parity с Android BarReplayEngine (Kotlin).
 */
const Z_SCORE_ROLLING_MIN_BARS = 48;
const BAR_REPLAY_BASE_DELAY_MS = 900;
const BAR_REPLAY_SPEEDS = [0.5, 1, 2, 5, 10];

function barReplayDelayMs(speed) {
  return Math.max(50, Math.round(BAR_REPLAY_BASE_DELAY_MS / Math.max(0.1, speed)));
}

function isConsecutiveM15Bar(prev, cur) {
  if (!prev.timestampMs || !cur.timestampMs) return false;
  return cur.timestampMs - prev.timestampMs === 15 * 60 * 1000;
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

class BarReplayEngine {
  constructor(points, entry, exit, startIndex = Z_SCORE_ROLLING_MIN_BARS) {
    this.points = points;
    this.entry = entry;
    this.exit = exit;
    this.minCursor = Math.min(Math.max(0, startIndex), Math.max(0, points.length - 1));
    this.cursor = this.minCursor;
    this.state = 'Idle';
    this.speed = 1;
    this.position = 'Flat';
    this.edges = [];
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
    const signal = this._advanceOneBar(next);
    this.cursor = next;
    return this._frame(signal);
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

  rebuildStateToCursor(target) {
    this.position = 'Flat';
    this.edges = [];
    if (this.points.length < 2 || target < 1) return;
    for (let i = 1; i <= target; i++) this._advanceOneBar(i);
  }

  _advanceOneBar(index) {
    const prev = this.points[index - 1];
    const cur = this.points[index];
    if (!isConsecutiveM15Bar(prev, cur)) return null;
    const signal = determineZSignal(prev.zScore, cur.zScore, this.position, this.entry, this.exit);
    if (signal === 'None') return null;
    const edge = {
      signal,
      bar: cur,
      positionBefore: this.position,
      positionAfter: positionAfter(signal),
    };
    this.position = edge.positionAfter;
    this.edges.push(edge);
    return edge;
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

function barReplayVisibleIndexRange(points, cursorIndex, visibleDays = 30) {
  if (!points.length) return { start: 0, end: 0 };
  const cursor = Math.min(cursorIndex, points.length - 1);
  const till = points[cursor].timestampMs;
  const from = till - visibleDays * 24 * 60 * 60 * 1000;
  let start = points.findIndex((p) => p.timestampMs >= from);
  if (start < 0) start = 0;
  return { start, end: cursor };
}

/** Сколько баров держать на экране (фикс. zoom); slice = visibleDays календарных дней. */
function visibleBarsOnScreen(visibleDays) {
  const map = { 30: 200, 90: 280, 180: 340, 400: 420 };
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

function buildMarkers(edges, windowPoints) {
  const markers = [];
  let tradeNo = 0;
  const windowMs = new Set(windowPoints.map((p) => p.timestampMs));
  for (const edge of edges) {
    if (!windowMs.has(edge.bar.timestampMs)) continue;
    const time = labelToUnixSec(edge.bar.tradeDate);
    switch (edge.signal) {
      case 'EnterLong':
        tradeNo++;
        markers.push({
          time,
          position: 'belowBar',
          color: '#69F0AE',
          shape: 'arrowUp',
          text: `${tradeNo}A`,
          tradeId: `${tradeNo}A`,
          isEntry: true,
        });
        break;
      case 'EnterShort':
        tradeNo++;
        markers.push({
          time,
          position: 'aboveBar',
          color: '#FF5252',
          shape: 'arrowDown',
          text: `${tradeNo}A`,
          tradeId: `${tradeNo}A`,
          isEntry: true,
        });
        break;
      case 'ExitLong':
        markers.push({
          time,
          position: 'inBar',
          color: '#80CBC4',
          shape: 'circle',
          text: `${tradeNo}R`,
          tradeId: `${tradeNo}R`,
          isEntry: false,
        });
        break;
      case 'ExitShort':
        markers.push({
          time,
          position: 'inBar',
          color: '#FFAB91',
          shape: 'circle',
          text: `${tradeNo}R`,
          tradeId: `${tradeNo}R`,
          isEntry: false,
        });
        break;
    }
  }
  return markers;
}

/** Симуляция PnL — parity с Android/zsim defaults. */
const SIM_NOTIONAL_RUB = 100_000;
const SIM_LEVERAGE = 7;
const SIM_COMMISSION_PCT_PER_SIDE = 0.04;
const SIM_OVERNIGHT_FEE_PCT_PER_DAY = 0.033;

const TRADE_COLUMNS = [
  { key: 'Index', title: '#', width: 28 },
  { key: 'Direction', title: 'Напр.', width: 40 },
  { key: 'Entry', title: 'Вход', width: 72 },
  { key: 'Exit', title: 'Выход', width: 72 },
  { key: 'Duration', title: 'Длит.', width: 52 },
  { key: 'Net', title: 'Чист.', width: 56 },
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

function formatSimTradeDuration(entryDate, exitDate) {
  const entryMs = parseTradeMs(entryDate);
  const exitMs = parseTradeMs(exitDate);
  if (entryMs == null || exitMs == null) return '—';
  const diffMs = exitMs - entryMs;
  if (diffMs < 0) return '—';
  if (diffMs === 0) return '0 мин';
  const totalMinutes = Math.floor(diffMs / 60000);
  if (totalMinutes === 0) return '< 1 мин';
  const days = Math.floor(totalMinutes / (24 * 60));
  const hours = Math.floor((totalMinutes % (24 * 60)) / 60);
  const minutes = totalMinutes % 60;
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

function buildTradeRiskFlags(entryDate, exitDate, entryZ, overnightRub, entryThreshold) {
  const durationMs = parseTradeMs(exitDate) - parseTradeMs(entryDate);
  const flags = [];
  if (durationMs != null && durationMs > 5 * 24 * 60 * 60 * 1000) flags.push('>5д');
  else if (durationMs != null && durationMs > 2 * 24 * 60 * 60 * 1000) flags.push('>2д');
  if (overnightRub > 100) flags.push('Ovn100');
  else if (overnightRub > 50 && durationMs != null && durationMs > 24 * 60 * 60 * 1000) flags.push('Ovn50');
  if (entryZ != null && Math.abs(entryZ) < 1.0 && durationMs != null && durationMs > 6 * 60 * 60 * 1000) {
    flags.push('Z<1');
  }
  if (durationMs != null && durationMs > 6 * 60 * 60 * 1000) {
    const h = entryHourMsk(entryDate);
    if (h === 13) flags.push('13ч');
    else if (h >= 12 && h <= 14) flags.push('12–14');
  }
  if (durationMs != null && durationMs > 2 * 24 * 60 * 60 * 1000 && isFridayEntryMsk(entryDate)) {
    flags.push('Пт>2д');
  }
  if (
    entryZ != null &&
    Math.abs(entryZ) < entryThreshold + 0.05 &&
    durationMs != null &&
    durationMs > 24 * 60 * 60 * 1000
  ) {
    flags.push('~порог');
  }
  return flags.length ? flags.join(' ') : '—';
}

function decodeTradeColumns(raw) {
  if (!raw) return [...TRADE_COLUMNS_DEFAULT];
  const loaded = raw.split(',').map((t) => t.trim()).filter((k) => TRADE_COLUMN_KEYS.includes(k));
  return loaded.length ? loaded : [...TRADE_COLUMNS_DEFAULT];
}

function encodeTradeColumns(keys) {
  const valid = keys.filter((k) => TRADE_COLUMN_KEYS.includes(k));
  return (valid.length ? valid : TRADE_COLUMNS_DEFAULT).join(',');
}

function buildTradeRows(edges, entryThreshold = 0.7) {
  const effNotional = SIM_NOTIONAL_RUB * SIM_LEVERAGE;
  const commPerSide = effNotional * (SIM_COMMISSION_PCT_PER_SIDE / 100);
  const overnightPerDay = SIM_NOTIONAL_RUB * Math.max(0, SIM_LEVERAGE - 1) * (SIM_OVERNIGHT_FEE_PCT_PER_DAY / 100);

  const rows = [];
  let tradeNo = 0;
  let openEntry = null;
  let entryCommission = 0;

  for (const edge of edges) {
    if (edge.signal === 'EnterLong' || edge.signal === 'EnterShort') {
      tradeNo++;
      openEntry = edge;
      entryCommission = commPerSide;
    } else if (edge.signal === 'ExitLong' || edge.signal === 'ExitShort') {
      if (!openEntry) continue;
      const isLong = openEntry.signal === 'EnterLong';
      const entrySpread = openEntry.bar.spreadPercent ?? 0;
      const exitSpread = edge.bar.spreadPercent ?? 0;
      const pnlPts = isLong ? exitSpread - entrySpread : entrySpread - exitSpread;
      const gross = spreadPnlToRub(pnlPts, effNotional);
      const ovn = overnightPerDay * overnightDays(openEntry.bar.tradeDate, edge.bar.tradeDate);
      const commTotal = entryCommission + commPerSide;
      const net = gross - commTotal - ovn;
      rows.push(makeTradeRow({
        index: tradeNo,
        direction: isLong ? 'Long' : 'Short',
        entryDate: openEntry.bar.tradeDate,
        exitDate: edge.bar.tradeDate,
        entryZ: openEntry.bar.zScore,
        entrySpread,
        exitSpread,
        pnlPts,
        gross,
        commission: commTotal,
        overnight: ovn,
        net,
        status: 'Закрыта',
        entryThreshold,
      }));
      openEntry = null;
      entryCommission = 0;
    }
  }
  if (openEntry) {
    const isLong = openEntry.signal === 'EnterLong';
    rows.push(makeTradeRow({
      index: tradeNo,
      direction: isLong ? 'Long' : 'Short',
      entryDate: openEntry.bar.tradeDate,
      exitDate: '—',
      entryZ: openEntry.bar.zScore,
      entrySpread: openEntry.bar.spreadPercent ?? 0,
      exitSpread: null,
      pnlPts: null,
      gross: null,
      commission: null,
      overnight: null,
      net: null,
      status: 'Открыта',
      entryThreshold,
    }));
  }
  return rows;
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
    spreadEntry: t.entrySpread != null ? t.entrySpread.toFixed(2) : '—',
    spreadExit: t.exitSpread != null ? t.exitSpread.toFixed(2) : '—',
    spreadDelta: closed && t.pnlPts != null ? `${t.pnlPts >= 0 ? '+' : ''}${t.pnlPts.toFixed(2)}` : '—',
    gross: closed ? formatRub(t.gross) : '—',
    grossValue: t.gross,
    commission: closed ? formatCostRub(t.commission) : '—',
    overnight: closed ? formatCostRub(t.overnight) : '—',
    risk: closed
      ? buildTradeRiskFlags(t.entryDate, t.exitDate, t.entryZ, t.overnight ?? 0, t.entryThreshold)
      : '—',
    status: t.status,
    entryZ: t.entryZ,
    exitZ: null,
  };
}

function tradeCellValue(row, colKey) {
  switch (colKey) {
    case 'Index': return String(row.index);
    case 'Direction': return row.direction === 'Long' ? 'L' : 'S';
    case 'Entry': return compactDateTime(row.entryDate);
    case 'Exit': return row.exitDate === '—' ? '—' : compactDateTime(row.exitDate);
    case 'Duration': return row.duration;
    case 'Net': return row.net;
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
  if (colKey === 'Net' || colKey === 'Gross' || colKey === 'SpreadDelta') {
    const v = colKey === 'Net' ? row.netValue : row.grossValue;
    if (v == null) return '';
    if (v > 0) return 'pnl-pos';
    if (v < 0) return 'pnl-neg';
  }
  if (colKey === 'Commission' || colKey === 'Overnight') return 'cost';
  if (colKey === 'Risk' && row.risk !== '—') return 'risk-flagged';
  return '';
}

function compactDateTime(label) {
  if (!label || label === '—') return '—';
  const s = String(label).replace('T', ' ');
  if (s.length >= 16) return `${s.slice(5, 10)} ${s.slice(11, 16)}`;
  return s;
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
    windowWidth: typeof opts.windowWidth === 'number' ? opts.windowWidth : 1,
    maxVisibleBars: typeof opts.maxVisibleBars === 'number' ? opts.maxVisibleBars : 200,
    playing: !!playing,
  };
}
