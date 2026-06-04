import type { ReactNode } from 'react'

export function Panel({
  className = '',
  children,
  title,
  compact = false,
}: {
  className?: string
  children: ReactNode
  title?: string
  compact?: boolean
}) {
  return (
    <div
      className={[
        'shrink-0 overflow-hidden border border-surface-border shadow-panel',
        compact ? 'rounded-lg' : 'rounded-2xl',
        'bg-[linear-gradient(165deg,rgba(17,28,50,0.94),rgba(13,20,37,0.98))]',
        className,
      ].join(' ')}
    >
      {title ? (
        <div
          className={`border-b border-surface-border-soft font-semibold tracking-wide text-ink-1 ${
            compact ? 'px-2 py-1 text-[10px]' : 'px-5 py-3 text-[13px]'
          }`}
        >
          {title}
        </div>
      ) : null}
      <div className={compact ? 'p-2' : 'p-5'}>{children}</div>
    </div>
  )
}
