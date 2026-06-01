type Bar = {
  bucket: string
  count: number
  highlight?: boolean
  tone?: 'good' | 'bad' | 'neutral'
}

type Props = {
  bars: Bar[]
  chartHeight?: number
  /** Горизонтальная прокрутка для длинных рядов (время суток) */
  scrollable?: boolean
  /** Для подписи «N (X%)» на столбцах */
  totalCount?: number
}

export function HistogramBars({ bars, chartHeight = 120, scrollable = false, totalCount }: Props) {
  const maxCount = Math.max(1, ...bars.map((b) => b.count))
  const total = totalCount && totalCount > 0 ? totalCount : bars.reduce((s, b) => s + b.count, 0)

  const content = (
    <div className={`flex gap-1 border-b border-panel-border-soft pb-1.5 ${scrollable ? 'min-w-max' : ''}`}>
      {bars.map(({ bucket, count, highlight, tone = 'neutral' }) => {
        const barPx = count > 0 ? Math.max(4, Math.round((count / maxCount) * chartHeight)) : 2
        const pct = total > 0 && count > 0 ? Math.round((100 * count) / total) : 0
        const bg =
          highlight
            ? 'linear-gradient(180deg, rgba(212,184,106,0.9), rgba(209,122,136,0.8))'
            : tone === 'good'
              ? 'linear-gradient(180deg, rgba(94,196,146,0.85), rgba(74,222,154,0.45))'
              : tone === 'bad'
                ? 'linear-gradient(180deg, rgba(209,122,136,0.85), rgba(180,90,110,0.45))'
                : count > 0
                  ? 'linear-gradient(180deg, rgba(130,150,170,0.75), rgba(95,110,130,0.5))'
                  : 'linear-gradient(180deg, rgba(80,90,105,0.25), rgba(60,70,85,0.15))'
        return (
          <div
            key={bucket}
            className={`flex shrink-0 flex-col items-center gap-1 ${scrollable ? 'w-14' : 'min-w-0 flex-1 px-0.5'}`}
            title={`${bucket}: ${count}${pct ? ` (${pct}%)` : ''}`}
          >
            {count > 0 ? (
              <span className={`font-semibold leading-tight tabular-nums ${scrollable ? 'text-[10px]' : 'text-[12px]'} text-ink-2`}>
                {count}
                {pct > 0 ? <span className={`font-normal text-ink-3 ${scrollable ? 'text-[9px]' : 'text-[11px]'}`}> · {pct}%</span> : null}
              </span>
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
              } ${highlight ? 'text-warn' : tone === 'good' ? 'text-good' : tone === 'bad' ? 'text-bad' : 'text-ink-2'} ${
                scrollable && !bucket.endsWith(':00') && !bucket.endsWith(':30') ? 'opacity-50' : ''
              }`}
            >
              {scrollable ? bucket.replace(':00', '') : bucket}
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
