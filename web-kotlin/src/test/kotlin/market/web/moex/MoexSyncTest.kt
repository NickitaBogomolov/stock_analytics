package market.web.moex

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import market.core.Candle
import market.core.TestData
import market.web.storage.InMemoryRepository
import market.web.storage.InstrumentSource
import org.junit.jupiter.api.Test
import java.time.LocalDate

class MoexSyncTest {
    private val today = LocalDate.parse("2026-11-02")

    /** Биржа-заглушка: N торговых дней до «сегодня», цена растёт на 1 в день. */
    private class FakeProvider(var days: Int = 40, var lastClose: Double? = null) : QuoteProvider {
        val calls = mutableListOf<Pair<String, LocalDate>>()
        var unavailable = false
        override fun fetch(ticker: String, from: LocalDate): MoexSecurity {
            calls += ticker to from
            if (unavailable) throw MoexUnavailableException("down")
            if (ticker == "NOPE") throw MoexNotFoundException(ticker)
            val start = LocalDate.parse("2026-11-02").minusDays(days.toLong() - 1)
            val candles = (0 until days).map { i ->
                val p = 100.0 + i
                val close = if (i == days - 1) lastClose ?: p else p
                Candle(start.plusDays(i.toLong()).toString(), p, maxOf(p, close) + 1, minOf(p, close) - 1, close, 10)
            }.filter { it.date >= from.toString() }
            return MoexSecurity(ticker, "Name of $ticker", candles)
        }
    }

    @Test
    fun `imports configured tickers with names`() {
        val repo = InMemoryRepository()
        val p = FakeProvider()
        val r = MoexSync(repo, p, MoexConfig(tickers = listOf("IMOEX", "SBER")), { today }).syncAll(null)
        r.imported shouldBe 2
        repo.instruments().map { it.ticker } shouldContainExactly listOf("IMOEX", "SBER")
        repo.instruments().first().name shouldBe "Name of IMOEX"
        repo.instruments().all { it.source == "moex" && it.syncedAt != null } shouldBe true
        p.calls.first().second shouldBe today.minusYears(3)
    }

    @Test
    fun `refresh overwrites the last candle and requests only recent days`() {
        val repo = InMemoryRepository()
        val p = FakeProvider()
        val sync = MoexSync(repo, p, MoexConfig(tickers = listOf("SBER")), { today })
        sync.import("SBER")
        p.lastClose = 150.0
        val info = sync.refresh("SBER")
        info.sessions shouldBe 40
        info.lastClose shouldBe 150.0
        p.calls.last().second shouldBe today.minusDays(7)
    }

    @Test
    fun `unknown ticker is reported as failed, others continue`() {
        val repo = InMemoryRepository()
        val r = MoexSync(repo, FakeProvider(), MoexConfig(tickers = listOf("NOPE", "SBER")), { today }).syncAll(null)
        r.failed shouldBe listOf("NOPE")
        repo.instruments() shouldHaveSize 1
    }

    @Test
    fun `exchange down and empty storage falls back to demo data`() {
        val repo = InMemoryRepository()
        val p = FakeProvider().apply { unavailable = true }
        val r = MoexSync(repo, p, MoexConfig(tickers = listOf("IMOEX", "SBER", "GAZP")), { today }).syncAll(TestData.dir)
        p.calls shouldHaveSize 1 // после первой сетевой ошибки остальные не дёргаем
        r.failed shouldBe listOf("IMOEX", "SBER", "GAZP")
        r.demoImported shouldBe 4
        repo.instruments().all { it.source == "demo" } shouldBe true
    }

    @Test
    fun `exchange down keeps existing data and adds no demo`() {
        val repo = InMemoryRepository()
        repo.saveInstrument("SBER", InstrumentSource.MOEX, FakeProvider().fetch("SBER", today.minusYears(1)).candles)
        val p = FakeProvider().apply { unavailable = true }
        val r = MoexSync(repo, p, MoexConfig(tickers = listOf("SBER")), { today }).syncAll(TestData.dir)
        r.demoImported shouldBe 0
        repo.instruments().single().ticker shouldBe "SBER"
    }

    @Test
    fun `disabled exchange only seeds demo data`() {
        val repo = InMemoryRepository()
        val p = FakeProvider()
        MoexSync(repo, p, MoexConfig(enabled = false), { today }).syncAll(TestData.dir).demoImported shouldBe 4
        p.calls shouldHaveSize 0
    }
}
