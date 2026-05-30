import type { IdleDurationForecast, IdlePrecursors, IdlePrecursorSign } from '@/types'

const BARS_PER_DAY = 26

function linSlope(y: number[]): number {
  const n = y.length
  if (n < 3) return 0
  let sx = 0
  let sy = 0
  for (let i = 0; i < n; i++) {
    sx += i
    sy += y[i]!
  }
  sx /= n
  sy /= n
  let num = 0
  let den = 0
  for (let i = 0; i < n; i++) {
    const dx = i - sx
    const dy = y[i]! - sy
    num += dx * dy
    den += dx * dx
  }
  return den > 1e-12 ? num / den : 0
}

function bbWidth(z: number[]): number {
  if (z.length < 5) return 0
  const mid = z.reduce((a, b) => a + b, 0) / z.length
  const variance = z.reduce((a, v) => a + (v - mid) ** 2, 0) / z.length
  const std = Math.sqrt(variance)
  return (4 * std) / Math.max(0.01, Math.abs(mid))
}

function atrZ(z: number[]): number {
  if (z.length < 2) return 0
  let s = 0
  for (let i = 1; i < z.length; i++) s += Math.abs(z[i]! - z[i - 1]!)
  return s / (z.length - 1)
}

function entryThresholdDistance(z: number, entry: number): number {
  if (z <= -entry || z >= entry) return 0
  return Math.min(z + entry, entry - z)
}

function zTaFeatures(z: number[], entry: number, exitZ: number): Record<string, number> {
  const n = z.length
  if (n < 10) return {}

  const cur = z[n - 1]!
  const wS = Math.min(32, n)
  const wM = Math.min(96, n)
  const wL = Math.min(192, n)
  const tailS = z.slice(n - wS)
  const tailM = z.slice(n - wM)
  const tailL = z.slice(n - wL)

  const stdS = Math.sqrt(tailS.reduce((a, v) => a + v ** 2, 0) / tailS.length - (tailS.reduce((a, b) => a + b, 0) / tailS.length) ** 2)
  const meanL = tailL.reduce((a, b) => a + b, 0) / tailL.length
  const stdL = Math.sqrt(tailL.reduce((a, v) => a + (v - meanL) ** 2, 0) / tailL.length)
  const squeeze = stdL > 1e-9 ? stdS / stdL : 1

  let neutralStreak = 0
  for (let i = n - 1; i >= 0; i--) {
    if (Math.abs(z[i]!) < entry) neutralStreak++
    else break
  }

  let barsSinceExtreme = 0
  for (let i = n - 1; i >= 0; i--) {
    if (Math.abs(z[i]!) < entry) barsSinceExtreme++
    else break
  }

  const maxAbsL = Math.max(...tailL.map((v) => Math.abs(v)))
  const minAbsTail = Math.min(...tailS.map((v) => Math.abs(v)))
  const trendThenFlat = maxAbsL >= entry && minAbsTail < exitZ && Math.abs(cur) < entry

  let path = 0
  for (let i = 1; i < tailM.length; i++) path += Math.abs(tailM[i]! - tailM[i - 1]!)
  const netMove = Math.abs(tailM[tailM.length - 1]! - tailM[0]!)
  const trendEfficiency = path > 1e-9 ? netMove / path : 0

  return {
    z: cur,
    slope_med: linSlope(tailM),
    atr_med: atrZ(tailM),
    bb_width_med: bbWidth(tailM),
    squeeze,
    neutral_streak_bars: neutralStreak,
    neutral_streak_days: neutralStreak / BARS_PER_DAY,
    bars_since_extreme: barsSinceExtreme,
    trend_then_flat: trendThenFlat ? 1 : 0,
    trend_efficiency: trendEfficiency,
    in_neutral: Math.abs(cur) < entry ? 1 : 0,
  }
}

