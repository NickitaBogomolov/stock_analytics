#!/usr/bin/env python3
"""Генерирует СИНТЕТИЧЕСКИЕ котировки для демонстрации (не реальные рыночные данные).

Модель: геометрическое броуновское движение с несколькими режимами тренда.
Детерминировано (фиксированный seed), поэтому повторный запуск даёт те же файлы.

    python3 tools/generate_demo_data.py [output_dir]
"""
import csv
import datetime as dt
import math
import random
import sys
from pathlib import Path

# тикер: (начальная цена, годовая волатильность, режимы годового дрейфа, seed, бета к рынку)
INSTRUMENTS = {
    "DEMO_TECH": (120.0, 0.38, [0.45, -0.30, 0.60, 0.10], 11, 0.75),
    "DEMO_BANK": (250.0, 0.22, [0.12, 0.05, -0.15, 0.20], 22, 0.65),
    "DEMO_ENERGY": (80.0, 0.30, [-0.20, 0.35, 0.00, -0.10], 33, 0.35),
    "DEMO_RETAIL": (45.0, 0.26, [0.05, 0.25, -0.05, 0.15], 44, 0.55),
}
MARKET_SEED = 2024  # общий рыночный фактор -> реалистичные корреляции между бумагами
START = dt.date(2024, 1, 2)
SESSIONS = 500


def business_days(start: dt.date, n: int):
    day = start
    while n:
        if day.weekday() < 5:
            yield day
            n -= 1
        day += dt.timedelta(days=1)


def generate(ticker, price, vol, regimes, seed, beta, out_dir: Path):
    rng = random.Random(seed)
    market = random.Random(MARKET_SEED)
    dt_year = 1 / 252
    regime_len = SESSIONS // len(regimes) + 1
    rows = []
    for i, day in enumerate(business_days(START, SESSIONS)):
        drift = regimes[i // regime_len]
        shock = beta * market.gauss(0, 1) + math.sqrt(1 - beta**2) * rng.gauss(0, 1)
        open_ = price * (1 + rng.gauss(0, vol * math.sqrt(dt_year) / 3))
        close = price * math.exp((drift - vol**2 / 2) * dt_year + vol * math.sqrt(dt_year) * shock)
        spread = abs(rng.gauss(0, vol * math.sqrt(dt_year) / 2))
        high = max(open_, close) * (1 + spread)
        low = min(open_, close) * (1 - spread)
        volume = int(rng.uniform(0.6, 1.6) * 1_000_000 * (1 + abs(shock)))
        rows.append([day.isoformat(), f"{open_:.2f}", f"{high:.2f}", f"{low:.2f}", f"{close:.2f}", volume])
        price = close
    path = out_dir / f"{ticker}.csv"
    with path.open("w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["Date", "Open", "High", "Low", "Close", "Volume"])
        w.writerows(rows)
    print(f"{path}: {len(rows)} rows")


def main():
    out_dir = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "data")
    out_dir.mkdir(parents=True, exist_ok=True)
    for ticker, (price, vol, regimes, seed, beta) in INSTRUMENTS.items():
        generate(ticker, price, vol, regimes, seed, beta, out_dir)


if __name__ == "__main__":
    main()
