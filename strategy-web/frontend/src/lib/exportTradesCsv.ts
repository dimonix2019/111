import type { Trade } from '@/types'

function esc(v: string | number): string {
  const s = String(v)
  if (s.includes(',') || s.includes('"') || s.includes('\n')) {
    return `"${s.replace(/"/g, '""')}"`
  }
  return s
}

export function tradesToCsv(trades: Trade[]): string {
  const header = [
    'no',
    'direction',
    'entry_time',
    'exit_time',
    'entry_z',
    'exit_z',
    'entry_spread',
    'exit_spread',
    'pnl_rub',
    'pnl_spread_pts',
    'commission_rub',
    'overnight_rub',
  ]
  const rows = trades.map((t) =>
    [
      t.no,
      t.direction,
      t.entry_time,
      t.exit_time,
      t.entry_z.toFixed(3),
      t.exit_z.toFixed(3),
      t.entry_spread.toFixed(4),
      t.exit_spread.toFixed(4),
      Math.round(t.pnl_rub),
      t.pnl_spread_pts.toFixed(3),
      t.commission_rub ?? 0,
      t.overnight_rub ?? 0,
    ].map(esc).join(','),
  )
  return [header.join(','), ...rows].join('\n')
}

export function downloadTradesCsv(trades: Trade[], filename = 'trades.csv'): void {
  const blob = new Blob(['\ufeff' + tradesToCsv(trades)], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
