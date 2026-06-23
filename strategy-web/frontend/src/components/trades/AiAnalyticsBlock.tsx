import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import type { SimPack, Trade } from '@/types'
import { buildAnalyticsContext } from '@/lib/analyticsContext'
import { ANALYTICS_SYSTEM_PROMPT, getLlmStatus, llmChat } from '@/lib/llmApi'
import type { TimeGranularityMin } from '@/lib/tradeDistribution'

type Props = {
  trades: Trade[]
  totalCount: number
  metricId: string
  metricLabel: string
  timeGranularityMin: TimeGranularityMin
  pack?: SimPack
  enabled?: boolean
}

export function AiAnalyticsBlock({
  trades,
  totalCount,
  metricId,
  metricLabel,
  timeGranularityMin,
  pack,
  enabled = true,
}: Props) {
  const [text, setText] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [llmOk, setLlmOk] = useState<boolean | null>(null)
  const [model, setModel] = useState<string | null>(null)
  const reqId = useRef(0)

  const context = useMemo(
    () =>
      buildAnalyticsContext({
        trades,
        totalCount,
        metricId,
        metricLabel,
        timeGranularityMin,
        pack,
      }),
    [trades, totalCount, metricId, metricLabel, timeGranularityMin, pack],
  )

  const fetchAi = useCallback(async () => {
    if (!enabled || !trades.length) return
    const id = ++reqId.current
    setLoading(true)
    setError(null)
    try {
      const status = await getLlmStatus()
      if (id !== reqId.current) return
      setLlmOk(status.ok)
      if (!status.ok) {
        setError(status.error ?? 'LM Studio не отвечает')
        return
      }
      const res = await llmChat(
        [
          { role: 'system', content: ANALYTICS_SYSTEM_PROMPT },
          {
            role: 'user',
            content: `Проанализируй распределение по показателю «${metricLabel}»:\n\n${context}`,
          },
        ],
        { temperature: 0.35, max_tokens: 900 },
      )
      if (id !== reqId.current) return
      setText(res.content)
      setModel(res.model)
    } catch (e) {
      if (id !== reqId.current) return
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      if (id === reqId.current) setLoading(false)
    }
  }, [context, enabled, metricLabel, trades.length])

  useEffect(() => {
    if (!enabled || !trades.length) {
      setText('')
      setError(null)
      return
    }
    const timer = window.setTimeout(() => {
      void fetchAi()
    }, 600)
    return () => window.clearTimeout(timer)
  }, [context, enabled, fetchAi, trades.length])

  return (
    <div className="rounded-md border border-panel-border-soft bg-panel-2/40 px-3 py-2">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <div className="text-[11px] font-semibold uppercase tracking-wide text-ink-3">
          Аналитика ИИ · {metricLabel}
        </div>
        <div className="flex items-center gap-2">
          {llmOk === true ? (
            <span className="text-[10px] text-good">LM Studio ✓</span>
          ) : llmOk === false ? (
            <span className="text-[10px] text-bad">LM Studio ✗</span>
          ) : null}
          <button
            type="button"
            className="tab-btn !px-2 !py-0.5 text-[10px]"
            disabled={loading || !trades.length}
            onClick={() => void fetchAi()}
          >
            {loading ? 'Думаю…' : 'Обновить'}
          </button>
        </div>
      </div>

      {!trades.length ? (
        <p className="text-[11px] text-ink-3">Нет сделок для анализа</p>
      ) : loading && !text ? (
        <p className="text-[11px] text-ink-3">Запрос к модели… (может занять 30–90 с)</p>
      ) : error ? (
        <p className="text-[11px] leading-snug text-bad">{error}</p>
      ) : text ? (
        <div className="space-y-2">
          {text.split(/\n\n+/).map((para) => (
            <p key={para.slice(0, 48)} className="text-[11px] leading-relaxed text-ink-2">
              {para.trim()}
            </p>
          ))}
          {model ? <p className="text-[9px] text-ink-3">Модель: {model}</p> : null}
        </div>
      ) : (
        <p className="text-[11px] text-ink-3">Нажмите «Обновить» для генерации</p>
      )}
    </div>
  )
}
