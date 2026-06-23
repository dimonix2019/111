import type { BasisScanResponse, Presets, SimParams, SimResponse, SimPack } from '@/types'
import type { GridSearchRequest, GridSearchResponse } from '@/types/gridSearch'

const DEFAULT_TIMEOUT_MS = 30_000
const SIMULATE_TIMEOUT_MS = 90_000
const GRID_SEARCH_TIMEOUT_MS = 600_000

function mergeSignals(a?: AbortSignal, b?: AbortSignal): AbortSignal | undefined {
  if (!a && !b) return undefined
  if (!a) return b
  if (!b) return a
  const ac = new AbortController()
  const abort = () => ac.abort()
  if (a.aborted || b.aborted) {
    ac.abort()
    return ac.signal
  }
  a.addEventListener('abort', abort, { once: true })
  b.addEventListener('abort', abort, { once: true })
  return ac.signal
}

async function api<T>(path: string, init?: RequestInit & { timeoutMs?: number }): Promise<T> {
  const { timeoutMs = DEFAULT_TIMEOUT_MS, signal, ...rest } = init ?? {}
  const timeoutAc = new AbortController()
  const timer = window.setTimeout(() => timeoutAc.abort(), timeoutMs)
  try {
    const res = await fetch(path, {
      headers: { 'Content-Type': 'application/json', ...(rest.headers || {}) },
      ...rest,
      signal: mergeSignals(signal ?? undefined, timeoutAc.signal),
    })
    if (!res.ok) {
      const err = await res.json().catch(() => ({ detail: res.statusText }))
      throw new Error(typeof err.detail === 'string' ? err.detail : JSON.stringify(err.detail))
    }
    return res.json() as Promise<T>
  } catch (e) {
    if (timeoutAc.signal.aborted && !(signal as AbortSignal | undefined)?.aborted) {
      throw new Error(`Таймаут запроса (${Math.round(timeoutMs / 1000)} с) — API не отвечает`)
    }
    throw e
  } finally {
    window.clearTimeout(timer)
  }
}

export function getHealth() {
  return api<{
    ok: boolean
    lookback_days: number
    bars_cache?: unknown
    features?: { llm?: boolean }
    api_build?: number
  }>('/api/health', { timeoutMs: 5_000 })
}

export function getPresets() {
  return api<Presets>('/api/presets')
}

export function getDataStatus(path: string) {
  return api<Record<string, unknown>>(`/api/data/status?path=${encodeURIComponent(path)}`)
}

export function downloadMoex(path: string) {
  return api<Record<string, unknown>>('/api/data/download', {
    method: 'POST',
    body: JSON.stringify({ path }),
  })
}

export function simulate(params: SimParams, signal?: AbortSignal) {
  return api<SimResponse>('/api/simulate', {
    method: 'POST',
    body: JSON.stringify(params),
    signal,
    timeoutMs: SIMULATE_TIMEOUT_MS,
  })
}

export function sweep(body: Record<string, unknown>) {
  return api<{ rows: Record<string, number>[]; best: Record<string, number> }>('/api/sweep', {
    method: 'POST',
    body: JSON.stringify(body),
    timeoutMs: 120_000,
  })
}

export function gridSearch(body: GridSearchRequest) {
  return api<GridSearchResponse>('/api/grid-search', {
    method: 'POST',
    body: JSON.stringify(body),
    timeoutMs: GRID_SEARCH_TIMEOUT_MS,
  })
}

export type LlmMarketSeriesResponse = {
  path: string
  bar_count_total: number
  bar_count_returned: number
  truncated: boolean
  bars: SimPack['zscore']
}

export function fetchLlmMarketSeries(csvPath: string, maxBars = 16_000) {
  const q = new URLSearchParams({
    path: csvPath,
    max_bars: String(maxBars),
  })
  return api<LlmMarketSeriesResponse>(`/api/llm/market-series?${q}`, { timeoutMs: 60_000 })
}

export function fmtRub(v: number) {
  return `${Math.round(v).toLocaleString('ru-RU')} ₽`
}

export function fmtPf(v: number | null) {
  return v == null ? '∞' : v.toFixed(2)
}

export type MarketLiveResponse = {
  ok: boolean
  refreshed?: boolean
  tick_at?: string
  zscore: { time: number; z_score: number; spread_percent: number }[]
  latest_quote?: Record<string, unknown>
  data_meta?: Record<string, unknown>
  last_ts?: string
}

export function refreshMoexTail(path: string) {
  return api<Record<string, unknown>>('/api/data/refresh-tail', {
    method: 'POST',
    body: JSON.stringify({ path }),
    timeoutMs: 60_000,
  })
}

export function fetchMarketLive(path: string, zMode: 'rolling30' | 'global' = 'rolling30') {
  const q = new URLSearchParams({ path, z_mode: zMode })
  return api<MarketLiveResponse>(`/api/market/live?${q}`, { timeoutMs: 45_000 })
}

export function fetchBasisScan(params: {
  fin_rate: number
  min_yield_ann: number
  z_entry: number
  history_days?: number
}) {
  const q = new URLSearchParams({
    fin_rate: String(params.fin_rate),
    min_yield_ann: String(params.min_yield_ann),
    z_entry: String(params.z_entry),
    history_days: String(params.history_days ?? 90),
  })
  return api<BasisScanResponse>(`/api/basis/scan?${q}`, { timeoutMs: 120_000 })
}
