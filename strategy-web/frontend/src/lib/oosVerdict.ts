import type { OosResult, OosVerdict } from '@/types'

function pfNum(pf: number | null | undefined): number {
  if (pf == null) return 0
  if (!Number.isFinite(pf)) return 99
  return pf
}

export function buildOosVerdict(oos: OosResult): OosVerdict {
  const { train, test, train_risk, test_risk, train_ratio, compound_returns: compoundRaw } = oos
  const compound = compoundRaw ?? false
  const trainTr = train.trade_count
  const testTr = test.trade_count
  const testPct = Math.round(100 * (1 - train_ratio))

  if (testTr < 8) {
    return {
      grade: 'weak_data',
      title: 'Мало данных на test',
      summary: `На test (${testPct}% истории) всего ${testTr} сделок — статистически мало для выводов.`,
      signals: [
        compound
          ? 'Капитализация PnL: включена'
          : 'Капитализация PnL: выключена — фиксированный номинал',
        `Train: ${trainTr} сделок · Test: ${testTr} сделок`,
        'При малом test любой убыток или супер-прибыль могут быть случайностью',
      ],
      actions: [
        'Уменьшите Train ratio (например 0.6) или возьмите более длинную историю CSV',
        'Смотрите на test вместе со стресс-тестом ×2',
        'Не меняйте Entry/Exit только ради улучшения train',
      ],
    }
  }

  const trainPnl = train.total_pnl_rub
  const testPnl = test.total_pnl_rub
  const trainAvg = trainPnl / Math.max(1, trainTr)
  const testAvg = testPnl / Math.max(1, testTr)
  const trainPf = pfNum(train.profit_factor)
  const testPf = pfNum(test.profit_factor)
  const trainWr = train.win_rate_pct
  const testWr = test.win_rate_pct
  const trainDd = Math.abs(train.max_drawdown_rub)
  const testDd = Math.abs(test.max_drawdown_rub)

  const signals: string[] = []
  if (compound) {
    signals.push(
      'Капитализация PnL: включена — train и test считаются отдельно, на границе split equity не переносится',
    )
  } else {
    signals.push('Капитализация PnL: выключена — фиксированный номинал (ближе к live без реинвестирования)')
  }

  let overfitPts = 0
  let robustPts = 0
  const highWr = trainWr >= 97 || testWr >= 97
  const ddWorse = testDd > trainDd * 1.6 && testDd > 0

  if (trainPnl > 0 && testPnl <= 0) {
    signals.push(
      `Train PnL ${Math.round(trainPnl).toLocaleString('ru-RU')} ₽, test ${Math.round(testPnl).toLocaleString('ru-RU')} ₽ — на отложенном периоде минус`,
    )
    overfitPts += 3
  } else if (trainAvg > 0 && testAvg < trainAvg * 0.45) {
    signals.push(
      `Ср. PnL/сделку: train ${Math.round(trainAvg)} ₽ vs test ${Math.round(testAvg)} ₽ (${Math.round((100 * testAvg) / trainAvg)}% от train)`,
    )
    overfitPts += 2
  } else if (testAvg >= trainAvg * 0.55 && testPnl > 0) {
    signals.push(`Ср. PnL/сделку на test удержался (${Math.round(testAvg)} ₽ vs train ${Math.round(trainAvg)} ₽)`)
    robustPts += 2
  }

  if (trainPf >= 1.3 && testPf < 1.0) {
    signals.push(`Profit Factor: train ${trainPf.toFixed(2)} → test ${testPf.toFixed(2)} (< 1)`)
    overfitPts += 2
  } else if (testPf >= 1.15) {
    signals.push(`Profit Factor на test ${testPf >= 99 ? '∞' : testPf.toFixed(2)} — приемлемо`)
    robustPts += 1
  }

  if (trainWr - testWr > 12) {
    signals.push(`Win rate: train ${trainWr.toFixed(1)}% → test ${testWr.toFixed(1)}%`)
    overfitPts += 1
  }

  if (highWr) {
    signals.push(
      `Win rate ${Math.max(trainWr, testWr).toFixed(1)}% — проверьте реализм (комиссии, slippage, фильтры spread)`,
    )
    overfitPts += 1
  }

  if (ddWorse) {
    signals.push(
      `Max DD на test (${Math.round(testDd).toLocaleString('ru-RU')} ₽) выше train (${Math.round(trainDd).toLocaleString('ru-RU')} ₽)`,
    )
    overfitPts += 1
  }

  const tailT = train_risk.tail_ratio
  const tailV = test_risk.tail_ratio
  if (tailT != null && tailV != null && tailT > 0.5 && tailV < tailT * 0.5) {
    signals.push(`Tail ratio упал на test (${tailV.toFixed(2)} vs train ${tailT.toFixed(2)}) — другой профиль риска`)
    overfitPts += 1
  } else if (tailV != null && tailT != null && tailV > tailT * 1.4) {
    signals.push(`Tail ratio вырос на test (${tailV.toFixed(2)} vs train ${tailT.toFixed(2)}) — хвостовые риски`)
    overfitPts += 1
  }

  if (testTr < 20) {
    signals.push(`На test только ${testTr} сделок — выводы предварительные`)
    overfitPts += 1
  }

  let grade: OosVerdict['grade']
  let title: string
  let summary: string
  let actions: string[]

  if (overfitPts >= 4) {
    grade = 'overfit'
    title = 'Признаки переобучения'
    summary = 'Train выглядит сильно, test заметно слабее. Параметры, вероятно, подогнаны под первую часть истории.'
    actions = [
      'Не переносите в live текущие Entry/Exit без проверки на другом периоде или train_ratio 0.6',
      'Запустите «Стресс ×2» и добавьте slippage',
      'Упростите правила: буфер Entry Z, фильтры spread, stop-loss / max DD halt',
    ]
  } else if (overfitPts >= 2) {
    grade = 'caution'
    title = 'Умеренный риск переобучения'
    summary = 'Test не полностью подтверждает train — стратегия чувствительна к режиму рынка.'
    actions = [
      'Сравните OOS при train_ratio 0.6 и 0.8',
      'Не увеличивайте номинал/плечо, пока test не стабилен',
      'На heatmap выбирайте плато параметров, а не один пик',
    ]
  } else if (testPnl > 0 && testPf >= 1.0 && robustPts >= 2) {
    grade = 'good'
    title = 'Test подтверждает train'
    summary = 'На отложенном периоде метрики согласованы с train — явного переобучения нет.'
    actions = [
      'Можно рассматривать осторожный live с лимитами риска',
      'Раз в 1–2 недели пересчитывайте OOS на свежих MOEX-данных',
      'Держите circuit breaker и стресс-тест перед увеличением размера',
    ]
  } else if (testPnl <= 0) {
    grade = 'caution'
    title = 'Test не прибылен'
    summary = 'На последнем отрезке истории стратегия не заработала.'
    actions = [
      'Не масштабируйте позицию; проверьте фильтры spread и стресс ×2',
      'Меняйте Entry/Exit только если улучшение видно и на test',
    ]
  } else {
    grade = 'good'
    title = 'Приемлемо'
    summary = 'Явного разрыва train/test нет, но следите за числом сделок на test.'
    actions = ['Повторите OOS после дозагрузки свежих баров', 'Сверьте результат со стресс-тестом']
  }

  if (grade === 'good' && (highWr || ddWorse)) {
    grade = 'caution'
    title = 'Test прибылен, но есть оговорки'
    const parts: string[] = []
    if (highWr) parts.push('win rate ≥ 97%')
    if (ddWorse) parts.push('Max DD на test заметно выше train')
    summary = `PnL на test согласован с train, но ${parts.join(' и ')} — полного подтверждения для live нет.`
    actions = [
      'Запустите «Стресс ×2» и добавьте slippage перед live',
      'Не увеличивайте номинал/плечо, пока DD и win rate не выглядят реалистично',
    ]
    if (compound) {
      actions.unshift(
        'Сравните OOS с капитализацией и без — для live без реинвестирования важнее режим «выкл»',
      )
    } else {
      actions.unshift(
        'При включении капитализации вердикт может стать мягче — ориентируйтесь на ваш реальный режим',
      )
    }
  }

  if (compound && grade === 'good') {
    actions.push('OOS с капитализацией завышает train DD — сравните с фиксированным номиналом')
  }

  return {
    grade,
    title,
    summary,
    signals: signals.length
      ? signals
      : [`Train ${trainTr} сделок / Test ${testTr} сделок · split ${Math.round(train_ratio * 100)}/${testPct}`],
    actions,
  }
}

export function resolveOosVerdict(oos: OosResult): OosVerdict {
  return buildOosVerdict(oos)
}