function idleRiskScore(
  feat: Record<string, number>,
  entry: number,
  idleDays: number,
  inPosition: boolean,
): { risk: number; signs: IdlePrecursorSign[] } {
  const signs: IdlePrecursorSign[] = []
  let score = 0

  const add = (id: string, title: string, weight: number, fired: boolean, label: string) => {
    if (fired) score += weight
    signs.push({ id, title, active: fired, weight, label, level: fired && weight >= 18 ? 'high' : fired ? 'med' : 'low' })
  }

  const ns = feat.neutral_streak_bars ?? 0
  const nsDays = feat.neutral_streak_days ?? 0
  add(
    'neutral_streak',
    'Флэт в нейтрали |Z|',
    22,
    ns >= 48,
    `${Math.round(ns)} баров (~${nsDays.toFixed(1)} дн.) |Z| < Entry — входов нет`,
  )

  const squeeze = feat.squeeze ?? 1
  add('squeeze', 'Сжатие волатильности Z', 18, squeeze < 0.45, `Squeeze ${squeeze.toFixed(2)} — короткая σ сжата`)

  const ttf = (feat.trend_then_flat ?? 0) > 0.5
  const bse = feat.bars_since_extreme ?? 0
  add(
    'trend_flat',
    'Тренд Z → флэт',
    20,
    ttf && bse >= 16,
    `Был экстремум |Z|, затем ${Math.round(bse)} баров (~${(bse / BARS_PER_DAY).toFixed(1)} дн.) в нейтрали`,
  )

  const te = feat.trend_efficiency ?? 0
  const slope = Math.abs(feat.slope_med ?? 0)
  add('weak_trend', 'Слабый тренд (пила)', 12, te < 0.12 && slope < 0.02, `Эффективность тренда ${te.toFixed(2)}`)

  const z = feat.z ?? 0
  const dist = entryThresholdDistance(z, entry)
  add(
    'far_from_entry',
    'Далеко от порога входа',
    15,
    (feat.in_neutral ?? 0) > 0.5 && dist > 0.35,
    `Z=${z >= 0 ? '+' : ''}${z.toFixed(2)}, до ±Entry ~${dist.toFixed(2)} Z`,
  )

  if (idleDays >= 7) {
    score += 12
    signs.push({
      id: 'already_long_idle',
      title: 'Пауза уже длинная',
      active: true,
      weight: 12,
      label: `Без сделок ${Math.round(idleDays)} дн.`,
      level: 'high',
    })
  }

  if (inPosition) score *= 0.55

  return { risk: Math.max(0, Math.min(100, Math.round(score))), signs }
}

function percentile(arr: number[], q: number): number {
  if (!arr.length) return 0
  const sorted = [...arr].sort((a, b) => a - b)
  const pos = (q / 100) * (sorted.length - 1)
  const lo = Math.floor(pos)
  const hi = Math.ceil(pos)
  if (lo === hi) return sorted[lo]!
  return sorted[lo]! + (sorted[hi]! - sorted[lo]!) * (pos - lo)
}

function pctLe(arr: number[], x: number): number {
  if (!arr.length) return 50
  return (100 * arr.filter((v) => v <= x).length) / arr.length
}

