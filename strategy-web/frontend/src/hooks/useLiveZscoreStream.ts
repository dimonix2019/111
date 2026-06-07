import { useEffect, useRef, useState } from 'react'

import { fetchMarketLive, type MarketLiveResponse } from '@/lib/api'
import type { SimPack } from '@/types'

const POLL_MS = 5000
const SIM_REFRESH_MS = 90_000

type ZPoint = SimPack['zscore'][number]

export type LatestQuote = {
  tatn_close?: number
  tatnp_close?: number
  timestamp?: string
}

type Args = {
  enabled: boolean
  csvPath: string
  zMode?: 'rolling30' | 'global'
  seed: ZPoint[]
  /** Полный бэктест для сделок/метрик (реже, чем график). */
  onSlowSim?: () => void
  onTick?: (resp: MarketLiveResponse) => void
}

/**
 * Только вкладка «Рынок»: каждые 5 с — /api/market/live (MOEX + Z/spread).
 * Остальные вкладки используют pack из бэктеста без live-опроса.
 */
export function useLiveZscoreStream({ enabled, csvPath, zMode = 'rolling30', seed, onSlowSim, onTick }: Args) {
  const [zscore, setZscore] = useState<ZPoint[]>(seed)
  const [tickAt, setTickAt] = useState<string | null>(null)
  const [liveError, setLiveError] = useState<string | null>(null)
  const [latestQuote, setLatestQuote] = useState<LatestQuote | null>(null)
  const [pollSeq, setPollSeq] = useState(0)
  const tickBusyRef = useRef(false)
  const onSlowSimRef = useRef(onSlowSim)
  const onTickRef = useRef(onTick)

  onSlowSimRef.current = onSlowSim
  onTickRef.current = onTick

  useEffect(() => {
    if (!enabled) {
      setZscore(seed)
      setTickAt(null)
      setLiveError(null)
      setLatestQuote(null)
      setPollSeq(0)
      return
    }
    setZscore(seed.length ? seed : [])
  }, [enabled, seed])

  useEffect(() => {
    if (!enabled || !csvPath) return

    const pull = async () => {
      if (tickBusyRef.current) return
      tickBusyRef.current = true
      try {
        const resp = await fetchMarketLive(csvPath, zMode)
        if (resp.zscore?.length) {
          setZscore(resp.zscore)
        }
        setTickAt(resp.tick_at ?? null)
        if (resp.latest_quote && Object.keys(resp.latest_quote).length) {
          setLatestQuote(resp.latest_quote as LatestQuote)
        }
        setPollSeq((n) => n + 1)
        setLiveError(null)
        onTickRef.current?.(resp)
      } catch (e) {
        setLiveError(e instanceof Error ? e.message : String(e))
      } finally {
        tickBusyRef.current = false
      }
    }

    void pull()
    const fastId = window.setInterval(() => void pull(), POLL_MS)
    const slowId = window.setInterval(() => {
      onSlowSimRef.current?.()
    }, SIM_REFRESH_MS)

    return () => {
      window.clearInterval(fastId)
      window.clearInterval(slowId)
    }
  }, [enabled, csvPath, zMode])

  return { zscore, tickAt, liveError, latestQuote, pollSeq }
}
