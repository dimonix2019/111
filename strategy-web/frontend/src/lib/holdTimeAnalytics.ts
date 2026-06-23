import type { Trade } from '@/types'
import {
  formatPct,
  HOLD_DURATION_ORDER,
  holdDurationBucket,
  tradeHoldHours,
} from '@/lib/tradeDistribution'

function median(values: number[]): number {
  if (!values.length) return 0
  const sorted = [...values].sort((a, b) => a - b)
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 === 1 ? sorted[mid]! : (sorted[mid - 1]! + sorted[mid]!) / 2
}

function avg(values: number[]): number {
  if (!values.length) return 0
  return values.reduce((s, v) => s + v, 0) / values.length
}

/** Человекочитаемая длительность для ответов модели. */
export function formatHoldDuration(hours: number): string {
  if (!Number.isFinite(hours) || hours <= 0) return '0 мин'
  if (hours < 1) return `${Math.round(hours * 60)} мин`
  if (hours < 24) return `${hours.toFixed(1)} ч`
  const days = hours / 24
  return `${days.toFixed(1)} д (${hours.toFixed(0)} ч)`
}

type HoldRow = { trade: Trade; hours: number }

function validHoldRows(trades: Trade[]): HoldRow[] {
  const out: HoldRow[] = []
  for (const trade of trades) {
    const hours = tradeHoldHours(trade)
    if (hours > 0 && Number.isFinite(hours)) out.push({ trade, hours })
  }
  return out
}

function pctWithin(hoursList: number[], limitH: number): number {
  if (!hoursList.length) return 0
  return (100 * hoursList.filter((h) => h < limitH).length) / hoursList.length
}

/**
 * Факты про длительность удержания — для контекста LLM и ответов на вопросы
 * «через сколько в плюс», «как долго держим убыток» и т.п.
 */
export function buildHoldTimeContextLines(trades: Trade[]): string[] {
  const rows = validHoldRows(trades)
  if (!rows.length) return []

  const allHours = rows.map((r) => r.hours)
  const winners = rows.filter((r) => r.trade.pnl_rub > 0)
  const losers = rows.filter((r) => r.trade.pnl_rub <= 0)
  const winHours = winners.map((r) => r.hours)
  const lossHours = losers.map((r) => r.hours)

  const lines: string[] = [
    '',
    'Длительность удержания (entry → exit, по закрытым сделкам):',
    `- Все сделки: n=${rows.length}, ср. ${formatHoldDuration(avg(allHours))}, медиана ${formatHoldDuration(median(allHours))}`,
  ]

  if (winners.length) {
    lines.push(
      `- Прибыльные (PnL>0): n=${winners.length}, ср. ${formatHoldDuration(avg(winHours))}, медиана ${formatHoldDuration(median(winHours))}`,
      `  Доля прибыльных, закрытых быстрее: <4 ч — ${pctWithin(winHours, 4).toFixed(1)}%, <12 ч — ${pctWithin(winHours, 12).toFixed(1)}%, <24 ч — ${pctWithin(winHours, 24).toFixed(1)}%`,
    )
  }

  if (losers.length) {
    lines.push(
      `- Убыточные (PnL≤0): n=${losers.length}, ср. ${formatHoldDuration(avg(lossHours))}, медиана ${formatHoldDuration(median(lossHours))}`,
    )
  }

  if (winners.length && losers.length) {
    const diff = avg(winHours) - avg(lossHours)
    lines.push(
      `- Сравнение: прибыльные в среднем ${diff >= 0 ? 'дольше' : 'короче'} убыточных на ${formatHoldDuration(Math.abs(diff))}`,
    )
  }

  lines.push('', 'Распределение по длительности (интервал · сделок · win rate · Σ PnL):')
  for (const bucket of HOLD_DURATION_ORDER) {
    const inBucket = rows.filter((r) => holdDurationBucket(r.hours) === bucket)
    if (!inBucket.length) continue
    const wins = inBucket.filter((r) => r.trade.pnl_rub > 0).length
    const pnl = inBucket.reduce((s, r) => s + r.trade.pnl_rub, 0)
    const wr = (100 * wins) / inBucket.length
    lines.push(
      `- ${bucket}: ${inBucket.length} (${formatPct(inBucket.length, rows.length)}), win ${wr.toFixed(1)}%, Σ ${Math.round(pnl).toLocaleString('ru-RU')} ₽`,
    )
  }

  lines.push(
    '',
    'Примечание: «время выхода в плюс» = длительность прибыльной сделки до закрытия (intra-trade путь не моделируется).',
  )

  return lines
}
