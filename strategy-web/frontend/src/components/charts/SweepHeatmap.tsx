import { useMemo } from 'react'
import { fmtRub } from '@/lib/api'

export type SweepRow = {
  entry: number
  exit_z: number
  total_pnl_rub: number
  trade_count: number
  max_drawdown_rub?: number
}

const MID = [55, 65, 81] as const
const GREEN = [16, 185, 129] as const
const RED = [239, 68, 68] as const

function lerp(a: readonly number[], b: readonly number[], t: number): string {
  const k = Math.max(0, Math.min(1, t))
  const r = Math.round(a[0] + (b[0] - a[0]) * k)
  const g = Math.round(a[1] + (b[1] - a[1]) * k)
  const bl = Math.round(a[2] + (b[2] - a[2]) * k)
  return `rgb(${r}, ${g}, ${bl})`
}

function heatColor(value: number, maxPos: number, maxNegAbs: number): string {
  if (value >= 0) {
    const t = maxPos > 0 ? value / maxPos : 0
    return lerp(MID, GREEN, t)
  }
  const t = maxNegAbs > 0 ? Math.abs(value) / maxNegAbs : 0
  return lerp(MID, RED, t)
}

function fmtCellRub(v: number): string {
  return Math.round(v).toLocaleString('ru-RU', { maximumFractionDigits: 0 })
}

type Props = {
  rows: SweepRow[]
  currentEntry?: number
  currentExit?: number
}

export function SweepHeatmap({ rows, currentEntry, currentExit }: Props) {
  const model = useMemo(() => {
    const entries = [...new Set(rows.map((r) => r.entry))].sort((a, b) => a - b)
    const exits = [...new Set(rows.map((r) => r.exit_z))].sort((a, b) => b - a)
    const lookup = new Map<string, SweepRow>()
    for (const r of rows) lookup.set(`${r.entry}|${r.exit_z}`, r)

    const pnls = rows.map((r) => r.total_pnl_rub)
    const maxPos = Math.max(0, ...pnls)
    const maxNegAbs = Math.max(0, ...pnls.filter((v) => v < 0).map(Math.abs))
    let best: SweepRow | null = null
    for (const r of rows) {
      if (!best || r.total_pnl_rub > best.total_pnl_rub) best = r
    }

    return { entries, exits, lookup, maxPos, maxNegAbs, best }
  }, [rows])

  if (!rows.length) return null

  return (
    <div className="space-y-3">
      <div className="overflow-x-auto rounded-xl border border-panel-border-soft">
        <table className="w-full min-w-[480px] border-collapse text-[11px]">
          <thead>
            <tr>
              <th className="sticky left-0 z-20 border-b border-panel-border-soft bg-[rgba(8,16,28,0.98)] px-2 py-2 text-left font-medium text-ink-3">
                Exit ↓ / Entry →
              </th>
              {model.entries.map((e) => (
                <th
                  key={e}
                  className={`border-b border-panel-border-soft px-1 py-2 text-center font-medium tabular-nums ${
                    currentEntry === e ? 'text-warn' : 'text-ink-3'
                  }`}
                >
                  {e}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {model.exits.map((exit) => (
              <tr key={exit}>
                <th
                  className={`sticky left-0 z-10 border-r border-panel-border-soft bg-[rgba(8,16,28,0.96)] px-2 py-1 text-left font-medium tabular-nums ${
                    currentExit === exit ? 'text-warn' : 'text-ink-3'
                  }`}
                >
                  {exit}
                </th>
                {model.entries.map((entry) => {
                  const cell = model.lookup.get(`${entry}|${exit}`)
                  if (!cell) {
                    return (
                      <td key={entry} className="border border-panel-border-soft/40 bg-[rgba(8,16,28,0.5)] p-1">
                        <div className="h-11" />
                      </td>
                    )
                  }
                  const isCurrent = currentEntry === entry && currentExit === exit
                  const isBest =
                    model.best?.entry === entry && model.best?.exit_z === exit && rows.length > 1
                  const bg = heatColor(cell.total_pnl_rub, model.maxPos, model.maxNegAbs)
                  const textLight = cell.total_pnl_rub >= 0 ? 'text-[rgba(240,248,255,0.95)]' : 'text-[rgba(255,245,245,0.95)]'

                  return (
                    <td key={entry} className="border border-panel-border-soft/40 p-0.5">
                      <div
                        className={`flex h-11 min-w-[52px] flex-col items-center justify-center rounded-md px-1 ${textLight} ${
                          isCurrent ? 'ring-2 ring-warn/70 ring-offset-1 ring-offset-[rgba(8,16,28,0.9)]' : ''
                        }`}
                        style={{ background: bg }}
                        title={`Entry ${entry} · Exit ${exit}\nPnL: ${fmtRub(cell.total_pnl_rub)}\nСделок: ${cell.trade_count}`}
                      >
                        <span className="font-semibold leading-tight tabular-nums">{fmtCellRub(cell.total_pnl_rub)}</span>
                        <span className="text-[9px] opacity-80">{cell.trade_count} сд.</span>
                        {isBest ? <span className="text-[8px] opacity-90">★ best</span> : null}
                      </div>
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="flex flex-wrap gap-4 text-[10px] text-ink-3">
        <span className="flex items-center gap-1.5">
          <span className="inline-block h-3 w-6 rounded-sm" style={{ background: lerp(MID, RED, 1) }} />
          убыток
        </span>
        <span className="flex items-center gap-1.5">
          <span className="inline-block h-3 w-6 rounded-sm bg-[rgb(55,65,81)]" />
          ~0
        </span>
        <span className="flex items-center gap-1.5">
          <span className="inline-block h-3 w-6 rounded-sm" style={{ background: lerp(MID, GREEN, 1) }} />
          прибыль
        </span>
        {model.best ? (
          <span>
            Лучшее: Entry <b className="text-ink-2">{model.best.entry}</b> · Exit{' '}
            <b className="text-ink-2">{model.best.exit_z}</b> → {fmtRub(model.best.total_pnl_rub)}
          </span>
        ) : null}
      </div>
    </div>
  )
}