/** Прогноз длительности простоя по историческим паузам (зеркало api/z_ta.py). */
export function idleDurationForecast(
  gaps: { days: number }[],
  idleDays: number,
  inPosition: boolean,
  riskScore: number,
): IdleDurationForecast {
  const durations = gaps.map((g) => Math.round(g.days)).filter((d) => d > 0).sort((a, b) => a - b)

  if (inPosition) {
    return {
      applicable: false,
      reason: 'in_position',
      label: 'Сейчас в сделке — прогноз простоя после выхода из позиции',
    }
  }
  if (durations.length < 2) {
    return {
      applicable: false,
      reason: 'insufficient_history',
      label: 'Мало исторических пауз для прогноза (нужно ≥2 сделки)',
      historical_gaps_count: durations.length,
    }
  }

  const cur = Math.max(0, Math.round(idleDays))
  let sample = durations
  if (riskScore >= 40) {
    const long = durations.filter((d) => d >= 4)
    if (long.length >= 2) sample = long
  }

  if (cur > 0) {
    const survived = sample.filter((d) => d >= cur)
    if (!survived.length) {
      const maxD = Math.max(...sample)
      return {
        applicable: true,
        current_idle_days: cur,
        median_remaining_days: 0,
        median_total_days: cur,
        p25_remaining_days: 0,
        p75_remaining_days: 0,
        historical_gaps_count: durations.length,
        similar_gaps_count: 0,
        percentile_vs_history: Math.round(pctLe(durations, cur) * 10) / 10,
        label: `Уже ${cur} дн. — дольше любой паузы в выборке (макс. ${maxD} дн.). Сигнал может появиться скоро.`,
        display_short: 'скоро сигнал?',
      }
    }
    const remainders = survived.map((d) => d - cur)
    const medRem = percentile(remainders, 50)
    const p25Rem = percentile(remainders, 25)
    const p75Rem = percentile(remainders, 75)
    const medTotal = cur + medRem
    const pctHist = pctLe(durations, cur)
    return {
      applicable: true,
      current_idle_days: cur,
      median_remaining_days: Math.round(medRem),
      median_total_days: Math.round(medTotal),
      p25_remaining_days: Math.round(p25Rem),
      p75_remaining_days: Math.round(p75Rem),
      historical_gaps_count: durations.length,
      similar_gaps_count: survived.length,
      percentile_vs_history: Math.round(pctHist * 10) / 10,
      label: `Уже ${cur} дн. · по ${survived.length} похожим паузам: ещё ~${Math.round(medRem)} дн., итого ~${Math.round(medTotal)} дн. (остаток ${Math.round(p25Rem)}–${Math.round(p75Rem)} дн.). Длиннее ${pctHist.toFixed(0)}% прошлых.`,
      display_short: `ещё ~${Math.round(medRem)} дн.`,
    }
  }

  const med = percentile(sample, 50)
  const p25 = percentile(sample, 25)
  const p75 = percentile(sample, 75)
  const label =
    riskScore >= 40
      ? `Если затишье продолжится: ~${Math.round(med)} дн. (медиана по ${sample.length} длинным паузам, ${Math.round(p25)}–${Math.round(p75)} дн.)`
      : `Типичная пауза: ~${Math.round(med)} дн. (медиана по ${durations.length} паузам, ${Math.round(p25)}–${Math.round(p75)} дн.)`
  return {
    applicable: true,
    current_idle_days: 0,
    median_total_days: Math.round(med),
    p25_remaining_days: Math.round(p25),
    p75_remaining_days: Math.round(p75),
    historical_gaps_count: durations.length,
    similar_gaps_count: sample.length,
    label,
    display_short: `~${Math.round(med)} дн.`,
  }
}

/** Локальный расчёт (если API ещё без idle_precursors). */
export function computeIdlePrecursorsFromZ(
  zscore: { z_score: number }[],
  entry: number,
  exitZ: number,
  idleDays = 0,
  inPosition = false,
  idleGaps: { days: number }[] = [],
): IdlePrecursors {
  const z = zscore.map((p) => p.z_score)
  const feat = zTaFeatures(z, entry, exitZ)
  const { risk, signs } = idleRiskScore(feat, entry, idleDays, inPosition)
  const active = signs.filter((s) => s.active).length

  let verdict: string
  if (risk >= 65) verdict = 'Высокий риск затяжного простоя: Z в флэте/сжатии, до входа далеко.'
  else if (risk >= 40) verdict = 'Умеренный риск затишья.'
  else verdict = 'Слабые признаки простоя: Z ещё «живой».'

  const forecast = idleDurationForecast(idleGaps, idleDays, inPosition, risk)
  let summary = `Риск простоя ${risk}/100 · активно ${active} из ${signs.length} предвестников. ${verdict}`
  if (forecast.applicable && forecast.display_short) {
    summary += ` Прогноз: ${forecast.display_short}.`
  }

  return {
    risk_score: risk,
    signs,
    summary,
    verdict,
    duration_forecast: forecast,
    features: Object.fromEntries(
      Object.entries(feat).map(([k, v]) => [k, Math.round(v * 10000) / 10000]),
    ),
    percentiles: {},
  }
}
