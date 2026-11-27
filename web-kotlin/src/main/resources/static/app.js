"use strict";

const state = { instruments: [], ticker: null, charts: {}, engines: { kotlin: true, cpp: false } };
const $ = (id) => document.getElementById(id);
const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
const fmt = (v, d = 2) => (v == null || Number.isNaN(v) ? "—" : Number(v).toLocaleString("ru-RU", { minimumFractionDigits: d, maximumFractionDigits: d }));
const SERIES = ["--s1", "--s2", "--s3", "--s4", "--s5", "--s6", "--s7", "--s8"];
const TREND_RU = { UPTREND: "Восходящий", DOWNTREND: "Нисходящий", SIDEWAYS: "Боковой" };
const ZONE_RU = { OVERBOUGHT: "перекупленность", OVERSOLD: "перепроданность", NEUTRAL: "нейтральная зона" };

// ---------- API ----------
async function api(url, options) {
  const res = await fetch(url, options);
  const text = await res.text();
  let body;
  try { body = JSON.parse(text); } catch { body = { error: text || res.statusText }; }
  if (!res.ok) {
    const where = body.line ? ` (строка ${body.line})` : "";
    throw new Error(`${body.error || "Ошибка запроса"}${where}`);
  }
  return body;
}

function params() {
  const q = new URLSearchParams({
    fast: $("p-fast").value, slow: $("p-slow").value, rsi: $("p-rsi").value,
    rf: $("p-rf").value || "0",
  });
  return q;
}

function showError(msg) {
  const el = $("error");
  el.textContent = msg || "";
  el.hidden = !msg;
}

// ---------- instruments ----------
function colorOf(ticker) {
  const idx = state.instruments.findIndex((i) => i.ticker === ticker);
  return css(SERIES[(idx < 0 ? 0 : idx) % SERIES.length]);
}

function renderInstruments() {
  const list = $("instrument-list");
  list.innerHTML = "";
  for (const i of state.instruments) {
    const li = document.createElement("li");
    li.className = "instrument";
    const b = document.createElement("button");
    b.type = "button";
    b.className = i.ticker === state.ticker ? "active" : "";
    const cls = i.changePct >= 0 ? "up" : "down";
    b.innerHTML = `<span class="t"><span class="tk"></span><span class="src"></span></span><span class="p">${fmt(i.lastClose)}</span>
      <span class="d"></span><span class="c ${cls}">${i.changePct >= 0 ? "▲" : "▼"} ${fmt(Math.abs(i.changePct))}%</span>`;
    b.querySelector(".tk").textContent = i.ticker;
    b.querySelector(".src").textContent = SOURCE_TAG[i.source] || "";
    b.querySelector(".d").textContent = i.name ? `${i.name} · с ${i.firstDate}` : `${i.firstDate} … ${i.lastDate}`;
    b.title = `${i.name || i.ticker}: ${i.sessions} сессий, ${i.firstDate} … ${i.lastDate}`;
    b.addEventListener("click", () => { state.ticker = i.ticker; switchTab("analysis"); loadAnalysis(); });
    li.appendChild(b);
    if (i.source !== "demo") {
      const del = document.createElement("button");
      del.type = "button";
      del.className = "delete";
      del.title = `Удалить ${i.ticker} и его историю из базы`;
      del.setAttribute("aria-label", del.title);
      del.textContent = "×";
      del.addEventListener("click", () => deleteInstrument(i.ticker));
      li.appendChild(del);
    }
    list.appendChild(li);
  }
  const picker = $("compare-picker");
  picker.innerHTML = "";
  state.instruments.forEach((i, n) => {
    const l = document.createElement("label");
    l.innerHTML = `<input type="checkbox" value="" ${n < 3 ? "checked" : ""}><span class="swatch"></span><span></span>`;
    l.querySelector("input").value = i.ticker;
    l.querySelector(".swatch").style.background = colorOf(i.ticker);
    l.lastChild.textContent = i.ticker;
    picker.appendChild(l);
  });
}

// ---------- analysis ----------
async function loadAnalysis() {
  showError("");
  try {
    if (!state.ticker) return;
    const report = await api(`/api/instruments/${encodeURIComponent(state.ticker)}/analysis?${params()}`);
    renderReport(report);
    renderInstruments();
    loadHistory(report.ticker);
  } catch (e) {
    showError(e.message);
  }
}

