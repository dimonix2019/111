export type LlmStatus = {
  ok: boolean
  base_url: string
  model: string
  models?: string[]
  error?: string
}

export type ChatMessage = {
  role: 'system' | 'user' | 'assistant'
  content: string
}

const LLM_TIMEOUT_MS = 120_000

async function llmFetch<T>(path: string, init?: RequestInit & { timeoutMs?: number }): Promise<T> {
  const timeoutMs = init?.timeoutMs ?? LLM_TIMEOUT_MS
  const ac = new AbortController()
  const timer = window.setTimeout(() => ac.abort(), timeoutMs)
  try {
    const res = await fetch(path, {
      headers: { 'Content-Type': 'application/json', ...(init?.headers || {}) },
      ...init,
      signal: ac.signal,
    })
    if (!res.ok) {
      if (res.status === 404 && path.startsWith('/api/llm')) {
        throw new Error(
          'API без маршрута /api/llm (старая версия на порту 8765). Закройте все окна «Z-Strategy API» и перезапустите run_tester.bat',
        )
      }
      const err = await res.json().catch(() => ({ detail: res.statusText }))
      throw new Error(typeof err.detail === 'string' ? err.detail : JSON.stringify(err.detail))
    }
    return res.json() as Promise<T>
  } catch (e) {
    if (ac.signal.aborted) {
      throw new Error(`Таймаут LM Studio (${Math.round(timeoutMs / 1000)} с)`)
    }
    throw e
  } finally {
    window.clearTimeout(timer)
  }
}

export function getLlmStatus() {
  return llmFetch<LlmStatus>('/api/llm/status', { timeoutMs: 8_000 })
}

export function llmChat(
  messages: ChatMessage[],
  opts?: { temperature?: number; max_tokens?: number; model?: string },
) {
  return llmFetch<{ content: string; model: string }>('/api/llm/chat', {
    method: 'POST',
    body: JSON.stringify({ messages, ...opts }),
    timeoutMs: LLM_TIMEOUT_MS,
  })
}

export const ANALYTICS_SYSTEM_PROMPT = `Ты аналитик Z-стратегии арбитража TATN/TATNP на MOEX (15-минутные бары).
На основе предоставленной статистики напиши связный текст на русском: 3–5 коротких абзацев с выводами, паттернами и практическими наблюдениями.
Используй только цифры из контекста — не выдумывай данные.
Укажи, что стоит проверить дальше. Без markdown-заголовков, простой текст.`

export const CHAT_SYSTEM_PROMPT = `Ты помощник по бэктесту Z-стратегии TATN/TATNP на MOEX (15м бары).
Отвечай на русском, опираясь ТОЛЬКО на данные в контексте ниже.

Структура контекста:
- СВОДКА и аналитика удержания
- СЦЕНАРИИ ВЫХОДА (PnL и Max DD) — baseline, Max Hold 12/24ч, правила A/B/C на 12ч
- TRADES — все сделки (TSV): Z, spread, PnL, цены/лоты TATN и TATNP на входе/выходе
- BARS_15M — прореженные 15м бары (~1200): z, spread, TATN/TATNP close; **все бары на entry/exit сделок сохранены**
- EQUITY — кривая капитала и просадка по барам

Ищи ответы в TSV, в блоке «СЦЕНАРИИ ВЫХОДА (PnL и Max DD)» и в «СЦЕНАРИИ MAX HOLD».
На вопросы про underwater-выход, spread>P90, z-divergence или Max DD сценариев — цитируй СЦЕНАРИИ ВЫХОДА.
На вопросы «закрыть через 6 часов» / «max hold 4/6/8ч» — цитируй СЦЕНАРИИ MAX HOLD.
Если данных не хватает — скажи честно. Будь конкретен, с цифрами из таблиц.`
