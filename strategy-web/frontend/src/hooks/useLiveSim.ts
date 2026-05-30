import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { SimParams, SimResponse } from '@/types'
import { simulate } from '@/lib/api'

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

export function useLiveSim({ csvPath, compare, params, enabled, onResult }: Args) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [lastMs, setLastMs] = useState<number | null>(null)
  const [pending, setPending] = useState(false)
  const [appliedKey, setAppliedKey] = useState('')

  const abortRef = useRef<AbortController | null>(null)
  const hasDataRef = useRef(false)
  const reqIdRef = useRef(0)
  const timerRef = useRef<number | null>(null)
  const onResultRef = useRef(onResult)
  const prevCsvRef = useRef(csvPath)
  const firstRunRef = useRef(true)
  const moexInFlightRef = useRef(false)

  const ctxRef = useRef({ csvPath, compare, params })
  ctxRef.current = { csvPath, compare, params }

  onResultRef.current = onResult
  const pKey = useMemo(() => paramsKey(compare, params), [compare, params])

  const clearTimer = useCallback(() => {
    if (timerRef.current != null) {
      window.clearTimeout(timerRef.current)
      timerRef.current = null
    }
  }, [])

  const runRequest = useCallback(
    (forceDownload: boolean) => {
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
      void simulate(
        {
          ...p,
          csv_path: path,
          auto_download: forceDownload || !hasDataRef.current,
          compare_mode: cmp,
        },
        ac.signal,
      )
        .then((res) => {
          if (reqId !== reqIdRef.current) return
          const latestKey = paramsKey(ctxRef.current.compare, ctxRef.current.params)
          if (requestKey !== latestKey) return
          hasDataRef.current = true
          setAppliedKey(requestKey)
          setLastMs(Math.round(performance.now() - t0))
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
    (delayMs: number, forceDownload = false) => {
      clearTimer()
      setPending(true)
      timerRef.current = window.setTimeout(() => {
        timerRef.current = null
        runRequest(forceDownload)
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

    const delay = firstRunRef.current ? 0 : csvChanged ? CSV_DEBOUNCE_MS : DEBOUNCE_MS
    firstRunRef.current = false
    scheduleRun(delay)

    return () => {
      clearTimer()
    }
  }, [enabled, csvPath, pKey, scheduleRun, clearTimer])

  useEffect(
    () => () => {
      clearTimer()
      abortRef.current?.abort()
    },
    [clearTimer],
  )

  const runNow = useCallback(() => {
    hasDataRef.current = false
    runRequest(true)
  }, [runRequest])

  const stale = enabled && !loading && !pending && appliedKey !== '' && appliedKey !== pKey

  return { loading, error, lastMs, pending, stale, runNow }
}
