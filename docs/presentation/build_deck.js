// Генератор презентации проекта: node build_deck.js (из каталога с установленными
// pptxgenjs, react, react-dom, react-icons, sharp). Картинки берутся из docs/img.
const path = require("path");
const pptxgen = require("pptxgenjs");
const React = require("react");
const ReactDOMServer = require("react-dom/server");
const sharp = require("sharp");
const fa = require("react-icons/fa6");

const IMG = path.resolve(__dirname, "../img");
const OUT = process.argv[2] || path.resolve(__dirname, "Market_Analytics.pptx");
const APPLY_THEME = process.argv[3];

const THEME = {
  name: "Market Analytics",
  headFontFace: "Cambria",
  bodyFontFace: "Calibri",
  colors: {
    dk1: "0F1B2D", lt1: "FFFFFF", dk2: "14233A", lt2: "EEF3F8",
    accent1: "0E9F6E", accent2: "D64545", accent3: "2A78D6", accent4: "E39B00",
    accent5: "6250D6", accent6: "5B6B80", hlink: "2A78D6", folHlink: "6250D6",
  },
};
const HEX = THEME.colors;
const MUTED = "5B6B80";

const pres = new pptxgen();
pres.layout = "LAYOUT_WIDE"; // 13.33 x 7.5
pres.title = "Market Analytics — анализ рынка ценных бумаг";
pres.subject = "Индивидуальный проект: C++ + Kotlin";
pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace };
const C = pres.SchemeColor;

// ---------- layouts ----------
pres.defineSlideMaster({
  title: "TITLE",
  background: { color: C.text2 },
  objects: [
    { placeholder: { options: { name: "title", type: "title", x: 0.8, y: 2.0, w: 6.4, h: 1.6, fontSize: 48, bold: true, color: C.background1, valign: "bottom", align: "left", margin: 0 }, text: "" } },
    { placeholder: { options: { name: "body", type: "body", x: 0.8, y: 3.75, w: 6.2, h: 1.6, fontSize: 20, color: C.background2, valign: "top", align: "left", margin: 0 }, text: "" } },
  ],
});
pres.defineSlideMaster({
  title: "CONTENT",
  background: { color: C.background1 },
  margin: [0.5, 0.6, 0.7, 0.6],
  objects: [
    { placeholder: { options: { name: "title", type: "title", x: 0.6, y: 0.35, w: 12.1, h: 0.85, fontSize: 34, bold: true, color: C.text2, valign: "middle", align: "left", margin: 0 }, text: "" } },
    { text: { text: "Market Analytics · индивидуальный проект · C++ + Kotlin", options: { x: 0.6, y: 7.0, w: 8, h: 0.3, fontSize: 10, color: C.accent6, margin: 0 } } },
  ],
  slideNumber: { x: 12.2, y: 7.0, w: 0.5, h: 0.3, fontSize: 10, color: C.accent6, align: "right" },
});

// ---------- helpers ----------
async function icon(name, color = "FFFFFF") {
  const svg = ReactDOMServer.renderToStaticMarkup(React.createElement(fa[name], { color: "#" + color, size: 256 }));
  const buf = await sharp(Buffer.from(svg)).png().toBuffer();
  return "image/png;base64," + buf.toString("base64");
}

async function iconCircle(slide, name, x, y, d, fill, id) {
  slide.addShape(pres.shapes.OVAL, { x, y, w: d, h: d, fill: { color: fill }, line: { type: "none" }, objectName: `${id}-circle` });
  const pad = d * 0.26;
  slide.addImage({ data: await icon(name), x: x + pad, y: y + pad, w: d - 2 * pad, h: d - 2 * pad, objectName: `${id}-icon`, altText: name });
}

async function fitImage(slide, file, box, opts = {}) {
  const meta = await sharp(path.join(IMG, file)).metadata();
  const r = Math.min(box.w / meta.width, box.h / meta.height);
  const w = meta.width * r, h = meta.height * r;
  const x = box.x + (opts.align === "left" ? 0 : (box.w - w) / 2);
  const y = box.y + (opts.valign === "top" ? 0 : (box.h - h) / 2);
  slide.addImage({ path: path.join(IMG, file), x, y, w, h, objectName: opts.name || file, altText: opts.alt || file });
  return { x, y, w, h };
}

function card(slide, x, y, w, h, id, fill = C.background2) {
  slide.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h, rectRadius: 0.08, fill: { color: fill }, line: { type: "none" }, objectName: id });
}

function text(slide, t, o) {
  slide.addText(t, { isTextBox: true, margin: 0, valign: "top", color: C.text1, fontSize: 14, ...o });
}

function content(title, section) {
  const s = pres.addSlide({ masterName: "CONTENT", sectionTitle: section });
  s.addText(title, { placeholder: "title" });
  return s;
}

const chartText = { catAxisLabelFontFace: "+mn-lt", valAxisLabelFontFace: "+mn-lt", legendFontFace: "+mn-lt", titleFontFace: "+mn-lt", dataLabelFontFace: "+mn-lt" };

