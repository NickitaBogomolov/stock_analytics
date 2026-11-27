package market.core

import kotlin.math.abs
import kotlin.math.sqrt

/** Технические индикаторы и метрики риска. Порт Indicators.cpp. */
object Indicators {
    const val TRADING_DAYS = 252.0

    private fun checkPeriod(period: Int, size: Int, minExtra: Int = 0) {
        require(period > 0) { "period must be positive" }
        require(size >= period + minExtra) { "not enough data for period $period" }
    }

    fun sma(values: List<Double>, period: Int): List<Double?> {
        checkPeriod(period, values.size)
        var sum = 0.0
        return values.indices.map { i ->
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            if (i + 1 >= period) sum / period else null
        }
    }

    fun ema(values: List<Double>, period: Int): List<Double?> {
        checkPeriod(period, values.size)
        val k = 2.0 / (period + 1)
        val out = MutableList<Double?>(values.size) { null }
        var prev = values.take(period).average()
        out[period - 1] = prev
        for (i in period until values.size) {
            prev = values[i] * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    fun rsi(values: List<Double>, period: Int): List<Double?> {
        checkPeriod(period, values.size, 1)
        fun value(g: Double, l: Double) = if (l == 0.0) (if (g == 0.0) 50.0 else 100.0) else 100.0 - 100.0 / (1 + g / l)
        val out = MutableList<Double?>(values.size) { null }
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = values[i] - values[i - 1]
            if (d > 0) gain += d else loss += abs(d)
        }
        gain /= period
        loss /= period
        out[period] = value(gain, loss)
        for (i in period + 1 until values.size) {
            val d = values[i] - values[i - 1]
            gain = (gain * (period - 1) + maxOf(d, 0.0)) / period
            loss = (loss * (period - 1) + maxOf(-d, 0.0)) / period
            out[i] = value(gain, loss)
        }
        return out
    }

    fun dailyReturns(closes: List<Double>): List<Double> =
        closes.zipWithNext { prev, cur ->
            require(prev > 0) { "price must be positive" }
            cur / prev - 1.0
        }

    private fun sampleStd(v: List<Double>): Double {
        require(v.size >= 2) { "need at least 2 returns" }
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
    }

    fun annualizedVolatility(returns: List<Double>): Double = sampleStd(returns) * sqrt(TRADING_DAYS)

    fun maxDrawdown(closes: List<Double>): Double {
        var peak = 0.0
        var worst = 0.0
        for (c in closes) {
            peak = maxOf(peak, c)
            if (peak > 0) worst = maxOf(worst, (peak - c) / peak)
        }
        return worst
    }

    fun sharpeRatio(returns: List<Double>, riskFreeAnnual: Double): Double {
        val sd = sampleStd(returns)
        if (sd == 0.0) return 0.0
        return (returns.average() * TRADING_DAYS - riskFreeAnnual) / (sd * sqrt(TRADING_DAYS))
    }

    fun crossoverSignals(dates: List<String>, closes: List<Double>, fast: Int, slow: Int): List<TradeSignal> {
        require(fast < slow) { "fast period must be less than slow period" }
        require(dates.size == closes.size) { "dates and closes size mismatch" }
        val f = sma(closes, fast)
        val s = sma(closes, slow)
        return (slow until closes.size).mapNotNull { i ->
            val prevDiff = f[i - 1]!! - s[i - 1]!!
            val diff = f[i]!! - s[i]!!
            when {
                prevDiff <= 0 && diff > 0 -> TradeSignal(dates[i], SignalType.BUY, closes[i])
                prevDiff >= 0 && diff < 0 -> TradeSignal(dates[i], SignalType.SELL, closes[i])
                else -> null
            }
        }
    }

    /** Корреляция Пирсона (расширение Kotlin-версии, в C++ ядре отсутствует). */
    fun correlation(a: List<Double>, b: List<Double>): Double {
        require(a.size == b.size && a.size >= 2) { "series must have equal length >= 2" }
        val ma = a.average()
        val mb = b.average()
        var cov = 0.0
        var va = 0.0
        var vb = 0.0
        for (i in a.indices) {
            cov += (a[i] - ma) * (b[i] - mb)
            va += (a[i] - ma) * (a[i] - ma)
            vb += (b[i] - mb) * (b[i] - mb)
        }
        return if (va == 0.0 || vb == 0.0) 0.0 else cov / sqrt(va * vb)
    }
}
