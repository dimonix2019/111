/** Broker PnL, funds, close forecast — split from trade.js */
(function (global) {
  'use strict';
  function D() { return global.__TradeDesk.deps; }
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
    text.textContent = `до колла: ${freePrefix}${D().fmt(freeAbs, 0)} ₽ (${pctStr}%)`;
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
      if (pending && D().lastGoodBroker && D().brokerHasTotals(D().lastGoodBroker)) {
        broker = D().lastGoodBroker;
      } else {
        totalEl.textContent = pending ? '…' : '—';
        cashEl.textContent = pending ? 'брокер…' : 'нет токена — вкладка «Счёт»';
        D().renderMarginHeadroom(null);
        return;
      }
    }
    if (broker.error) {
      box.classList.add('is-error');
      totalEl.textContent = '—';
      cashEl.textContent = D().modeLabel(broker.mode);
      if (brokerEl) {
        brokerEl.hidden = false;
        brokerEl.textContent = `Брокер: ${D().formatBrokerError(broker.error)}`;
      }
      D().renderMarginHeadroom(null);
      return;
    }
    if (broker.mode === 'prod') box.classList.add('is-prod');
    const mode = modeLabel(broker.mode);
    const total = Number(broker.total_rub);
    const cash = Number(broker.cash_rub);
    totalEl.textContent = Number.isFinite(total) ? `${D().fmt(total, 0)} ₽` : '—';
    // Не дублировать ту же сумму в cash, если она ≈ total
    if (Number.isFinite(cash) && Number.isFinite(total) && Math.abs(cash - total) > 1) {
      cashEl.textContent = `${mode} · cash ${D().fmt(cash, 0)} ₽`;
    } else {
      cashEl.textContent = mode;
    }
    D().renderMarginHeadroom(broker);
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
    el.textContent = `До ${D().fmt(atOpen, 0)} ₽`;
    el.title = 'Сумма на счету на входе (база Чист.)';
  }

  function fmtRubPlain(n, digits = 0) {
    if (n == null || !Number.isFinite(Number(n))) return '—';
    return `${D().fmt(Number(n), digits)} ₽`;
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
      ?? D().readEntryDeposit()
      ?? 10000,
    );
    return Number.isFinite(dep) && dep > 0 ? dep : 10000;
  }

  /** @deprecated kept for any external callers — prefer entryDepositRub for %. */
  function stakeNotionalRub(open, settings) {
    return D().entryDepositRub(open, settings);
  }

  /** Prod desk: API mark already has broker expectedYield — never recompute spread MTM for display. */
  function isBrokerPnlMark(mark) {
    if (!mark || typeof mark !== 'object') return false;
    if (mark.pnl_source === 'tinkoff_expected_yield') return true;
    const y = Number(mark.expected_yield_rub);
    return Number.isFinite(y);
  }

  function openProfitRub(open) {
    const mark = (open && open.mark) || {};
    // PnL sources policy: broker expectedYield from API — no local MTM fallback.
    if (isBrokerPnlMark(mark)) {
      const yieldRub = Number(mark.expected_yield_rub ?? mark.unrealized_pnl_rub);
      if (Number.isFinite(yieldRub)) return yieldRub;
    }
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
      `Прибыль ≥${PROFIT_ALERT_PCT}% от вложения ${D().fmt(deposit, 0)} ₽` +
      ` · сейчас ${D().fmtRub(profit)} (${D().fmtStakePct(pct)})`;
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
    if (D().profitToastTimer) clearTimeout(D().profitToastTimer);
    D().profitToastTimer = setTimeout(() => {
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
          : D().entryDepositRub(open, settings));
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
      return D().entryDepositRub(open, settings);
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
      D().lastCloseForecast = null;
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
      D().lastCloseForecast = fc;
    } else if (D().lastCloseForecast && D().lastCloseForecast.forecast_total_rub != null) {
      fc = D().lastCloseForecast;
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
      main.textContent = `Прогноз после закрытия ≈ ${D().fmt(forecastRub, 0)} ₽`;
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
    const brokerYield = Number(
      fc?.expected_yield_rub
      ?? open?.mark?.expected_yield_rub
      ?? ((open?.mark?.pnl_source === 'tinkoff_expected_yield')
        ? open?.mark?.unrealized_pnl_rub
        : null),
    );
    if (Number.isFinite(brokerYield)) {
      // Same number as APK / T‑Invest: Σ expectedYield, not forecast−«До».
      primaryDelta = Math.round(brokerYield);
    } else if (forecastRub != null && equityOpenRub != null) {
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
          text += ` (${D().fmtStakePct((primaryDelta / pctBase) * 100)} от вложения ${D().fmt(pctBase, 0)} ₽)`;
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
          text += ` (${D().fmtStakePct((pnlRound / dep) * 100)} от вложения ${D().fmt(dep, 0)} ₽)`;
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
      bits.push(`к текущим средствам ≈ ${D().fmtRub(vsNow)} (комиссии/закрытие)`);
    }
    if (fc && fc.exit_commission_rub != null) {
      bits.push(`комиссии ≈ ${D().fmtRubPlain(fc.exit_commission_rub, 0)}`);
    }
    if (fc && fc.overnight_rub != null) {
      const d = fc.overnight_days != null ? ` · ${fc.overnight_days}д` : '';
      bits.push(`overnight ≈ ${D().fmtRubPlain(fc.overnight_rub, 0)}${d}`);
    }
    if (fc && fc.vs_mid_rub != null) {
      const v = Number(fc.vs_mid_rub);
      const sign = v > 0 ? '+' : '';
      bits.push(`vs mid ${sign}${D().fmt(v, 0)} ₽`);
    }
    if (fc && fc.note) bits.push(fc.note);
    sub.textContent = bits.join(' · ');
  }


  function fmtRubPlain(n, digits = 0) {
    if (n == null || !Number.isFinite(Number(n))) return '—';
    return `${D().fmt(Number(n), digits)} ₽`;
  }

  function fmtStakePct(pct) {
    if (!Number.isFinite(pct)) return '';
    const abs = Math.abs(pct);
    const digits = abs >= 1 ? 1 : 2;
    return `${pct >= 0 ? '+' : '−'}${abs.toFixed(digits)}%`;
  }

  function entryDepositRub(open, settings) {
    const fromOpen = Number(open?.entry_deposit_rub);
    if (Number.isFinite(fromOpen) && fromOpen > 0) return fromOpen;
    const dep = Number(
      settings?.entry_deposit_rub
      ?? D().readEntryDeposit()
      ?? 10000,
    );
    return Number.isFinite(dep) && dep > 0 ? dep : 10000;
  }

  function stakeNotionalRub(open, settings) {
    return D().entryDepositRub(open, settings);
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

  function equityAtOpenRub(open, {
    fundsTotal = null,
    forecastEquityOpen = null,
  } = {}) {

  function exitLevelPotential(fc, open, {
    depositRub = null,
    settings = null,
  } = {}) {

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
  global.__TradeDesk = global.__TradeDesk || { deps: {} };
  global.__TradeDesk.pnl = {
    formatBrokerError: formatBrokerError,
    marginHeadroomFromBroker: marginHeadroomFromBroker,
    renderMarginHeadroom: renderMarginHeadroom,
    renderFunds: renderFunds,
    renderFundsAtOpen: renderFundsAtOpen,
    isBrokerPnlMark: isBrokerPnlMark,
    openProfitRub: openProfitRub,
    renderCloseForecast: renderCloseForecast,
    entryDepositRub: entryDepositRub,
    maybeFireProfitAlert: maybeFireProfitAlert
  };
})(typeof window !== 'undefined' ? window : globalThis);
