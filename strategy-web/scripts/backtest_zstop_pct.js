/**
 * Z-stop % от |Z входа|: 0…500% по сетке порогов entry/exit.
 * Usage: node scripts/backtest_zstop_pct.js [csv]
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.join(__dirname, '..');
const code = fs.readFileSync(path.join(root, 'replay/static/replay-engine.js'), 'utf8') + `
this.BarReplayEngine = BarReplayEngine;
this.Z_SCORE_ROLLING_MIN_BARS = Z_SCORE_ROLLING_MIN_BARS;
this.buildTradeRows = buildTradeRows;
this.buildTradeSimSummary = buildTradeSimSummary;
this.setSimNotionalRub = setSimNotionalRub;
this.setSimCompound = setSimCompound;
`;

const ctx = {
  console, Math, Number, Array, Object, String, Boolean, Date,
  Infinity, NaN, parseFloat, parseInt, JSON,
  isFinite: Number.isFinite, isNaN: Number.isNaN,
};
vm.createContext(ctx);
vm.runInContext(code, ctx);

vm.runInContext(`
const _n = (v) => {
  const x = Number(v);
  return Number.isFinite(x) && x > 0 ? x : 0;
};
normalizeSimExitOpts = function(opts = {}) {
  return {
    takeProfitPct: _n(opts.takeProfitPct),
    forcedTimeStopHours: _n(opts.forcedTimeStopHours),
    forcedZStopDeviation: _n(opts.forcedZStopDeviation),
    forcedZStopPctOfEntryZ: _n(opts.forcedZStopPctOfEntryZ),
    maxLossRub: _n(opts.maxLossRub),
    forcedHoldHoursIfLosing: _n(opts.forcedHoldHoursIfLosing),
    forcedHoldRequireNeverGreen: !!opts.forcedHoldRequireNeverGreen,
  };
};
resolveProtectiveExit = function(ctx) {
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
  if (stopLossRubHit(bar, position, entrySpread, entryDate, constants, opts.maxLossRub)) {
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
    let dev = opts.forcedZStopDeviation;
    if (opts.forcedZStopPctOfEntryZ > 0) {
      dev = (opts.forcedZStopPctOfEntryZ / 100) * Math.abs(entryZ);
    }
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
};
`, ctx);

const csvName = process.argv[2] || 'm15_tatn_255d.csv';
const csv = fs.readFileSync(path.join(root, 'data', csvName), 'utf8').trim().split(/\r?\n/);
const points = [];
for (let i = 1; i < csv.length; i++) {
  const [ts, z, sp] = csv[i].split(',');
  const tradeDate = ts.replace('T', ' ').slice(0, 16);
  points.push({
    tradeDate,
    timestampMs: new Date(`${tradeDate.replace(' ', 'T')}:00+03:00`).getTime(),
    zScore: parseFloat(z),
    spreadPercent: parseFloat(sp),
  });
}

ctx.setSimNotionalRub(10000);
ctx.setSimCompound(true);

/** Типичные пары entry/exit (exit < entry). */
const THRESHOLDS = [
  { entry: 0.5, exit: 0.3 },
  { entry: 0.7, exit: 0.5 },
  { entry: 0.8, exit: 0.5 },
  { entry: 0.8, exit: 0.7 },
  { entry: 1.0, exit: 0.5 },
  { entry: 1.0, exit: 0.7 },
  { entry: 1.2, exit: 0.7 },
  { entry: 1.3, exit: 0.7 },
  { entry: 1.5, exit: 0.7 },
  { entry: 1.5, exit: 1.0 },
  { entry: 1.7, exit: 1.0 },
  { entry: 1.7, exit: 1.3 },
  { entry: 2.0, exit: 1.0 },
  { entry: 2.0, exit: 1.3 },
  { entry: 2.0, exit: 1.5 },
];

const TP = 2;
const PCT_STEP = 10; // 0, 10, …, 500

function runSim(entry, exit, zOpts) {
  const eng = new ctx.BarReplayEngine(points, entry, exit, ctx.Z_SCORE_ROLLING_MIN_BARS, {
    takeProfitPct: TP,
    ...zOpts,
  });
  eng.seekToEnd();
  const rows = ctx.buildTradeRows(eng.edges, entry, points, eng.cursor);
  const closed = rows.filter((r) => r.status === 'Закрыта');
  const s = ctx.buildTradeSimSummary(closed, 10000);
  return {
    closed: closed.length,
    totalPnl: s.totalPnl,
    maxDd: s.maxDd,
    winRate: s.winRate,
  };
}

function fmt(n) {
  return `${n >= 0 ? '+' : ''}${Math.round(n)}`;
}

function fmtK(n) {
  const k = n / 1000;
  return `${k >= 0 ? '+' : ''}${k.toFixed(1)}k`;
}

console.log(`${csvName} · ${points.length} баров · TP ${TP}% · капит. 10k`);
console.log(`Z-stop: 0…500% от |Z_входа|, шаг ${PCT_STEP}% · якорь Z±0.5 фикс\n`);

const summaryRows = [];

