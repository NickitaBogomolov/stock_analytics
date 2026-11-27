package market.web

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import market.core.TestData
import market.web.storage.DemoDataSeeder
import market.web.storage.InMemoryRepository
import market.web.storage.LabRunSummary
import market.web.storage.MarketRepository
import market.web.storage.PostgresRepository
import market.web.storage.TestDb
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Квант-лаборатория через REST: вычисления в настоящем C++ модуле, хранение — в памяти и PostgreSQL. */
abstract class LabApiTestBase {
    protected abstract fun newRepo(): MarketRepository
    private val json = Json { ignoreUnknownKeys = true }
    private val cpp = CppEngine.locate()

    private fun withLab(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        assumeTrue(cpp.available, "C++ CLI is not built")
        val repo = newRepo()
        DemoDataSeeder.seed(repo, TestData.dir)
        application { module(MarketService(repo, cpp), LabService(repo, cpp)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.postJson(url: String): LabRunResponse {
        val res = client.post(url)
        res.status shouldBe HttpStatusCode.OK
        return json.decodeFromString(LabRunResponse.serializer(), res.bodyAsText())
    }

    @Test
    fun `backtest optimizes on train part and reports out-of-sample`() = withLab {
        val r = postJson("/api/lab/backtest/DEMO_TECH?commission=0.1&train=0.7")
        val res = r.result.jsonObject
        res["kind"]!!.jsonPrimitive.content shouldBe "backtest"
        res["bestFast"]!!.jsonPrimitive.int.toDouble() shouldBeLessThanOrEqual res["bestSlow"]!!.jsonPrimitive.int.toDouble()
        res["combos"]!!.jsonPrimitive.int.toDouble() shouldBeGreaterThan 100.0
        res["equityStrategy"]!!.jsonArray shouldHaveSize 500
        r.summary.headline shouldContain "вне выборки"
        r.params.jsonObject["commissionPct"]!!.jsonPrimitive.double shouldBe 0.1
        r.summary.tickers shouldBe listOf("DEMO_TECH")
    }

    @Test
    fun `monte carlo returns risk measures and fan`() = withLab {
        val r = postJson("/api/lab/montecarlo/DEMO_BANK?paths=5000&horizon=63&seed=7")
        val res = r.result.jsonObject
        res["paths"]!!.jsonPrimitive.int shouldBe 5000
        res["fan"]!!.jsonArray shouldHaveSize 5
        res["fan"]!!.jsonArray[0].jsonArray shouldHaveSize 64
        res["cvar95"]!!.jsonPrimitive.double shouldBeGreaterThan res["var95"]!!.jsonPrimitive.double - 1e-9
        r.summary.headline shouldContain "VaR95"
    }

    @Test
    fun `portfolio finds optimal weights`() = withLab {
        val r = postJson("/api/lab/portfolio?tickers=DEMO_TECH,DEMO_BANK,DEMO_ENERGY&samples=5000")
        val res = r.result.jsonObject
        val w = res["maxSharpe"]!!.jsonObject["weights"]!!.jsonArray.map { it.jsonPrimitive.double }
        w shouldHaveSize 3
        (w.sum() - 1.0 < 1e-9) shouldBe true
        r.summary.tickers shouldBe listOf("DEMO_TECH", "DEMO_BANK", "DEMO_ENERGY")
    }

    @Test
    fun `runs are stored, listed and reopened`() = withLab {
        val a = postJson("/api/lab/montecarlo/DEMO_RETAIL?paths=1000&horizon=10")
        val b = postJson("/api/lab/backtest/DEMO_RETAIL")
        val list = json.decodeFromString(ListSerializer(LabRunSummary.serializer()), client.get("/api/lab/runs").bodyAsText())
        list.map { it.labRunId } shouldBe listOf(b.summary.labRunId, a.summary.labRunId)
        val reopened = json.decodeFromString(LabRunResponse.serializer(), client.get("/api/lab/runs/${a.summary.labRunId}").bodyAsText())
        reopened.result shouldBe a.result
        client.get("/api/lab/runs/99999").status shouldBe HttpStatusCode.NotFound
        client.delete("/api/instruments/DEMO_RETAIL") // демо удалять через API можно; история лаборатории уходит каскадом
        client.get("/api/lab/runs").bodyAsText() shouldBe "[]"
    }

    @Test
    fun `validation errors`() = withLab {
        client.post("/api/lab/montecarlo/DEMO_BANK?paths=10").status shouldBe HttpStatusCode.BadRequest
        client.post("/api/lab/montecarlo/DEMO_BANK?horizon=abc").status shouldBe HttpStatusCode.BadRequest
        client.post("/api/lab/backtest/DEMO_BANK?train=0.99").status shouldBe HttpStatusCode.BadRequest
        client.post("/api/lab/portfolio?tickers=DEMO_BANK").status shouldBe HttpStatusCode.BadRequest
        client.post("/api/lab/backtest/NOPE").status shouldBe HttpStatusCode.NotFound
    }
}

class InMemoryLabApiTest : LabApiTestBase() {
    override fun newRepo(): MarketRepository = InMemoryRepository()
}

class PostgresLabApiTest : LabApiTestBase() {
    override fun newRepo(): MarketRepository = PostgresRepository(TestDb.freshDataSource())
}

class LabUnavailableTest {
    @Test
    fun `lab without C++ module answers 503`() = testApplication {
        val repo = InMemoryRepository().also { DemoDataSeeder.seed(it, TestData.dir) }
        application { module(MarketService(repo, CppEngine(null)), LabService(repo, CppEngine(null))) }
        client.post("/api/lab/backtest/DEMO_TECH").status shouldBe HttpStatusCode.ServiceUnavailable
        client.get("/api/lab/status").bodyAsText() shouldContain "false"
    }
}
