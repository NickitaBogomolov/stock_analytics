package market.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class IndicatorsTest {
    @Test
    fun `sma computes rolling mean with warmup nulls`() {
        Indicators.sma(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3) shouldBe listOf(null, null, 2.0, 3.0, 4.0)
    }

    @Test
    fun `sma boundary periods`() {
        Indicators.sma(listOf(7.0, 8.0), 1) shouldBe listOf(7.0, 8.0)
        Indicators.sma(listOf(2.0, 4.0, 6.0), 3).last() shouldBe 4.0
        shouldThrow<IllegalArgumentException> { Indicators.sma(listOf(1.0, 2.0), 0) }
        shouldThrow<IllegalArgumentException> { Indicators.sma(listOf(1.0, 2.0), 3) }
        shouldThrow<IllegalArgumentException> { Indicators.sma(emptyList(), 1) }
    }

    @Test
    fun `ema seeds with sma`() {
        Indicators.ema(listOf(1.0, 2.0, 3.0, 4.0), 3) shouldBe listOf(null, null, 2.0, 3.0)
    }

    @Test
    fun `rsi extremes and neutral`() {
        Indicators.rsi(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), 3).last() shouldBe 100.0
        Indicators.rsi(listOf(6.0, 5.0, 4.0, 3.0, 2.0), 2).last() shouldBe 0.0
        Indicators.rsi(listOf(5.0, 5.0, 5.0, 5.0), 2).last() shouldBe 50.0
        Indicators.rsi(listOf(10.0, 11.0, 10.0), 2).last() shouldBe 50.0
        shouldThrow<IllegalArgumentException> { Indicators.rsi(listOf(1.0, 2.0, 3.0), 3) }
    }

    @Test
    fun `daily returns`() {
        val r = Indicators.dailyReturns(listOf(100.0, 110.0, 99.0))
        r shouldHaveSize 2
        r[0] shouldBe (0.10 plusOrMinus 1e-12)
        r[1] shouldBe (-0.10 plusOrMinus 1e-12)
        Indicators.dailyReturns(listOf(100.0)).shouldBeEmpty()
    }

    @Test
    fun `volatility and sharpe`() {
        Indicators.annualizedVolatility(listOf(0.01, 0.01, 0.01)) shouldBe 0.0
        Indicators.annualizedVolatility(listOf(0.01, -0.01)) shouldBe (sqrt(0.0002) * sqrt(252.0) plusOrMinus 1e-12)
        shouldThrow<IllegalArgumentException> { Indicators.annualizedVolatility(listOf(0.01)) }
        Indicators.sharpeRatio(listOf(0.01, 0.01), 0.0) shouldBe 0.0
        Indicators.sharpeRatio(listOf(0.02, -0.01, 0.015, 0.005), 0.0) shouldBeGreaterThan 0.0
        Indicators.sharpeRatio(listOf(-0.02, 0.01, -0.015, -0.005), 0.0) shouldBeLessThan 0.0
    }

    @Test
    fun `max drawdown`() {
        Indicators.maxDrawdown(listOf(100.0, 120.0, 90.0, 110.0, 60.0, 130.0)) shouldBe (0.5 plusOrMinus 1e-12)
        Indicators.maxDrawdown(listOf(1.0, 2.0, 3.0)) shouldBe 0.0
        Indicators.maxDrawdown(emptyList()) shouldBe 0.0
    }

    @Test
    fun `crossover detects buy then sell`() {
        val closes = listOf(10.0, 10.0, 10.0, 10.0, 12.0, 14.0, 16.0, 14.0, 10.0, 6.0, 4.0)
        val dates = closes.indices.map { "2024-01-${10 + it}" }
        val s = Indicators.crossoverSignals(dates, closes, 2, 4)
        s.map { it.type } shouldBe listOf(SignalType.BUY, SignalType.SELL)
        s[0].date shouldBe "2024-01-14"
        shouldThrow<IllegalArgumentException> { Indicators.crossoverSignals(dates, closes, 3, 3) }
        shouldThrow<IllegalArgumentException> { Indicators.crossoverSignals(listOf("a"), closes, 1, 2) }
    }

    @Test
    fun `correlation`() {
        Indicators.correlation(listOf(1.0, 2.0, 3.0), listOf(2.0, 4.0, 6.0)) shouldBe (1.0 plusOrMinus 1e-12)
        Indicators.correlation(listOf(1.0, 2.0, 3.0), listOf(3.0, 2.0, 1.0)) shouldBe (-1.0 plusOrMinus 1e-12)
        Indicators.correlation(listOf(1.0, 1.0, 1.0), listOf(3.0, 2.0, 1.0)) shouldBe 0.0
        shouldThrow<IllegalArgumentException> { Indicators.correlation(listOf(1.0), listOf(1.0)) }
    }
}
