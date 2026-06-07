import type { SimPack, Trade } from '@/types'
import { parseTs, tradeHoldHours } from '@/lib/tradeDistribution'

type ZBar = SimPack['zscore'][number]

export type HoldCapTradeRow = {
  no: number
  holdHours: number
  actualPnl: number
  hypoPnl: number
  delta: number
  capped: boolean
}

export type HoldCapScenario = {
  capHours: number
  baselinePnl: number
  scenarioPnl: number
  deltaPnl: number
  deltaPct: number
  tradeCount: number
  cappedCount: number
  cappedActualPnl: number
  cappedHypoPnl: number
  winsBaseline: number
  winsScenario: number
  rows: HoldCapTradeRow[]
}

export function wallTimeToUnix(s: string): number | null {
  const d = parseTs(s)
  if (!d) return null
  return Math.floor(d.getTime() / 1000)
}

export function nearestBarIndex(bars: ZBar[], unix: number): number {
  if (!bars.length) return 0
  let lo = 0
  let hi = bars.length - 1
  while (lo < hi) {
    const mid = Math.floor((lo + hi) / 2)
    if (bars[mid]!.time < unix) lo = mid + 1
    else hi = mid
  }
  if (lo > 0 && Math.abs(bars[lo - 1]!.time - unix) < Math.abs(bars[lo]!.time - unix)) {
    return lo - 1
  }
  return lo
}

export function barAtOffset(bars: ZBar[], entryUnix: number, offsetHours: number): ZBar | null {
  if (!bars.length) return null
  const target = entryUnix + offsetHours * 3600
  return bars[nearestBarIndex(bars, target)] ?? null
}

/** Валовый PnL по спреду (до комиссии/overnight). */
export function tradeGrossRub(trade: Trade): number {
  return trade.pnl_rub + (trade.commission_rub ?? 0) + (trade.overnight_rub ?? 0)
}

/** Эффективный номинал×плечо из фактической сделки. */
export function inferEffNotional(trade: Trade): number {
  if (Math.abs(trade.pnl_spread_pts) < 1e-9) return 0
  return (tradeGrossRub(trade) * 100) / trade.pnl_spread_pts
}

export function spreadPnlPts(direction: string, entrySpread: number, exitSpread: number): number {
  if (direction === 'LONG') return exitSpread - entrySpread
  return entrySpread - exitSpread
}

export function spreadPnlToRub(pnlSpreadPts: number, effNotional: number): number {
  return effNotional * (pnlSpreadPts / 100)
}

/** PnL при выходе на spread бара entry+capHours (комиссия полная, overnight ∝ cap/hold). */
export function hypoPnlAtBar(trade: Trade, bar: ZBar, capHours: number, holdHours: number): number {
  const hypoPts = spreadPnlPts(trade.direction, trade.entry_spread, bar.spread_percent)
  const eff = inferEffNotional(trade)
  const gross = spreadPnlToRub(hypoPts, eff)
  const comm = trade.commission_rub ?? 0
  const ovn = trade.overnight_rub ?? 0
  const ovnScaled = holdHours > 0 ? ovn * Math.min(1, capHours / holdHours) : ovn
  return gross - comm - ovnScaled
}

/**
 * Если сделка держится дольше capHours — закрыть на баре entry+capHours.
 * Spread PnL пересчитывается по spread бара; комиссия — полная; overnight ∝ cap/hold.
 */
