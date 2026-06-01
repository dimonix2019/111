import { LineSeries, LineStyle, type IChartApi, type ISeriesApi, type UTCTimestamp } from 'lightweight-charts'

import { prepareLineData } from '@/lib/chartData'
import { tradingViewLineSeriesOptions, TV } from '@/lib/chartTheme'
import { BAR_15M_SEC } from '@/lib/chartTradeTooltip'

export type EquityPoint = { time: number; equity_rub: number; drawdown_rub: number }

/** Просадка ≤ 0 (от нуля вниз). */
export function drawdownLinePoints(equity: EquityPoint[]): { time: number; value: number }[] {
  if (!equity.length) return []
  return prepareLineData(
    equity.map((e) => ({
      time: e.time,
      value: Math.min(0, Number(e.drawdown_rub) || 0),
    })),
  )
}

export function lookupDrawdownAtTime(equity: EquityPoint[], timeSec: number): number {
  if (!equity.length) return 0
  let best: EquityPoint | null = null
  let bestDelta = BAR_15M_SEC + 1
  for (const p of equity) {
    const d = Math.abs(p.time - timeSec)
    if (d <= BAR_15M_SEC && d < bestDelta) {
      bestDelta = d
      best = p
    }
  }
  if (!best) return 0
  return Math.min(0, Number(best.drawdown_rub) || 0)
}

export const DRAWDOWN_SCALE_ID = 'left'

export type DrawdownOverlayVariant = 'market' | 'combined'

export function configureDrawdownPriceScales(chart: IChartApi, variant: DrawdownOverlayVariant): void {
  /** Z-score / equity ≈ 2/3 высоты, drawdown ≈ 1/3 (соотношение 2:1). */
  const ddFraction = variant === 'combined' ? 0.28 : 1 / 3
  const mainTop = 0.03
  const mainBottom = ddFraction + 0.02
  const ddTop = 1 - ddFraction + 0.01

  chart.priceScale('right').applyOptions({
    scaleMargins: { top: mainTop, bottom: mainBottom },
  })
  chart.priceScale(DRAWDOWN_SCALE_ID).applyOptions({
    visible: true,
    autoScale: true,
    scaleMargins: { top: ddTop, bottom: 0.02 },
    borderVisible: true,
    borderColor: TV.border,
    minimumWidth: 56,
  })
}

export function attachDrawdownLineSeries(
  chart: IChartApi,
  variant: DrawdownOverlayVariant = 'market',
): ISeriesApi<'Line'> {
  const combined = variant === 'combined'
  const series = chart.addSeries(LineSeries, {
    ...tradingViewLineSeriesOptions(TV.down, 'rub'),
    priceScaleId: DRAWDOWN_SCALE_ID,
    title: combined ? 'Просадка' : 'DD',
    color: TV.down,
    lineWidth: 1,
    lineStyle: LineStyle.Dashed,
    lastValueVisible: true,
    priceLineVisible: false,
  })
  configureDrawdownPriceScales(chart, variant)
  series.createPriceLine({
    price: 0,
    color: TV.textMuted,
    lineWidth: 2,
    lineStyle: LineStyle.Solid,
    axisLabelVisible: true,
    title: '0',
  })
  return series
}

export function setDrawdownSeriesData(
  series: ISeriesApi<'Line'>,
  equity: EquityPoint[] | undefined,
): void {
  const pts = drawdownLinePoints(equity ?? [])
  series.setData(pts.map((p) => ({ time: p.time as UTCTimestamp, value: p.value })))
}

/** Сигнатура для live-обновления без JSON.stringify всего ряда. */
export function equitySeriesSignature(equity: EquityPoint[] | undefined): string {
  if (!equity?.length) return '0'
  const last = equity[equity.length - 1]!
  return `${equity.length}:${last.time}:${last.drawdown_rub}:${last.equity_rub}`
}
