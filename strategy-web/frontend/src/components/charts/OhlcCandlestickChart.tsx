import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import {
  CandlestickSeries,
  CrosshairMode,
  ColorType,
  createChart,
  createSeriesMarkers,
  HistogramSeries,
  type IChartApi,
  type ISeriesApi,
  type ISeriesMarkersPluginApi,
  type Time,
  type UTCTimestamp,
} from 'lightweight-charts'

import type { Trade, TradeMarker } from '@/types'
import { ChartPanel } from '@/components/charts/ChartPanel'
import { ChartTradeTooltip } from '@/components/charts/ChartTradeTooltip'
import { EMPTY_HLINES, type HLine } from '@/components/charts/TimeSeriesChart'
import { syncCandleSeriesLive } from '@/lib/chartLiveUpdate'
import { attachChartTradeTooltip, type ChartTradeTooltipPersist, type ChartTradeTooltipView } from '@/lib/chartTradeTooltip'
import {
  attachDrawdownLineSeries,
  equitySeriesSignature,
  setDrawdownSeriesData,
  type EquityPoint,
} from '@/lib/drawdownOverlay'
import {
  buildOhlcSeries,
  type OhlcFieldMap,
} from '@/lib/ohlcCandles'
import {
  tradingViewCandleSeriesOptions,
  tradingViewChartOptions,
  tradingViewPriceLineOptions,
  toTradeSeriesMarkers,
  TV,
} from '@/lib/chartTheme'
import { applyChartRightMargin, type ChartValueFormat } from '@/lib/format'
type Props = {
  title: string
  rows: Record<string, unknown>[]
  fields: OhlcFieldMap
  height?: number
  hlines?: HLine[]
  markers?: TradeMarker[]
  trades?: Trade[]
  equity?: EquityPoint[]
  liveMode?: boolean
  valueFormat?: ChartValueFormat
  showVolume?: boolean
  /** Кривая drawdown внизу (только вкладка Рынок). */
  showDrawdownOverlay?: boolean
}