function tile(label, value, note, cls = "") {
  return `<div class="tile"><div class="label">${label}</div><div class="value ${cls}">${value}</div><div class="note">${note || ""}</div></div>`;
}

function renderReport(r) {
  $("a-title").textContent = r.ticker;
  const info = state.instruments.find((i) => i.ticker === r.ticker);
  $("a-name").textContent = info?.name || "";
  $("a-source").textContent = info ? sourceText(info) : "";
  $("a-refresh").hidden = info?.source !== "moex";
  $("a-meta").textContent = `${r.firstDate} … ${r.lastDate} · ${r.count} сессий`;
  $("a-engine").textContent = r.runId ? `запуск #${r.runId}` : "";
  const p = r.params;
  $("tiles").innerHTML = [
    tile("Последняя цена", fmt(r.lastClose), r.lastDate),
    tile("Доходность за период", `${r.totalReturnPct >= 0 ? "▲" : "▼"} ${fmt(Math.abs(r.totalReturnPct))}%`, "от первой до последней цены", r.totalReturnPct >= 0 ? "up" : "down"),
    tile("Волатильность (год.)", `${fmt(r.annualVolatilityPct)}%`, "СКО дневных доходностей · √252"),
    tile("Макс. просадка", `${fmt(r.maxDrawdownPct)}%`, "от пика до минимума"),
    tile("Коэффициент Шарпа", fmt(r.sharpeRatio, 3), `безриск. ставка ${fmt(p.riskFreeRatePct, 1)}%`),
    tile(`RSI(${p.rsiPeriod})`, fmt(r.rsi), ZONE_RU[r.rsiZone]),
    tile("Тренд", TREND_RU[r.trend], `SMA ${p.fastPeriod} vs SMA ${p.slowPeriod}`),
  ].join("");

  const s = r.series;
  const c = { close: css("--s1"), fast: css("--s2"), slow: css("--s3"), buy: css("--good"), sell: css("--critical") };
  const buys = r.signals.filter((x) => x.type === "BUY").map((x) => ({ x: x.date, y: x.price }));
  const sells = r.signals.filter((x) => x.type === "SELL").map((x) => ({ x: x.date, y: x.price }));
  $("price-legend").innerHTML = [
    `<span class="key"><i style="background:${c.close}"></i>Цена закрытия</span>`,
    `<span class="key"><i style="background:${c.fast}"></i>SMA ${p.fastPeriod}</span>`,
    `<span class="key"><i class="dash" style="--c:${c.slow}"></i>SMA ${p.slowPeriod}</span>`,
    `<span class="key"><span class="tri" style="color:${c.buy}">▲</span>покупка</span>`,
    `<span class="key"><span class="tri" style="color:${c.sell}">▼</span>продажа</span>`,
  ].join("");

  drawChart("price", $("price-chart"), {
    type: "line",
    data: {
      labels: s.dates,
      datasets: [
        line("Цена закрытия", s.close, c.close, 2),
        line(`SMA ${p.fastPeriod}`, s.smaFast, c.fast, 1.5),
        { ...line(`SMA ${p.slowPeriod}`, s.smaSlow, c.slow, 1.5), borderDash: [6, 4] },
        marker("Покупка", buys, c.buy, "triangle", 0),
        marker("Продажа", sells, c.sell, "triangle", 180),
      ],
    },
    options: baseOptions((v) => fmt(v)),
  });

  $("rsi-title").textContent = `RSI(${p.rsiPeriod})`;
  const rsiOpts = baseOptions((v) => fmt(v, 1));
  rsiOpts.scales.y.min = 0;
  rsiOpts.scales.y.max = 100;
  rsiOpts.scales.y.ticks.stepSize = 10;
  rsiOpts.scales.y.ticks.autoSkip = false;
  rsiOpts.scales.y.ticks.callback = (v) => ([0, 30, 50, 70, 100].includes(v) ? v : "");
  const flat = (v) => s.dates.map(() => v);
  drawChart("rsi", $("rsi-chart"), {
    type: "line",
    data: {
      labels: s.dates,
      datasets: [
        line("RSI", s.rsi, c.close, 2),
        { ...line("70", flat(70), css("--muted"), 1), borderDash: [3, 3], tooltip: false },
        { ...line("30", flat(30), css("--muted"), 1), borderDash: [3, 3], tooltip: false },
      ],
    },
    options: { ...rsiOpts, plugins: { ...rsiOpts.plugins, tooltip: { ...rsiOpts.plugins.tooltip, filter: (i) => i.datasetIndex === 0 } } },
  });

  $("signals").innerHTML = r.signals.length
    ? r.signals.slice().reverse().map((x) => `<tr><td>${x.date}</td><td class="${x.type === "BUY" ? "up" : "down"}">${x.type === "BUY" ? "▲ Покупка" : "▼ Продажа"}</td><td class="num">${fmt(x.price)}</td></tr>`).join("")
    : `<tr><td colspan="3">Пересечений не найдено</td></tr>`;
}

