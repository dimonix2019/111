import { useCallback, useEffect, useState } from 'react'
import type { BasisScanResponse } from '@/types'
import { fetchBasisScan } from '@/lib/api'

export type BasisScanParams = {
  finRate: number
  minYieldAnn: number
  zEntry: number
}

export function useBasisScan(params: BasisScanParams, active: boolean, pollSec = 60) {
  const [data, setData] = useState<BasisScanResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetchBasisScan({
        fin_rate: params.finRate,
        min_yield_ann: params.minYieldAnn,
        z_entry: params.zEntry,
      })
      setData(res)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [params.finRate, params.minYieldAnn, params.zEntry])

  useEffect(() => {
    if (!active) return
    void reload()
    const id = window.setInterval(() => void reload(), pollSec * 1000)
    return () => window.clearInterval(id)
  }, [active, reload, pollSec])

  return { data, loading, error, reload }
}
