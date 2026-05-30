import { useEffect, useMemo, useRef, useState } from 'react'

import {
  CandlestickSeries,
  ColorType,
  createChart,
  createSeriesMarkers,
  type IChartApi,
  type ISeriesApi,
  type ISeriesMarkersPluginApi,
  type SeriesMarker,
  type Time,
  type UTCTimestamp,
} from 'lightweight-charts'

import type { Trade, TradeMarker } from '@/types'
import { buildTradeByNo } from '@/lib/chartTradeTooltip'
import { legsCompactLabel } from '@/lib/spreadLegs'

import {
  applyChartRightMargin,
  chartPriceFormat,
  chartRightPriceScaleOptions,
  chartTimeScaleOptions,
} from '@/lib/format'
import { syncCandleSeriesLive } from '@/lib/chartLiveUpdate'
import { buildZScoreCandlesFromPoints, type ZScoreBar } from '@/lib/zScoreCandles'
import { EMPTY_HLINES, type HLine } from '@/components/charts/TimeSeriesChart'
import { attachChartTradeTooltip, type ChartTradeTooltipView } from '@/lib/chartTradeTooltip'
import { useChartKeyboard } from '@/hooks/useChartKeyboard'
import { ChartTradeTooltip } from '@/components/charts/ChartTradeTooltip'

type Props = {
  title: string
  data: ZScoreBar[]
  height?: number
  hlines?: HLine[]
  markers?: TradeMarker[]
  trades?: Trade[]
  liveMode?: boolean
}

function toMarkers(
  markers: TradeMarker[] | undefined,
  trades: Trade[] | undefined,
): SeriesMarker<Time>[] {
  if (!markers?.length) return []
  const tradeByNo = trades?.length ? buildTradeByNo(trades) : new Map<number, Trade>()
  return markers
    .map((m) => {
      const isEntry = m.event === 'вход'
      const trade = tradeByNo.get(m.trade_no)
      const legsHint = trade ? legsCompactLabel(trade) : ''
      return {
        time: m.time as UTCTimestamp,
        position: isEntry ? (m.direction === 'LONG' ? 'belowBar' : 'aboveBar') : 'inBar',
        color: m.marker_color || (isEntry ? '#27d17f' : '#f0b93a'),
        shape: isEntry ? (m.direction === 'LONG' ? 'arrowUp' : 'arrowDown') : 'circle',
        text: isEntry && legsHint ? `#${m.trade_no} ${legsHint}` : `#${m.trade_no}`,
      } as SeriesMarker<Time>
    })
    .sort((a, b) => (a.time as number) - (b.time as number))
}

