import type { DistributionMetric, MarketContext } from '@/types'
import { HistogramBars } from '@/components/charts/HistogramBars'
import { Panel } from '@/components/ui/Panel'

export function DistributionChart({
  metric,
  compact = false,
}: {
  metric: DistributionMetric
  compact?: boolean
}) {
  const tone =
    metric.percentile != null && metric.percentile >= 85
      ? 'text-bad'
      : metric.percentile != null && metric.percentile <= 15
        ? 'text-good'
        : 'text-warn'

  const bars = metric.histogram.map(({ bucket, count }) => ({
    bucket,
    count,
    highlight:
      !!metric.highlight_current && bucket === metric.current_bucket && metric.current_value != null,
  }))

  const scrollable = bars.length > 7

  return (
    <Panel title={metric.title} compact={compact}>
      <p
        className={`${compact ? 'mb-2 text-[11px]' : 'mb-3 text-[12px]'} font-medium leading-snug ${tone}`}
      >
        {metric.label}
      </p>
      {metric.histogram.length === 0 ? (
        <p className="text-[12px] text-ink-3">Недостаточно данных</p>
      ) : (
        <>
          <HistogramBars bars={bars} scrollable={scrollable} chartHeight={compact ? 96 : 120} />
          <div className={`mt-2 flex flex-wrap gap-3 ${compact ? 'text-[9px]' : 'text-[10px]'} text-ink-3`}>
            {metric.current_display !== '—' ? (
              <span>
                Сейчас: <b className="text-ink-2">{metric.current_display}</b>
              </span>
            ) : null}
            {metric.percentile != null ? <span>Перцентиль: {metric.percentile}%</span> : null}
            {metric.abs_percentile != null ? <span>|Z| перц.: {metric.abs_percentile}%</span> : null}
          </div>
        </>
      )}
    </Panel>
  )
}

export function MarketContextPanel({
  ctx,
  metricIds,
  showBanner = true,
  stack = false,
}: {
  ctx: MarketContext
  metricIds?: string[]
  showBanner?: boolean
  refreshKey?: number | null
  /** Одна колонка (мобильный /m). */
  stack?: boolean
}) {
  const metrics = metricIds?.length ? ctx.metrics.filter((m) => metricIds.includes(m.id)) : ctx.metrics

  return (
    <div className={`space-y-3 ${stack ? 'market-context-panel--stack' : ''}`}>
      {showBanner ? (
        <div className="rounded-xl border border-panel-border-soft bg-[rgba(8,16,28,0.4)] px-4 py-3">
          <div className="text-[11px] font-semibold tracking-[0.18em] text-ink-3">РЫНОК СЕЙЧАС</div>
          <div className="mt-1 text-[13px] font-medium text-ink-1">
            На {ctx.as_of_display}
            {ctx.in_position ? ' · есть открытая позиция' : ' · flat'}
          </div>
          <p className="mt-1 text-[11px] text-ink-3">
            Оранжевым — текущее значение (Z, spread, пауза) или медиана удержания при flat
          </p>
        </div>
      ) : null}
      <div className={stack ? 'flex flex-col gap-3' : 'grid gap-3 sm:grid-cols-2'}>
        {metrics.map((m) => (
          <DistributionChart key={m.id} metric={m} compact={stack} />
        ))}
      </div>
    </div>
  )
}
