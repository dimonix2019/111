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
}

export function HistogramBars({ bars, chartHeight = 112, scrollable = false }: Props) {
  const maxCount = Math.max(1, ...bars.map((b) => b.count))

  const content = (
    <div className={`flex gap-px border-b border-panel-border-soft pb-1 ${scrollable ? 'min-w-max' : ''}`}>
      {bars.map(({ bucket, count, highlight, tone = 'neutral' }) => {
        const barPx = count > 0 ? Math.max(3, Math.round((count / maxCount) * chartHeight)) : 2
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
            className={`flex shrink-0 flex-col items-center gap-0.5 ${scrollable ? 'w-10' : 'min-w-0 flex-1'}`}
            title={`${bucket}: ${count}`}
          >
            {count > 0 ? (
              <span className="text-[8px] font-semibold leading-none text-ink-3">{count}</span>
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
                highlight ? 'text-warn' : tone === 'good' ? 'text-good' : tone === 'bad' ? 'text-bad' : 'text-ink-3'
              } ${scrollable && !bucket.endsWith(':00') && !bucket.endsWith(':30') ? 'opacity-40' : ''}`}
            >
              {scrollable ? bucket.replace(':00', '') : bucket}
            </span>
          </div>
        )
      })}
    </motion div>
  )

  if (scrollable) {
    return <div className="scrollbar-thin overflow-x-auto pb-1">{content}</div>
  }
  return content
}
