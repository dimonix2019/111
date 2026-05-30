import { useEffect, type RefObject } from 'react'

import type { IChartApi } from 'lightweight-charts'

import { attachChartKeyboard } from '@/lib/chartKeyboard'

type UserInteractedRef = { current: boolean }

export function useChartKeyboard(
  rootRef: RefObject<HTMLDivElement | null>,
  chartRef: RefObject<IChartApi | null>,
  barCount: number,
  userInteractedRef?: UserInteractedRef,
): void {
  useEffect(() => {
    const root = rootRef.current
    if (!root) return

    return attachChartKeyboard(
      root,
      () => chartRef.current,
      () => ({
        barCount,
        onUserInteract: () => {
          if (userInteractedRef) userInteractedRef.current = true
        },
      }),
    )
  }, [rootRef, chartRef, barCount, userInteractedRef])
}
