#!/usr/bin/env node
/**
 * Полный перебор entry/exit/TP/DD при всегда включённой капитализации.
 * Быстрый путь (typed series + Z/TP/DD) — без BarReplayEngine на сетке.
 *
 * Данные: тот же путь, что Testing heatmap (/api/bars → SQLite + lookback CSV),
 * не «сырой» CSV (у файла другие Z / длина → другие N и PnL).
 *
 * Usage: node scripts/sweep_compound_opt.js [csv]
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const { spawnSync } = require('child_process');

const root = path.join(__dirname, '..');
const csvName = process.argv[2] || 'm15_tatn_1095d.csv';
const NOTIONAL = 10000;

function loadCtx() {
  const files = [
    'replay/static/replay-sim.js',
    'replay/static/replay-equity.js',
    'replay/static/replay-signals.js',
    'replay/static/replay-engine-core.js',
  ];
  const code = files.map((f) => fs.readFileSync(path.join(root, f), 'utf8')).join('\n;\n') + `
this.setSimNotionalRub = setSimNotionalRub;
this.setSimCompound = setSimCompound;
this.setSimSlippageSpreadPts = setSimSlippageSpreadPts;
this.getSimSlippageSpreadPts = getSimSlippageSpreadPts;
this.createSimSizingState = createSimSizingState;
this.prepareHeatmapSeries = prepareHeatmapSeries;
this.normalizeSimExitOpts = normalizeSimExitOpts;
`;
  const ctx = {
    console, Math, Number, Array, Object, String, Boolean, Date,
    Infinity, NaN, parseFloat, parseInt, JSON,
    isFinite: Number.isFinite, isNaN: Number.isNaN,
    Float64Array, Int32Array, Uint8Array, Map, Set,
    performance: { now: () => Date.now() },
  };
  vm.createContext(ctx);
  vm.runInContext(code, ctx);
  return ctx;
}

/** Fallback: прямой разбор CSV (Z из файла — может расходиться с UI). */
function loadPointsFromCsv(csvPath) {
  const lines = fs.readFileSync(csvPath, 'utf8').trim().split(/\r?\n/);
  const points = [];
  for (let i = 1; i < lines.length; i++) {
    const [ts, z, sp] = lines[i].split(',');
    const tradeDate = String(ts).replace('T', ' ').slice(0, 16);
    points.push({
      tradeDate,
      timestampMs: new Date(`${tradeDate.replace(' ', 'T')}:00+03:00`).getTime(),
      zScore: parseFloat(z),
      spreadPercent: parseFloat(sp),
    });
  }
  return { points, source: 'csv_file', note: 'raw CSV — Z/длина могут ≠ Testing UI' };
}

/**
 * Бары как у Testing heatmap: replay SQLite + filter lookback выбранного CSV
 * (см. replay_db._load_cached_bars / GET /api/bars).
 */
function loadPointsUiParity(csv) {
  const py = `
import json, sys
from pathlib import Path
root = Path(r${JSON.stringify(root)})
sys.path.insert(0, str(root))
from replay.replay_db import _load_cached_bars
csv_name = sys.argv[1]
csv_path = root / "data" / csv_name
bars = _load_cached_bars(csv_path, csv_name, None)
if not bars:
    raise SystemExit("no bars from _load_cached_bars")
out = [{
    "tradeDate": b["tradeDate"],
    "timestampMs": int(b["timestampMs"]),
    "zScore": float(b["zScore"]),
    "spreadPercent": float(b["spreadPercent"]),
} for b in bars]
json.dump({"points": out, "source": "ui_sqlite_lookback", "csv": csv_name}, sys.stdout, separators=(",", ":"))
`;
  const r = spawnSync('python', ['-c', py, csv], {
    encoding: 'utf8',
    maxBuffer: 256 * 1024 * 1024,
    cwd: root,
  });
  if (r.status !== 0) {
    const err = (r.stderr || r.stdout || '').trim();
    throw new Error(`UI bars load failed: ${err || `exit ${r.status}`}`);
  }
  const parsed = JSON.parse(r.stdout);
  return {
    points: parsed.points,
    source: parsed.source || 'ui_sqlite_lookback',
    note: 'parity Testing /api/bars (SQLite + CSV lookback)',
  };
}

