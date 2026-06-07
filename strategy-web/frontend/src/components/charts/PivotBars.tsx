import { formatPct } from '@/lib/tradeDistribution'
import { formatPivotValue, type PivotCell, type PivotMeasureId } from '@/lib/tradePivot'

type Props = {
  rows: PivotCell[]
  measure: PivotMeasureId
  totalTrades: number
  chartHeight?: number
  scrollable?: boolean
}

export function PivotBars({ rows, measure, totalTrades, chartHeight = 120, scrollable = false }: Props) {
  const chartRows = scrollable ? rows.filter((r) => r.count > 0) : rows
  const active = chartRows.filter((r) => r.count > 0)
  const maxVal = Math.max(1, ...active.map((r) => Math.abs(r.value)))
  let peakKey = ''
  let peakVal = -Infinity
  for (const r of active) {
    if (Math.abs(r.value) > peakVal) {
      peakVal = Math.abs(r.value)
      peakKey = r.rowKey
    }
  }

  const content = (
    <div className={`flex gap-1 border-b border-panel-border-soft pb-1.5 ${scrollable ? 'min-w-max' : ''}`}>
      {chartRows.map((row) => {
        const { rowKey, count, value } = row
        const barPx = count > 0 ? Math.max(4, Math.round((Math.abs(value) / maxVal) * chartHeight)) : 2
        const pctLabel = count > 0 ? formatPct(count, totalTrades) : null
        const valLabel = count > 0 ? formatPivotValue(measure, value) : ''
        const highlight = rowKey === peakKey && count > 0
        const title = `${rowKey}: ${valLabel} · ${count} сд.${pctLabel ? ` (${pctLabel})` : ''}`
        const pnlTone = measure === 'sum_pnl' || measure === 'avg_pnl'
        const bg =
          highlight
            ? 'linear-gradient(180deg, rgba(212,184,106,0.9), rgba(209,122,136,0.8))'
            : pnlTone && value > 0
              ? 'linear-gradient(180deg, rgba(94,196,146,0.85), rgba(74,222,154,0.45))'
              : pnlTone && value < 0
                ? 'linear-gradient(180deg, rgba(209,122,136,0.85), rgba(180,90,110,0.45))'
                : count > 0
                  ? 'linear-gradient(180deg, rgba(130,150,170,0.75), rgba(95,110,130,0.5))'
                  : 'linear-gradient(180deg, rgba(80,90,105,0.25), rgba(60,70,85,0.15))'

        const labelTone = highlight ? 'text-warn' : pnlTone && value > 0 ? 'text-good' : pnlTone && value < 0 ? 'text-bad' : 'text-ink-2'

        return (
          <div
            key={rowKey}
            className={`flex shrink-0 flex-col items-center gap-1 ${scrollable ? 'w-14' : 'min-w-0 flex-1 px-0.5'}`}
            title={title}
          >
            {count > 0 ? (
              <div className={`flex w-full flex-col items-center gap-0.5 leading-tight ${labelTone}`}>
                <span className={`text-center font-semibold tabular-nums ${scrollable ? 'text-[10px]' : 'text-[12px]'}`}>
                  {valLabel}
                </span>
                <span className={`text-center tabular-nums text-ink-3 ${scrollable ? 'text-[9px]' : 'text-[11px]'}`}>
                  {count} ({pctLabel})
                </span>
              </div>
            ) : (
              <span className="text-[11px] leading-none text-transparent">0</span>
            )}
            <div className="flex w-full items-end justify-center" style={{ height: chartHeight }}>
              <div
                className={`rounded-t-sm ${scrollable ? 'w-6' : 'w-full max-w-[52px]'}`}
                style={{
                  height: barPx,
                  background: bg,
                  boxShadow: highlight ? '0 0 8px rgba(212,184,106,0.3)' : undefined,
                }}
              />
            </div>
            <span
              className={`max-w-full text-center font-medium leading-tight ${
                scrollable ? 'text-[10px]' : 'text-[12px]'
              } ${highlight ? 'text-warn' : 'text-ink-2'} ${
                scrollable && !rowKey.endsWith(':00') && !rowKey.endsWith(':30') ? 'opacity-50' : ''
              }`}
            >
              {scrollable ? rowKey.replace(':00', '') : rowKey}
            </span>
          </div>
        )
      })}
    </div>
  )

  if (scrollable) {
    return <div className="scrollbar-thin overflow-x-auto pb-1">{content}</div>
  }
  return content
}
