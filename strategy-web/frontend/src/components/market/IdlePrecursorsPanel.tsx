import type { IdlePrecursors } from '@/types'

type Props = {
  data: IdlePrecursors | undefined
  loading?: boolean
  error?: string | null
}

function riskTone(score: number): string {
  if (score >= 65) return 'text-bad'
  if (score >= 40) return 'text-warn'
  return 'text-good'
}

function riskBarColor(score: number): string {
  if (score >= 65) return 'bg-bad'
  if (score >= 40) return 'bg-warn'
  return 'bg-good'
}

const EVENT_KIND_LABELS: Record<string, string> = {
  div_announce: 'див. — рекомендация СД',
  div_registry: 'див. — отсечка / реестр',
  agm: 'ГОСА',
  конец_месяца: 'конец месяца',
  конец_квартала: 'конец квартала',
}

function tagLabel(tag: string): string {
  return EVENT_KIND_LABELS[tag] ?? tag
}

function strengthLabel(strength: 'none' | 'weak' | 'moderate'): string {
  if (strength === 'moderate') return 'заметная'
  if (strength === 'weak') return 'частичная'
  return 'слабая'
}

export function IdlePrecursorsPanel({ data, loading, error }: Props) {
  if (loading) {
    return (
      <div className="surface-inner space-y-2 p-3">
        <p className="text-[13px] font-bold text-ink-1">Предвестники простоя (TA по Z)</p>
        <p className="text-[11px] text-ink-2">Считаем TA по ряду Z-score…</p>
      </div>
    )
  }

  if (error) {
    return (
      <div className="surface-inner space-y-2 border border-warn/30 p-3">
        <p className="text-[13px] font-bold text-ink-1">Предвестники простоя (TA по Z)</p>
        <p className="text-[11px] text-warn">{error}</p>
        <p className="text-[10px] text-ink-2">
          Закройте окно «Z-Strategy API» и снова запустите run_tester.bat — нужен API с поддержкой
          idle_precursors.
        </p>
      </div>
    )
  }

  if (!data) return null

  const score = data.risk_score ?? 0
  const signs = data.signs ?? []

  return (
    <div className="surface-inner space-y-3 p-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <p className="text-[13px] font-bold text-ink-1">Предвестники простоя (TA по Z)</p>
          <p className="mt-0.5 text-[11px] text-ink-2">
            Z-score как отдельный актив: тренд, сжатие, флэт в нейтрали
          </p>
        </div>
        <p className={`text-2xl font-bold tabular-nums ${riskTone(score)}`}>{score}</p>
      </div>

      <div className="h-2 overflow-hidden rounded-full bg-[rgba(148,163,184,0.15)]">
        <div
          className={`h-full rounded-full transition-all ${riskBarColor(score)}`}
          style={{ width: `${Math.min(100, score)}%` }}
        />
      </div>
      <p className="text-[11px] leading-relaxed text-slate-200">{data.summary}</p>

      {data.duration_forecast ? (
        <div
          className={`rounded-xl border px-3 py-2.5 ${
            data.duration_forecast.applicable
              ? 'border-cyan-500/30 bg-cyan-500/10'
              : 'border-slate-500/30 bg-slate-800/40'
          }`}
        >
          <p className="text-[12px] font-bold text-ink-1">Прогноз простоя (история)</p>
          {data.duration_forecast.applicable &&
          data.duration_forecast.median_remaining_days != null &&
          (data.duration_forecast.current_idle_days ?? 0) > 0 ? (
            <div className="mt-2 flex flex-wrap items-baseline gap-x-3 gap-y-1">
              <span className="text-xl font-bold tabular-nums text-cyan-200">
                ещё ~{data.duration_forecast.median_remaining_days} дн.
              </span>
              {data.duration_forecast.median_total_days != null ? (
                <span className="text-[11px] text-slate-300">
                  итого ~{data.duration_forecast.median_total_days} дн.
                </span>
              ) : null}
              {data.duration_forecast.percentile_vs_history != null ? (
                <span className="text-[10px] text-slate-400">
                  длиннее {data.duration_forecast.percentile_vs_history}% прошлых пауз
                </span>
              ) : null}
            </div>
          ) : data.duration_forecast.applicable &&
            data.duration_forecast.median_total_days != null ? (
            <p className="mt-1 text-xl font-bold tabular-nums text-cyan-200">
              ~{data.duration_forecast.median_total_days} дн.
            </p>
          ) : null}
          <p className="mt-1.5 text-[11px] leading-snug text-slate-200">
            {data.duration_forecast.label}
          </p>
          {data.duration_forecast.applicable &&
          data.duration_forecast.p25_remaining_days != null &&
          data.duration_forecast.p75_remaining_days != null ? (
            <p className="mt-1 text-[10px] text-slate-400">
              {(data.duration_forecast.current_idle_days ?? 0) > 0
                ? 'Остаток паузы (P25–P75): '
                : 'Длина паузы (P25–P75): '}
              {data.duration_forecast.p25_remaining_days}–{data.duration_forecast.p75_remaining_days} дн.
              {data.duration_forecast.similar_gaps_count != null
                ? ` · ${data.duration_forecast.similar_gaps_count} похожих пауз в истории`
                : ''}
            </p>
          ) : null}
        </div>
      ) : null}

      {data.event_correlation ? (
        <details className="group rounded-lg border border-slate-500/30 bg-slate-900/30">
          <summary className="cursor-pointer list-none px-3 py-2 text-[12px] font-semibold text-slate-200 marker:content-none hover:text-ink-1 [&::-webkit-details-marker]:hidden">
            <span className="inline-flex items-center gap-2">
              <span className="text-slate-400 transition group-open:rotate-90">▸</span>
              Календарь TATN и паузы (корреляция)
              {data.event_correlation.matches_count > 0 ? (
                <span className="font-normal text-cyan-300/90">
                  · {data.event_correlation.matches_count} совпад.
                </span>
              ) : null}
            </span>
          </summary>
          <div className="space-y-2 border-t border-slate-500/25 px-3 pb-2 pt-2">
            <p className="text-[11px] leading-snug text-slate-200">{data.event_correlation.summary}</p>
            <p className="text-[10px] text-slate-400">
              Длинных пауз ≥7 дн.: {data.event_correlation.long_gaps_count} · окно ±
              {data.event_correlation.window_days} дн. · связь с дивидендами/СД:{' '}
              {strengthLabel(data.event_correlation.strength)}
            </p>

            {data.event_correlation.events.length > 0 ? (
              <div>
                <p className="mb-1 text-[11px] font-semibold text-ink-2">События бумаги TATN (ручной календарь)</p>
                <ul className="space-y-1 text-[10px] text-slate-300">
                  {data.event_correlation.events.map((ev) => (
                    <li key={`${ev.kind}-${ev.date}`}>
                      <span className="tabular-nums text-ink-2">{ev.date.slice(0, 10)}</span>{' '}
                      <span className="text-cyan-300/80">[{tagLabel(ev.kind)}]</span> {ev.label}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}

            {data.event_correlation.rows.length > 0 ? (
              <div>
                <p className="mb-1 text-[11px] font-semibold text-ink-2">Длинные паузы и теги</p>
                <ul className="space-y-1.5">
                  {data.event_correlation.rows.map((row) => (
                    <li
                      key={`${row.from}-${row.to}`}
                      className="rounded-lg border border-slate-600/40 bg-slate-800/40 px-2 py-1.5 text-[10px] text-slate-200"
                    >
                      <span className="font-semibold tabular-nums">{row.days} дн.</span>{' '}
                      <span className="text-slate-400">
                        {row.from.slice(0, 10)} → {row.to.slice(0, 10)}
                      </span>
                      {row.tags.length > 0 ? (
                        <p className="mt-0.5 text-cyan-200/90">{row.tags.map(tagLabel).join(' · ')}</p>
                      ) : (
                        <p className="mt-0.5 text-slate-500">без календаря/событий в окне</p>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </div>
        </details>
      ) : null}

      {signs.length > 0 || Object.keys(data.features ?? {}).length > 0 ? (
        <details className="group rounded-lg border border-slate-500/30 bg-slate-900/30">
          <summary className="cursor-pointer list-none px-3 py-2 text-[12px] font-semibold text-slate-200 marker:content-none hover:text-ink-1 [&::-webkit-details-marker]:hidden">
            <span className="inline-flex items-center gap-2">
              <span className="text-slate-400 transition group-open:rotate-90">▸</span>
              Технические показатели и ТА
              {signs.some((s) => s.active) ? (
                <span className="font-normal text-cyan-300/90">
                  · {signs.filter((s) => s.active).length} активн.
                </span>
              ) : null}
            </span>
          </summary>
          <div className="space-y-2 border-t border-slate-500/25 px-2 pb-2 pt-1">
            <ul className="space-y-1.5">
              {signs.map((s) => (
                <li
                  key={s.id}
                  className={`rounded-lg border px-2.5 py-1.5 text-[11px] ${
                    s.active
                      ? 'border-cyan-500/40 bg-cyan-500/10'
                      : 'border-slate-500/35 bg-slate-800/45'
                  }`}
                >
                  <span
                    className={`font-semibold ${s.active ? 'text-ink-1' : 'text-slate-200'}`}
                  >
                    {s.title}
                  </span>
                  {s.active ? <span className="ml-1.5 text-cyan-300">●</span> : null}
                  <p
                    className={`mt-0.5 leading-snug ${s.active ? 'text-slate-200' : 'text-slate-300'}`}
                  >
                    {s.label}
                  </p>
                </li>
              ))}
            </ul>
            {Object.keys(data.features ?? {}).length > 0 ? (
              <dl className="grid grid-cols-2 gap-x-3 gap-y-1 px-1 text-[10px] tabular-nums">
                {Object.entries(data.features ?? {}).slice(0, 12).map(([k, v]) => (
                  <div key={k}>
                    <dt className="text-slate-400">{k}</dt>
                    <dd className="text-slate-200">
                      {typeof v === 'number' ? v.toFixed(3) : String(v)}
                    </dd>
                  </div>
                ))}
              </dl>
            ) : null}
          </div>
        </details>
      ) : null}
    </div>
  )
}
