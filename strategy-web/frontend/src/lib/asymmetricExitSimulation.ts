import type { SimPack, Trade } from '@/types'
import { parseTs, tradeHoldHours } from '@/lib/tradeDistribution'
import {
  barAtOffset,
  hypoPnlAtBar,
  simulateHoldCap,
  wallTimeToUnix,
  type HoldCapScenario,
} from '@/lib/holdCapSimulation'

type ZBar = SimPack['zscore'][number]

export type ExitScenarioRow = {
  no: number
  actualPnl: number
  scenarioPnl: number
  forced: boolean
}

export type ExitScenarioResult = {
  id: string
  label: string
  decisionHours: number
  baselinePnl: number
  scenarioPnl: number
  deltaPnl: number
  deltaPct: number
  cappedCount: number
  baselineMaxDrawdownPct: number
  scenarioMaxDrawdownPct: number
  rows: ExitScenarioRow[]
}

const DECISION_HOURS = 12

function percentile(values: number[], p: number): number {
  if (!values.length) return 0
  const sorted = [...values].sort((a, b) => a - b)
  const idx = (p / 100) * (sorted.length - 1)
  const lo = Math.floor(idx)
  const hi = Math.ceil(idx)
  if (lo === hi) return sorted[lo]!
  const w = idx - lo
  return sorted[lo]! * (1 - w) + sorted[hi]! * w
}

/** Max DD % по кумулятивной кривой PnL (сделки в порядке exit_time). */
export function maxDrawdownPct(orderedPnls: number[]): number {
  let equity = 0
  let peak = 0
  let maxDdPct = 0
  for (const pnl of orderedPnls) {
    equity += pnl
    if (equity > peak) peak = equity
    if (peak > 0) {
      const ddPct = (100 * (peak - equity)) / peak
      if (ddPct > maxDdPct) maxDdPct = ddPct
    }
  }
  return maxDdPct
}

function tradesByExitTime(trades: Trade[]): Trade[] {
  return [...trades].sort((a, b) => {
    const ta = parseTs(a.exit_time)?.getTime() ?? 0
    const tb = parseTs(b.exit_time)?.getTime() ?? 0
    return ta - tb || a.no - b.no
  })
}

function finalizeScenario(
  id: string,
  label: string,
  decisionHours: number,
  rows: ExitScenarioRow[],
  orderedTrades: Trade[],
): ExitScenarioResult {
  const baselinePnl = rows.reduce((s, r) => s + r.actualPnl, 0)
  const scenarioPnl = rows.reduce((s, r) => s + r.scenarioPnl, 0)
  const deltaPnl = scenarioPnl - baselinePnl
  const deltaPct = baselinePnl !== 0 ? (100 * deltaPnl) / Math.abs(baselinePnl) : 0
  const cappedCount = rows.filter((r) => r.forced).length

  const tradeOrder = new Map(orderedTrades.map((t, i) => [t.no, i]))
  const sortedRows = [...rows].sort((a, b) => (tradeOrder.get(a.no) ?? 0) - (tradeOrder.get(b.no) ?? 0))

  return {
    id,
    label,
    decisionHours,
    baselinePnl,
    scenarioPnl,
    deltaPnl,
    deltaPct,
    cappedCount,
    baselineMaxDrawdownPct: maxDrawdownPct(sortedRows.map((r) => r.actualPnl)),
    scenarioMaxDrawdownPct: maxDrawdownPct(sortedRows.map((r) => r.scenarioPnl)),
    rows,
  }
}

function simulateAtDecision(
  trades: Trade[],
  bars: ZBar[],
  decisionHours: number,
  shouldForce: (trade: Trade, bar: ZBar, holdHours: number, unrealized: number) => boolean,
): ExitScenarioRow[] {
  const rows: ExitScenarioRow[] = []
  for (const trade of trades) {
    const holdHours = tradeHoldHours(trade)
    const actual = trade.pnl_rub
    let scenario = actual
    let forced = false

    if (holdHours > decisionHours + 1e-6 && bars.length > 0) {
      const entryUnix = wallTimeToUnix(trade.entry_time)
      if (entryUnix != null) {
        const bar = barAtOffset(bars, entryUnix, decisionHours)
        if (bar) {
          const unrealized = hypoPnlAtBar(trade, bar, decisionHours, holdHours)
          if (shouldForce(trade, bar, holdHours, unrealized)) {
            forced = true
            scenario = unrealized
          }
        }
      }
    }

    rows.push({ no: trade.no, actualPnl: actual, scenarioPnl: scenario, forced })
  }
  return rows
}

/** A: принудительный выход на 12ч только если сделка underwater (unrealized PnL < 0). */
export function simulateUnderwaterCap(
  trades: Trade[],
  bars: ZBar[],
  decisionHours = DECISION_HOURS,
): ExitScenarioResult {
  const ordered = tradesByExitTime(trades)
  const rows = simulateAtDecision(trades, bars, decisionHours, (_t, _b, _h, unrealized) => unrealized < 0)
  return finalizeScenario('A', `A underwater ${decisionHours}ч`, decisionHours, rows, ordered)
}

/** B: как A, но только если spread бара > P90 всех баров датасета. */
export function simulateUnderwaterSpreadP90Cap(
  trades: Trade[],
  bars: ZBar[],
  decisionHours = DECISION_HOURS,
): ExitScenarioResult {
  const p90Spread = percentile(
    bars.map((b) => b.spread_percent).filter((v) => Number.isFinite(v)),
    90,
  )
  const ordered = tradesByExitTime(trades)
  const rows = simulateAtDecision(
    trades,
    bars,
    decisionHours,
    (_t, bar, _h, unrealized) => unrealized < 0 && bar.spread_percent > p90Spread,
  )
  const result = finalizeScenario(
    'B',
    `B underwater ${decisionHours}ч + spread>P90`,
    decisionHours,
    rows,
    ordered,
  )
  return { ...result, label: `${result.label} (P90=${p90Spread.toFixed(3)}%)` }
}

