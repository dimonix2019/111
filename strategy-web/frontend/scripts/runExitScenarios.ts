/**
 * Reads { trades, zscore, exit_z } from stdin; prints exit scenario summary.
 */
import { readFileSync } from 'node:fs'

import { buildExitScenarioContextLines, runAllExitScenarios } from '../src/lib/asymmetricExitSimulation'

const raw = readFileSync(0, 'utf8')
const { trades, zscore, exit_z } = JSON.parse(raw) as {
  trades: Parameters<typeof runAllExitScenarios>[0]
  zscore: Parameters<typeof runAllExitScenarios>[1]
  exit_z?: number
}

const exitZ = exit_z ?? 0.7
const { baseline, scenarios } = runAllExitScenarios(trades, zscore, exitZ)

console.log(`Trades: ${trades.length}`)
console.log(`Baseline PnL: ${Math.round(baseline.baselinePnl)} ₽, Max DD ${baseline.baselineMaxDrawdownPct.toFixed(1)}%`)
for (const s of scenarios) {
  console.log(
    `${s.label}: PnL ${Math.round(s.scenarioPnl)} ₽ (Δ ${Math.round(s.deltaPnl)} ₽), ` +
      `Max DD ${s.scenarioMaxDrawdownPct.toFixed(1)}%, capped ${s.cappedCount}`,
  )
}
console.log('--- LLM block preview ---')
console.log(buildExitScenarioContextLines(trades, zscore, exitZ).slice(0, 12).join('\n'))
