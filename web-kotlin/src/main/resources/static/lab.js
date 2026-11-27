"use strict";
// Квант-лаборатория: формы, вызов /api/lab/* (расчёт в C++), отрисовка результатов и истории.

const lab = { kind: "backtest", available: null };
const KIND_RU = { backtest: "Бэктест", montecarlo: "Монте-Карло", portfolio: "Портфель" };
const pct = (v, d = 2) => `${fmt(v, d)} %`;

async function openLab() {
  fillLabInputs();
  if (lab.available === null) {
    try {
      lab.available = (await api("/api/lab/status")).available;
    } catch {
      lab.available = false;
    }
    $("lab-status").textContent = lab.available
      ? "C++ модуль подключён."
      : "C++ модуль market_cli не найден на сервере: расчёты недоступны (соберите core-cpp или запустите через docker compose).";
    document.querySelectorAll(".lab-form button[type=submit]").forEach((b) => (b.disabled = !lab.available));
  }
  loadLabHistory();
}
window.openLab = openLab;

function fillLabInputs() {
  const opts = state.instruments.map((i) => `<option value="${i.ticker}">${i.ticker}${i.name ? " — " + i.name : ""}</option>`).join("");
  document.querySelectorAll(".lab-ticker").forEach((sel) => {
    const keep = sel.value || state.ticker;
    sel.innerHTML = opts;
    if (keep && state.instruments.some((i) => i.ticker === keep)) sel.value = keep;
  });
  const picker = $("lab-pf-picker");
  const checked = new Set([...picker.querySelectorAll("input:checked")].map((i) => i.value));
  picker.innerHTML = "";
  state.instruments.forEach((i, n) => {
    const l = document.createElement("label");
    const on = checked.size ? checked.has(i.ticker) : n < 5;
    l.innerHTML = `<input type="checkbox" ${on ? "checked" : ""}><span class="swatch"></span><span></span>`;
    l.querySelector("input").value = i.ticker;
    l.querySelector(".swatch").style.background = colorOf(i.ticker);
    l.lastChild.textContent = i.ticker;
    picker.appendChild(l);
  });
}

function switchLab(kind) {
  lab.kind = kind;
  document.querySelectorAll(".lab-tools .tab").forEach((t) => t.classList.toggle("active", t.dataset.lab === kind));
  document.querySelectorAll(".lab-form").forEach((f) => (f.hidden = f.dataset.kind !== kind));
}

async function runLab(kind) {
  showError("");
  let url;
  if (kind === "backtest") {
    url = `/api/lab/backtest/${encodeURIComponent($("lab-bt-ticker").value)}?commission=${$("lab-bt-commission").value}&train=${$("lab-bt-train").value}`;
  } else if (kind === "montecarlo") {
    url = `/api/lab/montecarlo/${encodeURIComponent($("lab-mc-ticker").value)}?horizon=${$("lab-mc-horizon").value}&paths=${$("lab-mc-paths").value}`;
  } else {
    const tickers = [...$("lab-pf-picker").querySelectorAll("input:checked")].map((i) => i.value);
    url = `/api/lab/portfolio?tickers=${encodeURIComponent(tickers.join(","))}&samples=${$("lab-pf-samples").value}&rf=${$("lab-pf-rf").value || 0}`;
  }
  const btn = $(`lab-form-${kind}`).querySelector("button[type=submit]");
  btn.disabled = true;
  btn.textContent = "Считаем в C++…";
  try {
    renderLab(await api(url, { method: "POST" }));
    loadLabHistory();
  } catch (e) {
    showError(e.message);
  } finally {
    btn.disabled = !lab.available;
    btn.textContent = "Запустить в C++";
  }
}

