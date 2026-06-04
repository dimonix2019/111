import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import {
  createChart,
  createSeriesMarkers,
  LineSeries,
  type IChartApi,
  type ISeriesApi,
  type ISeriesMarkersPluginApi,
  type Time,
  type UTCTimestamp,
} from 'lightweight-charts'

import type { Trade, TradeMarker } from '@/types'
import { ChartPanel } from '@/components/charts/ChartPanel'
import { ChartTradeTooltip } from '@/components/charts/ChartTradeTooltip'
import { prepareLineData } from '@/lib/chartData'
import { syncLineSeriesLive } from '@/lib/chartLiveUpdate'
import { applyChartRightMargin, type ChartValueFormat } from '@/lib/format'
import { attachChartTradeTooltip, type ChartTradeTooltipPersist, type ChartTradeTooltipView } from '@/lib/chartTradeTooltip'
import {
  attachDrawdownLineSeries,
  equitySeriesSignature,
  setDrawdownSeriesData,
  type DrawdownOverlayVariant,
  type EquityPoint,
} from '@/lib/drawdownOverlay'
import {
  tradingViewChartOptions,
  tradingViewLineSeriesOptions,
  tradingViewPriceLineOptions,
  toTradeSeriesMarkers,
} from '@/lib/chartTheme'
export type HLine = { value: number; color: string; title: string }

/** Стабильная ссылка: дефолт `hlines = []` в параметрах ломал useEffect на каждом рендере. */
export const EMPTY_HLINES: HLine[] = []

type Props = {
  title: string
  data: { time: number; value: number }[]
  color?: string
  height?: number
  hlines?: HLine[]
  markers?: TradeMarker[]
  trades?: Trade[]
  equity?: EquityPoint[]
  /** Кривая drawdown внизу (только если явно true). */
  showDrawdownOverlay?: boolean
  /** combined — тонкая пунктирная линия на графике капитала. */
  drawdownOverlayVariant?: DrawdownOverlayVariant
  valueFormat?: ChartValueFormat
  /** Обновление update() каждые 5 с без сброса zoom/pan. */
  liveMode?: boolean
}

export function TimeSeriesChart({
  title,
  data,
  color = '#2962FF',
  height = 280,
  hlines = EMPTY_HLINES,
  markers,
  trades,
  equity,
  showDrawdownOverlay = false,
  drawdownOverlayVariant = 'market',
  valueFormat = 'rub',
  liveMode = false,
}: Props) {
  const ref = useRef<HTMLDivElement>(null)
  const keyboardRootRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const seriesRef = useRef<ISeriesApi<'Line'> | null>(null)
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
  const lineData = useMemo(() => prepareLineData(data), [data])
  const hlinesKey = useMemo(() => JSON.stringify(hlines), [hlines])
  const seriesPoints = useMemo(
    () => lineData.map((d) => ({ time: d.time as UTCTimestamp, value: d.value })),
    [lineData],
  )
  const markersKey = useMemo(() => JSON.stringify(markers ?? []), [markers])
  const equityRef = useRef(equity)
  equityRef.current = equity
  const equitySig = useMemo(() => equitySeriesSignature(equity), [equity])
  const getEquity = useCallback(() => equityRef.current, [])
  const wantDrawdownOverlay = showDrawdownOverlay === true && !!equity?.length

  useEffect(() => {
    const el = ref.current
    if (!el || !lineData.length) return

    const chart = createChart(el, {
      width: Math.max(el.clientWidth, 100),
      height: Math.max(el.clientHeight, height),
      ...tradingViewChartOptions(valueFormat, showDrawdownOverlay),
    })

    const series = chart.addSeries(LineSeries, tradingViewLineSeriesOptions(color, valueFormat))
    for (const hl of hlines) {
      series.createPriceLine(tradingViewPriceLineOptions(hl))
    }

    let ddSeries: ISeriesApi<'Line'> | null = null
    if (showDrawdownOverlay) {
      ddSeries = attachDrawdownLineSeries(chart, drawdownOverlayVariant)
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
  }, [color, valueFormat, hlinesKey, lineData.length, showDrawdownOverlay, drawdownOverlayVariant])

  useEffect(() => {
    const series = seriesRef.current
    const chart = chartRef.current
    if (!series || !chart || !seriesPoints.length) return

    syncLineSeriesLive(series, chart, seriesPoints, liveMode, fittedRef.current, liveRefs)

    if (!fittedRef.current) {
      applyChartRightMargin(chart.timeScale())
      fittedRef.current = true
    }
  }, [seriesPoints, liveMode, liveRefs, hlinesKey])

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
      empty={!lineData.length}
      barCount={seriesPoints.length}
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
          <div
            ref={ref}
            className="chart-canvas-slot w-full"
            style={fullscreen ? { flex: 1, minHeight: 0 } : { height, minHeight: height }}
          />
          <ChartTradeTooltip view={tooltip} />
        </div>
      )}
    </ChartPanel>
  )
}
