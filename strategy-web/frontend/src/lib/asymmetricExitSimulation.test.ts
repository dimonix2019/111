import { describe, expect, it } from 'vitest'

import type { Trade } from '@/types'
import {
  maxDrawdownPct,
  runAllExitScenarios,
  simulateUnderwaterCap,
  simulateUnderwaterSpreadP90Cap,
  simulateZDivergenceCap,
} from '@/lib/asymmetricExitSimulation'

const bars = [
  { time: 1_000_000, z_score: -1.2, spread_percent: 1.0, tatn_close: 600, tatnp_close: 594 },
  { time: 1_021_600, z_score: -1.5, spread_percent: 1.5, tatn_close: 602, tatnp_close: 595 },
  { time: 1_043_200, z_score: -1.5, spread_percent: 0.85, tatn_close: 601, tatnp_close: 596 },
  { time: 1_064_800, z_score: 0.2, spread_percent: 0.8, tatn_close: 600, tatnp_close: 597 },
]

describe('maxDrawdownPct', () => {
  it('computes drawdown from cumulative peak', () => {
    expect(maxDrawdownPct([100, -50, 50])).toBeCloseTo(50, 0)
  })
})

describe('asymmetric exit scenarios', () => {
  const longUnderwater: Trade = {
    no: 1,
    direction: 'LONG',
    entry_time: '1970-01-12 13:46:40',
    exit_time: '1970-01-14 13:46:40',
    entry_spread: 1.0,
    exit_spread: 0.9,
    entry_z: -1.2,
    exit_z: -0.5,
    pnl_spread_pts: -0.1,
    pnl_rub: -800,
    commission_rub: 100,
    overnight_rub: 50,
  }

  const shortHold: Trade = {
    no: 2,
    direction: 'LONG',
    entry_time: '2025-01-01 10:00:00',
    exit_time: '2025-01-01 12:00:00',
    entry_spread: 1.0,
    exit_spread: 1.2,
    entry_z: 1,
    exit_z: 0.5,
    pnl_spread_pts: 0.2,
    pnl_rub: 1400,
    commission_rub: 100,
    overnight_rub: 0,
  }

  it('A caps only underwater long holds', () => {
    const s = simulateUnderwaterCap([longUnderwater, shortHold], bars)
    expect(s.cappedCount).toBe(1)
    expect(s.rows.find((r) => r.no === 2)?.forced).toBe(false)
  })

  it('B requires spread above P90', () => {
    const s = simulateUnderwaterSpreadP90Cap([longUnderwater], bars)
    expect(s.cappedCount).toBeLessThanOrEqual(1)
  })

  it('C forces exit on z divergence when underwater', () => {
    const s = simulateZDivergenceCap([longUnderwater], bars, 0.7)
    expect(s.cappedCount).toBe(1)
  })

  it('runAllExitScenarios returns baseline and five scenarios', () => {
    const { baseline, scenarios } = runAllExitScenarios([longUnderwater, shortHold], bars)
    expect(baseline.baselinePnl).toBe(600)
    expect(scenarios).toHaveLength(5)
    expect(scenarios.map((s) => s.id)).toEqual(['D12', 'D24', 'A', 'B', 'C'])
  })
})