export function simulateHoldCap(
  trades: Trade[],
  bars: ZBar[],
  capHours: number,
): HoldCapScenario {
  const rows: HoldCapTradeRow[] = []
  let baselinePnl = 0
  let scenarioPnl = 0
  let cappedCount = 0
  let cappedActualPnl = 0
  let cappedHypoPnl = 0
  let winsBaseline = 0
  let winsScenario = 0

  for (const trade of trades) {
    const holdHours = tradeHoldHours(trade)
    const actual = trade.pnl_rub
    baselinePnl += actual
    if (actual > 0) winsBaseline += 1

    let hypo = actual
    let capped = false

    if (holdHours > capHours + 1e-6 && bars.length > 0) {
      const entryUnix = wallTimeToUnix(trade.entry_time)
      if (entryUnix != null) {
        const bar = barAtOffset(bars, entryUnix, capHours)
        if (bar) {
          capped = true
          hypo = hypoPnlAtBar(trade, bar, capHours, holdHours)
        }
      }
    }

    scenarioPnl += hypo
    if (hypo > 0) winsScenario += 1

    if (capped) {
      cappedCount += 1
      cappedActualPnl += actual
      cappedHypoPnl += hypo
    }

    rows.push({
      no: trade.no,
      holdHours,
      actualPnl: actual,
      hypoPnl: hypo,
      delta: hypo - actual,
      capped,
    })
  }

  const deltaPnl = scenarioPnl - baselinePnl
  const deltaPct = baselinePnl !== 0 ? (100 * deltaPnl) / Math.abs(baselinePnl) : 0

  return {
    capHours,
    baselinePnl,
    scenarioPnl,
    deltaPnl,
    deltaPct,
    tradeCount: trades.length,
    cappedCount,
    cappedActualPnl,
    cappedHypoPnl,
    winsBaseline,
    winsScenario,
    rows,
  }
}

export function formatHoldCapScenarioLines(scenario: HoldCapScenario): string[] {
  const s = scenario
  return [
    `- Лимит ${s.capHours} ч: PnL ${Math.round(s.baselinePnl).toLocaleString('ru-RU')} → ${Math.round(s.scenarioPnl).toLocaleString('ru-RU')} ₽ (Δ ${Math.round(s.deltaPnl).toLocaleString('ru-RU')} ₽, ${s.deltaPct >= 0 ? '+' : ''}${s.deltaPct.toFixed(1)}%)`,
    `  Закрыто принудительно: ${s.cappedCount} сделок; их факт. PnL ${Math.round(s.cappedActualPnl).toLocaleString('ru-RU')} → ${Math.round(s.cappedHypoPnl).toLocaleString('ru-RU')} ₽`,
    `  Win rate: ${s.tradeCount ? ((100 * s.winsBaseline) / s.tradeCount).toFixed(1) : '0'}% → ${s.tradeCount ? ((100 * s.winsScenario) / s.tradeCount).toFixed(1) : '0'}%`,
  ]
}

/** Блок для LLM: сценарии max-hold (не выдумывать — только эти цифры). */
export function buildHoldCapContextLines(trades: Trade[], bars: ZBar[]): string[] {
  if (!trades.length) return []
  const caps = [4, 6, 8, 12, 24]
  const lines: string[] = [
    '',
    '=== СЦЕНАРИИ MAX HOLD (пересчёт по каждой сделке, spread бара на entry+N ч) ===',
    'Метод: если hold > N ч — выход на spread бара entry+Nч; комиссия полная; overnight × min(1, N/hold).',
    'Используй ТОЛЬКО цифры ниже для вопросов «что если закрывать через N часов».',
  ]
  for (const cap of caps) {
    lines.push(...formatHoldCapScenarioLines(simulateHoldCap(trades, bars, cap)))
  }
  const top = simulateHoldCap(trades, bars, 6)
  const worst = [...top.rows]
    .filter((r) => r.capped)
    .sort((a, b) => a.delta - b.delta)
    .slice(0, 5)
  if (worst.length) {
    lines.push('  Топ ухудшений при cap 6ч (no, Δ₽): ' + worst.map((r) => `#${r.no} ${Math.round(r.delta)}`).join(', '))
  }
  const best = [...top.rows]
    .filter((r) => r.capped)
    .sort((a, b) => b.delta - a.delta)
    .slice(0, 5)
  if (best.length) {
    lines.push('  Топ улучшений при cap 6ч (no, Δ₽): ' + best.map((r) => `#${r.no} +${Math.round(r.delta)}`).join(', '))
  }
  return lines
}
