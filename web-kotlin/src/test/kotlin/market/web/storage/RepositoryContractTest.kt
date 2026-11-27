package market.web.storage

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import market.core.AnalysisParams
import market.core.Analyzer
import market.core.Candle
import market.core.CsvLoader
import market.core.TestData
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/** Одинаковый набор проверок для всех реализаций хранилища. */
abstract class RepositoryContractTest {
    protected abstract fun newRepo(): MarketRepository
    private lateinit var repo: MarketRepository

    private val tech: List<Candle> by lazy { CsvLoader.loadFile(File(TestData.dir, "DEMO_TECH.csv")) }
    private val bank: List<Candle> by lazy { CsvLoader.loadFile(File(TestData.dir, "DEMO_BANK.csv")) }

    @BeforeEach
    fun setUp() {
        repo = newRepo()
    }

    @Test
    fun `saves instrument and reads quotes back unchanged`() {
        val info = repo.saveInstrument("DEMO_TECH", InstrumentSource.DEMO, tech)
        info.sessions shouldBe 500
        repo.exists("DEMO_TECH") shouldBe true
        repo.candles("DEMO_TECH") shouldBe tech
        repo.candles("NOPE").shouldBeNull()
        repo.instruments().single() shouldBe info
    }

    @Test
    fun `re-import replaces quotes and source`() {
        repo.saveInstrument("T", InstrumentSource.DEMO, tech)
        repo.saveInstrument("T", InstrumentSource.UPLOAD, tech.take(40))
        repo.candles("T")!! shouldHaveSize 40
        repo.instruments().single().source shouldBe "upload"
    }

    @Test
    fun `upsert adds new days and overwrites existing ones`() {
        repo.saveInstrument("SBER", InstrumentSource.MOEX, tech.take(100), name = "Сбербанк")
        val last = tech[99].copy(close = tech[99].close + 1, high = tech[99].high + 1)
        val info = repo.upsertQuotes("SBER", listOf(last) + tech.subList(100, 110))
        info.sessions shouldBe 110
        info.name shouldBe "Сбербанк"
        info.source shouldBe "moex"
        (info.syncedAt != null) shouldBe true
        repo.candles("SBER")!![99] shouldBe last
        repo.candles("SBER") shouldBe tech.take(99) + last + tech.subList(100, 110)
        shouldThrowAny { repo.upsertQuotes("GHOST", tech.take(1)) }
    }

    @Test
    fun `lab runs are stored newest first and removed with instrument`() {
        repo.saveInstrument("A", InstrumentSource.DEMO, tech)
        repo.saveInstrument("B", InstrumentSource.DEMO, bank)
        val first = repo.saveLabRun("montecarlo", listOf("A"), "VaR95 10 %", """{"paths":1000}""", """{"var95":10.5}""", 12.5)
        val second = repo.saveLabRun("portfolio", listOf("B", "A"), "max sharpe", """{"samples":5}""", """{"x":[1,2]}""", 3.0)
        repo.labRuns(10).map { it.labRunId } shouldBe listOf(second, first)
        repo.labRuns(10).first().tickers shouldBe listOf("B", "A")
        val rec = repo.labRun(first).shouldNotBeNull()
        rec.summary.headline shouldBe "VaR95 10 %"
        rec.summary.computeMs shouldBe 12.5
        rec.resultJson.replace(" ", "") shouldBe """{"var95":10.5}"""
        repo.labRun(9999).shouldBeNull()
        shouldThrowAny { repo.saveLabRun("backtest", listOf("GHOST"), "x", "{}", "{}", 1.0) }
        repo.deleteInstrument("A")
        repo.labRun(first).shouldBeNull()
    }

    @Test
    fun `instruments are sorted by ticker`() {
        repo.saveInstrument("B", InstrumentSource.DEMO, bank)
        repo.saveInstrument("A", InstrumentSource.DEMO, tech)
        repo.instruments().map { it.ticker } shouldBe listOf("A", "B")
    }

    @Test
    fun `analysis run round-trips with series and signals`() {
        repo.saveInstrument("DEMO_TECH", InstrumentSource.DEMO, tech)
        val report = Analyzer.analyze("DEMO_TECH", tech, AnalysisParams(7, 25, 14, 3.5))
        val id = repo.saveRun(report)
        val loaded = repo.run(id).shouldNotBeNull()
        loaded shouldBe report.copy(runId = id)
        repo.run(id + 1000).shouldBeNull()
    }

