import type { SimPack, Trade } from '@/types'
import { buildMetricInsights } from '@/lib/tradeInsights'
import {
  buildTradeHistogram,
  DISTRIBUTION_BY_ID,
  formatPct,
  isTimeOfDayDistribution,
  type TimeGranularityMin,
} from '@/lib/tradeDistribution'

function topHistogramLines(
  trades: Trade[],
  metricId: string,
  timeGranularityMin: TimeGranularityMin,
  limit = 12,
): string[] {
  const def = DISTRIBUTION_BY_ID.get(metricId)
  if (!def || !trades.length) return []
  const bars = buildTradeHistogram(trades, def, {
    timeGranularityMin: isTimeOfDayDistribution(metricId) ? timeGranularityMin : undefined,
  })
  const total = trades.length
  return [...bars]
    .filter((b) => b.count > 0)
    .sort((a, b) => b.count - a.count)
    .slice(0, limit)
    .map((b) => `${b.bucket}: ${b.count} (${formatPct(b.count, total)})`)
}

export function buildAnalyticsContext(opts: {
  trades: Trade[]
  totalCount: number
  metricId: string
  metricLabel: string
  timeGranularityMin: TimeGranularityMin
  pack?: SimPack
}): string {
  const { trades, totalCount, metricId, metricLabel, timeGranularityMin, pack } = opts
  const lines: string[] = []

  lines.push(`Показатель гистограммы: ${metricLabel} (${metricId})`)
  lines.push(`Сделок в выборке: ${trades.length}${trades.length !== totalCount ? ` (из ${totalCount} после фильтров)` : ''}`)

  if (pack) {
    const s = pack.stats
    lines.push(
      `Параметры стратегии: Entry Z=${pack.entry}, Exit Z=${pack.exit_z}`,
      `Итоги симуляции: ${s.trade_count} сделок, PnL ${Math.round(s.total_pnl_rub).toLocaleString('ru-RU')} ₽, win rate ${s.win_rate_pct.toFixed(1)}%, PF ${s.profit_factor?.toFixed(2) ?? '∞'}, max DD ${Math.round(s.max_drawdown_rub).toLocaleString('ru-RU')} ₽`,
      `LONG PnL ${Math.round(s.long_pnl_rub).toLocaleString('ru-RU')} ₽, SHORT ${Math.round(s.short_pnl_rub).toLocaleString('ru-RU')} ₽, ср. удержание ${s.avg_hold_hours.toFixed(1)} ч`,
    )
  }

  if (isTimeOfDayDistribution(metricId)) {
    lines.push(`Шаг времени гистограммы: ${timeGranularityMin} мин`)
  }

  const ruleInsights = buildMetricInsights(trades, metricId, timeGranularityMin)
  if (ruleInsights.length) {
    lines.push('', 'Правила аналитики (факты):')
    for (const line of ruleInsights) lines.push(`- ${line}`)
  }

  const hist = topHistogramLines(trades, metricId, timeGranularityMin)
  if (hist.length) {
    lines.push('', 'Топ интервалов гистограммы:')
    for (const line of hist) lines.push(`- ${line}`)
  }

  return lines.join('\n')
}