async function loadLabHistory() {
  const body = $("lab-history");
  try {
    const runs = await api("/api/lab/runs?limit=15");
    body.innerHTML = runs.length ? "" : `<tr><td colspan="6">Расчётов пока нет</td></tr>`;
    for (const r of runs) {
      const tr = document.createElement("tr");
      tr.className = "clickable";
      tr.tabIndex = 0;
      tr.innerHTML = `<td>#${r.labRunId}</td><td>${new Date(r.createdAt).toLocaleString("ru-RU")}</td><td>${KIND_RU[r.kind]}</td>
        <td class="wrap-s"></td><td class="wrap"></td><td class="num">${fmt(r.computeMs, 1)}</td>`;
      tr.children[3].textContent = r.tickers.join(", ");
      tr.children[4].textContent = r.headline;
      const open = async () => {
        try {
          const full = await api(`/api/lab/runs/${r.labRunId}`);
          switchLab(full.summary.kind);
          renderLab(full);
          $("lab-result").scrollIntoView({ behavior: "smooth" });
        } catch (e) {
          showError(e.message);
        }
      };
      tr.addEventListener("click", open);
      tr.addEventListener("keydown", (e) => { if (e.key === "Enter") open(); });
      body.appendChild(tr);
    }
  } catch (e) {
    body.innerHTML = `<tr><td colspan="6">${e.message}</td></tr>`;
  }
}

// ---------- отрисовка

function labTile(label, value, note, cls = "") {
  return `<div class="tile"><div class="label">${label}</div><div class="value ${cls}">${value}</div><div class="note">${note || ""}</div></div>`;
}

function labHeader(run, title) {
  const r = run.result;
  return `<div class="title-row"><h2>${title}</h2><span class="meta">${run.summary.tickers.join(", ")}</span>
    <span class="badge">расчёт #${run.summary.labRunId} · C++ ${fmt(r.computeMs, 1)} мс</span></div>`;
}

function renderLab(run) {
  const kind = run.summary.kind;
  if (kind === "backtest") renderBacktest(run);
  else if (kind === "montecarlo") renderMonteCarlo(run);
  else renderPortfolio(run);
}

