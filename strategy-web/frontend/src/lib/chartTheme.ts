import {
  ColorType,
  CrosshairMode,
  LineStyle,
  type ChartOptions,
  type DeepPartial,
  type SeriesMarker,
  type Time,
  type UTCTimestamp,
} from 'lightweight-charts'

import type { Trade, TradeMarker } from '@/types'
import { chartPriceFormat, chartTimeScaleOptions, formatChartTickMark, type ChartValueFormat } from '@/lib/format'

/** Палитра TradingView dark (classic). */
export const TV = {
  bg: '#131722',
  grid: '#2a2e39',
  text: '#d1d4dc',
  textMuted: '#787b86',
  border: '#2a2e39',
  crosshair: '#758696',
  crosshairLabel: '#2962FF',
  up: '#089981',
  down: '#f23645',
  lineBlue: '#2962FF',
  lineGreen: '#089981',
  lineOrange: '#f7931a',
  entryLine: '#787b86',
  exitLine: '#434651',
} as const

export type ChartHLine = { value: number; color: string; title: string }

export function chartEntryExitHlines(entry: number, exitZ: number): ChartHLine[] {
  return [
    { value: entry, color: TV.entryLine, title: `+Entry ${entry}` },
    { value: -entry, color: TV.entryLine, title: `-Entry ${entry}` },
    { value: exitZ, color: TV.exitLine, title: `+Exit ${exitZ}` },
    { value: -exitZ, color: TV.exitLine, title: `-Exit ${exitZ}` },
  ]
}

export function tradingViewChartOptions(
  valueFormat: ChartValueFormat = 'float',
  showLeftPriceScale = false,
): DeepPartial<ChartOptions> {
  const priceFmt = chartPriceFormat(valueFormat)
  return {
    layout: {
      background: { type: ColorType.Solid, color: TV.bg },
      textColor: TV.text,
      fontFamily: '-apple-system, BlinkMacSystemFont, "Trebuchet MS", Roboto, Ubuntu, sans-serif',
      fontSize: 12,
      attributionLogo: true,
    },
    grid: {
      vertLines: { color: TV.grid },
      horzLines: { color: TV.grid },
    },
    crosshair: {
      mode: CrosshairMode.Normal,
      vertLine: {
        color: TV.crosshair,
        width: 1,
        style: LineStyle.LargeDashed,
        labelBackgroundColor: TV.crosshairLabel,
      },
      horzLine: {
        color: TV.crosshair,
        width: 1,
        style: LineStyle.LargeDashed,
        labelBackgroundColor: TV.crosshairLabel,
      },
    },
    handleScroll: {
      mouseWheel: false,
      pressedMouseMove: true,
      horzTouchDrag: false,
      vertTouchDrag: false,
    },
    handleScale: {
      axisPressedMouseMove: { time: true, price: true },
      mouseWheel: false,
      pinch: false,
    },
    rightPriceScale: {
      borderColor: TV.border,
      minimumWidth: 72,
      scaleMargins: { top: 0.1, bottom: 0.1 },
    },
    leftPriceScale: {
      visible: showLeftPriceScale,
      borderColor: TV.border,
      minimumWidth: 56,
    },
    timeScale: {
      borderColor: TV.border,
      ...chartTimeScaleOptions(),
      tickMarkFormatter: formatChartTickMark,
    },
    localization: {
      locale: 'ru-RU',
      ...priceFmt.localization,
    },
  }
}

export function tradingViewLineSeriesOptions(color: string, valueFormat: ChartValueFormat) {
  const priceFmt = chartPriceFormat(valueFormat)
  return {
    color,
    lineWidth: 2 as const,
    crosshairMarkerVisible: true,
    crosshairMarkerRadius: 4,
    crosshairMarkerBorderColor: TV.bg,
    crosshairMarkerBackgroundColor: color,
    lastValueVisible: true,
    priceLineVisible: true,
    priceLineWidth: 1 as const,
    priceLineColor: color,
    priceFormat: priceFmt.seriesPriceFormat,
  }
}

export function tradingViewCandleSeriesOptions() {
  const priceFmt = chartPriceFormat('float')
  return {
    upColor: TV.up,
    downColor: TV.down,
    borderUpColor: TV.up,
    borderDownColor: TV.down,
    borderVisible: true,
    wickUpColor: TV.up,
    wickDownColor: TV.down,
    wickVisible: true,
    lastValueVisible: true,
    priceLineVisible: true,
    priceLineWidth: 1 as const,
    priceFormat: priceFmt.seriesPriceFormat,
  }
}

export function tradingViewPriceLineOptions(hl: ChartHLine) {
  return {
    price: hl.value,
    color: hl.color,
    lineWidth: 1 as const,
    lineStyle: LineStyle.Dashed,
    axisLabelVisible: true,
    title: hl.title,
  }
}

export function toTradeSeriesMarkers(
  markers: TradeMarker[] | undefined,
  _trades?: Trade[],
): SeriesMarker<Time>[] {
  if (!markers?.length) return []
  return markers
    .map((m) => {
      const isEntry = m.event === 'вход'
      const entryColor = m.direction === 'LONG' ? TV.up : TV.down
      return {
        time: m.time as UTCTimestamp,
        position: isEntry ? (m.direction === 'LONG' ? 'belowBar' : 'aboveBar') : 'inBar',
        color: m.marker_color || (isEntry ? entryColor : TV.textMuted),
        shape: isEntry ? (m.direction === 'LONG' ? 'arrowUp' : 'arrowDown') : 'circle',
      } as SeriesMarker<Time>
    })
    .sort((a, b) => (a.time as number) - (b.time as number))
}
