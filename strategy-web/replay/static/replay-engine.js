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
    this.rebuildStateToCursor(next);
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

  rebuildStateToCursor(target) {
    this.position = 'Flat';
    this.edges = [];
    if (this.points.length < 1 || target < 0) return;

    const lastBar = Math.min(target, this.points.length - 1);
    this._applyManualEdgesAtIndex(0);
    if (lastBar < 1) return;

    for (let i = 1; i <= lastBar; i++) {
      const prev = this.points[i - 1];
      const cur = this.points[i];
      if (isConsecutiveM15Bar(prev, cur)) {
        const signal = determineZSignal(prev.zScore, cur.zScore, this.position, this.entry, this.exit);
        if (signal !== 'None') this._pushEdge(signal, cur, false);
      }
      this._applyManualEdgesAtIndex(i);
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

/** Открытая сделка — parity шторки Android (formatSignalMonitorOpenTradeLine). */
function buildOpenTradeOverlay(edges, currentPoint, positionHint = null) {
  if (!currentPoint) return null;
  let tradeNo = 0;
  let openEntry = null;
  for (const edge of edges) {
    if (edge.signal === 'EnterLong' || edge.signal === 'EnterShort') {
      tradeNo++;
      openEntry = edge;
    } else if (edge.signal === 'ExitLong' || edge.signal === 'ExitShort') {
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
        }
      }
    }
  }
  if (!openEntry) return null;

  const isLong = openEntry.signal === 'EnterLong';
  const direction = isLong ? 'Long' : 'Short';
  const dirShort = tradeDirectionShort(direction);
  const entrySpread = openEntry.bar.spreadPercent ?? 0;
  const lastSpread = currentPoint.spreadPercent ?? 0;
  const pnlPts = isLong ? lastSpread - entrySpread : entrySpread - lastSpread;
  const { effNotional, commPerSide, overnightPerDay } = simPnlConstants();
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
let _simNotionalRub = SIM_NOTIONAL_DEFAULT;

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
  { key: 'PnlMin', title: 'Min', width: 48 },
  { key: 'PnlMax', title: 'Max', width: 48 },
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

  let cumulative = 0;
  let peak = 0;
  let maxDd = 0;
  for (const t of closed) {
    cumulative += t.netValue;
    if (cumulative > peak) peak = cumulative;
    const dd = peak - cumulative;
    if (dd > maxDd) maxDd = dd;
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

function decodeTradeColumns(raw) {
  if (!raw) return [...TRADE_COLUMNS_DEFAULT];
  const loaded = raw.split(',').map((t) => t.trim()).filter((k) => TRADE_COLUMN_KEYS.includes(k));
  return loaded.length ? loaded : [...TRADE_COLUMNS_DEFAULT];
}

function encodeTradeColumns(keys) {
  const valid = keys.filter((k) => TRADE_COLUMN_KEYS.includes(k));
  return (valid.length ? valid : TRADE_COLUMNS_DEFAULT).join(',');
}

/** Min/max чистого PnL по барам удержания (MTM; на выходе — с комиссией выхода). */
function computeTradePnlMinMax(allPoints, entryDate, endDate, direction, entrySpread, closedAtEnd) {
  const constants = simPnlConstants();
  const entryMs = parseTradeMs(entryDate);
  const endMs = parseTradeMs(endDate);
  if (entryMs == null || endMs == null || !allPoints.length) return { min: null, max: null };

  let min = Infinity;
  let max = -Infinity;
  let found = false;

  for (const p of allPoints) {
    if (p.timestampMs < entryMs || p.timestampMs > endMs) continue;
    const includeExit = closedAtEnd && p.timestampMs === endMs;
    const net = openTradeNetRub(p, direction, entrySpread, entryDate, constants, includeExit);
    found = true;
    if (net < min) min = net;
    if (net > max) max = net;
  }

  if (!found) return { min: null, max: null };
  return { min, max };
}

function buildTradeRows(edges, entryThreshold = 0.7, allPoints = [], cursorIndex = -1) {
  const { effNotional, commPerSide, overnightPerDay } = simPnlConstants();

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
      const { min: pnlMin, max: pnlMax } = computeTradePnlMinMax(
        allPoints,
        openEntry.bar.tradeDate,
        edge.bar.tradeDate,
        isLong ? 'Long' : 'Short',
        entrySpread,
        true,
      );
      const risk = assessTradeRisk(
        openEntry.bar.tradeDate,
        edge.bar.tradeDate,
        openEntry.bar.zScore,
        ovn,
        entryThreshold,
      );
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
        pnlMin,
        pnlMax,
        status: 'Закрыта',
        entryThreshold,
        _risk: risk,
      }));
      openEntry = null;
      entryCommission = 0;
    }
  }
  if (openEntry) {
    const isLong = openEntry.signal === 'EnterLong';
    const entrySpread = openEntry.bar.spreadPercent ?? 0;
    const cursorBar = cursorIndex >= 0 ? allPoints[cursorIndex] : null;
    const endDate = cursorBar?.tradeDate ?? openEntry.bar.tradeDate;
    const { min: pnlMin, max: pnlMax } = computeTradePnlMinMax(
      allPoints,
      openEntry.bar.tradeDate,
      endDate,
      isLong ? 'Long' : 'Short',
      entrySpread,
      false,
    );
    rows.push(makeTradeRow({
      index: tradeNo,
      direction: isLong ? 'Long' : 'Short',
      entryDate: openEntry.bar.tradeDate,
      exitDate: '—',
      entryZ: openEntry.bar.zScore,
      entrySpread,
      exitSpread: null,
      pnlPts: null,
      gross: null,
      commission: null,
      overnight: null,
      net: null,
      pnlMin,
      pnlMax,
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
    pnlMin: t.pnlMin != null ? formatRub(t.pnlMin) : '—',
    pnlMax: t.pnlMax != null ? formatRub(t.pnlMax) : '—',
    pnlMinValue: t.pnlMin,
    pnlMaxValue: t.pnlMax,
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
    exitZ: null,
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
    case 'Duration': return row.durationMs;
    case 'Net': return row.netValue;
    case 'PnlMin': return row.pnlMinValue;
    case 'PnlMax': return row.pnlMaxValue;
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
    case 'Entry': return compactDateTime(row.entryDate);
    case 'Exit': return row.exitDate === '—' ? '—' : compactDateTime(row.exitDate);
    case 'Duration': return row.duration;
    case 'Net': return row.net;
    case 'PnlMin': return row.pnlMin;
    case 'PnlMax': return row.pnlMax;
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
  if (colKey === 'Commission' || colKey === 'Overnight') return 'cost';
  if (colKey === 'Risk' && row.risk !== '—') {
    return row.riskRed ? 'risk-flagged risk-red' : 'risk-flagged';
  }
  return '';
}

function compactDateTime(label) {
  if (!label || label === '—') return '—';
  const s = String(label).replace('T', ' ');
  if (s.length >= 16) return `${s.slice(5, 10)} ${s.slice(11, 16)}`;
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
  const spread = bar.spreadPercent ?? 0;
  const pnlPts = position === 'Long'
    ? spread - entrySpread
    : entrySpread - spread;
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

  const { effNotional, commPerSide, overnightPerDay } = simPnlConstants();
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
        s.position = edge.positionAfter;
        s.entrySpread = edge.bar.spreadPercent ?? 0;
        s.entryDate = edge.bar.tradeDate;
        s.realizedRub -= commPerSide;
      },
      onExit: (edge, s) => {
        const isLong = edge.signal === 'ExitLong';
        const exitSpread = edge.bar.spreadPercent ?? 0;
        const pnlPts = isLong ? exitSpread - s.entrySpread : s.entrySpread - exitSpread;
        const gross = spreadPnlToRub(pnlPts, effNotional);
        const ovn = overnightPerDay * overnightDays(s.entryDate, edge.bar.tradeDate);
        s.realizedRub += gross - commPerSide - ovn;
        s.position = 'Flat';
        s.entrySpread = 0;
        s.entryDate = '';
      },
    });
    const spread = p.spreadPercent ?? 0;
    const mtmSpread = state.position === 'Long'
      ? spread - state.entrySpread
      : state.position === 'Short'
        ? state.entrySpread - spread
        : 0;
    equityByMs.set(p.timestampMs, state.realizedRub + spreadPnlToRub(mtmSpread, effNotional));
  }

  return equitySeriesToWindow(allPoints, windowPoints, cursorIndex, equityByMs);
}

