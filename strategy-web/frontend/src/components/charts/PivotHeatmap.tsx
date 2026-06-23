import { formatPct } from '@/lib/tradeDistribution'
import { formatPivotValue, type PivotCell, type PivotMeasureId } from '@/lib/tradePivot'

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

type Props = {
  rowKeys: string[]
  colKeys: string[]
  cells: Map<string, PivotCell>
  measure: PivotMeasureId
  totalTrades: number
  rowLabel: string
  colLabel: string
  colorByPnl?: boolean
}

export function PivotHeatmap({
  rowKeys,
  colKeys,
  cells,
  measure,
  totalTrades,
  rowLabel,
  colLabel,
  colorByPnl = true,
}: Props) {
  const values = [...cells.values()].map((c) => c.value)
  const maxPos = Math.max(0, ...values)
  const maxNegAbs = Math.max(0, ...values.filter((v) => v < 0).map(Math.abs))
  const useHeat = colorByPnl && (measure === 'sum_pnl' || measure === 'avg_pnl' || measure === 'sum_spread')

  return (
    <div className="overflow-x-auto rounded-xl border border-panel-border-soft">
      <table className="w-full min-w-[420px] border-collapse text-[11px]">
        <thead>
          <tr>
            <th className="sticky left-0 z-20 border-b border-panel-border-soft bg-[rgba(8,16,28,0.98)] px-2 py-2 text-left font-medium text-ink-3">
              {rowLabel} ↓ / {colLabel} →
            </th>
            {colKeys.map((c) => (
              <th key={c} className="border-b border-panel-border-soft px-1 py-2 text-center font-medium text-ink-3">
                {c}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rowKeys.map((row) => {
            const rowCells = colKeys.map((col) => cells.get(`${row}\0${col}`))
            const rowHasData = rowCells.some((c) => c && c.count > 0)
            if (!rowHasData) return null
            return (
              <tr key={row}>
                <td className="sticky left-0 z-10 border-b border-panel-border-soft/60 bg-[rgba(8,16,28,0.96)] px-2 py-1 font-medium text-ink-2">
                  {row}
                </td>
                {colKeys.map((col) => {
                  const cell = cells.get(`${row}\0${col}`)
                  const count = cell?.count ?? 0
                  const value = cell?.value ?? 0
                  const bg = useHeat && count > 0 ? heatColor(value, maxPos, maxNegAbs) : 'rgba(60,70,85,0.35)'
                  const title = cell
                    ? `${row} × ${col}: ${formatPivotValue(measure, value)} · ${count} (${formatPct(count, totalTrades)})`
                    : ''
                  return (
                    <td
                      key={col}
                      title={title}
                      className="border-b border-panel-border-soft/40 px-1 py-1 text-center tabular-nums text-ink-1"
                      style={{ backgroundColor: count > 0 ? bg : undefined }}
                    >
                      {count > 0 ? (
                        <span className="block leading-tight">
                          <span className="font-semibold">{formatPivotValue(measure, value)}</span>
                          <span className="block text-[9px] text-ink-3">{count}</span>
                        </span>
                      ) : (
                        <span className="text-ink-3/40">—</span>
                      )}
                    </td>
                  )
                })}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
