import type { SimPack } from '@/types'
import {
  formatTradeFieldTime,
  formatTradePnlRub,
  getLastTradeSnapshot,
} from '@/lib/lastTradeSnapshot'

type Props = {
  pack: SimPack
}

function Field({ label, value, tone }: { label: string; value: string; tone?: 'good' | 'bad' | 'neutral' }) {
  const toneClass =
    tone === 'good' ? 'text-good' : tone === 'bad' ? 'text-bad' : tone === 'neutral' ? 'text-warn' : 'text-ink-1'
  return (
    <span className="mobile-last-trade-field">
      <span className="text-ink-3">{label}</span>{' '}
      <span className={`font-semibold tabular-nums ${toneClass}`}>{value}</span>
    </span>
  )
}

export function MobileLastTradeCard({ pack }: Props) {
  const snap = getLastTradeSnapshot(pack)
  const t = snap.trade
  const isOpen = snap.status === 'open'

  if (snap.status === 'none' || !t) {
    return (
      <div className="mobile-last-trade-card rounded-lg border border-surface-border-soft bg-[rgba(8,16,28,0.5)] px-3 py-2">
        <p className="text-[12px] font-semibold text-ink-2">Последняя сделка</p>
        <p className="mt-1 text-[11px] text-ink-3">Нет сделок в расчёте</p>
      </div>
    )
  }

  const pnlTone = t.pnl_rub > 0 ? 'good' : t.pnl_rub < 0 ? 'bad' : 'neutral'
  const dir = t.direction === 'LONG' ? 'L' : 'S'
  const comm = t.commission_rub ?? 0
  const ovn = t.overnight_rub ?? 0

  return (
    <div className="mobile-last-trade-card rounded-lg border border-surface-border-soft bg-[rgba(8,16,28,0.55)] px-3 py-2">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <p className="text-[12px] font-bold text-ink-1">Последняя сделка</p>
        <span
          className={`rounded-md px-2 py-0.5 text-[11px] font-bold ${
            isOpen ? 'bg-warn/15 text-warn' : 'bg-surface-inner text-ink-2'
          }`}
        >
          {snap.statusLabel}
        </span>
      </div>

      <div className="space-y-1.5 text-[10px] leading-snug">
        <div className="flex flex-wrap gap-x-3 gap-y-1">
          <Field label="№" value={String(t.no)} />
          <Field label="L/S" value={dir} />
          <Field label="PnL" value={formatTradePnlRub(t.pnl_rub, isOpen)} tone={pnlTone} />
        </div>
        <div className="flex flex-wrap gap-x-3 gap-y-1">
          <Field label="Вх" value={formatTradeFieldTime(t.entry_time, false)} />
          <Field label="Вых" value={formatTradeFieldTime(t.exit_time, isOpen)} />
        </div>
        <div className="flex flex-wrap gap-x-3 gap-y-1">
          <Field label="Zв" value={t.entry_z.toFixed(2)} />
          <Field label="Zы" value={t.exit_z.toFixed(2)} />
          <Field label="Spr" value={`${t.entry_spread.toFixed(2)}/${t.exit_spread.toFixed(2)}`} />
        </div>
        <div className="flex flex-wrap gap-x-3 gap-y-1">
          <Field label="Ком." value={isOpen && comm === 0 ? '—' : `${Math.round(comm).toLocaleString('ru-RU')}₽`} />
          <Field label="O/n" value={isOpen && ovn === 0 ? '—' : `${Math.round(ovn).toLocaleString('ru-RU')}₽`} />
          <Field label="SprΔ" value={t.pnl_spread_pts.toFixed(2)} />
        </div>
      </div>

      {isOpen ? (
        <p className="mt-2 text-[9px] text-ink-3">* PnL нереализованный · выход по последнему бару</p>
      ) : null}
    </div>
  )
}
