/** BarReplayEngine + chart candles/markers/overlay — split from replay-engine.js */
class BarReplayEngine {
  constructor(points, entry, exit, startIndex = Z_SCORE_ROLLING_MIN_BARS, opts = {}) {
    this.points = points;
    this.entry = entry;
    this.exit = exit;
    /** Tip-1m chart: cursor/seek only — signals come from server sim. */
    this.disableSignals = !!opts.disableSignals;
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
    if (this.disableSignals) {
      this.cursor = next;
      return this._frame(null);
    }
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
    if (target === this.cursor) {
      if (this.state === 'Playing' && this.cursor >= this.lastIndex) this.state = 'Idle';
      return this._frame(null);
    }
    if (this.disableSignals) {
      this.cursor = target;
      if (this.state === 'Playing' && this.cursor >= this.lastIndex) this.state = 'Idle';
      return this._frame(null);
    }
    // Вперёд: дописать бары от текущего курсора (O(Δ)), не с нуля.
    // Назад / сброс: полный rebuildStateToCursor (O(n)).
    if (target > this.cursor) {
      for (let i = this.cursor + 1; i <= target; i++) {
        this._processBarAt(i);
      }
      this.cursor = target;
    } else {
      this.rebuildStateToCursor(target);
      this.cursor = target;
    }
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
      // как Прод: decision_bars.signal=NONE глушит фантомный geometric cross
      const liveSig = String(cur.liveSignal || '').toUpperCase();
      if (
        liveSig === 'NONE'
        && (signal === 'EnterLong' || signal === 'EnterShort')
      ) {
        signal = 'None';
      }
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
    if (this.disableSignals) return;

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
        currentPoint: null,
        position: 'Flat',
        newSignalThisBar: null,
        barLabel: '',
        // read-only refs — не копировать O(n) на каждый кадр
        signalEdgesSoFar: this.edges,
      };
    }
    const idx = Math.min(this.lastIndex, Math.max(0, this.cursor));
    return {
      cursorIndex: idx,
      currentPoint: this.points[idx] || null,
      position: this.position,
      newSignalThisBar: newSignal,
      barLabel: this.points[idx].tradeDate,
      signalEdgesSoFar: this.edges,
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
function visibleBarsOnScreen(visibleDays, { tip1m = false } = {}) {
  // «Всё» — уместить весь загруженный период (zoom-out без потолка)
  if (!visibleDays || visibleDays >= 400) return 1_000_000;
  if (tip1m) {
    // 1м плотнее M15 (~15×): держим ~1–1.5 торговых дня на экране при периоде 30д
    const map1m = { 30: 900, 90: 1400, 180: 2000 };
    if (map1m[visibleDays]) return map1m[visibleDays];
    return Math.min(2500, Math.max(600, Math.round(visibleDays * 30)));
  }
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