/**
 * C: на entry+12ч — если unrealized < 0, exit Z ещё не достигнут и z ухудшился vs вход:
 * LONG: bar.z < -exitZ (не откатился) и bar.z < entry_z (z ещё ниже);
 * SHORT: bar.z > exitZ и bar.z > entry_z.
 */
export function simulateZDivergenceCap(
  trades: Trade[],
  bars: ZBar[],
  exitZ = 0.7,
  decisionHours = DECISION_HOURS,
): ExitScenarioResult {
  const ordered = tradesByExitTime(trades)
  const rows = simulateAtDecision(trades, bars, decisionHours, (trade, bar, _h, unrealized) => {
    if (unrealized >= 0) return false
    if (trade.direction === 'LONG') {
      return bar.z_score < -exitZ && bar.z_score < trade.entry_z
    }
    if (trade.direction === 'SHORT') {
      return bar.z_score > exitZ && bar.z_score > trade.entry_z
    }
    return false
  })
  return finalizeScenario('C', `C z-divergence ${decisionHours}ч`, decisionHours, rows, ordered)
}

export function holdCapToExitScenario(
  hold: HoldCapScenario,
  trades: Trade[],
  id: string,
  label: string,
): ExitScenarioResult {
  const ordered = tradesByExitTime(trades)
  const tradeOrder = new Map(ordered.map((t, i) => [t.no, i]))
  const sortedHoldRows = [...hold.rows].sort((a, b) => (tradeOrder.get(a.no) ?? 0) - (tradeOrder.get(b.no) ?? 0))

  return {
    id,
    label,
    decisionHours: hold.capHours,
    baselinePnl: hold.baselinePnl,
    scenarioPnl: hold.scenarioPnl,
    deltaPnl: hold.deltaPnl,
    deltaPct: hold.deltaPct,
    cappedCount: hold.cappedCount,
    baselineMaxDrawdownPct: maxDrawdownPct(sortedHoldRows.map((r) => r.actualPnl)),
    scenarioMaxDrawdownPct: maxDrawdownPct(sortedHoldRows.map((r) => r.hypoPnl)),
    rows: hold.rows.map((r) => ({
      no: r.no,
      actualPnl: r.actualPnl,
      scenarioPnl: r.hypoPnl,
      forced: r.capped,
    })),
  }
}

export function runAllExitScenarios(
  trades: Trade[],
  bars: ZBar[],
  exitZ = 0.7,
): { baseline: ExitScenarioResult; scenarios: ExitScenarioResult[] } {
  const ordered = tradesByExitTime(trades)
  const baselineRows: ExitScenarioRow[] = trades.map((t) => ({
    no: t.no,
    actualPnl: t.pnl_rub,
    scenarioPnl: t.pnl_rub,
    forced: false,
  }))
  const baseline = finalizeScenario('baseline', 'Baseline', 0, baselineRows, ordered)

  const hold12 = holdCapToExitScenario(simulateHoldCap(trades, bars, 12), trades, 'D12', 'Max Hold 12ч')
  const hold24 = holdCapToExitScenario(simulateHoldCap(trades, bars, 24), trades, 'D24', 'Max Hold 24ч')
  const a = simulateUnderwaterCap(trades, bars)
  const b = simulateUnderwaterSpreadP90Cap(trades, bars)
  const c = simulateZDivergenceCap(trades, bars, exitZ)

  return { baseline, scenarios: [hold12, hold24, a, b, c] }
}

function fmtRub(v: number): string {
  return `${Math.round(v).toLocaleString('ru-RU')} ₽`
}

function fmtScenarioLine(s: ExitScenarioResult): string {
  const deltaSign = s.deltaPct >= 0 ? '+' : ''
  return (
    `${s.label}: PnL ${fmtRub(s.baselinePnl)} → ${fmtRub(s.scenarioPnl)} ` +
    `(Δ ${fmtRub(s.deltaPnl)}, ${deltaSign}${s.deltaPct.toFixed(1)}%), ` +
    `Max DD ${s.baselineMaxDrawdownPct.toFixed(1)}% → ${s.scenarioMaxDrawdownPct.toFixed(1)}%, ` +
    `закрыто ${s.cappedCount}`
  )
}

/** Блок для LLM: сценарии выхода с PnL и Max DD. */
export function buildExitScenarioContextLines(
  trades: Trade[],
  bars: ZBar[],
  exitZ = 0.7,
): string[] {
  if (!trades.length) return []
  const { baseline, scenarios } = runAllExitScenarios(trades, bars, exitZ)
  return [
    '',
    '=== СЦЕНАРИИ ВЫХОДА (PnL и Max DD) ===',
    'Baseline: PnL ' +
      fmtRub(baseline.baselinePnl) +
      `, Max DD ${baseline.baselineMaxDrawdownPct.toFixed(1)}%`,
    'Метод: сделки в порядке exit_time; Max DD — по кумулятивной кривой PnL (% от пика).',
    'A: на entry+12ч выход только если unrealized PnL < 0 (комиссия/overnight как в max-hold).',
    'B: как A + spread бара > P90 всех 15м баров.',
    `C: на entry+12ч unrealized < 0, exit Z не достигнут (LONG z<-${exitZ}, SHORT z>${exitZ}) и z ухудшился vs entry_z.`,
    'Max Hold: если hold > N ч — выход на spread бара entry+Nч.',
    'Используй ТОЛЬКО цифры ниже для вопросов про сценарии выхода и просадку.',
    ...scenarios.map(fmtScenarioLine),
  ]
}
