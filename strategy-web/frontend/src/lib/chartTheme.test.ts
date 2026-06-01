import { describe, expect, it } from 'vitest'

import { toTradeSeriesMarkers } from '@/lib/chartTheme'
import type { TradeMarker } from '@/types'

function mkMarker(trade_no: number, event: string): TradeMarker {
  return {
    trade_no,
    event,
    time: 1_700_000_000 + trade_no,
    z_score: 1,
    direction: 'LONG',
    entry_time: '',
    exit_time: '',
    pnl_rub: 0,
    pnl_spread_pts: 0,
    marker_color: '#000',
  }
}

describe('toTradeSeriesMarkers', () => {
  it('renders shape/color only — no text labels on chart', () => {
    const markers = [mkMarker(3, 'вход'), mkMarker(4, 'выход')]
    const out = toTradeSeriesMarkers(markers)
    expect(out).toHaveLength(2)
    for (const m of out) {
      expect(m.text).toBeUndefined()
    }
  })
})