function renderBacktest(run) {
  const r = run.result;
  const verdictGood = r.outSample.sharpe > r.buyHoldOut.sharpe;
  $("lab-result").innerHTML = `${labHeader(run, `Бэктест SMA ${r.bestFast}/${r.bestSlow}`)}
    <div class="tiles">
      ${labTile("Лучшая пара на обучении", `SMA ${r.bestFast} / ${r.bestSlow}`, `из ${r.combos} вариантов`)}
      ${labTile("Шарп на обучении", fmt(r.inSample.sharpe), `до ${r.splitDate}`)}
      ${labTile("Шарп вне выборки", fmt(r.outSample.sharpe), `купить и держать: ${fmt(r.buyHoldOut.sharpe)}`, verdictGood ? "up" : "down")}
      ${labTile("Доходность вне выборки", pct(r.outSample.totalReturnPct), `купить и держать: ${pct(r.buyHoldOut.totalReturnPct)}`)}
      ${labTile("Сделок вне выборки", r.outSample.trades, `в рынке ${fmt(r.outSample.exposurePct, 0)} % времени`)}
    </div>
    <p class="verdict ${verdictGood ? "up" : "down"}">${verdictGood
      ? "▲ Стратегия обыграла «купить и держать» на данных, которых не было при подборе."
      : "▼ На новых данных стратегия хуже «купить и держать»: лучший результат на истории — во многом подгонка (переобучение)."}</p>
    <div class="panel chart-panel">
      <div class="chart-head"><h3>Капитал: стратегия против «купить и держать» (старт = 100)</h3>
        <div class="legend"><span class="key"><i style="background:${css("--s1")}"></i>SMA ${r.bestFast}/${r.bestSlow}</span>
        <span class="key"><i style="background:${css("--s2")}"></i>купить и держать</span>
        <span class="key"><i class="ref"></i>граница обучения / проверки</span></div></div>
      <div class="chart-box tall"><canvas id="lab-chart-1"></canvas></div>
    </div>
    <div class="panel">
      <h3>Показатели</h3>
      <div class="table-wrap"><table class="data">
        <thead><tr><th></th><th class="num">Доходность</th><th class="num">Годовая</th><th class="num">Волатильность</th><th class="num">Шарп</th><th class="num">Макс. просадка</th><th class="num">Сделок</th></tr></thead>
        <tbody>${[["Обучение · стратегия", r.inSample], ["Обучение · купить и держать", r.buyHoldIn], ["Проверка · стратегия", r.outSample], ["Проверка · купить и держать", r.buyHoldOut]]
          .map(([n, s]) => `<tr><td>${n}</td><td class="num">${pct(s.totalReturnPct)}</td><td class="num">${pct(s.annualReturnPct)}</td><td class="num">${pct(s.annualVolatilityPct)}</td>
            <td class="num">${fmt(s.sharpe)}</td><td class="num">${pct(s.maxDrawdownPct)}</td><td class="num">${s.trades}</td></tr>`).join("")}</tbody>
      </table></div>
    </div>
    <div class="panel">
      <h3>Шарп на обучающей части для каждой пары (строки — fast, столбцы — slow)</h3>
      <div class="table-wrap no-max"><table class="heat heat-small" id="lab-grid"></table></div>
      <div class="scale"><span>Шарп &lt; 0</span><i class="scale-bar heat-scale"></i><span>&gt; 0</span><span>· рамка — выбранная пара</span></div>
    </div>`;

  const split = r.dates.indexOf(r.splitDate);
  const splitLine = {
    id: "split",
    afterDatasetsDraw(chart) {
      const x = chart.scales.x.getPixelForValue(split);
      const { top, bottom } = chart.chartArea;
      const ctx = chart.ctx;
      ctx.save();
      ctx.strokeStyle = css("--muted");
      ctx.setLineDash([4, 4]);
      ctx.beginPath();
      ctx.moveTo(x, top);
      ctx.lineTo(x, bottom);
      ctx.stroke();
      ctx.restore();
    },
  };
  drawChart("lab1", $("lab-chart-1"), {
    type: "line",
    data: { labels: r.dates, datasets: [line(`SMA ${r.bestFast}/${r.bestSlow}`, r.equityStrategy, css("--s1"), 2), line("Купить и держать", r.equityBuyHold, css("--s2"), 2)] },
    options: baseOptions((v) => fmt(v, 1)),
    plugins: [splitLine],
  });

  const max = Math.max(...r.sharpeGrid.flat().filter((v) => v !== null).map(Math.abs), 0.01);
  const head = `<tr><th></th>${r.slows.map((s) => `<th>${s}</th>`).join("")}</tr>`;
  const body = r.sharpeGrid.map((row, i) => `<tr><th>${r.fasts[i]}</th>${row.map((v, j) => {
    if (v === null) return "<td class='na'></td>";
    const pole = v >= 0 ? "var(--heat-pos)" : "var(--heat-neg)";
    const best = r.fasts[i] === r.bestFast && r.slows[j] === r.bestSlow;
    return `<td class="${best ? "best" : ""}" style="background:color-mix(in oklab, ${pole} ${Math.round(Math.min(1, Math.abs(v) / max) * 100)}%, var(--div-mid))"
      title="SMA ${r.fasts[i]}/${r.slows[j]}: Шарп ${fmt(v)}"></td>`;
  }).join("")}</tr>`).join("");
  $("lab-grid").innerHTML = head + body;
}

