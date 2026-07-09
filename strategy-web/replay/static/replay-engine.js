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

function buildMarkers(edges, allPoints) {
  const markers = [];
  let tradeNo = 0;
  for (const edge of edges) {
    const idx = allPoints.findIndex((p) => p.timestampMs === edge.bar.timestampMs);
    if (idx < 0) continue;
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

function buildTradeRows(edges) {
  const rows = [];
  let tradeNo = 0;
  let openEntry = null;
  for (const edge of edges) {
    if (edge.signal === 'EnterLong' || edge.signal === 'EnterShort') {
      tradeNo++;
      openEntry = edge;
    } else if (edge.signal === 'ExitLong' || edge.signal === 'ExitShort') {
      if (!openEntry) continue;
      const side = openEntry.signal === 'EnterLong' ? 'Long' : 'Short';
      rows.push({
        id: String(tradeNo),
        side,
        entryTime: openEntry.bar.tradeDate,
        exitTime: edge.bar.tradeDate,
        entryZ: openEntry.bar.zScore,
        exitZ: edge.bar.zScore,
        status: 'Закрыта',
      });
      openEntry = null;
    }
  }
  if (openEntry) {
    const side = openEntry.signal === 'EnterLong' ? 'Long' : 'Short';
    rows.push({
      id: String(tradeNo),
      side,
      entryTime: openEntry.bar.tradeDate,
      exitTime: '—',
      entryZ: openEntry.bar.zScore,
      exitZ: null,
      status: 'Открыта',
    });
  }
  return rows;
}

function buildChartPayload(candles, entry, exit, markers, trades, playing) {
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
    windowWidth: 1,
    playing: !!playing,
  };
}
