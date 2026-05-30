import type { Time } from 'lightweight-charts'

export type ChartValueFormat = 'rub' | 'float'

const MSK_TZ = 'Europe/Moscow'

const MSK_MONTHS = ['янв', 'фев', 'мар', 'апр', 'май', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'] as const

/**
 * Время из CSV / hq.last_ts без сдвига: строка уже в MSK («2026-05-26 14:30:00»).
 * Не использовать для старых unix из pack до перезапуска API.
 */
export function formatMoexWallTime(ts: string | undefined | null): string | null {
  if (!ts?.trim()) return null
  const normalized = ts.trim().replace('T', ' ')
  const m = normalized.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/)
  if (!m) return normalized.slice(0, 16)
  const y = m[1]!
  const mo = parseInt(m[2]!, 10)
  const d = parseInt(m[3]!, 10)
  const h = m[4]!
  const mi = m[5]!
  const mon = MSK_MONTHS[mo - 1] ?? m[2]
  return `${d} ${mon} ${y.slice(2)}, ${h}:${mi}`
}

/** Подпись crosshair и ось времени: дата + время MSK (как MOEX). */
export function formatChartTime(time: Time): string {
  if (typeof time === 'number') {
    const d = new Date(time * 1000)
    return d.toLocaleString('ru-RU', {
      timeZone: MSK_TZ,
      day: 'numeric',
      month: 'short',
      year: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    })
  }
  if (typeof time === 'object' && time !== null && 'year' in time) {
    const t = time as { year: number; month: number; day: number }
    return new Date(Date.UTC(t.year, t.month - 1, t.day)).toLocaleDateString('ru-RU', {
      day: 'numeric',
      month: 'short',
      year: '2-digit',
    })
  }
  return ''
}

/** Пустое место справа от последней точки (сетка и пороги Entry/Exit не сдвигаются). */
export const CHART_RIGHT_OFFSET_BARS = 14
export const CHART_RIGHT_OFFSET_PX = 112

export function chartTimeScaleOptions() {
  return {
    timeVisible: true,
    secondsVisible: false,
    rightOffset: CHART_RIGHT_OFFSET_BARS,
    rightOffsetPixels: CHART_RIGHT_OFFSET_PX,
  }
}

/** Место под подписи «+Entry 0.8», «+Exit 0.7» на правой шкале. */
export function chartRightPriceScaleOptions() {
  return {
    borderColor: 'rgba(255,255,255,0.06)',
    minimumWidth: 96,
    scaleMargins: { top: 0.08, bottom: 0.08 },
  }
}

/** После fitContent сохранить отступ справа. */
export function applyChartRightMargin(timeScale: { fitContent: () => void; applyOptions: (o: object) => void }) {
  timeScale.fitContent()
  timeScale.applyOptions({
    rightOffset: CHART_RIGHT_OFFSET_BARS,
    rightOffsetPixels: CHART_RIGHT_OFFSET_PX,
  })
}

/** Ось графика: ₽ — целые с разделителем тысяч, float — до 2 знаков без лишних нулей. */
export function fmtChartAxis(value: number, format: ChartValueFormat = 'rub'): string {
  if (format === 'rub') {
    return Math.round(value).toLocaleString('ru-RU', { maximumFractionDigits: 0 })
  }
  const abs = Math.abs(value)
  const digits = abs >= 10 ? 1 : abs >= 1 ? 2 : 3
  return value.toLocaleString('ru-RU', {
    minimumFractionDigits: 0,
    maximumFractionDigits: digits,
  })
}

export function chartPriceFormat(format: ChartValueFormat) {
  const formatter = (price: number) => fmtChartAxis(price, format)
  return {
    localization: {
      timeFormatter: formatChartTime,
      priceFormatter: formatter,
      tickmarksPriceFormatter: (prices: number[]) => prices.map(formatter),
    },
    seriesPriceFormat: {
      type: 'custom' as const,
      formatter,
    },
  }
}
