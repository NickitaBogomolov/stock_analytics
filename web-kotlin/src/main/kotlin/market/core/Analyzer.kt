package market.core

/** Построение отчёта по инструменту. Порт Analyzer.cpp. */
object Analyzer {
    fun analyze(ticker: String, candles: List<Candle>, p: AnalysisParams = AnalysisParams()): AnalysisReport {
        if (ticker.isBlank()) throw MarketDataException("ticker must not be empty")
        if (p.fastPeriod <= 0 || p.fastPeriod >= p.slowPeriod) {
            throw MarketDataException("fast period must be positive and less than slow period")
        }
        if (p.rsiPeriod <= 0) throw MarketDataException("RSI period must be positive")
        val need = maxOf(p.slowPeriod, p.rsiPeriod) + 1
        if (candles.size < need) {
            throw MarketDataException("not enough quotes: need at least $need, got ${candles.size}")
        }

        val dates = candles.map { it.date }
        val close = candles.map { it.close }
        val returns = Indicators.dailyReturns(close)
        val smaFast = Indicators.sma(close, p.fastPeriod)
        val smaSlow = Indicators.sma(close, p.slowPeriod)
        val rsiSeries = Indicators.rsi(close, p.rsiPeriod)
        val rsi = rsiSeries.last()!!
        val fast = smaFast.last()!!
        val slow = smaSlow.last()!!
        val lastClose = close.last()

        val trend = when {
            fast > slow && lastClose > slow -> Trend.UPTREND
            fast < slow && lastClose < slow -> Trend.DOWNTREND
            else -> Trend.SIDEWAYS
        }
        val zone = when {
            rsi >= 70 -> RsiZone.OVERBOUGHT
            rsi <= 30 -> RsiZone.OVERSOLD
            else -> RsiZone.NEUTRAL
        }

        return AnalysisReport(
            ticker = ticker,
            params = p,
            count = candles.size,
            firstDate = dates.first(),
            lastDate = dates.last(),
            lastClose = lastClose,
            totalReturnPct = (close.last() / close.first() - 1.0) * 100.0,
            annualVolatilityPct = Indicators.annualizedVolatility(returns) * 100.0,
            maxDrawdownPct = Indicators.maxDrawdown(close) * 100.0,
            sharpeRatio = Indicators.sharpeRatio(returns, p.riskFreeRatePct / 100.0),
            rsi = rsi,
            trend = trend,
            rsiZone = zone,
            series = Series(dates, close, smaFast, smaSlow, rsiSeries),
            signals = Indicators.crossoverSignals(dates, close, p.fastPeriod, p.slowPeriod),
        )
    }
}