function line(label, data, color, width) {
  return { label, data, borderColor: color, backgroundColor: color, borderWidth: width, pointRadius: 0, pointHoverRadius: 4, spanGaps: false, tension: 0 };
}

function marker(label, data, color, style, rotation) {
  return {
    label, data, type: "line", showLine: false, pointStyle: style, rotation, pointRadius: 7, pointHoverRadius: 9,
    borderColor: css("--surface"), borderWidth: 2, backgroundColor: color,
  };
}

function baseOptions(yFormat) {
  return {
    responsive: true, maintainAspectRatio: false, animation: false,
    interaction: { mode: "index", intersect: false },
    plugins: {
      legend: { display: false },
      tooltip: {
        backgroundColor: css("--surface"), titleColor: css("--ink"), bodyColor: css("--ink-2"),
        borderColor: css("--axis"), borderWidth: 1, padding: 10, usePointStyle: true,
        callbacks: { label: (ctx) => `${ctx.dataset.label}: ${yFormat(ctx.parsed.y)}` },
      },
    },
    scales: {
      x: { grid: { display: false }, border: { color: css("--axis") }, ticks: { color: css("--muted"), maxTicksLimit: 8, maxRotation: 0 } },
      y: { grid: { color: css("--grid") }, border: { display: false }, ticks: { color: css("--muted"), callback: yFormat } },
    },
  };
}

function drawChart(key, canvas, config) {
  if (!window.Chart) { showError("Не удалось загрузить библиотеку графиков (Chart.js). Проверьте файл vendor/chart.umd.min.js."); return; }
  state.charts[key]?.destroy();
  state.charts[key] = new Chart(canvas, config);
}

// ---------- compare ----------
async function runCompare() {
  showError("");
  const tickers = [...$("compare-picker").querySelectorAll("input:checked")].map((i) => i.value);
  try {
    const q = params();
    q.delete("engine");
    q.set("tickers", tickers.join(","));
    const r = await api(`/api/compare?${q}`);
    renderCompare(r);
  } catch (e) {
    $("compare-result").hidden = true;
    showError(e.message);
  }
}

function renderCompare(r) {
  $("compare-result").hidden = false;
  const tickers = r.rows.map((x) => x.ticker);
  $("compare-legend").innerHTML = tickers.map((t) => `<span class="key"><i style="background:${colorOf(t)}"></i>${t}</span>`).join("");
  const opts = baseOptions((v) => fmt(v, 1));
  drawChart("compare", $("compare-chart"), {
    type: "line",
    data: { labels: r.dates, datasets: tickers.map((t) => line(t, r.normalized[t], colorOf(t), 2)) },
    options: opts,
  });
  $("compare-rows").innerHTML = r.rows.map((x) => `<tr>
      <td><span class="swatch" style="background:${colorOf(x.ticker)}"></span> ${x.ticker}</td>
      <td class="num ${x.totalReturnPct >= 0 ? "up" : "down"}">${fmt(x.totalReturnPct)}</td>
      <td class="num">${fmt(x.annualVolatilityPct)}</td><td class="num">${fmt(x.maxDrawdownPct)}</td>
      <td class="num">${fmt(x.sharpeRatio, 3)}</td><td class="num">${fmt(x.rsi, 1)}</td><td>${TREND_RU[x.trend]}</td></tr>`).join("");

  const head = `<tr><th></th>${tickers.map((t) => `<th>${t}</th>`).join("")}</tr>`;
  const body = r.correlation.map((row, i) => `<tr><th>${tickers[i]}</th>${row.map((v) => {
    const pole = v >= 0 ? "var(--div-pos)" : "var(--div-neg)";
    const pct = Math.round(Math.min(1, Math.abs(v)) * 100);
    const ink = Math.abs(v) > 0.55 ? "#ffffff" : "var(--ink)";
    return `<td style="background:color-mix(in oklab, ${pole} ${pct}%, var(--div-mid));color:${ink}" title="${tickers[i]} × ${v.toFixed(3)}">${v.toFixed(2)}</td>`;
  }).join("")}</tr>`).join("");
  $("corr").innerHTML = head + body;
}