function loadPoints(csv) {
  try {
    const loaded = loadPointsUiParity(csv);
    process.stderr.write(
      `data: ${loaded.source} bars=${loaded.points.length}`
      + ` ${loaded.points[0]?.tradeDate} → ${loaded.points.at(-1)?.tradeDate}\n`,
    );
    return loaded;
  } catch (e) {
    process.stderr.write(`warn: UI data load failed (${e.message}); fallback CSV\n`);
    const csvPath = path.join(root, 'data', csv);
    return loadPointsFromCsv(csvPath);
  }
}

/**
 * Fast Z + optional TP + optional equity DD stop (maxLossPctOfNotional).
 * Parity with heatmapFastNetFromSeries + resolveProtectiveExit TP/DD order.
 * Tracks: totalPnl, closedCount, wins/winrate, peak-to-trough equity DD, calmar.
 */
function runFastDetailed(ctx, series, entry, exit, simOpts) {
  const { z, spread, session, consec, dayNum, len } = series;
  const opts = ctx.normalizeSimExitOpts(simOpts || {});
  const tpPct = opts.takeProfitPct > 0 ? opts.takeProfitPct : 0;
  const ddPct = opts.maxLossPctOfNotional > 0 ? opts.maxLossPctOfNotional : 0;
  const slip = Math.max(0, ctx.getSimSlippageSpreadPts());
  const sizing = ctx.createSimSizingState();

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
  let wins = 0;
  let peak = 0;
  let maxDd = 0;

  const onClose = (net) => {
    totalPnl += net;
    closedCount += 1;
    if (net > 0) wins += 1;
    if (totalPnl > peak) peak = totalPnl;
    const dd = peak - totalPnl;
    if (dd > maxDd) maxDd = dd;
    sizing.applyClosedNet(net);
    pos = 0;
  };

  const closeAt = (i, isLong) => {
    const sp = spread[i];
    const exitSpread = isLong ? sp - slip : sp + slip;
    const pnlPts = isLong ? exitSpread - entrySpread : entrySpread - exitSpread;
    const gross = effNotional * (pnlPts / 100);
    const ovn = overnightPerDay * Math.max(0, dayNum[i] - entryDay);
    const net = gross - (entryCommission + commPerSide) - ovn;
    onClose(net);
  };

  for (let i = 1; i < len; i++) {
    if (!session[i]) continue;

    // Protective: TP then DD on every session bar (MTM без exit-slip)
    if (pos) {
      const sp = spread[i];
      const pnlPts = pos === 1 ? (sp - entrySpread) : (entrySpread - sp);
      const gross = effNotional * (pnlPts / 100);
      const ovn = overnightPerDay * Math.max(0, dayNum[i] - entryDay);
      const netMtm = gross - entryCommission - ovn;
      if (tpPct > 0) {
        const pct = (netMtm / Math.max(1, notionalRub)) * 100;
        if (pct >= tpPct) {
          closeAt(i, pos === 1);
          continue;
        }
      }
      if (ddPct > 0) {
        const maxLossRub = Math.max(1, notionalRub) * (ddPct / 100);
        if (-netMtm >= maxLossRub) {
          closeAt(i, pos === 1);
          continue;
        }
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

  return {
    totalPnl,
    closedCount,
    wins,
    winrate: closedCount ? (100 * wins / closedCount) : 0,
    maxDd,
    calmar: maxDd > 1 ? totalPnl / maxDd : (totalPnl > 0 ? 999 : 0),
  };
}

function round1(v) {
  return Math.round(v * 10) / 10;
}

function classifyOverfit(c, train, test, byYear, slipStress) {
  const yearsPos = Object.values(byYear).filter((v) => v.pnl > 0).length;
  const yearsTot = Object.keys(byYear).length;
  const yearSum = Object.values(byYear).reduce((s, v) => s + v.pnl, 0);
  const oosRatio = train.totalPnl > 0
    ? test.totalPnl / train.totalPnl
    : (test.totalPnl > 0 ? 1 : 0);
  const compoundBoost = yearSum > 0 ? c.totalPnl / yearSum : 0;
  const ddVsNotional = (c.maxDd || 0) / NOTIONAL;

  let overfit = 'низкий';
  let reason = 'train/test оба плюс, годы в плюсе, slip 0.12 ≥ 0';

  // Rules (priority high → low):
  // высокий: OOS убыток | слабый OOS | мало прибыльных лет | equity DD ≫ депозит | compound-взрыв
  // средний: slip 0.12 ломает | умеренный OOS | низкие Z + TP
  if (test.totalPnl < 0 && train.totalPnl > 0) {
    overfit = 'высокий';
    reason = 'OOS (2-я половина) отрицательный при плюсе на train';
  } else if (oosRatio < 0.15 || (yearsTot >= 3 && yearsPos <= yearsTot - 2)) {
    overfit = 'высокий';
    reason = oosRatio < 0.15
      ? `OOS ratio ${Math.round(oosRatio * 100) / 100} < 0.15`
      : `прибыль только в ${yearsPos}/${yearsTot} годах`;
  } else if (ddVsNotional >= 15 || (compoundBoost >= 5 && ddVsNotional >= 5)) {
    overfit = 'высокий';
    reason = ddVsNotional >= 15
      ? `equity DD ${Math.round(c.maxDd)} ₽ ≈ ${Math.round(ddVsNotional)}× депозита (compound-путь)`
      : `full PnL / сумма лет ≈ ${Math.round(compoundBoost * 10) / 10}× при DD ${Math.round(ddVsNotional)}× депозита`;
  } else if (oosRatio < 0.35 || yearsPos < yearsTot - 1 || slipStress[0.12].pnl < 0 || compoundBoost >= 4) {
    overfit = 'средний';
    if (slipStress[0.12].pnl < 0) reason = 'ломается на slip 0.12';
    else if (compoundBoost >= 4) reason = `compound boost ≈ ${Math.round(compoundBoost * 10) / 10}× (полный путь ≫ сумма лет)`;
    else if (oosRatio < 0.35) reason = `OOS ratio ${Math.round(oosRatio * 100) / 100} < 0.35`;
    else reason = `не все годы в плюсе (${yearsPos}/${yearsTot})`;
  } else if (c.entry <= 0.9 && c.exit <= 0.5 && c.tp >= 2) {
    overfit = 'средний';
    reason = 'низкие пороги + TP≥2% + капитализация (типичный in-sample пик)';
  }

  return { overfit, reason, oosRatio, yearsPos, yearsTot, compoundBoost, ddVsNotional };
}

function main() {
  const t0 = Date.now();
  const ctx = loadCtx();
  const loaded = loadPoints(csvName);
  const points = loaded.points;
  const endIdx = points.length - 1;
  const mid = Math.floor(points.length / 2);

  const yearRanges = {};
  for (let i = 0; i < points.length; i++) {
    const y = String(points[i].tradeDate).slice(0, 4);
    if (!yearRanges[y]) yearRanges[y] = { lo: i, hi: i };
    yearRanges[y].hi = i;
  }

  ctx.setSimNotionalRub(NOTIONAL);
  ctx.setSimCompound(true);

  const entryMin = 0.3;
  const entryMax = 2.2;
  const exitMin = 0.1;
  const step = 0.1;
  const tps = [0, 1, 2, 3];
  const dds = [
    { id: 'off', pct: 0 },
    { id: 'dd15', pct: 15 },
    { id: 'dd20', pct: 20 },
    { id: 'dd25', pct: 25 },
  ];
  const primarySlip = 0.02;

  ctx.setSimSlippageSpreadPts(primarySlip);
  const seriesFull = ctx.prepareHeatmapSeries(points, endIdx);
  const seriesTrain = ctx.prepareHeatmapSeries(points.slice(0, mid + 1), mid);
  const seriesTestPts = points.slice(mid);
  const seriesTest = ctx.prepareHeatmapSeries(seriesTestPts, seriesTestPts.length - 1);

  const yearSeries = {};
  for (const [y, rng] of Object.entries(yearRanges)) {
    const slice = points.slice(rng.lo, rng.hi + 1);
    yearSeries[y] = ctx.prepareHeatmapSeries(slice, slice.length - 1);
  }

  const results = [];
  let n = 0;
  const tGrid0 = Date.now();
  for (let e = entryMin; e <= entryMax + 1e-9; e = round1(e + step)) {
    for (let x = exitMin; x < e - 1e-9; x = round1(x + step)) {
      for (const tp of tps) {
        for (const dd of dds) {
          n += 1;
          const metrics = runFastDetailed(ctx, seriesFull, e, x, {
            takeProfitPct: tp,
            maxLossPctOfNotional: dd.pct,
          });
          results.push({
            entry: e,
            exit: x,
            tp,
            dd: dd.id,
            ddPct: dd.pct,
            slip: primarySlip,
            compound: true,
            ...metrics,
          });
        }
      }
    }
    if ((e * 10) % 5 === 0) {
      process.stderr.write(`grid entry=${e} combos=${n} elapsed=${Date.now() - tGrid0}ms\n`);
    }
  }
  process.stderr.write(`grid done: ${n} combos in ${Date.now() - tGrid0}ms\n`);

  const rankedPnl = [...results].sort((a, b) => b.totalPnl - a.totalPnl);
  const rankedCalmar = [...results]
    .filter((r) => r.closedCount >= 40 && r.maxDd != null && r.maxDd > 0)
    .sort((a, b) => b.calmar - a.calmar);

  const topCandidates = [];
  const seen = new Set();
  function addCand(r, why) {
    const k = `${r.entry}|${r.exit}|${r.tp}|${r.dd}`;
    if (seen.has(k)) {
      const existing = topCandidates.find((c) => `${c.entry}|${c.exit}|${c.tp}|${c.dd}` === k);
      if (existing && !existing.why.includes(why)) existing.why += `+${why}`;
      return;
    }
    seen.add(k);
    topCandidates.push({ ...r, why });
  }
  rankedPnl.slice(0, 12).forEach((r) => addCand(r, 'top_pnl'));
  rankedCalmar.slice(0, 12).forEach((r) => addCand(r, 'top_calmar'));
  const uiLike = results.find((r) => r.entry === 0.9 && r.exit === 0.5 && r.tp === 3 && r.dd === 'dd25');
  if (uiLike) addCand(uiLike, 'ui_like');
  const classic = results.find((r) => r.entry === 1.6 && r.exit === 1.3 && r.tp === 0 && r.dd === 'off');
  if (classic) addCand(classic, 'classic_16_13');
  // classic with TP3 DD25 (UI-style risk on prod-like Z)
  const classicRisk = results.find((r) => r.entry === 1.6 && r.exit === 1.3 && r.tp === 3 && r.dd === 'dd25');
  if (classicRisk) addCand(classicRisk, 'classic_risk');
  const prodSaferE = results.find((r) => r.entry === 2.1 && r.exit === 1.2 && r.tp === 3 && r.dd === 'off');
  if (prodSaferE) addCand(prodSaferE, 'prod_safer_E');
  const prodSaferF = results.find((r) => r.entry === 2.2 && r.exit === 1.2 && r.tp === 2 && r.dd === 'off');
  if (prodSaferF) addCand(prodSaferF, 'prod_safer_F');

  const detailed = [];
  for (const c of topCandidates) {
    const simOpts = { takeProfitPct: c.tp, maxLossPctOfNotional: c.ddPct };
    ctx.setSimSlippageSpreadPts(primarySlip);

    const train = runFastDetailed(ctx, seriesTrain, c.entry, c.exit, simOpts);
    const test = runFastDetailed(ctx, seriesTest, c.entry, c.exit, simOpts);

    const byYear = {};
    for (const [y, ser] of Object.entries(yearSeries)) {
      const yr = runFastDetailed(ctx, ser, c.entry, c.exit, simOpts);
      byYear[y] = {
        pnl: Math.round(yr.totalPnl),
        n: yr.closedCount,
        dd: Math.round(yr.maxDd),
        wr: yr.closedCount ? Math.round(yr.winrate * 10) / 10 : 0,
      };
    }

    const slipStress = {};
    for (const s of [0.02, 0.04, 0.12]) {
      ctx.setSimSlippageSpreadPts(s);
      const m = runFastDetailed(ctx, seriesFull, c.entry, c.exit, simOpts);
      slipStress[String(s)] = {
        pnl: Math.round(m.totalPnl),
        n: m.closedCount,
        dd: Math.round(m.maxDd),
        calmar: Math.round(m.calmar * 100) / 100,
        wr: m.closedCount ? Math.round(m.winrate * 10) / 10 : 0,
      };
    }
    // restore primary for next candidate
    ctx.setSimSlippageSpreadPts(primarySlip);

    const clf = classifyOverfit(c, train, test, byYear, {
      0.12: slipStress['0.12'],
    });

    detailed.push({
      entry: c.entry,
      exit: c.exit,
      tp: c.tp,
      dd: c.dd,
      ddPct: c.ddPct,
      why: c.why,
      full: {
        pnl: Math.round(c.totalPnl),
        n: c.closedCount,
        wr: Math.round(c.winrate * 10) / 10,
        dd: Math.round(c.maxDd),
        calmar: Math.round(c.calmar * 100) / 100,
        pct: Math.round((c.totalPnl / NOTIONAL) * 10) / 10,
      },
      train: {
        pnl: Math.round(train.totalPnl),
        n: train.closedCount,
        dd: Math.round(train.maxDd),
        wr: train.closedCount ? Math.round(train.winrate * 10) / 10 : 0,
      },
      test: {
        pnl: Math.round(test.totalPnl),
        n: test.closedCount,
        dd: Math.round(test.maxDd),
        wr: test.closedCount ? Math.round(test.winrate * 10) / 10 : 0,
      },
      oosRatio: Math.round(clf.oosRatio * 100) / 100,
      compoundBoost: Math.round(clf.compoundBoost * 100) / 100,
      byYear,
      slipStress,
      overfit: clf.overfit,
      overfitReason: clf.reason,
    });
  }

  detailed.sort((a, b) => b.full.pnl - a.full.pnl);

  const out = {
    meta: {
      csv: csvName,
      bars: points.length,
      from: points[0]?.tradeDate,
      to: points[endIdx]?.tradeDate,
      dataSource: loaded.source,
      dataNote: loaded.note,
      notional: NOTIONAL,
      compound: true,
      primarySlip,
      grid: { entryMin, entryMax, exitMin, step, tps, dds: dds.map((d) => d.id) },
      combos: n,
      elapsedMs: Date.now() - t0,
      midSplit: points[mid]?.tradeDate,
      engine: 'fast_series_z_tp_dd',
      uiParity: {
        cell_E_2_1_1_2_tp3: (() => {
          const r = results.find((x) => x.entry === 2.1 && x.exit === 1.2 && x.tp === 3 && x.dd === 'off');
          return r ? { pnl: Math.round(r.totalPnl), n: r.closedCount } : null;
        })(),
        note: 'At notional 10k UI tooltip formatRub(183721)=+183.7k; screenshot +1837.2k = same cell at capital 100k',
      },
    },
    topPnl: rankedPnl.slice(0, 20).map((r) => ({
      entry: r.entry, exit: r.exit, tp: r.tp, dd: r.dd,
      pnl: Math.round(r.totalPnl), n: r.closedCount,
      wr: Math.round(r.winrate * 10) / 10,
      ddRub: Math.round(r.maxDd),
      calmar: Math.round(r.calmar * 100) / 100,
      pct: Math.round((r.totalPnl / NOTIONAL) * 10) / 10,
    })),
    topCalmar: rankedCalmar.slice(0, 20).map((r) => ({
      entry: r.entry, exit: r.exit, tp: r.tp, dd: r.dd,
      pnl: Math.round(r.totalPnl), n: r.closedCount,
      wr: Math.round(r.winrate * 10) / 10,
      ddRub: Math.round(r.maxDd),
      calmar: Math.round(r.calmar * 100) / 100,
    })),
    variants: detailed,
  };

  const outPath = path.join(root, 'data', 'sweep_compound_opt.json');
  fs.writeFileSync(outPath, JSON.stringify(out, null, 2));
  console.log(JSON.stringify({
    ok: true,
    outPath,
    combos: n,
    elapsedMs: out.meta.elapsedMs,
    best: out.topPnl[0],
    bestCalmar: out.topCalmar[0],
    variants: detailed.length,
  }, null, 2));
}

main();
