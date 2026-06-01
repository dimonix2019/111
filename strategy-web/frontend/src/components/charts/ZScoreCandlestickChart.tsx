import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import {
  CandlestickSeries,
  createChart,
  createSeriesMarkers,
  type IChartApi,
  type ISeriesApi,
  type ISeriesMarkersPluginApi,
  type Time,
  type UTCTimestamp,
} from 'lightweight-charts'

import type { Trade, TradeMarker } from '@/types'
import { ChartPanel } from '@/components/charts/ChartPanel'
import { ChartTradeTooltip } from '@/components/charts/ChartTradeTooltip'
import { syncCandleSeriesLive } from '@/lib/chartLiveUpdate'
import { attachChartTradeTooltip, type ChartTradeTooltipPersist, type ChartTradeTooltipView } from '@/lib/chartTradeTooltip'
import {
  attachDrawdownLineSeries,
  equitySeriesSignature,
  setDrawdownSeriesData,
  type EquityPoint,
} from '@/lib/drawdownOverlay'
import { buildZScoreCandlesFromPoints, type ZScoreBar } from '@/lib/zScoreCandles'
import { EMPTY_HLINES, type HLine } from '@/components/charts/TimeSeriesChart'
import {
  tradingViewCandleSeriesOptions,
  tradingViewChartOptions,
  tradingViewPriceLineOptions,
  toTradeSeriesMarkers,
} from '@/lib/chartTheme'
import { applyChartRightMargin } from '@/lib/format'
type Props = {
  title: string
  data: ZScoreBar[]
  height?: number
  hlines?: HLine[]
  markers?: TradeMarker[]
  trades?: Trade[]
  equity?: EquityPoint[]
  liveMode?: boolean
  /** Кривая drawdown внизу (только вкладка Рынок). */
  showDrawdownOverlay?: boolean
}

export function ZScoreCandlestickChart({
  title,
  data,
  height = 228,
  hlines = EMPTY_HLINES,
  markers,
  trades,
  equity,
  liveMode = false,
  showDrawdownOverlay = false,
}: Props) {
  const ref = useRef<HTMLDivElement>(null)
  const keyboardRootRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const seriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const ddSeriesRef = useRef<ISeriesApi<'Line'> | null>(null)
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
  const tooltipPersistRef = useRef<ChartTradeTooltipPersist>({ pinnedKey: null })
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
  const equityRef = useRef(equity)
  equityRef.current = equity
  const equitySig = useMemo(() => equitySeriesSignature(equity), [equity])
  const initialHeightRef = useRef(height)
  const getEquity = useCallback(() => equityRef.current, [])
  const wantDrawdownOverlay = showDrawdownOverlay === true && !!equity?.length

  useEffect(() => {
    const el = ref.current
    if (!el || !candles.length) return

    const chart = createChart(el, {
      width: Math.max(el.clientWidth, 100),
      height: Math.max(el.clientHeight, initialHeightRef.current),
      ...tradingViewChartOptions('float', showDrawdownOverlay),
    })

    const series = chart.addSeries(CandlestickSeries, tradingViewCandleSeriesOptions())
    for (const hl of hlines) {
      series.createPriceLine(tradingViewPriceLineOptions(hl))
    }

    let ddSeries: ISeriesApi<'Line'> | null = null
    if (showDrawdownOverlay) {
      ddSeries = attachDrawdownLineSeries(chart)
      if (equityRef.current?.length) {
        setDrawdownSeriesData(ddSeries, equityRef.current)
      }
    }

    chartRef.current = chart
    seriesRef.current = series
    ddSeriesRef.current = ddSeries
    fittedRef.current = false
    prevLenRef.current = 0
    prevLastKeyRef.current = null
    userInteractedRef.current = false

    const onRange = () => {
      userInteractedRef.current = true
    }
    chart.timeScale().subscribeVisibleLogicalRangeChange(onRange)

    const ro = new ResizeObserver(() => {
      const w = el.clientWidth
      const h = el.clientHeight
      if (w > 0 && h > 0) chart.resize(w, h)
      else if (w > 0) chart.applyOptions({ width: w })
    })
    ro.observe(el)

    return () => {
      chart.timeScale().unsubscribeVisibleLogicalRangeChange(onRange)
      ro.disconnect()
      markersPluginRef.current?.detach()
      markersPluginRef.current = null
      chart.remove()
      chartRef.current = null
      seriesRef.current = null
      ddSeriesRef.current = null
    }
  }, [hlinesKey, candles.length, showDrawdownOverlay])

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
    const dd = ddSeriesRef.current
    if (!dd || !wantDrawdownOverlay) return
    setDrawdownSeriesData(dd, equity)
  }, [equitySig, equity, wantDrawdownOverlay])

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
    if (!chart || !series) return
    const detach = attachChartTradeTooltip(chart, series, markers, trades, setTooltip, {
      preferPixelHit: true,
    }, getEquity, tooltipPersistRef)
    return () => detach?.()
  }, [markersKey, trades, getEquity])

  return (
    <ChartPanel
      title={title}
      defaultHeight={height}
      chartRef={chartRef}
      keyboardRootRef={keyboardRootRef}
      containerRef={ref}
      empty={!candles.length}
      barCount={candleData.length}
      onUserInteract={() => {
        userInteractedRef.current = true
      }}
    >
      {({ fullscreen }) => (
        <div
          className="chart-canvas-host relative overflow-hidden px-1"
          style={
            fullscreen
              ? { flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }
              : { height, minHeight: height }
          }
        >
          <div ref={ref} className="chart-canvas-slot w-full" style={fullscreen ? { flex: 1, minHeight: 0 } : undefined} />
          <ChartTradeTooltip view={tooltip} />
        </div>
      )}
    </ChartPanel>
  )
}
