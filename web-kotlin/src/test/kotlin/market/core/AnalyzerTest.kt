package market.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AnalyzerTest {
    private fun series(closes: List<Double>) = closes.mapIndexed { i, c ->
        Candle("2024-%02d-%02d".format(i / 28 + 1, i % 28 + 1), c, c, c, c, 100)
    }

    private fun linear(n: Int, start: Double, step: Double) = List(n) { start + step * it }
    private val p = AnalysisParams(5, 20, 14, 0.0)

    @Test
    fun `rising market`() {
        val r = Analyzer.analyze("UP", series(linear(40, 100.0, 1.0)), p)
        r.count shouldBe 40
        r.trend shouldBe Trend.UPTREND
        r.rsiZone shouldBe RsiZone.OVERBOUGHT
        r.totalReturnPct shouldBe (39.0 plusOrMinus 1e-9)
        r.maxDrawdownPct shouldBe 0.0
        r.sharpeRatio shouldBeGreaterThan 0.0
        r.engine shouldBe "kotlin"
    }

    @Test
    fun `falling market`() {
        val r = Analyzer.analyze("DOWN", series(linear(40, 200.0, -2.0)), p)
        r.trend shouldBe Trend.DOWNTREND
        r.rsiZone shouldBe RsiZone.OVERSOLD
        r.maxDrawdownPct shouldBe ((200.0 - 122.0) / 200.0 * 100 plusOrMinus 1e-9)
    }

    @Test
    fun `flat market`() {
        val r = Analyzer.analyze("FLAT", series(List(40) { 50.0 }), p)
        r.trend shouldBe Trend.SIDEWAYS
        r.rsiZone shouldBe RsiZone.NEUTRAL
        r.annualVolatilityPct shouldBe 0.0
        r.sharpeRatio shouldBe 0.0
    }

    @Test
    fun `minimum length boundary`() {
        Analyzer.analyze("T", series(linear(21, 10.0, 0.1)), p)
        shouldThrow<MarketDataException> { Analyzer.analyze("T", series(linear(20, 10.0, 0.1)), p) }
    }

    @Test
    fun `validates params`() {
        val data = series(linear(50, 10.0, 0.1))
        shouldThrow<MarketDataException> { Analyzer.analyze(" ", data) }
        shouldThrow<MarketDataException> { Analyzer.analyze("T", data, AnalysisParams(20, 20)) }
        shouldThrow<MarketDataException> { Analyzer.analyze("T", data, AnalysisParams(0, 20)) }
        shouldThrow<MarketDataException> { Analyzer.analyze("T", data, AnalysisParams(5, 20, 0)) }
    }
}
