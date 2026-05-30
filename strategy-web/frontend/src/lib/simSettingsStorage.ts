import type { SimParams, ZMode } from '@/types'

const STORAGE_KEY = 'z-strategy-web.sim-settings'
const STORAGE_VERSION = 1

export const DEFAULT_CSV_PATH = 'data/m15_tatn_255d.csv'

export function defaultSimParams(): SimParams {
  return {
    z_mode: 'rolling30',
    entry: 0.8,
    exit_z: 0.7,
    notional: 100_000,
    leverage: 7,
    commission: 0.04,
    compound: false,
    entry_a: 0.8,
    exit_a: 0.7,
    notional_a: 100_000,
    entry_b: 1.0,
    exit_b: 0.7,
    notional_b: 100_000,
    slippage: 0,
    max_loss_spread: 0,
    max_loss_rub: 0,
    min_spread: 0,
    max_spread: 0,
    entry_z_buffer: 0,
    max_dd_halt_rub: 0,
    max_dd_halt_pct: 0,
    oos_enabled: false,
    oos_train_ratio: 0.7,
  }
}

export type PersistedSimSettings = {
  params: SimParams
  csvPath: string
  compare: boolean
  liveMode: boolean
  preset: string
}

function defaultSettings(): PersistedSimSettings {
  return {
    params: defaultSimParams(),
    csvPath: DEFAULT_CSV_PATH,
    compare: false,
    liveMode: true,
    preset: 'Свои',
  }
}

function finiteNum(v: unknown, fallback: number): number {
  const n = typeof v === 'number' ? v : parseFloat(String(v ?? ''))
  return Number.isFinite(n) ? n : fallback
}

function finiteBool(v: unknown, fallback: boolean): boolean {
  return typeof v === 'boolean' ? v : fallback
}

function parseZMode(v: unknown, fallback: ZMode): ZMode {
  return v === 'global' || v === 'rolling30' ? v : fallback
}

function mergeParams(raw: unknown): SimParams {
  const d = defaultSimParams()
  const s = raw && typeof raw === 'object' ? (raw as Record<string, unknown>) : {}
  return {
    z_mode: parseZMode(s.z_mode, d.z_mode!),
    entry: finiteNum(s.entry, d.entry!),
    exit_z: finiteNum(s.exit_z, d.exit_z!),
    notional: finiteNum(s.notional, d.notional!),
    leverage: finiteNum(s.leverage, d.leverage!),
    commission: finiteNum(s.commission, d.commission!),
    compound: finiteBool(s.compound, !!d.compound),
    entry_a: finiteNum(s.entry_a, d.entry_a!),
    exit_a: finiteNum(s.exit_a, d.exit_a!),
    notional_a: finiteNum(s.notional_a, d.notional_a!),
    entry_b: finiteNum(s.entry_b, d.entry_b!),
    exit_b: finiteNum(s.exit_b, d.exit_b!),
    notional_b: finiteNum(s.notional_b, d.notional_b!),
    slippage: finiteNum(s.slippage, d.slippage!),
    max_loss_spread: finiteNum(s.max_loss_spread, d.max_loss_spread!),
    max_loss_rub: finiteNum(s.max_loss_rub, d.max_loss_rub!),
    min_spread: finiteNum(s.min_spread, d.min_spread!),
    max_spread: finiteNum(s.max_spread, d.max_spread!),
    entry_z_buffer: finiteNum(s.entry_z_buffer, d.entry_z_buffer!),
    max_dd_halt_rub: finiteNum(s.max_dd_halt_rub, d.max_dd_halt_rub!),
    max_dd_halt_pct: finiteNum(s.max_dd_halt_pct, d.max_dd_halt_pct!),
    oos_enabled: finiteBool(s.oos_enabled, !!d.oos_enabled),
    oos_train_ratio: finiteNum(s.oos_train_ratio, d.oos_train_ratio!),
  }
}

export function loadPersistedSimSettings(): PersistedSimSettings {
  const fallback = defaultSettings()
  if (typeof window === 'undefined') return fallback
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return fallback
    const parsed = JSON.parse(raw) as Record<string, unknown>
    if (parsed.v !== STORAGE_VERSION) return fallback
    const csvPath =
      typeof parsed.csvPath === 'string' && parsed.csvPath.trim() ? parsed.csvPath.trim() : fallback.csvPath
    return {
      params: mergeParams(parsed.params),
      csvPath,
      compare: finiteBool(parsed.compare, fallback.compare),
      liveMode: finiteBool(parsed.liveMode, fallback.liveMode),
      preset: typeof parsed.preset === 'string' && parsed.preset ? parsed.preset : fallback.preset,
    }
  } catch {
    return fallback
  }
}

export function savePersistedSimSettings(settings: PersistedSimSettings): void {
  if (typeof window === 'undefined') return
  try {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        v: STORAGE_VERSION,
        params: settings.params,
        csvPath: settings.csvPath,
        compare: settings.compare,
        liveMode: settings.liveMode,
        preset: settings.preset,
      }),
    )
  } catch {
    /* quota / private mode */
  }
}
