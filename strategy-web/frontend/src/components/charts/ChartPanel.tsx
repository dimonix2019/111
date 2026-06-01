import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
  type RefObject,
} from 'react'

import type { IChartApi } from 'lightweight-charts'

import { attachChartKeyboard, resetChartViewPublic } from '@/lib/chartKeyboard'

export type ChartPanelLayout = {
  panelHeight: number
  fullscreen: boolean
}

type Props = {
  title: string
  defaultHeight?: number
  chartRef: RefObject<IChartApi | null>
  keyboardRootRef: RefObject<HTMLDivElement | null>
  containerRef?: RefObject<HTMLDivElement | null>
  empty?: boolean
  emptyMessage?: string
  /** Число баров для клавиатурного pan/zoom. */
  barCount?: number
  onUserInteract?: () => void
  /** Если задан — высоту графика считает родитель (Ohlc + volume). */
  onHeightChange?: (height: number) => void
  children: (layout: ChartPanelLayout) => ReactNode
}

function readContainerSize(
  chart: IChartApi,
  el: HTMLDivElement | null | undefined,
  bodyEl: HTMLDivElement | null | undefined,
  fullscreen: boolean,
): { width: number; height: number } {
  const slotH = el?.clientHeight ?? 0
  const slotW = el?.clientWidth ?? 0
  const bodyH = bodyEl?.clientHeight ?? 0
  const bodyW = bodyEl?.clientWidth ?? 0

  let width = slotW > 0 ? slotW : bodyW
  let height = slotH > 0 ? slotH : bodyH

  if (width <= 0) {
    const body = el?.closest('.chart-panel__body') as HTMLElement | null
    if (body?.clientWidth) width = body.clientWidth
    const panel = el?.closest('.chart-panel') as HTMLElement | null
    if (panel?.clientWidth) width = panel.clientWidth
  }
  if (height <= 0 && bodyEl?.clientHeight) height = bodyEl.clientHeight

  if (width <= 0 && fullscreen) width = Math.max(320, window.innerWidth - 32)
  if (height <= 0 && fullscreen) height = Math.max(240, window.innerHeight - 160)
  if (width <= 0) {
    const parent = chart.chartElement().parentElement
    if (parent?.clientWidth) width = parent.clientWidth
  }
  if (width <= 0) {
    const cw = chart.chartElement().clientWidth
    if (cw > 0) width = cw
  }
  if (width <= 0) width = 320

  return { width, height }
}

