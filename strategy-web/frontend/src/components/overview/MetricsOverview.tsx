import type { ReactNode } from 'react'
import type { Stats } from '@/types'
import { fmtPf, fmtRub } from '@/lib/api'

type MetricTone = 'neutral' | 'profit' | 'cost' | 'activity'

function MetricCard({
  label,
  value,
  tone = 'neutral',
  hint,
}: {
  label: string
  value: string
  tone?: MetricTone
  hint?: string
}) {
  const styles: Record<MetricTone, string> = {
    neutral: 'surface-inner',
    profit: 'border-good/30 bg-[rgba(16,185,129,0.08)]',
    cost: 'border-bad/30 bg-[rgba(244,63,94,0.08)]',
    activity: 'border-accent-muted bg-[rgba(59,130,246,0.08)]',
  }
  const valueStyles: Record<MetricTone, string> = {
    neutral: 'text-ink-1',
    profit: 'text-good',
    cost: 'text-bad',
    activity: 'text-[rgba(180,210,220,0.95)]',
  }

  return (
    <div className={`rounded-xl px-3 py-2 ${styles[tone]}`} title={hint}>
      <div className="text-[11px] font-medium text-ink-3">{label}</div>
      <div className={`text-[14px] font-semibold tabular-nums ${valueStyles[tone]}`}>{value}</div>
    </div>
  )
}

function MetricSection({
  title,
  subtitle,
  children,
}: {
  title: string
  subtitle?: string
  children: ReactNode
}) {
  return (
    <section className="overflow-hidden rounded-2xl border border-surface-border bg-[linear-gradient(165deg,rgba(17,28,50,0.72),rgba(13,20,37,0.82))] p-4 shadow-panel">
      <div className="mb-2.5">
        <h3 className="text-[12px] font-semibold tracking-wide text-ink-2">{title}</h3>
        {subtitle ? <p className="mt-0.5 text-[10px] text-ink-3">{subtitle}</p> : null}
      </div>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5">{children}</div>
    </section>
  )
}

function pnlTone(v: number): MetricTone {
  return v >= 0 ? 'profit' : 'cost'
}

export function MetricsOverview({ stats, unrealizedRub }: { stats: Stats; unrealizedRub?: number }) {
  const hasUnrealized = unrealizedRub != null && unrealizedRub !== 0

  return (
    <div className="space-y-3">
      <MetricSection title="Прибыль и эффективность" subtitle="Итог и качество сделок">
        <MetricCard label="PnL итого" value={fmtRub(stats.total_pnl_rub)} tone={pnlTone(stats.total_pnl_rub)} />
        <MetricCard
          label="Win Rate"
          value={`${stats.win_rate_pct.toFixed(1)}%`}
          tone={stats.win_rate_pct >= 50 ? 'profit' : 'cost'}
        />
        <MetricCard
          label="Profit Factor"
          value={fmtPf(stats.profit_factor)}
          tone={
            stats.profit_factor == null || stats.profit_factor >= 1.2
              ? 'profit'
              : stats.profit_factor >= 1
                ? 'neutral'
                : 'cost'
          }
        />
        <MetricCard label="Long" value={fmtRub(stats.long_pnl_rub)} tone="profit" />
        <MetricCard label="Short" value={fmtRub(stats.short_pnl_rub)} tone="profit" />
        <MetricCard
          label="Реализовано"
          value={fmtRub(stats.realized_pnl_rub)}
          tone={pnlTone(stats.realized_pnl_rub)}
          hint="PnL закрытых сделок"
        />
        {hasUnrealized ? (
          <MetricCard
            label="Нереализовано"
            value={fmtRub(unrealizedRub!)}
            tone={pnlTone(unrealizedRub!)}
            hint="Открытая позиция на конец ряда"
          />
        ) : null}
      </MetricSection>

      <MetricSection title="Издержки и просадка" subtitle="Всё, что уменьшает капитал">
        <MetricCard
          label="Max DD"
          value={fmtRub(stats.max_drawdown_rub)}
          tone="cost"
          hint="Максимальная просадка от пика equity"
        />
        <MetricCard label="Комиссии" value={fmtRub(stats.total_commission_rub)} tone="cost" />
        <MetricCard label="Overnight" value={fmtRub(stats.total_overnight_rub)} tone="cost" />
      </MetricSection>

      <MetricSection title="Торговая активность" subtitle="Сколько и как долго торговали">
        <MetricCard label="Сделок" value={String(stats.trade_count)} tone="activity" />
        <MetricCard label="Ср. удержание" value={`${stats.avg_hold_hours.toFixed(1)} ч`} tone="activity" />
      </MetricSection>
    </div>
  )
}
