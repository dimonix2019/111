import type { IdleGaps } from '@/types'
import { HistogramBars } from '@/components/charts/HistogramBars'
import { Panel } from '@/components/ui/Panel'

const BUCKET_ORDER = ['0', '1', '2–3', '4–7', '8–14', '15–30', '31+']

export function IdleGapsChart({ data }: { data: IdleGaps }) {
  const currentBucket = (() => {
    const d = data.current.idle_days
    if (d === 0) return '0'
    if (d === 1) return '1'
    if (d <= 3) return '2–3'
    if (d <= 7) return '4–7'
    if (d <= 14) return '8–14'
    if (d <= 30) return '15–30'
    return '31+'
  })()

  const bars = BUCKET_ORDER.filter((b) => data.histogram.some((h) => h.bucket === b)).map((bucket) => {
    const row = data.histogram.find((h) => h.bucket === bucket)
    const highlight = !data.current.in_position && bucket === currentBucket && data.current.idle_days > 0
    return { bucket, count: row?.count ?? 0, highlight }
  })

  return (
    <Panel title="Дней без сделок (между входами)">
      <p
        className={`mb-4 text-[13px] font-medium ${data.current.in_position ? 'text-warn' : data.current.idle_days > 7 ? 'text-bad' : 'text-ink-2'}`}
      >
        {data.current.label}
      </p>
      <HistogramBars bars={bars} chartHeight={128} />
      {data.current.idle_days > 0 && !data.current.in_position ? (
        <p className="mt-2 text-[11px] text-ink-3">
          Текущая пауза выделена · с {data.current.since_display} · {data.current.idle_days} календ. дн.
        </p>
      ) : null}
    </Panel>
  )
}
