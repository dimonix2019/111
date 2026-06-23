import { MARKET_PERIODS, type MarketPeriod } from '@/lib/marketPeriod'

type Props = {
  selected: MarketPeriod
  onSelect: (p: MarketPeriod) => void
}

export function PeriodSelector({ selected, onSelect }: Props) {
  return (
    <div className="flex flex-wrap gap-2">
      {MARKET_PERIODS.map((p) => (
        <button
          key={p.id}
          type="button"
          className={`market-period-btn ${selected === p.id ? 'active' : ''}`}
          onClick={() => onSelect(p.id)}
        >
          {p.label}
        </button>
      ))}
    </div>
  )
}
