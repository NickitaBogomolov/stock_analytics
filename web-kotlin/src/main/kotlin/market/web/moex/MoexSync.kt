package market.web.moex

import market.web.storage.DemoDataSeeder
import market.web.storage.InstrumentSource
import market.web.storage.MarketRepository
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate

/** Настройки загрузки с Мосбиржи (переменные окружения). */
data class MoexConfig(
    val enabled: Boolean = true,
    val tickers: List<String> = DEFAULT_TICKERS,
    val historyYears: Long = 3,
    val refreshHours: Long = 6,
) {
    companion object {
        /** Индекс Мосбиржи и крупнейшие по капитализации акции из разных отраслей. */
        val DEFAULT_TICKERS = listOf("IMOEX", "SBER", "GAZP", "LKOH", "ROSN", "NVTK", "GMKN", "PLZL", "TATN", "YDEX", "MGNT", "VTBR")

        fun fromEnv(env: Map<String, String> = System.getenv()) = MoexConfig(
            enabled = env["MARKET_MOEX_ENABLED"]?.lowercase() !in setOf("false", "0", "no"),
            tickers = env["MARKET_MOEX_TICKERS"]?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() } ?: DEFAULT_TICKERS,
            historyYears = env["MARKET_MOEX_YEARS"]?.toLongOrNull()?.coerceIn(1, 20) ?: 3,
            refreshHours = env["MARKET_REFRESH_HOURS"]?.toLongOrNull()?.coerceIn(1, 168) ?: 6,
        )
    }
}

/** Импорт и обновление котировок с биржи в хранилище. */
class MoexSync(
    private val repo: MarketRepository,
    private val provider: QuoteProvider,
    private val config: MoexConfig,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val log = LoggerFactory.getLogger(MoexSync::class.java)

    /** Полная загрузка истории за [MoexConfig.historyYears] лет (создаёт или заменяет инструмент). */
    fun import(ticker: String) = provider.fetch(ticker, today().minusYears(config.historyYears)).let { sec ->
        repo.saveInstrument(sec.ticker, InstrumentSource.MOEX, sec.candles, sec.name)
    }

    /** Дозагрузка: последние 7 дней перезаписываются (незакрытая сегодняшняя свеча), новые добавляются. */
    fun refresh(ticker: String) = run {
        val last = repo.candles(ticker)?.lastOrNull()?.date?.let(LocalDate::parse) ?: today().minusYears(config.historyYears)
        repo.upsertQuotes(ticker, provider.fetch(ticker, last.minusDays(7)).candles)
    }

    /**
     * Начальное наполнение и обновление при старте: недостающие бумаги из списка загружаются,
     * уже сохранённые биржевые — обновляются. Если биржа недоступна, а хранилище пустое,
     * подгружаются демонстрационные (синтетические) CSV, чтобы приложение не было пустым.
     */
    fun syncAll(demoDir: File?): SyncResult {
        var imported = 0
        var refreshed = 0
        val failed = mutableListOf<String>()
        if (config.enabled) {
            val stored = repo.instruments().filter { it.source == InstrumentSource.MOEX.db }.map { it.ticker }.toSet()
            val all = (config.tickers + stored).distinct()
            for (t in all) {
                try {
                    if (t in stored) refresh(t) else import(t)
                    if (t in stored) refreshed++ else imported++
                } catch (e: MoexUnavailableException) {
                    log.warn("Moscow Exchange is unavailable ({}), skipping the rest of the sync", e.message)
                    failed += all.drop(all.indexOf(t))
                    break
                } catch (e: Exception) {
                    log.warn("MOEX sync of {} failed: {}", t, e.message)
                    failed += t
                }
            }
            log.info("MOEX sync: imported {}, refreshed {}, failed {}", imported, refreshed, failed)
        }
        val demo = if (repo.instruments().isEmpty() && demoDir != null) DemoDataSeeder.seed(repo, demoDir) else 0
        return SyncResult(imported, refreshed, failed, demo)
    }
}

data class SyncResult(val imported: Int, val refreshed: Int, val failed: List<String>, val demoImported: Int)
