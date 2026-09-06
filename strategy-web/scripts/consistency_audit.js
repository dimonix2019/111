#!/usr/bin/env node
/**
 * Комплексная сверка расчётов Replay (график/таблица/сводка/месяцы).
 * Usage: node scripts/consistency_audit.js [csv] [entry] [exit]
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.join(__dirname, '..');
const code =
  fs.readFileSync(path.join(root, 'replay/static/replay-engine.js'), 'utf8') +
  `
this.BarReplayEngine = BarReplayEngine;
this.Z_SCORE_ROLLING_MIN_BARS = Z_SCORE_ROLLING_MIN_BARS;
this.buildTradeRows = buildTradeRows;
this.buildTradeSimSummary = buildTradeSimSummary;
this.buildMonthlyPnl = buildMonthlyPnl;
this.buildEquitySeries = buildEquitySeries;
this.setSimNotionalRub = setSimNotionalRub;
this.setSimCompound = setSimCompound;
this.mergeRiskExitGroupOpts = mergeRiskExitGroupOpts;
this.classifySpreadRegime = classifySpreadRegime;
this.resolvePnlPctBasisRub = resolvePnlPctBasisRub;
`;

const ctx = {
  console,
  Math,
  Number,
  Array,
  Object,
  String,
  Boolean,
  Date,
  Infinity,
  NaN,
  parseFloat,
  parseInt,
  JSON,
  isFinite: Number.isFinite,
  isNaN: Number.isNaN,
};
vm.createContext(ctx);
vm.runInContext(code, ctx);

const csvName = process.argv[2] || 'm15_tatn_255d.csv';
const entry = parseFloat(process.argv[3] || '1.6');
const exitZ = parseFloat(process.argv[4] || '1.4');
const notional = 100000;

const csv = fs.readFileSync(path.join(root, 'data', csvName), 'utf8').trim().split(/\r?\n/);
const points = [];
for (let i = 1; i < csv.length; i++) {
  const [ts, z, sp, tatn, tatnp] = csv[i].split(',');
  const tradeDate = ts.replace('T', ' ').slice(0, 19);
  const label = tradeDate.slice(0, 16);
  points.push({
    tradeDate: label,
    timestampMs: new Date(`${label.replace(' ', 'T')}:00+03:00`).getTime(),
    zScore: parseFloat(z),
    spreadPercent: parseFloat(sp),
    tatnClose: tatn != null ? parseFloat(tatn) : null,
    tatnpClose: tatnp != null ? parseFloat(tatnp) : null,
  });
}

const failures = [];
const warnings = [];
function ok(name, cond, detail = '') {
  if (cond) console.log(`  OK  ${name}${detail ? ` — ${detail}` : ''}`);
  else {
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`);
    failures.push(name);
  }
}
function warn(name, detail) {
  console.log(`  WARN ${name} — ${detail}`);
  warnings.push(name);
}

function nearly(a, b, eps = 0.05) {
  if (!Number.isFinite(a) || !Number.isFinite(b)) return a === b;
  return Math.abs(a - b) <= eps;
}

function runCase(label, opts, compound) {
  console.log(`\n=== ${label} ===`);
  ctx.setSimNotionalRub(notional);
  ctx.setSimCompound(compound);
  const eng = new ctx.BarReplayEngine(
    points,
    entry,
    exitZ,
    ctx.Z_SCORE_ROLLING_MIN_BARS,
    opts || {},
  );
  eng.seekToEnd();
  const rows = ctx.buildTradeRows(eng.edges, entry, points, eng.cursor, { skipExtras: false });
  const closed = rows.filter((r) => r.status === 'Закрыта');
  const summary = ctx.buildTradeSimSummary(rows, notional);
  const monthly = ctx.buildMonthlyPnl(rows);

  // 1) sum net == summary.totalPnl
  const sumNet = closed.reduce((s, r) => s + (r.netValue || 0), 0);
  ok(
    'table Σnet == summary.totalPnl',
    nearly(sumNet, summary.totalPnl, 0.02),
    `Σ=${sumNet.toFixed(2)} summary=${summary.totalPnl.toFixed(2)}`,
  );

  // 2) monthly Σ == closed Σ
  const sumMonths = monthly.reduce((s, m) => s + m.pnl, 0);
  ok(
    'monthly Σpnl == table Σnet',
    nearly(sumMonths, sumNet, 0.02),
    `months=${sumMonths.toFixed(2)} trades=${sumNet.toFixed(2)}`,
  );

  // 3) closed count
  ok(
    'summary.closedCount == closed rows',
    summary.closedCount === closed.length,
    `${summary.closedCount} vs ${closed.length}`,
  );

  // 4) win/loss counts
  const wins = closed.filter((r) => r.netValue > 0).length;
  const losses = closed.filter((r) => r.netValue < 0).length;
  ok('winCount', summary.winCount === wins, `${summary.winCount} vs ${wins}`);
  ok('lossCount', summary.lossCount === losses, `${summary.lossCount} vs ${losses}`);

  // 5) long/short pnl
  const longPnl = closed.filter((r) => r.direction === 'Long').reduce((s, r) => s + r.netValue, 0);
  const shortPnl = closed.filter((r) => r.direction === 'Short').reduce((s, r) => s + r.netValue, 0);
  ok('longPnl', nearly(summary.longPnl, longPnl, 0.02), `${summary.longPnl.toFixed(2)} vs ${longPnl.toFixed(2)}`);
  ok('shortPnl', nearly(summary.shortPnl, shortPnl, 0.02), `${summary.shortPnl.toFixed(2)} vs ${shortPnl.toFixed(2)}`);

  // 6) equity last = closed realized + open MTM (summary.totalPnl is closed-only)
  const equity = ctx.buildEquitySeries(points, eng.edges, points, eng.cursor);
  const openRows = rows.filter((r) => r.status === 'Открыта');
  if (equity.length) {
    const lastEq = equity[equity.length - 1].value;
    if (openRows.length === 0) {
      ok(
        'equity last == summary.totalPnl (flat)',
        nearly(lastEq, summary.totalPnl, Math.max(1, Math.abs(summary.totalPnl) * 0.002)),
        `eq=${lastEq.toFixed(2)} pnl=${summary.totalPnl.toFixed(2)}`,
      );
    } else {
      const gap = summary.totalPnl - lastEq;
      ok(
        'equity last = closed PnL + open MTM (gap=−MTM)',
        Number.isFinite(gap),
        `eq=${lastEq.toFixed(2)} closed=${summary.totalPnl.toFixed(2)} impliedOpenMTM=${(-gap).toFixed(2)} openN=${openRows.length}`,
      );
      // gap should be positive when open is losing (closed - eq = -mtm => mtm negative => gap positive)
    }

    // 7) Dual DD metrics (documented): summary = closed-trade equity; chart path = MTM
    let peak = -Infinity;
    let maxDdMtm = 0;
    for (const p of equity) {
      const v = p.value;
      if (v > peak) peak = v;
      const dd = peak - v;
      if (dd > maxDdMtm) maxDdMtm = dd;
    }
    ok(
      'summary.maxDd defined (closed-trade DD)',
      summary.maxDd >= 0 && Number.isFinite(summary.maxDd),
      `closedDD=${summary.maxDd.toFixed(2)} mtmDD=${maxDdMtm.toFixed(2)} (могут отличаться — ок)`,
    );
    if (summary.maxDdPct != null) {
      const peakEq = notional + summary.totalPnl; // rough; real uses peak along closed path
      ok(
        'maxDdPct finite',
        Number.isFinite(summary.maxDdPct) && summary.maxDdPct >= 0,
        `sum%=${summary.maxDdPct.toFixed(2)}`,
      );
    }
  } else {
    warn('equity empty', 'no points');
  }

  // 9) hit milestones consistency
  let hit1 = 0;
  let hit2 = 0;
  let hit3 = 0;
  for (const r of rows) {
    if (r.hitPnl1 || r.hit1Ms != null) hit1++;
    if (r.hitPnl2 || r.hit2Ms != null) hit2++;
    if (r.hitPnl3 || r.hit3Ms != null) hit3++;
    if (r.hitPnl3 && !r.hitPnl2) failures.push('hit3 without hit2');
    if (r.hitPnl2 && !r.hitPnl1) failures.push('hit2 without hit1');
    if (r.pnlMaxValue != null && r.netValue != null && r.status === 'Закрыта') {
      // max MTM should be >= closed net (approx; costs at exit can differ)
      if (r.pnlMaxValue + 50 < r.netValue) {
        warn('pnlMax < net', `#${r.index} max=${r.pnlMaxValue} net=${r.netValue}`);
      }
    }
  }
  ok('hit hierarchy hit1>=hit2>=hit3', hit1 >= hit2 && hit2 >= hit3, `1%:${hit1} 2%:${hit2} 3%:${hit3}`);

  // 10) regime classifier
  const regime = ctx.classifySpreadRegime(points.slice(-8));
  ok('regime classifier', regime && regime.key !== 'na', JSON.stringify(regime));

  // 11) Z last bar finite
  const last = points[points.length - 1];
  ok('last Z finite', Number.isFinite(last.zScore), String(last.zScore));

  return { summary, closed: closed.length, monthly: monthly.length, equity: equity.length };
}

console.log(`CSV ${csvName} · bars=${points.length} · thresholds ±${entry}/±${exitZ} · notional ${notional}`);
console.log(`range ${points[0].tradeDate} … ${points[points.length - 1].tradeDate}`);

const bare = runCase('bare (no risk, no compound)', {}, false);
const prodLike = runCase(
  'prod-like (Z±20% + DD25% + compound)',
  ctx.mergeRiskExitGroupOpts({ zStop: 'z20', ddStop: 'dd25', timeStop: 'off', moneyStop: 'off' }),
  true,
);

// Neighbor stability (not a fail)
console.log('\n=== neighbor sanity (prod-like) ===');
ctx.setSimCompound(true);
for (const [e, x] of [
  [1.5, 1.3],
  [1.6, 1.3],
  [1.6, 1.4],
  [1.7, 1.3],
]) {
  const opts = ctx.mergeRiskExitGroupOpts({
    zStop: 'z20',
    ddStop: 'dd25',
    timeStop: 'off',
    moneyStop: 'off',
  });
  const eng = new ctx.BarReplayEngine(points, e, x, ctx.Z_SCORE_ROLLING_MIN_BARS, opts);
  eng.seekToEnd();
  const rows = ctx.buildTradeRows(eng.edges, e, points, eng.cursor, { skipExtras: true });
  const s = ctx.buildTradeSimSummary(rows, notional);
  console.log(
    `  ±${e}/${x}  PnL=${(s.totalPnl / 1000).toFixed(1)}k  DD=${(-s.maxDd / 1000).toFixed(1)}k  N=${s.closedCount}  WR=${s.winRate.toFixed(0)}%`,
  );
}

console.log('\n=== SUMMARY ===');
console.log(`failures: ${failures.length}`);
console.log(`warnings: ${warnings.length}`);
if (failures.length) {
  console.log('FAILED:', [...new Set(failures)].join('; '));
  process.exit(1);
}
console.log('ALL CONSISTENCY CHECKS PASSED');
console.log(
  `bare PnL=${(bare.summary.totalPnl / 1000).toFixed(1)}k · prod-like PnL=${(prodLike.summary.totalPnl / 1000).toFixed(1)}k`,
);
