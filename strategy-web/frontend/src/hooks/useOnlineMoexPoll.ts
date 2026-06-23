import { useEffect, useRef } from 'react'

import { refreshMoexTail } from '@/lib/api'

const POLL_MS = 5000

type Args = {
  enabled: boolean
  loading: boolean
  csvPath: string
  /** Пересчёт после догрузки CSV (без повторного MOEX в simulate). */
  runSim: () => void
  onDataStatus?: (meta: Record<string, unknown>) => void
}

/**
 * Каждые 5 с: сначала догрузка MOEX (/api/data/refresh-tail), затем симуляция.
 * Отдельный шаг, чтобы debounce simulate не отменял загрузку.
 */
export function useOnlineMoexPoll({ enabled, loading, csvPath, runSim, onDataStatus }: Args) {
  const loadingRef = useRef(loading)
  const runSimRef = useRef(runSim)
  const csvPathRef = useRef(csvPath)
  const tickInFlightRef = useRef(false)
  const onDataStatusRef = useRef(onDataStatus)

  loadingRef.current = loading
  runSimRef.current = runSim
  csvPathRef.current = csvPath
  onDataStatusRef.current = onDataStatus

  useEffect(() => {
    if (!enabled) return

    const tick = async () => {
      if (loadingRef.current || tickInFlightRef.current) return
      tickInFlightRef.current = true
      try {
        const meta = await refreshMoexTail(csvPathRef.current)
        onDataStatusRef.current?.(meta)
        if (!loadingRef.current) {
          runSimRef.current()
        }
      } catch {
        /* следующий тик через 5 с */
      } finally {
        tickInFlightRef.current = false
      }
    }

    void tick()
    const id = window.setInterval(() => void tick(), POLL_MS)
    return () => window.clearInterval(id)
  }, [enabled, csvPath])
}
