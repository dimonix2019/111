import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react'

import type { IChartApi } from 'lightweight-charts'

import { attachChartKeyboard } from '@/lib/chartKeyboard'

type UserInteractedRef = { current: boolean }

/** Тонкая обёртка для графиков без ChartPanel (например EquityCompareChart). */
export function useChartKeyboard(
  rootRef: RefObject<HTMLDivElement | null>,
  chartRef: RefObject<IChartApi | null>,
  barCount: number,
  userInteractedRef?: UserInteractedRef,
): void {
  const barCountRef = useRef(barCount)
  barCountRef.current = barCount

  const [attachKey, setAttachKey] = useState(0)

  useLayoutEffect(() => {
    let cancelled = false
    let attempts = 0

    const tryReady = () => {
      if (cancelled) return
      if (rootRef.current && chartRef.current) {
        setAttachKey((k) => k + 1)
        return
      }
      attempts += 1
      if (attempts < 120) requestAnimationFrame(tryReady)
    }

    tryReady()
    return () => {
      cancelled = true
    }
  }, [rootRef, chartRef])

  useEffect(() => {
    const root = rootRef.current
    if (!root || !chartRef.current) return

    return attachChartKeyboard(
      root,
      () => chartRef.current,
      () => ({
        getBarCount: () => barCountRef.current,
        onUserInteract: () => {
          if (userInteractedRef) userInteractedRef.current = true
        },
      }),
    )
  }, [rootRef, chartRef, userInteractedRef, attachKey])
}
