import { describe, expect, it } from 'vitest'

import type { Trade } from '@/types'
import { simulateHoldCap } from '@/lib/holdCapSimulation'

const bars = [
  { time: 1_000_000, z_score: 1, spread_percent: 1.0, tatn_close: 600, tatnp_close: 594 },
  { time: 1_021_600, z_score: 0.5, spread_percent: 1.2, tatn_close: 602, tatnp_close: 595 },
  { time: 1_043_200, z_score: 0.2, spread_percent: 0.9, tatn_close: 601, tatnp_close: 596 },
]

describe('simulateHoldCap', () => {
  it('keeps short holds unchanged', () => {
    const trades: Trade[] = [
      {
        no: 1,
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
      },
    ]
    const s = simulateHoldCap(trades, bars, 6)
    expect(s.cappedCount).toBe(0)
    expect(s.deltaPnl).toBe(0)
  })

  it('recalculates long hold using bar at cap', () => {
    const trades: Trade[] = [
      {
        no: 2,
        direction: 'LONG',
        entry_time: '1970-01-12 13:46:40',
        exit_time: '1970-01-14 13:46:40',
        entry_spread: 1.0,
        exit_spread: 0.9,
        entry_z: 1,
        exit_z: 0.2,
        pnl_spread_pts: -0.1,
        pnl_rub: -800,
        commission_rub: 100,
        overnight_rub: 50,
      },
    ]
    const s = simulateHoldCap(trades, bars, 6)
    expect(s.cappedCount).toBe(1)
    expect(s.scenarioPnl).not.toBe(s.baselinePnl)
  })
})
