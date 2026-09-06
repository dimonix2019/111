/** Счёт — T‑Invest credentials + monitor + rare manual entry */
(function () {
  const LS_VIEW = 'moexReplay.viewMode';
  let pollTimer = null;
  let formHydrated = false;

  const $ = (id) => document.getElementById(id);

  const VIEW_MIGRATE = {
    markets: 'trade',
    portfolio: 'history',
    live: 'account',
  };

  async function api(path, opts) {
    const res = await fetch(path, {
      headers: { 'Content-Type': 'application/json', ...(opts && opts.headers) },
      ...opts,
    });
    const text = await res.text();
    let data;
    try { data = text ? JSON.parse(text) : {}; } catch { data = { detail: text }; }
    if (!res.ok) {
      const msg = data.detail || data.message || text || res.statusText;
      throw new Error(typeof msg === 'string' ? msg : JSON.stringify(msg));
    }
    return data;
  }

  function fmt(n, d = 2) {
    if (n == null || Number.isNaN(n)) return '—';
    return Number(n).toLocaleString('ru-RU', { maximumFractionDigits: d });
  }

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function monBadge(running) {
    return running
      ? '<span class="badge-mon-on"><span class="badge-quiet">монитор</span> ON</span>'
      : '<span class="badge-mon-off"><span class="badge-quiet">монитор</span> OFF</span>';
  }

  function modeBadge(mode) {
    return mode === 'prod'
      ? '<span class="badge-mode-prod">Prod</span>'
      : '<span class="badge-mode-sandbox">Sandbox</span>';
  }

  function sessionOrdersBlockReason() {
    if (typeof window.MoexSessionGate?.sessionOrdersBlockReason === 'function') {
      return window.MoexSessionGate.sessionOrdersBlockReason();
    }
    return null;
  }

  function currentMode() {
    return $('liveMode').value === 'prod' ? 'prod' : 'sandbox';
  }

  function syncLiveTradeButtons() {
    const block = sessionOrdersBlockReason();
    const btnLong = $('liveBtnLong');
    const btnShort = $('liveBtnShort');
    if (btnLong) {
      btnLong.disabled = !!block;
      btnLong.title = block || 'Ручной Long на брокере';
    }
    if (btnShort) {
      btnShort.disabled = !!block;
      btnShort.title = block || 'Ручной Short на брокере';
    }
    let hint = $('liveSessionGateHint');
    if (!hint && (btnShort || btnLong)) {
      const row = (btnShort || btnLong).parentElement;
      if (row) {
        hint = document.createElement('div');
        hint.id = 'liveSessionGateHint';
        hint.className = 'meta live-session-gate-hint';
        hint.setAttribute('role', 'status');
        row.insertAdjacentElement('afterend', hint);
      }
    }
    if (hint) {
      if (block) {
        hint.hidden = false;
        hint.textContent = block;
      } else {
        hint.hidden = true;
        hint.textContent = '';
      }
    }
  }

  function syncFormFromSettings(s) {
    if (s.mode) $('liveMode').value = s.mode === 'prod' ? 'prod' : 'sandbox';
    if (s.account_id != null) $('liveAccountId').value = s.account_id || '';
  }

  function modeLabel(mode) {
    return mode === 'prod' ? 'Боевой (Prod)' : 'Песочница';
  }

  function renderFunds(broker, settingsMode) {
    const box = $('liveFundsBox');
    const modeEl = $('liveFundsMode');
    const totalEl = $('liveFundsTotal');
    const cashEl = $('liveFundsCash');
    const portfolioEl = $('livePortfolioBox');
    if (!box || !totalEl || !cashEl) return;

    box.classList.remove('is-prod', 'is-error');
    const mode = broker?.mode || settingsMode;
    if (modeEl) modeEl.textContent = mode ? modeLabel(mode) : 'Счёт не выбран';

    if (!broker) {
      totalEl.textContent = '—';
      cashEl.textContent = 'нужны токен и accountId';
      if (portfolioEl) portfolioEl.textContent = 'Портфель: —';
      return;
    }
    if (broker.error) {
      box.classList.add('is-error');
      totalEl.textContent = broker.error;
      cashEl.textContent = mode ? modeLabel(mode) : '';
      if (portfolioEl) portfolioEl.textContent = `Портфель: ${broker.error}`;
      return;
    }
    if (mode === 'prod') box.classList.add('is-prod');
    totalEl.textContent = `${fmt(broker.total_rub, 0)} ₽`;
    cashEl.textContent = `cash ${fmt(broker.cash_rub, 0)} ₽`;
    if (portfolioEl) {
      portfolioEl.textContent =
        `Портфель [${mode}]: ${fmt(broker.total_rub, 0)} ₽ · cash ${fmt(broker.cash_rub, 0)} ₽`;
    }
  }

  function renderStatus(data, { hydrateForm = false } = {}) {
    const s = data.settings || {};
    const m = data.market || {};
    const mon = data.monitor || {};
    const modeHtml = modeBadge(s.mode);
    const monHtml = monBadge(!!mon.running);
    const acct = s.account_id ? `${s.account_id.slice(0, 8)}…` : 'нет account';
    const b = data.broker;
    const fundsShort = (!b || b.error)
      ? ''
      : ` · <b>${fmt(b.total_rub, 0)} ₽</b>`;
    $('liveStatus').innerHTML =
      `${modeHtml} · ${s.has_token ? 'токен ✓' : 'нет токена'} · ` +
      `${escapeHtml(acct)} · ${monHtml}` +
      fundsShort +
      (m.z != null ? ` · Z ${Number(m.z).toFixed(2)}` : '');

    $('liveMeta').innerHTML =
      `Счёт · ${modeHtml}` +
      (s.token_preview ? ` · ${escapeHtml(s.token_preview)}` : '') +
      ((!b || b.error) ? '' : ` · ${fmt(b.total_rub, 0)} ₽`);

    if (hydrateForm || !formHydrated) {
      syncFormFromSettings(s);
      formHydrated = true;
    }

    if (m.error) {
      $('liveMarketBox').textContent = `Рынок: ${m.error}`;
    } else {
      const d = data.dealer;
      let dealerLine = '';
      if (d && (d.ok || d.error || d.trading_ok != null)) {
        if (d.error && !d.ok) {
          dealerLine = `\nДилер: ${d.label || 'дилер'} · ${d.error}`;
        } else {
          const st = (d.manual_ok || d.quotes_ok)
            ? 'OK (ручной Long/Short)'
            : (d.trading_ok ? 'статус OK' : 'нет котировок');
          dealerLine =
            `\nДилер (${d.label || 'выходные'}): ${st}` +
            ` · TATN ${fmt(d.tatn)} / TATNP ${fmt(d.tatnp)}` +
            ` · спред ${fmt(d.spread)}%` +
            (d.bars_count != null ? ` · ${d.bars_count}×1м` : '') +
            (d.tatn_bid != null ? ` · bid/ask ${fmt(d.tatn_bid)}/${fmt(d.tatn_ask)}` : '') +
            ' · не в Z/AUTO';
        }
      }
      $('liveMarketBox').textContent =
        `Рынок ISS: ${m.trade_date || '—'} · Z ${fmt(m.z)} · спред ${fmt(m.spread)}%` +
        dealerLine +
        (mon.last_message ? `\nМонитор: ${mon.last_message}` : '');
    }

    renderFunds(b, s.mode);
    syncLiveTradeButtons();

    const ev = $('liveEvents');
    if (ev) {
      ev.innerHTML = (data.events || [])
        .map((e) => {
          const t = new Date(e.ts_ms).toLocaleTimeString('ru-RU');
          return `<div class="live-event live-event-${e.level}"><span>${t}</span> ${e.message}</div>`;
        })
        .join('') || '<div class="live-event">событий нет</div>';
    }
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

  async function refreshAll({ hydrateForm = false } = {}) {
    let data = await api('/api/live/status');
    if (await ensureMonitorRunning(data)) {
      data = await api('/api/live/status');
    }
    renderStatus(data, { hydrateForm });
  }

  async function saveCreds() {
    const mode = currentMode();
    const token = $('liveToken').value.trim();
    const account_id = $('liveAccountId').value.trim();
    const body = { mode, account_id: account_id || null };
    if (token) body.token = token;
    await api('/api/live/credentials', { method: 'POST', body: JSON.stringify(body) });
    await api('/api/live/settings', { method: 'POST', body: JSON.stringify({ mode }) });
    $('liveToken').value = '';
    await refreshAll({ hydrateForm: true });
    $('liveAccountHint').textContent = `Сохранено · ${mode === 'prod' ? 'боевой' : 'песочница'}.`;
  }

  function setViewMode(view) {
    const app = $('app');
    if (!app) return;
    let v = VIEW_MIGRATE[view] || view;
    const allowed = ['trade', 'history', 'account', 'replay'];
    if (!allowed.includes(v)) v = 'trade';
    const prev = app.dataset.view;
    app.dataset.view = v;
    localStorage.setItem(LS_VIEW, v);
    document.querySelectorAll('#viewModeChips .chip').forEach((b) => {
      b.classList.toggle('active', b.dataset.view === v);
    });
    const titles = {
      trade: 'MOEX · Торговля',
      history: 'MOEX · История',
      account: 'MOEX · Счёт',
      replay: 'MOEX · Тестирование',
    };
    const title = $('appTitle');
    if (title) title.textContent = titles[v] || titles.trade;

    if (prev === 'account' && v !== 'account') stopPolling();
    if (prev === 'trade' && v !== 'trade' && window.MoexTrade) MoexTrade.onHide();
    if (prev === 'history' && v !== 'history' && window.MoexHistory) MoexHistory.onHide();
    // legacy aliases
    if (prev === 'markets' && window.MoexMarkets) MoexMarkets.onHide();
    if (prev === 'portfolio' && window.MoexPortfolio) MoexPortfolio.onHide();

    if (v === 'trade') {
      if (window.MoexTrade) MoexTrade.onShow();
    } else if (v === 'history') {
      if (window.MoexHistory) MoexHistory.onShow();
    } else if (v === 'account') {
      formHydrated = false;
      startPolling();
      refreshAll({ hydrateForm: true }).catch((e) => {
        $('liveStatus').textContent = `Ошибка: ${e.message}`;
      });
    } else {
      if (window.__moexApplyMobileReplayLayout) window.__moexApplyMobileReplayLayout();
      requestAnimationFrame(() => {
        if (window.__moexReplayResize) window.__moexReplayResize();
        setTimeout(() => {
          if (window.__moexReplayResize) window.__moexReplayResize();
        }, 80);
      });
    }

    if (v !== 'replay' && window.__moexSetReplayParamsOpen) {
      window.__moexSetReplayParamsOpen(false);
    }
  }

  function startPolling() {
    stopPolling();
    pollTimer = setInterval(() => {
      refreshAll({ hydrateForm: false }).catch(() => {});
    }, 8000);
  }

  function stopPolling() {
    if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  function getSavedView() {
    let v = localStorage.getItem(LS_VIEW) || 'trade';
    return VIEW_MIGRATE[v] || v;
  }

  function bindLive() {
    document.querySelectorAll('#viewModeChips .chip').forEach((btn) => {
      btn.addEventListener('click', () => setViewMode(btn.dataset.view));
    });

    $('liveMode')?.addEventListener('change', async () => {
      const mode = currentMode();
      $('liveAccountId').value = '';
      $('liveToken').value = '';
      try {
        await api('/api/live/settings', { method: 'POST', body: JSON.stringify({ mode }) });
        await refreshAll({ hydrateForm: true });
        $('liveAccountHint').textContent =
          mode === 'prod'
            ? 'Режим Prod: вставьте боевой токен и «Сохранить».'
            : 'Режим Sandbox: вставьте токен песочницы.';
      } catch (e) {
        alert(e.message);
      }
    });

    $('liveBtnSave')?.addEventListener('click', () => {
      saveCreds().catch((e) => alert(e.message));
    });

    $('liveBtnAccounts')?.addEventListener('click', async () => {
      try {
        const mode = currentMode();
        await saveCreds();
        const data = await api(`/api/live/accounts?mode=${encodeURIComponent(mode)}`);
        const rows = data.accounts || [];
        const modeRu = (data.mode || mode) === 'prod' ? 'БОЕВЫЕ (Prod)' : 'песочница (Sandbox)';
        if (!rows.length) {
          alert(`Счетов нет · ${modeRu}`);
          return;
        }
        const pick = rows[0];
        const list = rows.map((r) => `${r.id}  ${r.name || ''}`).join('\n');
        const chosen = window.prompt(`Счета · ${modeRu}:\n${list}\n\naccountId:`, pick.id);
        if (chosen) {
          $('liveAccountId').value = chosen.trim();
          await api('/api/live/credentials', {
            method: 'POST',
            body: JSON.stringify({ mode, account_id: chosen.trim() }),
          });
          await refreshAll({ hydrateForm: true });
        }
      } catch (e) {
        alert(e.message);
      }
    });

    $('liveBtnPortfolio')?.addEventListener('click', async () => {
      try {
        const data = await api('/api/live/portfolio');
        renderFunds({
          mode: data.mode,
          cash_rub: data.cash_rub,
          total_rub: data.total_rub,
        }, data.mode);
      } catch (e) {
        alert(e.message);
      }
    });

    $('liveBtnSizing')?.addEventListener('click', async () => {
      try {
        const data = await api('/api/live/sizing');
        const s = data.sizing || {};
        $('livePortfolioBox').textContent =
          `Лоты: ${s.quantity_lots}+${s.quantity_lots} · GO/лот ${fmt(s.go_per_lot_rub, 0)} ₽`;
      } catch (e) {
        alert(e.message);
      }
    });

    $('liveBtnMonitorStart')?.addEventListener('click', () => {
      api('/api/live/monitor/start', { method: 'POST' })
        .then(() => refreshAll())
        .catch((e) => alert(e.message));
    });
    $('liveBtnMonitorStop')?.addEventListener('click', () => {
      api('/api/live/monitor/stop', { method: 'POST' })
        .then(() => refreshAll())
        .catch((e) => alert(e.message));
    });
    $('liveBtnTick')?.addEventListener('click', () => {
      api('/api/live/monitor/tick', { method: 'POST' })
        .then(() => refreshAll())
        .catch((e) => alert(e.message));
    });

    const trade = (side) => {
      const block = sessionOrdersBlockReason();
      if (block) {
        alert(block);
        return;
      }
      const warn =
        currentMode() === 'prod'
          ? `Боевой счёт: отправить ${side}?`
          : `Песочница: ${side}?`;
      if (!window.confirm(warn)) return;
      api('/api/live/trade', { method: 'POST', body: JSON.stringify({ side }) })
        .then(() => refreshAll())
        .catch((e) => alert(e.message));
    };
    $('liveBtnLong')?.addEventListener('click', () => trade('LONG'));
    $('liveBtnShort')?.addEventListener('click', () => trade('SHORT'));
    syncLiveTradeButtons();
  }

  window.MoexLive = {
    setViewMode,
    bindLive,
    refreshAll,
    getSavedView,
  };

  document.addEventListener('DOMContentLoaded', () => {
    bindLive();
  });
})();
