import { describe, expect, it } from 'vitest'
import { BAR_15M_SEC, chartLocalToViewport, findNearestMarker, markerPersistKey, MARKER_NEAR_SEC } from '@/lib/chartTradeTooltip'
import type { TradeMarker } from '@/types'

function mkMarker(trade_no: number, event: string, time: number): TradeMarker {
  return {
    trade_no,
    event,
    time,
    z_score: 1,
    direction: 'LONG',
    entry_time: '',
    exit_time: '',
    pnl_rub: 0,
    pnl_spread_pts: 0,
    marker_color: '#000',
  }
}

describe('findNearestMarker', () => {
  const t0 = 1_700_000_000
  const entry = mkMarker(1, 'вход', t0)
  const exit = mkMarker(1, 'выход', t0 + BAR_15M_SEC * 3)

  it('picks entry within half-bar of crosshair time', () => {
    expect(findNearestMarker([entry, exit], t0 + 200)).toBe(entry)
  })

  it('picks exit within half-bar of exit time', () => {
    expect(findNearestMarker([entry, exit], exit.time + 100)).toBe(exit)
  })

  it('returns null beyond MARKER_NEAR_SEC', () => {
    expect(findNearestMarker([entry, exit], t0 + MARKER_NEAR_SEC + 1)).toBeNull()
  })

  it('chooses closer marker when entry and exit are both in range', () => {
    const mid = entry.time + Math.floor((exit.time - entry.time) / 2)
    expect(findNearestMarker([entry, exit], mid)).toBe(entry)
    expect(findNearestMarker([entry, exit], mid + 1)).toBe(
      Math.abs(exit.time - (mid + 1)) < Math.abs(entry.time - (mid + 1)) ? exit : entry,
    )
  })
})

describe('markerPersistKey', () => {
  it('is stable for the same marker', () => {
    const m = mkMarker(3, 'вход', 1_700_000_000)
    expect(markerPersistKey(m)).toBe('3:вход:1700000000')
  })
})

describe('chartLocalToViewport', () => {
  it('adds chart bounding rect to local coordinates', () => {
    const chart = {
      chartElement: () =>
        ({
          getBoundingClientRect: () => ({ left: 320, top: 180, width: 800, height: 280 }),
        }) as HTMLElement,
    }
    expect(chartLocalToViewport(chart as never, { x: 100, y: 50 })).toEqual({ left: 420, top: 230 })
  })
})
