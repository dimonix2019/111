import type { SimPack, TradeMarker } from '@/types'

const DAILY_SIGNAL_MAX = 20

export { DAILY_SIGNAL_MAX }

export function lastZscorePoint(pack: SimPack) {
  const z = pack.zscore
  return z.length ? z[z.length - 1]! : null
}

export function positionLabel(pack: SimPack): string {
  if (!pack.market_context?.in_position) return 'FLAT'
  const dir = openDirectionFromMarkers(pack.trade_markers)
  if (dir === 'LONG') return 'LONG спрэд (TATN / TATNP)'
  if (dir === 'SHORT') return 'SHORT спрэд'
  return 'В позиции'
}

function openDirectionFromMarkers(markers: TradeMarker[]): string | null {
  const entries = markers.filter((m) => m.event === 'вход')
  const exits = markers.filter((m) => m.event !== 'вход')
  if (entries.length <= exits.length) return null
  return entries[entries.length - 1]!.direction
}

export function signalsTodayCount(markers: TradeMarker[]): number {
  const today = new Date().toISOString().slice(0, 10)
  return markers.filter((m) => {
    if (m.event !== 'вход') return false
    const d = new Date(m.time * 1000).toISOString().slice(0, 10)
    return d === today
  }).length
}

export type ZZone = 'long' | 'short' | 'neutral'

export function zZone(z: number, entry: number): ZZone {
  if (z >= entry) return 'short'
  if (z <= -entry) return 'long'
  return 'neutral'
}

export function zZoneLabel(zone: ZZone): string {
  if (zone === 'long') return 'Зона LONG (Z ≤ −Entry)'
  if (zone === 'short') return 'Зона SHORT (Z ≥ +Entry)'
  return 'Нейтральная зона'
}