// ---------- Мосбиржа ----------
const SOURCE_TAG = { moex: "MOEX", demo: "демо", upload: "CSV" };

function sourceText(i) {
  if (i.source === "moex") return `Мосбиржа · обновлено ${i.syncedAt ? new Date(i.syncedAt).toLocaleString("ru-RU") : "—"}`;
  if (i.source === "demo") return "синтетические демо-данные";
  return "загружено из CSV";
}

async function importFromMoex(ticker) {
  showError("");
  const btn = $("moex-form").querySelector("button");
  btn.disabled = true;
  btn.textContent = "Загрузка…";
  try {
    const info = await api(`/api/instruments/${encodeURIComponent(ticker)}/moex`, { method: "POST" });
    state.ticker = info.ticker;
    await reloadInstruments();
    switchTab("analysis");
    await loadAnalysis();
    $("moex-ticker").value = "";
  } catch (e) {
    showError(e.message);
  } finally {
    btn.disabled = false;
    btn.textContent = "Загрузить котировки";
  }
}

async function refreshCurrent() {
  if (!state.ticker) return;
  showError("");
  const btn = $("a-refresh");
  btn.disabled = true;
  try {
    await api(`/api/instruments/${encodeURIComponent(state.ticker)}/refresh`, { method: "POST" });
    await reloadInstruments();
    await loadAnalysis();
  } catch (e) {
    showError(e.message);
  } finally {
    btn.disabled = false;
  }
}

/** Пока сервер загружает котировки с биржи (первый запуск), показываем статус и обновляем список. */
async function watchSync() {
  try {
    const st = await api("/api/sync");
    const note = $("sync-note");
    if (st.running) {
      note.hidden = false;
      note.textContent = "Загружаем котировки с Московской биржи…";
      await reloadInstruments();
      if (!state.ticker && state.instruments.length) {
        state.ticker = state.instruments[0].ticker;
        await loadAnalysis();
      }
      setTimeout(watchSync, 2000);
      return;
    }
    note.hidden = !(st.failed && st.failed.length);
    note.textContent = st.failed?.length ? `Не удалось загрузить с биржи: ${st.failed.join(", ")}` : "";
    const before = state.instruments.length;
    await reloadInstruments();
    if (!state.ticker && state.instruments.length) {
      state.ticker = state.instruments[0].ticker;
      await loadAnalysis();
    } else if (state.instruments.length !== before) {
      renderInstruments();
    }
  } catch (e) {
    /* статус синхронизации необязателен */
  }
}

// ---------- storage: instruments & history ----------
async function reloadInstruments() {
  state.instruments = await api("/api/instruments");
  renderInstruments();
}

async function deleteInstrument(ticker) {
  if (!confirm(`Удалить ${ticker} и всю историю его анализов из базы?`)) return;
  showError("");
  try {
    const res = await fetch(`/api/instruments/${encodeURIComponent(ticker)}`, { method: "DELETE" });
    if (!res.ok && res.status !== 404) throw new Error(`Не удалось удалить ${ticker}`);
    await reloadInstruments();
    if (state.ticker === ticker) {
      state.ticker = state.instruments[0]?.ticker ?? null;
      await loadAnalysis();
    }
  } catch (e) {
    showError(e.message);
  }
}

