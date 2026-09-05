/** Торговля — chart + open trade panel (desk) */
(function () {
  const LS_CHART_RANGE = 'moexReplay.tradeChartRange';
  const LS_SPREAD_PANE_HEIGHT = 'moexReplay.tradeSpreadPaneHeight';
  const LS_TRADE_PARAMS = 'moexReplay.tradeParams';
  /** Мечел m15: график и настройки скрыты из UI Prod. */
  const DESK_MTLR_UI_ENABLED = false;
  const LS_SIDE_SCROLL = 'moexReplay.tradeSideScrollTop';
  const LS_CHECK_SCROLL = 'moexReplay.tradeCheckScrollTop';
  const LS_DESK_SCROLL = 'moexReplay.tradeDeskScrollTop';
  const LS_OPEN_STATS_HIDDEN = 'moexReplay.tradeOpenStatsHidden';
  const LS_MID_TAB = 'moexReplay.tradeMidTab';
  const LS_MID_PANEL_COLLAPSED = 'moexReplay.tradeMidPanelCollapsed';
  const LS_SIDE_TAB = 'moexReplay.tradeSideTab';
  /** Once-per-open-trade toast when MTM ≥ 3% of entry deposit. */
  const LS_PROFIT_ALERT_TRADE = 'moexReplay.profitAlertTradeId';
  const PROFIT_ALERT_PCT = 3;
  const MSK = 'Europe/Moscow';
  /** to ближе к концу данных, чем N баров → считаем «у live» */
  const LIVE_EDGE_BARS = 5;
  /** Макс. пустоты справа от последнего бара (логический overscroll). */
  const MAX_RIGHT_OVERSCROLL_BARS = 48;
  /** Минимум реальных баров в окне — иначе pin считаем битым (линия «схлопнута» влево). */
  const MIN_VISIBLE_DATA_BARS = 24;
  const M15_MS = 15 * 60 * 1000;
  const M1_MS = 60 * 1000;
  /** Legacy M15 close settle (checklist / M15 path only). */
  const BAR_SETTLE_MS = 90 * 1000;
  /** tip1m AUTO: after 1m close — short settle (matches MONITOR_TIP1M_SETTLE_SEC). */
  const TIP1M_SETTLE_MS = 10 * 1000;
  /** Match zsim / tip1m rolling window for display-only dealer Z */
  const Z_ROLL_LOOKBACK_DAYS = 30;
  const Z_ROLL_MIN_BARS = 48;
  /** Z ближе порога на столько → «подготовка» (не шум далеко от края) */
  const PHASE_NEAR_Z = 0.30;
  /** S% ближе уровня входа/выхода → «подготовка» (режим спред-уровней) */
  const PHASE_NEAR_S = 0.30;

  const TRADE_SPREAD_DEFAULT = 150;
  const TRADE_SPREAD_MIN = 48;
  const TRADE_Z_MIN = 120;
  const CHART_SPLITTER_HEIGHT = 6;

  /** Панорама/зум как в TradingView / Replay chart.js */
  const CHART_SCROLL_SCALE = {
    handleScroll: {
      mouseWheel: true,
      pressedMouseMove: true,
      horzTouchDrag: true,
      vertTouchDrag: true,
    },
    handleScale: {
      axisPressedMouseMove: true,
      axisDoubleClickReset: true,
      mouseWheel: true,
      pinch: true,
    },
  };

  /** Hit-test маркеров — как в chart.js (Testing) */
  const MARKER_HIT_RADIUS_PX = 48;
  const MARKER_HIT_RADIUS_X_PX = 44;
  const MARKER_ENTRY_HIT_RADIUS_X_PX = 56;
  const TRADE_HIGHLIGHT_COLOR = '#FACC15';
  /** Плашка + пунктир текущей цены (не цвет «L вых» #26a69a). */
  const CURRENT_PRICE_LINE_COLOR = '#FACC15';
  /** Цель выхода по ТП (не путать с L/S вых и жёлтой текущей ценой). */
  const TP_EXIT_LINE_COLOR = '#FB923C';

  let days = 7;
  let pollTimer = null;
  let pollMs = 12000;
  let refreshWorkCount = 0;
  const POLL_MS_DEFAULT = 12000;
  const POLL_MS_DEALER_1M = 5000;
  /** Full desk (broker) every N lite polls — avoid wedging uvicorn on weekend TInvest. */
  const POLL_FULL_EVERY = 6;
  let pollTick = 0;
  /** Inputs filled from server once; polls must not clobber while typing */
  let formHydrated = false;
  /** User edited params since last successful hydrate/save */
  let formDirty = false;
  let saveStatusTimer = 0;
  /** Ignore late desk responses that would re-hydrate over newer edits */
  let deskFetchSeq = 0;
  /** Last good dealer/ISS chart bars — keep on timeout / empty weekend partial. */
  let lastGoodChartBars = [];
  let lastGoodDeskMeta = null;
  let lastPartialBanner = '';
  /**
   * Last good broker portfolio — lite desk often omits broker (cold / TTL);
   * keep totals so «СРЕДСТВА НА СЧЁТЕ» does not flash «…» / «брокер…».
   */
  let lastGoodBroker = null;
  let brokerEmptyStreak = 0;
  /** > POLL_FULL_EVERY so one full cycle can refill before we drop last good. */
  const BROKER_EMPTY_CLEAR_AFTER = 8;
  let zChart = null;
  let zSeries = null;
  /** Primary pane: CandlestickSeries (Z tip1m · spread dealer). Line only as last-resort fallback. */
  let zSeriesIsLine = false;
  let spreadChart = null;
  let spreadSeries = null;
  let priceLines = [];
  /** Горизонтали порогов спреда % (как Z createPriceLine). */
  let spreadPriceLines = [];
  let tpExitPriceLine = null;
  /**
   * Semi-transparent trade-band fills (BaselineSeries) — same style as Test/chart.js.
   * Bounds from enter/exit levels (L вх/вых · gap · S вых/вх) — not regime cuts.
   * primary* → upper TATN candle pane; spreadRegime* → lower MTLR candle pane.
   */
  let spreadRegimeBands = { narrow: null, transition: null, wide: null };
  let primarySpreadBands = { narrow: null, transition: null, wide: null };
  /** Last bar times for band setData when only levels/lines refresh. */
  let lastSpreadBandTimes = [];
  let lastPrimarySpreadBandTimes = [];
  const SPREAD_REGIME_BAND_COLORS = {
    // Cyan/teal — Long band (L вх … L вых)
    narrow: 'rgba(0, 188, 212, 0.20)',
    // Brownish/orange — gap between L вых and S вых (no entry)
    transition: 'rgba(183, 110, 45, 0.20)',
    // Maroon/red — Short band (S вых … S вх)
    wide: 'rgba(136, 14, 79, 0.22)',
  };
  /** Линия вход→сейчас / hover вход→выход на верхнем TATN. */
  let openHighlightSeries = null;
  /** Линии коридора S на верхнем графике (forming / formed). */
  let corridorChartSeries = [];
  /** Активные границы коридора для вертикального автомасштаба (lo/hi). */
  let activeCorridorBounds = null;
  let lastCorridorAutoscalePts = null;
  const CORRIDOR_PHASE_META = {
    none: {
      badge: 'нет',
      status: 'Коридора нет — спред не держится в узкой полосе',
    },
    forming: {
      badge: 'формируется',
      status: 'Края коридора проявляются — ждём устойчивого удержания в полосе',
    },
    formed: {
      badge: 'сформирован',
      status: 'Коридор держится · касания низа и верха подтверждены',
    },
    broken: {
      // Для торговли «сломан» = нет: тот же UI, что у none (без «сломан» / метрик).
      badge: 'нет',
      status: 'Коридора нет — спред вышел за недавние границы',
    },
  };

  /** Фазы, когда коридора для торговли нет (линии/метрики/бейдж «сломан» не показываем). */
  function corridorPhaseAbsent(phase) {
    const p = String(phase || 'none');
    return p === 'none' || p === 'broken';
  }
  const CORRIDOR_CHART_STYLE = {
    forming: {
      // Dotted (1) — частый пунктир; LargeDashed (3) был слишком редким.
      lineStyle: 1,
      color: '#fbbf24',
      width: 1,
      marker: 'Ф',
    },
    formed: {
      lineStyle: 0,
      color: '#34d399',
      width: 1,
      marker: 'С',
    },
  };
  let lastPaintZData = null;
  let lastPaintMtlrData = null;
  let openMarkersPlugin = null;
  /** Маркеры сделок Мечела на нижнем pane (не TATN). */
  let openSpreadMarkersPlugin = null;
  /** Bottom pane: CandlestickSeries (MTLR m15) — not yellow TATN line. */
  let spreadSeriesIsLine = false;
  let lastMtlrBars = [];
  let lastMtlrLevels = null;
  let lastMtlrMarkers = [];
  let lastOpenTradeFp = '';
  /** Режим счёта с последнего desk refresh (prod|sandbox) — для confirm ручного входа. */
  let lastTradeMode = '';
  /** Полные маркеры + сделки для hit-test / yellow highlight (как chart.js). */
  let lastDeskMarkers = [];
  let lastDeskTrades = [];
  /** Бары tip1m последней отрисовки — спред для highlight закрытых сделок. */
  let lastDeskPaintBars = [];
  let hoverTradeId = null;
  let markerHoverBound = false;
  /** Данные линии открытой сделки — восстанавливаем, когда hover снят. */
  let defaultOpenHighlightData = null;
  let refreshMarkersTimer = 0;
  /** true → fitContent после setData (смена периода 1Д/1Н/1М/3М/6М) */
  let forceFitContent = false;
  let pendingPeriodFitDays = 0;
  /** Глушим save/sync на время setData / apply / resize — LC шлёт range-change асинхронно */
  let suppressRangeEvents = false;
  let rangeSyncBound = false;
  let crosshairSyncBound = false;
  /** price @ UTC sec — для sync кроссхейра Z↔спред */
  let zPriceByTime = new Map();
  let spPriceByTime = new Map();
  let lastBarCount = 0;
  let lastDataEnd = null;
  /** Точный logical range, который пользователь выставил руками */
  let pinnedRange = null;
  /**
   * Строгий pin: после ручного pan влево poll НИКОГДА не follow-live,
   * пока пользователь сам не вернётся к правому краю (или chip периода).
   */
  let userPinnedAwayFromLive = false;
  /** Отпечаток последних баров — без изменений setData не трогаем (типичный poll) */
  let lastBarsFingerprint = '';
  let reapplyRangeTimer = 0;
  /** true только во время/сразу после реального pan/zoom пользователя */
  let userGestureActive = false;
  let userGestureTimer = 0;
  /** Выходные / дилер 1м: верхний pane = свечи спреда (как Test), не Z */
  let chartDealer1m = false;
  /** Узкая полоса для индикатора позиции: пол 1% … Long-вход (Prod 3.2). Пороги Prod не меняем. */
  const NARROW_ZONE_FLOOR_PCT = 1.0;
  const STOCH_K_PERIOD = 14;
  const STOCH_D_PERIOD = 3;

  function narrowZoneBounds(levels) {
    const enterN = Number(levels?.enter_narrow ?? 3.2);
    const hi = Number.isFinite(enterN) && enterN > NARROW_ZONE_FLOOR_PCT
      ? enterN
      : 3.2;
    return { lo: NARROW_ZONE_FLOOR_PCT, hi };
  }

  /** 0…100 внутри [lo,hi]; null если S нет. Вне зоны — clamp + флаг out. */
  function narrowZonePosition(spreadPct, levels) {
    const s = Number(spreadPct);
    if (!Number.isFinite(s)) return null;
    const { lo, hi } = narrowZoneBounds(levels);
    const span = hi - lo;
    if (!(span > 0)) return null;
    const raw = ((s - lo) / span) * 100;
    const pct = Math.max(0, Math.min(100, raw));
    let side = 'in';
    if (s < lo) side = 'below';
    else if (s > hi) side = 'above';
    return { pct, raw, spread: s, lo, hi, side };
  }

  function barSpreadValue(b) {
    if (!b || typeof b !== 'object') return null;
    const v = b.spread != null ? b.spread
      : (b.spreadPercent != null ? b.spreadPercent
        : (b.close != null && b.z == null ? b.close : null));
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }

  /** Stochastic по ряду спреда m15: %K(14) / %D(3 SMA). */
  function spreadStochasticKD(bars, kPeriod = STOCH_K_PERIOD, dPeriod = STOCH_D_PERIOD) {
    const series = [];
    for (const b of bars || []) {
      const v = barSpreadValue(b);
      if (v != null) series.push(v);
    }
    if (series.length < kPeriod) return null;
    const kArr = [];
    for (let i = kPeriod - 1; i < series.length; i += 1) {
      let lo = series[i - kPeriod + 1];
      let hi = lo;
      for (let j = i - kPeriod + 1; j <= i; j += 1) {
        const v = series[j];
        if (v < lo) lo = v;
        if (v > hi) hi = v;
      }
      const span = hi - lo;
      const k = span > 1e-12 ? ((series[i] - lo) / span) * 100 : 50;
      kArr.push(k);
    }
    if (!kArr.length) return null;
    const kLast = kArr[kArr.length - 1];
    let dLast = kLast;
    if (kArr.length >= dPeriod) {
      let sum = 0;
      for (let i = kArr.length - dPeriod; i < kArr.length; i += 1) sum += kArr[i];
      dLast = sum / dPeriod;
    }
    return { k: kLast, d: dLast, n: series.length };
  }

  function renderNarrowZoneMeter(spreadPct, levels, barsIss) {
    const box = $('tradeNarrowZoneBox');
    const fill = $('tradeNarrowZoneFill');
    const mark = $('tradeNarrowZoneMark');
    const vals = $('tradeNarrowZoneVals');
    if (!box || !vals) return;
    const pos = narrowZonePosition(spreadPct, levels);
    const stoch = spreadStochasticKD(barsIss);
    if (!pos) {
      if (fill) fill.style.width = '0%';
      if (mark) mark.style.left = '0%';
      vals.innerHTML = '— · нет спреда';
      return;
    }
    if (fill) fill.style.width = `${pos.pct.toFixed(1)}%`;
    if (mark) mark.style.left = `${pos.pct.toFixed(1)}%`;
    let sideHtml = '';
    if (pos.side === 'below') {
      sideHtml = ` <span class="tnz-out">ниже ${fmt(pos.lo, 1)}%</span>`;
    } else if (pos.side === 'above') {
      sideHtml = ` <span class="tnz-out">выше ${fmt(pos.hi, 1)}% · вне узкой</span>`;
    }
    const stochHtml = stoch
      ? `<div class="tnz-stoch">стох. m15 · %K ${fmt(stoch.k, 0)} · %D ${fmt(stoch.d, 0)}</div>`
      : `<div class="tnz-stoch">стох. m15 · мало баров</div>`;
    vals.innerHTML =
      `<b>${fmt(pos.pct, 0)}%</b> в полосе ${fmt(pos.lo, 1)}…${fmt(pos.hi, 1)}`
      + ` · S ${fmt(pos.spread, 2)}%${sideHtml}`
      + stochHtml;
  }

  function barTradeDay(b) {
    if (!b || typeof b !== 'object') return null;
    for (const key of ['trade_date', 'tradeDate', 'time', 'timestamp']) {
      const raw = b[key];
      if (raw == null) continue;
      const s = String(raw).trim().replace('T', ' ');
      if (s.length >= 10 && s[4] === '-' && s[7] === '-') return s.slice(0, 10);
    }
    const ms = Number(b.timestampMs);
    if (Number.isFinite(ms) && ms > 0) {
      return new Date(ms).toISOString().slice(0, 10);
    }
    return null;
  }

  /** Клиентский fallback: дневной ряд из m15 + упрощённый коридор (если API не отдал). */
  function dailySpreadFromBars(bars) {
    const byDay = new Map();
    for (const b of bars || []) {
      const td = barTradeDay(b);
      const sp = barSpreadValue(b);
      if (td && sp != null) {
        if (!byDay.has(td)) byDay.set(td, []);
        byDay.get(td).push(sp);
      }
    }
    return [...byDay.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .map(([, vals]) => {
        const s = [...vals].sort((a, b) => a - b);
        const m = Math.floor(s.length / 2);
        return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
      });
  }

  function dailySpreadMinMaxFromBars(bars) {
    const byDay = new Map();
    for (const b of bars || []) {
      const td = barTradeDay(b);
      const sp = barSpreadValue(b);
      if (td && sp != null) {
        if (!byDay.has(td)) byDay.set(td, []);
        byDay.get(td).push(sp);
      }
    }
    const mins = [];
    const maxs = [];
    [...byDay.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .forEach(([, vals]) => {
        mins.push(Math.min(...vals));
        maxs.push(Math.max(...vals));
      });
    return { mins, maxs, medians: valsFromDays(byDay) };
    function valsFromDays(map) {
      return [...map.entries()]
        .sort((a, b) => a[0].localeCompare(b[0]))
        .map(([, vals]) => {
          const s = [...vals].sort((a, b) => a - b);
          const m = Math.floor(s.length / 2);
          return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
        });
    }
  }

  function detectSpreadCorridorClient(bars, spreadNow) {
    const loSanity = 0.3;
    const hiSanity = 8.0;
    const touchEps = 0.25;
    const breakEps = 0.08;
    const { mins, maxs, medians } = dailySpreadMinMaxFromBars(bars);
    const values = medians;
    if (values.length < 7 || mins.length < 7 || maxs.length < 7) {
      return { phase: 'none', label_ru: 'нет', title: 'Мало дневных точек', n_days: values.length };
    }
    const pct = (arr, p) => {
      const s = [...arr].sort((a, b) => a - b);
      if (!s.length) return 0;
      const k = (s.length - 1) * (p / 100);
      const f = Math.floor(k);
      const c = Math.min(f + 1, s.length - 1);
      return f === c ? s[f] : s[f] + (s[c] - s[f]) * (k - f);
    };
    const boundsFrom = (meds, dayMins, dayMaxs) => {
      let mode = 'adaptive';
      let lo = Math.max(loSanity, pct(dayMins, 20));
      let hi = Math.min(hiSanity, pct(dayMaxs, 62));
      if (hi > lo + 0.8) mode = 'calculated';
      else {
        lo = Math.max(loSanity, pct(meds, 12));
        hi = Math.min(hiSanity, pct(meds, 88));
        if (hi <= lo + 0.3) hi = lo + 0.5;
      }
      return { lo, hi, mode };
    };
    let { lo, hi, mode: boundsMode } = boundsFrom(values, mins, maxs);
    const touchesLo = mins.filter((v) => v <= lo + touchEps).length;
    const touchesHi = maxs.filter((v) => v >= hi - touchEps).length;
    let width = hi - lo;
    const s = Number.isFinite(Number(spreadNow)) ? Number(spreadNow) : values[values.length - 1];
    const dwellOf = (meds, bandLo, bandHi) => {
      let n = 0;
      for (let i = meds.length - 1; i >= 0; i -= 1) {
        if (meds[i] >= bandLo - breakEps && meds[i] <= bandHi + breakEps) n += 1;
        else break;
      }
      return n;
    };
    let dwell = dwellOf(values, lo, hi);
    let bounces = 0;
    const bot = lo + 0.25 * Math.max(width, 1e-9);
    const top = hi - 0.25 * Math.max(width, 1e-9);
    let prevZone = null;
    for (const v of values) {
      let z = 'mid';
      if (v <= bot) z = 'lo';
      else if (v >= top) z = 'hi';
      if ((z === 'lo' || z === 'hi') && z !== prevZone) bounces += 1;
      if (z !== 'mid') prevZone = z;
    }
    // Липкий слом по предыдущему дню (как на сервере): шип max не расширяет полосу.
    let broken = false;
    let freezeLo = null;
    let freezeHi = null;
    if (values.length > 7) {
      const prevMeds = values.slice(0, -1);
      const prevMins = mins.slice(0, -1);
      const prevMaxs = maxs.slice(0, -1);
      const prevB = boundsFrom(prevMeds, prevMins, prevMaxs);
      const prevWidth = prevB.hi - prevB.lo;
      const prevCalcOk = prevB.mode === 'calculated' && prevWidth <= 4.0;
      const prevAdaptOk = prevB.mode === 'adaptive' && prevWidth <= 1.8;
      const prevDwell = dwellOf(prevMeds, prevB.lo, prevB.hi);
      let prevBounces = 0;
      const pBot = prevB.lo + 0.25 * Math.max(prevWidth, 1e-9);
      const pTop = prevB.hi - 0.25 * Math.max(prevWidth, 1e-9);
      let pz = null;
      for (const v of prevMeds) {
        let z = 'mid';
        if (v <= pBot) z = 'lo';
        else if (v >= pTop) z = 'hi';
        if ((z === 'lo' || z === 'hi') && z !== pz) prevBounces += 1;
        if (z !== 'mid') pz = z;
      }
      const pTouchesLo = prevMins.filter((v) => v <= prevB.lo + touchEps).length;
      const pTouchesHi = prevMaxs.filter((v) => v >= prevB.hi + touchEps).length;
      let prevPhase = 'none';
      if ((prevCalcOk || prevAdaptOk) && pTouchesLo >= 2 && pTouchesHi >= 2 && prevDwell >= 5
          && (prevB.mode === 'calculated' || prevBounces >= 2)) {
        prevPhase = 'formed';
      } else if ((prevCalcOk || prevAdaptOk)
          && (pTouchesLo >= 1 || pTouchesHi >= 1)
          && (pTouchesLo + pTouchesHi >= 3 || prevBounces >= 2)
          && prevDwell >= 2) {
        prevPhase = 'forming';
      }
      const lastMax = maxs[maxs.length - 1];
      const lastMin = mins[mins.length - 1];
      const exited = s > prevB.hi + breakEps || s < prevB.lo - breakEps
        || lastMax > prevB.hi + breakEps || lastMin < prevB.lo - breakEps;
      if ((prevPhase === 'formed' || prevPhase === 'forming') && exited) {
        broken = true;
        freezeLo = prevB.lo;
        freezeHi = prevB.hi;
      }
    }
    const calcOk = boundsMode === 'calculated' && width <= 4.0;
    const adaptOk = boundsMode === 'adaptive' && width <= 1.8;
    const boundsOk = calcOk || adaptOk;
    let phase = 'none';
    let label = 'нет';
    if (broken && freezeLo != null && freezeHi != null) {
      phase = 'broken';
      label = 'нет';
      lo = freezeLo;
      hi = freezeHi;
      width = hi - lo;
      boundsMode = 'frozen';
    } else if (boundsOk && touchesLo >= 2 && touchesHi >= 2 && dwell >= 5
        && (boundsMode === 'calculated' || bounces >= 2)) {
      phase = 'formed';
      label = 'сформирован';
    } else if (boundsOk && (touchesLo >= 1 || touchesHi >= 1) && (touchesLo + touchesHi >= 3 || bounces >= 2) && dwell >= 4) {
      phase = 'forming';
      label = 'формируется';
    }
    const bandSpan = Math.max(hi - lo, 1e-9);
    const pctIn = Math.max(0, Math.min(100, ((s - lo) / bandSpan) * 100));
    const title = broken
      ? `Коридор сломан · была полоса ${lo.toFixed(2)}…${hi.toFixed(2)}% · S сейчас ${s.toFixed(2)}%`
      : `Коридор ${lo.toFixed(2)}…${hi.toFixed(2)}% (расчёт: ${boundsMode}; низ=p20 дн.мин, верх=p62 дн.макс) · касания ${touchesLo}/${touchesHi}`;
    return {
      phase,
      label_ru: label,
      lo,
      hi,
      width: hi - lo,
      spread: s,
      pct_in_band: pctIn,
      dwell_days: dwell,
      bounces,
      touches_lo: touchesLo,
      touches_hi: touchesHi,
      bounds_mode: boundsMode,
      shrink_days: 0,
      n_days: values.length,
      title,
    };
  }

  function corridorPositionText(s, lo, hi, eps = 0.08) {
    if (!Number.isFinite(s) || !Number.isFinite(lo) || !Number.isFinite(hi)) return '—';
    if (s >= lo - eps && s <= hi + eps) {
      const pct = ((s - lo) / Math.max(hi - lo, 1e-9)) * 100;
      return `S внутри коридора · ${fmt(pct, 0)}% полосы`;
    }
    if (s < lo - eps) return `S ниже коридора на ${fmt(lo - s, 2)} п.п.`;
    return `S выше коридора на ${fmt(s - hi, 2)} п.п.`;
  }

  function corridorPhaseStatus(phase) {
    const meta = CORRIDOR_PHASE_META[phase] || CORRIDOR_PHASE_META.none;
    // Удержание (дни) — только в сетке коридора, без дубля в статусе.
    if (phase === 'formed') {
      return meta.status || 'Коридор держится · касания низа и верха подтверждены';
    }
    return meta.status;
  }

  function renderCorridorMeter(corridor, spreadPct, barsIss) {
    const box = $('tradeCorridorBox');
    const badge = $('tradeCorridorBadge');
    const statusEl = $('tradeCorridorStatus');
    const band = $('tradeCorridorBand');
    const mark = $('tradeCorridorMark');
    const edgeLo = $('tradeCorridorEdgeLo');
    const edgeHi = $('tradeCorridorEdgeHi');
    const grid = $('tradeCorridorGrid');
    if (!box) return;
    let c = corridor;
    if (!c || c.phase == null) {
      c = detectSpreadCorridorClient(barsIss, spreadPct);
    }
    const rawPhase = String(c.phase || 'none');
    // «Сломан» показываем как «нет» — без красного «сломан» и без метрик.
    const phase = corridorPhaseAbsent(rawPhase) ? 'none' : rawPhase;
    const meta = CORRIDOR_PHASE_META[rawPhase] || CORRIDOR_PHASE_META.none;
    box.classList.remove(
      'trade-corridor--forming', 'trade-corridor--formed',
      'trade-corridor--broken', 'trade-corridor--none',
    );
    box.classList.add(`trade-corridor--${phase}`);
    if (badge) badge.textContent = corridorPhaseAbsent(rawPhase) ? 'нет' : (c.label_ru || meta.badge);
    const phaseStatus = corridorPhaseAbsent(rawPhase)
      ? (CORRIDOR_PHASE_META[rawPhase] || CORRIDOR_PHASE_META.none).status
      : corridorPhaseStatus(phase);
    if (statusEl) statusEl.textContent = phaseStatus;
    if (c.title) box.title = c.title;

    // Нет / сломан: только заголовок + бейдж «нет» + статус — без сетки и шкалы.
    if (corridorPhaseAbsent(rawPhase)) {
      if (grid) grid.innerHTML = '';
      return;
    }

    const lo = Number(c.lo);
    const hi = Number(c.hi);
    const width = Number(c.width);
    const s = Number.isFinite(Number(c.spread)) ? Number(c.spread) : Number(spreadPct);
    const dwell = Number(c.dwell_days) || 0;
    const bounces = Number(c.bounces) || 0;
    const touchesLo = Number(c.touches_lo) || 0;
    const touchesHi = Number(c.touches_hi) || 0;
    const boundsMode = String(c.bounds_mode || '');
    const nDays = Number(c.n_days) || 0;

    if (!Number.isFinite(lo) || !Number.isFinite(hi) || hi <= lo) {
      if (band) { band.style.left = '0%'; band.style.width = '0%'; }
      if (mark) { mark.style.left = '50%'; mark.classList.remove('trade-corridor-mark--out'); }
      if (edgeLo) edgeLo.textContent = '—';
      if (edgeHi) edgeHi.textContent = '—';
      // Без «S сейчас» / «Режим» — спред на полосе рынка, фаза уже в бейдже.
      if (grid) {
        grid.innerHTML = `
          <div><div class="tcg-k">Дней данных</div><div class="tcg-v">${nDays}</div></div>`;
      }
      return;
    }

    const eps = 0.08;
    const inside = Number.isFinite(s) && s >= lo - eps && s <= hi + eps;
    const wVal = Number.isFinite(width) ? fmt(width, 2) : '—';
    const pad = Math.max(0.2, (hi - lo) * 0.45);
    const viewLo = lo - pad;
    const viewHi = hi + pad;
    const viewSpan = viewHi - viewLo;
    const bandL = ((lo - viewLo) / viewSpan) * 100;
    const bandW = ((hi - lo) / viewSpan) * 100;
    const markP = Number.isFinite(s)
      ? Math.max(0, Math.min(100, ((s - viewLo) / viewSpan) * 100))
      : 50;
    if (band) {
      band.style.left = `${bandL.toFixed(1)}%`;
      band.style.width = `${Math.max(6, bandW).toFixed(1)}%`;
    }
    if (mark) {
      mark.style.left = `${markP.toFixed(1)}%`;
      mark.classList.toggle('trade-corridor-mark--out', !inside);
    }
    if (edgeLo) edgeLo.textContent = `${fmt(lo, 2)}%`;
    if (edgeHi) edgeHi.textContent = `${fmt(hi, 2)}%`;

    const posText = corridorPositionText(s, lo, hi);
    if (statusEl && phase !== 'none') {
      statusEl.textContent = `${phaseStatus} · ${posText}`;
    } else if (statusEl && c.since_date && Number.isFinite(lo)) {
      statusEl.textContent = `Низкий спред с ${c.since_date} · ${posText}`;
    }

    const sinceKey = c.since_date ? 'С низкого режима' : 'Дней данных';
    const sinceVal = c.since_date
      ? String(c.since_date).slice(5).replace('-', '.')
      : String(nDays);
    // Низ/верх — на шкале; S — на полосе рынка + маркере. В сетке только уникальное.
    if (grid) {
      grid.innerHTML = `
        <div><div class="tcg-k">Ширина</div><div class="tcg-v">${wVal} п.п.</div></div>
        <div><div class="tcg-k">Удержание</div><div class="tcg-v">${fmt(dwell, 0)} дн.</div></div>
        <div><div class="tcg-k">Отскоки</div><div class="tcg-v">${fmt(bounces, 0)}</div></div>
        <div><div class="tcg-k">Касания низ / верх</div><div class="tcg-v">${fmt(touchesLo, 0)} / ${fmt(touchesHi, 0)}</div></div>
        <div><div class="tcg-k">${sinceKey}</div><div class="tcg-v">${sinceVal}${boundsMode === 'calculated' ? ' · расчёт' : boundsMode === 'adaptive' ? ' · адаптив' : ''}</div></div>`;
    }
  }

  function dateStrToChartSec(dateStr) {
    const d = String(dateStr || '').slice(0, 10);
    if (d.length < 10) return null;
    return toChartTime(`${d} 12:00`);
  }

  function chartSpreadValues(pts) {
    const vals = [];
    for (const c of pts || []) {
      if (!c) continue;
      for (const k of ['low', 'high', 'close', 'value']) {
        const v = Number(c[k]);
        if (Number.isFinite(v)) vals.push(v);
      }
    }
    return vals;
  }

  function zSeriesAutoscaleProvider() {
    const vals = chartSpreadValues(lastCorridorAutoscalePts || lastPaintZData);
    let minV = vals.length ? vals[0] : 0;
    let maxV = vals.length ? vals[0] : 2;
    for (let i = 1; i < vals.length; i += 1) {
      if (vals[i] < minV) minV = vals[i];
      if (vals[i] > maxV) maxV = vals[i];
    }
    const cb = activeCorridorBounds;
    if (cb && Number.isFinite(cb.lo) && Number.isFinite(cb.hi)) {
      minV = Math.min(minV, cb.lo, cb.hi);
      maxV = Math.max(maxV, cb.lo, cb.hi);
    }
    const span = Math.max(maxV - minV, 0.5);
    const pad = Math.max(0.25, span * 0.08);
    return {
      priceRange: {
        minValue: minV - pad,
        maxValue: maxV + pad,
      },
    };
  }

  function applyZSeriesCorridorAutoscale() {
    if (!zSeries) return;
    try {
      zSeries.applyOptions({ autoscaleInfoProvider: zSeriesAutoscaleProvider });
    } catch (_) { /* ignore */ }
  }

  function refreshZPriceScaleAfterCorridor() {
    if (!zChart || !zSeries) return;
    applyZSeriesCorridorAutoscale();
    if (!activeCorridorBounds) return;
    try {
      zChart.priceScale('right').applyOptions({ autoScale: true });
    } catch (_) { /* ignore */ }
    try {
      const pts = lastCorridorAutoscalePts || lastPaintZData;
      if (!pts || !pts.length) return;
      const tail = pts[pts.length - 1];
      if (zSeriesIsLine) {
        const v = tail.close != null ? tail.close : tail.value;
        zSeries.update({ time: tail.time, value: v });
      } else {
        zSeries.update(tail);
      }
    } catch (_) { /* ignore */ }
  }

  function clearCorridorChartSeries() {
    if (!zChart) return;
    for (const s of corridorChartSeries) {
      try { zChart.removeSeries(s); } catch (_) { /* ignore */ }
    }
    corridorChartSeries = [];
  }

  function buildDayRangeMap(candlePts) {
    const map = new Map();
    for (const p of candlePts || []) {
      if (!p || p.time == null) continue;
      const d = new Date(p.time * 1000).toLocaleDateString('sv-SE', { timeZone: MSK });
      const prev = map.get(d);
      if (!prev) map.set(d, { from: p.time, to: p.time });
      else {
        if (p.time < prev.from) prev.from = p.time;
        if (p.time > prev.to) prev.to = p.time;
      }
    }
    return map;
  }

  /** Строго возрастающее время — иначе Lightweight Charts молча ломает setData. */
  function sanitizeCorridorPoints(pts) {
    const out = [];
    for (const p of pts || []) {
      if (!p || p.time == null || !Number.isFinite(Number(p.value))) continue;
      const t = Number(p.time);
      const v = Number(p.value);
      if (!Number.isFinite(t) || t <= 1e9) continue;
      if (!out.length) {
        out.push({ time: t, value: v });
        continue;
      }
      const prev = out[out.length - 1];
      if (t < prev.time) continue;
      if (t === prev.time) {
        prev.value = v;
        continue;
      }
      out.push({ time: t, value: v });
    }
    return out;
  }

  function corridorLineStyle(phaseStyle) {
    const raw = phaseStyle && phaseStyle.lineStyle;
    const LS = (typeof LightweightCharts !== 'undefined' && LightweightCharts.LineStyle)
      ? LightweightCharts.LineStyle
      : null;
    if (raw === 'dotted' || raw === 1) return LS ? LS.Dotted : 1;
    if (raw === 'dashed' || raw === 2) return LS ? LS.Dashed : 2;
    if (raw === 'largeDashed' || raw === 3) return LS ? LS.LargeDashed : 3;
    return LS ? LS.Solid : 0;
  }

  function corridorLineTypeSteps() {
    if (typeof LightweightCharts !== 'undefined' && LightweightCharts.LineType) {
      return LightweightCharts.LineType.WithSteps;
    }
    return 1;
  }

  /**
   * Step-точки границ по дням сегмента.
   * extendToLive — только для последнего сегмента (иначе оранжевый тянется до «сейчас»).
   */
  function buildCorridorBoundPoints(rows, dayRangeMap, key, chartFirstSec, chartLastSec, liveVal, extendToLive) {
    const pts = [];
    const chartStart = Number(chartFirstSec);
    for (const row of rows || []) {
      const dkey = String(row.date || '').slice(0, 10);
      const range = dayRangeMap && dayRangeMap.get(dkey);
      if (!range) continue;
      if (range.to < chartStart) continue;
      const val = Number(row[key]);
      if (!Number.isFinite(val)) continue;
      let t0 = range.from;
      let t1 = range.to;
      if (t0 < chartStart) t0 = chartStart;
      if (t1 < t0) t1 = t0;
      pts.push({ time: t0, value: val });
      if (t1 > t0) pts.push({ time: t1, value: val });
    }
    if (extendToLive && Number.isFinite(liveVal) && Number.isFinite(chartLastSec)) {
      if (!pts.length) {
        pts.push({ time: chartFirstSec, value: liveVal });
        pts.push({ time: chartLastSec, value: liveVal });
      } else if (pts[pts.length - 1].time < chartLastSec) {
        pts.push({ time: chartLastSec, value: liveVal });
      } else {
        pts[pts.length - 1].value = liveVal;
      }
    }
    return sanitizeCorridorPoints(pts);
  }

  function splitCorridorHistory(history) {
    const segments = [];
    let cur = null;
    for (const row of history || []) {
      const ph = row && row.phase;
      if (ph !== 'forming' && ph !== 'formed') {
        if (cur) { segments.push(cur); cur = null; }
        continue;
      }
      if (!cur || cur.phase !== ph) {
        if (cur) segments.push(cur);
        cur = { phase: ph, rows: [] };
      }
      cur.rows.push(row);
    }
    if (cur) segments.push(cur);
    return segments;
  }

  /**
   * На графике: реальная фаза «формируется» + первые 2 дня «сформирован»
   * рисуем оранжевым, чтобы переход к зелёному был виден глазу.
   */
  function corridorRowsForChart(history) {
    const out = [];
    let formedRun = 0;
    for (const row of history || []) {
      const ph = row && row.phase;
      if (ph !== 'forming' && ph !== 'formed') {
        formedRun = 0;
        continue;
      }
      let draw = ph;
      if (ph === 'formed') {
        formedRun += 1;
        if (formedRun <= 2) draw = 'forming';
      } else {
        formedRun = 0;
        draw = 'forming';
      }
      out.push(Object.assign({}, row, { phase: draw }));
    }
    return out;
  }

  function paintCorridorOnChart(corridor, candlePts) {
    const ph = String(corridor && corridor.phase || '');
    // none / broken / пусто — без линий и маркеров «Ф»/«С» на графике.
    if (corridorPhaseAbsent(ph) || (ph !== 'forming' && ph !== 'formed')) {
      activeCorridorBounds = null;
      lastCorridorAutoscalePts = null;
      clearCorridorChartSeries();
      applyZSeriesCorridorAutoscale();
      return;
    }
    const lo = Number(corridor.lo);
    const hi = Number(corridor.hi);
    activeCorridorBounds = (Number.isFinite(lo) && Number.isFinite(hi))
      ? { lo, hi }
      : null;
    lastCorridorAutoscalePts = candlePts;
    updateCorridorOnChart(corridor, candlePts);
    refreshZPriceScaleAfterCorridor();
  }

  function updateCorridorOnChart(corridor, candlePts) {
    clearCorridorChartSeries();
    if (!zChart || !candlePts || !candlePts.length) return;

    const livePhase = String(corridor && corridor.phase || '');
    const liveLo = Number(corridor.lo);
    const liveHi = Number(corridor.hi);
    if (!Number.isFinite(liveLo) || !Number.isFinite(liveHi)) return;
    if (livePhase !== 'forming' && livePhase !== 'formed') return;

    const firstSec = candlePts[0].time;
    const lastSec = candlePts[candlePts.length - 1].time;
    const dayRangeMap = buildDayRangeMap(candlePts);

    let rows = [...((corridor && corridor.history) || [])];
    const today = corridor.last_date
      || new Date().toLocaleDateString('sv-SE', { timeZone: MSK });
    const todayRow = { date: today, lo: liveLo, hi: liveHi, phase: livePhase };
    const lastRow = rows[rows.length - 1];
    if (lastRow && lastRow.date === today) rows[rows.length - 1] = todayRow;
    else rows.push(todayRow);

    const segments = splitCorridorHistory(corridorRowsForChart(rows));
    if (!segments.length) return;

    const mkSeries = (style) => addSeries(zChart, 'LineSeries', {
      color: style.color,
      lineWidth: style.width,
      lineStyle: corridorLineStyle(style),
      lineType: corridorLineTypeSteps(),
      lastValueVisible: false,
      priceLineVisible: false,
      crosshairMarkerVisible: false,
      autoscaleInfoProvider: () => null,
    });

    const paint = (style, data) => {
      if (!data || data.length < 2) return null;
      const s = mkSeries(style);
      if (!s) return null;
      try {
        s.setData(data);
      } catch (err) {
        try { zChart.removeSeries(s); } catch (_) { /* ignore */ }
        console.warn('corridor setData failed', err);
        return null;
      }
      corridorChartSeries.push(s);
      return s;
    };

    let anyLo = false;
    let anyHi = false;
    let formingSegs = 0;
    let formedSegs = 0;
    segments.forEach((seg, idx) => {
      const style = CORRIDOR_CHART_STYLE[seg.phase];
      if (!style) return;
      const isLast = idx === segments.length - 1;
      const loData = buildCorridorBoundPoints(
        seg.rows, dayRangeMap, 'lo', firstSec, lastSec, liveLo, isLast,
      );
      const hiData = buildCorridorBoundPoints(
        seg.rows, dayRangeMap, 'hi', firstSec, lastSec, liveHi, isLast,
      );
      const loS = paint(style, loData);
      const hiS = paint(style, hiData);
      if (loS) anyLo = true;
      if (hiS) anyHi = true;
      if (seg.phase === 'forming') formingSegs += 1;
      if (seg.phase === 'formed') formedSegs += 1;
      if (loS && loData[0] && idx === 0) {
        try {
          loS.setMarkers([{
            time: loData[0].time,
            position: 'aboveBar',
            color: style.color,
            shape: 'circle',
            text: style.marker || '',
          }]);
        } catch (_) { /* ignore */ }
      }
    });

    try {
      window.__corridorChartDebug = {
        phase: livePhase,
        liveLo,
        liveHi,
        segments: segments.map((s) => ({ phase: s.phase, days: s.rows.length })),
        formingSegs,
        formedSegs,
        loOk: anyLo,
        hiOk: anyHi,
        ts: Date.now(),
      };
    } catch (_) { /* ignore */ }
  }

  function corridorFingerprint(corridor) {
    if (!corridor) return 'none';
    const h = corridor.history || [];
    const tail = h.length ? h[h.length - 1] : null;
    return [
      corridor.phase,
      corridor.lo,
      corridor.hi,
      corridor.dwell_days,
      tail && tail.date,
      tail && tail.phase,
      h.length,
    ].join('|');
  }

  let chartPrimarySpread = false;
  /** Одинаковая ширина правой шкалы → одинаковая plot-area → вертикальный кроссхейр */
  const PRICE_SCALE_MIN_WIDTH = 64;

  const $ = (id) => document.getElementById(id);

  function setZEmptyMessage(text) {
    const el = $('tradeZEmptyMsg');
    if (!el) return;
    if (text) {
      el.textContent = text;
      el.classList.remove('hidden');
    } else {
      el.textContent = '';
      el.classList.add('hidden');
    }
  }

  function updateChartPaneLabels(dealer1m, {
    zEmpty = false,
    lookbackDays = null,
    spreadLevels = null,
    mtlrLevels = null,
    mtlrEmpty = false,
  } = {}) {
    const zLab = $('tradeZChartLabel');
    const spLab = $('tradeSpreadChartLabel');
    const thLab = $('tradeThreshLabel');
    const mtlrThLab = $('tradeMtlrThreshLabel');
    const lb = (lookbackDays != null && Number(lookbackDays) > 0)
      ? ` · до ${Number(lookbackDays)}д`
      : '';
    if (zLab) {
      if (dealer1m && zEmpty) {
        zLab.textContent = 'Татнефть · спред % · нет баров дилер 1м';
      } else if (dealer1m) {
        zLab.textContent = `Татнефть · спред % · дилер 1м (монитор · не AUTO)${lb}`;
      } else {
        zLab.textContent = 'Татнефть · спред % · tip1m';
      }
      zLab.classList.toggle('pnl-label-dealer', !!dealer1m);
    }
    if (spLab) {
      spLab.textContent = mtlrEmpty
        ? 'Мечел · спред % · нет баров m15'
        : 'Мечел · спред % · m15';
      spLab.classList.remove('pnl-label-dealer');
    }
    if (thLab) {
      thLab.classList.toggle('pnl-label-dealer', !!dealer1m);
      const lv = spreadLevels || {};
      const en = Number(lv.enter_narrow);
      const xn = Number(lv.exit_narrow);
      const xw = Number(lv.exit_wide);
      const ew = Number(lv.enter_wide);
      // «дилер · не AUTO» уже в заголовке графика — здесь только числа уровней.
      if ([en, xn, xw, ew].every((n) => Number.isFinite(n))) {
        thLab.textContent = `уровни L ${fmt(en, 1)}/${fmt(xn, 1)} · S ${fmt(xw, 1)}/${fmt(ew, 1)}`;
      } else {
        thLab.textContent = 'уровни спреда';
      }
    }
    if (mtlrThLab) {
      const lv = mtlrLevels || lastMtlrLevels || {};
      const en = Number(lv.enter_narrow);
      const xn = Number(lv.exit_narrow);
      const xw = Number(lv.exit_wide);
      const ew = Number(lv.enter_wide);
      if ([en, xn, xw, ew].every((n) => Number.isFinite(n))) {
        mtlrThLab.textContent = `уровни L ${fmt(en, 1)}/${fmt(xn, 1)} · S ${fmt(xw, 1)}/${fmt(ew, 1)}`;
      } else {
        mtlrThLab.textContent = 'уровни L 3.2/4.3 · S 8.4/8.9';
      }
    }
  }

  function markUserGesture() {
    userGestureActive = true;
    if (userGestureTimer) clearTimeout(userGestureTimer);
    userGestureTimer = setTimeout(() => {
      userGestureActive = false;
      userGestureTimer = 0;
    }, 400);
  }

  function dataEndIndex(barCount) {
    return barCount > 0 ? barCount - 1 : 0;
  }

  /**
   * У live-края только если правый край окна ≈ конец данных.
   * Старое `to >= dataEnd - N` ошибочно ловило pan с пустотой справа (to >> dataEnd)
   * и на poll схлопывало to=dataEnd — отсюда прыжок.
   */
  function isNearLiveEdge(range, dataEnd) {
    if (!range || !Number.isFinite(range.to) || !Number.isFinite(dataEnd)) return false;
    return Math.abs(range.to - dataEnd) <= LIVE_EDGE_BARS;
  }

  /** Сколько реальных баров попало в logical range (пустота справа/слева не считается). */
  function visibleDataInRange(range, dataEnd) {
    if (!range || !Number.isFinite(range.from) || !Number.isFinite(range.to)
      || !Number.isFinite(dataEnd) || dataEnd < 0) {
      return { span: 0, visibleData: 0, emptyRight: 0 };
    }
    const span = range.to - range.from;
    if (!(span > 0)) return { span: 0, visibleData: 0, emptyRight: 0 };
    const dataFrom = Math.max(0, Math.min(range.from, dataEnd));
    const dataTo = Math.min(dataEnd, range.to);
    const visibleData = dataTo >= dataFrom ? (dataTo - dataFrom + 1) : 0;
    const emptyRight = Math.max(0, range.to - dataEnd);
    return { span, visibleData, emptyRight };
  }

  /**
   * Битый pin: данные сжаты влево (2 бара на огромном окне) или from за концом ряда.
   * Иначе строгий userPinnedAwayFromLive навсегда держит пустой график.
   */
  function isViewportCorrupt(range, dataEnd, barCount) {
    if (!range || !(barCount > 0) || !Number.isFinite(dataEnd)) return true;
    if (range.from > dataEnd) return true;
    const { span, visibleData, emptyRight } = visibleDataInRange(range, dataEnd);
    if (!(span > 0)) return true;
    const minData = Math.min(MIN_VISIBLE_DATA_BARS, barCount);
    if (visibleData < minData) return true;
    const maxEmpty = Math.max(MAX_RIGHT_OVERSCROLL_BARS, span * 0.35);
    if (emptyRight > maxEmpty) return true;
    return false;
  }

  function persistViewport() {
    if (!pinnedRange) return;
    try {
      const payload = {
        from: pinnedRange.from,
        to: pinnedRange.to,
        dataEnd: lastDataEnd,
        pinnedAway: !!userPinnedAwayFromLive,
      };
      localStorage.setItem(LS_CHART_RANGE, JSON.stringify(payload));
    } catch (_) { /* quota / private mode */ }
  }

  function loadPersistedViewport() {
    try {
      const raw = localStorage.getItem(LS_CHART_RANGE);
      if (!raw) return null;
      const o = JSON.parse(raw);
      if (o && Number.isFinite(o.from) && Number.isFinite(o.to) && o.to > o.from) {
        return {
          from: o.from,
          to: o.to,
          dataEnd: Number.isFinite(o.dataEnd) ? o.dataEnd : null,
          pinnedAway: !!o.pinnedAway,
        };
      }
    } catch (_) {}
    return null;
  }

  function setPinnedRange(range, { fromUser = false } = {}) {
    if (!range || !Number.isFinite(range.from) || !Number.isFinite(range.to)) return;
    pinnedRange = { from: range.from, to: range.to };
    if (fromUser && lastDataEnd != null) {
      if (isNearLiveEdge(pinnedRange, lastDataEnd)) {
        userPinnedAwayFromLive = false;
      } else {
        userPinnedAwayFromLive = true;
      }
    }
    persistViewport();
  }

  function clearPinState() {
    pinnedRange = null;
    userPinnedAwayFromLive = false;
    lastDataEnd = null;
    lastBarCount = 0;
    lastBarsFingerprint = '';
    try { localStorage.removeItem(LS_CHART_RANGE); } catch (_) {}
  }

  function hydratePinFromStorage() {
    const saved = loadPersistedViewport();
    if (!saved) return;
    pinnedRange = { from: saved.from, to: saved.to };
    if (saved.dataEnd != null) lastDataEnd = saved.dataEnd;
    userPinnedAwayFromLive = !!saved.pinnedAway;
  }

  /** Верх TATN (logical pin) + низ MTLR — один календарный интервал по времени. */
  function applyVisibleRange(range) {
    if (!range || !zChart) return;
    suppressRangeEvents = true;
    try {
      equalizePriceScales();
      zChart.timeScale().setVisibleLogicalRange(range);
      syncBottomPaneToTopTime();
    } catch (_) { /* ignore */ }
    // Не снимаем suppress сразу: LC часто шлёт range-change в следующем кадре
    scheduleEndSuppress();
  }

  /** TATN tip1m ↔ MTLR m15: sync by calendar time, not logical bar index. */
  function syncBottomPaneToTopTime() {
    if (!zChart || !spreadChart) return;
    try {
      const tr = zChart.timeScale().getVisibleRange();
      if (tr && tr.from != null && tr.to != null) {
        spreadChart.timeScale().setVisibleRange(tr);
      }
    } catch (_) { /* ignore */ }
  }

  function syncTopPaneToBottomTime() {
    if (!zChart || !spreadChart) return;
    try {
      const tr = spreadChart.timeScale().getVisibleRange();
      if (tr && tr.from != null && tr.to != null) {
        zChart.timeScale().setVisibleRange(tr);
      }
    } catch (_) { /* ignore */ }
  }

  function scheduleEndSuppress() {
    if (reapplyRangeTimer) cancelAnimationFrame(reapplyRangeTimer);
    reapplyRangeTimer = requestAnimationFrame(() => {
      reapplyRangeTimer = requestAnimationFrame(() => {
        reapplyRangeTimer = 0;
        suppressRangeEvents = false;
      });
    });
  }

  function equalizePriceScales() {
    try {
      const opts = { minimumWidth: PRICE_SCALE_MIN_WIDTH };
      zChart?.priceScale('right')?.applyOptions?.(opts);
      spreadChart?.priceScale('right')?.applyOptions?.(opts);
    } catch (_) { /* ignore */ }
  }

  /** После paint ещё раз навязать общий time-range (setData/fit асинхронно съезжают). */
  function forceSyncAfterPaint() {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        if (!pinnedRange || !zChart || !spreadChart) return;
        equalizePriceScales();
        reassertPinnedRange();
        syncBottomPaneToTopTime();
        try {
          if (window.__tradeChartDebug) {
            window.__tradeChartDebug.zRange = zChart.timeScale().getVisibleLogicalRange();
            window.__tradeChartDebug.spRange = spreadChart.timeScale().getVisibleLogicalRange();
            window.__tradeChartDebug.ts = Date.now();
          }
        } catch (_) { /* debug only */ }
      });
    });
  }

  /** Повторно навязать pin после асинхронного сброса timescale от setData/resize */
  function reassertPinnedRange() {
    if (!zChart || !pinnedRange) return;
    suppressRangeEvents = true;
    try {
      equalizePriceScales();
      zChart.timeScale().setVisibleLogicalRange(pinnedRange);
      syncBottomPaneToTopTime();
    } catch (_) { /* ignore */ }
    scheduleEndSuppress();
  }

  function bindRangeSync() {
    if (rangeSyncBound || !zChart || !spreadChart) return;
    rangeSyncBound = true;
    if (!pinnedRange) hydratePinFromStorage();

    const bindGesture = (el) => {
      if (!el || el.dataset.tradeGestureBound === '1') return;
      el.dataset.tradeGestureBound = '1';
      el.addEventListener('pointerdown', markUserGesture);
      el.addEventListener('wheel', markUserGesture, { passive: true });
      el.addEventListener('touchstart', markUserGesture, { passive: true });
    };
    bindGesture($('tradeZChart'));
    bindGesture($('tradeSpreadChart'));

    const onRangeChange = (source) => (range) => {
      if (!range || suppressRangeEvents) return;

      // Программный сброс от setData/resize — не пишем в pin; если ушли от live — вернуть окно
      if (!userGestureActive) {
        if (userPinnedAwayFromLive && pinnedRange) {
          const jumpedToLive = lastDataEnd != null && isNearLiveEdge(range, lastDataEnd)
            && Math.abs(range.to - pinnedRange.to) > LIVE_EDGE_BARS;
          if (jumpedToLive || Math.abs(range.from - pinnedRange.from) > 0.01
            || Math.abs(range.to - pinnedRange.to) > 0.01) {
            reassertPinnedRange();
          }
        }
        return;
      }

      // Жест: pin по верхнему TATN (logical); низ — тот же календарный интервал.
      suppressRangeEvents = true;
      try {
        if (source === 'z') {
          setPinnedRange(range, { fromUser: true });
          syncBottomPaneToTopTime();
        } else {
          syncTopPaneToBottomTime();
          const zr = zChart.timeScale().getVisibleLogicalRange();
          if (zr) setPinnedRange(zr, { fromUser: true });
        }
      } catch (_) { /* ignore */ }
      scheduleEndSuppress();
    };
    zChart.timeScale().subscribeVisibleLogicalRangeChange(onRangeChange('z'));
    spreadChart.timeScale().subscribeVisibleLogicalRangeChange(onRangeChange('spread'));
  }

  function nearestPriceByTime(map, time) {
    if (!map || time == null) return null;
    if (map.has(time)) return map.get(time);
    let best = null;
    let bestD = Infinity;
    for (const [t, v] of map) {
      const d = Math.abs(t - time);
      if (d < bestD) {
        bestD = d;
        best = v;
      }
    }
    // ~½ m15 bar — enough for tip1m↔m15 crosshair
    return bestD <= 450 ? best : null;
  }

  function bindCrosshairSync() {
    if (crosshairSyncBound || !zChart || !spreadChart) return;
    crosshairSyncBound = true;
    let syncing = false;
    const clearOther = (dst) => {
      if (typeof dst.clearCrosshairPosition !== 'function') return;
      syncing = true;
      try { dst.clearCrosshairPosition(); } catch (_) { /* ignore */ }
      syncing = false;
    };
    const onMove = (src) => (param) => {
      if (src === 'z') updateCandleOhlcOverlay(param);
      if (syncing) return;
      const dst = src === 'z' ? spreadChart : zChart;
      const dstSeries = src === 'z' ? spreadSeries : zSeries;
      if (!dst || !dstSeries || !param || param.time == null || !param.point) {
        clearOther(dst);
        return;
      }
      const priceMap = src === 'z' ? spPriceByTime : zPriceByTime;
      const price = nearestPriceByTime(priceMap, param.time);
      if (price == null || typeof dst.setCrosshairPosition !== 'function') {
        clearOther(dst);
        return;
      }
      syncing = true;
      try { dst.setCrosshairPosition(price, param.time, dstSeries); } catch (_) { /* ignore */ }
      syncing = false;
    };
    zChart.subscribeCrosshairMove(onMove('z'));
    spreadChart.subscribeCrosshairMove(onMove('spread'));
  }

  function fitAndRemember() {
    try {
      // Явный диапазон надёжнее fitContent(): у большого tip1m fit применяется
      // асинхронно, а getVisibleLogicalRange успевал вернуть старое окно.
      suppressRangeEvents = true;
      equalizePriceScales();
      let range = null;
      if (lastBarCount > 0) {
        const rightGap = Math.max(2, Math.ceil(lastBarCount * 0.01));
        range = { from: 0, to: Math.max(0, lastBarCount - 1) + rightGap };
        applyVisibleRange(range);
      } else {
        try {
          zChart?.timeScale().fitContent();
          range = zChart?.timeScale().getVisibleLogicalRange();
        } catch (_) { /* ignore */ }
      }
      if (range) {
        userPinnedAwayFromLive = false;
        setPinnedRange(range, { fromUser: false });
        if (lastBarCount <= 0) applyVisibleRange(range);
      }
      scheduleEndSuppress();
      forceSyncAfterPaint();
    } catch (_) {
      try { spreadChart?.timeScale().fitContent(); } catch (__) {}
    }
  }

  /**
   * После обновления данных:
   * - userPinnedAwayFromLive → ВСЕГДА точный restore, never follow
   * - иначе у live-края → сдвинуть окно к новому концу
   * - иначе → точный restore
   * @param {number} zCount
   * @param {number} [spreadCount]
   */
  function restoreOrFitVisibleRange(zCount, spreadCount) {
    // Pin/live follow по верхнему TATN; низ MTLR подтягивается по времени.
    const zN = Math.max(0, zCount | 0);
    const spN = spreadCount != null ? Math.max(0, spreadCount | 0) : zN;
    const n = zN || spN;
    const dataEnd = dataEndIndex(n);
    const previousBarCount = lastBarCount;
    const dataExpanded = previousBarCount > 0 && n > previousBarCount * 1.5;
    lastBarCount = n;
    lastDataEnd = dataEnd;

    // После смены периода краткий lite-ответ может прийти раньше полного и
    // израсходовать forceFitContent. При последующем расширении всё равно
    // показываем весь выбранный период, если пользователь сам не прокручивал.
    if (forceFitContent || (dataExpanded && !userPinnedAwayFromLive)) {
      forceFitContent = false;
      userPinnedAwayFromLive = false;
      fitAndRemember();
      return;
    }

    if (!pinnedRange) hydratePinFromStorage();

    if (!pinnedRange) {
      fitAndRemember();
      return;
    }

    // Битый LS/pin (данные слева, справа пустота) — не уважать, переfit.
    if (isViewportCorrupt(pinnedRange, dataEnd, n)) {
      userPinnedAwayFromLive = false;
      fitAndRemember();
      return;
    }

    // Строгий pin: poll/tick не имеют права уезжать к правому краю
    if (userPinnedAwayFromLive) {
      applyVisibleRange(pinnedRange);
      persistViewport();
      forceSyncAfterPaint();
      return;
    }

    if (isNearLiveEdge(pinnedRange, dataEnd) && n > 0) {
      const span = Math.max(1, pinnedRange.to - pinnedRange.from);
      const next = { from: dataEnd - span, to: dataEnd };
      // Follow-live тоже может дать почти пустое окно — сразу переfit.
      if (isViewportCorrupt(next, dataEnd, n)) {
        userPinnedAwayFromLive = false;
        fitAndRemember();
        return;
      }
      setPinnedRange(next, { fromUser: false });
      applyVisibleRange(next);
      forceSyncAfterPaint();
      return;
    }

    // Не у live, но флаг ещё не стоял (старый LS) — тоже держим окно
    userPinnedAwayFromLive = true;
    applyVisibleRange(pinnedRange);
    persistViewport();
    forceSyncAfterPaint();
  }

  async function api(path, opts) {
    const timeoutMs = (opts && opts.timeoutMs != null) ? Number(opts.timeoutMs) : 0;
    const { timeoutMs: _tm, ...fetchOpts } = opts || {};
    const ctrl = (timeoutMs > 0 && typeof AbortController !== 'undefined')
      ? new AbortController()
      : null;
    let timer = null;
    if (ctrl) timer = setTimeout(() => ctrl.abort(), timeoutMs);
    try {
      const res = await fetch(path, {
        headers: { 'Content-Type': 'application/json', ...(fetchOpts && fetchOpts.headers) },
        ...fetchOpts,
        signal: ctrl ? ctrl.signal : (fetchOpts && fetchOpts.signal),
      });
      const text = await res.text();
      let data;
      try { data = text ? JSON.parse(text) : {}; } catch { data = { detail: text }; }
      if (!res.ok) {
        const msg = data.detail || data.message || text || res.statusText;
        throw new Error(typeof msg === 'string' ? msg : JSON.stringify(msg));
      }
      return data;
    } catch (e) {
      if (e && (e.name === 'AbortError' || /aborted/i.test(String(e.message || '')))) {
        throw new Error(`Таймаут ${timeoutMs}мс: ${path}`);
      }
      throw e;
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  function fmt(n, d = 2) {
    if (n == null || Number.isNaN(Number(n))) return '—';
    return Number(n).toLocaleString('ru-RU', {
      maximumFractionDigits: d,
      useGrouping: true,
    });
  }

  /** % до порога входа/выхода: need/th×100 (100% у дальнего края, 0% у порога). */
  function needPctSuffix(need, th) {
    if (need == null || !(th > 0)) return '';
    return ` (${Math.round((need / th) * 100)}%)`;
  }

  /** Текст «до Long/Short ещё … (N%)» для плашки чеклиста. */
  function openEntryProgressText(dir, needLong, needShort, entryTh) {
    if (dir === 'long' && needLong != null && Number.isFinite(needLong)) {
      return `до Long ещё −${fmt(needLong, 2)}${needPctSuffix(needLong, entryTh)}`;
    }
    if (dir === 'short' && needShort != null && Number.isFinite(needShort)) {
      return `до Short ещё +${fmt(needShort, 2)}${needPctSuffix(needShort, entryTh)}`;
    }
    return '';
  }

  function fmtRub(n) {
    if (n == null || Number.isNaN(Number(n))) return '—';
    const v = Number(n);
    const sign = v > 0 ? '+' : '';
    return sign + v.toLocaleString('ru-RU', { maximumFractionDigits: 0 }) + ' ₽';
  }

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  /** Format bar trade_date → «19.07 13:45» */
  function fmtTickLabel(tradeDate) {
    if (!tradeDate) return '—';
    const s = String(tradeDate).trim();
    const m = s.match(/^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2}))?/);
    if (m) {
      const dd = `${m[3]}.${m[2]}`;
      return m[4] != null ? `${dd} ${m[4]}:${m[5]}` : dd;
    }
    return s;
  }

  function onlineBadge(online) {
    return online
      ? '<span class="badge-online">online</span>'
      : '<span class="badge-offline">offline</span>';
  }

  function dealerBadge(dealer) {
    if (!dealer) return '';
    if (dealer.error && !dealer.ok) {
      return `<span class="badge-dealer-off" title="${escapeHtml(String(dealer.error))}">дилер ?</span>`;
    }
    if (dealer.manual_ok || dealer.quotes_ok) {
      return `<span class="badge-dealer-on" title="Котировки дилера · ручной Long/Short OK · не в Z/AUTO">${escapeHtml(dealer.label || 'дилер / выходные')}</span>`;
    }
    return `<span class="badge-dealer-off" title="Нет дилерских цен">дилер · нет цен</span>`;
  }

  /** Плашка дилера в статус-баре: только когда уместно (выходные / котировки / ошибка). */
  function dealerStatusHtml(dealer, weekendMonitor) {
    if (dealer && (dealer.error && !dealer.ok)) return dealerBadge(dealer);
    if (dealer && (dealer.manual_ok || dealer.quotes_ok)) return dealerBadge(dealer);
    if (weekendMonitor) return dealer ? dealerBadge(dealer) : '<span class="badge-dealer-off">дилер · нет цен</span>';
    return '';
  }

  /** Фаза коридора S — компактная плашка в верхний ряд индикаторов. */
  function corridorStatusBadge(corridor, spreadPct, barsIss) {
    let c = corridor;
    if (!c || c.phase == null) {
      c = detectSpreadCorridorClient(barsIss, spreadPct);
    }
    if (!c) return '';
    const phase = String(c.phase || 'none');
    // Нет / сломан: плашку не показываем (иначе «коридор · сломан» выглядит как активный индикатор).
    if (corridorPhaseAbsent(phase)) return '';
    const meta = CORRIDOR_PHASE_META[phase] || CORRIDOR_PHASE_META.none;
    const label = c.label_ru || meta.badge;
    const title = corridorPhaseStatus(phase) || c.title || meta.status || '';
    return (
      `<span class="badge-corridor badge-corridor-${escapeHtml(phase)}" title="${escapeHtml(String(title))}">`
      + `коридор · ${escapeHtml(String(label))}</span>`
    );
  }

  /** Плашка: спред заморожен после 23:45 МСК (не путать с live tip после сессии). */
  function spreadFrozenBadge(dealer, mskParts) {
    const msk = mskParts || nowMskParts();
    const fromDealer = dealer && dealer.spread_live === false;
    const frozen = fromDealer || (msk.spreadLive === false && !msk.weekend);
    if (!frozen) return '';
    const reason = (dealer && dealer.spread_frozen_reason)
      || 'сессия закрыта · спред не обновляем после 23:45';
    const cutoff = (dealer && dealer.spread_cutoff) || '23:45';
    return `<span class="badge-spread-frozen" title="${escapeHtml(String(reason))}">спред стоп · ${escapeHtml(String(cutoff))}</span>`;
  }

  function monBadge(running) {
    return running
      ? '<span class="badge-mon-on"><span class="badge-quiet">монитор</span> ON</span>'
      : '<span class="badge-mon-off"><span class="badge-quiet">монитор</span> OFF</span>';
  }

  /** Отставание — всегда минуты, шаг 30 с (без прыжков «87 с» ↔ «2 мин»). */
  function formatTipLagSec(sec) {
    const n = Number(sec);
    if (!Number.isFinite(n) || n < 0) return null;
    const q = Math.round(n / 30) * 30;
    const mins = q / 60;
    if (mins < 0.5) return '<0.5 мин';
    if (mins < 10) return `${mins.toFixed(1).replace(/\.0$/, '')} мин`;
    return `${Math.round(mins)} мин`;
  }

  function tipFeedLabel(feed) {
    const f = String(feed || '').toLowerCase();
    if (f === 'tinvest') return 'Т‑Инвест';
    if (f === 'iss') return 'биржа ISS';
    if (f === 'parquet') return 'кэш';
    if (f === 'mixed') return 'смесь';
    return '';
  }

  /** Достать epoch ms бара из «2026-08-05 07:02» / сообщения монитора. */
  function parseMskBarMs(raw) {
    if (raw == null) return null;
    const s = String(raw).replace('T', ' ').trim();
    const m = s.match(/(\d{4})-(\d{2})-(\d{2})[ ](\d{2}):(\d{2})(?::(\d{2}))?/);
    if (!m) return null;
    const barMs = Date.parse(
      `${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}:${m[6] || '00'}+03:00`,
    );
    return Number.isFinite(barMs) ? barMs : null;
  }

  /** Сглаживание: не дёргать плашку на каждом опросе стола. */
  let tipLagSmooth = { sec: null, shownAt: 0, barMs: null };

  /**
   * Badge: отставание tip. Green <3м, warn 3–10м, bad ≥10м.
   * Бар: tip_lag_sec → last_message монитора → last_bar → trade_date.
   */
  function tipLagBadge(mon, tradeDate) {
    const m = (mon && typeof mon === 'object') ? mon : {};
    let lag = Number(m.tip_lag_sec);
    let barMs = null;
    if (!Number.isFinite(lag)) {
      // Сначала монитор (AUTO), не trade_date стола — иначе лаг растёт и скачком падает.
      barMs = parseMskBarMs(m.last_message)
        || parseMskBarMs(m.last_bar)
        || parseMskBarMs(tradeDate);
      if (barMs != null) lag = Math.max(0, (Date.now() - barMs) / 1000);
    }
    if (!Number.isFinite(lag)) return '';

    const now = Date.now();
    const prev = tipLagSmooth.sec;
    const sameBar = barMs != null && tipLagSmooth.barMs === barMs;
    if (
      prev != null
      && sameBar
      && (now - tipLagSmooth.shownAt) < 12000
      && Math.abs(lag - prev) < 90
    ) {
      lag = prev;
    } else {
      tipLagSmooth = { sec: lag, shownAt: now, barMs: barMs ?? tipLagSmooth.barMs };
    }

    const text = formatTipLagSec(lag);
    if (!text) return '';
    let cls = 'badge-tip-lag is-ok';
    if (lag >= 600) cls = 'badge-tip-lag is-bad';
    else if (lag >= 180) cls = 'badge-tip-lag is-warn';
    const feed = tipFeedLabel(m.tip_feed);
    const iss = formatTipLagSec(m.iss_lag_sec);
    const ti = formatTipLagSec(m.ti_lag_sec);
    const bits = [];
    if (feed) bits.push(`источник: ${feed}`);
    if (iss) bits.push(`ISS ${iss}`);
    if (ti) bits.push(`Т‑Инвест ${ti}`);
    bits.push('хвост tip vs часы · шаг 30 с');
    const title = bits.join(' · ');
    return `<span class="${cls}" title="${escapeHtml(title)}">отставание ${escapeHtml(text)}</span>`;
  }

  function autoBadge(on) {
    return on
      ? '<span class="badge-auto-on"><span class="badge-quiet">auto</span> ON</span>'
      : '<span class="badge-auto-off"><span class="badge-quiet">auto</span> OFF</span>';
  }

  function modeBadge(mode) {
    return mode === 'prod'
      ? '<span class="badge-mode-prod">Prod</span>'
      : '<span class="badge-mode-sandbox">Sandbox</span>';
  }

  /** Prod signal path badge — tip1m Mode B (Testing «касание 1м»). */
  function strategyBadge(signalMode) {
    const tip = String(signalMode || 'tip1m') === 'tip1m';
    if (tip) {
      return '<span class="badge-strat-tip1m" title="Mode B: tip1m · settle +10с · дилер не в AUTO">касание tip1m</span>';
    }
    return '<span class="badge-strat-m15" title="Legacy: вход/выход на закрытии M15">M15 close</span>';
  }

  function spreadLevelsBadge(settings, sl) {
    const on = settings && settings.spread_level_mode !== false
      && (settings.spread_level_mode === true || settings.spread_level_mode == null
        || String(settings.spread_level_mode) === '1');
    if (!on && !(sl && sl.spread_level_mode)) return '';
    const lv = (sl && sl.levels) || {};
    const label = (sl && sl.current_label_ru) || '—';
    // S% — на полосе рынка; здесь только зона (узкий / переход / широкий).
    const blocked = sl && sl.entry_blocked ? ' · вход запрещён' : '';
    return (
      `<span class="badge-spread-levels" title="Short ≥${fmt(lv.enter_wide ?? 6.1, 1)} / ≤${fmt(lv.exit_wide ?? 5.8, 1)} · Long ≤${fmt(lv.enter_narrow ?? 3.2, 1)} / ≥${fmt(lv.exit_narrow ?? 4, 1)} · переход без входа">`
      + `спред-уровни · ${escapeHtml(String(label))}${blocked}</span>`
    );
  }

  /** Z-режим UI снят со стола — бейдж больше не показываем (настройки на сервере остаются). */
  function regimeZBadge() {
    return '';
  }

  function determineSpreadLevelSignalJs(prevS, curS, pos, lv) {
    if (prevS == null || curS == null || !Number.isFinite(prevS) || !Number.isFinite(curS)) {
      return 'NONE';
    }
    const enterW = Number(lv?.enter_wide ?? 6.1);
    const exitW = Number(lv?.exit_wide ?? 5.8);
    const enterN = Number(lv?.enter_narrow ?? 3.2);
    const exitN = Number(lv?.exit_narrow ?? 4.0);
    const p = String(pos || 'FLAT').toUpperCase();
    if (p === 'FLAT') {
      if (prevS < enterW && curS >= enterW && curS > 5.5) return 'ENTER_SHORT';
      if (prevS > enterN && curS <= enterN && curS < 3.5) return 'ENTER_LONG';
      return 'NONE';
    }
    if (p === 'LONG') {
      if (prevS < exitN && curS >= exitN) return 'EXIT_LONG';
      return 'NONE';
    }
    if (p === 'SHORT') {
      if (prevS > exitW && curS <= exitW) return 'EXIT_SHORT';
      return 'NONE';
    }
    return 'NONE';
  }

  function effectiveThresholds(settings, regime, formEntry, formExit) {
    const spreadOn = !!(settings && settings.spread_level_mode !== false
      && (settings.spread_level_mode === true || settings.spread_level_mode == null
        || String(settings.spread_level_mode) === '1'));
    if (spreadOn) {
      return {
        entry: Number(settings?.spread_enter_wide ?? 6.1),
        exit: Number(settings?.spread_exit_wide ?? 5.8),
        regimeOn: false,
        spreadOn: true,
        allowEntry: true,
        levels: {
          enter_wide: Number(settings?.spread_enter_wide ?? 6.1),
          exit_wide: Number(settings?.spread_exit_wide ?? 5.8),
          enter_narrow: Number(settings?.spread_enter_narrow ?? 3.2),
          exit_narrow: Number(settings?.spread_exit_narrow ?? 4.0),
        },
      };
    }
    const modeOn = !!(settings && settings.regime_z_mode);
    if (modeOn && regime && regime.effective) {
      const e = Number(regime.effective.entry);
      const x = Number(regime.effective.exit);
      if (Number.isFinite(e) && Number.isFinite(x) && e > 0 && x > 0) {
        return {
          entry: e, exit: x, regimeOn: true, spreadOn: false,
          allowEntry: !!regime.effective.allow_entry,
        };
      }
    }
    const entry = Number(formEntry != null ? formEntry : settings?.entry_z);
    const exitZ = Number(formExit != null ? formExit : settings?.exit_z);
    return {
      entry: Number.isFinite(entry) && entry > 0 ? entry : 1.6,
      exit: Number.isFinite(exitZ) && exitZ > 0 ? exitZ : 1.3,
      regimeOn: modeOn,
      spreadOn: false,
      allowEntry: true,
    };
  }

  /** Как маркеры Z на Тесте: Long #69F0AE, Short #FF8A80; FLAT — серый pill */
  function posBadge(pos) {
    const p = String(pos || 'FLAT').toUpperCase();
    if (p === 'LONG') return '<span class="badge-pos-long">Long</span>';
    if (p === 'SHORT') return '<span class="badge-pos-short">Short</span>';
    return '<span class="badge-pos-flat">FLAT</span>';
  }

  function phaseBadgeHtml(phase) {
    if (!phase || phase.kind === 'idle') return '';
    const cls = phase.kind === 'ready'
      ? 'badge-phase-ready'
      : phase.kind === 'signal'
        ? 'badge-phase-signal'
        : 'badge-phase-prep';
    return `<span class="${cls}" title="${escapeHtml(phase.title || phase.label)}">${escapeHtml(phase.label)}</span>`;
  }

  function tickBadge(tradeDate) {
    return `<span class="badge-tick">тик ${escapeHtml(fmtTickLabel(tradeDate))}</span>`;
  }

  /** Кластер плашек в статус-баре: gap внутри группы меньше, чем между группами. */
  function statusGroupHtml(parts) {
    const items = (parts || []).filter(Boolean);
    if (!items.length) return '';
    return `<span class="status-group">${items.join('')}</span>`;
  }

  /** Компактный уровень спреда без лишних нулей (3.2, 4, 6.1). */
  function fmtRuleLvl(v, fallback) {
    const n = Number(v != null ? v : fallback);
    if (!Number.isFinite(n)) return String(fallback);
    const t = Math.round(n * 10) / 10;
    return Number.isInteger(t) ? String(t) : String(t);
  }

  /**
   * Вторая строка статус-бара: включённые правила из settings (как «Параметры Prod»).
   * Выключенные не показываем; для добора — явно «добор выкл».
   */
  function tradeRulesStatusHtml(settings) {
    if (!settings) return '';
    const items = [];
    const spreadOn = settings.spread_level_mode !== false
      && (settings.spread_level_mode === true || settings.spread_level_mode == null
        || String(settings.spread_level_mode) === '1');
    if (spreadOn) {
      const en = fmtRuleLvl(settings.spread_enter_narrow, 3.2);
      const xn = fmtRuleLvl(settings.spread_exit_narrow, 4.0);
      const ew = fmtRuleLvl(settings.spread_enter_wide, 6.1);
      const xw = fmtRuleLvl(settings.spread_exit_wide, 5.8);
      items.push(
        `<span class="badge-rule badge-rule-spread" title="Уровни спреда Prod: Long вход ${en} → выход ${xn} · Short вход ${ew} → выход ${xw}">`
        + `L ${en}→${xn} · S ${ew}→${xw}</span>`
      );
    }
    const tp = Number(settings.take_profit_pct != null ? settings.take_profit_pct : 2);
    if (tp > 0) {
      items.push(
        `<span class="badge-rule badge-rule-tp" title="Выход по ТП: MTM % от депозита, как tip1m">ТП ${tp}%</span>`
      );
    }
    const noTrend = Number(
      settings.max_hold_days_no_exit_trend != null ? settings.max_hold_days_no_exit_trend : 5
    );
    if (noTrend > 0) {
      items.push(
        `<span class="badge-rule badge-rule-hold" title="Закрыть, если нет хода к выходу за ${noTrend} дн.">`
        + `нет хода ${noTrend}д</span>`
      );
    }
    const losing = Number(
      settings.max_hold_days_if_losing != null ? settings.max_hold_days_if_losing : 0
    );
    if (losing > 0) {
      items.push(
        `<span class="badge-rule badge-rule-losing" title="Закрыть убыточную позицию после ${losing} дн.">`
        + `в минусе ${losing}д</span>`
      );
    }
    if (settings.addon_mode !== false) {
      const aen = fmtRuleLvl(settings.addon_enter_narrow, 2.0);
      const aew = fmtRuleLvl(settings.addon_enter_wide, 7.0);
      const axn = fmtRuleLvl(settings.addon_exit_narrow, 3.2);
      const axw = fmtRuleLvl(settings.addon_exit_wide, 6.1);
      items.push(
        `<span class="badge-rule badge-rule-addon" title="Добор: Long касание ${aen}→выход ${axn} · Short ${aew}→${axw}">`
        + `добор ${aen}/${aew}</span>`
      );
    } else {
      items.push(
        `<span class="badge-rule badge-rule-addon-off" title="Добор выключен в параметрах Prod">добор выкл</span>`
      );
    }
    if (settings.extra_addon_mode !== false) {
      const een = fmtRuleLvl(settings.extra_enter_narrow, 1.0);
      const eew = fmtRuleLvl(settings.extra_enter_wide, 9.0);
      const exn = fmtRuleLvl(settings.extra_exit_narrow, 2.0);
      const exw = fmtRuleLvl(settings.extra_exit_wide, 7.0);
      items.push(
        `<span class="badge-rule badge-rule-addon" title="Экстра независима: Long в зоне ≤${een}→выход ${exn} · Short ≥${eew}→${exw}, если уже есть база или добор">`
        + `экстра ${een}/${eew}</span>`
      );
    } else {
      items.push(
        `<span class="badge-rule badge-rule-addon-off" title="Экстра выключена в параметрах Prod">экстра выкл</span>`
      );
    }
    if (settings.compound !== false) {
      items.push(
        `<span class="badge-rule badge-rule-addon" title="40/30/30 от текущего счёта: прибыль остаётся в рынке">капит.</span>`
      );
    }
    return statusGroupHtml(items);
  }

  function renderTradeRulesStatus(settings) {
    const el = $('tradeRulesStatus');
    if (!el) return;
    el.innerHTML = tradeRulesStatusHtml(settings);
  }

  let lastDeskBars = [];
  let metricTipEl = null;
  let metricTipHideTimer = null;
  let barMetricDists = { z: null, spread: null };
  /** @type {{ source: string, n: number, degraded: boolean, sampleLabel: string }} */
  let metricDistMeta = {
    source: 'none',
    n: 0,
    degraded: true,
    sampleLabel: 'окно графика',
  };

  function ensureMetricTip() {
    if (metricTipEl) return metricTipEl;
    const el = document.createElement('div');
    el.id = 'tradeLiveMetricTip';
    el.className = 'trade-metric-tip hidden';
    el.setAttribute('role', 'tooltip');
    document.body.appendChild(el);
    metricTipEl = el;
    return el;
  }

  function hideMetricTip() {
    if (metricTipHideTimer) {
      clearTimeout(metricTipHideTimer);
      metricTipHideTimer = null;
    }
    if (metricTipEl) metricTipEl.classList.add('hidden');
  }

  function positionMetricTip(tip, anchorEl) {
    const rect = anchorEl.getBoundingClientRect();
    const tipRect = tip.getBoundingClientRect();
    const pad = 8;
    let left = rect.left + rect.width / 2 - tipRect.width / 2;
    let top = rect.top - tipRect.height - 10;
    if (top < pad) top = rect.bottom + 10;
    if (left < pad) left = pad;
    if (left + tipRect.width > window.innerWidth - pad) {
      left = window.innerWidth - tipRect.width - pad;
    }
    if (top + tipRect.height > window.innerHeight - pad) {
      top = Math.max(pad, window.innerHeight - tipRect.height - pad);
    }
    tip.style.left = `${Math.round(left)}px`;
    tip.style.top = `${Math.round(top)}px`;
  }

  function distsFromBars(bars) {
    if (typeof computeNumericDistribution !== 'function') {
      return { z: null, spread: null };
    }
    const zs = [];
    const sps = [];
    for (const b of bars || []) {
      const z = b.z != null ? Number(b.z) : (b.zScore != null ? Number(b.zScore) : NaN);
      const sp = b.spread != null ? Number(b.spread)
        : (b.spreadPercent != null ? Number(b.spreadPercent) : NaN);
      if (Number.isFinite(z)) zs.push(z);
      if (Number.isFinite(sp)) sps.push(sp);
    }
    return {
      z: computeNumericDistribution(zs),
      spread: computeNumericDistribution(sps),
    };
  }

  function hydrateServerDist(raw) {
    if (!raw || !Array.isArray(raw.bins) || !(raw.n > 0)) return null;
    const numOrNaN = (v) => {
      const x = Number(v);
      return Number.isFinite(x) ? x : NaN;
    };
    return {
      n: Number(raw.n) || 0,
      nHist: Number(raw.nHist != null ? raw.nHist : raw.n) || 0,
      min: Number(raw.min),
      max: Number(raw.max),
      histMin: Number(raw.histMin != null ? raw.histMin : raw.lo),
      histMax: Number(raw.histMax != null ? raw.histMax : raw.hi),
      mean: Number(raw.mean),
      stdev: Number(raw.stdev),
      median: numOrNaN(raw.median),
      mad: numOrNaN(raw.mad),
      p5: numOrNaN(raw.p5),
      p25: numOrNaN(raw.p25),
      p75: numOrNaN(raw.p75),
      p95: numOrNaN(raw.p95),
      bins: raw.bins.map((c) => Number(c) || 0),
      lo: Number(raw.lo),
      hi: Number(raw.hi),
      width: Number(raw.width),
      binCount: Number(raw.binCount) || raw.bins.length,
      histClipped: !!(raw.histClipped),
      sorted: [],
    };
  }

  /**
   * Prefer server ≈3y compact dists for tips / «в хвосте»; chart window stays for display.
   * Fallback: window bars (degraded).
   */
  function rebuildBarMetricDists(bars, serverDists) {
    lastDeskBars = Array.isArray(bars) ? bars : [];
    const windowDists = distsFromBars(lastDeskBars);
    const z3 = hydrateServerDist(serverDists && serverDists.z);
    const sp3 = hydrateServerDist(serverDists && serverDists.spread);
    const use3y = !!(serverDists && serverDists.ok && z3 && sp3);
    if (use3y) {
      barMetricDists = { z: z3, spread: sp3 };
      metricDistMeta = {
        source: String(serverDists.source || '3y'),
        n: Number(serverDists.n) || z3.n,
        degraded: false,
        sampleLabel: '3 года',
      };
      return;
    }
    barMetricDists = windowDists;
    const nWin = Math.max(windowDists.z?.n || 0, windowDists.spread?.n || 0);
    metricDistMeta = {
      source: 'window',
      n: nWin,
      degraded: true,
      sampleLabel: nWin > 0 ? 'окно графика' : 'нет данных',
    };
  }

  function formatBarMetricStat(metric, value) {
    if (value == null || !Number.isFinite(value)) return '—';
    if (metric === 'z') {
      const sign = value > 0 ? '+' : '';
      return `${sign}${value.toFixed(2)}`;
    }
    if (metric === 'spread') return `${value.toFixed(2)}%`;
    return String(value);
  }

  function showBarMetricTip(anchor) {
    if (typeof buildMetricDistTipHtml !== 'function') return;
    const metric = anchor.dataset.metric;
    const value = Number(anchor.dataset.value);
    if ((metric !== 'z' && metric !== 'spread') || !Number.isFinite(value)) return;
    const dist = barMetricDists[metric];
    if (!dist) return;
    const title = metric === 'z' ? 'Z' : 'Спред';
    const display = anchor.textContent.trim() || formatBarMetricStat(metric, value);
    const tip = ensureMetricTip();
    tip.innerHTML = buildMetricDistTipHtml({
      title,
      display,
      value,
      dist,
      colKey: metric,
      formatStat: (v) => formatBarMetricStat(metric, v),
      sampleLabel: metricDistMeta.sampleLabel,
      spreadWidthRegime: metric === 'spread',
    });
    tip.classList.remove('hidden');
    positionMetricTip(tip, anchor);
  }

  function metricHoverValue(metric, value, display) {
    if (value == null || !Number.isFinite(Number(value))) {
      return escapeHtml(display);
    }
    return (
      `<span class="metric-hover" data-metric="${escapeHtml(metric)}" data-value="${Number(value)}">`
      + `${escapeHtml(display)}</span>`
    );
  }

  /**
   * Подпись под метрикой: для спреда — узкий/переход/широкий;
   * для Z — «типично» / «ниже|выше обычного» / «выброс» по медиане≈3г.
   */
  function metricPlaceCaption(metric, value) {
    if (value == null || !Number.isFinite(Number(value))) return '';
    if (metric === 'spread') {
      if (typeof classifySpreadWidthRegime !== 'function') return '';
      const reg = classifySpreadWidthRegime(Number(value));
      if (!reg || !reg.key || reg.key === 'na') return '';
      const title = reg.title ? ` title="${escapeHtml(reg.title)}"` : '';
      return (
        `<span class="metric-place metric-place-${escapeHtml(reg.key)}"${title}>`
        + `${escapeHtml(reg.label)}</span>`
      );
    }
    if (typeof classifyTradeMetricPlacement !== 'function') return '';
    const dist = barMetricDists[metric];
    if (!dist) return '';
    const place = classifyTradeMetricPlacement(Number(value), dist, { colKey: metric });
    if (!place || !place.key || place.key === 'none') return '';
    return (
      `<span class="metric-place metric-place-${escapeHtml(place.key)}">`
      + `${escapeHtml(place.label)}</span>`
    );
  }

  function metricStripBlock(label, metric, value, display) {
    const place = metricPlaceCaption(metric, value);
    return (
      `<span class="metric-block">`
      + `<span class="metric-block-row"><b>${escapeHtml(label)}</b> ${metricHoverValue(metric, value, display)}</span>`
      + (place || '')
      + `</span>`
    );
  }

  function regimeBadge(bars) {
    if (typeof classifySpreadRegime !== 'function') return '';
    const r = classifySpreadRegime(bars || []);
    if (!r || r.key === 'na') return '';
    return `<span class="badge-regime badge-regime-${escapeHtml(r.key)}" title="${escapeHtml(r.title)}">`
      + `${escapeHtml(r.label)}</span>`;
  }

  /** Каскад через 3.5 и 5.5 за ~10д — ранний/подтверждающий флаг смены режима. */
  function cascadeBadge(bars) {
    if (typeof detectSpreadRegimeCascade !== 'function') return '';
    const c = detectSpreadRegimeCascade(bars || []);
    if (!c || !c.on) return '';
    return `<span class="badge-cascade badge-cascade-${escapeHtml(c.key)}" title="${escapeHtml(c.title)}">`
      + `${escapeHtml(c.label)}</span>`;
  }

  /** Зона карты Prod 3.2/4/5.8/6.1 + длительность эпизода. */
  function zoneMapBadge(bars) {
    if (typeof detectSpreadMapZone !== 'function') return '';
    const z = detectSpreadMapZone(bars || []);
    if (!z || !z.on) return '';
    const wall = z.nearWall ? ' badge-zone-wall' : '';
    return `<span class="badge-zone badge-zone-${escapeHtml(z.key)}${wall}" title="${escapeHtml(z.title)}">`
      + `${escapeHtml(z.badgeText || z.shortLabel)}</span>`;
  }

  /**
   * MOEX trade_date — wall-clock MSK. Как labelToUnixSec в replay-engine.js:
   * явно +03:00, без Date.parse без зоны (браузер иначе сдвигает на несколько часов).
   */
  function toChartTime(tradeDate, timestampMs) {
    const ms = Number(timestampMs);
    if (Number.isFinite(ms) && ms > 0) {
      if (ms > 1e12) return Math.floor(ms / 1000);
      if (ms > 1e9) return Math.floor(ms);
    }
    if (typeof labelToUnixSec === 'function') {
      const t = labelToUnixSec(tradeDate);
      return (typeof t === 'number' && t > 1e9) ? t : null;
    }
    if (!tradeDate) return null;
    const s = String(tradeDate).trim().replace('T', ' ');
    let iso;
    if (s.length >= 16) iso = `${s.slice(0, 16).replace(' ', 'T')}:00+03:00`;
    else if (s.length >= 10 && s[4] === '-' && s[7] === '-') iso = `${s.slice(0, 10)}T00:00:00+03:00`;
    else return null;
    const parsed = new Date(iso).getTime();
    const sec = Number.isFinite(parsed) ? Math.floor(parsed / 1000) : null;
    return (sec != null && sec > 1e9) ? sec : null;
  }

  /** Подписи оси/кроссхейра в MSK — общий форматтер из chart.js */
  function formatChartTick(time) {
    if (typeof time !== 'number') return '';
    if (typeof window.formatMskAxisDayMonthYear === 'function') {
      return window.formatMskAxisDayMonthYear(time, true);
    }
    return new Date(time * 1000).toLocaleString('ru-RU', {
      timeZone: MSK,
      day: 'numeric',
      month: 'short',
      year: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    });
  }

  function chartTimeOpts() {
    return {
      timeScale: {
        borderColor: '#2a2e39',
        timeVisible: true,
        secondsVisible: false,
        tickMarkFormatter: formatChartTick,
        // 3М tip1m ≈ 70k свечей: без этого fitContent схлопывает ось (как на Тесте).
        minBarSpacing: 0.001,
        maxBarSpacing: 64,
      },
      localization: {
        locale: 'ru-RU',
        timeFormatter: formatChartTick,
      },
    };
  }

  function hideCandleOhlcOverlay() {
    paintTradeOhlc(null);
  }

  function candleOhlcAtTime(param) {
    if (param && param.time != null && zSeries && !zSeriesIsLine && param.seriesData) {
      try {
        const sd = param.seriesData.get(zSeries);
        if (sd && (sd.open != null || sd.close != null || sd.value != null)) {
          return sd;
        }
      } catch (_) { /* ignore */ }
    }
    const pts = lastPaintZData;
    if (!pts || !pts.length) return null;
    if (param && param.time != null) {
      const t = Number(param.time);
      for (let i = pts.length - 1; i >= 0; i -= 1) {
        if (Number(pts[i].time) === t) return pts[i];
      }
    }
    return pts[pts.length - 1];
  }

  function paintTradeOhlc(param) {
    const el = $('tradeCandleOhlc');
    if (!el) return;
    if (!zChart || !zSeries || zSeriesIsLine) {
      el.textContent = '—';
      el.classList.add('is-idle');
      return;
    }
    const candle = candleOhlcAtTime(param);
    if (!candle) {
      el.textContent = '—';
      el.classList.add('is-idle');
      return;
    }
    el.textContent = typeof window.formatOhlcLine === 'function'
      ? window.formatOhlcLine(candle)
      : `O: ${fmt(candle.open, 2)}  H: ${fmt(candle.high, 2)}  L: ${fmt(candle.low, 2)}  C: ${fmt(candle.close, 2)}`;
    el.classList.remove('is-idle');
  }

  /** Hover = эта свеча, иначе последняя. */
  function updateCandleOhlcOverlay(param) {
    const hovering = param && param.point && param.time != null
      && param.point.x >= 0 && param.point.y >= 0;
    paintTradeOhlc(hovering ? param : null);
  }

  function addSeries(chart, type, opts) {
    if (typeof chart.addSeries === 'function' && LightweightCharts[type]) {
      return chart.addSeries(LightweightCharts[type], opts);
    }
    if (type === 'CandlestickSeries' && chart.addCandlestickSeries) return chart.addCandlestickSeries(opts);
    if (type === 'LineSeries' && chart.addLineSeries) return chart.addLineSeries(opts);
    if (type === 'BaselineSeries' && chart.addBaselineSeries) return chart.addBaselineSeries(opts);
    return null;
  }

  /** Horizontal price-band fill (BaselineSeries); ignored by Y autoscale. */
  function makeSpreadRegimeBand(chart, basePrice, fillColor) {
    return addSeries(chart, 'BaselineSeries', {
      baseValue: { type: 'price', price: basePrice },
      topLineColor: 'rgba(0,0,0,0)',
      topFillColor1: fillColor,
      topFillColor2: fillColor,
      bottomLineColor: 'rgba(0,0,0,0)',
      bottomFillColor1: 'rgba(0,0,0,0)',
      bottomFillColor2: 'rgba(0,0,0,0)',
      lineWidth: 1,
      lastValueVisible: false,
      priceLineVisible: false,
      crosshairMarkerVisible: false,
      // Bands must not stretch the price scale.
      autoscaleInfoProvider: () => null,
    });
  }

  /**
   * Trade-band Y bounds from desk.spread_levels.levels (not regime cuts).
   * Narrow Long: enter_narrow…exit_narrow; Wide Short: exit_wide…enter_wide;
   * Transition: exit_narrow…exit_wide (gap, no entry).
   */
  function resolveSpreadTradeBandBounds(levels) {
    const lv = levels || {};
    const num = (v, fb) => {
      const n = Number(v);
      return Number.isFinite(n) ? n : fb;
    };
    const enterW = num(lv.enter_wide, 6.1);
    const exitW = num(lv.exit_wide, 5.8);
    const enterN = num(lv.enter_narrow, 3.2);
    const exitN = num(lv.exit_narrow, 4.0);
    const loHi = (a, b) => (a <= b ? { lo: a, hi: b } : { lo: b, hi: a });
    const narrow = loHi(enterN, exitN);
    const wide = loHi(exitW, enterW);
    const trans = loHi(exitN, exitW);
    return {
      narrowLo: narrow.lo,
      narrowHi: narrow.hi,
      transLo: trans.lo,
      transHi: trans.hi,
      wideLo: wide.lo,
      wideHi: wide.hi,
    };
  }

  /**
   * @param {object} chart
   * @param {{narrow:*,transition:*,wide:*}} bandsRef
   * @param {object|null} levels
   * @returns {boolean} true if band series were newly created
   */
  function ensureSpreadBandsOnChart(chart, bandsRef, levels) {
    if (!chart || !bandsRef) return false;
    const b = resolveSpreadTradeBandBounds(levels);
    if (!bandsRef.narrow) {
      // Create before candles/line when possible → fills under price series (как Test).
      bandsRef.narrow = makeSpreadRegimeBand(
        chart, b.narrowLo, SPREAD_REGIME_BAND_COLORS.narrow,
      );
      bandsRef.transition = makeSpreadRegimeBand(
        chart, b.transLo, SPREAD_REGIME_BAND_COLORS.transition,
      );
      bandsRef.wide = makeSpreadRegimeBand(
        chart, b.wideLo, SPREAD_REGIME_BAND_COLORS.wide,
      );
      return !!(bandsRef.narrow || bandsRef.transition || bandsRef.wide);
    }
    try {
      bandsRef.narrow.applyOptions({
        baseValue: { type: 'price', price: b.narrowLo },
        topFillColor1: SPREAD_REGIME_BAND_COLORS.narrow,
        topFillColor2: SPREAD_REGIME_BAND_COLORS.narrow,
      });
      bandsRef.transition.applyOptions({
        baseValue: { type: 'price', price: b.transLo },
        topFillColor1: SPREAD_REGIME_BAND_COLORS.transition,
        topFillColor2: SPREAD_REGIME_BAND_COLORS.transition,
      });
      bandsRef.wide.applyOptions({
        baseValue: { type: 'price', price: b.wideLo },
        topFillColor1: SPREAD_REGIME_BAND_COLORS.wide,
        topFillColor2: SPREAD_REGIME_BAND_COLORS.wide,
      });
    } catch (_) { /* */ }
    return false;
  }

  function ensureSpreadRegimeBands(levels) {
    if (!spreadChart) return;
    const created = ensureSpreadBandsOnChart(spreadChart, spreadRegimeBands, levels);
    if (created && spreadSeries) {
      const wantLine = spreadSeriesIsLine;
      spreadPriceLines.forEach((pl) => {
        try { spreadSeries.removePriceLine(pl); } catch (_) {}
      });
      spreadPriceLines = [];
      openSpreadMarkersPlugin = null;
      try {
        if (typeof spreadChart.removeSeries === 'function') spreadChart.removeSeries(spreadSeries);
      } catch (_) {}
      spreadSeries = null;
      spreadSeriesIsLine = !wantLine;
      ensureSpreadSeriesKind(wantLine);
    }
  }

  /**
   * Upper pane (spread candles) — same Long/Short zone fills as Test chart.js.
   * If bands are created after candles already exist, recreate candle series so
   * fills stay under OHLC (z-order = add order).
   */
  function ensurePrimarySpreadBands(levels) {
    if (!zChart) return;
    const created = ensureSpreadBandsOnChart(zChart, primarySpreadBands, levels);
    if (created && zSeries) {
      const wantLine = zSeriesIsLine;
      priceLines.forEach((pl) => {
        try { zSeries.removePriceLine(pl); } catch (_) {}
      });
      priceLines = [];
      openMarkersPlugin = null;
      try {
        if (typeof zChart.removeSeries === 'function') zChart.removeSeries(zSeries);
      } catch (_) {}
      zSeries = null;
      zSeriesIsLine = !wantLine;
      ensureZSeriesKind(wantLine);
    }
  }

  function setSpreadBandSeriesData(bandsRef, times, levels) {
    if (!bandsRef) return;
    const b = resolveSpreadTradeBandBounds(levels);
    const mk = (value) => times.map((time) => ({ time, value }));
    try {
      if (bandsRef.narrow) {
        bandsRef.narrow.setData(times.length ? mk(b.narrowHi) : []);
      }
      if (bandsRef.transition) {
        bandsRef.transition.setData(times.length ? mk(b.transHi) : []);
      }
      if (bandsRef.wide) {
        bandsRef.wide.setData(times.length ? mk(b.wideHi) : []);
      }
    } catch (_) { /* */ }
  }

  /**
   * Fill trade bands on lower yellow S% chart from enter/exit levels.
   * @param {Array<{time:number}>|null} spreadPts — times; omit to reuse last
   * @param {{enter_wide?:number,exit_wide?:number,enter_narrow?:number,exit_narrow?:number}|null} levels
   */
  function updateSpreadRegimeBands(spreadPts, levels) {
    if (!spreadChart) return;
    ensureSpreadRegimeBands(levels);
    if (Array.isArray(spreadPts)) {
      lastSpreadBandTimes = spreadPts
        .map((p) => p && p.time)
        .filter((t) => t != null);
    }
    setSpreadBandSeriesData(spreadRegimeBands, lastSpreadBandTimes, levels);
  }

  /**
   * Fill Long/Short zones on upper spread-candle pane (parity Test).
   * @param {Array<{time:number}>|null} candlePts
   * @param {object|null} levels
   */
  function updatePrimarySpreadBands(candlePts, levels) {
    if (!zChart) return;
    ensurePrimarySpreadBands(levels);
    if (Array.isArray(candlePts)) {
      lastPrimarySpreadBandTimes = candlePts
        .map((p) => p && p.time)
        .filter((t) => t != null);
    }
    setSpreadBandSeriesData(primarySpreadBands, lastPrimarySpreadBandTimes, levels);
  }

  function makeZCandleSeries(chart) {
    return addSeries(chart, 'CandlestickSeries', {
      upColor: '#089981', downColor: '#f23645',
      borderUpColor: '#089981', borderDownColor: '#f23645',
      wickUpColor: '#089981', wickDownColor: '#f23645',
      lastValueVisible: true,
      priceLineVisible: true,
      priceLineColor: CURRENT_PRICE_LINE_COLOR,
      lastValueFillColor: CURRENT_PRICE_LINE_COLOR,
    });
  }

  function makeZLineSeries(chart) {
    return addSeries(chart, 'LineSeries', {
      color: '#089981',
      lineWidth: 2,
      lastValueVisible: true,
      priceLineVisible: true,
      priceLineColor: CURRENT_PRICE_LINE_COLOR,
      lastValueFillColor: CURRENT_PRICE_LINE_COLOR,
    });
  }

  function publishChartDebug(extra) {
    try {
      let zRange = null;
      let spRange = null;
      try { zRange = zChart?.timeScale()?.getVisibleLogicalRange() || null; } catch (_) {}
      try { spRange = spreadChart?.timeScale()?.getVisibleLogicalRange() || null; } catch (_) {}
      window.__tradeChartDebug = {
        zSeriesIsLine,
        chartDealer1m,
        zPts: (extra && extra.zPts) || 0,
        spPts: (extra && extra.spPts) || 0,
        bodyN: (extra && extra.bodyN) || 0,
        sample: (extra && extra.sample) || null,
        sync: (extra && extra.sync) || null,
        zRange,
        spRange,
        err: (extra && extra.err) || null,
        ts: Date.now(),
      };
    } catch (_) { /* ignore */ }
  }

  /**
   * Prefer CandlestickSeries. LineSeries only if candles unavailable.
   * @returns {boolean} true if series was (re)created
   */
  function ensureZSeriesKind(wantLine) {
    if (!zChart) return false;
    const asLine = !!wantLine;
    if (zSeries && zSeriesIsLine === asLine) return false;
    priceLines.forEach((pl) => {
      try { if (zSeries) zSeries.removePriceLine(pl); } catch (_) {}
    });
    priceLines = [];
    if (tpExitPriceLine) {
      try { if (zSeries) zSeries.removePriceLine(tpExitPriceLine); } catch (_) {}
      tpExitPriceLine = null;
    }
    openMarkersPlugin = null;
    if (zSeries) {
      try {
        if (typeof zChart.removeSeries === 'function') zChart.removeSeries(zSeries);
      } catch (_) {}
      zSeries = null;
    }
    if (asLine) {
      zSeries = makeZLineSeries(zChart);
      zSeriesIsLine = !!zSeries;
    } else {
      const candle = makeZCandleSeries(zChart);
      if (candle) {
        zSeries = candle;
        zSeriesIsLine = false;
      } else {
        zSeries = makeZLineSeries(zChart);
        zSeriesIsLine = !!zSeries;
      }
    }
    lastBarsFingerprint = '';
    forceFitContent = true;
    publishChartDebug({ zPts: 0, spPts: 0 });
    applyZSeriesCorridorAutoscale();
    return true;
  }

  /** Bottom MTLR pane: candles by default (same as TATN top). */
  function ensureSpreadSeriesKind(wantLine) {
    if (!spreadChart) return false;
    const asLine = !!wantLine;
    if (spreadSeries && spreadSeriesIsLine === asLine) return false;
    spreadPriceLines.forEach((pl) => {
      try { if (spreadSeries) spreadSeries.removePriceLine(pl); } catch (_) {}
    });
    spreadPriceLines = [];
    openSpreadMarkersPlugin = null;
    if (spreadSeries) {
      try {
        if (typeof spreadChart.removeSeries === 'function') spreadChart.removeSeries(spreadSeries);
      } catch (_) {}
      spreadSeries = null;
    }
    if (asLine) {
      spreadSeries = makeZLineSeries(spreadChart);
      spreadSeriesIsLine = !!spreadSeries;
    } else {
      const candle = makeZCandleSeries(spreadChart);
      if (candle) {
        spreadSeries = candle;
        spreadSeriesIsLine = false;
      } else {
        spreadSeries = makeZLineSeries(spreadChart);
        spreadSeriesIsLine = !!spreadSeries;
      }
    }
    lastBarsFingerprint = '';
    forceFitContent = true;
    return true;
  }

  function ensureCharts() {
    if (typeof LightweightCharts === 'undefined') return;
    const zEl = $('tradeZChart');
    const sEl = $('tradeSpreadChart');
    if (!zEl || !sEl) return;
    const zoom = { minBarSpacing: 0.001, maxBarSpacing: 64 };
    try { zChart?.timeScale()?.applyOptions?.(zoom); } catch (_) { /* ignore */ }
    try { spreadChart?.timeScale()?.applyOptions?.(zoom); } catch (_) { /* ignore */ }
    if (!zChart) {
      zChart = LightweightCharts.createChart(zEl, {
        layout: { background: { color: '#161a25' }, textColor: '#d1d4dc' },
        grid: { vertLines: { color: '#2a2e39' }, horzLines: { color: '#2a2e39' } },
        rightPriceScale: { borderColor: '#2a2e39', minimumWidth: PRICE_SCALE_MIN_WIDTH },
        width: zEl.clientWidth,
        height: zEl.clientHeight || 300,
        ...chartTimeOpts(),
        ...CHART_SCROLL_SCALE,
      });
      // Bands first → under candles (как Test chart.js).
      ensurePrimarySpreadBands({
        enter_wide: 6.1, exit_wide: 5.8, enter_narrow: 3.2, exit_narrow: 4.0,
      });
      // Candles by default (dealer + tip1m). Line only if candle API missing.
      const candle = makeZCandleSeries(zChart);
      if (candle) {
        zSeries = candle;
        zSeriesIsLine = false;
      } else {
        zSeries = makeZLineSeries(zChart);
        zSeriesIsLine = !!zSeries;
      }
      openHighlightSeries = addSeries(zChart, 'LineSeries', {
        color: TRADE_HIGHLIGHT_COLOR,
        lineWidth: 2,
        lineStyle: 0,
        lastValueVisible: false,
        priceLineVisible: false,
        crosshairMarkerVisible: false,
        autoscaleInfoProvider: () => null,
      });
    } else if (zChart && zSeries && !openHighlightSeries) {
      openHighlightSeries = addSeries(zChart, 'LineSeries', {
        color: TRADE_HIGHLIGHT_COLOR,
        lineWidth: 2,
        lineStyle: 0,
        lastValueVisible: false,
        priceLineVisible: false,
        crosshairMarkerVisible: false,
        autoscaleInfoProvider: () => null,
      });
    }
    if (!spreadChart) {
      spreadChart = LightweightCharts.createChart(sEl, {
        layout: { background: { color: '#161a25' }, textColor: '#d1d4dc' },
        grid: { vertLines: { color: '#2a2e39' }, horzLines: { color: '#2a2e39' } },
        rightPriceScale: { borderColor: '#2a2e39', minimumWidth: PRICE_SCALE_MIN_WIDTH },
        width: sEl.clientWidth,
        height: sEl.clientHeight || 150,
        ...chartTimeOpts(),
        ...CHART_SCROLL_SCALE,
      });
      // Trade-band fills first (under MTLR candles) — Mechel levels.
      ensureSpreadRegimeBands({
        enter_wide: 8.9, exit_wide: 8.4, enter_narrow: 3.2, exit_narrow: 4.3,
      });
      const candle = makeZCandleSeries(spreadChart);
      if (candle) {
        spreadSeries = candle;
        spreadSeriesIsLine = false;
      } else {
        spreadSeries = makeZLineSeries(spreadChart);
        spreadSeriesIsLine = !!spreadSeries;
      }
    } else if (spreadSeries && spreadSeriesIsLine) {
      ensureSpreadSeriesKind(false);
    }
    equalizePriceScales();
    bindRangeSync();
    bindCrosshairSync();
    bindMarkerHover();
  }

  function setThresholdLines(entry, exitZ) {
    if (!zSeries || typeof zSeries.createPriceLine !== 'function') return;
    priceLines.forEach((pl) => { try { zSeries.removePriceLine(pl); } catch (_) {} });
    priceLines = [];
    const mk = (price, color, title) => {
      priceLines.push(zSeries.createPriceLine({
        price, color, lineWidth: 1, lineStyle: 2, title,
      }));
    };
    mk(entry, '#2962ff', `+вх ${entry}`);
    mk(-entry, '#2962ff', `−вх ${entry}`);
    mk(exitZ, '#089981', `+вых ${exitZ}`);
    mk(-exitZ, '#089981', `−вых ${exitZ}`);
  }

  /** Уровни спреда на верхнем pane (L/S вх/вых — без линий коридора). */
  function setPrimarySpreadThresholdLines(levels) {
    const lv = levels || {};
    // Bands first (may recreate candle series for z-order); then price lines.
    updatePrimarySpreadBands(null, lv);
    if (!zSeries || typeof zSeries.createPriceLine !== 'function') return;
    priceLines.forEach((pl) => { try { zSeries.removePriceLine(pl); } catch (_) {} });
    priceLines = [];
    const enterW = Number(lv.enter_wide);
    const exitW = Number(lv.exit_wide);
    const enterN = Number(lv.enter_narrow);
    const exitN = Number(lv.exit_narrow);
    const mk = (price, color, title) => {
      if (!Number.isFinite(price)) return;
      priceLines.push(zSeries.createPriceLine({
        price, color, lineWidth: 1, lineStyle: 2, title,
      }));
    };
    mk(enterW, '#2962ff', `S вх ${fmt(enterW, 1)}`);
    mk(exitW, '#26a69a', `S вых ${fmt(exitW, 1)}`);
    mk(exitN, '#26a69a', `L вых ${fmt(exitN, 1)}`);
    mk(enterN, '#2962ff', `L вх ${fmt(enterN, 1)}`);
  }

  /** Снять линию цели ТП с верхнего графика Прода. */
  function clearTpExitSpreadLine() {
    if (!tpExitPriceLine) return;
    try {
      if (zSeries && typeof zSeries.removePriceLine === 'function') {
        zSeries.removePriceLine(tpExitPriceLine);
      }
    } catch (_) { /* */ }
    tpExitPriceLine = null;
  }

  /**
   * Горизонталь «цель выхода по ТП» на графике Прода.
   * Уровень — close_forecast.exit_level_spread. Нет открытой / ТП выкл → линии нет.
   */
  function setTpExitSpreadLine(openTrade, closeForecast, settings) {
    clearTpExitSpreadLine();
    if (!zSeries || typeof zSeries.createPriceLine !== 'function') return;
    if (!openTrade) return;
    const tpRaw = Number(settings?.take_profit_pct);
    const tpPct = Number.isFinite(tpRaw) ? tpRaw : 0;
    if (!(tpPct > 0)) return;

    const fc = closeForecast
      || (lastCloseForecast && lastCloseForecast.exit_level_spread != null
        ? lastCloseForecast
        : null);
    const pot = exitLevelPotential(fc, openTrade, {
      settings,
      depositRub: entryDepositRub(openTrade, settings),
    });
    if (!pot || !Number.isFinite(pot.spread)) return;

    const title = `ТП ${fmt(tpPct, tpPct % 1 === 0 ? 0 : 1)}%`;
    try {
      tpExitPriceLine = zSeries.createPriceLine({
        price: Number(pot.spread),
        color: TP_EXIT_LINE_COLOR,
        lineWidth: 1,
        lineStyle: 3,
        title,
        axisLabelVisible: true,
      });
    } catch (_) {
      tpExitPriceLine = null;
    }
  }

  /** Уровни спреда из desk.spread_levels / settings (не хардкод-only). */
  function resolveSpreadLevelLines(settings, spreadLevelsPayload) {
    const sl = spreadLevelsPayload || {};
    const lv = sl.levels || {};
    const cuts = sl.cuts || {};
    const num = (v, fallback) => {
      const n = Number(v);
      return Number.isFinite(n) ? n : fallback;
    };
    return {
      levels: {
        enter_wide: num(lv.enter_wide ?? settings?.spread_enter_wide, 6.1),
        exit_wide: num(lv.exit_wide ?? settings?.spread_exit_wide, 5.8),
        enter_narrow: num(lv.enter_narrow ?? settings?.spread_enter_narrow, 3.2),
        exit_narrow: num(lv.exit_narrow ?? settings?.spread_exit_narrow, 4.0),
      },
      cuts: {
        narrow_max: num(cuts.narrow_max, 3.5),
        wide_min: num(cuts.wide_min, 5.5),
      },
    };
  }

  function setSpreadThresholdLines(levels) {
    if (!spreadSeries || typeof spreadSeries.createPriceLine !== 'function') return;
    spreadPriceLines.forEach((pl) => {
      try { spreadSeries.removePriceLine(pl); } catch (_) {}
    });
    spreadPriceLines = [];
    const lv = levels || {};
    const enterW = Number(lv.enter_wide);
    const exitW = Number(lv.exit_wide);
    const enterN = Number(lv.enter_narrow);
    const exitN = Number(lv.exit_narrow);
    const mk = (price, color, title) => {
      if (!Number.isFinite(price)) return;
      spreadPriceLines.push(spreadSeries.createPriceLine({
        price, color, lineWidth: 1, lineStyle: 2, title,
      }));
    };
    // Как Z: синий = вход, бирюзовый = выход; подписи Short/Long (запятая ru-RU).
    // Cuts режима (3.5/5.5) не рисуем — только в engine для gating.
    // Порядок сверху вниз как на скрине: S вх → S вых → L вых → L вх.
    mk(enterW, '#2962ff', `S вх ${fmt(enterW, 1)}`);
    mk(exitW, '#26a69a', `S вых ${fmt(exitW, 1)}`);
    mk(exitN, '#26a69a', `L вых ${fmt(exitN, 1)}`);
    mk(enterN, '#2962ff', `L вх ${fmt(enterN, 1)}`);
    // Zone fills = торговые полосы L/S вх–вых (не cuts).
    updateSpreadRegimeBands(null, lv);
  }

  function barsFingerprint(bars) {
    if (!bars || !bars.length) return '0';
    const first = bars[0];
    const last = bars[bars.length - 1];
    return [
      bars.length,
      first?.time || '',
      last?.time || '',
      last?.z ?? '',
      last?.spread ?? '',
      last?.timestampMs ?? '',
    ].join('|');
  }

  /**
   * Live tip Z/S% from monitor / open mark — not parquet/sidecar chart tail.
   * Desk tip1m bars often lag (cold μ/σ or sidecar ≈−1.9 while tip ≈−2.3).
   */
  function liveTipZSpread(data, openTrade = null) {
    const open = openTrade || (data && data.open) || null;
    const mark = (open && open.mark) || {};
    const mon = (data && data.monitor) || {};
    const s = (data && data.summary) || {};
    const dealer = (data && data.dealer) || {};
    const msk = nowMskParts();
    const spreadFrozen = dealer.spread_live === false
      || (msk.spreadLive === false && !msk.weekend);
    let z = null;
    let sp = null;
    if (mark.z_now != null && Number.isFinite(Number(mark.z_now))) z = Number(mark.z_now);
    else if (mon.last_z != null && Number.isFinite(Number(mon.last_z))) z = Number(mon.last_z);
    else if (s.z != null && Number.isFinite(Number(s.z))) z = Number(s.z);
    if (spreadFrozen) {
      const good = lastSpreadLiveBar((data && data.bars) || []);
      if (good && good.spread != null && Number.isFinite(Number(good.spread))) {
        sp = Number(good.spread);
      } else if (dealer.spread != null && Number.isFinite(Number(dealer.spread))) {
        sp = Number(dealer.spread);
      } else if (s.spread != null && Number.isFinite(Number(s.spread))) {
        sp = Number(s.spread);
      }
    } else if (mark.spread_now != null && Number.isFinite(Number(mark.spread_now))) {
      sp = Number(mark.spread_now);
    } else if (s.spread != null && Number.isFinite(Number(s.spread))) {
      sp = Number(s.spread);
    }
    return { z, sp };
  }

  /** Patch last tip1m bar to live tip so last-price label ≡ overlay ≡ monitor. */
  function alignTip1mBarsToLiveTip(bars, data, openTrade = null) {
    if (!Array.isArray(bars) || !bars.length) return bars || [];
    const live = liveTipZSpread(data, openTrade);
    // Never paint live tip as 0 / null — warmup sentinels make fake candle spikes.
    const liveZ = (live.z != null && Number.isFinite(live.z) && Math.abs(live.z) > 1e-12)
      ? live.z
      : null;
    const liveSp = (live.sp != null && Number.isFinite(live.sp)) ? live.sp : null;
    if (liveZ == null && liveSp == null) return bars;
    const last = bars[bars.length - 1];
    if (!last) return bars;
    const curZ = last.z != null ? Number(last.z) : NaN;
    const curSp = last.spread != null ? Number(last.spread) : NaN;
    const needZ = liveZ != null && (!Number.isFinite(curZ) || Math.abs(curZ - liveZ) > 1e-6);
    const needSp = liveSp != null && (!Number.isFinite(curSp) || Math.abs(curSp - liveSp) > 1e-6);
    if (!needZ && !needSp) return bars;
    const out = bars.slice();
    const row = { ...last };
    if (needZ) {
      row.z = liveZ;
      // Forming bar: flat Z OHLC at live tip — do not leave stale z_open/high/low
      // (or let prevZ→close invent a wick down to entry / sidecar gap).
      row.z_open = liveZ;
      row.z_high = liveZ;
      row.z_low = liveZ;
    }
    if (needSp) row.spread = liveSp;
    out[out.length - 1] = row;
    return out;
  }

  /** Keep Z candles + spread line on identical sorted timestamps (1:1). */
  function syncChartSeriesByTime(zData, spreadPts) {
    const zBy = new Map();
    for (const c of zData || []) {
      if (c && c.time != null && !zBy.has(c.time)) zBy.set(c.time, c);
    }
    const spBy = new Map();
    for (const p of spreadPts || []) {
      if (p && p.time != null && Number.isFinite(Number(p.value)) && !spBy.has(p.time)) {
        spBy.set(p.time, p);
      }
    }
    const times = [];
    for (const t of zBy.keys()) {
      if (spBy.has(t)) times.push(t);
    }
    times.sort((a, b) => a - b);
    return {
      zData: times.map((t) => zBy.get(t)),
      spreadPts: times.map((t) => spBy.get(t)),
    };
  }

  function resolveOpenEntryOnBars(bars, open) {
    if (!open || !bars || !bars.length) return null;
    const entryZ = Number(open.entry_z);
    const entrySp = Number(open.entry_spread);
    const entrySec = toChartTime(open.entry_time);
    if (entrySec == null) return null;

    const barTimes = [];
    const byTime = new Map();
    for (const b of bars) {
      const t = toChartTime(b.time, b.timestampMs);
      if (t == null) continue;
      barTimes.push(t);
      if (!byTime.has(t)) byTime.set(t, b);
    }
    if (!barTimes.length) return null;

    const maxSnap = Math.max(90, Math.round(estimateBarStepSec(barTimes) * 1.5));
    const snapped = snapSecToBarTimes(entrySec, barTimes, maxSnap);
    const z0 = Number.isFinite(entryZ) ? entryZ : NaN;
    const sp0 = Number.isFinite(entrySp) ? entrySp : NaN;

    // Prefer real entry_time on the axis; snap only to a nearby tip bar.
    if (snapped != null) {
      const b = byTime.get(snapped);
      const barSp = b && b.spread != null ? Number(b.spread) : NaN;
      return {
        time: snapped,
        z: Number.isFinite(z0) ? z0 : Number(b && b.z),
        spread: Number.isFinite(sp0) ? sp0 : (Number.isFinite(barSp) ? barSp : null),
        tradeDate: open.entry_time || (b && b.time) || null,
      };
    }

    const first = barTimes[0];
    const last = barTimes[barTimes.length - 1];
    // Inside (or just past) the painted window — keep exact entry time (inject later).
    if (entrySec >= first - maxSnap && entrySec <= last + maxSnap) {
      return {
        time: entrySec,
        z: Number.isFinite(z0) ? z0 : 0,
        spread: Number.isFinite(sp0) ? sp0 : null,
        tradeDate: open.entry_time,
        synthetic: true,
      };
    }
    // Entry AFTER series end (stale tip ending Fri, entry today) — never snap
    // to the last Friday candle; keep real entry_time for axis inject.
    if (entrySec > last + maxSnap) {
      return {
        time: entrySec,
        z: Number.isFinite(z0) ? z0 : 0,
        spread: Number.isFinite(sp0) ? sp0 : null,
        tradeDate: open.entry_time,
        synthetic: true,
      };
    }
    // Entry before chart window — omit marker.
    return null;
  }

  /** Спред % на баре tip1m по chart-time (когда API не отдал entry_spread). */
  function barSpreadAtChartSec(bars, chartSec) {
    if (chartSec == null || !Array.isArray(bars) || !bars.length) return null;
    for (const b of bars) {
      const t = toChartTime(b.time, b.timestampMs);
      if (t !== chartSec) continue;
      const sp = b.spread != null ? Number(b.spread) : NaN;
      if (Number.isFinite(sp)) return sp;
    }
    return null;
  }

  /** Y-координата линии highlight: на графике спреда — только %, не Z. */
  function deskSpreadChartValue(spreadHint, chartSec, zHint, bars) {
    if (chartPrimarySpread) {
      if (spreadHint != null && Number.isFinite(Number(spreadHint))) return Number(spreadHint);
      const fromBar = barSpreadAtChartSec(bars, chartSec);
      if (fromBar != null) return fromBar;
      if (chartSec != null && zPriceByTime.has(chartSec)) {
        const v = Number(zPriceByTime.get(chartSec));
        if (Number.isFinite(v)) return v;
      }
      return NaN;
    }
    if (zHint != null && Number.isFinite(Number(zHint))) return Number(zHint);
    return NaN;
  }

  /** Ensure LW markers can land on entry_time (series must contain the time). */
  function injectOpenEntryIntoChartSeries(zData, spreadPts, open, bars, {
    primarySpread = false,
  } = {}) {
    const entry = resolveOpenEntryOnBars(bars, open);
    if (!entry || entry.time == null) {
      return { zData, spreadPts, bars };
    }
    const t = entry.time;
    const hasZ = (zData || []).some((c) => c && c.time === t);
    const dualPane = Array.isArray(spreadPts) && spreadPts.length > 0;
    const hasSp = dualPane && spreadPts.some((p) => p && p.time === t);
    // TATN/MTLR now paint one candle series; zip vs empty spreadPts left 1 bar
    // (all markers stacked at the live tick — «Аид» вместо свечей).
    if (!dualPane) {
      let nextZ = zData ? zData.slice() : [];
      let nextBars = bars ? bars.slice() : [];
      if (!hasZ) {
        const z = Number(entry.z);
        const zVal = Number.isFinite(z) ? z : 0;
        const spN = Number(entry.spread != null ? entry.spread : open.entry_spread);
        const spVal = Number.isFinite(spN) ? spN : zVal;
        const candleVal = primarySpread ? spVal : zVal;
        nextZ.push({
          time: t, open: candleVal, high: candleVal, low: candleVal, close: candleVal,
        });
        nextZ.sort((a, b) => a.time - b.time);
      }
      if (entry.synthetic && !nextBars.some((b) => toChartTime(b.time, b.timestampMs) === t)) {
        const z = Number(entry.z);
        const zVal = Number.isFinite(z) ? z : 0;
        const spN = Number(entry.spread != null ? entry.spread : open.entry_spread);
        const spVal = Number.isFinite(spN) ? spN : zVal;
        nextBars.push({
          time: open.entry_time,
          timestampMs: t * 1000,
          z: zVal,
          spread: spVal,
          interval: '1m',
          source: primarySpread ? 'tinvest_dealer_1m' : 'tip1m',
          for_z: !primarySpread,
        });
        nextBars.sort((a, b) => (
          (toChartTime(a.time, a.timestampMs) || 0) - (toChartTime(b.time, b.timestampMs) || 0)
        ));
      }
      return { zData: nextZ, spreadPts, bars: nextBars };
    }
    if (hasZ && hasSp && !entry.synthetic) {
      const synced = syncChartSeriesByTime(zData, spreadPts);
      return { zData: synced.zData, spreadPts: synced.spreadPts, bars };
    }
    const z = Number(entry.z);
    const zVal = Number.isFinite(z) ? z : 0;
    const spN = Number(entry.spread != null ? entry.spread : open.entry_spread);
    const spVal = Number.isFinite(spN) ? spN : zVal;
    const candleVal = primarySpread ? spVal : zVal;
    let nextZ = zData ? zData.slice() : [];
    let nextSp = spreadPts.slice();
    let nextBars = bars ? bars.slice() : [];
    // Dual pane: inject into BOTH series — never leave Z/spread length mismatch.
    if (!hasZ) {
      nextZ.push({
        time: t, open: candleVal, high: candleVal, low: candleVal, close: candleVal,
      });
    }
    if (!hasSp) {
      nextSp.push({ time: t, value: spVal });
    }
    nextZ.sort((a, b) => a.time - b.time);
    nextSp.sort((a, b) => a.time - b.time);
    const synced = syncChartSeriesByTime(nextZ, nextSp);
    nextZ = synced.zData;
    nextSp = synced.spreadPts;
    if (entry.synthetic && !nextBars.some((b) => toChartTime(b.time, b.timestampMs) === t)) {
      nextBars.push({
        time: open.entry_time,
        timestampMs: t * 1000,
        z: zVal,
        spread: spVal,
        interval: '1m',
        source: primarySpread ? 'tinvest_dealer_1m' : 'tip1m',
        for_z: !primarySpread,
      });
      nextBars.sort((a, b) => (
        (toChartTime(a.time, a.timestampMs) || 0) - (toChartTime(b.time, b.timestampMs) || 0)
      ));
    }
    return { zData: nextZ, spreadPts: nextSp, bars: nextBars };
  }

  function deskTradeById(id) {
    if (!id) return null;
    return lastDeskTrades.find((t) => t.id === id) || null;
  }

  function markerChartPrice(m) {
    const trade = deskTradeById(m.tradeId || m.text);
    if (!trade) return 0;
    const chartSec = m.time;
    if (chartPrimarySpread) {
      if (m.isEntry) {
        const v = deskSpreadChartValue(
          trade.entrySpread, chartSec, trade.entryZ, lastDeskPaintBars,
        );
        return Number.isFinite(v) ? v : 0;
      }
      const v = deskSpreadChartValue(
        trade.exitSpread != null ? trade.exitSpread : trade.entrySpread,
        chartSec,
        trade.exitSpread != null ? trade.exitZ : trade.entryZ,
        lastDeskPaintBars,
      );
      return Number.isFinite(v) ? v : 0;
    }
    return m.isEntry ? trade.entryZ : (trade.exitZ ?? trade.entryZ);
  }

  function markerScreenPosition(m) {
    if (!zChart || !zSeries) return null;
    const x = zChart.timeScale().timeToCoordinate(m.time);
    if (x == null) return null;
    const price = markerChartPrice(m);
    let y = zSeries.priceToCoordinate(price);
    if (y == null) return null;
    const yOffset = m.isEntry ? (m.position === 'belowBar' ? 18 : -18) : 0;
    return { x, y: y + yOffset };
  }

  function findNearestDeskMarkerAtPoint(point) {
    if (!lastDeskMarkers.length || !zChart) return null;
    let bestPixel = null;
    for (const m of lastDeskMarkers) {
      const pos = markerScreenPosition(m);
      const x = pos?.x ?? zChart.timeScale().timeToCoordinate(m.time);
      if (x == null) continue;
      const dx = Math.abs(point.x - x);
      const hitRadiusXPx = m.isEntry ? MARKER_ENTRY_HIT_RADIUS_X_PX : MARKER_HIT_RADIUS_X_PX;
      let pixelHit = false;
      let pixelScore = Number.POSITIVE_INFINITY;
      if (pos) {
        const dy = Math.abs(point.y - pos.y);
        const dist = Math.hypot(dx, dy);
        pixelHit = dist <= MARKER_HIT_RADIUS_PX || dx <= hitRadiusXPx;
        pixelScore = dx * 1.5 + dy;
      } else {
        pixelHit = dx <= hitRadiusXPx;
        pixelScore = dx;
      }
      if (pixelHit && (!bestPixel || pixelScore < bestPixel.score)) {
        bestPixel = { marker: m, score: pixelScore };
      }
    }
    return bestPixel?.marker ?? null;
  }

  function buildMarkerRenderData(markers, activeTradeId) {
    return markers.map((m) => {
      const isActive = !!activeTradeId && (m.tradeId === activeTradeId || m.text === activeTradeId);
      return {
        time: m.time,
        position: m.position,
        color: isActive ? TRADE_HIGHLIGHT_COLOR : m.color,
        shape: m.shape,
        text: m.text,
        size: isActive ? Math.max((m.size || 2.5) * 1.15, 2.8) : (m.size || 2.5),
      };
    });
  }

  function setHighlightSeriesData(data) {
    if (!openHighlightSeries) return;
    try {
      openHighlightSeries.setData(data || []);
    } catch (e) {
      console.warn('trade highlight', e);
    }
  }

  function highlightDataForTrade(trade) {
    if (!trade || trade.entryTime == null) return [];
    const exitTime = trade.exitTime ?? trade.entryTime;
    let entryVal;
    let exitVal;
    if (chartPrimarySpread) {
      entryVal = deskSpreadChartValue(
        trade.entrySpread, trade.entryTime, trade.entryZ, lastDeskPaintBars,
      );
      exitVal = deskSpreadChartValue(
        trade.exitSpread != null ? trade.exitSpread : trade.entrySpread,
        exitTime,
        trade.exitSpread != null ? trade.exitZ : trade.entryZ,
        lastDeskPaintBars,
      );
    } else {
      entryVal = Number(trade.entryZ);
      exitVal = Number(trade.exitZ ?? trade.entryZ);
    }
    if (!Number.isFinite(entryVal) || exitTime == null || !Number.isFinite(exitVal)) return [];
    return [
      { time: trade.entryTime, value: entryVal },
      { time: exitTime, value: exitVal },
    ].sort((a, b) => a.time - b.time);
  }

  function applyHighlightForActiveTrade() {
    const trade = deskTradeById(hoverTradeId);
    if (trade) {
      setHighlightSeriesData(highlightDataForTrade(trade));
      return;
    }
    setHighlightSeriesData(defaultOpenHighlightData || []);
  }

  function refreshDeskMarkers() {
    applyTradeMarkers(
      buildMarkerRenderData(lastDeskMarkers, hoverTradeId),
      buildMarkerRenderData(lastMtlrMarkers, null),
    );
    applyHighlightForActiveTrade();
  }

  function scheduleRefreshDeskMarkers() {
    if (refreshMarkersTimer) clearTimeout(refreshMarkersTimer);
    refreshMarkersTimer = setTimeout(() => {
      refreshMarkersTimer = 0;
      refreshDeskMarkers();
    }, 100);
  }

  function onDeskMarkerCrosshair(param) {
    if (userGestureActive || suppressRangeEvents) return;
    let nextHover = null;
    if (param.point && param.point.x >= 0 && param.point.y >= 0) {
      const marker = findNearestDeskMarkerAtPoint(param.point);
      if (marker && deskTradeById(marker.tradeId || marker.text)) {
        nextHover = marker.tradeId || marker.text || null;
      }
    }
    if (nextHover === hoverTradeId) return;
    hoverTradeId = nextHover;
    applyHighlightForActiveTrade();
    scheduleRefreshDeskMarkers();
  }

  function bindMarkerHover() {
    if (markerHoverBound || !zChart) return;
    markerHoverBound = true;
    zChart.subscribeCrosshairMove((param) => onDeskMarkerCrosshair(param));
  }

  function clearOpenTradeOnChart() {
    lastOpenTradeFp = '';
    defaultOpenHighlightData = null;
    if (!hoverTradeId) setHighlightSeriesData([]);
    else applyHighlightForActiveTrade();
    const el = $('tradeOpenTradeOverlay');
    if (el) {
      el.classList.add('hidden');
      el.innerHTML = '';
    }
  }

  function clearAllTradeMarkers() {
    hoverTradeId = null;
    lastDeskMarkers = [];
    lastDeskTrades = [];
    clearOpenTradeOnChart();
    try {
      if (openMarkersPlugin && typeof openMarkersPlugin.setMarkers === 'function') {
        openMarkersPlugin.setMarkers([]);
      } else if (zSeries && typeof zSeries.setMarkers === 'function') {
        zSeries.setMarkers([]);
      }
    } catch (_) {}
    try {
      if (openSpreadMarkersPlugin && typeof openSpreadMarkersPlugin.setMarkers === 'function') {
        openSpreadMarkersPlugin.setMarkers([]);
      } else if (spreadSeries && typeof spreadSeries.setMarkers === 'function') {
        spreadSeries.setMarkers([]);
      }
    } catch (_) {}
  }

  function applyMarkersToSeries(series, pluginRef, markerData) {
    if (!series) return pluginRef;
    try {
      if (LightweightCharts.createSeriesMarkers) {
        if (!pluginRef) {
          return LightweightCharts.createSeriesMarkers(series, markerData);
        }
        if (typeof pluginRef.setMarkers === 'function') {
          pluginRef.setMarkers(markerData);
        }
        return pluginRef;
      }
      if (typeof series.setMarkers === 'function') {
        series.setMarkers(markerData);
      }
    } catch (e) {
      console.warn('trade markers', e);
    }
    return pluginRef;
  }

  function applyTradeMarkers(tatnMarkerData, mtlrMarkerData) {
    openMarkersPlugin = applyMarkersToSeries(
      zSeries, openMarkersPlugin, tatnMarkerData || [],
    );
    openSpreadMarkersPlugin = applyMarkersToSeries(
      spreadSeries, openSpreadMarkersPlugin, mtlrMarkerData || [],
    );
  }

  /** Median Δt of chart bars — tip1m ~60s, M15 ~900s. */
  function estimateBarStepSec(barTimes) {
    if (!barTimes || barTimes.length < 2) return 60;
    const diffs = [];
    const n = Math.min(barTimes.length, 80);
    const start = Math.max(1, barTimes.length - n);
    for (let i = start; i < barTimes.length; i++) {
      const d = barTimes[i] - barTimes[i - 1];
      if (d > 0 && d < 7200) diffs.push(d);
    }
    if (!diffs.length) return 60;
    diffs.sort((a, b) => a - b);
    return diffs[Math.floor(diffs.length / 2)] || 60;
  }

  /**
   * Snap entry/exit to a real candle time for LW markers.
   * Returns null if nearest bar is farther than maxDeltaSec — NEVER pile
   * weekend/older trades onto the first Monday tip1m candle.
   */
  function snapSecToBarTimes(sec, barTimes, maxDeltaSec) {
    if (sec == null || !barTimes.length) return null;
    let best = barTimes[0];
    let bestD = Math.abs(best - sec);
    for (const t of barTimes) {
      const d = Math.abs(t - sec);
      if (d < bestD) {
        best = t;
        bestD = d;
      }
    }
    const lim = maxDeltaSec != null
      ? maxDeltaSec
      : Math.max(90, Math.round(estimateBarStepSec(barTimes) * 1.5));
    if (bestD > lim) return null;
    return best;
  }

  /** Маркеры закрытых + открытой сделки (стиль Теста: стрелка входа, круг выхода). */
  function isManualTradeSource(src) {
    const s = String(src || '').toUpperCase();
    return s === 'MANUAL' || s === 'BROKER' || s.includes('ПОДХВАТ') || s.includes('ADOPT');
  }

  function isSpreadLevelModeOn(settings, mark) {
    if (mark && mark.spread_level_mode === false) return false;
    if (mark && mark.spread_level_mode === true) return true;
    if (!settings) return true;
    if (settings.spread_level_mode === false) return false;
    if (settings.spread_level_mode === true || settings.spread_level_mode == null) return true;
    return String(settings.spread_level_mode) === '1';
  }

  /** Prod AUTO is spread-levels: drop leftover Z-risk text/score from old enrich. */
  function sanitizeOpenRisk(mark, settings) {
    const flags = Array.isArray(mark && mark.risk_flags) ? mark.risk_flags.slice() : [];
    let score = Number(mark && mark.risk_score);
    if (!Number.isFinite(score)) score = 0;
    const levelIn = mark && mark.risk_level;
    if (!isSpreadLevelModeOn(settings, mark)) {
      return {
        flags,
        score,
        level: levelIn || 'Ok',
        red: !!(mark && mark.risk_red),
      };
    }
    const kept = [];
    for (const f of flags) {
      const s = String(f || '');
      if (/Zвх/i.test(s) || /Zвx/i.test(s)) {
        score -= 2;
        continue;
      }
      if (/\|Z\|/.test(s) || /порог\s*Z/i.test(s)) {
        score -= 1;
        continue;
      }
      kept.push(f);
    }
    if (score < 0) score = 0;
    const level = score >= 6 ? 'Critical' : score >= 4 ? 'High' : score >= 3 ? 'Elevated' : 'Ok';
    return { flags: kept, score, level, red: score >= 4 };
  }

  function deskEntryMarkerColor(isLong, source) {
    if (isManualTradeSource(source)) {
      return isLong ? '#4FC3F7' : '#CE93D8';
    }
    return isLong ? '#69F0AE' : '#FF8A80';
  }

  function buildDeskTradeMarkers(closed, open, bars) {
    const barTimes = [];
    for (const b of bars || []) {
      const t = toChartTime(b.time, b.timestampMs);
      if (t != null) barTimes.push(t);
    }
    const maxSnap = Math.max(90, Math.round(estimateBarStepSec(barTimes) * 1.5));
    const markers = [];
    const trades = [];
    const list = [...(closed || [])].sort((a, b) => {
      const am = toChartTime(a.entry_time) || 0;
      const bm = toChartTime(b.entry_time) || 0;
      return am - bm;
    });
    let n = 0;
    const dirShort = (isLong) => (typeof tradeDirectionShort === 'function'
      ? tradeDirectionShort(isLong ? 'Long' : 'Short')
      : (isLong ? 'L' : 'S'));

    for (const t of list) {
      n += 1;
      const isLong = String(t.direction || '').toUpperCase().includes('LONG');
      // Номера 1L…N как в таблице; маркеры только если вход/выход попадает в окно графика.
      const entrySec = snapSecToBarTimes(toChartTime(t.entry_time), barTimes, maxSnap);
      const exitSec = snapSecToBarTimes(toChartTime(t.exit_time), barTimes, maxSnap);
      const label = `${n}${dirShort(isLong)}`;
      const tradeId = t.id != null ? String(t.id) : `desk-${n}`;
      const entryZ = Number(t.entry_z);
      const exitZ = Number(t.exit_z);
      const entrySp = Number(t.entry_spread);
      const exitSp = Number(t.exit_spread);
      const entrySpreadResolved = deskSpreadChartValue(entrySp, entrySec, entryZ, bars);
      const exitSpreadResolved = deskSpreadChartValue(exitSp, exitSec, exitZ, bars);
      const entryColor = deskEntryMarkerColor(isLong, t.source);
      if (entrySec != null) {
        trades.push({
          id: tradeId,
          entryTime: entrySec,
          entryZ: Number.isFinite(entryZ) ? entryZ : 0,
          exitTime: exitSec != null ? exitSec : entrySec,
          exitZ: Number.isFinite(exitZ) ? exitZ : (Number.isFinite(entryZ) ? entryZ : 0),
          entrySpread: Number.isFinite(entrySpreadResolved) ? entrySpreadResolved : null,
          exitSpread: Number.isFinite(exitSpreadResolved) ? exitSpreadResolved : null,
          open: false,
        });
        markers.push({
          time: entrySec,
          position: isLong ? 'belowBar' : 'aboveBar',
          color: entryColor,
          shape: isLong ? 'arrowUp' : 'arrowDown',
          text: label,
          size: 2.5,
          tradeId,
          isEntry: true,
        });
      }
      if (exitSec != null && exitSec !== entrySec) {
        markers.push({
          time: exitSec,
          position: 'inBar',
          color: isManualTradeSource(t.source) ? '#B39DDB' : '#FFCC80',
          shape: 'circle',
          text: label,
          size: 2.5,
          tradeId,
          isEntry: false,
        });
      }
    }

    if (open) {
      n += 1;
      const isLong = String(open.direction || '').toUpperCase() === 'LONG';
      const entry = resolveOpenEntryOnBars(bars, open);
      const last = bars && bars.length ? bars[bars.length - 1] : null;
      const exitTime = last ? toChartTime(last.time, last.timestampMs) : null;
      const mark = open.mark || {};
      // Prefer aligned last tip bar (same as overlay / last-price), then mark.
      let exitZ = last != null && last.z != null ? Number(last.z) : NaN;
      if (!Number.isFinite(exitZ) && mark.z_now != null) exitZ = Number(mark.z_now);
      let exitSp = last != null && last.spread != null ? Number(last.spread) : NaN;
      if (!Number.isFinite(exitSp) && mark.spread_now != null) exitSp = Number(mark.spread_now);
      const entrySpN = Number(entry && entry.spread != null
        ? entry.spread
        : (mark.fill_spread != null ? mark.fill_spread : open.entry_spread));
      const tradeId = open.id != null ? String(open.id) : `desk-open-${n}`;
      if (entry) {
        trades.push({
          id: tradeId,
          entryTime: entry.time,
          entryZ: entry.z,
          exitTime: exitTime != null ? exitTime : entry.time,
          exitZ: Number.isFinite(exitZ) ? exitZ : entry.z,
          entrySpread: Number.isFinite(entrySpN) ? entrySpN : null,
          exitSpread: Number.isFinite(exitSp) ? exitSp : null,
          open: true,
        });
        markers.push({
          time: entry.time,
          position: isLong ? 'belowBar' : 'aboveBar',
          color: deskEntryMarkerColor(isLong, open.source),
          shape: isLong ? 'arrowUp' : 'arrowDown',
          text: `${n}${dirShort(isLong)}`,
          size: 2.5,
          tradeId,
          isEntry: true,
        });
      }
    }

    markers.sort((a, b) => a.time - b.time || String(a.text).localeCompare(String(b.text)));
    return { markers, trades };
  }

  function updateOpenTradeOnChart(open, bars, closed = [], {
    mtlrOpen = null,
    mtlrBars = null,
    mtlrClosed = null,
  } = {}) {
    ensureCharts();
    lastDeskPaintBars = Array.isArray(bars) ? bars : [];
    const tatnBuilt = buildDeskTradeMarkers(closed, open, bars);
    lastDeskMarkers = tatnBuilt.markers;
    lastDeskTrades = tatnBuilt.trades;
    const mtlrBuilt = buildDeskTradeMarkers(
      mtlrClosed || [],
      mtlrOpen || null,
      mtlrBars != null ? mtlrBars : lastMtlrBars,
    );
    lastMtlrMarkers = mtlrBuilt.markers;
    if (hoverTradeId && !deskTradeById(hoverTradeId)) hoverTradeId = null;
    applyTradeMarkers(
      buildMarkerRenderData(tatnBuilt.markers, hoverTradeId),
      buildMarkerRenderData(mtlrBuilt.markers, null),
    );

    if (!open || !bars || !bars.length) {
      clearOpenTradeOnChart();
      return;
    }
    const mark = open.mark || {};
    const entry = resolveOpenEntryOnBars(bars, open);
    const last = bars[bars.length - 1];
    const exitTime = toChartTime(last.time, last.timestampMs);
    // Overlay must match last painted primary candle (Z or spread %).
    let exitVal = NaN;
    if (exitTime != null && zPriceByTime.has(exitTime)) {
      exitVal = Number(zPriceByTime.get(exitTime));
    }
    if (!Number.isFinite(exitVal)) {
      if (chartPrimarySpread) {
        if (last != null && last.spread != null) exitVal = Number(last.spread);
        if (!Number.isFinite(exitVal) && mark.spread_now != null) exitVal = Number(mark.spread_now);
      } else {
        if (last != null && last.z != null) exitVal = Number(last.z);
        if (!Number.isFinite(exitVal) && mark.z_now != null) exitVal = Number(mark.z_now);
      }
    }
    const entryVal = chartPrimarySpread
      ? Number(entry && entry.spread != null
        ? entry.spread
        : (mark.fill_spread != null ? mark.fill_spread : open.entry_spread))
      : Number(entry && entry.z);
    if (!entry || exitTime == null || !Number.isFinite(exitVal) || !Number.isFinite(entryVal)) {
      clearOpenTradeOnChart();
      return;
    }
    const dir = String(open.direction || '').toUpperCase();
    const isLong = dir === 'LONG';
    const fp = [
      open.id, entry.time, entryVal, exitTime, exitVal,
      mark.unrealized_pnl_rub, mark.spread_now, mark.fills_spread,
      (closed || []).length, chartPrimarySpread ? 'sp' : 'z',
      String(open.entry_comment || ''),
    ].join('|');
    lastOpenTradeFp = fp;

    defaultOpenHighlightData = [
      { time: entry.time, value: entryVal },
      { time: exitTime, value: exitVal },
    ].sort((a, b) => a.time - b.time);
    applyHighlightForActiveTrade();

    const dirShort = typeof tradeDirectionShort === 'function'
      ? tradeDirectionShort(isLong ? 'Long' : 'Short')
      : (isLong ? 'L' : 'S');
    const el = $('tradeOpenTradeOverlay');
    if (!el) return;
    const net = Number(mark.net_approx_rub ?? mark.unrealized_pnl_rub);
    const pnlClass = net > 0 ? 'pnl-pos' : net < 0 ? 'pnl-neg' : '';
    const deposit = entryDepositRub(open, null);
    const netText = fmtPnlWithDepositPct(net, deposit);
    const srcUp = String(open.source || '').toUpperCase();
    const isManual = isManualTradeSource(open.source);
    const srcTag = isManual
      ? (srcUp === 'BROKER'
        ? ' <span class="ot-src-manual">· ручная (брокер)</span>'
        : ' <span class="ot-src-manual">· ручная</span>')
      : '';
    const entryLabel = typeof compactDateTime === 'function'
      ? compactDateTime(open.entry_time || entry.tradeDate)
      : (open.entry_time || entry.tradeDate || '');
    const entrySpread = mark.fill_spread != null ? mark.fill_spread : open.entry_spread;
    const nowSpread = mark.spread_now;
    const dirLabel = isLong ? 'LONG спрэд' : 'SHORT спрэд';
    const duration = typeof formatSimTradeDuration === 'function'
      ? formatSimTradeDuration(open.entry_time || entry.tradeDate, last.time || mark.trade_date)
      : '';
    const tradeNo = (closed || []).length + 1;
    // Комментарий — только в панели «Сделка», без дубля на оверлее графика.
    el.classList.remove('hidden');
    el.classList.toggle('ot-manual', isManual);
    el.innerHTML = [
      `<div class="ot-trade">${tradeNo} ${dirShort} ${entryLabel}${srcTag} `
        + `<span class="ot-pnl ${pnlClass}">${netText}</span></div>`,
      `<div class="ot-spread">${dirLabel} · ${fmt(entrySpread)}% → ${fmt(nowSpread)}%</div>`,
      duration ? `<div class="ot-duration">${duration}</div>` : '',
    ].join('');
  }

  /**
   * Display-only tip-style Z: rolling μ/σ on ISS M15 + dealer 1m tip as last obs.
   * Never feeds AUTO — UI chart only (for_z stays false / z_kind=dealer_monitor).
   */
  function attachDealerMonitorZClient(dealerBars, m15Bars) {
    const src = Array.isArray(dealerBars) ? dealerBars : [];
    if (!src.length) return src;
    if (src.some((b) => b && b.z != null && Number.isFinite(Number(b.z))
      && (b.z_kind === 'dealer_monitor' || (b.source && String(b.source).includes('dealer'))))) {
      return src;
    }
    const m15 = [];
    for (const b of m15Bars || []) {
      if (!b) continue;
      const sp = b.spread != null ? Number(b.spread)
        : (b.spreadPercent != null ? Number(b.spreadPercent) : NaN);
      const ms = Number(b.timestampMs || 0);
      if (!Number.isFinite(sp) || !(ms > 0)) continue;
      m15.push({ ms, sp });
    }
    m15.sort((a, b) => a.ms - b.ms);
    let completedEnd = 0;
    let winStart = 0;
    let total = 0;
    let totalSq = 0;
    const out = [];
    const dayMs = 86_400_000;
    for (const b of src) {
      const row = { ...b, for_z: false, z_kind: 'dealer_monitor' };
      const tipSp = b && b.spread != null ? Number(b.spread) : NaN;
      const tipMs = b ? Number(b.timestampMs || 0) : 0;
      if (!Number.isFinite(tipSp) || !(tipMs > 0)) {
        row.z = null;
        out.push(row);
        continue;
      }
      if (!m15.length) {
        row.z = null;
        out.push(row);
        continue;
      }
      const slotMs = Math.floor(tipMs / M15_MS) * M15_MS;
      while (completedEnd < m15.length && m15[completedEnd].ms < slotMs) {
        const s = m15[completedEnd].sp;
        total += s;
        totalSq += s * s;
        completedEnd += 1;
      }
      const fromMs = tipMs - Z_ROLL_LOOKBACK_DAYS * dayMs;
      while (winStart < completedEnd && m15[winStart].ms < fromMs) {
        const s = m15[winStart].sp;
        total -= s;
        totalSq -= s * s;
        winStart += 1;
      }
      const count = completedEnd - winStart;
      const n = count + 1;
      if (n < Z_ROLL_MIN_BARS) {
        row.z = 0;
      } else {
        const t = total + tipSp;
        const tsq = totalSq + tipSp * tipSp;
        const mean = t / n;
        let std = Math.sqrt(Math.max((tsq / n) - mean * mean, 0));
        if (std <= 1e-12) std = 1;
        row.z = (tipSp - mean) / std;
      }
      out.push(row);
    }
    // Fallback: rolling on dealer spreads alone if M15 window was empty.
    if (out.length && out.every((r) => r.z == null) && out.length >= Z_ROLL_MIN_BARS) {
      for (let i = 0; i < out.length; i += 1) {
        const from = Math.max(0, i - Z_ROLL_MIN_BARS + 1);
        let sum = 0;
        let sumSq = 0;
        let c = 0;
        for (let j = from; j <= i; j += 1) {
          const sp = Number(out[j].spread);
          if (!Number.isFinite(sp)) continue;
          sum += sp;
          sumSq += sp * sp;
          c += 1;
        }
        if (c < 2) {
          out[i].z = 0;
          continue;
        }
        const mean = sum / c;
        let std = Math.sqrt(Math.max((sumSq / c) - mean * mean, 0));
        if (std <= 1e-12) std = 1;
        out[i].z = (Number(out[i].spread) - mean) / std;
      }
    }
    return out;
  }

  /** Build one spread % OHLC candle: prefer server spread_open/high/low; else prev→close. */
  function spreadCandleFromBar(b, spClose, prevSp) {
    const sp = Number(spClose);
    if (!Number.isFinite(sp)) return null;
    const so = b && b.spread_open != null ? Number(b.spread_open) : NaN;
    const sh = b && b.spread_high != null ? Number(b.spread_high) : NaN;
    const sl = b && b.spread_low != null ? Number(b.spread_low) : NaN;
    const serverOk = Number.isFinite(so) && Number.isFinite(sh) && Number.isFinite(sl);
    if (serverOk) {
      return {
        open: so,
        high: Math.max(so, sh, sl, sp),
        low: Math.min(so, sh, sl, sp),
        close: sp,
      };
    }
    const open = prevSp == null || !Number.isFinite(prevSp) ? sp : prevSp;
    return {
      open,
      high: Math.max(open, sp),
      low: Math.min(open, sp),
      close: sp,
    };
  }

  /** Build one Z OHLC candle: prefer server z_open/high/low when set; else prevZ→currZ. */
  function zCandleFromBar(b, zClose, prevZ) {
    const z = Number(zClose);
    if (!Number.isFinite(z)) return null;
    const zo = b && b.z_open != null ? Number(b.z_open) : NaN;
    const zh = b && b.z_high != null ? Number(b.z_high) : NaN;
    const zl = b && b.z_low != null ? Number(b.z_low) : NaN;
    const serverOk = Number.isFinite(zo) && Number.isFinite(zh) && Number.isFinite(zl);
    if (serverOk) {
      // Explicit OHLC (incl. flat live-tip align) — never fall back to prevZ wick.
      return {
        open: zo,
        high: Math.max(zo, zh, zl, z),
        low: Math.min(zo, zh, zl, z),
        close: z,
      };
    }
    const open = prevZ == null || !Number.isFinite(prevZ) ? z : prevZ;
    return {
      open,
      high: Math.max(open, z),
      low: Math.min(open, z),
      close: z,
    };
  }

  /**
   * Median bar step in chart-time seconds (UTC unix). Null if <2 points.
   */
  function medianBarStepSec(bars) {
    const times = [];
    const seen = new Set();
    for (const b of bars || []) {
      const t = toChartTime(b.time, b.timestampMs);
      if (t == null || seen.has(t)) continue;
      seen.add(t);
      times.push(t);
    }
    if (times.length < 2) return null;
    const diffs = [];
    for (let i = 1; i < Math.min(times.length, 40); i += 1) {
      diffs.push(times[i] - times[i - 1]);
    }
    diffs.sort((a, b) => a - b);
    return diffs[Math.floor(diffs.length / 2)] || 0;
  }

  /**
   * Z candles from tip1m bars with z (~1m step) — never M15, never TATN mid.
   * Returns [] if series is empty or looks like M15 (≥10m median step).
   * Prefer server z_open/high/low; else open=prevZ, close=currZ (visible bodies).
   * Last (forming) bar is always flat at close — live-tip align must not invent
   * a wick from stale sidecar prevZ down to entry / monitor Z.
   */
  function buildTip1mZCandles(bars) {
    const pts = [];
    let prevZ = null;
    const seen = new Set();
    const times = [];
    // Warmup sentinel from short M15 (exact 0.0) — skip so price scale isn't
    // pinned to zero while live tip is ~-2.x (looks like a blank Z pane).
    let nonzero = 0;
    for (const b of bars || []) {
      if (b && b.z != null && Math.abs(Number(b.z)) > 1e-12) nonzero += 1;
    }
    const skipExactZero = nonzero >= 8;
    for (const b of bars || []) {
      const t = toChartTime(b.time, b.timestampMs);
      if (t == null || b.z == null || Number.isNaN(Number(b.z)) || seen.has(t)) continue;
      // Dealer mid rows without display Z — skipped. Never use TATN price as Z.
      if (b.interval === '1m' && b.source && String(b.source).includes('dealer') && b.z == null) continue;
      const z = Number(b.z);
      if (!Number.isFinite(z)) continue;
      if (skipExactZero && Math.abs(z) < 1e-12) continue;
      seen.add(t);
      times.push(t);
      const candle = zCandleFromBar(b, z, prevZ);
      if (!candle) continue;
      pts.push({ time: t, ...candle });
      prevZ = z;
    }
    // Forming right-edge bar: OHLC = close (no prevZ body / entry-line wick).
    if (pts.length) {
      const last = pts[pts.length - 1];
      const c = Number(last.close);
      if (Number.isFinite(c)) {
        pts[pts.length - 1] = { time: last.time, open: c, high: c, low: c, close: c };
      }
    }
    if (pts.length < 2) return pts;
    const diffs = [];
    for (let i = 1; i < Math.min(times.length, 40); i += 1) {
      diffs.push(times[i] - times[i - 1]);
    }
    diffs.sort((a, b) => a - b);
    const med = diffs[Math.floor(diffs.length / 2)] || 0;
    // M15 masquerading as tip1m → reject (HARD RULE)
    if (med >= 600) return [];
    return pts;
  }

  /**
   * Weekday tip1m paint series: Z candles + spread, identical timestamps.
   * Rejects M15-density (HARD RULE). Zip by time — same length, same keys.
   */
  function buildTip1mChartSeries(bars) {
    const zPts = buildTip1mZCandles(bars);
    if (zPts.length < 2) return { zPts: [], spreadPts: [] };
    const spByTime = new Map();
    for (const b of bars || []) {
      const t = toChartTime(b.time, b.timestampMs);
      if (t == null || spByTime.has(t)) continue;
      const sp = b.spread != null ? Number(b.spread) : NaN;
      if (!Number.isFinite(sp)) continue;
      spByTime.set(t, sp);
    }
    const zSynced = [];
    const spreadPts = [];
    for (const c of zPts) {
      if (!spByTime.has(c.time)) continue;
      zSynced.push(c);
      spreadPts.push({ time: c.time, value: spByTime.get(c.time) });
    }
    return { zPts: zSynced, spreadPts };
  }

  /** Calendar-day span of chart bars (for period chip coverage checks). */
  function tip1mSpanDays(bars) {
    if (!Array.isArray(bars) || bars.length < 2) return 0;
    const msOf = (b) => {
      const ms = Number(b && b.timestampMs);
      if (Number.isFinite(ms) && ms > 0) return ms;
      const s = String((b && (b.time || b.tradeDate)) || '').replace('T', ' ').trim();
      const t = Date.parse(s.length === 16 ? `${s}:00` : s);
      return Number.isFinite(t) ? t : 0;
    };
    let first = 0;
    let last = 0;
    for (let i = 0; i < bars.length; i++) {
      first = msOf(bars[i]);
      if (first > 0) break;
    }
    for (let i = bars.length - 1; i >= 0; i--) {
      last = msOf(bars[i]);
      if (last > 0) break;
    }
    if (first <= 0 || last <= first) return 0;
    return (last - first) / 86_400_000;
  }

  function filterTip1mBarsByDays(bars, wantDays) {
    const d = Math.max(1, Number(wantDays) || 7);
    if (!Array.isArray(bars) || bars.length < 2) return bars || [];
    let lastMs = 0;
    for (let i = bars.length - 1; i >= 0; i--) {
      const ms = Number(bars[i] && bars[i].timestampMs);
      if (Number.isFinite(ms) && ms > 0) { lastMs = ms; break; }
    }
    if (lastMs <= 0) return bars;
    const cut = lastMs - d * 86_400_000;
    return bars.filter((b) => Number(b && b.timestampMs) >= cut);
  }

  /**
   * Уменьшить плотность длинного tip1m только для отрисовки.
   * Диапазон дат, open/close и внутригрупповые min/max сохраняются.
   */
  function thinDeskTip1mBars(bars, maxPoints = 2500) {
    if (!Array.isArray(bars) || bars.length <= maxPoints) {
      return { bars: bars || [], stepMin: 1, rawCount: (bars || []).length };
    }
    const step = Math.max(2, Math.ceil(bars.length / maxPoints));
    const out = [];
    let group = [];
    let groupDay = '';

    const num = (v) => {
      const n = Number(v);
      return Number.isFinite(n) ? n : null;
    };
    const dayOf = (b) => String((b && (b.time || b.tradeDate)) || '').slice(0, 10);
    const flush = () => {
      if (!group.length) return;
      const first = group[0];
      const last = group[group.length - 1];
      const spVals = [];
      const zVals = [];
      for (const b of group) {
        for (const key of ['spread', 'spread_open', 'spread_high', 'spread_low']) {
          const v = num(b && b[key]);
          if (v != null) spVals.push(v);
        }
        for (const key of ['z', 'z_open', 'z_high', 'z_low']) {
          const v = num(b && b[key]);
          if (v != null) zVals.push(v);
        }
      }
      const row = {
        ...last,
        time: last.time || last.tradeDate,
        timestampMs: last.timestampMs,
        interval: `${step}m`,
        display_step_min: step,
      };
      const spOpen = num(first.spread_open) ?? num(first.spread);
      const spClose = num(last.spread);
      if (spOpen != null && spClose != null && spVals.length) {
        row.spread = spClose;
        row.spread_open = spOpen;
        row.spread_high = Math.max(...spVals, spOpen, spClose);
        row.spread_low = Math.min(...spVals, spOpen, spClose);
      }
      const zOpen = num(first.z_open) ?? num(first.z);
      const zClose = num(last.z);
      if (zOpen != null && zClose != null && zVals.length) {
        row.z = zClose;
        row.z_open = zOpen;
        row.z_high = Math.max(...zVals, zOpen, zClose);
        row.z_low = Math.min(...zVals, zOpen, zClose);
      }
      out.push(row);
      group = [];
    };

    for (const b of bars) {
      const day = dayOf(b);
      if (group.length && (group.length >= step || day !== groupDay)) flush();
      if (!group.length) groupDay = day;
      group.push(b);
    }
    flush();
    return { bars: out, stepMin: step, rawCount: bars.length };
  }

  /**
   * If desk still serves iss_m15 under tip1m labels (pre-reload / race),
   * or tip1m is session-only while period chip asks 1Н/1М/3М/6М —
   * replace with real tip1m (static sidecar → bars1m) or empty — never paint M15.
   */
  async function ensureWeekdayTip1mBars(data) {
    if (!data || data.weekend_monitor) return data;
    const mode = String(data.bars_mode || '');
    if (mode.startsWith('dealer')) return data;
    // Chip intent wins over desk payload (old API may coerce 180→7 until reload).
    const wantDays = Number(days || data.days || 7) || 7;
    const med = medianBarStepSec(data.bars);
    const span = tip1mSpanDays(data.bars);
    // Session-only (~<0.6д) is fine for 1Д; multi-day chips need real lookback.
    const coversPeriod = wantDays <= 1
      ? (Array.isArray(data.bars) && data.bars.length >= 2)
      : span >= Math.min(wantDays * 0.45, wantDays - 0.4);
    const looksTip = mode === 'tip1m' && (med == null || med < 600)
      && Array.isArray(data.bars) && data.bars.length >= 2
      && coversPeriod;
    if (looksTip) return data;

    const issKeep = (data.bars_iss && data.bars_iss.length)
      ? data.bars_iss
      : (mode === 'iss_m15' ? (data.bars || []) : (data.bars_iss || []));

    let tipBars = null;
    // 1) Sidecar JSON (works without uvicorn reload while SHORT is open)
    try {
      const r = await fetch(`/static/desk_tip1m.json?v=${Date.now()}`, { cache: 'no-store' });
      if (r.ok) {
        const j = await r.json();
        const bars = filterTip1mBarsByDays(j && j.bars, wantDays);
        const spanSide = tip1mSpanDays(bars);
        const okSpan = wantDays <= 1 || spanSide >= Math.min(wantDays * 0.45, wantDays - 0.4);
        if (Array.isArray(bars) && bars.length >= 5 && (medianBarStepSec(bars) || 9999) < 600 && okSpan) {
          tipBars = bars;
        }
      }
    } catch (_) { /* ignore */ }

    // 2) Testing /api/bars1m parquet (may lag vs live tip)
    if (!tipBars) {
      try {
        const d = wantDays;
        const r = await fetch(`/api/bars1m?csv=${encodeURIComponent('m15_tatn_255d.csv')}&chartDays=${d}`);
        if (r.ok) {
          const j = await r.json();
          const raw = j && j.bars;
          if (Array.isArray(raw) && raw.length >= 5) {
            const mapped = raw.map((b) => ({
              time: b.tradeDate || b.time,
              timestampMs: b.timestampMs,
              z: b.zScore != null ? b.zScore : b.z,
              spread: b.spreadPercent != null ? b.spreadPercent : b.spread,
              tatn: b.tatnClose != null ? b.tatnClose : b.tatn,
              tatnp: b.tatnpClose != null ? b.tatnpClose : b.tatnp,
              interval: '1m',
              source: 'tip1m',
              for_z: true,
            }));
            if ((medianBarStepSec(mapped) || 9999) < 600) tipBars = mapped;
          }
        }
      } catch (_) { /* ignore */ }
    }

    if (tipBars && tipBars.length) {
      const last = tipBars[tipBars.length - 1];
      const summary = { ...(data.summary || {}) };
      summary.bars_mode = 'tip1m';
      summary.window_count = tipBars.length;
      // Do not replace live markets S% with a stale parquet/sidecar tip.
      const tipMs = Number(last && last.timestampMs) || 0;
      const mktTd = String(summary.trade_date || '').replace('T', ' ').trim();
      let mktMs = 0;
      if (mktTd.length >= 16) {
        const p = Date.parse(mktTd.replace(' ', 'T') + '+03:00');
        if (Number.isFinite(p)) mktMs = p;
      }
      const tipStale = tipMs > 0 && mktMs > 0 && (mktMs - tipMs) > 45 * 60 * 1000;
      // Prefer live tip (monitor / open mark) over sidecar/parquet Z on the tail.
      const live = liveTipZSpread(data);
      let barsOut = tipBars;
      if (!tipStale && (live.z != null || live.sp != null)) {
        barsOut = alignTip1mBarsToLiveTip(tipBars, data);
        const aligned = barsOut[barsOut.length - 1] || last;
        if (aligned.z != null) summary.z = aligned.z;
        if (aligned.spread != null) summary.spread = aligned.spread;
        if (aligned.time) summary.trade_date = aligned.time;
      } else if (!tipStale) {
        if (last.z != null) summary.z = last.z;
        if (last.spread != null) summary.spread = last.spread;
        if (last.time) summary.trade_date = last.time;
      }
      return {
        ...data,
        days: wantDays,
        bars: barsOut,
        bars_iss: issKeep,
        bars_mode: 'tip1m',
        tip1m_warming: tipStale ? true : data.tip1m_warming,
        partial: tipStale ? true : data.partial,
        summary,
      };
    }

    // HARD RULE: empty tip1m > M15 under tip1m label
    return {
      ...data,
      bars: [],
      bars_iss: issKeep,
      bars_mode: 'tip1m',
      partial: true,
      tip1m_warming: true,
      summary: { ...(data.summary || {}), bars_mode: 'tip1m' },
    };
  }

  /**
   * If API still clips MTLR m15 to 2500 bars (~45д), fill 1М/3М/6М from sidecar.
   */
  async function ensureMtlrChartBars(bars, wantDays) {
    const d = Math.max(1, Number(wantDays) || 7);
    const span = tip1mSpanDays(bars);
    const covers = d <= 30
      ? (Array.isArray(bars) && bars.length >= 2)
      : span >= Math.min(d * 0.45, d - 0.4);
    if (Array.isArray(bars) && bars.length >= 5 && covers) return bars;
    try {
      const r = await fetch(`/static/desk_mtlr_m15.json?v=${Date.now()}`, { cache: 'no-store' });
      if (r.ok) {
        const j = await r.json();
        const raw = filterTip1mBarsByDays(j && j.bars, d);
        const spanSide = tip1mSpanDays(raw);
        const okSpan = d <= 30 || spanSide >= Math.min(d * 0.45, d - 0.4);
        if (Array.isArray(raw) && raw.length >= 5 && okSpan) return raw;
      }
    } catch (_) { /* keep API bars */ }
    return bars || [];
  }

  /**
   * Spread % candles for TATN tip1m/dealer (top). Prefer spread_open/high/low; else prevSp→currSp.
   */
  function buildSpread1mChartSeries(bars) {
    const candlePts = [];
    const spreadPts = [];
    const seen = new Set();
    let prevSp = null;
    for (const b of bars || []) {
      if (!b) continue;
      const t = toChartTime(b.time, b.timestampMs);
      if (t == null || seen.has(t)) continue;
      const sp = b.spread != null ? Number(b.spread) : NaN;
      if (!Number.isFinite(sp)) continue;
      seen.add(t);
      spreadPts.push({ time: t, value: sp });
      const candle = spreadCandleFromBar(b, sp, prevSp);
      if (!candle) continue;
      candlePts.push({ time: t, ...candle });
      prevSp = sp;
    }
    // Reject M15 masquerading as 1m (≥10m median step), но разрешить
    // явно агрегированный для отрисовки длинный tip1m.
    if (candlePts.length >= 2) {
      const diffs = [];
      for (let i = 1; i < Math.min(candlePts.length, 40); i += 1) {
        diffs.push(candlePts[i].time - candlePts[i - 1].time);
      }
      diffs.sort((a, b) => a - b);
      const med = diffs[Math.floor(diffs.length / 2)] || 0;
      const displayAggregated = (bars || []).some(
        (b) => Number(b && b.display_step_min) > 1,
      );
      if (med >= 600 && !displayAggregated) return { zPts: [], spreadPts: [] };
    }
    return { zPts: candlePts, spreadPts };
  }

  /**
   * Spread % candles for MTLR m15 (bottom pane). Allows ~15m step.
   */
  function buildSpreadM15ChartSeries(bars) {
    const candlePts = [];
    const seen = new Set();
    let prevSp = null;
    for (const b of bars || []) {
      if (!b) continue;
      const t = toChartTime(b.time, b.timestampMs);
      if (t == null || seen.has(t)) continue;
      const sp = b.spread != null ? Number(b.spread) : NaN;
      if (!Number.isFinite(sp)) continue;
      seen.add(t);
      const candle = spreadCandleFromBar(b, sp, prevSp);
      if (!candle) continue;
      candlePts.push({ time: t, ...candle });
      prevSp = sp;
    }
    return candlePts;
  }

  function chartPointVal(p) {
    if (!p) return NaN;
    if (p.value != null) return Number(p.value);
    return Number(p.close);
  }

  function chartPrefixEqual(prev, next) {
    if (!prev || !next || !prev.length || next.length < prev.length) return false;
    for (let i = 0; i < prev.length; i += 1) {
      if (prev[i].time !== next[i].time) return false;
      const a = chartPointVal(prev[i]);
      const b = chartPointVal(next[i]);
      if (!Number.isFinite(a) || !Number.isFinite(b)) return false;
      if (Math.abs(a - b) > 1e-9) return false;
    }
    return true;
  }

  /** Только хвост изменился — update() вместо setData на тысячах баров. */
  function canTailUpdateChart(prev, next) {
    if (!prev || !next || !prev.length) return false;
    if (next.length > prev.length + 1) return false;
    if (next.length === prev.length + 1) {
      return chartPrefixEqual(prev, next.slice(0, prev.length));
    }
    if (next.length === prev.length) return true;
    return false;
  }

  function applyTailChartUpdate(zData, mtlrCandlePts) {
    if (!zSeries || !zData.length) return false;
    const tail = zData[zData.length - 1];
    if (zSeriesIsLine) {
      zSeries.update({
        time: tail.time,
        value: chartPointVal(tail),
      });
    } else {
      zSeries.update(tail);
    }
    if (spreadSeries && mtlrCandlePts && mtlrCandlePts.length) {
      const mTail = mtlrCandlePts[mtlrCandlePts.length - 1];
      if (spreadSeriesIsLine) {
        spreadSeries.update({ time: mTail.time, value: chartPointVal(mTail) });
      } else {
        spreadSeries.update(mTail);
      }
    }
    lastPaintZData = zData;
    lastPaintMtlrData = mtlrCandlePts;
    return true;
  }

  /**
   * Top: TATN tip1m/dealer spread candles. Bottom: MTLR m15 spread candles + Mechel levels.
   */
  function renderCharts(bars, entry, exitZ, openTrade = null, closedTrades = [], {
    dealer1m = false,
    weekendMonitor = false,
    zBars = null,
    spreadLevels = null,
    spreadCuts = null,
    lookbackDays = null,
    mtlrBars = null,
    mtlrLevels = null,
    mtlrOpen = null,
    mtlrClosed = null,
    corridor = null,
    closeForecast = null,
    settings = null,
  } = {}) {
    ensureCharts();
    const monMode = !!(dealer1m || weekendMonitor);
    if (zSeriesIsLine || !zSeries) {
      ensureZSeriesKind(false);
    }
    if (spreadSeriesIsLine || !spreadSeries) {
      ensureSpreadSeriesKind(false);
    }
    if (!zSeries || !spreadSeries) return;

    if (monMode !== chartDealer1m) {
      chartDealer1m = monMode;
      forceFitContent = true;
      clearPinState();
    }
    if (!chartPrimarySpread) {
      chartPrimarySpread = true;
      forceFitContent = true;
      clearPinState();
    }

    let zData = [];
    let paintBars = bars;
    const mtlrPaint = Array.isArray(mtlrBars) ? mtlrBars : lastMtlrBars;
    if (Array.isArray(mtlrBars)) lastMtlrBars = mtlrBars.slice();
    const mtlrLv = mtlrLevels || lastMtlrLevels || {
      enter_wide: 8.9, exit_wide: 8.4, enter_narrow: 3.2, exit_narrow: 4.3,
    };
    if (mtlrLevels) lastMtlrLevels = mtlrLevels;

    const built = buildSpread1mChartSeries(paintBars);
    zData = built.zPts;
    if (openTrade) {
      const inj = injectOpenEntryIntoChartSeries(zData, [], openTrade, paintBars, {
        primarySpread: true,
      });
      zData = inj.zData;
      paintBars = inj.bars;
    }

    let mtlrCandlePts = buildSpreadM15ChartSeries(mtlrPaint);
    if (mtlrOpen) {
      const injM = injectOpenEntryIntoChartSeries(
        mtlrCandlePts, [], mtlrOpen, mtlrPaint, { primarySpread: true },
      );
      mtlrCandlePts = injM.zData;
    }

    zPriceByTime = new Map(zData.map((c) => [c.time, c.close != null ? c.close : c.value]));
    spPriceByTime = new Map(mtlrCandlePts.map((c) => [
      c.time, c.close != null ? c.close : c.value,
    ]));

    const zEmpty = zData.length === 0;
    const mtlrEmpty = mtlrCandlePts.length === 0;
    updateChartPaneLabels(monMode, {
      zEmpty: monMode && zEmpty,
      lookbackDays: monMode ? lookbackDays : null,
      spreadLevels,
      mtlrLevels: mtlrLv,
      mtlrEmpty,
    });
    // Не вызывать setData([]) — LC очищает серию; раз в минуту poll с пустым
    // payload мигал пустым графиком при живых осях/линии цены.
    if (zEmpty && lastPaintZData && lastPaintZData.length) {
      setZEmptyMessage('');
      try { window.__deskChartEmptySkip = (window.__deskChartEmptySkip || 0) + 1; } catch (_) {}
      setPrimarySpreadThresholdLines(spreadLevels);
      setTpExitSpreadLine(openTrade, closeForecast, settings);
      setSpreadThresholdLines(mtlrLv);
      paintCorridorOnChart(corridor, lastPaintZData);
      updateOpenTradeOnChart(openTrade, paintBars, closedTrades, {
        mtlrOpen, mtlrBars: mtlrPaint, mtlrClosed,
      });
      paintTradeOhlc(null);
      if (userPinnedAwayFromLive && pinnedRange) reassertPinnedRange();
      return;
    }

    if (monMode && zEmpty) {
      setZEmptyMessage('Нет баров дилер 1м · ISS M15 не рисуем · спред внизу — Мечел');
    } else if (!monMode && zEmpty) {
      setZEmptyMessage('Нет tip1m 1м · ISS M15 не рисуем под лейблом tip1m');
    } else {
      setZEmptyMessage('');
    }

    let bodyN = 0;
    for (const c of zData) {
      if (!c || c.open == null || c.close == null) continue;
      if (Math.abs(Number(c.open) - Number(c.close)) > 1e-9
        || (c.high != null && c.low != null && Math.abs(Number(c.high) - Number(c.low)) > 1e-9)) {
        bodyN += 1;
      }
    }
    const previousFirstTime = lastPaintZData && lastPaintZData.length
      ? Number(lastPaintZData[0].time)
      : null;
    const nextFirstTime = zData.length ? Number(zData[0].time) : null;
    if (!userPinnedAwayFromLive
      && Number.isFinite(previousFirstTime)
      && Number.isFinite(nextFirstTime)
      && nextFirstTime < previousFirstTime - 86400) {
      forceFitContent = true;
    }
    publishChartDebug({
      zPts: zData.length,
      spPts: mtlrCandlePts.length,
      bodyN,
      primary: chartPrimarySpread ? 'spread' : 'z',
      bottom: 'mtlr_m15',
      sample: zData.length ? [zData[0], zData[zData.length - 1]] : null,
      sync: { mode: 'time', zN: zData.length, mtlrN: mtlrCandlePts.length },
    });

    const fp = barsFingerprint(paintBars) + '|zc' + zData.length
      + '|mtlr' + mtlrCandlePts.length + '|' + barsFingerprint(mtlrPaint)
      + '|c' + (closedTrades || []).length + '|o' + (openTrade ? openTrade.id : '')
      + '|mc' + (mtlrClosed || []).length + '|mo' + (mtlrOpen ? mtlrOpen.id : '')
      + (monMode ? '|d1m' : '')
      + (zSeriesIsLine ? '|zl' : '|zcndl')
      + (spreadSeriesIsLine ? '|sl' : '|scndl')
      + (chartPrimarySpread ? '|ps' : '|pz')
      + (zEmpty ? '|ze' : '')
      + (mtlrEmpty ? '|me' : '')
      + (Array.isArray(zBars) ? '|iss' + zBars.length : '')
      + '|cr' + corridorFingerprint(corridor);
    const dataChanged = fp !== lastBarsFingerprint;
    lastBarsFingerprint = fp;

    if (!dataChanged && !forceFitContent) {
      setPrimarySpreadThresholdLines(spreadLevels);
      setTpExitSpreadLine(openTrade, closeForecast, settings);
      setSpreadThresholdLines(mtlrLv);
      paintCorridorOnChart(corridor, zData);
      updateOpenTradeOnChart(openTrade, paintBars, closedTrades, {
        mtlrOpen, mtlrBars: mtlrPaint, mtlrClosed,
      });
      paintTradeOhlc(null);
      if (userPinnedAwayFromLive && pinnedRange) reassertPinnedRange();
      return;
    }

    const tailOnly = !forceFitContent && dataChanged
      && canTailUpdateChart(lastPaintZData, zData)
      && (!mtlrCandlePts.length || !lastPaintMtlrData
        || canTailUpdateChart(lastPaintMtlrData, mtlrCandlePts));
    if (tailOnly && applyTailChartUpdate(zData, mtlrCandlePts)) {
      setPrimarySpreadThresholdLines(spreadLevels);
      setTpExitSpreadLine(openTrade, closeForecast, settings);
      setSpreadThresholdLines(mtlrLv);
      paintCorridorOnChart(corridor, zData);
      updateOpenTradeOnChart(openTrade, paintBars, closedTrades, {
        mtlrOpen, mtlrBars: mtlrPaint, mtlrClosed,
      });
      paintTradeOhlc(null);
      lastBarsFingerprint = fp;
      if (!userGestureActive) {
        if (userPinnedAwayFromLive) reassertPinnedRange();
        else restoreOrFitVisibleRange(zData.length, mtlrCandlePts.length);
      } else {
        scheduleEndSuppress();
      }
      return;
    }

    try {
      suppressRangeEvents = true;
      equalizePriceScales();
      try {
        if (zSeriesIsLine) {
          const linePts = zData.map((c) => (
            c && c.value != null
              ? { time: c.time, value: Number(c.value) }
              : { time: c.time, value: Number(c.close) }
          )).filter((p) => p.time != null && Number.isFinite(p.value));
          zSeries.setData(linePts);
        } else {
          try { zSeries.setData(zData); }
          catch {
            ensureZSeriesKind(true);
            zSeries.setData(zData.map((c) => ({ time: c.time, value: c.close })));
            zSeriesIsLine = true;
          }
        }
        if (spreadSeriesIsLine) {
          spreadSeries.setData(mtlrCandlePts.map((c) => ({
            time: c.time, value: c.close != null ? c.close : c.value,
          })));
        } else {
          try { spreadSeries.setData(mtlrCandlePts); }
          catch {
            ensureSpreadSeriesKind(true);
            spreadSeries.setData(mtlrCandlePts.map((c) => ({
              time: c.time, value: c.close,
            })));
          }
        }
      } catch (e) {
        suppressRangeEvents = false;
        publishChartDebug({
          zPts: zData.length,
          spPts: mtlrCandlePts.length,
          err: String(e && e.message || e),
          sync: { mode: 'time', zN: zData.length, mtlrN: mtlrCandlePts.length },
        });
        throw e;
      }
      updatePrimarySpreadBands(zData, spreadLevels);
      updateSpreadRegimeBands(mtlrCandlePts, mtlrLv);
      setPrimarySpreadThresholdLines(spreadLevels);
      setTpExitSpreadLine(openTrade, closeForecast, settings);
      setSpreadThresholdLines(mtlrLv);
      paintCorridorOnChart(corridor, zData);
      updateOpenTradeOnChart(openTrade, paintBars, closedTrades, {
        mtlrOpen, mtlrBars: mtlrPaint, mtlrClosed,
      });
      lastPaintZData = zData;
      lastPaintMtlrData = mtlrCandlePts;
      paintTradeOhlc(null);
      if (!userGestureActive) {
        restoreOrFitVisibleRange(zData.length, mtlrCandlePts.length);
      } else {
        scheduleEndSuppress();
      }
      forceSyncAfterPaint();
      publishChartDebug({
        zPts: zData.length,
        spPts: mtlrCandlePts.length,
        bodyN,
        primary: chartPrimarySpread ? 'spread' : 'z',
        bottom: 'mtlr_m15',
        sample: zData.length ? [zData[0], zData[zData.length - 1]] : null,
        sync: { mode: 'time', zN: zData.length, mtlrN: mtlrCandlePts.length },
      });
    } catch (e) {
      suppressRangeEvents = false;
      console.warn('trade chart', e);
    }
  }

  function syncTradeActionButtons(data) {
    const open = data && data.open;
    const flat = !open;
    const dealer = data && data.dealer;
    const dealer1m = String(data?.bars_mode || '') === 'dealer_1m'
      || String(data?.bars_mode || '') === 'dealer_weekend';
    const weekend = !!(data?.weekend_monitor || dealer1m || nowMskParts().weekend);
    const dealerPresent = !!(dealer && (dealer.ok || dealer.quotes_ok || dealer.manual_ok != null || dealer.error));
    // Weekend / OTC dealer: enable when quotes present (not only DEALER_NORMAL).
    // Weekday TQBR session: allow if flat. Close always when position open.
    const dealerQuotesOk = !!(dealer && (dealer.manual_ok || dealer.quotes_ok || (dealer.ok && dealer.tatn != null && dealer.tatnp != null)));
    const manualGate = (weekend || dealerPresent) ? dealerQuotesOk : true;
    const canOpen = flat && manualGate;
    const btnLong = $('tradeBtnLong');
    const btnShort = $('tradeBtnShort');
    const btnClose = $('tradeBtnClose');
    if (btnLong) {
      btnLong.disabled = !canOpen;
      btnLong.title = !flat
        ? 'Уже есть открытая позиция'
        : (!manualGate
          ? 'Ручной вход недоступен (нет дилерских котировок)'
          : 'Ручной вход Long (выходные/OTC или TQBR)');
    }
    if (btnShort) {
      btnShort.disabled = !canOpen;
      btnShort.title = !flat
        ? 'Уже есть открытая позиция'
        : (!manualGate
          ? 'Ручной вход недоступен (нет дилерских котировок)'
          : 'Ручной вход Short (выходные/OTC или TQBR)');
    }
    if (btnClose) {
      btnClose.disabled = flat;
      btnClose.title = flat
        ? 'Нет открытой позиции'
        : 'Закрыть открытый спрэд на брокере (OTC/TQBR)';
    }
  }

  /** Path Min/Max MTM entry→now (parity closed pnl_min/max; no exit commission). */
  function overnightDaysOpen(entryTime, barTime) {
    const a = String(entryTime || '').slice(0, 10);
    const b = String(barTime || '').slice(0, 10);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(a) || !/^\d{4}-\d{2}-\d{2}$/.test(b)) return 0;
    const ms = Date.parse(`${b}T00:00:00`) - Date.parse(`${a}T00:00:00`);
    if (!Number.isFinite(ms)) return 0;
    return Math.max(0, Math.round(ms / 86400000));
  }

  function parseTradeMsOpen(ts) {
    if (ts == null) return null;
    if (typeof ts === 'number' && Number.isFinite(ts)) return ts;
    const s = String(ts).trim().replace('T', ' ');
    // MSK wall-clock — same as replay-sim parseTradeMs (avoid browser TZ shift).
    const iso = s.length >= 16
      ? `${s.slice(0, 16).replace(' ', 'T')}:00+03:00`
      : `${s}+03:00`;
    const ms = new Date(iso).getTime();
    return Number.isNaN(ms) ? null : ms;
  }

  /** Compact wall-clock for Min/Max: «ДД.ММ ЧЧ:ММ». */
  function compactExtremeDt(label) {
    if (!label) return '';
    const s = String(label).trim().replace('T', ' ');
    // YYYY-MM-DD HH:MM…
    if (/^\d{4}-\d{2}-\d{2}/.test(s) && s.length >= 16) {
      return `${s.slice(8, 10)}.${s.slice(5, 7)} ${s.slice(11, 16)}`;
    }
    if (s.length >= 16) return s.slice(0, 16);
    return s;
  }

  function computeOpenPathMinMax(open, bars, settings) {
    const mark = (open && open.mark) || {};
    const fromApiMin = Number(mark.pnl_min_rub);
    const fromApiMax = Number(mark.pnl_max_rub);
    const apiMinT = mark.pnl_min_time || null;
    const apiMaxT = mark.pnl_max_time || null;
    if (
      Number.isFinite(fromApiMin) && Number.isFinite(fromApiMax)
      && apiMinT && apiMaxT
    ) {
      return { min: fromApiMin, max: fromApiMax, minTime: apiMinT, maxTime: apiMaxT };
    }
    if (!open || !Array.isArray(bars) || !bars.length) {
      if (Number.isFinite(fromApiMin) && Number.isFinite(fromApiMax)) {
        return { min: fromApiMin, max: fromApiMax, minTime: apiMinT, maxTime: apiMaxT };
      }
      return { min: null, max: null, minTime: null, maxTime: null };
    }
    const entrySp = Number(mark.fill_spread != null ? mark.fill_spread : open.entry_spread);
    const entryMs = parseTradeMsOpen(open.entry_time);
    if (!Number.isFinite(entrySp) || entryMs == null) {
      if (Number.isFinite(fromApiMin) && Number.isFinite(fromApiMax)) {
        return { min: fromApiMin, max: fromApiMax, minTime: apiMinT, maxTime: apiMaxT };
      }
      return { min: null, max: null, minTime: null, maxTime: null };
    }
    const direction = String(open.direction || '').toUpperCase();
    const isLong = direction.includes('LONG');
    const notional = Number(mark.notional_rub ?? open.execution_notional_rub);
    const lev = Number(settings?.leverage ?? 7);
    const L = Number.isFinite(lev) && lev > 0 ? lev : 7;
    const eff = Number.isFinite(notional) && notional > 0
      ? notional
      : (Number(settings?.entry_deposit_rub) || 10000) * L;
    const deposit = Number.isFinite(notional) && notional > 0 ? notional / L : (eff / L);
    const commPerSide = eff * (0.04 / 100);
    // T‑Invest Премиум: ступени на короткую ногу (не deposit×(L−1)×0.033%).
    const fillTn = Number(mark.fill_tatn ?? open.entry_tatn);
    const fillTp = Number(mark.fill_tatnp ?? open.entry_tatnp);
    const lots = Number(open.quantity_lots) || 0;
    let uncovered = 0;
    if (lots > 0 && Number.isFinite(fillTn) && Number.isFinite(fillTp) && fillTn > 0 && fillTp > 0) {
      uncovered = isLong ? lots * fillTp : lots * fillTn;
    } else if (Number.isFinite(notional) && notional > 0) {
      uncovered = notional / 2;
    }
    const overnightPerDay = (() => {
      const u = Math.max(0, uncovered);
      if (u <= 0) return 0;
      if (u <= 5000) return 0;
      if (u <= 50000) return 35;
      if (u <= 100000) return 70;
      if (u <= 250000) return 175;
      if (u <= 500000) return 340;
      if (u <= 1000000) return 680;
      if (u <= 2500000) return 1700;
      if (u <= 5000000) return 3400;
      if (u <= 10000000) return 6800;
      if (u <= 25000000) return u * 0.00066;
      if (u <= 50000000) return u * 0.00063;
      return u * 0.00055;
    })();
    let min = Infinity;
    let max = -Infinity;
    let minTime = null;
    let maxTime = null;
    let found = false;
    for (const b of bars) {
      if (!b) continue;
      const ms = b.timestampMs != null ? Number(b.timestampMs) : parseTradeMsOpen(b.time || b.tradeDate);
      if (!Number.isFinite(ms) || ms < entryMs) continue;
      const sp = Number(b.spreadPercent != null ? b.spreadPercent : b.spread);
      if (!Number.isFinite(sp)) continue;
      const barTime = b.tradeDate || b.time || '';
      const pnlPts = isLong ? (sp - entrySp) : (entrySp - sp);
      const gross = eff * (pnlPts / 100);
      const ovn = overnightPerDay * overnightDaysOpen(open.entry_time, barTime);
      const net = gross - commPerSide - ovn;
      found = true;
      if (net < min) {
        min = net;
        minTime = barTime || null;
      }
      if (net > max) {
        max = net;
        maxTime = barTime || null;
      }
    }
    if (!found) {
      if (Number.isFinite(fromApiMin) && Number.isFinite(fromApiMax)) {
        return { min: fromApiMin, max: fromApiMax, minTime: apiMinT, maxTime: apiMaxT };
      }
      return { min: null, max: null, minTime: null, maxTime: null };
    }
    // Prefer API rub magnitudes when present (parity), keep path times for UI.
    return {
      min: Number.isFinite(fromApiMin) ? fromApiMin : min,
      max: Number.isFinite(fromApiMax) ? fromApiMax : max,
      minTime: apiMinT || minTime,
      maxTime: apiMaxT || maxTime,
    };
  }

  function fmtOpenMinMax(rub, depositRub, atTime) {
    if (rub == null || !Number.isFinite(Number(rub))) return { text: '—', cls: '' };
    const v = Number(rub);
    const cls = v > 0 ? 'pnl-pos' : v < 0 ? 'pnl-neg' : '';
    let text = `≈ ${fmtRub(v)}`;
    const base = Number(depositRub);
    const pct = Number.isFinite(base) && base > 0 ? fmtStakePct((v / base) * 100) : '';
    const when = compactExtremeDt(atTime);
    if (pct && when) text += ` (${pct}, ${when})`;
    else if (pct) text += ` (${pct})`;
    else if (when) text += ` (${when})`;
    return { text, cls };
  }

  function fmtPnlWithDepositPct(rub, depositRub) {
    if (rub == null || !Number.isFinite(Number(rub))) return '—';
    const v = Number(rub);
    let text = fmtRub(v);
    const base = Number(depositRub);
    if (Number.isFinite(base) && base > 0) {
      text += ` (${fmtStakePct((v / base) * 100)})`;
    }
    return text;
  }

  function renderOpen(open, { barsMode = '', bars = null, settings = null } = {}) {
    const box = $('tradeOpenBox');
    if (!open) {
      box.innerHTML = '<div class="trade-open-empty">Нет открытой позиции</div>';
      clearProfitAlertBadge();
      return;
    }
    const m = open.mark || {};
    const pnlCls = (m.unrealized_pnl_rub || 0) >= 0 ? 'pnl-pos' : 'pnl-neg';
    const netCls = (m.net_approx_rub || 0) >= 0 ? 'pnl-pos' : 'pnl-neg';
    const spreadEntry = m.fill_spread != null ? m.fill_spread : open.entry_spread;
    const spreadLabel = m.pnl_source === 'broker_fills' ? 'Спред (fill→сейч)' : 'Спред';
    const pnlNote = m.pnl_source === 'broker_fills'
      ? 'по ценам Тинькофф'
      : (String(barsMode) === 'dealer_1m' ? 'по спреду дилер 1м' : 'по спреду ISS');
    const pathMm = computeOpenPathMinMax(open, bars || [], settings || {});
    const deposit = entryDepositRub(open, settings);
    const fundsNow = Number(lastGoodBroker?.total_rub);
    const atOpen = equityAtOpenRub(open, {
      fundsTotal: Number.isFinite(fundsNow) ? fundsNow : null,
    });
    const minLine = fmtOpenMinMax(pathMm.min, deposit, pathMm.minTime);
    const maxLine = fmtOpenMinMax(pathMm.max, deposit, pathMm.maxTime);
    const depLabel = Number.isFinite(deposit) && deposit > 0
      ? `от вложения ${fmt(deposit, 0)} ₽`
      : '% от депозита';
    const profitHit = isOpenProfitAlertHit(open, settings);
    const srcRaw = String(open.source || '');
    const srcUp = srcRaw.toUpperCase();
    const isManual = isManualTradeSource(open.source);
    const srcLabel = isManual
      ? (srcUp === 'BROKER' ? 'ручная (брокер)' : 'ручная')
      : (srcRaw || 'AUTO');
    const dirCls = isManual ? 'trade-open-dir trade-open-dir--manual' : 'trade-open-dir';
    const srcBadge = isManual
      ? ` <span class="trade-manual-badge">${escapeHtml(srcLabel)}</span>`
      : ` · ${escapeHtml(srcLabel)}`;
    const manualPlaque = isManual
      ? `<div class="trade-manual-plaque" role="status">MANUAL · не AUTO</div>`
      : '';
    const risk = sanitizeOpenRisk(m, settings);
    const riskCls = risk.red ? 'risk-red' : (risk.level === 'Elevated' ? 'risk-warn' : 'risk-ok');
    const entryComment = String(open.entry_comment || '').trim();
    const commentHtml = entryComment
      ? `<div class="trade-open-comment"><span class="trade-open-comment-label">Комментарий</span>${escapeHtml(entryComment)}</div>`
      : '';
    box.innerHTML =
      `<div class="${dirCls}">${escapeHtml(String(open.direction || ''))} · ${open.quantity_lots}+${open.quantity_lots} лот${srcBadge}</div>` +
      manualPlaque +
      commentHtml +
      (profitHit
        ? `<div class="trade-profit-alert" role="status">Прибыль ≥${PROFIT_ALERT_PCT}% ${depLabel}</div>`
        : '') +
      `<div class="trade-open-grid">` +
      `<span>Вход</span><b>${open.entry_time || '—'}</b>` +
      `<span>${spreadLabel}</span><b>${fmt(spreadEntry)}% → ${fmt(m.spread_now)}%</b>` +
      (m.entry_slip_pts != null
        ? `<span>Slip вх</span><b>${fmt(m.entry_slip_pts, 2)} п.п.</b>`
        : '') +
      (m.fill_tatn != null
        ? `<span>Fill TATN/P</span><b>${fmt(m.fill_tatn, 2)} / ${fmt(m.fill_tatnp, 2)}</b>`
        : '') +
      `<span>Notional</span><b>${fmt(m.notional_rub, 0)} ₽</b>` +
      `<span>Депозит</span><b>${fmt(deposit, 0)} ₽</b>` +
      (atOpen != null
        ? `<span title="Сумма на счету на входе (база Чист.)">До</span><b>${fmt(atOpen, 0)} ₽</b>`
        : '') +
      `<span>PnL ≈</span><b class="${pnlCls}">${fmtPnlWithDepositPct(m.unrealized_pnl_rub, deposit)}</b>` +
      `<span>Нетто ≈</span><b class="${netCls}">${fmtPnlWithDepositPct(m.net_approx_rub, deposit)}</b>` +
      `<span>Min</span><b class="${minLine.cls}">${minLine.text}</b>` +
      `<span>Max</span><b class="${maxLine.cls}">${maxLine.text}</b>` +
      `<span>Овн</span><b>${fmtRub(m.overnight_rub)} · ${m.overnight_days || 0}д</b>` +
      `<span>Hold</span><b>${m.hold_hours != null ? fmt(m.hold_hours, 1) + ' ч' : '—'}</b>` +
      `</div>` +
      `<div class="trade-open-pnl-src meta">${pnlNote} · % ${depLabel}</div>` +
      `<div class="trade-risk ${riskCls}">Риск ${risk.level || '—'} · score ${risk.score ?? '—'}` +
      (risk.flags && risk.flags.length ? ` · ${risk.flags.join(', ')}` : '') +
      `</div>` +
      `<div class="trade-open-stats-mini meta" id="tradeOpenStatsMini">ориентиры — см. блок под графиками</div>`;
    maybeFireProfitAlert(open, settings);
  }

  function fmtRubShort(n) {
    if (n == null || Number.isNaN(Number(n))) return '—';
    const v = Number(n);
    const sign = v > 0 ? '+' : '';
    if (Math.abs(v) >= 1000) return `${sign}${(v / 1000).toFixed(1)}k ₽`;
    return `${sign}${v.toFixed(0)} ₽`;
  }

  let openStatsTabBound = false;
  function bindOpenStatsTabs() {
    if (openStatsTabBound) return;
    const tabs = $('tradeOpenStatsTabs');
    if (!tabs) return;
    openStatsTabBound = true;
    tabs.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-tos-tab]');
      if (!btn) return;
      const id = btn.getAttribute('data-tos-tab');
      tabs.querySelectorAll('[data-tos-tab]').forEach((b) => {
        const on = b === btn;
        b.classList.toggle('active', on);
        b.setAttribute('aria-selected', on ? 'true' : 'false');
      });
      document.querySelectorAll('#tradeOpenStats [data-tos-pane]').forEach((pane) => {
        const on = pane.getAttribute('data-tos-pane') === id;
        pane.classList.toggle('active', on);
        if (on) pane.removeAttribute('hidden');
        else pane.setAttribute('hidden', '');
      });
      const body = $('tradeOpenStatsBody');
      if (body) body.scrollTop = 0;
    });
  }

  const MID_TAB_IDS = new Set(['check', 'params']);
  let midTabsBound = false;
  function setMidTab(id) {
    const tabId = MID_TAB_IDS.has(id) ? id : 'check';
    const tabs = $('tradeMidTabs');
    if (!tabs) return;
    tabs.querySelectorAll('[data-mid-tab]').forEach((b) => {
      const on = b.getAttribute('data-mid-tab') === tabId;
      b.classList.toggle('active', on);
      b.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    document.querySelectorAll('#tradeMidTabsPanel [data-mid-pane]').forEach((pane) => {
      const on = pane.getAttribute('data-mid-pane') === tabId;
      pane.classList.toggle('active', on);
      if (on) pane.removeAttribute('hidden');
      else pane.setAttribute('hidden', '');
    });
  }
  function bindMidTabs() {
    if (midTabsBound) return;
    const tabs = $('tradeMidTabs');
    if (!tabs) return;
    midTabsBound = true;
    let saved = 'check';
    try {
      const raw = localStorage.getItem(LS_MID_TAB) || 'check';
      if (MID_TAB_IDS.has(raw)) saved = raw;
    } catch (_) { /* */ }
    setMidTab(saved);
    tabs.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-mid-tab]');
      if (!btn || !tabs.contains(btn)) return;
      const id = btn.getAttribute('data-mid-tab');
      if (!MID_TAB_IDS.has(id)) return;
      setMidTab(id);
      try { localStorage.setItem(LS_MID_TAB, id); } catch (_) { /* */ }
    });
  }

  const SIDE_TAB_IDS = new Set(['account', 'situation']);
  let sideTabsBound = false;
  function setSideTab(id) {
    const tabId = SIDE_TAB_IDS.has(id) ? id : 'account';
    const tabs = $('tradeSideTabs');
    if (!tabs) return;
    tabs.querySelectorAll('[data-side-tab]').forEach((b) => {
      const on = b.getAttribute('data-side-tab') === tabId;
      b.classList.toggle('active', on);
      b.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    document.querySelectorAll('#tradeSidePanel [data-side-pane]').forEach((pane) => {
      const on = pane.getAttribute('data-side-pane') === tabId;
      pane.classList.toggle('active', on);
      if (on) pane.removeAttribute('hidden');
      else pane.setAttribute('hidden', '');
    });
  }
  function bindSideTabs() {
    if (sideTabsBound) return;
    const tabs = $('tradeSideTabs');
    if (!tabs) return;
    sideTabsBound = true;
    let saved = 'account';
    try {
      const raw = localStorage.getItem(LS_SIDE_TAB) || 'account';
      if (SIDE_TAB_IDS.has(raw)) saved = raw;
    } catch (_) { /* */ }
    setSideTab(saved);
    tabs.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-side-tab]');
      if (!btn || !tabs.contains(btn)) return;
      const id = btn.getAttribute('data-side-tab');
      if (!SIDE_TAB_IDS.has(id)) return;
      setSideTab(id);
      try { localStorage.setItem(LS_SIDE_TAB, id); } catch (_) { /* */ }
    });
  }

  let midPanelCollapseBound = false;
  function isMidPanelCollapsed() {
    return !!$('tradeView')?.classList.contains('mid-panel-collapsed');
  }
  function setMidPanelCollapsed(collapsed) {
    const desk = $('tradeView');
    const collapseBtn = $('btnCollapseMidPanel');
    const restoreBtn = $('btnRestoreMidPanel');
    if (!desk) return;
    desk.classList.toggle('mid-panel-collapsed', !!collapsed);
    if (collapseBtn) {
      collapseBtn.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
      collapseBtn.title = collapsed ? 'Показать панель' : 'Скрыть панель вправо';
    }
    if (restoreBtn) restoreBtn.hidden = !collapsed;
    try {
      if (collapsed) localStorage.setItem(LS_MID_PANEL_COLLAPSED, '1');
      else localStorage.removeItem(LS_MID_PANEL_COLLAPSED);
    } catch (_) { /* */ }
    resize();
    requestAnimationFrame(resize);
    setTimeout(resize, 120);
  }
  function bindMidPanelCollapse() {
    if (midPanelCollapseBound) return;
    const collapseBtn = $('btnCollapseMidPanel');
    const restoreBtn = $('btnRestoreMidPanel');
    if (!collapseBtn && !restoreBtn) return;
    midPanelCollapseBound = true;
    let saved = false;
    try { saved = localStorage.getItem(LS_MID_PANEL_COLLAPSED) === '1'; } catch (_) { /* */ }
    setMidPanelCollapsed(saved);
    collapseBtn?.addEventListener('click', () => setMidPanelCollapsed(true));
    restoreBtn?.addEventListener('click', () => setMidPanelCollapsed(false));
  }

  let openStatsCollapseBound = false;
  function isOpenStatsCollapsed() {
    return !!$('tradeOpenStats')?.classList.contains('is-collapsed');
  }
  function setOpenStatsCollapsed(collapsed) {
    const root = $('tradeOpenStats');
    if (!root) return;
    root.classList.toggle('is-collapsed', !!collapsed);
    const btn = $('btnCollapseTradeOpenStats');
    if (!btn) return;
    btn.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
    btn.textContent = collapsed ? '+' : '−';
    btn.title = collapsed ? 'Показать' : 'Скрыть';
  }
  function bindOpenStatsCollapse() {
    if (openStatsCollapseBound) return;
    openStatsCollapseBound = true;
    let saved = false;
    try { saved = localStorage.getItem(LS_OPEN_STATS_HIDDEN) === '1'; } catch (_) {}
    setOpenStatsCollapsed(saved);
    $('btnCollapseTradeOpenStats')?.addEventListener('click', () => {
      const next = !isOpenStatsCollapsed();
      setOpenStatsCollapsed(next);
      try { localStorage.setItem(LS_OPEN_STATS_HIDDEN, next ? '1' : ''); } catch (_) {}
    });
  }

  const TRADE_FS_PANES = {
    z: { paneId: 'tradeZPane', btnId: 'btnExpandTradeZ' },
    spread: { paneId: 'tradeSpreadPane', btnId: 'btnExpandTradeSpread' },
  };

  function isTradeChartFullscreen(which) {
    const cfg = TRADE_FS_PANES[which];
    return !!(cfg && $(cfg.paneId)?.classList.contains('is-fullscreen'));
  }

  function activeTradeChartFullscreen() {
    if (isTradeChartFullscreen('z')) return 'z';
    if (isTradeChartFullscreen('spread')) return 'spread';
    return null;
  }

  function syncTradeFsButton(which, on) {
    const cfg = TRADE_FS_PANES[which];
    const btn = cfg && $(cfg.btnId);
    if (!btn) return;
    btn.setAttribute('aria-pressed', on ? 'true' : 'false');
    btn.title = on ? 'Свернуть с экрана (Esc)' : 'На весь экран';
    btn.textContent = on ? '✕' : '⛶';
  }

  function scheduleTradeFsResize() {
    requestAnimationFrame(() => {
      resize();
      requestAnimationFrame(() => {
        resize();
        setTimeout(resize, 100);
      });
    });
  }

  function setTradeChartFullscreen(which, on) {
    const cfg = TRADE_FS_PANES[which];
    if (!cfg) return;
    const pane = $(cfg.paneId);
    if (!pane) return;
    const next = !!on;
    if (next) {
      const other = which === 'z' ? 'spread' : 'z';
      if (isTradeChartFullscreen(other)) setTradeChartFullscreen(other, false);
    }
    pane.classList.toggle('is-fullscreen', next);
    syncTradeFsButton(which, next);
    const anyFs = !!(isTradeChartFullscreen('z') || isTradeChartFullscreen('spread'));
    document.body.classList.toggle('trade-chart-fs-open', anyFs);
    scheduleTradeFsResize();
  }

  let tradeChartFsBound = false;
  function bindTradeChartFullscreen() {
    if (tradeChartFsBound) return;
    tradeChartFsBound = true;
    $('btnExpandTradeZ')?.addEventListener('click', () => {
      setTradeChartFullscreen('z', !isTradeChartFullscreen('z'));
    });
    $('btnExpandTradeSpread')?.addEventListener('click', () => {
      setTradeChartFullscreen('spread', !isTradeChartFullscreen('spread'));
    });
    document.addEventListener('keydown', (e) => {
      if (e.key !== 'Escape') return;
      const active = activeTradeChartFullscreen();
      if (!active) return;
      setTradeChartFullscreen(active, false);
    });
    if (typeof ResizeObserver !== 'undefined') {
      ['tradeZChart', 'tradeSpreadChart'].forEach((id) => {
        const el = $(id);
        if (!el) return;
        const ro = new ResizeObserver(() => {
          if (document.getElementById('app')?.dataset?.view !== 'trade') return;
          resize();
        });
        ro.observe(el);
      });
    }
  }

  /** Не перехватывать PgUp/PgDn/стрелки, пока фокус в поле ввода (депозит, параметры…). */
  function isTradeTypingTarget(el) {
    if (!el || el === document.body || el === document.documentElement) return false;
    const tag = (el.tagName || '').toLowerCase();
    if (tag === 'input' || tag === 'textarea' || tag === 'select') return true;
    if (el.isContentEditable) return true;
    return !!(el.closest && el.closest('input, textarea, select, [contenteditable="true"]'));
  }

  function getTradeLogicalRange() {
    try {
      const r = zChart?.timeScale()?.getVisibleLogicalRange();
      if (r && Number.isFinite(r.from) && Number.isFinite(r.to) && r.to > r.from) {
        return { from: r.from, to: r.to };
      }
    } catch (_) { /* ignore */ }
    if (pinnedRange && Number.isFinite(pinnedRange.from) && Number.isFinite(pinnedRange.to)
      && pinnedRange.to > pinnedRange.from) {
      return { from: pinnedRange.from, to: pinnedRange.to };
    }
    return null;
  }

  /**
   * Клавиатура Trade Desk: PgDn/PgUp — на экран вперёд/назад по времени;
   * → конец (свежие), ← начало (старые). Z и Спред через applyVisibleRange.
   * @param {'pageForward'|'pageBack'|'end'|'start'} action
   */
  function navigateTradeChartByKeyboard(action) {
    if (!zChart || lastBarCount <= 0) return false;
    const cur = getTradeLogicalRange();
    if (!cur) return false;
    const dataEnd = lastDataEnd != null ? lastDataEnd : dataEndIndex(lastBarCount);
    let span = Math.max(1, cur.to - cur.from);
    if (span > dataEnd) span = Math.max(1, dataEnd);

    let from;
    let to;
    if (action === 'pageForward') {
      from = cur.from + span;
      to = cur.to + span;
    } else if (action === 'pageBack') {
      from = cur.from - span;
      to = cur.to - span;
    } else if (action === 'end') {
      to = dataEnd;
      from = to - span;
    } else if (action === 'start') {
      from = 0;
      to = span;
    } else {
      return false;
    }

    if (from < 0) {
      from = 0;
      to = span;
    }
    if (to > dataEnd) {
      to = dataEnd;
      from = to - span;
      if (from < 0) from = 0;
    }
    if (!(to > from)) return false;

    markUserGesture();
    setPinnedRange({ from, to }, { fromUser: true });
    applyVisibleRange({ from, to });
    return true;
  }

  let tradeChartKeyNavBound = false;
  function bindTradeChartKeyboardNav() {
    if (tradeChartKeyNavBound) return;
    tradeChartKeyNavBound = true;
    const stack = $('tradeChartStack');
    if (stack && stack.tabIndex < 0) stack.tabIndex = 0;
    ['tradeZChart', 'tradeSpreadChart'].forEach((id) => {
      const el = $(id);
      if (el && el.tabIndex < 0) el.tabIndex = 0;
    });
    document.addEventListener('keydown', (e) => {
      if (document.getElementById('app')?.dataset?.view !== 'trade') return;
      if (e.ctrlKey || e.altKey || e.metaKey) return;
      if (isTradeTypingTarget(e.target)) return;
      let action = null;
      if (e.key === 'PageDown') action = 'pageForward';
      else if (e.key === 'PageUp') action = 'pageBack';
      else if (e.key === 'ArrowRight') action = 'end';
      else if (e.key === 'ArrowLeft') action = 'start';
      if (!action) return;
      if (!navigateTradeChartByKeyboard(action)) return;
      e.preventDefault();
    });
  }

  function renderOpenStats(stats, open) {
    bindOpenStatsTabs();
    const root = $('tradeOpenStats');
    const mini = $('tradeOpenStatsMini');
    if (!root) return;
    if (!open || !stats || !stats.ok) {
      root.classList.add('hidden');
      if (mini) mini.textContent = stats && stats.error
        ? `статистика: ${stats.error}`
        : '';
      return;
    }
    root.classList.remove('hidden');
    const s = stats.summary || {};
    const meta = $('tradeOpenStatsMeta');
    if (meta) {
      meta.textContent =
        `${stats.direction || ''} · ${s.trade_count || 0} сделок sim` +
        (stats.params?.slippage_spread_pts != null
          ? ` · slip ${stats.params.slippage_spread_pts}`
          : '');
    }
    const kpis = $('tradeOpenStatsKpis');
    if (kpis) {
      const wr = s.win_rate_pct != null ? `${fmt(s.win_rate_pct, 0)}%` : '—';
      kpis.innerHTML =
        `<div class="tos-kpi"><span>До плюса (мед.)</span><b>${s.median_hold_winners_label || '—'}</b></div>` +
        `<div class="tos-kpi"><span>Ср. PnL (все)</span><b class="${(s.avg_pnl_rub || 0) >= 0 ? 'pnl-pos' : 'pnl-neg'}">${fmtRubShort(s.avg_pnl_rub)}</b></div>` +
        `<div class="tos-kpi"><span>Ср. PnL (+)</span><b class="pnl-pos">${fmtRubShort(s.avg_win_rub)}</b></div>` +
        `<div class="tos-kpi"><span>Win rate</span><b>${wr}</b></div>` +
        `<div class="tos-kpi"><span>MAE мед.</span><b class="pnl-neg">${fmtRubShort(s.median_mae_rub)}</b></div>` +
        `<div class="tos-kpi"><span>P+ 1ч / 1д</span><b>${(() => {
          const pp = stats.p_profit || [];
          const h1 = pp.find((x) => x.label === '1ч');
          const d1 = pp.find((x) => x.label === '1д');
          const a = h1 && h1.pct_in_profit != null ? fmt(h1.pct_in_profit, 0) + '%' : '—';
          const b = d1 && d1.pct_in_profit != null ? fmt(d1.pct_in_profit, 0) + '%' : '—';
          return `${a} / ${b}`;
        })()}</b></div>`;
      kpis.classList.add('tos-kpis-6');
    }
    if (mini) {
      mini.textContent =
        `история: до плюса ${s.median_hold_winners_label || '—'} · ср. PnL ${fmtRubShort(s.avg_pnl_rub)} · WR ${s.win_rate_pct != null ? fmt(s.win_rate_pct, 0) + '%' : '—'}`;
    }

    const hbar = $('tradeStatsHbar');
    if (hbar) {
      const rows = stats.typical_mtm || [];
      const maxAbs = Math.max(
        0.01,
        ...rows.map((r) => Math.abs(Number(r.typical_pnl_rub) || 0)),
      );
      hbar.innerHTML = rows.map((r) => {
        const rub = Number(r.typical_pnl_rub);
        const w = Number.isFinite(rub) ? Math.min(100, (Math.abs(rub) / maxAbs) * 100) : 0;
        const pp = r.median_fav_pp != null ? fmt(r.median_fav_pp, 2) : '—';
        const pct = r.pct_in_profit != null ? ` · ${fmt(r.pct_in_profit, 0)}% в плюсе` : '';
        const cls = rub >= 0 ? 'pos' : 'neg';
        return (
          `<div class="tos-hrow">` +
          `<span class="tos-hlab">${r.label}</span>` +
          `<div class="tos-htrack"><div class="tos-hfill ${cls}" style="width:${w}%"></div></div>` +
          `<span class="tos-hval">~${fmtRubShort(rub)} · ${pp} п.п.${pct}</span>` +
          `</div>`
        );
      }).join('');
    }

    const vbar = $('tradeStatsVbar');
    if (vbar) {
      const rows = stats.spread_move || [];
      const maxY = Math.max(
        0.05,
        ...rows.flatMap((r) => [Number(r.median_abs_pp) || 0, Number(r.p90_abs_pp) || 0]),
      );
      vbar.innerHTML =
        `<div class="tos-vgrid">` +
        rows.map((r) => {
          const med = Number(r.median_abs_pp) || 0;
          const p90 = Number(r.p90_abs_pp) || 0;
          const hm = Math.max(4, (med / maxY) * 100);
          const hp = Math.max(4, (p90 / maxY) * 100);
          return (
            `<div class="tos-vcol" title="n=${r.n || 0}">` +
            `<div class="tos-vpair">` +
            `<div class="tos-vbar-med" style="height:${hm}%"></div>` +
            `<div class="tos-vbar-p90" style="height:${hp}%"></div>` +
            `</div>` +
            `<span class="tos-vlab">${r.label}</span>` +
            `</div>`
          );
        }).join('') +
        `</div>`;
    }

    const hint = $('tradeOpenStatsHint');
    if (hint) hint.textContent = stats.hint || '';

    const ppBox = $('tradeStatsPProfit');
    if (ppBox) {
      const rows = stats.p_profit || [];
      const maxP = Math.max(1, ...rows.map((r) => Number(r.pct_in_profit) || 0));
      ppBox.innerHTML =
        `<div class="tos-vgrid">` +
        rows.map((r) => {
          const p = Number(r.pct_in_profit) || 0;
          const h = Math.max(4, (p / maxP) * 100);
          const hi = r.label === '1ч' || r.label === '1д' ? ' is-hi' : '';
          return (
            `<div class="tos-vcol${hi}" title="n=${r.n || 0}">` +
            `<div class="tos-vpair tos-vpair-single">` +
            `<div class="tos-vbar-med" style="height:${h}%"></div>` +
            `</div>` +
            `<span class="tos-vlab">${r.label}</span>` +
            `<span class="tos-vpct">${p ? fmt(p, 0) + '%' : '—'}</span>` +
            `</div>`
          );
        }).join('') +
        `</div>`;
    }

    const maeBox = $('tradeStatsMae');
    if (maeBox) {
      const mae = stats.mae || {};
      maeBox.innerHTML =
        `<div class="tos-kpi"><span>Медиана Min</span><b class="pnl-neg">${fmtRubShort(mae.median_rub)}</b></div>` +
        `<div class="tos-kpi"><span>Редко p10</span><b class="pnl-neg">${fmtRubShort(mae.p10_rub)}</b></div>` +
        `<div class="tos-kpi"><span>Среднее Min</span><b class="pnl-neg">${fmtRubShort(mae.mean_rub)}</b></div>` +
        `<div class="tos-kpi"><span>n</span><b>${mae.n != null ? mae.n : '—'}</b></div>`;
    }

    const ovnBox = $('tradeStatsOvn');
    if (ovnBox) {
      const rows = stats.overnight_share || [];
      ovnBox.innerHTML = rows.map((r) => {
        const p = Number(r.median_overnight_share_pct);
        const w = Number.isFinite(p) ? Math.min(100, Math.max(0, p)) : 0;
        return (
          `<div class="tos-hrow">` +
          `<span class="tos-hlab" title="${r.label}">${r.label}</span>` +
          `<div class="tos-htrack"><div class="tos-hfill" style="width:${w}%"></div></div>` +
          `<span class="tos-hval">${Number.isFinite(p) ? fmt(p, 0) + '%' : '—'} · n=${r.n || 0}</span>` +
          `</div>`
        );
      }).join('');
    }

    const slipBox = $('tradeStatsSlip');
    if (slipBox) {
      const rows = stats.slip_sensitivity || [];
      slipBox.innerHTML =
        `<div class="tos-slip-head"><span>Slip</span><span>WR</span><span>Ср.PnL</span><span>До+</span></div>` +
        rows.map((r) => (
          `<div class="tos-slip-row">` +
          `<span>${fmt(r.slip, 2)}</span>` +
          `<span>${r.win_rate_pct != null ? fmt(r.win_rate_pct, 0) + '%' : '—'}</span>` +
          `<span class="${(r.avg_pnl_rub || 0) >= 0 ? 'pnl-pos' : 'pnl-neg'}">${fmtRubShort(r.avg_pnl_rub)}</span>` +
          `<span>${r.median_hold_winners_label || '—'}</span>` +
          `</div>`
        )).join('');
    }

    function paintHit(boxId, metaId, hit) {
      const box = $(boxId);
      const meta = $(metaId);
      if (!box || !hit) return;
      const buckets = hit.buckets || [];
      const maxC = Math.max(1, ...buckets.map((b) => Number(b.count) || 0));
      box.innerHTML = buckets.map((b) => {
        const c = Number(b.count) || 0;
        const w = (c / maxC) * 100;
        return (
          `<div class="tos-hrow">` +
          `<span class="tos-hlab" title="${b.label}">${b.label}</span>` +
          `<div class="tos-htrack"><div class="tos-hfill" style="width:${w}%"></div></div>` +
          `<span class="tos-hval">${c} · ${b.pct != null ? fmt(b.pct, 0) + '%' : '—'}</span>` +
          `</div>`
        );
      }).join('');
      if (meta) {
        meta.textContent =
          `hit ${hit.hit_count || 0}/${hit.n || 0}` +
          (hit.hit_rate_pct != null ? ` (${fmt(hit.hit_rate_pct, 0)}%)` : '') +
          ` · медиана ${hit.median_label || '—'}` +
          (hit.miss_count ? ` · без hit ${hit.miss_count}` : '');
      }
    }
    paintHit('tradeStatsHit1', 'tradeStatsHit1Meta', stats.hit1);
    paintHit('tradeStatsHit2', 'tradeStatsHit2Meta', stats.hit2);
  }

  function modeLabel(mode) {
    return mode === 'prod' ? 'Боевой (Prod)' : 'Песочница';
  }

  function brokerHasTotals(broker) {
    if (!broker || broker.error) return false;
    return Number.isFinite(Number(broker.total_rub))
      || Number.isFinite(Number(broker.cash_rub));
  }

  /**
   * Lite/full race: lite may omit broker while full previously painted funds.
   * Keep last good unless full confirms logout/no-token, or N empty polls, or
   * a persistent broker.error (after streak). Mutates data.broker in place.
   */
  function coalesceDeskBroker(data) {
    if (!data || typeof data !== 'object') return data;
    const incoming = data.broker;
    const lite = !!data.lite;

    if (brokerHasTotals(incoming)) {
      lastGoodBroker = {
        mode: incoming.mode,
        total_rub: incoming.total_rub,
        cash_rub: incoming.cash_rub,
        margin: incoming.margin ?? lastGoodBroker?.margin ?? null,
      };
      brokerEmptyStreak = 0;
      return data;
    }

    const missing = incoming == null;
    const isErr = !!(incoming && incoming.error);

    // Full desk with no broker object ⇒ logout / нет токена — clear.
    // Skip partial/timeout stubs — they omit broker without meaning logout.
    if (!lite && missing && !data.partial) {
      lastGoodBroker = null;
      brokerEmptyStreak = BROKER_EMPTY_CLEAR_AFTER;
      return data;
    }

    brokerEmptyStreak += 1;

    if (lastGoodBroker && brokerEmptyStreak < BROKER_EMPTY_CLEAR_AFTER) {
      // Keep painted totals; do not flash «…» on lite cold / brief error.
      data.broker = lastGoodBroker;
      data.broker_from_last_good = true;
      return data;
    }

    if (isErr && brokerEmptyStreak >= BROKER_EMPTY_CLEAR_AFTER) {
      lastGoodBroker = null;
      return data;
    }

    if (missing && brokerEmptyStreak >= BROKER_EMPTY_CLEAR_AFTER) {
      lastGoodBroker = null;
    }
    return data;
  }

  /** Short RU hint instead of urllib3/SSL traceback spam. */
  function formatBrokerError(err) {
    const s = String(err || '');
    if (/SSL:\s*цепочка/i.test(s)) return s.length > 220 ? `${s.slice(0, 217)}…` : s;
    if (/certificate verify failed|SSLCertVerification|self-signed certificate|CERTIFICATE_VERIFY_FAILED|Max retries exceeded.*tinkoff|Max retries exceeded.*tbank|Russian Trusted/i.test(s)) {
      return 'SSL: цепочка сертификатов (Russian Trusted CA / антивирус) — см. live/certs или MOEX_SSL_VERIFY=0';
    }
    return s.length > 220 ? `${s.slice(0, 217)}…` : s;
  }

  function marginHeadroomFromBroker(broker) {
    const m = broker?.margin;
    if (!m || typeof m !== 'object') return null;
    if (m.headroom && typeof m.headroom === 'object') return m.headroom;
    const liquid = Number(m.liquid_portfolio_rub);
    const corrected = Number(m.corrected_margin_rub);
    if (!Number.isFinite(liquid) || !Number.isFinite(corrected) || corrected <= 0) return null;
    const free = liquid - corrected;
    const pct = (free / corrected) * 100;
    let zone = 'red';
    if (pct > 30) zone = 'green';
    else if (pct >= 10) zone = 'yellow';
    return { free_rub: free, pct, zone };
  }

  function renderMarginHeadroom(broker) {
    const box = $('tradeMarginHeadroom');
    const fill = $('tradeMarginHeadroomFill');
    const text = $('tradeMarginHeadroomText');
    if (!box || !fill || !text) return;
    const hr = broker && !broker.error ? marginHeadroomFromBroker(broker) : null;
    if (!hr) {
      box.hidden = true;
      box.classList.remove(
        'trade-margin-headroom--green',
        'trade-margin-headroom--yellow',
        'trade-margin-headroom--red',
      );
      fill.style.width = '0';
      text.textContent = '—';
      return;
    }
    box.hidden = false;
    box.classList.remove(
      'trade-margin-headroom--green',
      'trade-margin-headroom--yellow',
      'trade-margin-headroom--red',
    );
    box.classList.add(`trade-margin-headroom--${hr.zone}`);
    const pctClamped = Math.min(100, Math.max(0, Number(hr.pct)));
    fill.style.width = `${pctClamped}%`;
    const freeAbs = Math.abs(Number(hr.free_rub));
    const freePrefix = Number(hr.free_rub) < 0 ? '−' : '';
    const pctStr = Number.isFinite(Number(hr.pct)) ? Number(hr.pct).toFixed(1) : '—';
    text.textContent = `до колла: ${freePrefix}${fmt(freeAbs, 0)} ₽ (${pctStr}%)`;
  }

  function renderFunds(broker, { pending = false } = {}) {
    const box = $('tradeFundsBox');
    const totalEl = $('tradeFundsTotal');
    const cashEl = $('tradeFundsCash');
    const brokerEl = $('tradeBrokerBox');
    if (!box || !totalEl || !cashEl) return;

    box.classList.remove('is-prod', 'is-error');
    if (brokerEl) {
      brokerEl.textContent = '';
      brokerEl.hidden = true;
    }
    if (!broker) {
      // Prefer last good over pending flash when coalesce kept nothing usable.
      if (pending && lastGoodBroker && brokerHasTotals(lastGoodBroker)) {
        broker = lastGoodBroker;
      } else {
        totalEl.textContent = pending ? '…' : '—';
        cashEl.textContent = pending ? 'брокер…' : 'нет токена — вкладка «Счёт»';
        renderMarginHeadroom(null);
        return;
      }
    }
    if (broker.error) {
      box.classList.add('is-error');
      totalEl.textContent = '—';
      cashEl.textContent = modeLabel(broker.mode);
      if (brokerEl) {
        brokerEl.hidden = false;
        brokerEl.textContent = `Брокер: ${formatBrokerError(broker.error)}`;
      }
      renderMarginHeadroom(null);
      return;
    }
    if (broker.mode === 'prod') box.classList.add('is-prod');
    const mode = modeLabel(broker.mode);
    const total = Number(broker.total_rub);
    const cash = Number(broker.cash_rub);
    totalEl.textContent = Number.isFinite(total) ? `${fmt(total, 0)} ₽` : '—';
    // Не дублировать ту же сумму в cash, если она ≈ total
    if (Number.isFinite(cash) && Number.isFinite(total) && Math.abs(cash - total) > 1) {
      cashEl.textContent = `${mode} · cash ${fmt(cash, 0)} ₽`;
    } else {
      cashEl.textContent = mode;
    }
    renderMarginHeadroom(broker);
  }

  function renderFundsAtOpen(open, fundsTotal) {
    const el = $('tradeFundsAtOpen');
    if (!el) return;
    if (!open) {
      el.hidden = true;
      el.textContent = '';
      return;
    }
    const atOpen = equityAtOpenRub(open, { fundsTotal });
    if (atOpen == null) {
      el.hidden = true;
      el.textContent = '';
      return;
    }
    el.hidden = false;
    el.textContent = `До ${fmt(atOpen, 0)} ₽`;
    el.title = 'Сумма на счету на входе (база Чист.)';
  }

  function fmtRubPlain(n, digits = 0) {
    if (n == null || !Number.isFinite(Number(n))) return '—';
    return `${fmt(Number(n), digits)} ₽`;
  }

  /**
   * % of entry deposit (вложение), not account equity and not deposit×leverage.
   * 1 decimal for |pct|≥1 (desk style); 2 decimals for tiny close-Δ.
   */
  function fmtStakePct(pct) {
    if (!Number.isFinite(pct)) return '';
    const abs = Math.abs(pct);
    const digits = abs >= 1 ? 1 : 2;
    return `${pct >= 0 ? '+' : '−'}${abs.toFixed(digits)}%`;
  }

  /** Entry deposit ₽ (settings / UI), never notional. */
  function entryDepositRub(open, settings) {
    const fromOpen = Number(open?.entry_deposit_rub);
    if (Number.isFinite(fromOpen) && fromOpen > 0) return fromOpen;
    const dep = Number(
      settings?.entry_deposit_rub
      ?? readEntryDeposit()
      ?? 10000,
    );
    return Number.isFinite(dep) && dep > 0 ? dep : 10000;
  }

  /** @deprecated kept for any external callers — prefer entryDepositRub for %. */
  function stakeNotionalRub(open, settings) {
    return entryDepositRub(open, settings);
  }

  function openProfitRub(open) {
    const mark = (open && open.mark) || {};
    const mtm = Number(mark.unrealized_pnl_rub);
    if (Number.isFinite(mtm)) return mtm;
    const net = Number(mark.net_approx_rub);
    return Number.isFinite(net) ? net : null;
  }

  function isOpenProfitAlertHit(open, settings) {
    if (!open) return false;
    const deposit = entryDepositRub(open, settings);
    const profit = openProfitRub(open);
    if (!(deposit > 0) || profit == null) return false;
    return profit >= deposit * (PROFIT_ALERT_PCT / 100);
  }

  function clearProfitAlertBadge() {
    const toast = $('tradeProfitToast');
    if (toast) {
      toast.hidden = true;
      toast.textContent = '';
    }
  }

  let profitToastTimer = null;
  function maybeFireProfitAlert(open, settings) {
    if (!isOpenProfitAlertHit(open, settings)) return;
    const tid = String(open.id ?? open.entry_time ?? '');
    if (!tid) return;
    let prev = '';
    try { prev = localStorage.getItem(LS_PROFIT_ALERT_TRADE) || ''; } catch (_) {}
    if (prev === tid) return;
    try { localStorage.setItem(LS_PROFIT_ALERT_TRADE, tid); } catch (_) {}
    const deposit = entryDepositRub(open, settings);
    const profit = openProfitRub(open);
    const pct = deposit > 0 && profit != null ? (profit / deposit) * 100 : PROFIT_ALERT_PCT;
    const msg =
      `Прибыль ≥${PROFIT_ALERT_PCT}% от вложения ${fmt(deposit, 0)} ₽` +
      ` · сейчас ${fmtRub(profit)} (${fmtStakePct(pct)})`;
    let toast = $('tradeProfitToast');
    if (!toast) {
      toast = document.createElement('div');
      toast.id = 'tradeProfitToast';
      toast.className = 'trade-profit-toast';
      toast.setAttribute('role', 'status');
      const host = $('tradeSideAccountPane') || $('tradeSidePanel') || $('tradeOpenBox') || document.body;
      host.prepend(toast);
    }
    toast.hidden = false;
    toast.textContent = msg;
    if (profitToastTimer) clearTimeout(profitToastTimer);
    profitToastTimer = setTimeout(() => {
      if (toast && toast.textContent === msg) toast.hidden = true;
    }, 12000);
  }

  /** Last good close forecast — lite polls must not wipe full-desk number. */
  let lastCloseForecast = null;

  /**
   * Equity / funds right after this trade opened («До» / Δ vs forecast).
   * Not the % base — percent-of-investment uses entry deposit.
   * Prefer last entry-leg portfolio_total; else API field; else current − mark PnL.
   */
  function equityAtOpenRub(open, {
    fundsTotal = null,
    forecastEquityOpen = null,
  } = {}) {
    const fromApi = Number(
      forecastEquityOpen
      ?? open?.equity_at_open_rub
      ?? open?.account_after_rub,
    );
    if (Number.isFinite(fromApi) && fromApi > 0) return fromApi;

    let legs = open?.legs ?? open?.legs_json;
    if (typeof legs === 'string') {
      try { legs = JSON.parse(legs); } catch (_) { legs = null; }
    }
    if (Array.isArray(legs)) {
      for (let i = legs.length - 1; i >= 0; i -= 1) {
        const leg = legs[i];
        if (!leg || typeof leg !== 'object') continue;
        const total = Number(leg.portfolio_total_rub);
        if (Number.isFinite(total) && total > 0) return total;
        const cash = Number(leg.portfolio_cash_rub);
        if (Number.isFinite(cash) && cash > 0) return cash;
      }
    }

    const now = Number(fundsTotal);
    const mtm = openProfitRub(open);
    if (Number.isFinite(now) && Number.isFinite(mtm)) {
      const derived = now - mtm;
      if (Number.isFinite(derived) && derived > 0) return derived;
    }
    return null;
  }

  /**
   * Потенциал при выходе на L вых / S вых (спред-% уровни).
   * notional×ΔS/100 − комиссия выхода − overnight (как в прогнозе закрытия).
   */
  function exitLevelPotential(fc, open, {
    depositRub = null,
    settings = null,
  } = {}) {
    if (fc && fc.exit_level_pnl_rub != null && Number.isFinite(Number(fc.exit_level_pnl_rub))
        && fc.exit_level_spread != null && Number.isFinite(Number(fc.exit_level_spread))) {
      const pnl = Number(fc.exit_level_pnl_rub);
      const spread = Number(fc.exit_level_spread);
      const dep = Number(fc.exit_level_deposit_rub);
      const deposit = (Number.isFinite(dep) && dep > 0)
        ? dep
        : (Number.isFinite(Number(depositRub)) && Number(depositRub) > 0
          ? Number(depositRub)
          : entryDepositRub(open, settings));
      return { pnl, spread, deposit };
    }

    const dir = String(open?.direction || '').toUpperCase();
    const mark = open?.mark && typeof open.mark === 'object' ? open.mark : {};
    const entrySp = Number(mark.fill_spread != null ? mark.fill_spread : open?.entry_spread);
    if (!Number.isFinite(entrySp)) return null;

    const lv = settings?.spread_exit_narrow != null || settings?.spread_exit_wide != null
      ? {
        exitNarrow: Number(settings?.spread_exit_narrow ?? 4.0),
        exitWide: Number(settings?.spread_exit_wide ?? 5.8),
      }
      : {
        exitNarrow: 4.0,
        exitWide: 5.8,
      };
    let exitSp = null;
    if (dir.startsWith('L')) exitSp = lv.exitNarrow;
    else if (dir.startsWith('S')) exitSp = lv.exitWide;
    if (exitSp == null || !Number.isFinite(exitSp)) return null;

    const notional = Number(
      mark.notional_rub
      ?? open?.execution_notional_rub
      ?? open?.notional_rub
      ?? 0,
    );
    const deposit = (() => {
      const d = Number(depositRub);
      if (Number.isFinite(d) && d > 0) return d;
      return entryDepositRub(open, settings);
    })();
    const lev = Number(settings?.leverage ?? open?.leverage ?? 7) || 7;
    const eff = (Number.isFinite(notional) && notional > 0) ? notional : deposit * lev;
    if (!(eff > 0)) return null;

    const pnlPts = dir.startsWith('L') ? (exitSp - entrySp) : (entrySp - exitSp);
    const gross = eff * (pnlPts / 100);
    const exitComm = (fc && fc.exit_commission_rub != null && Number.isFinite(Number(fc.exit_commission_rub)))
      ? Number(fc.exit_commission_rub)
      : eff * 0.0004;
    const ovn = (fc && fc.overnight_rub != null && Number.isFinite(Number(fc.overnight_rub)))
      ? Number(fc.overnight_rub)
      : (Number(mark.overnight_rub) || 0);
    return { pnl: gross - exitComm - ovn, spread: exitSp, deposit };
  }

  /** Премиум ₽/день по короткой ноге (как mark / overnight_fee). */
  function premiumOvernightPerDayRub(open, fc = null) {
    const fromFc = Number(fc?.overnight_per_day_rub);
    if (Number.isFinite(fromFc) && fromFc >= 0) return fromFc;
    const mark = open?.mark && typeof open.mark === 'object' ? open.mark : {};
    const fromMark = Number(mark.overnight_per_day_rub);
    if (Number.isFinite(fromMark) && fromMark >= 0) return fromMark;
    const days = Number(mark.overnight_days ?? fc?.overnight_days);
    const total = Number(mark.overnight_rub ?? fc?.overnight_rub);
    if (Number.isFinite(days) && days > 0 && Number.isFinite(total) && total >= 0) {
      return total / days;
    }
    const dir = String(open?.direction || '').toUpperCase();
    const fillTn = Number(mark.fill_tatn ?? open?.entry_tatn);
    const fillTp = Number(mark.fill_tatnp ?? open?.entry_tatnp);
    const lots = Number(open?.quantity_lots) || 0;
    const notional = Number(
      mark.notional_rub
      ?? open?.execution_notional_rub
      ?? open?.notional_rub
      ?? 0,
    );
    let uncovered = 0;
    if (lots > 0 && Number.isFinite(fillTn) && Number.isFinite(fillTp) && fillTn > 0 && fillTp > 0) {
      uncovered = dir.startsWith('L') ? lots * fillTp : lots * fillTn;
    } else if (Number.isFinite(notional) && notional > 0) {
      uncovered = notional / 2;
    }
    const u = Math.max(0, uncovered);
    if (u <= 0) return 0;
    if (u <= 5000) return 0;
    if (u <= 50000) return 35;
    if (u <= 100000) return 70;
    if (u <= 250000) return 175;
    if (u <= 500000) return 340;
    if (u <= 1000000) return 680;
    if (u <= 2500000) return 1700;
    if (u <= 5000000) return 3400;
    if (u <= 10000000) return 6800;
    if (u <= 25000000) return u * 0.00066;
    if (u <= 50000000) return u * 0.00063;
    return u * 0.00055;
  }

  /**
   * Сколько overnight-суток съедят подушку «при выходе ≈ +X».
   * days_to_red = ceil(X / ₽/д) — после стольких полуночей плюс при выходе ≤ 0.
   * floor(X / ₽/д) = ещё полных дней с остатком > 0 (если не кратно).
   */
  function exitOvernightCushionLine(cushionRub, perDayRub) {
    const perDay = Number(perDayRub);
    const cushion = Number(cushionRub);
    if (!Number.isFinite(perDay) || perDay < 0) return null;
    if (!Number.isFinite(cushion)) return null;
    const rateTxt = Number(perDay).toLocaleString('ru-RU', {
      maximumFractionDigits: perDay % 1 === 0 ? 0 : 1,
    });
    if (cushion <= 0) {
      return {
        text: 'овернайт: уже в минусе по модели',
        cls: 'pnl-neg',
        days: 0,
        perDay,
      };
    }
    if (perDay <= 0) {
      return {
        text: 'овернайт 0 ₽/д — подушка при выходе не съедается',
        cls: '',
        days: null,
        perDay: 0,
      };
    }
    const daysToRed = Math.ceil(cushion / perDay);
    const redOn = new Date();
    redOn.setHours(0, 0, 0, 0);
    redOn.setDate(redOn.getDate() + Math.max(1, daysToRed));
    const redDateTxt = `${String(redOn.getDate()).padStart(2, '0')}.${String(redOn.getMonth() + 1).padStart(2, '0')}`;
    const text = daysToRed <= 0
      ? `овернайт → минус через < 1 дн. (${redDateTxt}, ${rateTxt} ₽/д)`
      : `овернайт → минус через ≈ ${daysToRed} дн. (${redDateTxt}, ${rateTxt} ₽/д)`;
    return { text, cls: '', days: daysToRed, perDay, redDate: redDateTxt };
  }

  function renderCloseForecast(fc, {
    hasOpen = false,
    fundsTotal = null,
    open = null,
    depositRub = null,
    stakeNotional = null,
    settings = null,
  } = {}) {
    const root = $('tradeCloseForecast');
    const main = $('tradeCloseForecastMain');
    const deltaEl = $('tradeCloseForecastDelta');
    const exitEl = $('tradeCloseForecastExit');
    const ovnEl = $('tradeCloseForecastOvn');
    const sub = $('tradeCloseForecastSub');
    if (!root || !main || !sub) return;

    const clearDelta = () => {
      if (!deltaEl) return;
      deltaEl.hidden = true;
      deltaEl.textContent = '';
      deltaEl.className = 'account-funds-forecast-delta';
    };
    const clearExit = () => {
      if (!exitEl) return;
      exitEl.hidden = true;
      exitEl.textContent = '';
      exitEl.className = 'account-funds-forecast-exit';
    };
    const clearOvn = () => {
      if (!ovnEl) return;
      ovnEl.hidden = true;
      ovnEl.textContent = '';
      ovnEl.className = 'account-funds-forecast-ovn';
    };

    if (!hasOpen) {
      lastCloseForecast = null;
      root.hidden = true;
      main.textContent = 'Прогноз после закрытия ≈ —';
      clearDelta();
      clearExit();
      clearOvn();
      sub.textContent = '';
      return;
    }

    const incomingOk = fc
      && fc.forecast_total_rub != null
      && Number.isFinite(Number(fc.forecast_total_rub));
    if (incomingOk) {
      lastCloseForecast = fc;
    } else if (lastCloseForecast && lastCloseForecast.forecast_total_rub != null) {
      fc = lastCloseForecast;
    }

    root.hidden = false;
    const total = fc && fc.forecast_total_rub != null ? Number(fc.forecast_total_rub) : null;
    // Same total as «СРЕДСТВА НА СЧЁТЕ» (broker.total_rub), not cash / float equity alone.
    let equityNow = null;
    if (fundsTotal != null && Number.isFinite(Number(fundsTotal))) {
      equityNow = Number(fundsTotal);
    } else if (fc && fc.equity_now_rub != null && Number.isFinite(Number(fc.equity_now_rub))) {
      equityNow = Number(fc.equity_now_rub);
    }
    // Round first so on-screen lines and «ожид.» share the same integers.
    const forecastRub = (total != null && Number.isFinite(total)) ? Math.round(total) : null;
    const accountNowRub = (equityNow != null && Number.isFinite(equityNow))
      ? Math.round(equityNow)
      : null;
    const equityOpen = equityAtOpenRub(open, {
      fundsTotal: equityNow,
      forecastEquityOpen: fc?.equity_at_open_rub,
    });
    const equityOpenRub = (equityOpen != null && Number.isFinite(equityOpen) && equityOpen > 0)
      ? Math.round(equityOpen)
      : null;
    if (forecastRub != null) {
      main.textContent = `Прогноз после закрытия ≈ ${fmt(forecastRub, 0)} ₽`;
    } else {
      main.textContent = 'Прогноз после закрытия ≈ —';
    }

    // Primary Δ = round(прогноз) − round(средства на открытии) — account change.
    // % «от вложения» must use entry deposit (params / open.entry_deposit_rub), not «До».
    const depositBase = (() => {
      const d = Number(depositRub);
      if (Number.isFinite(d) && d > 0) return Math.round(d);
      const fallback = entryDepositRub(open, null);
      return Number.isFinite(fallback) && fallback > 0 ? Math.round(fallback) : null;
    })();
    let primaryDelta = null;
    let pctBase = depositBase;
    let usedMarkFallback = false;
    if (forecastRub != null && equityOpenRub != null) {
      primaryDelta = forecastRub - equityOpenRub;
    } else {
      const markNet = openProfitRub(open);
      if (markNet != null && Number.isFinite(markNet)) {
        primaryDelta = Math.round(markNet);
        usedMarkFallback = true;
      }
    }
    if (deltaEl) {
      if (primaryDelta != null && Number.isFinite(primaryDelta)) {
        const cls = primaryDelta > 0 ? 'pnl-pos' : primaryDelta < 0 ? 'pnl-neg' : '';
        deltaEl.hidden = false;
        deltaEl.className = `account-funds-forecast-delta${cls ? ` ${cls}` : ''}`;
        let text = `ожид. ≈ ${fmtRub(primaryDelta)}`;
        if (Number.isFinite(pctBase) && pctBase > 0) {
          text += ` (${fmtStakePct((primaryDelta / pctBase) * 100)} от вложения ${fmt(pctBase, 0)} ₽)`;
        } else if (usedMarkFallback) {
          text += ' (по MTM к открытию)';
        }
        deltaEl.textContent = text;
      } else {
        clearDelta();
      }
    }

    let exitPot = null;
    if (exitEl) {
      const pot = exitLevelPotential(fc, open, { depositRub, settings });
      if (pot && Number.isFinite(pot.pnl) && Number.isFinite(pot.spread)) {
        exitPot = pot;
        const pnlRound = Math.round(pot.pnl);
        const cls = pnlRound > 0 ? 'pnl-pos' : pnlRound < 0 ? 'pnl-neg' : '';
        exitEl.hidden = false;
        exitEl.className = `account-funds-forecast-exit${cls ? ` ${cls}` : ''}`;
        const spTxt = Number(pot.spread).toLocaleString('ru-RU', {
          minimumFractionDigits: 1,
          maximumFractionDigits: 1,
        });
        let text = `при выходе S ${spTxt}% ≈ ${fmtRub(pnlRound)}`;
        const dep = Math.round(Number(pot.deposit));
        if (Number.isFinite(dep) && dep > 0) {
          text += ` (${fmtStakePct((pnlRound / dep) * 100)} от вложения ${fmt(dep, 0)} ₽)`;
        }
        exitEl.textContent = text;
      } else {
        clearExit();
      }
    }

    if (ovnEl) {
      if (exitPot && Number.isFinite(exitPot.pnl)) {
        const perDay = (fc && fc.overnight_per_day_rub != null
          && Number.isFinite(Number(fc.overnight_per_day_rub)))
          ? Number(fc.overnight_per_day_rub)
          : premiumOvernightPerDayRub(open, fc);
        const line = exitOvernightCushionLine(Math.round(exitPot.pnl), perDay);
        if (line) {
          ovnEl.hidden = false;
          ovnEl.className = `account-funds-forecast-ovn${line.cls ? ` ${line.cls}` : ''}`;
          ovnEl.textContent = line.text;
        } else {
          clearOvn();
        }
      } else {
        clearOvn();
      }
    }

    const bits = [];
    if (forecastRub != null && accountNowRub != null) {
      const vsNow = forecastRub - accountNowRub;
      bits.push(`к текущим средствам ≈ ${fmtRub(vsNow)} (комиссии/закрытие)`);
    }
    if (fc && fc.exit_commission_rub != null) {
      bits.push(`комиссии ≈ ${fmtRubPlain(fc.exit_commission_rub, 0)}`);
    }
    if (fc && fc.overnight_rub != null) {
      const d = fc.overnight_days != null ? ` · ${fc.overnight_days}д` : '';
      bits.push(`overnight ≈ ${fmtRubPlain(fc.overnight_rub, 0)}${d}`);
    }
    if (fc && fc.vs_mid_rub != null) {
      const v = Number(fc.vs_mid_rub);
      const sign = v > 0 ? '+' : '';
      bits.push(`vs mid ${sign}${fmt(v, 0)} ₽`);
    }
    if (fc && fc.note) bits.push(fc.note);
    sub.textContent = bits.join(' · ');
  }

  function paramsFocused() {
    const ae = document.activeElement;
    return !!(ae && (ae.id === 'tradeLeverage' || ae.id === 'tradeAutoExec'
      || ae.id === 'tradeTpSel'
      || ae.id === 'tradeEntryDeposit'
      || ae.id === 'tradeAddonDeposit'
      || ae.id === 'tradeExtraDeposit'
      || ae.id === 'tradeCompound'
      || ae.id === 'tradeSpreadEnterNarrow' || ae.id === 'tradeSpreadExitNarrow'
      || ae.id === 'tradeSpreadEnterWide' || ae.id === 'tradeSpreadExitWide'));
  }

  function setParamsStatus(msg, kind) {
    const el = $('tradeParamsStatus');
    if (!el) return;
    el.textContent = msg || '';
    el.classList.remove('is-ok', 'is-err', 'is-pending');
    if (kind) el.classList.add(`is-${kind}`);
    if (saveStatusTimer) clearTimeout(saveStatusTimer);
    if (msg && kind === 'ok') {
      saveStatusTimer = setTimeout(() => {
        if (el.textContent === msg) {
          el.textContent = '';
          el.classList.remove('is-ok');
        }
      }, 4000);
    }
  }

  function setDepositStatus(msg, kind) {
    const el = $('tradeDepositStatus');
    if (!el) return;
    el.textContent = msg || '';
    el.classList.remove('is-ok', 'is-err', 'is-pending');
    if (kind) el.classList.add(`is-${kind}`);
  }

  function readEntryDeposit() {
    const n = parseFloat(String($('tradeEntryDeposit')?.value || '').replace(',', '.'));
    if (!Number.isFinite(n)) return null;
    return Math.max(1000, Math.min(10_000_000, Math.round(n)));
  }

  function readNamedDeposit(id) {
    const n = parseFloat(String($(id)?.value || '').replace(',', '.'));
    if (!Number.isFinite(n)) return null;
    return Math.max(1000, Math.min(10_000_000, Math.round(n)));
  }

  function readMtlrDeposit() {
    const n = parseFloat(String($('tradeMtlrDeposit')?.value || '').replace(',', '.'));
    if (!Number.isFinite(n)) return null;
    return Math.max(1000, Math.min(10_000_000, Math.round(n)));
  }

  function readSpreadLevelInput(id, fallback) {
    const n = parseFloat(String($(id)?.value || '').replace(',', '.'));
    if (!Number.isFinite(n)) return fallback;
    return Math.max(0.1, Math.min(20, Math.round(n * 10) / 10));
  }

  function readFormParams() {
    const leverage = parseFloat(String($('tradeLeverage')?.value || '').replace(',', '.'));
    const tpRaw = parseFloat(String($('tradeTpSel')?.value || '0').replace(',', '.'));
    const tpAllowed = [0, 1, 2, 3];
    const takeProfit = tpAllowed.includes(tpRaw) ? tpRaw : 0;
    const noTrendRaw = parseInt(String($('tradeHoldNoTrendSel')?.value || '5'), 10);
    const losingRaw = parseInt(String($('tradeHoldLosingSel')?.value || '0'), 10);
    const holdNoTrend = [0, 5, 7, 10].includes(noTrendRaw) ? noTrendRaw : 5;
    const holdLosing = [0, 7, 10].includes(losingRaw) ? losingRaw : 0;
    // entry_z / exit_z / regime_z_mode не шлём с UI — серверные значения не трогаем.
    const params = {
      leverage: Number.isFinite(leverage) ? leverage : null,
      take_profit_pct: takeProfit,
      max_hold_days_no_exit_trend: holdNoTrend,
      max_hold_days_if_losing: holdLosing,
      addon_mode: !!$('tradeAddonMode')?.checked,
      addon_enter_narrow: readSpreadLevelInput('tradeAddonEnterNarrow', 2.0),
      addon_exit_narrow: readSpreadLevelInput('tradeAddonExitNarrow', 3.2),
      addon_enter_wide: readSpreadLevelInput('tradeAddonEnterWide', 7.0),
      addon_exit_wide: readSpreadLevelInput('tradeAddonExitWide', 6.1),
      extra_addon_mode: !!$('tradeExtraAddonMode')?.checked,
      extra_enter_narrow: readSpreadLevelInput('tradeExtraEnterNarrow', 1.0),
      extra_exit_narrow: readSpreadLevelInput('tradeExtraExitNarrow', 2.0),
      extra_enter_wide: readSpreadLevelInput('tradeExtraEnterWide', 9.0),
      extra_exit_wide: readSpreadLevelInput('tradeExtraExitWide', 7.0),
      auto_execute: !!$('tradeAutoExec')?.checked,
      spread_level_mode: true,
      entry_deposit_rub: readEntryDeposit(),
      addon_deposit_rub: readNamedDeposit('tradeAddonDeposit'),
      extra_deposit_rub: readNamedDeposit('tradeExtraDeposit'),
      compound: !!$('tradeCompound')?.checked,
      spread_enter_narrow: readSpreadLevelInput('tradeSpreadEnterNarrow', 3.2),
      spread_exit_narrow: readSpreadLevelInput('tradeSpreadExitNarrow', 4.0),
      spread_enter_wide: readSpreadLevelInput('tradeSpreadEnterWide', 6.1),
      spread_exit_wide: readSpreadLevelInput('tradeSpreadExitWide', 5.8),
    };
    if (DESK_MTLR_UI_ENABLED) {
      params.mtlr_enabled = !!$('tradeMtlrEnabled')?.checked;
      params.mtlr_auto_execute = !!$('tradeMtlrAuto')?.checked;
      params.mtlr_deposit_rub = readMtlrDeposit();
    }
    return params;
  }

  function cacheParamsLocal(settings) {
    if (!settings) return;
    try {
      localStorage.setItem(LS_TRADE_PARAMS, JSON.stringify({
        entry_z: settings.entry_z,
        exit_z: settings.exit_z,
        leverage: settings.leverage,
        take_profit_pct: settings.take_profit_pct != null ? settings.take_profit_pct : 2,
        max_hold_days_no_exit_trend: settings.max_hold_days_no_exit_trend != null
          ? settings.max_hold_days_no_exit_trend : 5,
        max_hold_days_if_losing: settings.max_hold_days_if_losing != null
          ? settings.max_hold_days_if_losing : 0,
        addon_mode: settings.addon_mode !== false,
        addon_enter_narrow: settings.addon_enter_narrow != null ? settings.addon_enter_narrow : 2.0,
        addon_exit_narrow: settings.addon_exit_narrow != null ? settings.addon_exit_narrow : 3.2,
        addon_enter_wide: settings.addon_enter_wide != null ? settings.addon_enter_wide : 7.0,
        addon_exit_wide: settings.addon_exit_wide != null ? settings.addon_exit_wide : 6.1,
        extra_addon_mode: settings.extra_addon_mode !== false,
        extra_enter_narrow: settings.extra_enter_narrow != null ? settings.extra_enter_narrow : 1.0,
        extra_exit_narrow: settings.extra_exit_narrow != null ? settings.extra_exit_narrow : 2.0,
        extra_enter_wide: settings.extra_enter_wide != null ? settings.extra_enter_wide : 9.0,
        extra_exit_wide: settings.extra_exit_wide != null ? settings.extra_exit_wide : 7.0,
        auto_execute: !!settings.auto_execute,
        spread_level_mode: settings.spread_level_mode !== false,
        regime_z_mode: !!settings.regime_z_mode,
        entry_deposit_rub: settings.entry_deposit_rub != null ? settings.entry_deposit_rub : 40000,
        addon_deposit_rub: settings.addon_deposit_rub != null ? settings.addon_deposit_rub : 30000,
        extra_deposit_rub: settings.extra_deposit_rub != null ? settings.extra_deposit_rub : 30000,
        compound: settings.compound !== false,
        spread_enter_narrow: settings.spread_enter_narrow,
        spread_exit_narrow: settings.spread_exit_narrow,
        spread_enter_wide: settings.spread_enter_wide,
        spread_exit_wide: settings.spread_exit_wide,
      }));
    } catch (_) { /* ignore quota */ }
  }

  function loadCachedParamsLocal() {
    try {
      const raw = localStorage.getItem(LS_TRADE_PARAMS);
      if (!raw) return null;
      const o = JSON.parse(raw);
      if (!o || o.leverage == null) return null;
      if (localStorage.getItem('moexReplay.tradeSpreadEnterWideV61') !== '1') {
        const ew = Number(o.spread_enter_wide);
        if (!Number.isFinite(ew) || Math.abs(ew - 6.2) < 1e-9) o.spread_enter_wide = 6.1;
        const ax = Number(o.addon_exit_wide);
        if (Number.isFinite(ax) && Math.abs(ax - 6.2) < 1e-9) o.addon_exit_wide = 6.1;
        localStorage.setItem('moexReplay.tradeSpreadEnterWideV61', '1');
        try { localStorage.setItem(LS_TRADE_PARAMS, JSON.stringify(o)); } catch (_) { /* quota */ }
      }
      if (o.entry_deposit_rub == null) o.entry_deposit_rub = 10000;
      if (o.take_profit_pct == null) o.take_profit_pct = 2;
      if (o.max_hold_days_no_exit_trend == null) o.max_hold_days_no_exit_trend = 5;
      if (o.max_hold_days_if_losing == null) o.max_hold_days_if_losing = 0;
      if (o.addon_mode == null) o.addon_mode = true;
      if (o.addon_enter_narrow == null) o.addon_enter_narrow = 2.0;
      if (o.addon_exit_narrow == null) o.addon_exit_narrow = 3.2;
      if (o.addon_enter_wide == null) o.addon_enter_wide = 7.0;
      if (o.addon_exit_wide == null) o.addon_exit_wide = 6.1;
      if (o.extra_addon_mode == null) o.extra_addon_mode = true;
      if (o.extra_enter_narrow == null) o.extra_enter_narrow = 1.0;
      if (o.extra_exit_narrow == null) o.extra_exit_narrow = 2.0;
      if (o.extra_enter_wide == null) o.extra_enter_wide = 9.0;
      if (o.extra_exit_wide == null) o.extra_exit_wide = 7.0;
      if (o.addon_deposit_rub == null) o.addon_deposit_rub = 30000;
      if (o.extra_deposit_rub == null) o.extra_deposit_rub = 30000;
      if (o.compound == null) o.compound = true;
      return o;
    } catch (_) {
      return null;
    }
  }

  function applyParamsToForm(settings) {
    if (!settings) return;
    if (settings.leverage != null && $('tradeLeverage')) {
      $('tradeLeverage').value = String(settings.leverage);
    }
    if ($('tradeTpSel')) {
      const tp = settings.take_profit_pct != null ? Number(settings.take_profit_pct) : 2;
      const v = [0, 1, 2, 3].includes(tp) ? String(tp) : '2';
      $('tradeTpSel').value = v;
    }
    if ($('tradeHoldNoTrendSel')) {
      const n = settings.max_hold_days_no_exit_trend != null
        ? Number(settings.max_hold_days_no_exit_trend) : 5;
      const v = [0, 5, 7, 10].includes(n) ? String(n) : '5';
      $('tradeHoldNoTrendSel').value = v;
    }
    if ($('tradeHoldLosingSel')) {
      const n = settings.max_hold_days_if_losing != null
        ? Number(settings.max_hold_days_if_losing) : 0;
      const v = [0, 7, 10].includes(n) ? String(n) : '0';
      $('tradeHoldLosingSel').value = v;
    }
    if ($('tradeAddonMode')) {
      $('tradeAddonMode').checked = settings.addon_mode !== false;
    }
    if ($('tradeAddonEnterNarrow')) {
      $('tradeAddonEnterNarrow').value = String(
        settings.addon_enter_narrow != null ? settings.addon_enter_narrow : 2.0
      );
    }
    if ($('tradeAddonExitNarrow')) {
      $('tradeAddonExitNarrow').value = String(
        settings.addon_exit_narrow != null ? settings.addon_exit_narrow : 3.2
      );
    }
    if ($('tradeAddonEnterWide')) {
      $('tradeAddonEnterWide').value = String(
        settings.addon_enter_wide != null ? settings.addon_enter_wide : 7.0
      );
    }
    if ($('tradeAddonExitWide')) {
      $('tradeAddonExitWide').value = String(
        settings.addon_exit_wide != null ? settings.addon_exit_wide : 6.1
      );
    }
    if ($('tradeExtraAddonMode')) {
      $('tradeExtraAddonMode').checked = settings.extra_addon_mode !== false;
    }
    if ($('tradeExtraEnterNarrow')) {
      $('tradeExtraEnterNarrow').value = String(
        settings.extra_enter_narrow != null ? settings.extra_enter_narrow : 1.0
      );
    }
    if ($('tradeExtraExitNarrow')) {
      $('tradeExtraExitNarrow').value = String(
        settings.extra_exit_narrow != null ? settings.extra_exit_narrow : 2.0
      );
    }
    if ($('tradeExtraEnterWide')) {
      $('tradeExtraEnterWide').value = String(
        settings.extra_enter_wide != null ? settings.extra_enter_wide : 9.0
      );
    }
    if ($('tradeExtraExitWide')) {
      $('tradeExtraExitWide').value = String(
        settings.extra_exit_wide != null ? settings.extra_exit_wide : 7.0
      );
    }
    if ($('tradeEntryDeposit')) {
      const dep = settings.entry_deposit_rub != null ? settings.entry_deposit_rub : 40000;
      $('tradeEntryDeposit').value = String(dep);
    }
    if ($('tradeAddonDeposit')) {
      const dep = settings.addon_deposit_rub != null ? settings.addon_deposit_rub : 30000;
      $('tradeAddonDeposit').value = String(dep);
    }
    if ($('tradeExtraDeposit')) {
      const dep = settings.extra_deposit_rub != null ? settings.extra_deposit_rub : 30000;
      $('tradeExtraDeposit').value = String(dep);
    }
    if ($('tradeCompound')) {
      $('tradeCompound').checked = settings.compound !== false;
    }
    if ($('tradeAutoExec')) $('tradeAutoExec').checked = !!settings.auto_execute;
    const spEn = settings.spread_enter_narrow != null ? settings.spread_enter_narrow : 3.2;
    const spXn = settings.spread_exit_narrow != null ? settings.spread_exit_narrow : 4.0;
    const spEw = settings.spread_enter_wide != null ? settings.spread_enter_wide : 6.1;
    const spXw = settings.spread_exit_wide != null ? settings.spread_exit_wide : 5.8;
    if ($('tradeSpreadEnterNarrow')) $('tradeSpreadEnterNarrow').value = String(spEn);
    if ($('tradeSpreadExitNarrow')) $('tradeSpreadExitNarrow').value = String(spXn);
    if ($('tradeSpreadEnterWide')) $('tradeSpreadEnterWide').value = String(spEw);
    if ($('tradeSpreadExitWide')) $('tradeSpreadExitWide').value = String(spXw);
    if (DESK_MTLR_UI_ENABLED) {
      if ($('tradeMtlrEnabled')) {
        $('tradeMtlrEnabled').checked = settings.mtlr_enabled !== false;
      }
      if ($('tradeMtlrAuto')) {
        $('tradeMtlrAuto').disabled = false;
        $('tradeMtlrAuto').checked = !!settings.mtlr_auto_execute;
      }
      if ($('tradeMtlrDeposit')) {
        const dep = settings.mtlr_deposit_rub != null ? settings.mtlr_deposit_rub : 12000;
        $('tradeMtlrDeposit').value = String(dep);
      }
    }
  }

  /** Server (or LS backup) → inputs + chart lines. Skips if user is mid-edit. */
  function hydrateParams(settings, { force = false } = {}) {
    if (!settings) return false;
    if (!force && (formDirty || paramsFocused())) return false;
    applyParamsToForm(settings);
    cacheParamsLocal(settings);
    ensureCharts();
    applyThresholdVisuals(settings.entry_z, settings.exit_z, { settings });
    formHydrated = true;
    formDirty = false;
    return true;
  }

  async function hydrateParamsFromServer() {
    if (formDirty || paramsFocused()) return null;
    try {
      // lite: settings only — full /status waits on TInvest (~5s) and blocked Trade
      const data = await api('/api/live/status?lite=1');
      const settings = data.settings || {};
      if (settings.leverage != null || settings.spread_level_mode != null) {
        hydrateParams(settings, { force: true });
        return settings;
      }
    } catch (_) { /* fall through to LS */ }
    const cached = loadCachedParamsLocal();
    if (cached) hydrateParams(cached, { force: true });
    return cached;
  }

  function applyThresholdVisuals(entry, exitZ, {
    dealer1m = false,
    zEmpty = false,
    settings = null,
    spreadLevelsPayload = null,
  } = {}) {
    const e = Number(entry);
    const x = Number(exitZ);
    const entryN = Number.isFinite(e) && e > 0 ? e : 1.3;
    const exitN = Number.isFinite(x) && x > 0 ? x : 1.2;
    const spreadGuide = resolveSpreadLevelLines(settings, spreadLevelsPayload);
    const label = $('tradeThreshLabel');
    if (label) {
      if (dealer1m && zEmpty) {
        label.textContent = 'нет баров дилер 1м · ISS M15 не рисуем';
        label.classList.add('pnl-label-dealer');
      } else {
        const lv = spreadGuide.levels || {};
        const en = Number(lv.enter_narrow);
        const xn = Number(lv.exit_narrow);
        const xw = Number(lv.exit_wide);
        const ew = Number(lv.enter_wide);
        if ([en, xn, xw, ew].every((n) => Number.isFinite(n))) {
          label.textContent = `уровни L ${fmt(en, 1)}/${fmt(xn, 1)} · S ${fmt(xw, 1)}/${fmt(ew, 1)}`;
        } else {
          label.textContent = 'уровни спреда';
        }
        label.classList.toggle('pnl-label-dealer', !!dealer1m);
      }
    }
    setPrimarySpreadThresholdLines(spreadGuide.levels);
    if (DESK_MTLR_UI_ENABLED) {
      setSpreadThresholdLines(lastMtlrLevels || {
        enter_wide: 8.9, exit_wide: 8.4, enter_narrow: 3.2, exit_narrow: 4.3,
      });
    }
    return { entry: entryN, exitZ: exitN, spreadLevels: spreadGuide.levels, spreadCuts: spreadGuide.cuts };
  }

  function markParamsDirty() {
    formDirty = true;
    if ($('tradeParamsStatus')?.classList.contains('is-ok')) setParamsStatus('');
  }

  function readScrollLs(key) {
    const n = parseInt(localStorage.getItem(key) || '0', 10);
    return Number.isFinite(n) && n > 0 ? n : 0;
  }

  function bindScrollPersist(el, key) {
    if (!el || el.dataset.scrollBound === '1') return;
    el.dataset.scrollBound = '1';
    let timer = 0;
    el.addEventListener('scroll', () => {
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => {
        timer = 0;
        try { localStorage.setItem(key, String(el.scrollTop | 0)); } catch (_) { /* */ }
      }, 80);
    }, { passive: true });
  }

  function restoreScroll(el, key) {
    if (!el) return;
    const top = readScrollLs(key);
    if (top <= 0) return;
    const apply = () => { el.scrollTop = top; };
    apply();
    requestAnimationFrame(() => {
      apply();
      requestAnimationFrame(apply);
    });
  }

  function bindTradeScrolls() {
    bindScrollPersist($('tradeChecklistPanel'), LS_CHECK_SCROLL);
    bindScrollPersist($('tradeSideAccountPane') || $('tradeSidePanel'), LS_SIDE_SCROLL);
    bindScrollPersist($('tradeView'), LS_DESK_SCROLL);
  }

  function restoreTradeScrolls() {
    restoreScroll($('tradeChecklistPanel'), LS_CHECK_SCROLL);
    restoreScroll($('tradeSideAccountPane') || $('tradeSidePanel'), LS_SIDE_SCROLL);
    restoreScroll($('tradeView'), LS_DESK_SCROLL);
  }

  function isTqbrSessionBar(tradeDate) {
    const s = String(tradeDate || '').replace('T', ' ').trim();
    if (s.length < 16) return false;
    const m = s.match(/^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})/);
    if (!m) return false;
    // tradeDate already MSK wall-clock
    const wd = new Date(+m[1], +m[2] - 1, +m[3]).getDay();
    if (wd === 0 || wd === 6) return false;
    const mins = (+m[4]) * 60 + (+m[5]);
    return mins >= 7 * 60 && mins < 23 * 60 + 50;
  }

  /** Бар в окне live-спреда (будни до 23:45; выходные — дилер OK). */
  function isSpreadLiveBar(tradeDateOrBar) {
    let s = '';
    if (tradeDateOrBar && typeof tradeDateOrBar === 'object') {
      s = String(tradeDateOrBar.time || tradeDateOrBar.tradeDate || tradeDateOrBar.trade_date || '');
    } else {
      s = String(tradeDateOrBar || '');
    }
    s = s.replace('T', ' ').trim();
    if (s.length < 16) return false;
    const m = s.match(/^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})/);
    if (!m) return false;
    const wd = new Date(+m[1], +m[2] - 1, +m[3]).getDay();
    if (wd === 0 || wd === 6) return true;
    const mins = (+m[4]) * 60 + (+m[5]);
    return mins >= 7 * 60 && mins < 23 * 60 + 45;
  }

  /** Последний бар спреда до отсечки 23:45 (не after-hours tip). */
  function lastSpreadLiveBar(bars) {
    const list = Array.isArray(bars) ? bars : [];
    for (let i = list.length - 1; i >= 0; i -= 1) {
      const b = list[i];
      if (!b) continue;
      const src = String(b.source || '');
      if (src === 'tinvest_dealer_1m_tip' && !isSpreadLiveBar(b)) continue;
      if (isSpreadLiveBar(b)) return b;
    }
    return list.length ? list[list.length - 1] : null;
  }

  function nowMskParts() {
    const parts = new Intl.DateTimeFormat('en-GB', {
      timeZone: MSK,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
      weekday: 'short',
    }).formatToParts(new Date());
    const get = (t) => parts.find((p) => p.type === t)?.value;
    const y = +get('year');
    const mo = +get('month');
    const d = +get('day');
    const h = +get('hour');
    const mi = +get('minute');
    const wd = get('weekday'); // Mon..Sun
    const weekend = wd === 'Sat' || wd === 'Sun';
    const mins = h * 60 + mi;
    // AUTO tip1m / TQBR: до 23:50
    const inSession = !weekend && mins >= 7 * 60 && mins < 23 * 60 + 50;
    // Live tip-спред на столе: до 23:45 (выходные — дилер OK)
    const spreadLive = weekend
      ? true
      : (mins >= 7 * 60 && mins < 23 * 60 + 45);
    return {
      y, mo, d, h, mi, weekend, inSession, spreadLive,
      label: `${String(h).padStart(2, '0')}:${String(mi).padStart(2, '0')}`,
    };
  }

  function barZ(bar) {
    if (!bar) return null;
    const z = bar.zScore ?? bar.z;
    return z == null || Number.isNaN(Number(z)) ? null : Number(z);
  }

  function determineZSignalJs(prevZ, curZ, pos, entry, exitZ) {
    if (prevZ == null || curZ == null) return 'NONE';
    if (pos === 'FLAT') {
      if (prevZ > -entry && curZ <= -entry) return 'ENTER_LONG';
      if (prevZ < entry && curZ >= entry) return 'ENTER_SHORT';
      return 'NONE';
    }
    if (pos === 'LONG') {
      if (prevZ < -exitZ && curZ >= -exitZ) return 'EXIT_LONG';
      return 'NONE';
    }
    if (pos === 'SHORT') {
      if (prevZ > exitZ && curZ <= exitZ) return 'EXIT_SHORT';
      return 'NONE';
    }
    return 'NONE';
  }

  function checkItem(state, text) {
    const mark = state === 'ok' ? '✓' : state === 'wait' ? '…' : state === 'block' ? '!' : '–';
    return {
      state,
      html: `<li class="trade-check-item is-${state}"><span class="trade-check-mark">${mark}</span><span class="trade-check-text">${text}</span></li>`,
    };
  }

  /** Visible by default: ! / … ; ✓ (+ N/A) go into collapsed spoiler. Hide section if only N/A or empty. */
  function renderCheckList(listEl, items) {
    if (!listEl) return;
    const section = listEl.closest('.trade-check-section');
    const active = [];
    const done = [];
    const na = [];
    for (const it of items || []) {
      if (!it) continue;
      const state = it.state || 'wait';
      const html = it.html || String(it);
      if (state === 'ok') done.push(html);
      else if (state === 'na') na.push(html);
      else active.push(html);
    }

    // Only N/A or empty → hide whole section (e.g. Закрытие while FLAT)
    if (!active.length && !done.length) {
      listEl.innerHTML = '';
      listEl.hidden = true;
      const spoilRm = section && section.querySelector(':scope > details.trade-check-done');
      if (spoilRm) spoilRm.remove();
      if (section) section.hidden = true;
      return;
    }
    if (section) section.hidden = false;

    listEl.innerHTML = active.join('');
    listEl.hidden = active.length === 0;

    const spoilItems = done.concat(na);
    let spoilEl = section && section.querySelector(':scope > details.trade-check-done');
    if (spoilItems.length && section) {
      const wasOpen = !!spoilEl?.open;
      if (!spoilEl) {
        spoilEl = document.createElement('details');
        spoilEl.className = 'trade-check-done';
        section.appendChild(spoilEl);
      }
      const label = done.length
        ? (na.length ? `OK · ${done.length}` : `Выполнено (${done.length})`)
        : `N/A · ${na.length}`;
      spoilEl.innerHTML =
        `<summary>${label}</summary>` +
        `<ul class="trade-check-list">${spoilItems.join('')}</ul>`;
      spoilEl.open = wasOpen;
    } else if (spoilEl) {
      spoilEl.remove();
    }
  }

  /**
   * Фазы: idle → prep (почти всё OK, метрика у порога) → signal (edge есть) → ready (AUTO может взять).
   * spreadOn: пороги по S% (Long вых / Short вых), не Z ±exit.
   */
  function buildTradePhase({
    pos, curZ, curS, entryN, exitN, signal,
    monOn, autoOn, settled, consecutive, sessionOk, brokerOk, ghost,
    needLong, needShort, needExitLong, needExitShort, settleLeftSec,
    tip1mMode, spreadOn, enterNarrow, enterWide, exitNarrow, exitWide,
  }) {
    const hardOk = monOn && sessionOk && consecutive && brokerOk && !ghost;
    const softWait = [];
    if (!autoOn) softWait.push('авто');
    if (!settled) {
      softWait.push(tip1mMode
        ? (settleLeftSec > 0 ? `закрытие минутки tip (~${settleLeftSec}с)` : 'закрытие минутки tip')
        : (settleLeftSec > 0 ? `закрытие бара (~${settleLeftSec}с)` : 'закрытие бара'));
    }

    const waitingText = (extra) => {
      const extras = Array.isArray(extra) ? extra : (extra ? [extra] : []);
      const all = [...softWait, ...extras].filter(Boolean);
      return all.length ? `ждём: ${all.join(', ')}` : '';
    };

    const nearTh = spreadOn ? PHASE_NEAR_S : PHASE_NEAR_Z;
    const sOk = Number.isFinite(curS);

    if (pos === 'FLAT') {
      const nearLong = needLong != null && needLong <= nearTh;
      const nearShort = needShort != null && needShort <= nearTh;
      const atLevel = spreadOn
        ? (sOk && (curS <= enterNarrow || curS >= enterWide))
        : (curZ != null && (curZ <= -entryN || curZ >= entryN));
      const hasEdge = signal.startsWith('ENTER');
      const approach = hasEdge || atLevel || nearLong || nearShort;

      if (!hardOk || !approach) {
        return {
          kind: 'idle',
          label: 'ожидание',
          title: 'Условия входа ещё далеко',
        };
      }

      const sideHint = hasEdge
        ? signal
        : (nearLong || (spreadOn ? (sOk && curS <= enterNarrow) : (curZ != null && curZ <= -entryN))
          ? 'Long'
          : (nearShort || (spreadOn ? (sOk && curS >= enterWide) : (curZ != null && curZ >= entryN))
            ? 'Short' : ''));

      if (hasEdge && softWait.length === 0) {
        return {
          kind: 'ready',
          side: 'open',
          label: 'AUTO · открытие',
          title: `Готово к AUTO: ${signal}`,
          detail: signal,
        };
      }
      if (hasEdge) {
        return {
          kind: 'signal',
          side: 'open',
          label: 'сигнал · открытие',
          title: `${signal} · ${waitingText()}`,
          detail: signal,
          waiting: waitingText(),
        };
      }
      return {
        kind: 'prep',
        side: 'open',
        label: 'подготовка · открытие',
        title: sideHint
          ? `Подготовка к открытию (${sideHint}) · ${waitingText(!hasEdge ? 'edge' : '')}`
          : `Подготовка к открытию · ${waitingText('edge')}`,
        waiting: waitingText('edge'),
        detail: sideHint,
      };
    }

    // LONG / SHORT — подготовка к закрытию (spread: S→exit; иначе Z→±exit)
    const needExit = pos === 'LONG' ? needExitLong : needExitShort;
    const atExit = spreadOn
      ? (pos === 'LONG'
        ? (sOk && curS >= exitNarrow)
        : (sOk && curS <= exitWide))
      : (pos === 'LONG'
        ? (curZ != null && curZ >= -exitN)
        : (curZ != null && curZ <= exitN));
    const nearExit = needExit != null && needExit <= nearTh;
    const hasEdge = signal.startsWith('EXIT');
    const approach = hasEdge || atExit || nearExit;

    if (!hardOk || !approach) {
      return {
        kind: 'idle',
        label: 'в позиции',
        title: 'До выхода ещё далеко',
      };
    }

    if (hasEdge && softWait.length === 0) {
      return {
        kind: 'ready',
        side: 'close',
        label: 'AUTO · закрытие',
        title: `Готово к AUTO: ${signal}`,
        detail: signal,
      };
    }
    if (hasEdge) {
      return {
        kind: 'signal',
        side: 'close',
        label: 'сигнал · закрытие',
        title: `${signal} · ${waitingText()}`,
        detail: signal,
        waiting: waitingText(),
      };
    }
    return {
      kind: 'prep',
      side: 'close',
      label: 'подготовка · закрытие',
      title: `Подготовка к закрытию · ${waitingText('edge')}`,
      waiting: waitingText('edge'),
    };
  }

  function renderChecklist(data) {
    const hintEl = $('tradeCheckHint');
    const generalEl = $('tradeCheckGeneral');
    const openEl = $('tradeCheckOpen');
    const closeEl = $('tradeCheckClose');
    if (!hintEl || !generalEl || !openEl || !closeEl) return;

    const settings = data.settings || {};
    const mon = data.monitor || {};
    const pos = String(data.position || 'FLAT').toUpperCase();
    const open = data.open || null;
    const broker = data.broker;
    const bs = data.broker_spread;
    const bars = data.bars || [];
    const regime = data.regime || {};
    const sl = data.spread_levels || {};
    const thEff = effectiveThresholds(settings, regime, settings.entry_z, settings.exit_z);
    const entryN = thEff.entry;
    const exitN = thEff.exit;
    const autoOn = !!settings.auto_execute;
    const monOn = !!mon.running;
    const nowMs = Date.now();
    const msk = nowMskParts();

    const last = bars.length ? bars[bars.length - 1] : null;
    const spreadBar = lastSpreadLiveBar(bars) || last;
    const prev = bars.length >= 2 ? bars[bars.length - 2] : null;
    const lastMs = last ? Number(last.timestampMs || 0) : 0;
    const prevMs = prev ? Number(prev.timestampMs || 0) : 0;
    // Prod tip1m: consecutive 1m tips; chart may still show M15 — treat either step as OK for prep.
    const consecutive = prevMs > 0 && (lastMs - prevMs === M1_MS || lastMs - prevMs === M15_MS);
    const tip1mMode = String(settings.signal_mode || 'tip1m') === 'tip1m';
    const hasNext = false;
    const settled = tip1mMode
      ? (lastMs > 0 && nowMs >= lastMs + M1_MS + TIP1M_SETTLE_MS)
      : (lastMs > 0 && (hasNext || nowMs >= lastMs + M15_MS + BAR_SETTLE_MS));
    const settleLeftSec = lastMs > 0
      ? Math.max(0, Math.ceil(
        ((tip1mMode
          ? (lastMs + M1_MS + TIP1M_SETTLE_MS)
          : (lastMs + M15_MS + BAR_SETTLE_MS)) - nowMs) / 1000,
      ))
      : 0;
    const curZ = barZ(spreadBar);
    const prevZ = barZ(prev);
    const curS = spreadBar && (spreadBar.spread != null || spreadBar.spreadPercent != null)
      ? Number(spreadBar.spread != null ? spreadBar.spread : spreadBar.spreadPercent)
      : (sl.spread != null ? Number(sl.spread) : null);
    const prevS = prev && (prev.spread != null || prev.spreadPercent != null)
      ? Number(prev.spread != null ? prev.spread : prev.spreadPercent)
      : null;
    const barTd = spreadBar?.time || spreadBar?.tradeDate || spreadBar?.trade_date
      || last?.time || last?.tradeDate || last?.trade_date || data.summary?.trade_date || '';
    const barInSession = isTqbrSessionBar(barTd);
    const sessionOk = msk.inSession && barInSession;
    const dealer = data.dealer;
    const spreadFrozen = (msk.spreadLive === false && !msk.weekend)
      || (dealer && dealer.spread_live === false);
    let signal = 'NONE';
    if (consecutive) {
      if (thEff.spreadOn) {
        signal = determineSpreadLevelSignalJs(prevS, curS, pos, thEff.levels || sl.levels);
        if (sl.entry_blocked && (signal === 'ENTER_LONG' || signal === 'ENTER_SHORT')) {
          signal = 'NONE';
        }
      } else {
        signal = determineZSignalJs(prevZ, curZ, pos, entryN, exitN);
        if (thEff.regimeOn && !thEff.allowEntry
          && (signal === 'ENTER_LONG' || signal === 'ENTER_SHORT')) {
          signal = 'NONE';
        }
      }
    }
    const tpPct = Number(settings.take_profit_pct);
    const tpOn = Number.isFinite(tpPct) && tpPct > 0;

    const brokerOk = !!(broker && !broker.error);
    const ghost = !!(!open && bs && !bs.error && bs.direction);
    const dealerManualOk = !!(dealer && (dealer.manual_ok || dealer.quotes_ok
      || (dealer.ok && dealer.tatn != null && dealer.tatnp != null)));

    const general = [];
    general.push(checkItem(monOn ? 'ok' : 'block', monOn ? 'Монитор ON' : 'Монитор OFF — старт на вкладке Счёт'));
    general.push(checkItem(autoOn ? 'ok' : 'wait', autoOn ? 'Авто ON (ордера)' : 'Авто OFF — сигналы без ордеров'));
    general.push(checkItem('ok', tip1mMode
      ? 'Стратегия: касание tip1m (Mode B)'
      : 'Стратегия: M15 close (legacy)'));
    if (spreadFrozen) {
      const sTxt = Number.isFinite(curS) ? fmt(curS, 2) : '—';
      const asof = escapeHtml(String(
        (dealer && dealer.spread_asof) || barTd || msk.label,
      ));
      general.push(checkItem('wait',
        `Спред заморожен после 23:45 · S=${sTxt}% на ${asof} · не after-hours tip`));
    }
    if (thEff.spreadOn) {
      const lv = thEff.levels || sl.levels || {};
      const curLab = sl.current_label_ru || '—';
      const sTxt = Number.isFinite(curS) ? fmt(curS, 2) : '—';
      general.push(checkItem('ok',
        `Спред-уровни ON · сейчас ${escapeHtml(String(curLab))} · S=${sTxt}%`
        + ` · Short ${fmt(lv.enter_wide ?? 6.1, 1)}/${fmt(lv.exit_wide ?? 5.8, 1)}`
        + ` · Long ${fmt(lv.enter_narrow ?? 3.2, 1)}/${fmt(lv.exit_narrow ?? 4, 1)}`));
      if (pos === 'FLAT' && sl.entry_blocked) {
        general.push(checkItem('block', 'Вход запрещён · переход'));
      }
    } else {
      general.push(checkItem('wait',
        'Спред-уровни выкл · включите в Параметрах (AUTO без Z-порогов)'));
    }
    general.push(checkItem(tpOn ? 'ok' : 'wait',
      tpOn ? `ТП ${tpPct}% (MTM % депозита, как tip1m)` : 'ТП выкл'));
    if (msk.weekend || dealer) {
      const stShort = escapeHtml(String(dealer?.status_tatn || '—').replace('SECURITY_TRADING_STATUS_', ''));
      general.push(checkItem(dealerManualOk ? 'ok' : (dealer && dealer.error ? 'block' : 'wait'),
        dealerManualOk
          ? `Дилер OK · ${escapeHtml(dealer.label || 'выходные · 1м')} · ручной Long/Short · статус ${stShort}`
          : (dealer && dealer.error
            ? `Дилер: ${escapeHtml(String(dealer.error))}`
            : `Дилер: нет котировок (${msk.label} МСК)`)));
      general.push(checkItem(
        (dealer && dealer.bars_count > 0) || String(data.bars_mode || '') === 'dealer_1m'
          ? 'ok' : 'wait',
        (dealer && dealer.bars_count > 0) || String(data.bars_mode || '') === 'dealer_1m'
          ? `Спред: дилер 1м (${dealer?.bars_count || bars.length} бар) · не в AUTO`
          : 'Спред: ждём 1м дилерские свечи'
      ));
      general.push(checkItem('wait',
        `TQBR закрыта (${msk.label} МСК) — AUTO tip1m только в сессии · дилер = монитор/ручной`));
      const hasMonSp = (bars || []).some((b) => b && b.spread != null
        && (b.source && String(b.source).includes('dealer')));
      general.push(checkItem(hasMonSp || ((bars || []).some((b) => b && b.spread != null)) ? 'ok' : 'wait',
        hasMonSp || ((bars || []).some((b) => b && b.spread != null))
          ? 'График: свечи спреда % (дилер 1м · монитор · не AUTO)'
          : 'График: ждём бары спреда дилера · ISS M15 не рисуем'));
    } else {
      general.push(checkItem(msk.inSession ? 'ok' : 'block',
        msk.inSession ? `Сессия TQBR сейчас (${msk.label} МСК)` : `Вне сессии TQBR (${msk.label} МСК)`));
    }
    const dealerUi = String(data.bars_mode || '') === 'dealer_1m' || !!(msk.weekend && dealer);
    if (dealerUi) {
      general.push(checkItem(last ? 'ok' : 'wait',
        last
          ? `Дилер 1м бар · ${escapeHtml(fmtTickLabel(barTd))} · не tip1m-сигнал`
          : 'Нет дилерского 1м бара'));
      const dGapOk = prevMs > 0 && lastMs > 0 && (lastMs - prevMs) <= 5 * M1_MS;
      general.push(checkItem(dGapOk ? 'ok' : (bars.length >= 2 ? 'wait' : 'wait'),
        dGapOk
          ? 'Ряд дилер 1м достаточно плотный'
          : (bars.length >= 2 ? 'Дилер 1м с пропусками (нормально вне сессии)' : 'Мало дилерских баров')));
    } else {
      general.push(checkItem(barInSession ? 'ok' : (last ? 'block' : 'wait'),
        last
          ? (barInSession
            ? (tip1mMode
              ? `Tip-бар в сессии · ${escapeHtml(fmtTickLabel(barTd))}`
              : `Бар в сессии · ${escapeHtml(fmtTickLabel(barTd))}`)
            : `Бар вне сессии · ${escapeHtml(fmtTickLabel(barTd))}`)
          : (tip1mMode ? 'Нет tip-бара' : 'Нет бара')));
      if (tip1mMode) {
        general.push(checkItem(settled ? 'ok' : 'wait',
          settled
            ? `Минутка tip закрыта (+${Math.round(TIP1M_SETTLE_MS / 1000)}с)`
            : `Ждём закрытие минутки tip${settleLeftSec > 0 ? ` · ещё ~${settleLeftSec}с` : ''}`));
        general.push(checkItem(consecutive ? 'ok' : 'wait',
          consecutive
            ? 'Ряд tip1m без дыры'
            : (bars.length >= 2 ? 'Дыра в UI-барах (сигнал считает сервер по 1м tip)' : 'Мало баров')));
      } else {
        general.push(checkItem(settled ? 'ok' : 'wait',
          settled
            ? `Бар закрыт (+${Math.round(BAR_SETTLE_MS / 1000)}с)`
            : `Ждём закрытие бара${settleLeftSec > 0 ? ` · ещё ~${settleLeftSec}с` : ''}`));
        general.push(checkItem(consecutive ? 'ok' : (bars.length >= 2 ? 'block' : 'wait'),
          consecutive ? 'Ряд баров без дыры (15м)' : (bars.length >= 2 ? 'Дыра в барах — AUTO пропустит' : 'Мало баров')));
      }
    }
    general.push(checkItem(brokerOk ? 'ok' : 'block',
      brokerOk ? `Брокер OK · ${escapeHtml(String(settings.mode || '—'))}` : `Брокер: ${escapeHtml(broker?.error || 'нет данных')}`));
    if (ghost) {
      general.push(checkItem('block', `Призрак брокера: ${escapeHtml(bs.direction)} ${bs.quantity_lots}+${bs.quantity_lots}`));
    }

    const openItems = [];
    const closeItems = [];
    const zTxt = curZ == null ? '—' : fmt(curZ, 2);
    const sTxt = Number.isFinite(curS) ? fmt(curS, 2) : '—';
    const lv = thEff.levels || sl.levels || {};
    const enterW = Number(lv.enter_wide ?? 6.1);
    const exitW = Number(lv.exit_wide ?? 5.8);
    const enterNlv = Number(lv.enter_narrow ?? 3.2);
    const exitNlv = Number(lv.exit_narrow ?? 4.0);
    const needLong = thEff.spreadOn
      ? (Number.isFinite(curS) ? Math.max(0, curS - enterNlv) : null)
      : (curZ == null ? null : Math.max(0, curZ + entryN));
    const needShort = thEff.spreadOn
      ? (Number.isFinite(curS) ? Math.max(0, enterW - curS) : null)
      : (curZ == null ? null : Math.max(0, entryN - curZ));
    const needExitLong = thEff.spreadOn
      ? (Number.isFinite(curS) ? Math.max(0, exitNlv - curS) : null)
      : (curZ == null ? null : Math.max(0, (-exitN) - curZ));
    const needExitShort = thEff.spreadOn
      ? (Number.isFinite(curS) ? Math.max(0, curS - exitW) : null)
      : (curZ == null ? null : Math.max(0, curZ - exitN));

    const phase = buildTradePhase({
      pos, curZ, curS, entryN, exitN, signal,
      monOn, autoOn, settled, consecutive, sessionOk, brokerOk, ghost,
      needLong, needShort, needExitLong, needExitShort, settleLeftSec,
      tip1mMode,
      spreadOn: !!thEff.spreadOn,
      enterNarrow: enterNlv,
      enterWide: enterW,
      exitNarrow: exitNlv,
      exitWide: exitW,
    });

    const settleBlocker = tip1mMode
      ? `закрытие минутки tip (+${Math.round(TIP1M_SETTLE_MS / 1000)}с)`
      : `закрытие бара (+${Math.round(BAR_SETTLE_MS / 1000)}с)`;
    const edgeOpenHint = tip1mMode
      ? (thEff.spreadOn
        ? 'Нужен edge: касание уровня входа на tip1m (S)'
        : 'Нужен edge: касание порога входа на tip1m')
      : 'Нужен edge: пересечение порога входа на закрытом баре';
    const edgeCloseHint = tip1mMode
      ? (thEff.spreadOn
        ? 'Нужен edge: касание уровня выхода на tip1m (S)'
        : 'Нужен edge: касание порога выхода на tip1m')
      : 'Нужен edge: пересечение порога выхода на закрытом баре';

    if (pos === 'FLAT') {
      openItems.push(checkItem('ok', 'Позиция FLAT — можно открыть'));
      if (ghost) {
        openItems.push(checkItem('block', 'Сначала сверить призрак с брокером'));
      }
      if (thEff.spreadOn) {
        openItems.push(checkItem(
          Number.isFinite(curS) && curS <= enterNlv ? 'ok' : 'wait',
          Number.isFinite(curS) && curS <= enterNlv
            ? `S ≤ ${fmt(enterNlv, 1)}% для Long · сейчас ${sTxt}%`
            : `До Long: S ≤ ${fmt(enterNlv, 1)}% · сейчас ${sTxt}%`
              + `${needLong != null && needLong > 0 ? ` · ещё −${fmt(needLong, 2)}` : ''}`
        ));
        openItems.push(checkItem(
          Number.isFinite(curS) && curS >= enterW ? 'ok' : 'wait',
          Number.isFinite(curS) && curS >= enterW
            ? `S ≥ ${fmt(enterW, 1)}% для Short · сейчас ${sTxt}%`
            : `До Short: S ≥ ${fmt(enterW, 1)}% · сейчас ${sTxt}%`
              + `${needShort != null && needShort > 0 ? ` · ещё +${fmt(needShort, 2)}` : ''}`
        ));
      } else {
        openItems.push(checkItem(
          curZ != null && curZ <= -entryN ? 'ok' : 'wait',
          curZ != null && curZ <= -entryN
            ? `Z ≤ −${fmt(entryN, 2)} для Long · сейчас ${zTxt}`
            : `До Long: Z ≤ −${fmt(entryN, 2)} · сейчас ${zTxt}${needLong != null && needLong > 0 ? ` · ещё −${fmt(needLong, 2)}${needPctSuffix(needLong, entryN)}` : ''}`
        ));
        openItems.push(checkItem(
          curZ != null && curZ >= entryN ? 'ok' : 'wait',
          curZ != null && curZ >= entryN
            ? `Z ≥ +${fmt(entryN, 2)} для Short · сейчас ${zTxt}`
            : `До Short: Z ≥ +${fmt(entryN, 2)} · сейчас ${zTxt}${needShort != null && needShort > 0 ? ` · ещё +${fmt(needShort, 2)}${needPctSuffix(needShort, entryN)}` : ''}`
        ));
      }
      openItems.push(checkItem(
        signal.startsWith('ENTER') ? 'ok' : 'wait',
        signal.startsWith('ENTER')
          ? `Edge готов: ${signal}`
          : edgeOpenHint
      ));
      if (phase.kind === 'prep' || phase.kind === 'signal') {
        openItems.push(checkItem('wait', phase.title || phase.label));
      } else if (phase.kind === 'ready') {
        openItems.push(checkItem('ok', autoOn ? 'AUTO откроет на следующем тике tip1m' : 'Сигнал готов — включите Авто'));
      }
      const blockers = [];
      if (!monOn) blockers.push('монитор');
      if (!autoOn) blockers.push('авто');
      if (!sessionOk) blockers.push('сессия');
      if (!settled) blockers.push(settleBlocker);
      if (!consecutive) blockers.push('дыра');
      if (!brokerOk) blockers.push('брокер');
      if (ghost) blockers.push('призрак');
      if (msk.weekend || dealer) blockers.push('дилер/выходные (нет AUTO tip)');
      if (phase.kind !== 'ready' && blockers.length && !(phase.kind === 'prep' || phase.kind === 'signal')) {
        openItems.push(checkItem('block', `Блокируют: ${blockers.join(', ')}`));
      } else if (phase.kind === 'signal' && blockers.length) {
        openItems.push(checkItem('block', `Блокируют AUTO: ${blockers.join(', ')}`));
      }

      closeItems.push(checkItem('na', 'Нет позиции — закрытие не нужно'));
    } else {
      openItems.push(checkItem('na', `Уже ${escapeHtml(pos)} — новое открытие ждут FLAT`));

      closeItems.push(checkItem('ok', `Открыто: ${escapeHtml(pos)}${open?.source ? ` · ${escapeHtml(open.source)}` : ''}`));
      if (thEff.spreadOn) {
        if (pos === 'LONG') {
          closeItems.push(checkItem(
            Number.isFinite(curS) && curS >= exitNlv ? 'ok' : 'wait',
            Number.isFinite(curS) && curS >= exitNlv
              ? `S ≥ ${fmt(exitNlv, 1)}% для EXIT_LONG · сейчас ${sTxt}%`
              : `До EXIT_LONG: S ≥ ${fmt(exitNlv, 1)}% · сейчас ${sTxt}%`
                + `${needExitLong != null && needExitLong > 0 ? ` · ещё +${fmt(needExitLong, 2)}` : ''}`
          ));
        } else {
          closeItems.push(checkItem(
            Number.isFinite(curS) && curS <= exitW ? 'ok' : 'wait',
            Number.isFinite(curS) && curS <= exitW
              ? `S ≤ ${fmt(exitW, 1)}% для EXIT_SHORT · сейчас ${sTxt}%`
              : `До EXIT_SHORT: S ≤ ${fmt(exitW, 1)}% · сейчас ${sTxt}%`
                + `${needExitShort != null && needExitShort > 0 ? ` · ещё −${fmt(needExitShort, 2)}` : ''}`
          ));
        }
      } else if (pos === 'LONG') {
        const exitLongProg = needExitLong != null && Number.isFinite(needExitLong)
          ? ` · ещё +${fmt(needExitLong, 2)}${needPctSuffix(needExitLong, exitN)}`
          : '';
        closeItems.push(checkItem(
          curZ != null && curZ >= -exitN ? 'ok' : 'wait',
          curZ != null && curZ >= -exitN
            ? `Z ≥ −${fmt(exitN, 2)} для EXIT_LONG · сейчас ${zTxt}${exitLongProg}`
            : `До EXIT_LONG: Z ≥ −${fmt(exitN, 2)} · сейчас ${zTxt}${exitLongProg}`
        ));
      } else {
        const exitShortProg = needExitShort != null && Number.isFinite(needExitShort)
          ? ` · ещё −${fmt(needExitShort, 2)}${needPctSuffix(needExitShort, exitN)}`
          : '';
        closeItems.push(checkItem(
          curZ != null && curZ <= exitN ? 'ok' : 'wait',
          curZ != null && curZ <= exitN
            ? `Z ≤ +${fmt(exitN, 2)} для EXIT_SHORT · сейчас ${zTxt}${exitShortProg}`
            : `До EXIT_SHORT: Z ≤ +${fmt(exitN, 2)} · сейчас ${zTxt}${exitShortProg}`
        ));
      }
      closeItems.push(checkItem(
        signal.startsWith('EXIT') ? 'ok' : 'wait',
        signal.startsWith('EXIT')
          ? `Edge готов: ${signal}`
          : edgeCloseHint
      ));
      if (tpOn) {
        closeItems.push(checkItem('ok', `ТП ${tpPct}% — выход по MTM без ожидания M15`));
      }
      if (phase.kind === 'prep' || phase.kind === 'signal') {
        closeItems.push(checkItem('wait', phase.title || phase.label));
      } else if (phase.kind === 'ready') {
        closeItems.push(checkItem('ok', autoOn ? 'AUTO закроет на следующем тике tip1m' : 'Сигнал готов — Авто или «Закрыть сделку»'));
      }
      const blockers = [];
      if (!monOn) blockers.push('монитор');
      if (!autoOn) blockers.push('авто');
      if (!sessionOk) blockers.push('сессия');
      if (!settled) blockers.push(settleBlocker);
      if (!consecutive) blockers.push('дыра');
      if (!brokerOk) blockers.push('брокер');
      if (phase.kind === 'signal' && blockers.length) {
        closeItems.push(checkItem('block', `Блокируют AUTO: ${blockers.join(', ')}`));
      } else if (phase.kind === 'idle' && blockers.length) {
        closeItems.push(checkItem('block', `Блокируют AUTO: ${blockers.join(', ')}`));
      }
    }

    renderCheckList(generalEl, general);
    renderCheckList(openEl, openItems);
    renderCheckList(closeEl, closeItems);

    let hint = '';
    let hintCls = 'trade-check-hint';
    let hintIco = '';
    const metricTxt = thEff.spreadOn ? `S ${sTxt}%` : `Z ${zTxt}`;
    const exitThLong = thEff.spreadOn ? exitNlv : exitN;
    const exitThShort = thEff.spreadOn ? exitW : exitN;
    const entryThLong = thEff.spreadOn ? enterNlv : entryN;
    const entryThShort = thEff.spreadOn ? enterW : entryN;
    const openDir = (() => {
      const d = `${phase.detail || ''} ${phase.title || ''} ${signal || ''}`;
      if (/ENTER_LONG|\bLong\b/i.test(d)) return 'long';
      if (/ENTER_SHORT|\bShort\b/i.test(d)) return 'short';
      return null;
    })();
    if (phase.kind === 'ready') {
      const prog = phase.side === 'open'
        ? openEntryProgressText(openDir, needLong, needShort,
          openDir === 'short' ? entryThShort : entryThLong)
        : '';
      hint = prog ? `${phase.title} · ${prog} · ${metricTxt}` : `${phase.title} · ${metricTxt}`;
      hintCls += ' is-ready';
      hintIco = phase.side === 'close' ? '↘' : '↗';
    } else if (phase.kind === 'signal') {
      const prog = phase.side === 'open'
        ? openEntryProgressText(openDir, needLong, needShort,
          openDir === 'short' ? entryThShort : entryThLong)
        : '';
      hint = prog ? `${phase.title} · ${prog} · ${metricTxt}` : `${phase.title} · ${metricTxt}`;
      hintCls += ' is-block';
      hintIco = phase.side === 'close' ? '↘' : '↗';
    } else if (phase.kind === 'prep') {
      const prog = phase.side === 'open'
        ? openEntryProgressText(openDir, needLong, needShort,
          openDir === 'short' ? entryThShort : entryThLong)
        : '';
      hint = prog ? `${phase.title} · ${prog} · ${metricTxt}` : `${phase.title} · ${metricTxt}`;
      if (phase.side === 'close') {
        hintCls += ' is-prep-close';
        hintIco = '↘';
      } else if (openDir === 'short') {
        hintCls += ' is-prep-open-short';
        hintIco = '↗';
      } else if (openDir === 'long') {
        hintCls += ' is-prep-open-long';
        hintIco = '↗';
      } else {
        hintCls += ' is-prep-open';
        hintIco = '↗';
      }
    } else if (pos === 'FLAT') {
      const nearer = (needLong == null || needShort == null)
        ? null
        : (needLong <= needShort
          ? (thEff.spreadOn
            ? `до Long ещё −${fmt(needLong, 2)}${needPctSuffix(needLong, entryThLong)} по S`
            : `до Long ещё −${fmt(needLong, 2)}${needPctSuffix(needLong, entryThLong)} по Z`)
          : (thEff.spreadOn
            ? `до Short ещё +${fmt(needShort, 2)}${needPctSuffix(needShort, entryThShort)} по S`
            : `до Short ещё +${fmt(needShort, 2)}${needPctSuffix(needShort, entryThShort)} по Z`));
      hint = nearer ? `Ожидание входа · ${nearer} · ${metricTxt}` : `Ожидание входа · ${metricTxt}`;
    } else if (pos === 'LONG') {
      hint = needExitLong != null && Number.isFinite(needExitLong)
        ? (thEff.spreadOn
          ? `В позиции Long · до выхода ещё +${fmt(needExitLong, 2)}${needPctSuffix(needExitLong, exitThLong)} по S · ${metricTxt}`
          : `В позиции Long · до выхода ещё +${fmt(needExitLong, 2)}${needPctSuffix(needExitLong, exitThLong)} по Z · ${metricTxt}`)
        : `В позиции Long · ${metricTxt}`;
    } else {
      hint = needExitShort != null && Number.isFinite(needExitShort)
        ? (thEff.spreadOn
          ? `В позиции Short · до выхода ещё −${fmt(needExitShort, 2)}${needPctSuffix(needExitShort, exitThShort)} по S · ${metricTxt}`
          : `В позиции Short · до выхода ещё −${fmt(needExitShort, 2)}${needPctSuffix(needExitShort, exitThShort)} по Z · ${metricTxt}`)
        : `В позиции Short · ${metricTxt}`;
    }
    hintEl.className = hintCls;
    if (hintIco) {
      hintEl.innerHTML = `<span class="trade-check-hint-ico" aria-hidden="true">${hintIco}</span>${escapeHtml(hint)}`;
    } else {
      hintEl.textContent = hint;
    }

    const sideStatus = $('tradeSideStatus');
    if (sideStatus) {
      const phaseHtml = phaseBadgeHtml(phase.kind === 'idle' ? null : phase);
      sideStatus.innerHTML = `${posBadge(pos)}${phaseHtml ? ` ${phaseHtml}` : ''}`;
    }
  }

  function renderDesk(data, { hydrateForm = false } = {}) {
    // Lite omit / cold broker must not wipe painted «СРЕДСТВА НА СЧЁТЕ».
    data = coalesceDeskBroker(data || {});
    const s = data.summary || {};
    const settings = data.settings || {};
    const mon = data.monitor || {};
    const pos = data.position || 'FLAT';
    lastTradeMode = String(settings.mode || lastTradeMode || '').toLowerCase();
    const monHtml = monBadge(!!mon.running);
    const lagHtml = tipLagBadge(mon, s.trade_date || mon.last_bar || mon.last_message);
    const autoHtml = autoBadge(!!settings.auto_execute);
    const modeHtml = modeBadge(settings.mode);
    const stratHtml = strategyBadge(settings.signal_mode);
    const slHtml = spreadLevelsBadge(settings, data.spread_levels);
    const regimeZHtml = regimeZBadge(settings, data.regime);

    const dealer1mMode = String(data.bars_mode || '') === 'dealer_1m'
      || String(data.bars_mode || '') === 'dealer_weekend';
    const weekendMonitor = !!(data.weekend_monitor || dealer1mMode || nowMskParts().weekend);

    // Weekend display-only Z (server or client tip-style) — never into AUTO.
    // Prefer dealer bars whenever payload looks like dealer_1m / has dealer z_kind,
    // even if bars_mode lagged behind on a lite race.
    const barsLookDealer = (data.bars || []).some((b) => b && (
      b.z_kind === 'dealer_monitor'
      || (b.source && String(b.source).includes('dealer'))
    ));
    let chartBars = data.bars || [];
    if (dealer1mMode || barsLookDealer) {
      chartBars = data.bars || [];
    } else if (weekendMonitor) {
      // Раньше здесь было [] — раз в ~1 мин lite без dealer-source стирал график.
      // Держим последний хороший ряд, пока снова не придут дилерские бары.
      chartBars = lastGoodChartBars.length ? lastGoodChartBars.slice() : [];
    }
    // Пустой/короткий ответ (таймаут, кэш, дыра tip) — не затирать уже нарисованное.
    if ((!chartBars || chartBars.length < 5) && lastGoodChartBars.length >= 5) {
      chartBars = lastGoodChartBars.slice();
      data = {
        ...data,
        bars: chartBars,
        chart_preserved: true,
        partial: true,
      };
    }
    if ((dealer1mMode || weekendMonitor || barsLookDealer) && chartBars.length) {
      chartBars = attachDealerMonitorZClient(chartBars, data.bars_iss || []);
      data = { ...data, bars: chartBars };
      const lastMon = [...chartBars].reverse().find((b) => b && b.z != null);
      if (lastMon) {
        s.z = lastMon.z;
        s.z_kind = 'dealer_monitor';
      }
    } else if (!weekendMonitor && chartBars.length) {
      // tip1m: last candle/overlay must track live tip (monitor / mark), not sidecar Z.
      chartBars = alignTip1mBarsToLiveTip(chartBars, data, data.open || null);
      data = { ...data, bars: chartBars };
      const lastTip = chartBars[chartBars.length - 1];
      if (lastTip) {
        if (lastTip.z != null) s.z = lastTip.z;
        if (lastTip.spread != null) s.spread = lastTip.spread;
      }
    }

    const bars = data.bars || [];
    const barsForDist = (dealer1mMode && (data.bars_iss || []).length)
      ? data.bars_iss
      : bars;
    rebuildBarMetricDists(barsForDist, data.metric_dists);
    const regimeHtml = regimeBadge(bars);
    const cascadeHtml = cascadeBadge(bars);
    const zoneHtml = zoneMapBadge(bars);
    const dealer = data.dealer;
    const mskNow = nowMskParts();
    const spreadFrozen = (dealer && dealer.spread_live === false)
      || (mskNow.spreadLive === false && !mskNow.weekend);
    const liveSpreadBar = lastSpreadLiveBar(bars);
    const useDealerPx = !!(dealer && (dealer.ok || dealer.quotes_ok) && dealer.tatn != null && dealer.tatnp != null)
      && !spreadFrozen;
    let spVal = useDealerPx ? dealer.spread : s.spread;
    if (spreadFrozen && liveSpreadBar && liveSpreadBar.spread != null) {
      spVal = liveSpreadBar.spread;
    } else if (spreadFrozen && dealer && dealer.spread != null) {
      spVal = dealer.spread;
    }
    const spDisp = `${fmt(spVal)}%`;
    const tatnDisp = useDealerPx ? dealer.tatn : (spreadFrozen && dealer?.tatn != null ? dealer.tatn : s.tatn);
    const tatnpDisp = useDealerPx ? dealer.tatnp : (spreadFrozen && dealer?.tatnp != null ? dealer.tatnp : s.tatnp);
    const spreadFreezeHtml = spreadFrozenBadge(dealer, mskNow);

    // Шапка: только счётчики (плашки связи/рынка — в статус-баре)
    const distHint = metricDistMeta.degraded
      ? ` · dist:окно`
      : ` · dist:3г n=${metricDistMeta.n || '—'}`;
    $('tradeMeta').innerHTML =
      `${s.window_count || 0} баров · ${escapeHtml(s.source || '—')}${distHint}`;

    const dealerHtml = dealerStatusHtml(dealer, weekendMonitor);
    const corridorHtml = corridorStatusBadge(
      data.corridor || null,
      spVal,
      Array.isArray(data.bars_iss) ? data.bars_iss : barsForDist,
    );

    // Верхний ряд: 4 смысловые группы (система · данные · источник · рынок)
    const partialBanner = applyPartialBanner(data);
    const partialHtml = partialBanner
      ? `<span class="badge-quiet badge-partial">${escapeHtml(partialBanner)}</span>`
      : '';
    $('tradeStatus').innerHTML = [
      statusGroupHtml([monHtml, autoHtml, modeHtml, onlineBadge(!!s.online)]),
      statusGroupHtml([
        lagHtml,
        partialHtml,
        s.trade_date ? tickBadge(s.trade_date) : '',
      ]),
      statusGroupHtml([dealerHtml, stratHtml, spreadFreezeHtml]),
      statusGroupHtml([
        slHtml,
        zoneHtml,
        regimeHtml,
        cascadeHtml,
        corridorHtml,
        regimeZHtml,
      ]),
    ].filter(Boolean).join('');
    renderTradeRulesStatus(settings);

    // Рынок — у графиков: цены (зона/режим/каскад — в статус-баре)
    const stripSpLabel = useDealerPx
      ? (dealer1mMode ? 'Спред · дилер 1м' : 'Спред · дилер')
      : 'Спред';
    const lookbackDays = dealer?.lookback_days ?? data.dealer_lookback_days;
    const lookbackNote = dealer?.lookback_note || data.dealer_lookback_note || '';
    const lookbackCapped = !!(dealer?.lookback_capped || data.dealer_lookback_capped);
    $('tradeStrip').innerHTML = [
      metricStripBlock(stripSpLabel, 'spread', spVal, spDisp),
      `<span><b>TATN</b> ${fmt(tatnDisp)}</span>`,
      `<span><b>TATNP</b> ${fmt(tatnpDisp)}${useDealerPx ? ' <span class="badge-quiet">дилер</span>' : ''}</span>`,
      // Один блок глубины: без второй строки «дилер 1м: последние Nd».
      (dealer && dealer.bars_count != null)
        ? `<span class="badge-quiet" title="${escapeHtml(String(lookbackNote))}">${dealer.bars_count}×1м${
            lookbackDays != null ? ` · до ${lookbackDays}д` : ''
          }${lookbackCapped ? ' · чип урезан' : ''}</span>`
        : '',
    ].filter(Boolean).join(' ');

    const nzLevels = (data.spread_levels && data.spread_levels.levels)
      || {
        enter_narrow: Number(settings?.spread_enter_narrow ?? 3.2),
      };
    renderNarrowZoneMeter(
      spVal,
      nzLevels,
      Array.isArray(data.bars_iss) ? data.bars_iss : barsForDist,
    );
    renderCorridorMeter(
      data.corridor || null,
      spVal,
      Array.isArray(data.bars_iss) ? data.bars_iss : barsForDist,
    );

    // Hydrate once from server; never while user is editing (poll / late desk).
    const shouldHydrate = (hydrateForm || !formHydrated) && !formDirty && !paramsFocused();
    if (shouldHydrate && (settings.leverage != null || settings.spread_level_mode != null)) {
      hydrateParams(settings);
    } else if (!formHydrated && !formDirty) {
      const cached = loadCachedParamsLocal();
      if (cached) hydrateParams(cached);
    }

    renderMtlrCard(data.mtlr || null, settings);
    if (DESK_MTLR_UI_ENABLED && settings.mtlr_enabled !== false && (!data.mtlr || data.mtlr.warming)) {
      refreshMtlrShadow({ force: false }).catch(() => {});
    }

    const entry = settings.entry_z;
    const exitZ = settings.exit_z;
    const thEffDesk = effectiveThresholds(settings, data.regime, entry, exitZ);
    const guideEntry = thEffDesk.regimeOn ? thEffDesk.entry : entry;
    const guideExit = thEffDesk.regimeOn ? thEffDesk.exit : exitZ;
    const hasDealerMonSp = (bars || []).some((b) => b && b.spread != null
      && (b.source && String(b.source).includes('dealer')));
    const hasTip1mSp = (bars || []).some((b) => b && b.spread != null
      && !(b.source && String(b.source).includes('dealer')));
    const zEmptyWeekend = weekendMonitor && !hasTip1mSp && !hasDealerMonSp
      && !(bars || []).some((b) => b && b.spread != null);
    const th = applyThresholdVisuals(guideEntry, guideExit, {
      dealer1m: dealer1mMode || weekendMonitor,
      zEmpty: zEmptyWeekend,
      settings,
      spreadLevelsPayload: data.spread_levels,
    });

    renderOpen(data.open, {
      barsMode: data.bars_mode || '',
      bars: data.bars || [],
      settings,
    });
    syncTradeActionButtons(data);
    renderOpenStats(data.open_stats, data.open);
    const fundsPending = !!data.lite && !brokerHasTotals(data.broker)
      && !brokerHasTotals(lastGoodBroker);
    renderFunds(data.broker, { pending: fundsPending });
    const fundsTotal = brokerHasTotals(data.broker)
      ? data.broker.total_rub
      : (brokerHasTotals(lastGoodBroker) ? lastGoodBroker.total_rub : null);
    renderFundsAtOpen(data.open || null, fundsTotal);
    renderCloseForecast(data.close_forecast, {
      hasOpen: !!data.open,
      fundsTotal,
      open: data.open || null,
      depositRub: entryDepositRub(data.open, settings),
      settings,
    });
    renderChecklist(data);

    const mtlr = DESK_MTLR_UI_ENABLED ? (data.mtlr || null) : null;
    renderCharts(chartBars, th.entry, th.exitZ, data.open || null, data.closed || [], {
      dealer1m: dealer1mMode || barsLookDealer,
      weekendMonitor,
      zBars: data.bars_iss || null,
      spreadLevels: th.spreadLevels,
      spreadCuts: th.spreadCuts,
      lookbackDays: data.dealer_lookback_days
        || (data.dealer && data.dealer.lookback_days)
        || (data.dealer && data.dealer.want_days)
        || null,
      mtlrBars: DESK_MTLR_UI_ENABLED && mtlr && Array.isArray(mtlr.bars) ? mtlr.bars : [],
      mtlrLevels: DESK_MTLR_UI_ENABLED && mtlr && mtlr.levels ? mtlr.levels : null,
      mtlrOpen: DESK_MTLR_UI_ENABLED && mtlr && mtlr.open ? mtlr.open : null,
      mtlrClosed: DESK_MTLR_UI_ENABLED && mtlr && Array.isArray(mtlr.closed) ? mtlr.closed : [],
      corridor: data.corridor || null,
      closeForecast: data.close_forecast || lastCloseForecast,
      settings,
    });
  }

  async function ensureMonitorRunning(data) {
    const settings = data.settings || {};
    const mon = data.monitor || {};
    if (settings.monitor_running && !mon.running) {
      try {
        await api('/api/live/monitor/start', { method: 'POST' });
        return true;
      } catch (_) { /* keep status as-is */ }
    }
    return false;
  }

  function dealerHistBarCount(bars) {
    let n = 0;
    for (const b of bars || []) {
      if (b && String(b.source || '') === 'tinvest_dealer_1m') n += 1;
    }
    return n;
  }

  function spreadBarCount(bars) {
    let n = 0;
    for (const b of bars || []) {
      if (!b || b.spread == null) continue;
      if (Number.isFinite(Number(b.spread))) n += 1;
    }
    return n;
  }

  function rememberGoodChartBars(bars, data) {
    const n = dealerHistBarCount(bars);
    const spreadN = spreadBarCount(bars);
    const weekend = !!(data && (data.weekend_monitor
      || String(data.bars_mode || '').startsWith('dealer')
      || (typeof nowMskParts === 'function' && nowMskParts().weekend)));
    // ≥5 баров со спредом (не «длина массива» и не только tinvest_dealer_1m) —
    // иначе lastGood пуст / затирается мусором и следующий poll стирает график.
    if (n >= 5 || spreadN >= 5) {
      lastGoodChartBars = bars.slice();
      lastGoodDeskMeta = {
        bars_mode: data && data.bars_mode,
        weekend_monitor: !!(data && data.weekend_monitor) || weekend,
        partial: !!(data && data.partial),
      };
    }
  }

  function applyPartialBanner(data, extraMsg) {
    const parts = [];
    if (data && (data.partial || data.dealer_warming || data.tip1m_warming
      || (data.dealer && data.dealer.warming))) {
      parts.push('кэш / частичные данные');
    }
    if (data && data.tip1m_warming) parts.push('tip1m греется');
    if (data && data.dealer && data.dealer.from_cache) parts.push('дилер из кэша');
    if (extraMsg) parts.push(extraMsg);
    lastPartialBanner = parts.filter(Boolean).join(' · ');
    return lastPartialBanner;
  }

  async function refreshImpl({ hydrateForm = false, forceMoex = false, lite = false } = {}) {
    if (forceMoex) {
      await api(`/api/markets/refresh?days=${days}`, { method: 'POST', timeoutMs: 20000 });
    }
    const seq = ++deskFetchSeq;
    const liteQ = lite ? '&lite=1' : '';
    // Lite must stay snappy; full desk capped so UI never hangs forever.
    const timeoutMs = lite ? 8000 : 28000;
    const fetchDesk = () => api(`/api/trade/desk?days=${days}${liteQ}`, { timeoutMs });
    let data;
    try {
      data = await fetchDesk();
    } catch (e) {
      const msg = String((e && e.message) || e || '');
      if (/Таймаут|timeout|Failed to fetch|NetworkError/i.test(msg)) {
        await new Promise((r) => setTimeout(r, 900));
        if (seq !== deskFetchSeq) return null;
        try {
          data = await fetchDesk();
        } catch (e2) {
          if (seq !== deskFetchSeq) return null;
          // Desk wedged: still upgrade tip1m charts from sidecar (never paint M15).
          try {
            const stub = await ensureWeekdayTip1mBars({
              ok: true,
              days,
              lite: !!lite,
              bars_mode: 'iss_m15',
              bars: lastGoodChartBars.length ? lastGoodChartBars : [],
              bars_iss: [],
              settings: loadCachedParamsLocal() || {},
              summary: {},
              position: 'FLAT',
              partial: true,
              tip1m_warming: true,
            });
            if (stub && (stub.bars || []).length) {
              rememberGoodChartBars(stub.bars || [], stub);
              renderDesk(stub, { hydrateForm: false });
              const el = $('tradeStatus');
              if (el) {
                el.textContent = `Торговля · tip1m sidecar · ${msg.replace(/^Таймаут \d+мс: /, 'таймаут ')}`;
              }
              return stub;
            }
          } catch (_) { /* fall through */ }
          // Keep last good charts — never wipe to empty nonsense on timeout.
          if (lastGoodChartBars.length) {
            const el = $('tradeStatus');
            const banner = applyPartialBanner(
              { partial: true, dealer_warming: true },
              msg.replace(/^Таймаут \d+мс: /, 'таймаут '),
            );
            if (el) {
              el.textContent = `${el.textContent || 'Торговля'} · ${banner}`;
            }
            // Keep existing chart series — do not wipe to empty.
            return lastGoodDeskMeta;
          }
          throw e2;
        }
      } else {
        throw e;
      }
    }
    if (seq !== deskFetchSeq) return data;
    // Monitor start only on full desk (lite is first-paint charts)
    if (!lite && await ensureMonitorRunning(data)) {
      data = await api(`/api/trade/desk?days=${days}`, { timeoutMs: 20000 });
      if (seq !== deskFetchSeq) return data;
    }
    // Weekday: never paint iss_m15 under tip1m labels (sidecar / bars1m upgrade).
    // Keep chip days even if running API still coerces unknown windows → 7.
    data.days = days;
    data = await ensureWeekdayTip1mBars(data);
    if (seq !== deskFetchSeq) return data;
    // Короткий lite-ответ не должен стирать уже загруженный длинный период.
    // Это особенно заметно на 3М: после полного ответа следующий опрос мог
    // заменить 5 000 точек одной текущей свечой.
    if (lite && days > 1) {
      const needSpan = Math.min(days * 0.45, days - 0.4);
      const currentSpan = tip1mSpanDays(data.bars);
      const savedSpan = tip1mSpanDays(lastGoodChartBars);
      if (currentSpan < needSpan && savedSpan >= needSpan) {
        data = {
          ...data,
          bars: lastGoodChartBars,
          bars_mode: 'tip1m',
          chart_preserved_from_full: true,
          summary: {
            ...(data.summary || {}),
            window_count: lastGoodChartBars.length,
          },
        };
      }
    }
    if ((data.bars_mode === 'tip1m' || data.bars_mode === 'dealer_1m') && Array.isArray(data.bars)) {
      const thinned = thinDeskTip1mBars(data.bars);
      if (thinned.bars.length !== data.bars.length) {
        data = {
          ...data,
          bars: thinned.bars,
          chart_raw_count: thinned.rawCount,
          chart_step_min: thinned.stepMin,
          summary: {
            ...(data.summary || {}),
            chart_raw_count: thinned.rawCount,
            chart_step_min: thinned.stepMin,
          },
        };
      }
    }
    if (seq !== deskFetchSeq) return data;
    if (data.mtlr && Array.isArray(data.mtlr.bars) && DESK_MTLR_UI_ENABLED) {
      const mtlrBars = await ensureMtlrChartBars(data.mtlr.bars, days);
      data.mtlr = { ...data.mtlr, bars: mtlrBars };
    }
    if (seq !== deskFetchSeq) return data;
    if (pendingPeriodFitDays > 0) {
      const requiredSpan = pendingPeriodFitDays <= 1
        ? 0
        : Math.min(pendingPeriodFitDays * 0.45, pendingPeriodFitDays - 0.4);
      if (tip1mSpanDays(data.bars) >= requiredSpan) {
        forceFitContent = true;
        pendingPeriodFitDays = 0;
      }
    }
    rememberGoodChartBars(data.bars || [], data);
    // Late responses: never force-hydrate over newer in-flight work
    const hydrate = hydrateForm && seq === deskFetchSeq;
    renderDesk(data, { hydrateForm: hydrate });
    ensurePollInterval(data);
    return data;
  }

  async function refresh(opts = {}) {
    if (opts.lite && refreshWorkCount > 0) return lastGoodDeskMeta;
    refreshWorkCount += 1;
    try {
      return await refreshImpl(opts);
    } finally {
      refreshWorkCount = Math.max(0, refreshWorkCount - 1);
    }
  }

  async function saveEntryDeposit() {
    const deposit = readEntryDeposit();
    const addonDep = readNamedDeposit('tradeAddonDeposit');
    const extraDep = readNamedDeposit('tradeExtraDeposit');
    if (deposit == null || addonDep == null || extraDep == null) {
      setDepositStatus('Проверьте число', 'err');
      throw new Error('Некорректный депозит');
    }
    setDepositStatus('Сохранение…', 'pending');
    const btn = $('tradeBtnSaveDeposit');
    if (btn) btn.disabled = true;
    try {
      const res = await api('/api/portfolio/params', {
        method: 'POST',
        body: JSON.stringify({
          entry_deposit_rub: deposit,
          addon_deposit_rub: addonDep,
          extra_deposit_rub: extraDep,
        }),
      });
      const saved = res.settings || {
        entry_deposit_rub: deposit,
        addon_deposit_rub: addonDep,
        extra_deposit_rub: extraDep,
      };
      if ($('tradeEntryDeposit') && saved.entry_deposit_rub != null) {
        $('tradeEntryDeposit').value = String(saved.entry_deposit_rub);
      }
      if ($('tradeAddonDeposit') && saved.addon_deposit_rub != null) {
        $('tradeAddonDeposit').value = String(saved.addon_deposit_rub);
      }
      if ($('tradeExtraDeposit') && saved.extra_deposit_rub != null) {
        $('tradeExtraDeposit').value = String(saved.extra_deposit_rub);
      }
      const cached = loadCachedParamsLocal() || {};
      cacheParamsLocal({
        ...cached,
        ...saved,
        entry_deposit_rub: saved.entry_deposit_rub ?? deposit,
        addon_deposit_rub: saved.addon_deposit_rub ?? addonDep,
        extra_deposit_rub: saved.extra_deposit_rub ?? extraDep,
      });
      setDepositStatus('Сохранено', 'ok');
      setTimeout(() => {
        if ($('tradeDepositStatus')?.textContent === 'Сохранено') setDepositStatus('');
      }, 4000);
      return saved;
    } catch (e) {
      setDepositStatus('Ошибка', 'err');
      throw e;
    } finally {
      if (btn) btn.disabled = false;
    }
  }

  async function saveParams() {
    const params = readFormParams();
    if (params.leverage == null) {
      setParamsStatus('Проверьте числа', 'err');
      throw new Error('Некорректное плечо');
    }
    setParamsStatus('Сохранение…', 'pending');
    const btn = $('tradeBtnSaveParams');
    if (btn) btn.disabled = true;
    try {
      const res = await api('/api/portfolio/params', {
        method: 'POST',
        body: JSON.stringify(params),
      });
      const saved = res.settings || params;
      hydrateParams(saved, { force: true });
      renderTradeRulesStatus(saved);
      setParamsStatus('Сохранено', 'ok');
      // Desk poll updates MTM/chart without clobbering inputs
      refresh({ hydrateForm: false }).catch(() => {});
      return saved;
    } catch (e) {
      setParamsStatus('Ошибка сохранения', 'err');
      throw e;
    } finally {
      if (btn) btn.disabled = false;
    }
  }

  function resize() {
    const zEl = $('tradeZChart');
    const sEl = $('tradeSpreadChart');
    suppressRangeEvents = true;
    try {
      if (zChart && zEl) zChart.applyOptions({ width: zEl.clientWidth, height: zEl.clientHeight || 300 });
      if (spreadChart && sEl) spreadChart.applyOptions({ width: sEl.clientWidth, height: sEl.clientHeight || 150 });
      equalizePriceScales();
    } finally {
      scheduleEndSuppress();
    }
    if (pinnedRange) {
      requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          reassertPinnedRange();
          forceSyncAfterPaint();
        });
      });
    }
  }

  function spreadPaneHeadHeight(stackEl) {
    const heads = stackEl.querySelectorAll('.trade-pane-spread-wrap .pnl-head');
    let h = 0;
    heads.forEach((el) => { h += el.offsetHeight || 28; });
    return h || 28;
  }

  function loadSpreadChartHeight() {
    const saved = parseInt(localStorage.getItem(LS_SPREAD_PANE_HEIGHT) || '', 10);
    if (!Number.isFinite(saved)) return TRADE_SPREAD_DEFAULT;
    return Math.max(TRADE_SPREAD_MIN, saved);
  }

  function spreadChartHeightBounds(stackEl) {
    const headH = spreadPaneHeadHeight(stackEl);
    const zHead = stackEl.querySelector('.trade-pane-z-wrap .pnl-head');
    const zHeadH = zHead?.offsetHeight || 28;
    const total = stackEl.clientHeight - CHART_SPLITTER_HEIGHT - headH - zHeadH;
    const max = Math.max(TRADE_SPREAD_MIN, total - TRADE_Z_MIN);
    return { min: TRADE_SPREAD_MIN, max };
  }

  function applySpreadChartHeight(heightPx) {
    const stack = $('tradeChartStack');
    if (!stack) return TRADE_SPREAD_DEFAULT;
    if (stack.clientHeight <= 0) {
      const h = Math.round(Math.max(TRADE_SPREAD_MIN, heightPx));
      stack.style.setProperty('--trade-spread-chart-height', `${h}px`);
      return h;
    }
    const { min, max } = spreadChartHeightBounds(stack);
    const h = Math.round(Math.max(min, Math.min(max, heightPx)));
    stack.style.setProperty('--trade-spread-chart-height', `${h}px`);
    requestAnimationFrame(() => {
      requestAnimationFrame(resize);
    });
    return h;
  }

  function bindTradeChartVerticalSplit() {
    const divider = $('tradeSplitDividerH');
    const stack = $('tradeChartStack');
    const paneChart = $('tradeSpreadChart');
    if (!divider || !stack || !paneChart) return;

    applySpreadChartHeight(loadSpreadChartHeight());

    let dragging = false;
    let startY = 0;
    let startH = 0;

    const onMove = (e) => {
      if (!dragging) return;
      const dy = e.clientY - startY;
      applySpreadChartHeight(startH - dy);
    };

    const endDrag = () => {
      if (!dragging) return;
      dragging = false;
      divider.classList.remove('active');
      document.body.classList.remove('split-dragging-v');
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', endDrag);
      const h = parseInt(getComputedStyle(stack).getPropertyValue('--trade-spread-chart-height'), 10);
      if (h > 0) localStorage.setItem(LS_SPREAD_PANE_HEIGHT, String(h));
      resize();
    };

    divider.addEventListener('mousedown', (e) => {
      e.preventDefault();
      dragging = true;
      startY = e.clientY;
      startH = paneChart.offsetHeight;
      divider.classList.add('active');
      document.body.classList.add('split-dragging-v');
      document.addEventListener('mousemove', onMove);
      document.addEventListener('mouseup', endDrag);
    });

    divider.addEventListener('dblclick', () => {
      const h = applySpreadChartHeight(TRADE_SPREAD_DEFAULT);
      localStorage.setItem(LS_SPREAD_PANE_HEIGHT, String(h));
    });

    window.addEventListener('resize', () => {
      if (document.getElementById('app')?.dataset?.view !== 'trade') return;
      // Re-clamp preferred height; do not overwrite LS with early/hidden clamp.
      applySpreadChartHeight(loadSpreadChartHeight());
    });
  }

  function startPoll(ms) {
    stopPoll();
    if (ms != null && Number.isFinite(ms) && ms >= 2000) pollMs = ms;
    pollTimer = setInterval(() => {
      if (refreshWorkCount > 0) return;
      pollTick += 1;
      const doFull = (pollTick % POLL_FULL_EVERY) === 0;
      refresh({ lite: !doFull }).catch(() => {});
    }, pollMs);
  }

  function stopPoll() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  }

  function ensurePollInterval(data) {
    const want = (
      String(data?.bars_mode || '').startsWith('dealer')
      || data?.weekend_monitor
      || (data?.dealer && data.dealer.ok)
    )
      ? POLL_MS_DEALER_1M
      : POLL_MS_DEFAULT;
    if (want !== pollMs) startPoll(want);
  }

  function fmtMtlrSig(sig) {
    const s = String(sig || '');
    if (s === 'ENTER_LONG') return 'вход Long';
    if (s === 'ENTER_SHORT') return 'вход Short';
    if (s === 'EXIT_LONG') return 'выход Long';
    if (s === 'EXIT_SHORT') return 'выход Short';
    return s || '—';
  }

  function renderMtlrCard(mtlr, settings) {
    const card = $('tradeMtlrCard');
    const main = $('tradeMtlrMain');
    const meta = $('tradeMtlrMeta');
    const sigEl = $('tradeMtlrSig');
    const badge = $('tradeMtlrBadge');
    const openEl = $('tradeMtlrOpen');
    if (!card) return;
    const enabled = mtlr
      ? (mtlr.enabled !== false && !mtlr.disabled)
      : (settings ? settings.mtlr_enabled !== false : true);
    if (!enabled) {
      card.hidden = true;
      syncMtlrActionButtons(null);
      return;
    }
    card.hidden = false;
    const autoOn = !!(mtlr && mtlr.auto_execute)
      || !!(settings && settings.mtlr_auto_execute);
    if (badge) {
      badge.textContent = (mtlr && mtlr.badge_ru) || (autoOn ? 'AUTO · m15' : 'тень · m15');
    }
    if (!mtlr || mtlr.warming) {
      if (main) main.textContent = 'прогрев m15…';
      if (meta) {
        meta.textContent = `Short 8.9→8.4 · Long 3.2→4.3 · ${autoOn ? 'AUTO вкл' : 'AUTO выкл'}`;
      }
      if (openEl) {
        openEl.hidden = true;
        openEl.textContent = '';
      }
      if (sigEl) {
        sigEl.textContent = '';
        sigEl.classList.remove('is-edge');
      }
      syncMtlrActionButtons(mtlr || null);
      return;
    }
    if (mtlr.ok === false) {
      if (main) main.textContent = `ошибка: ${mtlr.error || '—'}`;
      if (meta) meta.textContent = mtlr.note_ru || '';
      if (openEl) {
        openEl.hidden = true;
        openEl.textContent = '';
      }
      if (sigEl) {
        sigEl.textContent = '';
        sigEl.classList.remove('is-edge');
      }
      syncMtlrActionButtons(mtlr);
      return;
    }
    const s = mtlr.spread != null ? Number(mtlr.spread).toFixed(2) : '—';
    const livePos = mtlr.live_position || (mtlr.open ? mtlr.open.direction : null);
    const pos = livePos || mtlr.position || 'FLAT';
    const reg = mtlr.regime_label_ru || mtlr.regime || '—';
    if (main) {
      main.textContent = `S ${s}% · ${pos} · ${reg}`;
    }
    const lv = mtlr.levels || {};
    const dep = mtlr.deposit_rub != null
      ? mtlr.deposit_rub
      : (settings && settings.mtlr_deposit_rub != null ? settings.mtlr_deposit_rub : 12000);
    if (meta) {
      meta.textContent = [
        `Short ${lv.enter_wide ?? 8.9}→${lv.exit_wide ?? 8.4}`,
        `Long ${lv.enter_narrow ?? 3.2}→${lv.exit_narrow ?? 4.3}`,
        autoOn ? 'AUTO вкл' : 'AUTO выкл',
        `деп ${Math.round(Number(dep) || 12000)}`,
        mtlr.last_bar ? `бар ${String(mtlr.last_bar).slice(0, 16)}` : '',
        (Number(mtlr.last_bar_lag_sec) >= 20 * 60
          ? `отставание ${Math.round(Number(mtlr.last_bar_lag_sec) / 60)} мин`
          : ''),
      ].filter(Boolean).join(' · ');
    }
    if (openEl) {
      if (mtlr.open) {
        const o = mtlr.open;
        const lots = o.quantity_lots != null ? o.quantity_lots : '—';
        const es = o.entry_spread != null ? Number(o.entry_spread).toFixed(2) : '—';
        openEl.hidden = false;
        openEl.textContent = `открыто ${o.direction || '—'} · ${lots}+${lots} лот · вход S ${es}%`
          + (o.entry_time ? ` · ${String(o.entry_time).slice(0, 16)}` : '');
      } else {
        openEl.hidden = true;
        openEl.textContent = '';
      }
    }
    if (sigEl) {
      if (mtlr.last_signal) {
        sigEl.textContent = `последний сигнал: ${fmtMtlrSig(mtlr.last_signal)}`
          + (mtlr.last_signal_bar ? ` @ ${String(mtlr.last_signal_bar).slice(0, 16)}` : '');
        sigEl.classList.add('is-edge');
      } else {
        sigEl.textContent = 'сигналов на истории нет / вне уровней';
        sigEl.classList.remove('is-edge');
      }
    }
    syncMtlrActionButtons(mtlr);
  }

  function syncMtlrActionButtons(mtlr) {
    const btnLong = $('tradeMtlrBtnLong');
    const btnShort = $('tradeMtlrBtnShort');
    const btnClose = $('tradeMtlrBtnClose');
    if (!btnLong && !btnShort && !btnClose) return;
    const liveOpen = !!(mtlr && mtlr.open);
    const livePos = (mtlr && (mtlr.live_position || (mtlr.open && mtlr.open.direction))) || 'FLAT';
    const flat = !liveOpen && String(livePos).toUpperCase() === 'FLAT';
    const basketOpen = mtlr && mtlr.basket_open != null ? Number(mtlr.basket_open) : 0;
    const basketMax = mtlr && mtlr.basket_max != null ? Number(mtlr.basket_max) : 2;
    const canOpen = flat && basketOpen < basketMax;
    let openTitle = 'Ручной Long Мечел (брокер, отдельно от Татнефть)';
    if (!flat) openTitle = 'Уже есть открытый Мечел';
    else if (basketOpen >= basketMax) openTitle = `Корзина заполнена (${basketOpen}/${basketMax})`;
    if (btnLong) {
      btnLong.disabled = !canOpen;
      btnLong.title = openTitle;
    }
    if (btnShort) {
      btnShort.disabled = !canOpen;
      btnShort.title = !flat
        ? 'Уже есть открытый Мечел'
        : (basketOpen >= basketMax
          ? `Корзина заполнена (${basketOpen}/${basketMax})`
          : 'Ручной Short Мечел');
    }
    if (btnClose) {
      btnClose.disabled = !liveOpen;
      btnClose.title = liveOpen
        ? 'Закрыть спред Мечел на брокере'
        : 'Нет открытого Мечела';
    }
  }

  async function refreshMtlrShadow({ force = false } = {}) {
    try {
      const q = force ? `?force=1&days=${days}` : `?days=${days}`;
      const data = await api(`/api/live/mtlr/shadow${q}`);
      renderMtlrCard(data || {}, null);
      if (data && Array.isArray(data.bars) && data.bars.length) {
        lastMtlrBars = await ensureMtlrChartBars(data.bars.slice(), days);
        if (data.levels) lastMtlrLevels = data.levels;
        // Soft re-paint bottom pane without waiting for next desk poll.
        if (zSeries && spreadSeries) {
          const mtlrLv = data.levels || lastMtlrLevels || {
            enter_wide: 8.9, exit_wide: 8.4, enter_narrow: 3.2, exit_narrow: 4.3,
          };
          const pts = buildSpreadM15ChartSeries(lastMtlrBars);
          try {
            suppressRangeEvents = true;
            if (!spreadSeriesIsLine) spreadSeries.setData(pts);
            else {
              spreadSeries.setData(pts.map((c) => ({ time: c.time, value: c.close })));
            }
            updateSpreadRegimeBands(pts, mtlrLv);
            setSpreadThresholdLines(mtlrLv);
            spPriceByTime = new Map(pts.map((c) => [c.time, c.close]));
            updateChartPaneLabels(chartDealer1m, {
              spreadLevels: null,
              mtlrLevels: mtlrLv,
              mtlrEmpty: pts.length === 0,
            });
            syncBottomPaneToTopTime();
            scheduleEndSuppress();
          } catch (_) {
            suppressRangeEvents = false;
            /* next desk refresh will paint */
          }
        }
      }
      return data;
    } catch (_) {
      return null;
    }
  }

  function onShow() {
    // Сначала сеть desk (lite) — не ждать Мечел.
    if (!formDirty) formHydrated = false;
    ensureCharts();
    applySpreadChartHeight(loadSpreadChartHeight());
    restoreTradeScrolls();
    resize();
    // Instant LS thresholds; params also arrive with desk.settings
    const cached = loadCachedParamsLocal();
    if (cached && !formDirty) hydrateParams(cached, { force: true });
    // Повторный вызов onShow не должен запускать ещё одну цепочку загрузки,
    // способную отменить уже идущий полный запрос выбранного периода.
    const alreadyLoading = !!(pollTimer || refreshWorkCount > 0);
    // Do NOT wait on /status → desk (was ~10s waterfall via TInvest×2).
    // 1) lite desk: bars/markers/settings without broker (~markets time)
    // 2) full desk: broker/funds + dealer in background (never block lite paint)
    hydrateParamsFromServer().catch(() => {});
    if (!alreadyLoading) {
      const bootDays = days;
      const bootLite = () => refresh({ hydrateForm: !formDirty, lite: true });
      bootLite()
        .catch((e) => {
          // Watchdog hard-restart mid-fetch → native "Failed to fetch"; one retry.
          const msg = String((e && e.message) || e || '');
          if (/Failed to fetch|NetworkError|fetch|Таймаут|timeout/i.test(msg)) {
            if (lastGoodChartBars.length) {
              const el = $('tradeStatus');
              if (el) {
                el.textContent = `Ошибка: ${msg} · кэш / частичные данные`;
              }
            }
            return new Promise((r) => setTimeout(r, 1200)).then(() => bootLite());
          }
          throw e;
        })
        .then(() => {
          restoreTradeScrolls();
          // Пользователь уже выбрал другой период: его полную загрузку не
          // перебиваем завершающим запросом начального семидневного запуска.
          if (days !== bootDays) return null;
          startPoll();
          // Full desk is best-effort; lite already painted charts/checklist.
          return refresh({ hydrateForm: false, lite: false }).catch((e) => {
            const el = $('tradeStatus');
            if (el && /Таймаут|timeout|Failed to fetch/i.test(String(e && e.message))) {
              el.textContent = (el.textContent || '') + ' · дилер/брокер: таймаут (частичные данные)';
            }
          });
        })
        .catch((e) => {
          $('tradeStatus').textContent = `Ошибка: ${e.message}`
            + (lastGoodChartBars.length ? ' · кэш / частичные данные' : '');
          startPoll();
        });
    }
    // Тяжёлые боковые панели — после первого кадра, не конкурируют с desk.
    setTimeout(() => {
      refreshMtlrShadow({ force: false }).catch(() => {});
    }, 0);
    requestAnimationFrame(() => {
      resize();
      restoreTradeScrolls();
      requestAnimationFrame(resize);
    });
    // Phone/WebView: layout settles after paint / keyboard / orientation
    setTimeout(() => { resize(); restoreTradeScrolls(); }, 120);
    setTimeout(() => { resize(); restoreTradeScrolls(); }, 400);
  }

  function onHide() {
    stopPoll();
    const active = activeTradeChartFullscreen();
    if (active) setTradeChartFullscreen(active, false);
  }

  function bind() {
    document.querySelectorAll('#tradePeriodChips .chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('#tradePeriodChips .chip').forEach((b) => b.classList.remove('active'));
        btn.classList.add('active');
        days = parseInt(btn.dataset.days, 10) || 7;
        forceFitContent = true;
        pendingPeriodFitDays = days;
        clearPinState();
        stopPoll();
        refresh()
          .catch((e) => alert(e.message))
          .finally(() => startPoll());
      });
    });
    $('tradeBtnRefresh')?.addEventListener('click', () => {
      refresh({ forceMoex: true }).catch((e) => alert(e.message));
    });
    $('tradeBtnSaveParams')?.addEventListener('click', () => {
      saveParams().catch((e) => alert(e.message));
    });
    $('tradeBtnSaveDeposit')?.addEventListener('click', () => {
      saveEntryDeposit().catch((e) => alert(e.message));
    });
    $('tradeEntryDeposit')?.addEventListener('keydown', (ev) => {
      if (ev.key === 'Enter') {
        ev.preventDefault();
        saveEntryDeposit().catch((e) => alert(e.message));
      }
    });
    ['tradeAddonDeposit', 'tradeExtraDeposit'].forEach((id) => {
      $(id)?.addEventListener('keydown', (ev) => {
        if (ev.key === 'Enter') {
          ev.preventDefault();
          saveEntryDeposit().catch((e) => alert(e.message));
        }
      });
    });
    ['tradeLeverage', 'tradeTpSel', 'tradeSpreadEnterNarrow', 'tradeSpreadExitNarrow',
      'tradeSpreadEnterWide', 'tradeSpreadExitWide'].forEach((id) => {
      const el = $(id);
      if (!el) return;
      el.addEventListener('input', markParamsDirty);
      el.addEventListener('change', markParamsDirty);
      el.addEventListener('keydown', (ev) => {
        if (ev.key === 'Enter') {
          ev.preventDefault();
          saveParams().catch((e) => alert(e.message));
        }
      });
    });
    $('tradeAutoExec')?.addEventListener('change', () => {
      markParamsDirty();
      setParamsStatus('Сохранение…', 'pending');
      api('/api/portfolio/params', {
        method: 'POST',
        body: JSON.stringify({ auto_execute: $('tradeAutoExec').checked }),
      }).then((res) => {
        formDirty = false;
        formHydrated = true;
        if (res.settings) hydrateParams(res.settings, { force: true });
        setParamsStatus('Сохранено', 'ok');
        return refresh({ hydrateForm: false });
      }).catch((e) => {
        setParamsStatus('Ошибка сохранения', 'err');
        alert(e.message);
      });
    });
    $('tradeMtlrEnabled')?.addEventListener('change', () => {
      markParamsDirty();
      setParamsStatus('Сохранение…', 'pending');
      api('/api/portfolio/params', {
        method: 'POST',
        body: JSON.stringify({ mtlr_enabled: $('tradeMtlrEnabled').checked }),
      }).then((res) => {
        formDirty = false;
        formHydrated = true;
        if (res.settings) hydrateParams(res.settings, { force: true });
        setParamsStatus('Сохранено', 'ok');
        renderMtlrCard(null, res.settings || { mtlr_enabled: $('tradeMtlrEnabled').checked });
        if ($('tradeMtlrEnabled').checked) {
          return refreshMtlrShadow({ force: true });
        }
        return null;
      }).catch((e) => {
        setParamsStatus('Ошибка сохранения', 'err');
        alert(e.message);
      });
    });
    $('tradeMtlrAuto')?.addEventListener('change', () => {
      markParamsDirty();
      setParamsStatus('Сохранение…', 'pending');
      api('/api/portfolio/params', {
        method: 'POST',
        body: JSON.stringify({ mtlr_auto_execute: !!$('tradeMtlrAuto').checked }),
      }).then((res) => {
        formDirty = false;
        formHydrated = true;
        if (res.settings) hydrateParams(res.settings, { force: true });
        setParamsStatus('Сохранено', 'ok');
        renderMtlrCard(null, res.settings || {});
        return refreshMtlrShadow({ force: true });
      }).catch((e) => {
        setParamsStatus('Ошибка сохранения', 'err');
        alert(e.message);
      });
    });
    $('tradeMtlrDeposit')?.addEventListener('change', () => {
      const dep = readMtlrDeposit();
      if (dep == null) return;
      markParamsDirty();
      setParamsStatus('Сохранение…', 'pending');
      api('/api/portfolio/params', {
        method: 'POST',
        body: JSON.stringify({ mtlr_deposit_rub: dep }),
      }).then((res) => {
        formDirty = false;
        formHydrated = true;
        if (res.settings) hydrateParams(res.settings, { force: true });
        setParamsStatus('Сохранено', 'ok');
      }).catch((e) => {
        setParamsStatus('Ошибка сохранения', 'err');
        alert(e.message);
      });
    });
    let tradeCommentIdleTimer = null;
    let tradeCommentBound = false;
    let tradeCommentCtx = { kind: 'entry', tradeId: null };
    const TRADE_COMMENT_IDLE_MS = 10_000;

    function hideTradeCommentPopup() {
      if (tradeCommentIdleTimer) {
        clearTimeout(tradeCommentIdleTimer);
        tradeCommentIdleTimer = null;
      }
      const pop = $('tradeCommentPopup');
      const input = $('tradeCommentInput');
      if (pop) {
        pop.classList.add('hidden');
        pop.hidden = true;
      }
      if (input) input.value = '';
    }

    function resetTradeCommentIdle() {
      if (tradeCommentIdleTimer) clearTimeout(tradeCommentIdleTimer);
      tradeCommentIdleTimer = setTimeout(() => {
        const input = $('tradeCommentInput');
        const text = input ? String(input.value || '').trim() : '';
        if (text) {
          saveTradeComment({ dismiss: true }).catch(() => hideTradeCommentPopup());
        } else {
          hideTradeCommentPopup();
        }
      }, TRADE_COMMENT_IDLE_MS);
    }

    async function saveTradeComment({ dismiss = true } = {}) {
      const input = $('tradeCommentInput');
      const text = input ? String(input.value || '').trim() : '';
      const kind = tradeCommentCtx.kind === 'close' ? 'close' : 'entry';
      const body = { kind, comment: text };
      if (tradeCommentCtx.tradeId != null) body.trade_id = tradeCommentCtx.tradeId;
      if (text) {
        try {
          await api('/api/live/trade/comment', {
            method: 'POST',
            body: JSON.stringify(body),
          });
        } catch (e) {
          if (!dismiss) throw e;
          console.warn('trade comment', e);
        }
      }
      if (dismiss) hideTradeCommentPopup();
      if (text) {
        try { await refresh({ hydrateForm: false }); } catch (_) { /* ignore */ }
      }
    }

    function showTradeCommentPopup({ kind = 'entry', tradeId = null, placeholder = '' } = {}) {
      const pop = $('tradeCommentPopup');
      const input = $('tradeCommentInput');
      const title = $('tradeCommentTitle');
      if (!pop || !input) return;
      tradeCommentCtx = { kind: kind === 'close' ? 'close' : 'entry', tradeId };
      if (title) {
        title.textContent = kind === 'close' ? 'Комментарий к закрытию' : 'Комментарий';
      }
      input.placeholder = placeholder
        || (kind === 'close' ? 'почему закрыл…' : 'почему открыл…');
      input.value = '';
      pop.classList.remove('hidden');
      pop.hidden = false;
      resetTradeCommentIdle();
      requestAnimationFrame(() => {
        try { input.focus(); } catch (_) { /* ignore */ }
      });
    }

    function bindTradeCommentPopup() {
      if (tradeCommentBound) return;
      tradeCommentBound = true;
      const input = $('tradeCommentInput');
      const saveBtn = $('tradeCommentSave');
      if (input) {
        const bump = () => resetTradeCommentIdle();
        input.addEventListener('input', bump);
        input.addEventListener('keydown', (ev) => {
          if (ev.key === 'Enter' && !ev.shiftKey) {
            ev.preventDefault();
            saveTradeComment({ dismiss: true }).catch((e) => alert(e.message));
            return;
          }
          bump();
        });
        input.addEventListener('focus', bump);
      }
      saveBtn?.addEventListener('click', () => {
        saveTradeComment({ dismiss: true }).catch((e) => alert(e.message));
      });
    }

    const manualTrade = async (side) => {
      const warn = lastTradeMode === 'prod'
        ? `Боевой счёт: открыть ${side}?`
        : `Открыть ${side} (ручной вход)?`;
      if (!window.confirm(warn)) return;
      const btn = side === 'LONG' ? $('tradeBtnLong') : $('tradeBtnShort');
      if (btn) btn.disabled = true;
      let openedId = null;
      try {
        const res = await api('/api/live/trade', { method: 'POST', body: JSON.stringify({ side }) });
        const tid = res && res.trade_id != null ? Number(res.trade_id) : null;
        openedId = Number.isFinite(tid) ? tid : null;
        await refresh();
      } finally {
        // состояние кнопок вернёт refresh / applyTradeModeUi
        try { await refresh(); } catch (_) { if (btn) btn.disabled = false; }
      }
      showTradeCommentPopup({
        kind: 'entry',
        tradeId: openedId,
        placeholder: side === 'LONG' ? 'почему открыл Long…' : 'почему открыл Short…',
      });
    };
    $('tradeBtnLong')?.addEventListener('click', () => {
      manualTrade('LONG').catch((e) => alert(e.message));
    });
    $('tradeBtnShort')?.addEventListener('click', () => {
      manualTrade('SHORT').catch((e) => alert(e.message));
    });
    $('tradeBtnClose')?.addEventListener('click', async () => {
      if (!window.confirm('Закрыть открытый спрэд на брокере?')) return;
      try {
        const res = await api('/api/portfolio/close', { method: 'POST' });
        await refresh();
        const closed = res && res.closed;
        const tid = closed && closed.id != null ? Number(closed.id) : null;
        showTradeCommentPopup({
          kind: 'close',
          tradeId: Number.isFinite(tid) ? tid : null,
          placeholder: 'почему закрыл…',
        });
      } catch (e) {
        alert(e.message);
      }
    });
    bindTradeCommentPopup();
    const manualMtlrTrade = async (side) => {
      const label = side === 'CLOSE' ? 'закрыть Мечел' : `Мечел ${side}`;
      const warn = lastTradeMode === 'prod'
        ? `Боевой счёт: ${label}?`
        : `${label} (ручной)?`;
      if (!window.confirm(warn)) return;
      await api('/api/live/mtlr/trade', { method: 'POST', body: JSON.stringify({ side }) });
      await Promise.all([
        refresh({ hydrateForm: false }).catch(() => {}),
        refreshMtlrShadow({ force: true }).catch(() => {}),
      ]);
    };
    $('tradeMtlrBtnLong')?.addEventListener('click', () => {
      manualMtlrTrade('LONG').catch((e) => alert(e.message));
    });
    $('tradeMtlrBtnShort')?.addEventListener('click', () => {
      manualMtlrTrade('SHORT').catch((e) => alert(e.message));
    });
    $('tradeMtlrBtnClose')?.addEventListener('click', () => {
      manualMtlrTrade('CLOSE').catch((e) => alert(e.message));
    });
    window.addEventListener('resize', () => {
      if (document.getElementById('app')?.dataset?.view === 'trade') resize();
    });
    bindTradeChartVerticalSplit();
    bindTradeScrolls();
    bindOpenStatsCollapse();
    bindMidTabs();
    bindSideTabs();
    bindMidPanelCollapse();
    bindTradeChartFullscreen();
    bindTradeChartKeyboardNav();
    const tipRoots = [$('tradeStrip')].filter(Boolean);
    tipRoots.forEach((root) => {
      root.addEventListener('pointerover', (e) => {
        const cell = e.target.closest('.metric-hover');
        if (!cell || !root.contains(cell)) return;
        if (metricTipHideTimer) {
          clearTimeout(metricTipHideTimer);
          metricTipHideTimer = null;
        }
        showBarMetricTip(cell);
      });
      root.addEventListener('pointerout', (e) => {
        const cell = e.target.closest('.metric-hover');
        if (!cell || !root.contains(cell)) return;
        const related = e.relatedTarget;
        if (related && (cell.contains(related) || related.closest?.('#tradeLiveMetricTip'))) return;
        metricTipHideTimer = setTimeout(hideMetricTip, 80);
      });
    });
    // Prefetch порогов сразу (Ctrl+F5 → не ждать открытия вкладки / desk)
    const cached = loadCachedParamsLocal();
    if (cached) hydrateParams(cached, { force: true });
    hydrateParamsFromServer().catch(() => {});
  }

  window.MoexTrade = { onShow, onHide, refresh, bind, resize };
  // alias for old name
  window.MoexMarkets = window.MoexTrade;
  /** Хуки для Playwright ui_tests (не для прод-логики). */
  window.__deskUiTest = {
    renderDesk,
    rememberGoodChartBars,
    lastGoodBarCount: () => lastGoodChartBars.length,
    lastPaintZCount: () => (lastPaintZData && lastPaintZData.length) || 0,
    emptySkipCount: () => Number(window.__deskChartEmptySkip || 0),
  };

  document.addEventListener('DOMContentLoaded', bind);
})();
