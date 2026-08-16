/** Equity / delta / chart payload — split from replay-engine.js */
/** Кривая equity (realized + MTM) — parity MoexZStrategySim.equityRubAt. */
function simPnlConstants(notionalRub = getSimNotionalRub()) {
  const effNotional = notionalRub * SIM_LEVERAGE;
  const commPerSide = effNotional * (SIM_COMMISSION_PCT_PER_SIDE / 100);
  // Как live.overnight_fee: без цен ног ≈ номинал пары / 2.
  const overnightPerDay = simOvernightPerDayRub(effNotional / 2);
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

/** Подпись уровня как на Trade desk (ru-RU, 1 знак). */
function formatSpreadLevelTitle(prefix, value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return prefix;
  return `${prefix} ${n.toLocaleString('ru-RU', { maximumFractionDigits: 1 })}`;
}

/**
 * Горизонтали + зоны спреда — как trade.js setSpreadThresholdLines / bands.
 * @param {{enter_wide?:number,exit_wide?:number,enter_narrow?:number,exit_narrow?:number}} levels
 */
function buildSpreadChartOverlays(levels) {
  const num = (v, fb) => {
    const n = Number(v);
    return Number.isFinite(n) ? n : fb;
  };
  const enterW = num(levels?.enter_wide, 6.2);
  const exitW = num(levels?.exit_wide, 5.8);
  const enterN = num(levels?.enter_narrow, 3.2);
  const exitN = num(levels?.exit_narrow, 4.0);
  const hlines = [
    { value: enterW, color: '#2962ff', title: formatSpreadLevelTitle('S вх', enterW) },
    { value: exitW, color: '#26a69a', title: formatSpreadLevelTitle('S вых', exitW) },
    { value: exitN, color: '#26a69a', title: formatSpreadLevelTitle('L вых', exitN) },
    { value: enterN, color: '#2962ff', title: formatSpreadLevelTitle('L вх', enterN) },
  ];
  return {
    hlines,
    spreadLevels: {
      enter_wide: enterW,
      exit_wide: exitW,
      enter_narrow: enterN,
      exit_narrow: exitN,
    },
  };
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
  const primaryMetric = opts.primaryMetric === 'spread' ? 'spread' : 'z';
  let hlines;
  let spreadLevels = null;
  if (primaryMetric === 'spread') {
    const ov = buildSpreadChartOverlays(opts.spreadLevels || {});
    hlines = ov.hlines;
    spreadLevels = ov.spreadLevels;
  } else {
    hlines = [
      { value: entry, color: '#EF5350', title: '+Entry' },
      { value: -entry, color: '#66BB6A', title: '−Entry' },
      { value: exit, color: '#FFB74D', title: '+Exit' },
      { value: -exit, color: '#4DD0E1', title: '−Exit' },
      { value: 0, color: '#616161', title: '0' },
    ];
  }
  return {
    candles: candleArr,
    hlines,
    primaryMetric,
    spreadLevels,
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
