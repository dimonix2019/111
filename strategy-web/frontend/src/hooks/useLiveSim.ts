import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { SimParams, SimResponse } from '@/types'
import { refreshMoexTail, simulate } from '@/lib/api'
import { loadCachedSimResult, saveCachedSimResult, simResultCacheKey } from '@/lib/simResultCache'

const DEBOUNCE_MS = 400
const CSV_DEBOUNCE_MS = 700

type Args = {
  csvPath: string
  compare: boolean
  params: SimParams
  enabled: boolean
  onResult: (r: SimResponse) => void
}

function paramsKey(compare: boolean, params: SimParams) {
  return JSON.stringify({ compare, ...params })
}

function parseHqLastTsSec(lastTs: string | undefined): number | null {
  if (!lastTs?.trim()) return null
  const t = new Date(lastTs.trim().replace(' ', 'T')).getTime()
  return Number.isFinite(t) ? Math.floor(t / 1000) : null
}

/** Кэшированный zscore короче CSV/MOEX — нужен пересчёт с хвостом. */
export function simZscoreBehindHq(result: SimResponse): boolean {
  const z = result.packs[0]?.zscore
  const hqSec = parseHqLastTsSec(result.hq?.last_ts)
  if (!z?.length || hqSec == null) return false
  const lastZ = z[z.length - 1]!.time
  return lastZ < hqSec - 900
}

export function useLiveSim({ csvPath, compare, params, enabled, onResult }: Args) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [lastMs, setLastMs] = useState<number | null>(null)
  const [lastUpdatedAt, setLastUpdatedAt] = useState<number | null>(null)
  const [pending, setPending] = useState(false)
  const [appliedKey, setAppliedKey] = useState('')

  const abortRef = useRef<AbortController | null>(null)
  const hasDataRef = useRef(false)
  const reqIdRef = useRef(0)
  const timerRef = useRef<number | null>(null)
  const onResultRef = useRef(onResult)
  const prevCsvRef = useRef(csvPath)
  const firstRunRef = useRef(true)

  const ctxRef = useRef({ csvPath, compare, params })
  ctxRef.current = { csvPath, compare, params }

  onResultRef.current = onResult
  const pKey = useMemo(() => paramsKey(compare, params), [compare, params])
  const cacheKey = useMemo(() => simResultCacheKey(csvPath, compare, params), [csvPath, compare, params])

  const clearTimer = useCallback(() => {
    if (timerRef.current != null) {
      window.clearTimeout(timerRef.current)
      timerRef.current = null
    }
  }, [])

  const runRequest = useCallback(
    (forceDownload: boolean, refreshTail = false) => {
      clearTimer()
      abortRef.current?.abort()

      const { csvPath: path, compare: cmp, params: p } = ctxRef.current
      const ac = new AbortController()
      abortRef.current = ac
      const reqId = ++reqIdRef.current
      const requestKey = paramsKey(cmp, p)

      setLoading(true)
      setError(null)
      setPending(false)

      const t0 = performance.now()
      const run = async () => {
        if (refreshTail && !forceDownload && hasDataRef.current) {
          await refreshMoexTail(path).catch(() => undefined)
        }
        return simulate(
          {
            ...p,
            csv_path: path,
            auto_download: forceDownload || !hasDataRef.current,
            compare_mode: cmp,
            oos_enabled: p.oos_enabled,
            oos_train_ratio: p.oos_train_ratio,
          },
          ac.signal,
        )
      }

      void run()
        .then((res) => {
          if (reqId !== reqIdRef.current) return
          const latestKey = paramsKey(ctxRef.current.compare, ctxRef.current.params)
          if (requestKey !== latestKey) return
          hasDataRef.current = true
          setAppliedKey(requestKey)
          setLastMs(Math.round(performance.now() - t0))
          setLastUpdatedAt(Date.now())
          void saveCachedSimResult(simResultCacheKey(path, cmp, p), res)
          onResultRef.current(res)
        })
        .catch((e) => {
          if (ac.signal.aborted) return
          if (reqId !== reqIdRef.current) return
          const msg = e instanceof Error ? e.message : String(e)
          if (msg.includes('abort') || msg.includes('Abort')) return
          setError(
            msg.includes('Failed to fetch') || msg.includes('NetworkError')
              ? 'API недоступен — перезапустите run_tester.bat (окно Z-Strategy API)'
              : msg,
          )
        })
        .finally(() => {
          if (reqId === reqIdRef.current) setLoading(false)
        })
    },
    [clearTimer],
  )

  const scheduleRun = useCallback(
    (delayMs: number, forceDownload = false, refreshTail = false) => {
      clearTimer()
      setPending(true)
      timerRef.current = window.setTimeout(() => {
        timerRef.current = null
        runRequest(forceDownload, refreshTail)
      }, delayMs)
    },
    [clearTimer, runRequest],
  )

  useEffect(() => {
    if (!enabled) {
      clearTimer()
      setPending(false)
      return
    }

    const csvChanged = prevCsvRef.current !== csvPath
    prevCsvRef.current = csvPath
    if (csvChanged) hasDataRef.current = false

    const firstRun = firstRunRef.current
    firstRunRef.current = false
    let cancelled = false

    void loadCachedSimResult(cacheKey).then((cached) => {
      if (cancelled) return
      let refreshTail = false
      if (cached) {
        hasDataRef.current = true
        setAppliedKey(pKey)
        setLastUpdatedAt(cached.savedAt)
        onResultRef.current(cached.result)
        refreshTail = simZscoreBehindHq(cached.result)
      }
      const delay = firstRun ? 0 : csvChanged ? CSV_DEBOUNCE_MS : DEBOUNCE_MS
      scheduleRun(delay, false, refreshTail || !!cached)
    })

    return () => {
      cancelled = true
      clearTimer()
    }
  }, [enabled, csvPath, pKey, cacheKey, scheduleRun, clearTimer])

  useEffect(
    () => () => {
      clearTimer()
      abortRef.current?.abort()
    },
    [clearTimer],
  )

  const runNow = useCallback(() => {
    runRequest(false)
  }, [runRequest])

  const stale = enabled && !loading && !pending && appliedKey !== '' && appliedKey !== pKey

  return { loading, error, lastMs, lastUpdatedAt, pending, stale, runNow }
}

