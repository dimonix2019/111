import { useMemo, useState } from 'react'
import type { Trade } from '@/types'
import { HistogramBars } from '@/components/charts/HistogramBars'
import { Panel } from '@/components/ui/Panel'
import {
  buildTradeHistogram,
  DISTRIBUTION_BY_ID,
  distributionGroups,
  isTimeOfDayDistribution,
  TIME_GRANULARITY_OPTIONS,
  type DistributionDef,
  type TimeGranularityMin,
} from '@/lib/tradeDistribution'
import { buildGeneralTradeInsights, buildMetricInsights } from '@/lib/tradeInsights'

const QUICK_IDS = ['entry_hour', 'exit_hour', 'pnl_outcome', 'hold_hours', 'entry_z', 'pnl_rub'] as const

type Props = {
  trades: Trade[]
  totalCount: number
}

function DistributionChart({
  trades,
  def,
  timeGranularityMin,
}: {
  trades: Trade[]
  def: DistributionDef
  timeGranularityMin: TimeGranularityMin
}) {
  const isTime = isTimeOfDayDistribution(def.id)
  const bars = useMemo(
    () =>
      buildTradeHistogram(trades, def, {
        timeGranularityMin: isTime ? timeGranularityMin : undefined,
      }),
    [trades, def, isTime, timeGranularityMin],
  )
  const summary = def.summary?.(trades) ?? `${trades.length} сделок`
  const insights = useMemo(() => {
    const metric = buildMetricInsights(trades, def.id, timeGranularityMin)
    if (metric.length) return metric
    return buildGeneralTradeInsights(trades, timeGranularityMin).slice(0, 4)
  }, [trades, def.id, timeGranularityMin])

  return (
    <div className="space-y-2">
      <p className="text-[11px] leading-snug text-ink-3">{summary}</p>
      {insights.length > 0 ? (
        <ul className="space-y-0.5 text-[10px] leading-snug text-ink-3">
          {insights.map((line) => (
            <li key={line}>• {line}</li>
          ))}
        </ul>
      ) : null}
      {bars.length === 0 ? (
        <p className="text-[12px] text-ink-3">Нет данных для распределения</p>
      ) : (
        <HistogramBars
          bars={bars}
          totalCount={trades.length}
          chartHeight={isTime && timeGranularityMin < 60 ? 100 : 120}
          scrollable={isTime && timeGranularityMin < 60}
        />
      )}
    </div>
  )
}

export function TradeDistributionPanel({ trades, totalCount }: Props) {
  const [primaryId, setPrimaryId] = useState<string>('entry_hour')
  const [compareId, setCompareId] = useState<string>('')
  const [timeGranularityMin, setTimeGranularityMin] = useState<TimeGranularityMin>(15)
  const groups = useMemo(() => distributionGroups(), [])

  const primary = DISTRIBUTION_BY_ID.get(primaryId) ?? DISTRIBUTION_BY_ID.get('entry_hour')!
  const compare = compareId ? DISTRIBUTION_BY_ID.get(compareId) : undefined
  const showTimeGranularity = isTimeOfDayDistribution(primaryId) || (compareId !== '' && isTimeOfDayDistribution(compareId))

  const filteredNote =
    trades.length !== totalCount ? (
      <span className="text-warn">
        по фильтрам таблицы ({trades.length} из {totalCount})
      </span>
    ) : (
      <span>все {totalCount} сделок</span>
    )

  return (
    <Panel title="Распределения">
      <p className="mb-3 text-[11px] text-ink-3">Гистограмма по выбранному показателю · {filteredNote}</p>

      <div className="mb-3 flex flex-wrap gap-1.5">
        {QUICK_IDS.map((id) => {
          const d = DISTRIBUTION_BY_ID.get(id)!
          const active = primaryId === id
          return (
            <button
              key={id}
              type="button"
              className={`tab-btn !px-2 !py-1 text-[10px] ${active ? 'active' : ''}`}
              onClick={() => setPrimaryId(id)}
              title={d.hint ?? d.label}
            >
              {d.label}
            </button>
          )
        })}
      </div>

      <div className={`grid gap-3 ${showTimeGranularity ? 'md:grid-cols-3' : 'md:grid-cols-2'}`}>
        <label className="block text-[12px] font-medium text-ink-3">
          Показатель
          <select value={primaryId} onChange={(e) => setPrimaryId(e.target.value)} className="mt-1 w-full">
            {groups.map(({ group, items }) => (
              <optgroup key={group} label={group}>
                {items.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.label}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>
        </label>
        {showTimeGranularity ? (
          <label className="block text-[12px] font-medium text-ink-3">
            Шаг времени
            <select
              value={timeGranularityMin}
              onChange={(e) => setTimeGranularityMin(Number(e.target.value) as TimeGranularityMin)}
              className="mt-1 w-full"
            >
              {TIME_GRANULARITY_OPTIONS.map((o) => (
                <option key={o.value} value={o.value} title={o.hint}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        <label className="block text-[12px] font-medium text-ink-3">
          Сравнить с (опционально)
          <select value={compareId} onChange={(e) => setCompareId(e.target.value)} className="mt-1 w-full">
            <option value="">— не сравнивать —</option>
            {groups.map(({ group, items }) => (
              <optgroup key={group} label={group}>
                {items.map((d) => (
                  <option key={d.id} value={d.id} disabled={d.id === primaryId}>
                    {d.label}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>
        </label>
      </div>

      {showTimeGranularity ? (
        <p className="mt-2 text-[10px] text-ink-3">
          Прокрутите график влево/вправо · зелёный столбец — пик · проверка гипотезы: входы 07:00–08:00, выходы 17:00–22:00
        </p>
      ) : null}

      <div className={`mt-4 grid gap-4 ${compare ? 'lg:grid-cols-2' : ''}`}>
        <div>
          <div className="mb-2 text-[12px] font-semibold text-ink-2">{primary.label}</div>
          <DistributionChart
            trades={trades}
            def={primary}
            timeGranularityMin={timeGranularityMin}
          />
        </div>
        {compare ? (
          <div>
            <div className="mb-2 text-[12px] font-semibold text-ink-2">{compare.label}</div>
            <DistributionChart trades={trades} def={compare} timeGranularityMin={timeGranularityMin} />
          </div>
        ) : null}
      </div>
    </Panel>
  )
}