async function build() {
  // ===== 1. Титул =====
  pres.addSection({ title: "Введение" });
  let s = pres.addSlide({ masterName: "TITLE", sectionTitle: "Введение" });
  s.addText("Market Analytics", { placeholder: "title" });
  s.addText([
    { text: "Веб-приложение для анализа рынка ценных бумаг", options: { breakLine: true } },
    { text: "C++ ядро + Kotlin веб-сервис · полный цикл разработки", options: { fontSize: 16, color: C.accent1, breakLine: true } },
    { text: "Программная инженерия · 4 курс · индивидуальный проект", options: { fontSize: 14 } },
  ], { placeholder: "body" });
  await fitImage(s, "ui-analysis.png", { x: 7.5, y: 1.2, w: 5.3, h: 5.1 }, { name: "hero-screenshot", alt: "Экран анализа инструмента" });
  s.addNotes("Здравствуйте. Мой проект — Market Analytics, веб-приложение для анализа ценных бумаг. Ядро расчётов написано на C++, затем портировано на Kotlin, и на Kotlin же сделан веб-сервис. Расскажу, как проект прошёл все шесть этапов: требования, проектирование, ядро, портирование, тестирование и Git.");

  // ===== 2. Задача =====
  s = content("Задача: доходность и риск бумаги за минуту", "Введение");
  text(s, [
    { text: "Проблема", options: { bold: true, fontSize: 18, color: C.text2, breakLine: true } },
    { text: "Частному инвестору нужно быстро понять, как вела себя бумага: сколько принесла, насколько волатильна, какова глубина просадок и что говорят технические индикаторы.", options: { breakLine: true } },
    { text: " ", options: { breakLine: true } },
    { text: "Цель", options: { bold: true, fontSize: 18, color: C.text2, breakLine: true } },
    { text: "Реальные котировки Московской биржи (или свой CSV) → метрики, графики, сигналы и сравнение бумаг в браузере; данные хранятся и обновляются автоматически.", options: {} },
  ], { x: 0.6, y: 1.6, w: 5.4, h: 4.6, fontSize: 16 });
  const goals = [
    ["FaChartLine", "Доходность и риск", "Доходность, годовая волатильность, макс. просадка, коэффициент Шарпа", C.accent1],
    ["FaGaugeHigh", "Технический анализ", "SMA быстрая/медленная, RSI, сигналы пересечения, тренд", C.accent3],
    ["FaScaleBalanced", "Сравнение", "2–8 бумаг: динамика к 100, таблица метрик, корреляции доходностей", C.accent5],
    ["FaFileCsv", "Реальные данные", "Мосбиржа: IMOEX и крупные акции, любая бумага по тикеру; свой CSV", C.accent4],
  ];
  for (let i = 0; i < goals.length; i++) {
    const [ic, h, d, col] = goals[i];
    const y = 1.55 + i * 1.3;
    card(s, 6.6, y, 6.1, 1.1, `goal-card-${i}`);
    await iconCircle(s, ic, 6.8, y + 0.2, 0.7, col, `goal-${i}`);
    text(s, [{ text: h, options: { bold: true, fontSize: 16, breakLine: true } }, { text: d, options: { fontSize: 14, color: MUTED } }], { x: 7.7, y: y + 0.15, w: 4.8, h: 0.85 });
  }
  s.addNotes("Задача: инвестору нужно быстро понять поведение бумаги. Приложение само загружает реальные котировки Московской биржи — индекс и крупнейшие акции, а любую другую бумагу можно добавить по тикеру. Оно считает метрики доходности и риска, индикаторы технического анализа и сравнивает бумаги между собой.");

  // ===== 3. Этапы =====
  s = content("Шесть этапов — шесть артефактов", "Введение");
  const stages = [
    ["1", "Требования", "SRS: 9 историй,\n~35 критериев приёмки", "Markdown"],
    ["2", "Проектирование", "Use Case, Class,\nSequence, Activity, ERD", "PlantUML"],
    ["3", "Ядро", "Расчёты, валидация,\nошибки, файловый I/O", "C++17"],
    ["4", "Портирование", "Kotlin-сервис +\nC++ лаборатория", "Kotlin, Ktor, SQL"],
    ["5", "Тестирование", "170 тестов,\nотчёт о 8 дефектах", "GTest, JUnit"],
    ["6", "Git + защита", "Ветки, PR, CI,\nDocker, доклад", "GitHub, Docker"],
  ];
  const colw = 12.1 / 6;
  s.addShape(pres.shapes.LINE, { x: 0.6 + colw / 2, y: 2.15, w: 12.1 - colw, h: 0, line: { color: C.accent6, width: 1.5 }, objectName: "timeline" });
  stages.forEach(([n, h, d, tool], i) => {
    const cx = 0.6 + i * colw + colw / 2;
    s.addShape(pres.shapes.OVAL, { x: cx - 0.45, y: 1.7, w: 0.9, h: 0.9, fill: { color: i === 2 || i === 3 ? C.accent1 : C.text2 }, line: { color: C.background1, width: 3 }, objectName: `stage-${n}` });
    text(s, n, { x: cx - 0.45, y: 1.7, w: 0.9, h: 0.9, align: "center", valign: "middle", fontSize: 24, bold: true, color: C.background1 });
    text(s, h, { x: cx - colw / 2 + 0.1, y: 2.85, w: colw - 0.2, h: 0.45, align: "center", fontSize: 17, bold: true, color: C.text2 });
    card(s, cx - colw / 2 + 0.12, 3.45, colw - 0.24, 1.55, `stage-card-${n}`);
    text(s, d, { x: cx - colw / 2 + 0.22, y: 3.6, w: colw - 0.44, h: 1.3, align: "center", fontSize: 14 });
    text(s, tool, { x: cx - colw / 2 + 0.1, y: 5.2, w: colw - 0.2, h: 0.4, align: "center", fontSize: 14, bold: true, color: C.accent3 });
  });
  text(s, "Все артефакты лежат в репозитории: docs/ (документы и диаграммы), core-cpp/, web-kotlin/, compose.yaml, .github/workflows/", { x: 0.6, y: 6.1, w: 12.1, h: 0.4, fontSize: 14, color: MUTED });
  s.addNotes("Проект прошёл все шесть этапов из задания. Для каждого есть конкретный артефакт в репозитории: документ требований, диаграммы PlantUML, модуль на C++, порт на Kotlin с веб-интерфейсом и хранением в PostgreSQL, набор тестов с отчётом, история Git с CI и запуск через Docker Compose.");

  // ===== 4. Требования =====
  pres.addSection({ title: "Требования и проектирование" });
  s = content("Требования: 9 пользовательских историй", "Требования и проектирование");
  const us = [
    ["US-1", "Каталог инструментов", "тикер, период, последняя цена, изменение"],
    ["US-2", "Анализ инструмента", "метрики, графики цены/SMA/RSI, сигналы"],
    ["US-6", "Квант-лаборатория (C++)", "бэктест, Монте-Карло VaR, портфель"],
    ["US-4", "Загрузка своего CSV", "проверка формата, ошибка с номером строки"],
    ["US-5", "Сравнение 2–8 бумаг", "динамика к 100, корреляции"],
    ["US-8", "Хранение и история", "PostgreSQL: бумаги и все запуски анализа"],
  ];
  us.forEach(([id, h, d], i) => {
    const col = i % 3, row = Math.floor(i / 3);
    const x = 0.6 + col * 4.1, y = 1.55 + row * 1.75;
    card(s, x, y, 3.85, 1.5, `us-card-${i}`);
    text(s, id, { x: x + 0.25, y: y + 0.18, w: 1.2, h: 0.35, fontSize: 14, bold: true, color: C.accent1 });
    text(s, [{ text: h, options: { bold: true, fontSize: 17, breakLine: true } }, { text: d, options: { fontSize: 14, color: MUTED } }], { x: x + 0.25, y: y + 0.5, w: 3.4, h: 0.95 });
  });
  card(s, 0.6, 5.2, 12.05, 1.45, "ac-example", C.background1);
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 0.6, y: 5.2, w: 12.05, h: 1.45, rectRadius: 0.08, fill: { type: "none" }, line: { color: C.accent6, width: 1 }, objectName: "ac-frame" });
  text(s, [
    { text: "Пример критерия приёмки (AC-4.3): ", options: { bold: true } },
    { text: "при ошибке в CSV пользователь видит причину и номер строки; API отвечает 422 {error, line}.", options: { breakLine: true } },
    { text: "Также в SRS: US-3 (параметры), US-7 (консольный отчёт), US-9 (котировки Мосбиржи); 9 функциональных и 9 нефункциональных требований (офлайн-работа, лимит 5 МБ, защита от path traversal, docker compose).", options: { color: MUTED } },
  ], { x: 0.85, y: 5.35, w: 11.6, h: 1.2, fontSize: 14 });
  s.addNotes("Требования оформлены как пользовательские истории с критериями приёмки — 9 историй и около 35 критериев. Например, для загрузки CSV: ошибка показывается с номером строки, API возвращает 422. Отдельная история — хранение: бумаги и все запуски анализа сохраняются в PostgreSQL. Плюс нефункциональные требования: работа без интернета, лимит загрузки, защита от обхода путей, запуск одной командой.");

  // ===== 5. Use Case =====
  s = content("Диаграмма вариантов использования", "Требования и проектирование");
  await fitImage(s, "use-case.png", { x: 0.6, y: 1.35, w: 12.1, h: 5.45 }, { alt: "UML Use Case диаграмма" });
  s.addNotes("Два актора: инвестор-аналитик и разработчик. Анализ включает построение графиков; загрузка CSV включает валидацию; настройка параметров и выбор движка расширяют анализ. Консольная утилита market_cli — отдельный вариант использования.");

  // ===== 6. Архитектура =====
  s = content("Архитектура: сервис и лаборатория", "Требования и проектирование");
  await fitImage(s, "components.png", { x: 0.6, y: 1.3, w: 7.0, h: 5.55 }, { alt: "Диаграмма компонентов" });
  const decisions = [
    ["FaLayerGroup", "Слои", "UI → REST API (Ktor) → ядро; котировки — Мосбиржа (ISS API), хранение — PostgreSQL."],
    ["FaCode", "Роли языков", "Kotlin — сервис и интерактивный анализ; C++ — тяжёлые расчёты лаборатории."],
    ["FaServer", "C++ как процесс, не JNI", "Падение C++ не роняет JVM, таймаут 120 с; запуск процесса ≈ 4 мс."],
    ["FaShieldHalved", "Хранилище за интерфейсом", "MarketRepository: PostgreSQL в работе, в памяти — без БД; один набор тестов на оба."],
  ];
  for (let i = 0; i < decisions.length; i++) {
    const [ic, h, d] = decisions[i];
    const y = 1.45 + i * 1.35;
    await iconCircle(s, ic, 7.85, y + 0.05, 0.65, C.text2, `dec-${i}`);
    text(s, [{ text: h, options: { bold: true, fontSize: 17, breakLine: true } }, { text: d, options: { fontSize: 14, color: MUTED } }], { x: 8.7, y, w: 4.0, h: 1.2 });
  }
  s.addNotes("Архитектура слоистая. Ядро не зависит ни от веба, ни от базы, а оба ядра выдают один и тот же JSON — поэтому Kotlin-сервер может вызывать C++ утилиту как внешний процесс. Данные хранятся в PostgreSQL за интерфейсом репозитория: есть реализация для базы и в памяти, и один и тот же набор тестов проверяет обе. Всё разворачивается двумя контейнерами через docker compose.");

  // ===== 7. Классы ядра =====
  s = content("Диаграмма классов ядра", "Требования и проектирование");
  await fitImage(s, "class-core.png", { x: 0.6, y: 1.3, w: 8.4, h: 5.55 }, { alt: "Диаграмма классов ядра" });
  card(s, 9.3, 1.5, 3.4, 5.1, "class-note");
  text(s, [
    { text: "Одна модель — два языка", options: { bold: true, fontSize: 17, breakLine: true } },
    { text: " ", options: { fontSize: 8, breakLine: true } },
    { text: "C++: namespace market, свободные функции, struct", options: { bullet: true, breakLine: true } },
    { text: "Kotlin: package market.core, object + data class", options: { bullet: true, breakLine: true } },
    { text: "Ошибка данных хранит номер строки файла", options: { bullet: true, breakLine: true } },
    { text: "Ряды индикаторов: NaN в C++, null в Kotlin", options: { bullet: true } },
  ], { x: 9.5, y: 1.7, w: 3.0, h: 4.7, fontSize: 14, paraSpaceAfter: 6 });
  s.addNotes("Модель предметной области: свеча, параметры анализа, отчёт, торговый сигнал, перечисления тренда и зоны RSI. Три модуля: загрузчик CSV, индикаторы и анализатор. Структура одинакова в C++ и Kotlin, отличается только идиоматика языка.");

  // ===== 8. Классы веб-слоя =====
  s = content("Диаграмма классов веб-слоя (Kotlin)", "Требования и проектирование");
  await fitImage(s, "class-web.png", { x: 0.6, y: 1.3, w: 12.1, h: 5.5 }, { alt: "Диаграмма классов веб-слоя" });
  s.addNotes("Веб-слой: модуль Ktor с маршрутами, сервис MarketService — каталог, анализ, загрузка и сравнение, — и адаптер CppEngine, который запускает C++ утилиту через ProcessBuilder. DTO сериализуются kotlinx.serialization.");

  // ===== 9. Sequence =====
  s = content("Последовательность: анализ инструмента", "Требования и проектирование");
  await fitImage(s, "sequence-analysis.png", { x: 0.6, y: 1.3, w: 12.1, h: 5.5 }, { alt: "Диаграмма последовательности" });
  s.addNotes("Сценарий анализа: браузер шлёт GET с параметрами, сервер их проверяет, читает котировки из PostgreSQL и считает на Kotlin или вызывает market_cli. Результат одной транзакцией записывается в историю, а клиент получает JSON с номером запуска, метриками и рядами для графиков.");

  // ===== 9b. Sequence лаборатории =====
  s = content("Последовательность: лаборатория C++", "Требования и проектирование");
  await fitImage(s, "sequence-lab.png", { x: 0.6, y: 1.3, w: 12.1, h: 5.5 }, { alt: "Диаграмма последовательности лаборатории" });
  s.addNotes("Расчёт в лаборатории: Kotlin проверяет параметры, берёт котировки из PostgreSQL, пишет временные CSV и запускает market_cli нужной командой. C++ считает и возвращает JSON со временем расчёта, Kotlin формирует краткий итог и сохраняет результат в таблицу lab_run как JSONB, чтобы его можно было открыть из истории.");

  // ===== 10. Activity + ошибки =====
  s = content("Проверка CSV и обработка ошибок", "Требования и проектирование");
  await fitImage(s, "activity-csv.png", { x: 0.6, y: 1.3, w: 3.3, h: 5.55 }, { alt: "Диаграмма активности проверки CSV" });
  const codes = [
    ["400", "неверный параметр, тикер, движок", C.accent4],
    ["404", "инструмент не найден", C.accent6],
    ["422", "ошибка данных + номер строки", C.accent2],
    ["500", "непредвиденная ошибка (в лог)", C.text2],
  ];
  codes.forEach(([code, d, col], i) => {
    const x = 4.3 + (i % 2) * 4.25, y = 1.45 + Math.floor(i / 2) * 1.1;
    card(s, x, y, 4.05, 0.9, `code-card-${i}`);
    text(s, code, { x: x + 0.2, y: y + 0.12, w: 0.9, h: 0.65, fontSize: 26, bold: true, color: col, valign: "middle" });
    text(s, d, { x: x + 1.15, y: y + 0.12, w: 2.75, h: 0.65, fontSize: 14, valign: "middle" });
  });
  await fitImage(s, "ui-error.png", { x: 4.3, y: 3.85, w: 8.3, h: 2.2 }, { alt: "Сообщение об ошибке в интерфейсе" });
  text(s, "Проверки: 6 колонок · только десятичные числа · цены > 0 · low ≤ open, close ≤ high · объём — целое ≥ 0 · даты строго по возрастанию", { x: 4.3, y: 6.2, w: 8.3, h: 0.6, fontSize: 14, color: MUTED });
  s.addNotes("Алгоритм проверки CSV построчный: заголовок, число колонок, числа, логика OHLC, порядок дат. Любая ошибка сразу прерывает чтение и сообщает номер строки. В интерфейсе пользователь видит причину — как на скриншоте.");

  // ===== 11. ERD =====
  s = content("ER-диаграмма: схема PostgreSQL", "Требования и проектирование");
  await fitImage(s, "erd.png", { x: 0.6, y: 1.3, w: 9.0, h: 5.55 }, { alt: "ER-диаграмма" });
  card(s, 9.9, 1.5, 2.8, 5.1, "erd-note");
  text(s, [
    { text: "9 таблиц", options: { bold: true, fontSize: 17, breakLine: true } },
    { text: "схема — миграция Flyway", options: { breakLine: true } },
    { text: " ", options: { fontSize: 8, breakLine: true } },
    { text: "Целостность", options: { bold: true, fontSize: 17, breakLine: true } },
    { text: "FK + CASCADE, CHECK повторяют правила CSV", options: { breakLine: true } },
    { text: " ", options: { fontSize: 8, breakLine: true } },
    { text: "Транзакции", options: { bold: true, fontSize: 17, breakLine: true } },
    { text: "импорт и запуск — атомарно", options: { breakLine: true } },
    { text: " ", options: { fontSize: 8, breakLine: true } },
    { text: "Снимок рядов в indicator_point", options: { color: MUTED } },
  ], { x: 10.1, y: 1.7, w: 2.45, h: 4.7, fontSize: 14 });
  s.addNotes("Это реальная схема PostgreSQL, она создаётся миграцией Flyway. Инструмент и его котировки, запуск анализа с метриками, рядами индикаторов и сигналами, сохранённые сравнения с матрицей корреляций. Внешние ключи с каскадным удалением и CHECK-ограничения повторяют правила проверки CSV, так что некорректная свеча не попадёт в базу даже в обход кода. Импорт и сохранение запуска идут в транзакциях.");

  // ===== 12. C++ ядро =====
  pres.addSection({ title: "Реализация" });
  s = content("C++ ядро: что и как считается", "Реализация");
  const stats = [["285", "строк логики (.cpp)"], ["8", "функций индикаторов"], ["3,5 мс", "полный прогон CLI на 500 сессиях"]];
  stats.forEach(([v, l], i) => {
    text(s, v, { x: 0.6, y: 1.5 + i * 1.75, w: 3.6, h: 0.9, fontSize: 54, bold: true, color: C.accent1, fontFace: THEME.headFontFace });
    text(s, l, { x: 0.6, y: 2.4 + i * 1.75, w: 3.6, h: 0.5, fontSize: 14, color: MUTED });
  });
  const formulas = [
    ["Доходность", "rₜ = Pₜ / Pₜ₋₁ − 1"],
    ["Волатильность (год.)", "σ = std(r) · √252"],
    ["Макс. просадка", "max (пик − Pₜ) / пик"],
    ["Коэффициент Шарпа", "(mean(r) · 252 − r_f) / σ"],
    ["RSI (Уайлдер)", "100 − 100 / (1 + avgGain / avgLoss)"],
    ["Сигнал SMA", "BUY: fast ↑ slow · SELL: fast ↓ slow"],
  ];
  formulas.forEach(([h, f], i) => {
    const col = i % 2, row = Math.floor(i / 2);
    const x = 4.6 + col * 4.1, y = 1.5 + row * 1.7;
    card(s, x, y, 3.85, 1.45, `formula-${i}`);
    text(s, h, { x: x + 0.25, y: y + 0.2, w: 3.4, h: 0.4, fontSize: 16, bold: true });
    text(s, f, { x: x + 0.25, y: y + 0.7, w: 3.4, h: 0.55, fontSize: 16, color: C.accent3, fontFace: "Courier New" });
  });
  text(s, "CsvLoader · Indicators · Analyzer + market_cli (коды выхода 0 / 1 / 2, вывод JSON или --summary, запись отчёта --out)", { x: 4.6, y: 6.6, w: 8.1, h: 0.35, fontSize: 12, color: MUTED });
  s.addNotes("Ядро на C++17 — около 285 строк логики, в рамках требования 150–300. Оно считает доходность, годовую волатильность, просадку, коэффициент Шарпа, RSI по Уайлдеру и сигналы пересечения скользящих. Консольная утилита пишет отчёт в JSON или в текстовом виде, полный прогон — около 3,5 миллисекунд.");

  // ===== 13. Kotlin vs C++ =====
  s = content("Портирование ядра на Kotlin", "Реализация");
  s.addChart(pres.charts.BAR, [
    { name: "C++", labels: ["Модели", "CSV", "Индикаторы", "Анализ + JSON"], values: [49, 103, 116, 118] },
    { name: "Kotlin", labels: ["Модели", "CSV", "Индикаторы", "Анализ + JSON"], values: [58, 74, 106, 51] },
  ], {
    x: 0.6, y: 1.35, w: 6.2, h: 5.3, barDir: "col", barGapWidthPct: 60,
    chartColors: [HEX.dk2, HEX.accent1],
    showTitle: true, title: "Строк кода ядра (без пустых и комментариев)", titleFontSize: 14, titleColor: HEX.dk1,
    showValue: true, dataLabelPosition: "outEnd", dataLabelFontSize: 12, dataLabelColor: HEX.dk1,
    showLegend: true, legendPos: "b", legendFontSize: 12,
    catAxisLabelColor: MUTED, valAxisLabelColor: MUTED, catAxisLabelFontSize: 12, valAxisLabelFontSize: 11,
    valGridLine: { color: "E1E6EE", size: 0.75 }, catGridLine: { style: "none" },
    ...chartText,
  });
  const rows = [
    ["Аспект", "C++17", "Kotlin"],
    ["Нет значения", "NaN, легко забыть", "Double? — проверит компилятор"],
    ["JSON", "ручная сборка", "@Serializable"],
    ["Коллекции", "циклы, accumulate", "map, zipWithNext"],
    ["Роль в продукте", "тяжёлые расчёты", "сервис, анализ в UI"],
    ["Итого ядро", "386 строк", "289 строк (−25 %)"],
  ];
  s.addTable(rows.map((r, i) => r.map((c) => ({ text: c, options: { bold: i === 0, color: i === 0 ? HEX.lt1 : HEX.dk1, fill: { color: i === 0 ? HEX.dk2 : (i % 2 ? HEX.lt2 : HEX.lt1) } } }))), {
    x: 7.2, y: 1.45, w: 5.5, colW: [1.5, 1.7, 2.3], fontSize: 14, rowH: 0.5, border: { type: "none" }, valign: "middle", margin: 0.08,
  });
  text(s, "Паритет проверяется автотестом: на 4 наборах данных все метрики, сигналы и ряды SMA совпадают с точностью 1e-6.", { x: 7.2, y: 4.85, w: 5.5, h: 0.9, fontSize: 14, color: C.text2, bold: true });
  s.addNotes("Ядро индикаторов портировано на Kotlin: логика та же, код на четверть короче — сериализация через аннотацию, функции коллекций, null-safety вместо NaN. Совпадение с C++ проверяется автотестом. Но дублировать функции в продукте смысла нет, поэтому роли разделены: интерактивный анализ в интерфейсе делает Kotlin, а C++ отвечает за тяжёлые расчёты — об этом следующий слайд.");

  // ===== 13b. Лаборатория C++ =====
  s = content("C++ квант-лаборатория: тяжёлые расчёты", "Реализация");
  const labCards = [
    ["FaChartLine", "Бэктест с оптимизацией", "Перебор 570 пар SMA с комиссией; подбор на 70 % истории, проверка на 30 %, которые алгоритм «не видел»; сравнение с «купить и держать».", "1 722 пары — 4 мс", C.accent3],
    ["FaGaugeHigh", "Монте-Карло риск", "Бутстреп исторических доходностей: десятки тысяч сценариев цены; VaR и CVaR 95 / 99 %, вероятность убытка, веер прогноза.", "100 000 × 252 дня — ≈ 1 с", C.accent2],
    ["FaScaleBalanced", "Оптимизация портфеля", "Ковариационная матрица и случайный поиск по долям: портфели с максимальным Шарпом и минимальным риском.", "200 000 портфелей — 20 мс", C.accent1],
  ];
  for (let i = 0; i < labCards.length; i++) {
    const [ic, h, d, perf, col] = labCards[i];
    const x = 0.6 + i * 4.1;
    card(s, x, 1.45, 3.85, 3.9, `lab-card-${i}`);
    await iconCircle(s, ic, x + 0.25, 1.7, 0.7, col, `lab-${i}`);
    text(s, h, { x: x + 0.25, y: 2.55, w: 3.4, h: 0.45, fontSize: 18, bold: true });
    text(s, d, { x: x + 0.25, y: 3.05, w: 3.4, h: 1.6, fontSize: 14, color: MUTED });
    text(s, perf, { x: x + 0.25, y: 4.7, w: 3.4, h: 0.45, fontSize: 16, bold: true, color: col });
  }
  card(s, 0.6, 5.6, 12.05, 1.05, "lab-why", C.background1);
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 0.6, y: 5.6, w: 12.05, h: 1.05, rectRadius: 0.08, fill: { type: "none" }, line: { color: C.accent6, width: 1 }, objectName: "lab-why-frame" });
  text(s, [
    { text: "Почему C++: ", options: { bold: true } },
    { text: "миллионы операций с числами без пауз сборщика мусора, плотные массивы double, std::mt19937_64 и nth_element. Kotlin в это время делает свою работу: берёт котировки из PostgreSQL, вызывает market_cli и сохраняет результат в историю (lab_run, JSONB).", options: {} },
  ], { x: 0.85, y: 5.72, w: 11.6, h: 0.85, fontSize: 14 });
  s.addNotes("Чтобы у двух языков были разные задачи, тяжёлые вычисления вынесены в C++. Это бэктест стратегии с перебором параметров и честной проверкой на данных, которых алгоритм не видел при подборе, Монте-Карло для оценки риска и оптимизация портфеля. Здесь важна скорость: 100 тысяч сценариев на год вперёд — около секунды. Kotlin готовит данные из базы и сохраняет результат.");

  // ===== 13c. Пример бэктеста =====
  s = content("Пример: бэктест разоблачает переобучение", "Реализация");
  await fitImage(s, "ui-lab-backtest-crop.png", { x: 0.6, y: 1.3, w: 8.4, h: 5.45 }, { align: "left", name: "lab-screenshot", alt: "Экран бэктеста" });
  const bt = [["2,66", "Шарп лучшей пары SMA 6/10 на обучающей части"], ["−1,37", "Шарп той же пары на новых данных"], ["0,65", "Шарп «купить и держать» на тех же новых данных"]];
  bt.forEach(([v, d], i) => {
    const y = 1.45 + i * 1.6;
    card(s, 9.3, y, 3.4, 1.4, `bt-${i}`);
    text(s, v, { x: 9.5, y: y + 0.12, w: 3.0, h: 0.65, fontSize: 32, bold: true, color: i === 1 ? C.accent2 : C.text2, fontFace: THEME.headFontFace });
    text(s, d, { x: 9.5, y: y + 0.78, w: 3.0, h: 0.6, fontSize: 13, color: MUTED });
  });
  text(s, "Скриншот на демо-данных; с котировками Мосбиржи расчёт тот же.", { x: 9.3, y: 6.3, w: 3.4, h: 0.45, fontSize: 11, color: MUTED });
  s.addNotes("Пример работы лаборатории. Лучшая на истории пара скользящих даёт коэффициент Шарпа 2,66 — выглядит отлично. Но на последних 30 % данных, которые при подборе не использовались, та же стратегия даёт минус 1,37 и проигрывает простому «купить и держать». Это классическое переобучение, и программа показывает его наглядно: тепловая карта всех пар, кривые капитала и граница обучения на графике.");

  // ===== 14. Веб-интерфейс =====
  s = content("Веб-интерфейс", "Реализация");
  await fitImage(s, "ui-analysis.png", { x: 0.6, y: 1.3, w: 8.6, h: 5.5 }, { align: "left", name: "ui-screenshot", alt: "Экран анализа" });
  const ui = [
    ["Метрики и тренд", "7 плиток: цена, доходность, волатильность, просадка, Шарп, RSI, тренд"],
    ["Графики", "цена + SMA, маркеры ▲ покупки ▼ продажи, RSI с уровнями 30/70"],
    ["Лаборатория C++", "отдельная вкладка: бэктест, Монте-Карло, портфель"],
    ["История в БД", "каждый запуск сохраняется; свой CSV — в базу; тёмная тема, офлайн"],
  ];
  ui.forEach(([h, d], i) => {
    text(s, [{ text: h, options: { bold: true, fontSize: 16, breakLine: true } }, { text: d, options: { fontSize: 14, color: MUTED } }], { x: 9.55, y: 1.45 + i * 1.35, w: 3.15, h: 1.2 });
  });
  s.addNotes("Так выглядит интерфейс: слева каталог и загрузка файла, сверху параметры анализа, дальше плитки метрик, график цены со скользящими и маркерами сигналов, ниже — RSI и таблица сигналов. Каждый запуск сохраняется в базе — внизу есть история, любой старый отчёт открывается одним кликом. Свой CSV тоже сохраняется в базу. Есть тёмная тема, а библиотека графиков поставляется локально, так что всё работает без интернета.");

  // ===== 15. Сравнение =====
  s = content("Сравнение инструментов на демо-данных", "Реализация");
  const tick = ["DEMO_TECH", "DEMO_BANK", "DEMO_ENERGY", "DEMO_RETAIL"];
  s.addChart(pres.charts.BAR, [
    { name: "Доходность за период, %", labels: tick, values: [138.1, -2.7, -46.1, 18.3] },
    { name: "Годовая волатильность, %", labels: tick, values: [37.1, 21.0, 31.3, 26.0] },
    { name: "Макс. просадка, %", labels: tick, values: [38.6, 36.2, 54.1, 33.3] },
  ], {
    x: 0.6, y: 1.35, w: 7.6, h: 5.35, barDir: "col", barGapWidthPct: 50,
    chartColors: [HEX.accent1, HEX.accent3, HEX.accent2],
    showValue: true, dataLabelPosition: "outEnd", dataLabelFontSize: 11, dataLabelColor: HEX.dk1, dataLabelFormatCode: "0.0",
    showLegend: true, legendPos: "b", legendFontSize: 12,
    catAxisLabelColor: MUTED, valAxisLabelColor: MUTED, catAxisLabelFontSize: 12, valAxisLabelFontSize: 11,
    valGridLine: { color: "E1E6EE", size: 0.75 }, catGridLine: { style: "none" },
    ...chartText,
  });
  const cmp = [["1,37", "Шарп у DEMO_TECH — лучшее соотношение доходности и риска"], ["0,24–0,43", "корреляции дневных доходностей: есть потенциал диверсификации"], ["54 %", "просадка DEMO_ENERGY — максимальный риск в выборке"]];
  text(s, "Данные синтетические: модель с общим рыночным фактором, не реальные котировки", { x: 8.55, y: 6.55, w: 4.15, h: 0.35, fontSize: 10, color: MUTED });
  cmp.forEach(([v, d], i) => {
    const y = 1.45 + i * 1.75;
    card(s, 8.55, y, 4.15, 1.5, `cmp-${i}`);
    text(s, v, { x: 8.8, y: y + 0.15, w: 3.7, h: 0.6, fontSize: 30, bold: true, color: C.text2, fontFace: THEME.headFontFace });
    text(s, d, { x: 8.8, y: y + 0.78, w: 3.7, h: 0.65, fontSize: 14, color: MUTED });
  });
  s.addNotes("Экран сравнения на демо-данных. Данные синтетические — сгенерированы моделью с общим рыночным фактором, поэтому корреляции реалистичные, от 0,24 до 0,43. Видно, что DEMO_TECH дал лучший коэффициент Шарпа, а у DEMO_ENERGY самая глубокая просадка.");

  // ===== 16. Тестирование =====
  pres.addSection({ title: "Тестирование и Git" });
  s = content("Тестирование: 170 тестов, 0 падений", "Тестирование и Git");
  s.addChart(pres.charts.BAR, [
    { name: "Тестов", labels: ["C++ ядро (индикаторы, CSV, анализ)", "C++ квант-модуль", "Kotlin ядро", "Хранилище (память + PostgreSQL)", "REST API (память + PostgreSQL)", "Лаборатория через API", "Мосбиржа (клиент, синхр., API)", "Паритет C++/Kotlin"], values: [38, 13, 40, 22, 28, 11, 13, 5] },
  ], {
    x: 0.6, y: 1.35, w: 6.0, h: 5.35, barDir: "bar", barGapWidthPct: 45,
    chartColors: [HEX.dk2],
    showValue: true, dataLabelPosition: "outEnd", dataLabelFontSize: 12, dataLabelColor: HEX.dk1,
    showLegend: false, showTitle: true, title: "Google Test (C++) и JUnit 5 + Kotest (Kotlin)", titleFontSize: 14, titleColor: HEX.dk1,
    catAxisLabelColor: MUTED, valAxisLabelColor: MUTED, catAxisLabelFontSize: 12, valAxisLabelFontSize: 11, catAxisOrientation: "maxMin",
    valGridLine: { color: "E1E6EE", size: 0.75 }, catGridLine: { style: "none" },
    ...chartText,
  });
  const defects = [
    ["D-01", "Графики не грузились без CDN → Chart.js локально"],
    ["D-02", "stod принимал «0x0A», Kotlin — «0x1p3», «11d» → белый список"],
    ["D-03", "Скрыты подписи 30/70 на RSI → autoSkip = false"],
    ["D-04", "Демо-данные без корреляций → рыночный фактор"],
    ["D-05", "Предупреждение -Wformat-truncation в тестах"],
    ["D-06", "Дата 2024-02-30 проходила проверку → календарная дата"],
    ["D-07", "«Осиротевшие» расчёты лаборатории в PostgreSQL → удаление в транзакции"],
    ["D-08", "Вкладки лаборатории скрывали страницу → отдельный обработчик"],
  ];
  text(s, "Найденные и исправленные дефекты", { x: 7.0, y: 1.45, w: 5.7, h: 0.4, fontSize: 17, bold: true, color: C.text2 });
  defects.forEach(([id, d], i) => {
    const y = 1.92 + i * 0.53;
    card(s, 7.0, y, 5.7, 0.47, `defect-${i}`);
    text(s, id, { x: 7.15, y: y + 0.03, w: 0.75, h: 0.41, fontSize: 13, bold: true, color: C.accent2, valign: "middle" });
    text(s, d, { x: 7.9, y: y + 0.03, w: 4.75, h: 0.41, fontSize: 13, valign: "middle" });
  });
  text(s, "Граничные случаи: период 0 / 1 / = длине / > длины, RSI 0–50–100, σ = 0, 18 видов битых строк CSV, откат транзакции, отсутствие заглядывания в будущее в бэктесте", { x: 7.0, y: 6.2, w: 5.7, h: 0.7, fontSize: 12, color: MUTED });
  s.addNotes("Всего 170 тестов: 51 на Google Test и 119 на JUnit с Kotest, все проходят. Отдельно проверено, что бэктест не заглядывает в будущее, а Монте-Карло воспроизводим при одинаковом seed. Тесты хранилища и API запускаются дважды — на хранилище в памяти и на настоящей PostgreSQL, а клиент Мосбиржи проверяется на локальной заглушке ISS API. Покрыты граничные случаи: нулевой и слишком большой период, крайние значения RSI, нулевая волатильность, 18 видов некорректных строк CSV, откат транзакции. Найдено и исправлено восемь дефектов; например, D-02: оба языка принимали шестнадцатеричные числа как цены, а D-06 обнаружился при переходе на базу — несуществующая дата 30 февраля проходила проверку.");

  // ===== 17. Git + CI =====
  s = content("Git и непрерывная интеграция", "Тестирование и Git");
  const flow = [
    ["FaCodeBranch", "Ветка", "feature-ветка от main", C.accent3],
    ["FaCode", "Коммиты", "по этапам: данные → ядро → Kotlin → документы", C.accent5],
    ["FaListCheck", "Pull Request", "описание изменений, ревью", C.accent4],
    ["FaVial", "CI", "GTest, JUnit + PostgreSQL, Docker-образ", C.accent1],
    ["FaBullseye", "Merge в main", "только при зелёном CI", C.text2],
  ];
  const fw = 12.1 / 5;
  for (let i = 0; i < flow.length; i++) {
    const [ic, h, d, col] = flow[i];
    const x = 0.6 + i * fw;
    await iconCircle(s, ic, x + fw / 2 - 0.5, 1.6, 1.0, col, `flow-${i}`);
    if (i < flow.length - 1) s.addShape(pres.shapes.LINE, { x: x + fw / 2 + 0.65, y: 2.1, w: fw - 1.3, h: 0, line: { color: C.accent6, width: 1.5, endArrowType: "triangle" }, objectName: `flow-arrow-${i}` });
    text(s, h, { x: x + 0.1, y: 2.75, w: fw - 0.2, h: 0.4, align: "center", fontSize: 17, bold: true });
    text(s, d, { x: x + 0.15, y: 3.2, w: fw - 0.3, h: 0.9, align: "center", fontSize: 14, color: MUTED });
  }
  card(s, 0.6, 4.45, 5.9, 1.75, "repo-card");
  text(s, [
    { text: "Структура репозитория", options: { bold: true, fontSize: 16, breakLine: true } },
    { text: "core-cpp/ · web-kotlin/ · data/ · tools/", options: { breakLine: true } },
    { text: "docs/ — SRS, архитектура, UML, сравнение, тесты", options: { breakLine: true } },
    { text: "compose.yaml · Dockerfile · .github/workflows/ci.yml", options: {} },
  ], { x: 0.85, y: 4.65, w: 5.45, h: 1.45, fontSize: 14 });
  card(s, 6.8, 4.45, 5.9, 1.75, "run-card");
  text(s, [
    { text: "Запуск одной командой", options: { bold: true, fontSize: 16, breakLine: true } },
    { text: "docker compose up --build -d", options: { breakLine: true } },
    { text: "→ web (Kotlin + C++) + db (PostgreSQL)", options: { breakLine: true } },
    { text: "docker compose --profile test run --rm tests", options: { fontSize: 12 } },
  ], { x: 7.05, y: 4.65, w: 5.45, h: 1.45, fontSize: 14, fontFace: "Courier New" });
  s.addNotes("Работа велась в Git: отдельная ветка, коммиты по этапам, изменения вливаются в main через Pull Request. GitHub Actions на каждом PR собирает C++ и запускает Google Test, затем собирает Kotlin и запускает JUnit, включая тест паритета. Весь стек — веб-приложение и PostgreSQL — поднимается одной командой docker compose up, а тесты запускаются в контейнере отдельным профилем.");

  // ===== 18. Итоги =====
  s = pres.addSlide({ masterName: "TITLE", sectionTitle: "Тестирование и Git" });
  s.addText("Итоги", { placeholder: "title" });
  s.addText([
    { text: "Полный цикл: от SRS до CI", options: { breakLine: true } },
    { text: "Спасибо! Готов ответить на вопросы", options: { fontSize: 16, color: C.accent1 } },
  ], { placeholder: "body" });
  const results = [
    ["Работающее приложение", "котировки Мосбиржи, PostgreSQL, веб + CLI, docker compose"],
    ["Kotlin — сервис, C++ — лаборатория", "бэктест, Монте-Карло, портфель; паритет ядер 1e-6"],
    ["170 тестов, 8 дефектов исправлено", "Google Test, JUnit 5, Kotest, PostgreSQL, заглушка ISS"],
    ["Дальше", "другие стратегии, дивиденды, внутридневные данные, учётные записи"],
  ];
  results.forEach(([h, d], i) => {
    const y = 1.25 + i * 1.3;
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 7.6, y, w: 5.1, h: 1.1, rectRadius: 0.08, fill: { color: C.background1, transparency: 90 }, line: { type: "none" }, objectName: `result-${i}` });
    text(s, [{ text: h, options: { bold: true, fontSize: 17, color: C.background1, breakLine: true } }, { text: d, options: { fontSize: 14, color: C.background2 } }], { x: 7.85, y: y + 0.15, w: 4.7, h: 0.85 });
  });
  s.addNotes("Итог: проект прошёл полный цикл. Есть работающее веб-приложение на реальных котировках Московской биржи с хранением в PostgreSQL, консольная утилита, разделение ролей: Kotlin — сервис, C++ — квант-лаборатория, 170 тестов, CI и запуск одной командой через docker compose. Развивать можно так: другие стратегии в лаборатории, учёт дивидендов, внутридневные данные и учётные записи пользователей. Спасибо, готов ответить на вопросы.");

  await pres.writeFile({ fileName: OUT });
  if (APPLY_THEME) {
    const { applyTheme } = require(APPLY_THEME);
    await applyTheme(OUT, THEME);
  }
  console.log("written", OUT);
}

build().catch((e) => { console.error(e); process.exit(1); });