/** PnL одной открытой сделки: 0 вне позиции и на баре выхода. */
function buildPerTradeEquitySeries(allPoints, edges, windowPoints, cursorIndex) {
  if (!allPoints.length || !windowPoints.length) return [];

  const constants = simPnlConstants();
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
        state.position = 'Flat';
        state.entrySpread = 0;
        state.entryDate = '';
        equity = 0;
      } else if (edge.signal === 'EnterLong' || edge.signal === 'EnterShort') {
        state.position = edge.positionAfter;
        state.entrySpread = edge.bar.spreadPercent ?? 0;
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
        entrySpread = edge.bar.spreadPercent ?? 0;
        delta = 0;
      } else if (edge.signal === 'ExitLong' || edge.signal === 'ExitShort') {
        const isLong = edge.signal === 'ExitLong';
        closedDelta = isLong ? spread - entrySpread : entrySpread - spread;
        position = 'Flat';
        entrySpread = 0;
      }
    }

    if (closedDelta != null) {
      delta = closedDelta;
    } else if (position === 'Long') {
      delta = spread - entrySpread;
    } else if (position === 'Short') {
      delta = entrySpread - spread;
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
    windowWidth: typeof opts.windowWidth === 'number' ? opts.windowWidth : 1,
    maxVisibleBars: typeof opts.maxVisibleBars === 'number' ? opts.maxVisibleBars : 200,
    playing: !!playing,
  };
}
