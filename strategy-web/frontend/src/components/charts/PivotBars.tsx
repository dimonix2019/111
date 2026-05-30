import { formatPct } from '@/lib/tradeDistribution'
import { formatPivotValue, type PivotCell, type PivotMeasureId } from '@/lib/tradePivot'

type Props = {
  rows: PivotCell[]
  measure: PivotMeasureId
  totalTrades: number
  chartHeight?: number
  scrollable?: boolean
}

export function PivotBars({ rows, measure, totalTrades, chartHeight = 96, scrollable = false }: Props) {
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
    <div className={`flex gap-px border-b border-panel-border-soft pb-1 ${scrollable ? 'min-w-max' : ''}`}>
      {chartRows.map((row) => {
        const { rowKey, count, value } = row
        const barPx = count > 0 ? Math.max(3, Math.round((Math.abs(value) / maxVal) * chartHeight)) : 2
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

        return (
          <div
            key={rowKey}
            className={`flex shrink-0 flex-col items-center gap-0.5 ${scrollable ? 'w-11' : 'min-w-0 flex-1'}`}
            title={title}
          >
            {count > 0 ? (
              scrollable ? (
                <span className={`flex flex-col items-center leading-none ${highlight ? 'text-warn' : 'text-ink-3'}`}>
                  <span className="max-w-full truncate text-[7px] font-semibold">{valLabel}</span>
                  <span className="text-[7px]">
                    {count} ({pctLabel})
                  </span>
                </span>
              ) : (
                <span
                  className={`max-w-full truncate text-center text-[7px] font-semibold leading-none ${highlight ? 'text-warn' : 'text-ink-3'}`}
                >
                  {valLabel}
                  <span className="block text-[6px] font-normal opacity-80">
                    {count} ({pctLabel})
                  </span>
                </span>
              )
            ) : (
              <span className="text-[8px] leading-none text-transparent">0</span>
            )}
            <div className="flex w-full items-end justify-center" style={{ height: chartHeight }}>
              <div
                className={`rounded-t-sm ${scrollable ? 'w-5' : 'w-full max-w-[44px]'}`}
                style={{
                  height: barPx,
                  background: bg,
                  boxShadow: highlight ? '0 0 8px rgba(212,184,106,0.3)' : undefined,
                }}
              />
            </div>
            <span
              className={`max-w-full truncate text-center text-[7px] font-medium leading-tight ${
                highlight ? 'text-warn' : 'text-ink-3'
              } ${scrollable && !rowKey.endsWith(':00') && !rowKey.endsWith(':30') ? 'opacity-40' : ''}`}
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
