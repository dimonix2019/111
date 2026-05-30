import type { OosResult, OosVerdict } from '@/types'
import { fmtPf, fmtRub } from '@/lib/api'
import { resolveOosVerdict } from '@/lib/oosVerdict'
import { Panel } from '@/components/ui/Panel'

type Props = {
  oos?: OosResult | null
}

const GRADE_STYLES: Record<
  OosVerdict['grade'],
  { badge: string; border: string; bg: string }
> = {
  good: {
    badge: 'text-good border-good/40 bg-[rgba(74,222,154,0.1)]',
    border: 'border-good/30',
    bg: 'bg-[rgba(74,222,154,0.06)]',
  },
  caution: {
    badge: 'text-warn border-warn/40 bg-[rgba(212,184,106,0.12)]',
    border: 'border-warn/30',
    bg: 'bg-[rgba(212,184,106,0.06)]',
  },
  overfit: {
    badge: 'text-bad border-bad/40 bg-[rgba(209,122,136,0.12)]',
    border: 'border-bad/30',
    bg: 'bg-[rgba(209,122,136,0.06)]',
  },
  weak_data: {
    badge: 'text-ink-2 border-panel-border-soft bg-[rgba(8,16,28,0.5)]',
    border: 'border-panel-border-soft',
    bg: 'bg-[rgba(8,16,28,0.35)]',
  },
}

function SideMetrics({ title, stats }: { title: string; stats: OosResult['train'] }) {
  return (
    <div className="surface-inner p-3">
      <h4 className="mb-2 text-[12px] font-semibold text-ink-2">{title}</h4>
      <dl className="space-y-1.5 text-[12px]">
        <div className="flex justify-between gap-2">
          <dt className="text-ink-3">PnL</dt>
          <dd className={`tabular-nums ${stats.total_pnl_rub >= 0 ? 'text-good' : 'text-bad'}`}>
            {fmtRub(stats.total_pnl_rub)}
          </dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt className="text-ink-3">Сделок</dt>
          <dd className="tabular-nums text-ink-1">{stats.trade_count}</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt className="text-ink-3">Win Rate</dt>
          <dd className="tabular-nums text-ink-1">{stats.win_rate_pct.toFixed(1)}%</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt className="text-ink-3">Profit Factor</dt>
          <dd className="tabular-nums text-ink-1">{fmtPf(stats.profit_factor)}</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt className="text-ink-3">Max DD</dt>
          <dd className="tabular-nums text-bad">{fmtRub(stats.max_drawdown_rub)}</dd>
        </div>
      </dl>
    </div>
  )
}

function OosVerdictBlock({ verdict }: { verdict: OosVerdict }) {
  const st = GRADE_STYLES[verdict.grade]
  return (
    <div className={`mt-4 rounded-xl2 border p-3 ${st.border} ${st.bg}`}>
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <span className={`rounded-full border px-2.5 py-0.5 text-[11px] font-semibold ${st.badge}`}>
          {verdict.title}
        </span>
      </div>
      <p className="text-[12px] leading-relaxed text-ink-1">{verdict.summary}</p>
      {verdict.signals.length ? (
        <div className="mt-3">
          <h5 className="mb-1.5 text-[11px] font-semibold text-ink-2">Что видно по цифрам</h5>
          <ul className="space-y-1 text-[11px] text-ink-3">
            {verdict.signals.map((s) => (
              <li key={s} className="flex gap-2">
                <span>·</span>
                <span>{s}</span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      {verdict.actions.length ? (
        <div className="mt-3">
          <h5 className="mb-1.5 text-[11px] font-semibold text-ink-2">Что делать</h5>
          <ul className="space-y-1 text-[11px] text-ink-2">
            {verdict.actions.map((a) => (
              <li key={a} className="flex gap-2">
                <span className="text-good">→</span>
                <span>{a}</span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  )
}

export function OosPanel({ oos }: Props) {
  if (!oos) return null
  if (oos.error) {
    return (
      <Panel title="OOS train / test">
        <p className="text-[12px] text-bad">{oos.error}</p>
      </Panel>
    )
  }

  const trainPct = Math.round(oos.train_ratio * 100)
  const testPct = 100 - trainPct
  const verdict = resolveOosVerdict(oos)

  return (
    <Panel title="OOS train / test">
      <OosVerdictBlock verdict={verdict} />
      <p className="mb-3 mt-4 text-[11px] text-ink-3">
        Разделение {trainPct}/{testPct} · split на баре {oos.split_bar} · Train ratio = {oos.train_ratio}
      </p>
      <div className="grid gap-3 sm:grid-cols-2">
        <SideMetrics title={`Train (${trainPct}%)`} stats={oos.train} />
        <SideMetrics title={`Test (${testPct}%)`} stats={oos.test} />
      </div>
      <div className="mt-3 grid gap-2 sm:grid-cols-2 text-[11px] text-ink-3">
        <div>
          Train tail ratio:{' '}
          <span className="text-ink-2">
            {oos.train_risk.tail_ratio != null ? oos.train_risk.tail_ratio.toFixed(2) : '—'}
          </span>
        </div>
        <div>
          Test tail ratio:{' '}
          <span className="text-ink-2">
            {oos.test_risk.tail_ratio != null ? oos.test_risk.tail_ratio.toFixed(2) : '—'}
          </span>
        </div>
      </div>
    </Panel>
  )
}
