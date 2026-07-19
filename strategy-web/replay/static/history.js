/** История — закрытые сделки */
(function () {
  let days = 7;
  let pollTimer = null;

  const $ = (id) => document.getElementById(id);

  async function api(path) {
    const res = await fetch(path);
    const text = await res.text();
    let data;
    try { data = text ? JSON.parse(text) : {}; } catch { data = { detail: text }; }
    if (!res.ok) throw new Error(typeof data.detail === 'string' ? data.detail : (data.detail || text));
    return data;
  }

  function fmt(n, d = 2) {
    if (n == null || Number.isNaN(Number(n))) return '—';
    return Number(n).toLocaleString('ru-RU', { maximumFractionDigits: d });
  }

  function render(data) {
    const closed = data.closed || [];
    $('historyStatus').textContent =
      `Закрытых за ${data.days}д: ${closed.length}` +
      (data.closed_total != null ? ` · всего ${data.closed_total}` : '') +
      (data.position ? ` · сейчас ${data.position}` : '');
    $('historyMeta').textContent = `История · ${data.days}д`;
    $('historyCount').textContent =
      `${closed.length} сделок` + (data.closed_total != null ? ` (из ${data.closed_total})` : '');
    $('historyClosedBody').innerHTML = closed.length
      ? closed.map((t) =>
          `<tr><td>${t.id}</td><td>${t.direction}</td><td>${t.entry_time || ''}</td>` +
          `<td>${t.exit_time || ''}</td><td>${t.quantity_lots}</td>` +
          `<td>${fmt(t.entry_z)}</td><td>${fmt(t.exit_z)}</td><td>${t.source || ''}</td></tr>`
        ).join('')
      : '<tr><td colspan="8">пусто</td></tr>';
  }

  async function refresh() {
    const data = await api(`/api/portfolio?days=${days}`);
    render(data);
  }

  function startPoll() {
    stopPoll();
    pollTimer = setInterval(() => { refresh().catch(() => {}); }, 20000);
  }

  function stopPoll() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  }

  function onShow() {
    refresh().then(() => startPoll()).catch((e) => {
      $('historyStatus').textContent = `Ошибка: ${e.message}`;
    });
  }

  function onHide() { stopPoll(); }

  function bind() {
    document.querySelectorAll('#historyDepthChips .chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('#historyDepthChips .chip').forEach((b) => b.classList.remove('active'));
        btn.classList.add('active');
        days = parseInt(btn.dataset.days, 10) || 7;
        refresh().catch((e) => alert(e.message));
      });
    });
  }

  window.MoexHistory = { onShow, onHide, refresh, bind };
  window.MoexPortfolio = window.MoexHistory;

  document.addEventListener('DOMContentLoaded', bind);
})();
