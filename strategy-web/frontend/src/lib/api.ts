import type { BasisScanResponse, IdlePrecursors, Presets, SimParams, SimResponse } from '@/types'

const DEFAULT_TIMEOUT_MS = 30_000
const SIMULATE_TIMEOUT_MS = 90_000

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
  return api<{ ok: boolean; lookback_days: number; bars_cache?: unknown }>('/api/health', {
    timeoutMs: 5_000,
  })
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

export function fmtRub(v: number) {
  return `${Math.round(v).toLocaleString('ru-RU')} ₽`
}

export function fmtPf(v: number | null) {
  return v == null ? '∞' : v.toFixed(2)
}
