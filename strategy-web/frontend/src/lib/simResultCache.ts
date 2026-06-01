import type { SimParams, SimResponse } from '@/types'

const DB_NAME = 'z-strategy-web'
const STORE_NAME = 'sim-results'
const DB_VERSION = 1
const CACHE_VERSION = 2

type CachedSimResult = {
  key: string
  savedAt: number
  result: SimResponse
}

function stableValue(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(stableValue)
  if (!v || typeof v !== 'object') return v
  return Object.keys(v as Record<string, unknown>)
    .sort()
    .reduce<Record<string, unknown>>((acc, k) => {
      const value = (v as Record<string, unknown>)[k]
      if (value !== undefined) acc[k] = stableValue(value)
      return acc
    }, {})
}

export function simResultCacheKey(csvPath: string, compare: boolean, params: SimParams): string {
  return JSON.stringify({
    v: CACHE_VERSION,
    csvPath,
    compare,
    params: stableValue({
      ...params,
      csv_path: undefined,
      auto_download: undefined,
      moex_live: undefined,
      compare_mode: undefined,
    }),
  })
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(STORE_NAME)) db.createObjectStore(STORE_NAME)
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

async function withStore<T>(mode: IDBTransactionMode, fn: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openDb()
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, mode)
    const req = fn(tx.objectStore(STORE_NAME))
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
    tx.oncomplete = () => db.close()
    tx.onerror = () => {
      db.close()
      reject(tx.error)
    }
  })
}

export async function loadCachedSimResult(key: string): Promise<CachedSimResult | null> {
  if (typeof indexedDB === 'undefined') return null
  try {
    const cached = await withStore<CachedSimResult | undefined>('readonly', (store) => store.get(key))
    return cached?.result?.packs?.length ? cached : null
  } catch {
    return null
  }
}

export async function saveCachedSimResult(key: string, result: SimResponse): Promise<void> {
  if (typeof indexedDB === 'undefined' || !result.packs?.length) return
  try {
    await withStore<IDBValidKey>('readwrite', (store) => store.put({ key, savedAt: Date.now(), result }, key))
  } catch {
    /* private mode / quota */
  }
}