export function OhlcCandlestickChart({
  title,
  rows,
  fields,
  height = 260,
  hlines = EMPTY_HLINES,
  markers,
  trades,
  equity,
  liveMode = false,
  valueFormat = 'float',
  showVolume = true,
  showDrawdownOverlay = false,
}: Props) {
  const ref = useRef<HTMLDivElement>(null)
  const volRef = useRef<HTMLDivElement>(null)
  const keyboardRootRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const volChartRef = useRef<IChartApi | null>(null)
  const seriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const ddSeriesRef = useRef<ISeriesApi<'Line'> | null>(null)
  const volSeriesRef = useRef<ISeriesApi<'Histogram'> | null>(null)
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
  const [panelHeight, setPanelHeight] = useState(height)
  const [tooltip, setTooltip] = useState<ChartTradeTooltipView | null>(null)
  const tooltipPersistRef = useRef<ChartTradeTooltipPersist>({ pinnedKey: null })

  const bars = useMemo(() => buildOhlcSeries(rows, fields), [rows, fields])
  const hasVolume = showVolume && bars.some((b) => b.volume != null && b.volume > 0)

  useEffect(() => {
    const chart = chartRef.current
    const volChart = volChartRef.current
    if (!chart) return
    const volH = hasVolume ? Math.max(48, Math.round(panelHeight * 0.22)) : 0
    const mainH = hasVolume ? Math.max(120, panelHeight - volH - 4) : panelHeight
    const w = ref.current?.clientWidth ?? 0
    const opts = w > 0 ? { width: w, height: mainH } : { height: mainH }
    chart.applyOptions(opts)
    if (volChart && hasVolume) {
      volChart.applyOptions(w > 0 ? { width: w, height: volH } : { height: volH })
    }
  }, [panelHeight, hasVolume])
  const hlinesKey = useMemo(() => JSON.stringify(hlines), [hlines])
  const markersKey = useMemo(() => JSON.stringify(markers ?? []), [markers])
  const equityRef = useRef(equity)
  equityRef.current = equity
  const equitySig = useMemo(() => equitySeriesSignature(equity), [equity])
  const getEquity = useCallback(() => equityRef.current, [])
  const wantDrawdownOverlay = showDrawdownOverlay === true && !!equity?.length

  const candleData = useMemo(
    () =>
      bars.map((c) => ({
        time: c.time as UTCTimestamp,
        open: c.open,
        high: c.high,
        low: c.low,
        close: c.close,
      })),
    [bars],
  )

  const volumeData = useMemo(() => {
    if (!hasVolume) return []
    return bars
      .filter((b) => b.volume != null && b.volume > 0)
      .map((b) => ({
        time: b.time as UTCTimestamp,
        value: b.volume!,
        color: b.close >= b.open ? `${TV.up}55` : `${TV.down}55`,
      }))
  }, [bars, hasVolume])

  useEffect(() => {
    const el = ref.current
    if (!el || !bars.length) return

    const volEl = hasVolume ? volRef.current : null
    const volHeight = hasVolume ? Math.max(48, Math.round(height * 0.22)) : 0
    const mainHeight = hasVolume ? height - volHeight - 4 : height

    const chart = createChart(el, {
      width: el.clientWidth,
      height: mainHeight,
      ...tradingViewChartOptions(valueFormat, showDrawdownOverlay),
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

    const syncVolRange = (range: { from: number; to: number } | null) => {
      if (!volChartRef.current || !range) return
      volChartRef.current.timeScale().setVisibleLogicalRange(range)
    }
    chart.timeScale().subscribeVisibleLogicalRangeChange(syncVolRange)

    const ro = new ResizeObserver(() => {
      const w = el.clientWidth
      const h = el.clientHeight
      if (w > 0 && h > 0) chart.resize(w, h)
      else if (w > 0) chart.applyOptions({ width: w })
    })
    ro.observe(el)

    let volRo: ResizeObserver | null = null
    if (hasVolume && volEl) {
      const volChart = createChart(volEl, {
        width: volEl.clientWidth,
        height: volHeight,
        layout: {
          background: { type: ColorType.Solid, color: TV.bg },
          textColor: TV.textMuted,
          fontSize: 11,
        },
        grid: { vertLines: { visible: false }, horzLines: { color: TV.grid } },
        rightPriceScale: { borderColor: TV.border, scaleMargins: { top: 0.1, bottom: 0 } },
        timeScale: { visible: false, borderColor: TV.border },
        crosshair: { mode: CrosshairMode.Normal },
        handleScroll: false,
        handleScale: false,
      })
      const volSeries = volChart.addSeries(HistogramSeries, {
        priceFormat: { type: 'volume' },
        priceScaleId: 'right',
      })
      volChartRef.current = volChart
      volSeriesRef.current = volSeries
      volRo = new ResizeObserver(() => volChart.applyOptions({ width: volEl.clientWidth }))
      volRo.observe(volEl)
    }

    return () => {
      chart.timeScale().unsubscribeVisibleLogicalRangeChange(onRange)
      chart.timeScale().unsubscribeVisibleLogicalRangeChange(syncVolRange)
      ro.disconnect()
      volRo?.disconnect()
      markersPluginRef.current?.detach()
      markersPluginRef.current = null
      chart.remove()
      volChartRef.current?.remove()
      chartRef.current = null
      volChartRef.current = null
      seriesRef.current = null
      ddSeriesRef.current = null
      volSeriesRef.current = null
    }
  }, [height, hlinesKey, hasVolume, valueFormat, bars.length, showDrawdownOverlay])

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
    const volSeries = volSeriesRef.current
    if (!volSeries || !volumeData.length) return
    volSeries.setData(volumeData)
  }, [volumeData])

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
      empty={!bars.length}
      barCount={candleData.length}
      onUserInteract={() => {
        userInteractedRef.current = true
      }}
      onHeightChange={setPanelHeight}
    >
      {(layout) => (
        <div
          className="chart-canvas-host relative flex flex-col overflow-hidden px-1"
          style={
            layout.fullscreen
              ? { flex: 1, minHeight: 0 }
              : { height: layout.panelHeight, minHeight: layout.panelHeight }
          }
        >
          <div ref={ref} className="chart-canvas-slot min-h-0 w-full flex-1" />
          {hasVolume ? <div ref={volRef} className="mt-1 w-full shrink-0" /> : null}
          <ChartTradeTooltip view={tooltip} />
        </div>
      )}
    </ChartPanel>
  )
}
