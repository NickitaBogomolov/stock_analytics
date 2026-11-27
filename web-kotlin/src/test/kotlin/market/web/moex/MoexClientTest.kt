package market.web.moex

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import market.core.Candle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

class MoexClientTest {
    private val iss = FakeIss(pageSize = 2)
    private val client = MoexClient(iss.url)

    @AfterEach
    fun tearDown() = iss.close()

    @Test
    fun `loads shares with pagination, name and from filter`() {
        iss.shares["SBER"] = listOf(
            FakeIss.candle("2026-10-27", 300.0, 302.5, 303.0, 299.0, 1_000),
            FakeIss.candle("2026-10-28", 302.5, 305.0, 306.0, 301.0, 2_000),
            FakeIss.candle("2026-10-29", 305.0, 304.0, 307.5, 303.1, 3_000),
            FakeIss.candle("2026-10-30", 304.0, 310.2, 311.0, 303.5, 4_000),
            FakeIss.candle("2026-10-31", 310.2, 309.9, 312.0, 308.0, 5_000),
        )
        iss.names["SBER"] = "Сбербанк"
        val sec = client.fetch("sber", LocalDate.parse("2026-10-28"))
        sec.ticker shouldBe "SBER"
        sec.name shouldBe "Сбербанк"
        sec.candles shouldHaveSize 4
        sec.candles.first() shouldBe Candle("2026-10-28", 302.5, 306.0, 301.0, 305.0, 2_000)
        sec.candles.last().date shouldBe "2026-10-31"
        iss.requests.count { "candles.json" in it && "shares" in it } shouldBe 3 // 2 + 2 + пустая страница
        iss.requests.first() shouldContain "interval=24"
    }

    @Test
    fun `falls back to index market`() {
        iss.index["IMOEX"] = listOf(
            FakeIss.candle("2026-10-30", 2750.0, 2771.3, 2780.0, 2741.2, 0),
            FakeIss.candle("2026-10-31", 2771.3, 2760.1, 2790.4, 2755.0, 0),
        )
        iss.names["IMOEX"] = "Индекс МосБиржи"
        val sec = client.fetch("IMOEX", LocalDate.parse("2026-01-01"))
        sec.name shouldBe "Индекс МосБиржи"
        sec.candles.map { it.close } shouldBe listOf(2771.3, 2760.1)
        sec.candles.all { it.volume == 0L } shouldBe true
    }

    @Test
    fun `invalid candles are skipped, not fatal`() {
        iss.shares["GAZP"] = listOf(
            FakeIss.candle("2026-10-30", 120.0, 121.0, 119.0, 118.0), // high < close
            FakeIss.candle("2026-10-31", 121.0, 122.0, 123.0, 120.5),
        )
        client.fetch("GAZP", LocalDate.parse("2026-01-01")).candles.map { it.date } shouldBe listOf("2026-10-31")
    }

    @Test
    fun `unknown ticker`() {
        shouldThrow<MoexNotFoundException> { client.fetch("NOPE", LocalDate.parse("2026-01-01")) }
    }

    @Test
    fun `server error and network failure are reported as unavailable`() {
        iss.failWith = 503
        shouldThrow<MoexUnavailableException> { client.fetch("SBER", LocalDate.parse("2026-01-01")) }.message shouldContain "503"
        shouldThrow<MoexUnavailableException> { MoexClient("http://127.0.0.1:1").fetch("SBER", LocalDate.parse("2026-01-01")) }
    }
}