async function loadHistory(ticker) {
  const body = $("history");
  try {
    const runs = await api(`/api/runs?ticker=${encodeURIComponent(ticker)}&limit=10`);
    $("history-title").textContent = `История анализов ${ticker}`;
    body.innerHTML = runs.length ? "" : `<tr><td colspan="6">Запусков пока нет</td></tr>`;
    for (const r of runs) {
      const tr = document.createElement("tr");
      tr.className = "clickable";
      tr.tabIndex = 0;
      const p = r.params;
      tr.innerHTML = `<td>#${r.runId}</td><td>${new Date(r.createdAt).toLocaleString("ru-RU")}</td>
        <td>SMA ${p.fastPeriod}/${p.slowPeriod}, RSI ${p.rsiPeriod}, r<sub>f</sub> ${fmt(p.riskFreeRatePct, 1)}%</td>
        <td class="num ${r.totalReturnPct >= 0 ? "up" : "down"}">${fmt(r.totalReturnPct)}</td><td class="num">${fmt(r.sharpeRatio, 3)}</td><td>${TREND_RU[r.trend]}</td>`;
      const open = () => openRun(r.runId);
      tr.addEventListener("click", open);
      tr.addEventListener("keydown", (e) => { if (e.key === "Enter") open(); });
      body.appendChild(tr);
    }
  } catch (e) {
    body.innerHTML = `<tr><td colspan="6">${e.message}</td></tr>`;
  }
}

async function openRun(runId) {
  showError("");
  try {
    const r = await api(`/api/runs/${runId}`);
    const p = r.params;
    $("p-fast").value = p.fastPeriod;
    $("p-slow").value = p.slowPeriod;
    $("p-rsi").value = p.rsiPeriod;
    $("p-rf").value = p.riskFreeRatePct;
    renderReport(r);
    $("a-engine").textContent += " · из истории";
    window.scrollTo({ top: 0, behavior: "smooth" });
  } catch (e) {
    showError(e.message);
  }
}

// ---------- tabs & init ----------
function switchTab(name) {
  document.querySelectorAll(".tabs .tab").forEach((t) => {
    const on = t.dataset.tab === name;
    t.classList.toggle("active", on);
    t.setAttribute("aria-selected", on);
  });
  $("tab-analysis").hidden = name !== "analysis";
  $("tab-compare").hidden = name !== "compare";
  $("tab-lab").hidden = name !== "lab";
  $("params-panel").hidden = name === "lab";
  if (name === "lab" && window.openLab) window.openLab();
  showError("");
}

async function init() {
  document.querySelectorAll(".tabs .tab").forEach((t) => t.addEventListener("click", () => switchTab(t.dataset.tab)));
  $("apply").addEventListener("click", () => ($("tab-compare").hidden ? loadAnalysis() : runCompare()));
  $("compare-run").addEventListener("click", runCompare);
  $("moex-form").addEventListener("submit", (e) => {
    e.preventDefault();
    importFromMoex($("moex-ticker").value.trim().toUpperCase());
  });
  $("a-refresh").addEventListener("click", refreshCurrent);
  $("upload-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const file = $("upload-file").files[0];
    if (!file) return;
    if (file.size > 5 * 1024 * 1024) { showError("Файл больше 5 МБ"); return; }
    const ticker = $("upload-ticker").value.trim();
    showError("");
    try {
      await api(`/api/instruments/${encodeURIComponent(ticker)}`, { method: "POST", headers: { "Content-Type": "text/csv" }, body: await file.text() });
      state.ticker = ticker;
      await reloadInstruments();
      switchTab("analysis");
      await loadAnalysis();
    } catch (err) {
      showError(err.message);
    }
  });
  try {
    const health = await api("/api/health");
    $("storage-badge").textContent = health.storage === "postgres" ? "хранилище: PostgreSQL" : "хранилище: в памяти";
    await reloadInstruments();
    state.ticker = (state.instruments.find((i) => i.ticker === "IMOEX") || state.instruments[0])?.ticker ?? null;
    renderInstruments();
    await loadAnalysis();
    watchSync();
  } catch (e) {
    showError(e.message);
  }
}

document.addEventListener("DOMContentLoaded", init);