    @Test
    fun `run history is newest first and filtered by ticker`() {
        repo.saveInstrument("A", InstrumentSource.DEMO, tech)
        repo.saveInstrument("B", InstrumentSource.DEMO, bank)
        val a1 = repo.saveRun(Analyzer.analyze("A", tech))
        val b1 = repo.saveRun(Analyzer.analyze("B", bank))
        val a2 = repo.saveRun(Analyzer.analyze("A", tech, AnalysisParams(5, 20)))
        repo.runs(null, 10).map { it.runId } shouldBe listOf(a2, b1, a1)
        repo.runs("A", 10).map { it.runId } shouldBe listOf(a2, a1)
        repo.runs("A", 1).single().params.fastPeriod shouldBe 5
        repo.runs("B", 10).single().sharpeRatio shouldBe (Analyzer.analyze("B", bank).sharpeRatio plusOrMinus 1e-12)
    }

    @Test
    fun `deleting instrument removes its runs`() {
        repo.saveInstrument("A", InstrumentSource.DEMO, tech)
        val id = repo.saveRun(Analyzer.analyze("A", tech))
        repo.deleteInstrument("A") shouldBe true
        repo.deleteInstrument("A") shouldBe false
        repo.run(id).shouldBeNull()
        repo.runs(null, 10).shouldBeEmpty()
        repo.instruments().shouldBeEmpty()
    }

    @Test
    fun `run for unknown instrument is rejected`() {
        shouldThrowAny { repo.saveRun(Analyzer.analyze("GHOST", tech)) }
    }

    @Test
    fun `comparison gets an id`() {
        repo.saveInstrument("A", InstrumentSource.DEMO, tech)
        repo.saveInstrument("B", InstrumentSource.DEMO, bank)
        val rows = listOf("A" to tech, "B" to bank).map { (t, c) ->
            val r = Analyzer.analyze(t, c)
            CompareRow(t, r.totalReturnPct, r.annualVolatilityPct, r.maxDrawdownPct, r.sharpeRatio, r.rsi, r.trend.name)
        }
        val result = CompareResult(tech.map { it.date }, emptyMap(), rows, listOf(listOf(1.0, 0.4), listOf(0.4, 1.0)))
        val first = repo.saveComparison(result, AnalysisParams())
        repo.saveComparison(result, AnalysisParams()) shouldBe first + 1
    }
}

class InMemoryRepositoryTest : RepositoryContractTest() {
    override fun newRepo(): MarketRepository = InMemoryRepository()
}

class PostgresRepositoryTest : RepositoryContractTest() {
    override fun newRepo(): MarketRepository = PostgresRepository(TestDb.freshDataSource())

    @Test
    fun `database constraints reject inconsistent quotes`() {
        val ds = TestDb.freshDataSource()
        val repo = PostgresRepository(ds)
        // обходим валидацию CsvLoader: high < close должен отсечь CHECK в БД
        shouldThrowAny { repo.saveInstrument("BAD", InstrumentSource.UPLOAD, listOf(Candle("2024-01-02", 10.0, 9.0, 8.0, 10.0, 1))) }
        repo.exists("BAD") shouldBe false // транзакция откатилась целиком
    }

    @Test
    fun `comparison is stored with items and correlation matrix`() {
        val ds = TestDb.freshDataSource()
        val repo = PostgresRepository(ds)
        val tech = CsvLoader.loadFile(File(TestData.dir, "DEMO_TECH.csv"))
        val bank = CsvLoader.loadFile(File(TestData.dir, "DEMO_BANK.csv"))
        repo.saveInstrument("A", InstrumentSource.DEMO, tech)
        repo.saveInstrument("B", InstrumentSource.DEMO, bank)
        val rows = listOf(CompareRow("A", 1.0, 2.0, 3.0, 0.5, 50.0, "UPTREND"), CompareRow("B", 1.0, 2.0, 3.0, 0.5, 50.0, "SIDEWAYS"))
        val id = repo.saveComparison(CompareResult(listOf("2024-01-02"), emptyMap(), rows, listOf(listOf(1.0, 0.3), listOf(0.3, 1.0))), AnalysisParams())
        ds.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM correlation_cell WHERE comparison_id = ?").use { st ->
                st.setLong(1, id)
                st.executeQuery().use { rs -> rs.next(); rs.getInt(1) shouldBe 4 }
            }
            c.prepareStatement("SELECT ticker FROM comparison_item WHERE comparison_id = ? ORDER BY position").use { st ->
                st.setLong(1, id)
                st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() shouldBe listOf("A", "B") }
            }
        }
    }
}
