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

  function currentMode() {
    return $('liveMode').value === 'prod' ? 'prod' : 'sandbox';
  }

  function syncFormFromSettings(s) {
    if (s.mode) $('liveMode').value = s.mode === 'prod' ? 'prod' : 'sandbox';
    if (s.account_id != null) $('liveAccountId').value = s.account_id || '';
  }

  function renderStatus(data, { hydrateForm = false } = {}) {
    const s = data.settings || {};
    const m = data.market || {};
    const mon = data.monitor || {};
    const modeRu = s.mode === 'prod' ? 'Prod' : 'Sandbox';
    const monRu = mon.running ? 'монитор ON' : 'монитор OFF';
    $('liveStatus').textContent =
      `${modeRu} · ${s.has_token ? 'токен ✓' : 'нет токена'} · ` +
      `${s.account_id ? s.account_id.slice(0, 8) + '…' : 'нет account'} · ${monRu}` +
      (m.z != null ? ` · Z ${Number(m.z).toFixed(2)}` : '');

    $('liveMeta').textContent =
      `Счёт · ${modeRu}` + (s.token_preview ? ` · ${s.token_preview}` : '');

    if (hydrateForm || !formHydrated) {
      syncFormFromSettings(s);
      formHydrated = true;
    }

    if (m.error) {
      $('liveMarketBox').textContent = `Рынок: ${m.error}`;
    } else {
      $('liveMarketBox').textContent =
        `Рынок: ${m.trade_date || '—'} · Z ${fmt(m.z)} · спред ${fmt(m.spread)}%` +
        (mon.last_message ? `\nМонитор: ${mon.last_message}` : '');
    }

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

  async function refreshAll({ hydrateForm = false } = {}) {
    const data = await api('/api/live/status');
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
      replay: 'MOEX Bar Replay',
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
      requestAnimationFrame(() => {
        if (window.__moexReplayResize) window.__moexReplayResize();
      });
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
        $('livePortfolioBox').textContent =
          `Портфель [${data.mode}]: ${fmt(data.total_rub)} ₽ · cash ${fmt(data.cash_rub)} ₽`;
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