export function ChartPanel({
  title,
  defaultHeight = 280,
  chartRef,
  keyboardRootRef,
  containerRef,
  empty = false,
  emptyMessage = 'Нет данных для графика',
  barCount = 0,
  onUserInteract,
  onHeightChange,
  children,
}: Props) {
  const panelRef = useRef<HTMLDivElement>(null)
  const [fullscreen, setFullscreen] = useState(false)
  const [measuredHeight, setMeasuredHeight] = useState(defaultHeight)
  const panelHeight = fullscreen ? measuredHeight : defaultHeight
  const managesHeight = !!onHeightChange
  const retryRef = useRef(0)
  const barCountRef = useRef(barCount)
  barCountRef.current = barCount
  const onUserInteractRef = useRef(onUserInteract)
  onUserInteractRef.current = onUserInteract
  const [keyboardAttachKey, setKeyboardAttachKey] = useState(0)

  const closeFullscreen = useCallback(() => {
    if (document.fullscreenElement === panelRef.current) {
      void document.exitFullscreen()
      return
    }
    setFullscreen(false)
  }, [])

  const toggleFullscreen = useCallback(async () => {
    const panel = panelRef.current
    if (!panel) return
    try {
      if (document.fullscreenElement === panel) {
        await document.exitFullscreen()
      } else {
        await panel.requestFullscreen()
      }
    } catch {
      setFullscreen((v) => !v)
    }
  }, [])

  useEffect(() => {
    const onFsChange = () => {
      const active = document.fullscreenElement === panelRef.current
      setFullscreen(active)
      if (active) {
        document.body.classList.add('chart-fullscreen-active')
      } else if (!document.fullscreenElement) {
        document.body.classList.remove('chart-fullscreen-active')
      }
    }
    document.addEventListener('fullscreenchange', onFsChange)
    return () => {
      document.removeEventListener('fullscreenchange', onFsChange)
      if (document.fullscreenElement === panelRef.current) {
        void document.exitFullscreen()
      }
      document.body.classList.remove('chart-fullscreen-active')
    }
  }, [])
  const onReset = useCallback(() => resetChartViewPublic(chartRef.current), [chartRef])

  useLayoutEffect(() => {
    if (empty) return

    let cancelled = false
    let attempts = 0

    const tryReady = () => {
      if (cancelled) return
      if (keyboardRootRef.current && chartRef.current) {
        setKeyboardAttachKey((k) => k + 1)
        return
      }
      attempts += 1
      if (attempts < 120) requestAnimationFrame(tryReady)
    }

    tryReady()
    return () => {
      cancelled = true
    }
  }, [empty, chartRef, keyboardRootRef])

  useEffect(() => {
    const root = keyboardRootRef.current
    if (empty || !root || !chartRef.current) return

    return attachChartKeyboard(
      root,
      () => chartRef.current,
      () => ({
        getBarCount: () => barCountRef.current,
        onUserInteract: () => onUserInteractRef.current?.(),
      }),
    )
  }, [empty, chartRef, keyboardRootRef, keyboardAttachKey])

  const measureBodyHeight = useCallback(() => {
    const body = keyboardRootRef.current
    if (!body) return defaultHeight
    const next = Math.max(120, body.clientHeight)
    setMeasuredHeight((prev) => (prev === next ? prev : next))
    return next
  }, [keyboardRootRef, defaultHeight])

  const applyChartSize = useCallback(() => {
    const chart = chartRef.current
    if (!chart) return
    const body = keyboardRootRef.current
    const { width, height } = readContainerSize(chart, containerRef?.current, body, fullscreen)
    const effectiveHeight = fullscreen ? Math.max(height, measuredHeight) : height || panelHeight

    if (fullscreen && (width <= 0 || effectiveHeight <= 0) && retryRef.current < 16) {
      retryRef.current += 1
      requestAnimationFrame(() => applyChartSize())
      return
    }
    retryRef.current = 0

    if (managesHeight) {
      onHeightChange?.(effectiveHeight)
      if (width > 0) chart.applyOptions({ width })
      return
    }

    if (width > 0 && effectiveHeight > 0) {
      chart.resize(width, effectiveHeight)
    } else if (width > 0) {
      chart.applyOptions({ width })
    } else if (effectiveHeight > 0) {
      chart.applyOptions({ height: effectiveHeight })
    }
  }, [chartRef, containerRef, keyboardRootRef, fullscreen, measuredHeight, panelHeight, managesHeight, onHeightChange])

  useLayoutEffect(() => {
    if (!fullscreen) {
      setMeasuredHeight(defaultHeight)
      return
    }
    measureBodyHeight()
  }, [fullscreen, defaultHeight, measureBodyHeight])

  useLayoutEffect(() => {
    applyChartSize()
  }, [panelHeight, applyChartSize])

  useLayoutEffect(() => {
    retryRef.current = 0
    const timers: number[] = []
    const raf = requestAnimationFrame(() => {
      requestAnimationFrame(() => applyChartSize())
    })
    for (const ms of [0, 50, 150, 300]) {
      timers.push(window.setTimeout(() => applyChartSize(), ms))
    }
    if (fullscreen) {
      keyboardRootRef.current?.focus({ preventScroll: true })
    }
    return () => {
      cancelAnimationFrame(raf)
      for (const id of timers) window.clearTimeout(id)
    }
  }, [fullscreen, keyboardRootRef, applyChartSize])

  useEffect(() => {
    const targets = new Set<Element>()
    const body = keyboardRootRef.current
    const slot = containerRef?.current
    if (body) targets.add(body)
    if (slot) targets.add(slot)
    if (!targets.size) return

    const ro = new ResizeObserver(() => {
      if (fullscreen) measureBodyHeight()
      applyChartSize()
    })
    for (const t of targets) ro.observe(t)
    return () => ro.disconnect()
  }, [containerRef, keyboardRootRef, applyChartSize, fullscreen, measureBodyHeight])

  useEffect(() => {
    if (!fullscreen) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && document.fullscreenElement === panelRef.current) {
        e.preventDefault()
        closeFullscreen()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [fullscreen, closeFullscreen])

  return (
    <div
      ref={panelRef}
      className={`chart-panel ${fullscreen ? 'chart-panel--fullscreen chart-panel--native-fs' : ''}`}
    >
      <div className="chart-toolbar">
        <div className="chart-toolbar__title">{title}</div>
        <div className="chart-toolbar__actions">
          <button type="button" className="chart-tool-btn" title="Сброс масштаба (Alt+R)" onClick={onReset}>
            ⟲
          </button>
          <button
            type="button"
            className="chart-tool-btn"
            title={fullscreen ? 'Выйти (Esc)' : 'На весь экран'}
            onClick={() => void toggleFullscreen()}
          >
            {fullscreen ? '✕' : '⛶'}
          </button>
        </div>
      </div>
      {empty ? (
        <p className="px-3 pb-3 text-[12px] text-ink-3">{emptyMessage}</p>
      ) : (
        <div className="chart-panel__body chart-keyboard-root" ref={keyboardRootRef} tabIndex={-1}>
          {children({ panelHeight, fullscreen })}
        </div>
      )}
      {!empty ? (
        <p className="chart-hint px-3 pb-2 text-[10px] text-ink-3">
          Клавиатура: ← → — 1 бар; Ctrl+←/→ — быстрый pan; Ctrl+↑/↓ или Ctrl+/− — zoom; Home/End — в начало/конец;
          Alt+R — сброс. Тачпад/мышь: двухпальцевый скролл — pan; Ctrl+скролл (pinch) — zoom; скролл над правой
          шкалой — сжатие/растяжение цены; ЛКМ на шкале — drag. Клик по графику — фокус. {fullscreen ? 'Esc — выход' : '⛶ — полный экран'}
        </p>
      ) : null}
    </div>
  )
}