export function ZScoreCandlestickChart({
  title,
  data,
  height = 228,
  hlines = EMPTY_HLINES,
  markers,
  trades,
  liveMode = false,
}: Props) {
  const ref = useRef<HTMLDivElement>(null)
  const keyboardRootRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const seriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const markersPluginRef = useRef<ISeriesMarkersPluginApi<Time> | null>(null)
  const fittedRef = useRef(false)
  const prevLenRef = useRef(0)
  const prevLastKeyRef = useRef<string | null>(null)
  const userInteractedRef = useRef(false)
  const liveRefs = useMemo(
    () => ({
      prevLen: prevLenRef,
      prevLastKey: prevLastKeyRef,
      userInteracted: userInteractedRef,
    }),
    [],
  )
  const [tooltip, setTooltip] = useState<ChartTradeTooltipView | null>(null)
  const candles = useMemo(() => buildZScoreCandlesFromPoints(data), [data])
  const hlinesKey = useMemo(() => JSON.stringify(hlines), [hlines])
  const candleData = useMemo(
    () =>
      candles.map((c) => ({
        time: c.time as UTCTimestamp,
        open: c.open,
        high: c.high,
        low: c.low,
        close: c.close,
      })),
    [candles],
  )
  const markersKey = useMemo(() => JSON.stringify(markers ?? []), [markers])

  useChartKeyboard(keyboardRootRef, chartRef, candleData.length, userInteractedRef)

  useEffect(() => {
    const el = ref.current
    if (!el) return

    const priceFmt = chartPriceFormat('float')
    const chart = createChart(el, {
      height,
      layout: {
        background: { type: ColorType.Solid, color: 'transparent' },
        textColor: 'rgba(186, 196, 210, 0.75)',
        fontFamily: 'Inter, system-ui, sans-serif',
      },
      grid: {
        vertLines: { color: 'rgba(255,255,255,0.04)' },
        horzLines: { color: 'rgba(255,255,255,0.04)' },
      },
      rightPriceScale: chartRightPriceScaleOptions(),
      timeScale: { borderColor: 'rgba(255,255,255,0.06)', ...chartTimeScaleOptions() },
      localization: priceFmt.localization,
    })

    const series = chart.addSeries(CandlestickSeries, {
      upColor: 'rgba(39, 209, 127, 0.85)',
      downColor: 'rgba(232, 93, 106, 0.85)',
      borderVisible: false,
      wickUpColor: 'rgba(39, 209, 127, 0.55)',
      wickDownColor: 'rgba(232, 93, 106, 0.55)',
      priceFormat: priceFmt.seriesPriceFormat,
    })

    for (const hl of hlines) {
      series.createPriceLine({
        price: hl.value,
        color: hl.color,
        lineWidth: 1,
        lineStyle: 2,
        title: hl.title,
      })
    }

    chartRef.current = chart
    seriesRef.current = series
    fittedRef.current = false
    prevLenRef.current = 0
    prevLastKeyRef.current = null
    userInteractedRef.current = false

    const onRange = () => {
      userInteractedRef.current = true
    }
    chart.timeScale().subscribeVisibleLogicalRangeChange(onRange)

    const ro = new ResizeObserver(() => chart.applyOptions({ width: el.clientWidth }))
    ro.observe(el)

    return () => {
      chart.timeScale().unsubscribeVisibleLogicalRangeChange(onRange)
      ro.disconnect()
      markersPluginRef.current?.detach()
      markersPluginRef.current = null
      chart.remove()
      chartRef.current = null
      seriesRef.current = null
    }
  }, [height, hlinesKey, layoutHeight])

  useEffect(() => {
    const series = seriesRef.current
    const chart = chartRef.current
    if (!series || !chart || !candleData.length) return

    syncCandleSeriesLive(series, chart, candleData, liveMode, fittedRef.current, liveRefs)

    if (!fittedRef.current) {
      applyChartRightMargin(chart.timeScale())
      fittedRef.current = true
    }
  }, [candleData, liveMode, liveRefs, hlinesKey])

  useEffect(() => {
    const series = seriesRef.current
    if (!series) return
    const markerData = toTradeSeriesMarkers(markers, trades)
    markersPluginRef.current?.detach()
    markersPluginRef.current = markerData.length ? createSeriesMarkers(series, markerData) : null
  }, [markersKey, markers, trades, hlinesKey])

  useEffect(() => {
    const chart = chartRef.current
    const series = seriesRef.current
    const el = ref.current
    if (!chart || !series || !el) return
    const detach = attachChartTradeTooltip(chart, el, series, markers, trades, setTooltip, {
      preferPixelHit: true,
    })
    return () => detach?.()
  }, [markersKey, markers, trades, hlinesKey])

  if (!candles.length) {
    return (
      <div className="rounded-xl2 border border-panel-border-soft bg-[rgba(8,16,28,0.4)] p-3">
        <div className="mb-2 text-[13px] font-semibold text-ink-2">{title}</div>
        <p className="text-[12px] text-ink-3">Нет данных для графика</p>
      </div>
    )
  }

  return (
    <div className="rounded-xl2 border border-panel-border-soft bg-[rgba(8,16,28,0.4)] p-3">
      <div className="mb-2 text-[13px] font-semibold text-ink-2">{title}</div>
      <div className="relative w-full">
        <div ref={ref} className="w-full" />
        <ChartTradeTooltip view={tooltip} />
      </div>
    </div>
  )
}
