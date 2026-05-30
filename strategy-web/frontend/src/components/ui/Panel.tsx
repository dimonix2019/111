import type { ReactNode } from 'react'

export function Panel({
  className = '',
  children,
  title,
}: {
  className?: string
  children: ReactNode
  title?: string
}) {
  return (
    <div
      className={[
        'shrink-0 overflow-hidden rounded-2xl border border-surface-border shadow-panel',
        'bg-[linear-gradient(165deg,rgba(17,28,50,0.94),rgba(13,20,37,0.98))]',
        className,
      ].join(' ')}
    >
      {title ? (
        <div className="border-b border-surface-border-soft px-5 py-3 text-[13px] font-semibold tracking-wide text-ink-1">
          {title}
        </div>
      ) : null}
      <div className="p-5">{children}</div>
    </div>
  )
}
