package market.web

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import market.core.AnalysisParams
import market.core.Analyzer
import market.core.CsvLoader
import market.core.TestData
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import market.web.storage.DemoDataSeeder
import market.web.storage.InMemoryRepository
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Сравнивает результаты C++ ядра и Kotlin-порта на одних и тех же данных.
 * Пропускается, если C++ CLI не собран (core-cpp/build/market_cli).
 */
class EngineParityTest {
    private val cpp = CppEngine.locate()

    @ParameterizedTest
    @ValueSource(strings = ["DEMO_TECH", "DEMO_BANK", "DEMO_ENERGY", "DEMO_RETAIL"])
    fun `cpp and kotlin produce the same report`(ticker: String) {
        assumeTrue(cpp.available, "C++ CLI is not built")
        val file = File(TestData.dir, "$ticker.csv")
        val params = AnalysisParams(7, 25, 14, 5.0)
        val c = cpp.analyze(file, ticker, params)
        val k = Analyzer.analyze(ticker, CsvLoader.loadFile(file), params)

        c.engine shouldBe "cpp"
        c.count shouldBe k.count
        c.trend shouldBe k.trend
        c.rsiZone shouldBe k.rsiZone
        c.signals shouldBe k.signals
        val eps = 1e-6
        c.totalReturnPct shouldBe (k.totalReturnPct plusOrMinus eps)
        c.annualVolatilityPct shouldBe (k.annualVolatilityPct plusOrMinus eps)
        c.maxDrawdownPct shouldBe (k.maxDrawdownPct plusOrMinus eps)
        c.sharpeRatio shouldBe (k.sharpeRatio plusOrMinus eps)
        c.rsi shouldBe (k.rsi plusOrMinus eps)
        c.series.smaSlow.zip(k.series.smaSlow).forEach { (a, b) ->
            if (b == null) a shouldBe null else a!! shouldBe (b plusOrMinus eps)
        }
    }

    @Test
    fun `service runs cpp engine on quotes from storage`() {
        assumeTrue(cpp.available, "C++ CLI is not built")
        val repo = InMemoryRepository().also { DemoDataSeeder.seed(it, TestData.dir) }
        val service = MarketService(repo, cpp)
        val c = service.analyze("DEMO_ENERGY", AnalysisParams(), Engine.CPP)
        val k = service.analyze("DEMO_ENERGY", AnalysisParams(), Engine.KOTLIN)
        c.engine shouldBe "cpp"
        c.signals shouldBe k.signals
        c.sharpeRatio shouldBe (k.sharpeRatio plusOrMinus 1e-9)
        service.run(c.runId!!).engine shouldBe "cpp"
    }
}
