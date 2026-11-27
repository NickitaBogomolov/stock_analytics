package market.web

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import market.core.AnalysisReport
import market.core.TestData
import market.web.storage.CompareResult
import market.web.storage.DemoDataSeeder
import market.web.storage.InMemoryRepository
import market.web.storage.MarketRepository
import market.web.storage.PostgresRepository
import market.web.storage.RunSummary
import market.web.storage.TestDb
import io.ktor.client.request.delete
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Test

/** Тесты REST API; выполняются на двух хранилищах — в памяти и в PostgreSQL. */
abstract class ApiTestBase {
    protected abstract fun newRepo(): MarketRepository
    private val json = Json { ignoreUnknownKeys = true }

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val repo = newRepo()
        DemoDataSeeder.seed(repo, TestData.dir)
        application { module(MarketService(repo, CppEngine(null))) }
        block()
    }

    @Test
    fun `lists demo instruments`() = withApp {
        val res = client.get("/api/instruments")
        res.status shouldBe HttpStatusCode.OK
        json.parseToJsonElement(res.bodyAsText()).jsonArray shouldHaveSize 4
    }

    @Test
    fun `analysis of a demo instrument`() = withApp {
        val res = client.get("/api/instruments/DEMO_BANK/analysis?fast=5&slow=20")
        res.status shouldBe HttpStatusCode.OK
        val r = json.decodeFromString(AnalysisReport.serializer(), res.bodyAsText())
        r.ticker shouldBe "DEMO_BANK"
        r.params.fastPeriod shouldBe 5
        r.series.smaSlow.take(19).all { it == null } shouldBe true
    }

    @Test
    fun `unknown instrument gives 404, bad ticker gives 400`() = withApp {
        client.get("/api/instruments/NOPE/analysis").status shouldBe HttpStatusCode.NotFound
        client.get("/api/instruments/..%2Fsecret/analysis").status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `invalid parameters give 400 or 422`() = withApp {
        client.get("/api/instruments/DEMO_BANK/analysis?fast=abc").status shouldBe HttpStatusCode.BadRequest
        client.get("/api/instruments/DEMO_BANK/analysis?fast=0").status shouldBe HttpStatusCode.BadRequest
        client.get("/api/instruments/DEMO_BANK/analysis?rf=nan").status shouldBe HttpStatusCode.BadRequest
        client.get("/api/instruments/DEMO_BANK/analysis?engine=java").status shouldBe HttpStatusCode.BadRequest
        client.get("/api/instruments/DEMO_BANK/analysis?fast=40&slow=30").status shouldBe HttpStatusCode.UnprocessableEntity
    }

    @Test
    fun `cpp engine unavailable gives 400`() = withApp {
        client.get("/api/instruments/DEMO_BANK/analysis?engine=cpp").status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `upload of a broken csv reports the line`() = withApp {
        val res = client.post("/api/analysis?ticker=MY") {
            setBody("Date,Open,High,Low,Close,Volume\n2024-01-02,10,11,9,10,1\n2024-01-03,10,9,9,10,1\n")
        }
        res.status shouldBe HttpStatusCode.UnprocessableEntity
        val body = json.parseToJsonElement(res.bodyAsText()).jsonObject
        body["line"]!!.jsonPrimitive.int shouldBe 3
        body["error"]!!.jsonPrimitive.content shouldContain "OHLC"
    }

    @Test
    fun `upload of a valid csv`() = withApp {
        val csv = TestData.dir.resolve("DEMO_RETAIL.csv").readText()
        val res = client.post("/api/analysis?ticker=MY") { setBody(csv) }
        res.status shouldBe HttpStatusCode.OK
        json.decodeFromString(AnalysisReport.serializer(), res.bodyAsText()).count shouldBe 500
    }

    @Test
    fun `compare returns correlation matrix`() = withApp {
        val res = client.get("/api/compare?tickers=DEMO_TECH,DEMO_BANK,DEMO_ENERGY")
        res.status shouldBe HttpStatusCode.OK
        val r = json.decodeFromString(CompareResult.serializer(), res.bodyAsText())
        r.rows shouldHaveSize 3
        r.correlation[0][0] shouldBe 1.0
        r.normalized.getValue("DEMO_TECH").first() shouldBe 100.0
        client.get("/api/compare?tickers=DEMO_TECH").status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `serves web ui`() = withApp {
        val res = client.get("/")
        res.status shouldBe HttpStatusCode.OK
        res.bodyAsText() shouldContain "<html"
    }

    @Test
    fun `health reports storage`() = withApp {
        val res = client.get("/api/health")
        res.status shouldBe HttpStatusCode.OK
        res.bodyAsText() shouldContain "\"storageHealthy\":true"
    }

    @Test
    fun `analysis is saved to history and can be reopened`() = withApp {
        val r = json.decodeFromString(AnalysisReport.serializer(), client.get("/api/instruments/DEMO_TECH/analysis?fast=5&slow=20").bodyAsText())
        val runId = r.runId!!
        val history = json.decodeFromString(ListSerializer(RunSummary.serializer()), client.get("/api/runs?ticker=DEMO_TECH").bodyAsText())
        history.first().runId shouldBe runId
        history.first().params.fastPeriod shouldBe 5
        val reopened = json.decodeFromString(AnalysisReport.serializer(), client.get("/api/runs/$runId").bodyAsText())
        reopened shouldBe r
        client.get("/api/runs/999999").status shouldBe HttpStatusCode.NotFound
        client.get("/api/runs/abc").status shouldBe HttpStatusCode.BadRequest
        client.get("/api/runs?limit=0").status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `import, list, analyze and delete own instrument`() = withApp {
        val csv = TestData.dir.resolve("DEMO_RETAIL.csv").readText()
        val created = client.post("/api/instruments/MY_STOCK") { contentType(ContentType.Text.CSV); setBody(csv) }
        created.status shouldBe HttpStatusCode.Created
        created.bodyAsText() shouldContain "\"source\":\"upload\""
        client.get("/api/instruments").bodyAsText() shouldContain "MY_STOCK"
        client.get("/api/instruments/MY_STOCK/analysis").status shouldBe HttpStatusCode.OK
        client.delete("/api/instruments/MY_STOCK").status shouldBe HttpStatusCode.NoContent
        client.delete("/api/instruments/MY_STOCK").status shouldBe HttpStatusCode.NotFound
        client.get("/api/runs?ticker=MY_STOCK").bodyAsText() shouldBe "[]"
    }

    @Test
    fun `broken import is rejected and nothing is stored`() = withApp {
        val res = client.post("/api/instruments/BROKEN") { setBody("Date,Open,High,Low,Close,Volume\n2024-02-30,10,11,9,10,1\n") }
        res.status shouldBe HttpStatusCode.UnprocessableEntity
        client.get("/api/instruments/BROKEN/candles").status shouldBe HttpStatusCode.NotFound
        client.post("/api/instruments/..bad..%2F") { setBody("x") }.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `comparison gets an id`() = withApp {
        val r = json.decodeFromString(CompareResult.serializer(), client.get("/api/compare?tickers=DEMO_TECH,DEMO_BANK").bodyAsText())
        (r.comparisonId != null) shouldBe true
    }
}

class InMemoryApiTest : ApiTestBase() {
    override fun newRepo(): MarketRepository = InMemoryRepository()
}

class PostgresApiTest : ApiTestBase() {
    override fun newRepo(): MarketRepository = PostgresRepository(TestDb.freshDataSource())
}