function renderMonteCarlo(run) {
  const r = run.result;
  const days = Array.from({ length: r.horizon + 1 }, (_, i) => i);
  const [p5, p25, p50, p75, p95] = r.fan;
  $("lab-result").innerHTML = `${labHeader(run, `Монте-Карло на ${r.horizon} торг. дн.`)}
    <div class="tiles">
      ${labTile("VaR 95 %", pct(r.var95, 1), "потеря не превысит с вероятностью 95 %")}
      ${labTile("CVaR 95 %", pct(r.cvar95, 1), "средняя потеря в худших 5 % случаев")}
      ${labTile("VaR 99 %", pct(r.var99, 1), `CVaR 99 %: ${pct(r.cvar99, 1)}`)}
      ${labTile("Вероятность убытка", pct(r.probLossPct, 1), `средняя доходность ${pct(r.meanReturnPct, 1)}`)}
      ${labTile("Медианная цена", fmt(p50[r.horizon]), `сейчас ${fmt(r.lastClose)} (${r.lastDate})`)}
    </div>
    <p class="hint">${fmt(r.paths, 0)} сценариев собраны из ${r.sample} исторических дневных доходностей. Метод предполагает, что будущее похоже на прошлое: тренд и волатильность периода переносятся вперёд.</p>
    <div class="panel chart-panel">
      <div class="chart-head"><h3>Веер сценариев цены</h3>
        <div class="legend"><span class="key"><i style="background:${css("--s1")}"></i>медиана</span>
        <span class="key"><i style="background:${css("--s1")};opacity:.45"></i>25–75 %</span>
        <span class="key"><i style="background:${css("--s1")};opacity:.18"></i>5–95 %</span></div></div>
      <div class="chart-box tall"><canvas id="lab-chart-1"></canvas></div>
    </div>
    <div class="panel chart-panel">
      <div class="chart-head"><h3>Распределение доходности через ${r.horizon} дн.</h3>
        <div class="legend"><span class="key"><i style="background:${css("--s2")}"></i>убыток</span><span class="key"><i style="background:${css("--s1")}"></i>прибыль</span></div></div>
      <div class="chart-box"><canvas id="lab-chart-2"></canvas></div>
    </div>`;

  const band = (label, data, fill, alpha) => ({
    label, data, borderWidth: 0, pointRadius: 0, fill, backgroundColor: hexA(css("--s1"), alpha), tooltip: false,
  });
  const opts = baseOptions((v) => fmt(v));
  opts.scales.x.title = { display: true, text: "торговых дней вперёд", color: css("--muted") };
  opts.plugins.tooltip.filter = (i) => i.dataset.label !== "";
  drawChart("lab1", $("lab-chart-1"), {
    type: "line",
    data: {
      labels: days,
      datasets: [
        { ...band("95 %", p95, false, 0), borderColor: "transparent" },
        { ...band("5 %", p5, "-1", 0.18), borderColor: "transparent" },
        { ...band("75 %", p75, false, 0), borderColor: "transparent" },
        { ...band("25 %", p25, "-1", 0.45), borderColor: "transparent" },
        line("Медиана", p50, css("--s1"), 2),
      ],
    },
    options: opts,
  });

  const centers = r.histCounts.map((_, i) => (r.histEdges[i] + r.histEdges[i + 1]) / 2);
  const hOpts = baseOptions((v) => fmt(v, 0));
  hOpts.interaction = { mode: "nearest", intersect: true };
  hOpts.plugins.tooltip.callbacks = {
    title: (items) => `${pct(r.histEdges[items[0].dataIndex], 1)} … ${pct(r.histEdges[items[0].dataIndex + 1], 1)}`,
    label: (ctx) => `сценариев: ${fmt(ctx.parsed.y, 0)}`,
  };
  hOpts.scales.x.ticks.callback = function (v) { return `${fmt(centers[v], 0)} %`; };
  drawChart("lab2", $("lab-chart-2"), {
    type: "bar",
    data: {
      labels: centers,
      datasets: [{ data: r.histCounts, backgroundColor: centers.map((c) => (c < 0 ? css("--s2") : css("--s1"))), borderRadius: 2, borderSkipped: "bottom", barPercentage: 1, categoryPercentage: 0.92 }],
    },
    options: hOpts,
  });
}

