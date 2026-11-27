package market.web.moex

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import market.web.CppEngine
import market.web.MarketService
import market.web.module
import market.web.storage.InMemoryRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class MoexApiTest {
    private val iss = FakeIss(pageSize = 500).apply {
        shares["SBER"] = (1..30).map { d ->
            val p = 300.0 + d
            FakeIss.candle("2026-10-%02d".format(d), p, p + 0.5, p + 1, p - 1)
        }
        names["SBER"] = "Сбербанк"
    }

    @AfterEach
    fun tearDown() = iss.close()

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        val repo = InMemoryRepository()
        val sync = MoexSync(repo, MoexClient(iss.url), MoexConfig(tickers = emptyList()))
        application { module(MarketService(repo, CppEngine(null), sync)) }
        block()
    }

    @Test
    fun `import from exchange, analyze, refresh`() = app {
        val created = client.post("/api/instruments/sber/moex")
        created.status shouldBe HttpStatusCode.Created
        created.bodyAsText() shouldContain "\"name\":\"Сбербанк\""
        created.bodyAsText() shouldContain "\"source\":\"moex\""
        client.get("/api/instruments/SBER/analysis?fast=5&slow=20").status shouldBe HttpStatusCode.OK
        client.post("/api/instruments/SBER/refresh").status shouldBe HttpStatusCode.OK
        client.get("/api/sync").bodyAsText() shouldContain "\"moexEnabled\":true"
    }

    @Test
    fun `errors map to 404, 400 and 502`() = app {
        client.post("/api/instruments/NOPE/moex").status shouldBe HttpStatusCode.NotFound
        client.post("/api/instruments/MY/refresh").status shouldBe HttpStatusCode.NotFound
        client.post("/api/instruments/MY") { setBody("Date,Open,High,Low,Close,Volume\n2026-10-01,1,2,1,1,1\n") }
        client.post("/api/instruments/MY/refresh").status shouldBe HttpStatusCode.BadRequest // не с биржи
        iss.failWith = 500
        client.post("/api/instruments/GAZP/moex").status shouldBe HttpStatusCode.BadGateway
    }
}
