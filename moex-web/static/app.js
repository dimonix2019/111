const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

let chart;
let candleSeries;
let currentPeriod = "1D";

function toast(msg) {
  const el = $("#toast");
  el.textContent = msg;
  el.classList.remove("hidden");
  setTimeout(() => el.classList.add("hidden"), 4000);
}

function initTabs() {
  $$("#tabs button").forEach((btn) => {
    btn.addEventListener("click", () => {
      $$("#tabs button").forEach((b) => b.classList.remove("active"));
      $$(".panel").forEach((p) => p.classList.remove("active"));
      btn.classList.add("active");
      $(`#panel-${btn.dataset.tab}`).classList.add("active");
    });
  });
}

function initChart() {
  const el = $("#chart");
  chart = LightweightCharts.createChart(el, {
    layout: { background: { color: "#0b111b" }, textColor: "#94a3b8" },
    grid: { vertLines: { color: "#1f2937" }, horzLines: { color: "#1f2937" } },
    rightPriceScale: { borderColor: "#374151" },
    timeScale: { borderColor: "#374151", timeVisible: true },
  });
  candleSeries = chart.addCandlestickSeries({
    upColor: "#26a69a",
    downColor: "#ef5350",
    borderVisible: false,
    wickUpColor: "#26a69a",
    wickDownColor: "#ef5350",
  });
  new ResizeObserver(() => {
    chart.applyOptions({ width: el.clientWidth, height: el.clientHeight });
  }).observe(el);
}

function parseTime(s) {
  const d = new Date(s.replace(" ", "T") + "+03:00");
  return Math.floor(d.getTime() / 1000);
}

async function loadMarkets(period) {
  currentPeriod = period;
  $$("#periods button").forEach((b) => {
    b.classList.toggle("active", b.dataset.period === period);
  });
  $("#markets-meta").textContent = "Загрузка…";
  try {
    const r = await fetch(`/api/markets/chart?period=${period}`);
    const data = await r.json();
    if (!r.ok) throw new Error(data.detail || r.statusText);
    const candles = data.candles.map((c) => ({
      time: parseTime(c.time),
      open: c.open,
      high: c.high,
      low: c.low,
      close: c.close,
    }));
    candleSeries.setData(candles);
    chart.timeScale().fitContent();
    $("#markets-meta").textContent =
      `${period} · баров ${data.barCount} (на графике ${data.displayBarCount}) · Z=${(data.lastZ ?? 0).toFixed(3)} · spread=${(data.lastSpread ?? 0).toFixed(3)}%`;
  } catch (e) {
    $("#markets-meta").textContent = "Ошибка: " + e.message;
    toast(e.message);
  }
}

async function refreshM15() {
  toast("Обновление 15м с MOEX…");
  try {
    await fetch("/api/data/refresh", { method: "POST" });
    await loadMarkets(currentPeriod);
    toast("Данные обновлены");
  } catch (e) {
    toast(e.message);
  }
}

async function runSimulation() {
  const body = {
    entry: parseFloat($("#st-entry").value),
    exit_z: parseFloat($("#st-exit").value),
    leverage: parseFloat($("#st-lev").value),
    commission: parseFloat($("#st-comm").value),
    compound: $("#st-compound").checked,
  };
  $("#st-metrics").textContent = "Считаем…";
  const r = await fetch("/api/strategy-test/simulate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const data = await r.json();
  if (data.error) {
    $("#st-metrics").textContent = data.error;
    return;
  }
  $("#st-metrics").textContent = [
    `Баров: ${data.barCount}`,
    `Сделок: ${data.tradeCount}`,
    `PnL всего: ${data.totalPnlRub?.toFixed(2)} ₽`,
    `Реализовано: ${data.realizedPnlRub?.toFixed(2)} ₽`,
    `Win rate: ${data.winRate != null ? (data.winRate * 100).toFixed(1) + "%" : "—"}`,
    `Max DD: ${data.maxDrawdownRub?.toFixed(2) ?? "—"} ₽`,
  ].join("\n");
  $("#st-trades").innerHTML = (data.trades || [])
    .map(
      (t, i) =>
        `<div>#${i + 1} ${t.direction} ${t.entryTime} → ${t.exitTime} · ${t.pnlRub.toFixed(2)} ₽</div>`
    )
    .join("");
}

async function loadAbout() {
  const r = await fetch("/api/info");
  const data = await r.json();
  $("#about-info").textContent = JSON.stringify(data, null, 2);
}

function init() {
  initTabs();
  initChart();
  $$("#periods button").forEach((b) => {
    b.addEventListener("click", () => loadMarkets(b.dataset.period));
  });
  $("#btn-refresh-m15").addEventListener("click", refreshM15);
  $("#btn-sim").addEventListener("click", runSimulation);
  loadMarkets("1D");
  loadAbout();
}

init();
