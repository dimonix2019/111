import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { SimResponse } from '@/types'
import { sweep } from '@/lib/api'
import type { SweepRow } from '@/components/charts/SweepHeatmap'

const DEBOUNCE_MS = 500

function sweepKey(result: SimResponse): string {
  return JSON.stringify({ path: result.path, kw: result.sim_kw })
}

export function useSweepHeatmap(result: SimResponse, enabled: boolean) {
  const [rows, setRows] = useState<SweepRow[] | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [lastMs, setLastMs] = useState<number | null>(null)
  const reqIdRef = useRef(0)
  const key = useMemo(() => sweepKey(result), [result])

  const runSweep = useCallback(() => {
    const kw = result.sim_kw as Record<string, unknown>
    const reqId = ++reqIdRef.current
    setLoading(true)
    setError(null)
    const t0 = performance.now()
    void sweep({
      csv_path: result.path,
      notional_rub: kw.notional_rub,
      leverage: kw.leverage,
      commission_pct_per_side: kw.commission_pct_per_side,
      compound_returns: kw.compound_returns,
    })
      .then((res) => {
        if (reqId !== reqIdRef.current) return
        setRows(res.rows as SweepRow[])
        setLastMs(Math.round(performance.now() - t0))
      })
      .catch((e) => {
        if (reqId !== reqIdRef.current) return
        setError(e instanceof Error ? e.message : String(e))
      })
      .finally(() => {
        if (reqId === reqIdRef.current) setLoading(false)
      })
  }, [result])

  useEffect(() => {
    if (!enabled) return
    const timer = window.setTimeout(runSweep, DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
  }, [enabled, key, runSweep])

  return { rows, loading, error, lastMs, refresh: runSweep }
}
