import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import type { SimPack, Trade } from '@/types'
import { Panel } from '@/components/ui/Panel'
import { buildStrategyChatContext } from '@/lib/analyticsContext'
import { CHAT_SYSTEM_PROMPT, getLlmStatus, llmChat, type ChatMessage } from '@/lib/llmApi'

type Props = {
  pack: SimPack
  filteredTrades: Trade[]
}

type UiMessage = ChatMessage & { id: string }

const STARTERS = [
  'Когда лучше входить по времени суток?',
  'Почему win rate такой высокий — это устойчиво?',
  'LONG или SHORT приносит больше?',
  'Какие риски видишь в этой выборке?',
]

export function StrategyChatPanel({ pack, filteredTrades }: Props) {
  const [messages, setMessages] = useState<UiMessage[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [llmOk, setLlmOk] = useState<boolean | null>(null)
  const bottomRef = useRef<HTMLDivElement>(null)

  const context = useMemo(
    () =>
      buildStrategyChatContext({
        pack,
        filteredTrades,
        totalTrades: pack.trades.length,
      }),
    [pack, filteredTrades],
  )

  useEffect(() => {
    void getLlmStatus()
      .then((s) => setLlmOk(s.ok))
      .catch(() => setLlmOk(false))
  }, [])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, loading])

  const send = useCallback(
    async (text: string) => {
      const q = text.trim()
      if (!q || loading) return
      setError(null)
      const userMsg: UiMessage = { id: `u-${Date.now()}`, role: 'user', content: q }
      const next = [...messages, userMsg]
      setMessages(next)
      setInput('')
      setLoading(true)
      try {
        const status = await getLlmStatus()
        setLlmOk(status.ok)
        if (!status.ok) {
          throw new Error(status.error ?? 'LM Studio недоступен')
        }
        const apiMessages: ChatMessage[] = [
          {
            role: 'system',
            content: `${CHAT_SYSTEM_PROMPT}\n\nКонтекст симуляции:\n${context}`,
          },
          ...next.map(({ role, content }) => ({ role, content })),
        ]
        const res = await llmChat(apiMessages, { temperature: 0.45, max_tokens: 700 })
        setMessages((prev) => [
          ...prev,
          { id: `a-${Date.now()}`, role: 'assistant', content: res.content },
        ])
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e))
      } finally {
        setLoading(false)
      }
    },
    [context, loading, messages],
  )

  return (
    <Panel title="Вопросы по стратегии">
      <p className="mb-2 text-[11px] text-ink-3">
        Чат с локальной моделью LM Studio · Entry {pack.entry} · Exit {pack.exit_z}
        {llmOk === true ? (
          <span className="ml-2 text-good">подключено</span>
        ) : llmOk === false ? (
          <span className="ml-2 text-bad">LM Studio offline</span>
        ) : null}
      </p>

      <div className="mb-2 flex flex-wrap gap-1.5">
        {STARTERS.map((q) => (
          <button
            key={q}
            type="button"
            className="tab-btn !px-2 !py-1 text-[10px]"
            onClick={() => setInput(q)}
          >
            {q}
          </button>
        ))}
      </div>

      <div className="scrollbar-thin mb-2 max-h-64 space-y-2 overflow-y-auto rounded-lg border border-panel-border-soft bg-[rgba(8,16,28,0.35)] p-3">
        {messages.length === 0 ? (
          <p className="text-[11px] text-ink-3">
            Спросите про сделки, время входа/выхода, PnL, риски или параметры Entry/Exit.
          </p>
        ) : (
          messages.map((m) => (
            <div
              key={m.id}
              className={`rounded-md px-2.5 py-2 text-[11px] leading-relaxed ${
                m.role === 'user'
                  ? 'ml-8 bg-[rgba(212,184,106,0.12)] text-ink-1'
                  : 'mr-4 bg-panel-2/60 text-ink-2'
              }`}
            >
              {m.content}
            </div>
          ))
        )}
        {loading ? <p className="text-[11px] text-ink-3">Модель отвечает…</p> : null}
        <div ref={bottomRef} />
      </div>

      {error ? <p className="mb-2 text-[11px] text-bad">{error}</p> : null}

      {loading ? (
        <p className="mb-2 text-[10px] text-ink-3">Модель отвечает… поле ввода можно редактировать.</p>
      ) : null}

      <form
        className="flex gap-2"
        onSubmit={(e) => {
          e.preventDefault()
          void send(input)
        }}
      >
        <textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Ваш вопрос по стратегии…"
          rows={2}
          className="min-h-[52px] flex-1 resize-y !py-2 text-[12px]"
          aria-label="Ваш вопрос по стратегии"
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              if (!loading) void send(input)
            }
          }}
        />
        <button
          type="submit"
          className="primary !w-auto self-end !px-4 !py-2 text-[12px]"
          disabled={loading || !input.trim()}
        >
          {loading ? '…' : 'Спросить'}
        </button>
      </form>
    </Panel>
  )
}