for (const th of THRESHOLDS) {
  const label = `${th.entry}/${th.exit}`;
  const baseline = runSim(th.entry, th.exit, {});
  const fixed05 = runSim(th.entry, th.exit, { forcedZStopDeviation: 0.5 });

  const sweep = [];
  for (let pct = 0; pct <= 500; pct += PCT_STEP) {
    if (pct === 0) {
      sweep.push({ pct: 0, ...baseline });
      continue;
    }
    sweep.push({ pct, ...runSim(th.entry, th.exit, { forcedZStopPctOfEntryZ: pct }) });
  }

  const bestPnl = sweep.reduce((a, b) => (b.totalPnl > a.totalPnl ? b : a));
  const bestDd = sweep.reduce((a, b) => (b.maxDd < a.maxDd ? b : a)); // меньший maxDd = лучше
  // среди вариантов с PnL ≥ Z±0.5 — лучший DD
  const beat05 = sweep.filter((r) => r.totalPnl >= fixed05.totalPnl - 1);
  const bestDdAmongBeat = beat05.length
    ? beat05.reduce((a, b) => (b.maxDd < a.maxDd ? b : a))
    : null;

  const top = [...sweep].sort((a, b) => b.totalPnl - a.totalPnl).slice(0, 5);

  console.log(`${'='.repeat(72)}`);
  console.log(`пороги ±${label}`);
  console.log(`  без Z:   PnL ${fmtK(baseline.totalPnl)}  DD ${fmtK(-baseline.maxDd)}  N=${baseline.closed}`);
  console.log(`  Z±0.5:   PnL ${fmtK(fixed05.totalPnl)}  DD ${fmtK(-fixed05.maxDd)}  ΔPnL ${fmt(fixed05.totalPnl - baseline.totalPnl)}`);
  console.log(`  лучший PnL: ${bestPnl.pct}%|Z|  PnL ${fmtK(bestPnl.totalPnl)}  DD ${fmtK(-bestPnl.maxDd)}  Δvs±0.5 ${fmt(bestPnl.totalPnl - fixed05.totalPnl)}`);
  console.log(`  лучший DD:  ${bestDd.pct}%|Z|  PnL ${fmtK(bestDd.totalPnl)}  DD ${fmtK(-bestDd.maxDd)}`);
  if (bestDdAmongBeat) {
    console.log(`  лучший DD при PnL≥±0.5: ${bestDdAmongBeat.pct}%|Z|  PnL ${fmtK(bestDdAmongBeat.totalPnl)}  DD ${fmtK(-bestDdAmongBeat.maxDd)}`);
  }
  console.log('  топ-5 PnL:');
  for (const r of top) {
    console.log(
      `    ${String(r.pct).padStart(3)}%  PnL ${fmtK(r.totalPnl).padStart(8)}  DD ${fmtK(-r.maxDd).padStart(8)}  Δ±0.5 ${fmt(r.totalPnl - fixed05.totalPnl).padStart(7)}`,
    );
  }

  summaryRows.push({
    label,
    basePnl: baseline.totalPnl,
    baseDd: baseline.maxDd,
    z05Pnl: fixed05.totalPnl,
    z05Dd: fixed05.maxDd,
    bestPct: bestPnl.pct,
    bestPnl: bestPnl.totalPnl,
    bestDd: bestPnl.maxDd,
    deltaVs05: bestPnl.totalPnl - fixed05.totalPnl,
    ddDeltaVs05: bestPnl.maxDd - fixed05.maxDd, // >0 = хуже DD
  });
}

console.log(`\n${'='.repeat(72)}`);
console.log('СВОДКА: лучший %|Z| vs Z±0.5 по порогам');
console.log('пороги      best%   PnL%      PnL±0.5   ΔPnL     DD%       DD±0.5   ΔDD');
for (const r of summaryRows) {
  const ddMark = r.ddDeltaVs05 <= 0 ? 'лучше/=' : 'хуже';
  console.log(
    `${r.label.padEnd(10)} ${String(r.bestPct).padStart(4)}%  ${fmtK(r.bestPnl).padStart(8)}  ${fmtK(r.z05Pnl).padStart(8)}  ${fmt(r.deltaVs05).padStart(7)}  ${fmtK(-r.bestDd).padStart(8)}  ${fmtK(-r.z05Dd).padStart(8)}  ${fmt(-(r.ddDeltaVs05)).padStart(6)} ${ddMark}`,
  );
}

const wins = summaryRows.filter((r) => r.deltaVs05 > 0).length;
const ddBetter = summaryRows.filter((r) => r.ddDeltaVs05 < 0).length;
const ddWorse = summaryRows.filter((r) => r.ddDeltaVs05 > 0).length;
const avgBestPct = summaryRows.reduce((s, r) => s + r.bestPct, 0) / summaryRows.length;
console.log(`\nПо PnL: %|Z| лучше ±0.5 на ${wins}/${summaryRows.length} порогах`);
console.log(`По DD (при лучшем PnL %): лучше ${ddBetter}, хуже ${ddWorse}, равно ${summaryRows.length - ddBetter - ddWorse}`);
console.log(`Средний оптимальный %|Z|: ${avgBestPct.toFixed(0)}%`);
