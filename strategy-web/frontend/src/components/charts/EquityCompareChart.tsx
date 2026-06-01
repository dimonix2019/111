import { useEffect, useMemo, useRef } from 'react'

import { ColorType, createChart, LineSeries, type IChartApi, type ISeriesApi, type Time, type UTCTimestamp } from 'lightweight-charts'

import { prepareLineData } from '@/lib/chartData'
import { useChartKeyboard } from '@/hooks/useChartKeyboard'
import {
  applyChartRightMargin,
  chartPriceFormat,
  chartRightPriceScaleOptions,
  chartTimeScaleOptions,
} from '@/lib/format'

type SeriesInput = {
  label: string
  color: string
  data: { time: number; value: number }[]
}

type Props = {
  title: string
  series: SeriesInput[]
  height?: number
}

export function EquityCompareChart({ title, series, height = 280 }: Props) {
  const keyboardRootRef = useRef<HTMLDivElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const seriesRefs = useRef<ISeriesApi<'Line'>[]>([])

  const prepared = useMemo(
    () =>
      series.map((s) => ({
        ...s,
        points: prepareLineData(s.data),
      })),
    [series],
  )

  const barCount = useMemo(
    () => prepared.reduce((max, s) => Math.max(max, s.points.length), 0),
    [prepared],
  )

  useChartKeyboard(keyboardRootRef, chartRef, barCount)

  useEffect(() => {
    const el = containerRef.current
    if (!el) return

    const priceFmt = chartPriceFormat('rub')
    const chart = createChart(el, {
      width: el.clientWidth,
      height,
      layout: {
        background: { type: ColorType.Solid, color: 'rgba(8,16,28,0.35)' },
        textColor: 'rgba(220,230,255,0.72)',
      },
      grid: {
        vertLines: { color: 'rgba(120,180,255,0.06)' },
        horzLines: { color: 'rgba(120,180,255,0.06)' },
      },
      rightPriceScale: chartRightPriceScaleOptions(),
      timeScale: chartTimeScaleOptions(),
      localization: priceFmt.localization,
      handleScroll: {
        mouseWheel: true,
        pressedMouseMove: true,
        horzTouchDrag: true,
        vertTouchDrag: false,
      },
      handleScale: {
        axisPressedMouseMove: { time: true, price: true },
        mouseWheel: false,
        pinch: false,
      },
    })

    chartRef.current = chart
    seriesRefs.current = []

    for (const s of prepared) {
      const line = chart.addSeries(LineSeries, {
        color: s.color,
        lineWidth: 2,
        priceFormat: priceFmt.seriesPriceFormat,
        title: s.label,
      })
      line.setData(
        s.points.map((p) => ({ time: p.time as UTCTimestamp, value: p.value })) as { time: Time; value: number }[],
      )
      seriesRefs.current.push(line)
    }

    applyChartRightMargin(chart.timeScale())

    const ro = new ResizeObserver(() => chart.applyOptions({ width: el.clientWidth }))
    ro.observe(el)

    return () => {
      ro.disconnect()
      chart.remove()
      chartRef.current = null
      seriesRefs.current = []
    }
  }, [height, prepared])

  return (
    <div className="surface-inner p-3">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <div className="text-[13px] font-extrabold text-ink-2">{title}</div>
        <div className="flex flex-wrap gap-3 text-[11px] text-ink-3">
          {series.map((s) => (
            <span key={s.label} className="inline-flex items-center gap-1.5">
              <span className="inline-block h-2 w-4 rounded-sm" style={{ background: s.color }} />
              {s.label}
            </span>
          ))}
        </div>
      </div>
      <div ref={keyboardRootRef} className="w-full chart-keyboard-root">
        <div ref={containerRef} className="w-full" />
      </div>
    </div>
  )
}