function renderPortfolio(run) {
  const r = run.result;
  const pt = (p) => `доходность ${pct(p.returnPct, 1)}, риск ${pct(p.volatilityPct, 1)}`;
  $("lab-result").innerHTML = `${labHeader(run, "Оптимизация портфеля")}
    <div class="tiles">
      ${labTile("Макс. Шарп", fmt(r.maxSharpe.sharpe), pt(r.maxSharpe), "up")}
      ${labTile("Мин. риск", pct(r.minVolatility.volatilityPct, 1), `доходность ${pct(r.minVolatility.returnPct, 1)}`)}
      ${labTile("Равные доли", fmt(r.equalWeight.sharpe), pt(r.equalWeight))}
      ${labTile("Перебрано портфелей", fmt(r.samples, 0), `${r.days} общих торговых дней`)}
    </div>
    <p class="hint">Доходность и риск — годовые, по истории ${r.firstDate} … ${r.lastDate}. Оптимум по прошлым данным не гарантирует результат в будущем.</p>
    <div class="panel chart-panel">
      <div class="chart-head"><h3>Риск и доходность портфелей</h3><div class="legend" id="lab-pf-legend"></div></div>
      <div class="chart-box tall"><canvas id="lab-chart-1"></canvas></div>
    </div>
    <div class="panel">
      <h3>Доли бумаг</h3>
      <div class="table-wrap"><table class="data">
        <thead><tr><th>Бумага</th><th class="num">Макс. Шарп</th><th class="num">Мин. риск</th><th class="num">Равные</th><th class="num">Доходность</th><th class="num">Риск</th></tr></thead>
        <tbody>${r.tickers.map((t, i) => `<tr><td><span class="swatch" style="background:${colorOf(t)}"></span> ${t}</td>
          <td class="num">${pct(r.maxSharpe.weights[i] * 100, 1)}</td><td class="num">${pct(r.minVolatility.weights[i] * 100, 1)}</td>
          <td class="num">${pct(r.equalWeight.weights[i] * 100, 1)}</td><td class="num">${pct(r.assetReturnPct[i], 1)}</td><td class="num">${pct(r.assetVolatilityPct[i], 1)}</td></tr>`).join("")}</tbody>
      </table></div>
    </div>`;

  const pts = (arr) => arr.map((p) => ({ x: p.volatilityPct, y: p.returnPct }));
  const special = (label, p, style, color) => ({ label, type: "scatter", data: pts([p]), pointStyle: style, pointRadius: style === "star" ? 13 : 9, pointHoverRadius: 15, backgroundColor: color,
    // звезда в Chart.js рисуется только контуром — красим контур цветом точки
    borderColor: style === "star" ? color : css("--surface"), borderWidth: style === "star" ? 3 : 2 });
  const assets = r.tickers.map((t, i) => ({ label: t, type: "scatter", data: [{ x: r.assetVolatilityPct[i], y: r.assetReturnPct[i] }], pointRadius: 6, backgroundColor: colorOf(t), borderColor: css("--surface"), borderWidth: 2 }));
  $("lab-pf-legend").innerHTML = [
    `<span class="key"><span class="tri" style="color:${css("--muted")}">●</span>случайные портфели</span>`,
    `<span class="key"><span class="tri" style="color:${css("--good")}">★</span>макс. Шарп</span>`,
    `<span class="key"><span class="tri" style="color:${css("--ink")}">▲</span>мин. риск</span>`,
    `<span class="key"><span class="tri" style="color:${css("--ink-2")}">■</span>равные доли</span>`,
    ...r.tickers.map((t) => `<span class="key"><span class="tri" style="color:${colorOf(t)}">●</span>${t}</span>`),
  ].join("");
  const opts = baseOptions((v) => `${fmt(v, 0)} %`);
  opts.interaction = { mode: "nearest", intersect: true };
  opts.scales.x = { type: "linear", grid: { color: css("--grid") }, ticks: { color: css("--muted"), callback: (v) => `${fmt(v, 0)} %` }, title: { display: true, text: "риск (годовая волатильность)", color: css("--muted") } };
  opts.scales.y.title = { display: true, text: "ожидаемая годовая доходность", color: css("--muted") };
  opts.plugins.tooltip.callbacks = { label: (ctx) => `${ctx.dataset.label}: доходность ${pct(ctx.parsed.y, 1)}, риск ${pct(ctx.parsed.x, 1)}` };
  drawChart("lab1", $("lab-chart-1"), {
    type: "scatter",
    data: {
      // в Chart.js первый набор рисуется поверх остальных: оптимальные точки — первыми
      datasets: [
        special("Макс. Шарп", r.maxSharpe, "star", css("--good")),
        special("Мин. риск", r.minVolatility, "triangle", css("--ink")),
        special("Равные доли", r.equalWeight, "rect", css("--ink-2")),
        ...assets,
        { label: "портфель", data: pts(r.cloud), pointRadius: 2, backgroundColor: hexA(css("--muted"), 0.35) },
      ],
    },
    options: opts,
  });
}

function hexA(hex, alpha) {
  const h = hex.replace("#", "");
  const n = parseInt(h.length === 3 ? h.split("").map((c) => c + c).join("") : h, 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

document.addEventListener("DOMContentLoaded", () => {
  document.querySelectorAll(".lab-tools .tab").forEach((t) => t.addEventListener("click", () => switchLab(t.dataset.lab)));
  document.querySelectorAll(".lab-form").forEach((f) => f.addEventListener("submit", (e) => {
    e.preventDefault();
    runLab(f.dataset.kind);
  }));
});
