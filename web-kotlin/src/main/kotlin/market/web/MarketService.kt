package market.web

import kotlinx.serialization.Serializable
import market.core.AnalysisParams
import market.core.AnalysisReport
import market.core.Analyzer
import market.core.Candle
import market.core.CsvLoader
import market.core.Indicators
import market.core.MarketDataException
import market.web.storage.CompareResult
import market.web.storage.CompareRow
import market.web.storage.InstrumentInfo
import market.web.storage.InstrumentSource
import market.web.storage.MarketRepository
import market.web.storage.RunSummary
import market.web.moex.MoexSync
import market.web.moex.SyncResult
import java.io.File
import java.time.Instant

@Serializable
data class EnginesInfo(val kotlin: Boolean, val cpp: Boolean)

@Serializable
data class HealthInfo(val status: String, val storage: String, val storageHealthy: Boolean)

/** Состояние синхронизации с Мосбиржей для интерфейса. */
@Serializable
data class SyncStatus(
    val moexEnabled: Boolean,
    val running: Boolean,
    val lastFinishedAt: String? = null,
    val imported: Int = 0,
    val refreshed: Int = 0,
    val failed: List<String> = emptyList(),
)

enum class Engine { KOTLIN, CPP }

class NotFoundException(message: String) : Exception(message)

/** Прикладной сервис: каталог инструментов, импорт котировок, анализ, история, сравнение. */
class MarketService(
    private val repo: MarketRepository,
    private val cpp: CppEngine,
    private val moex: MoexSync? = null,
) {
    private val tickerPattern = Regex("[A-Za-z0-9_.-]{1,32}")
    @Volatile private var syncStatus = SyncStatus(moexEnabled = moex != null, running = false)

    fun syncStatus() = syncStatus

    /** Импорт/обновление всех биржевых бумаг (при старте и по расписанию). */
    fun syncAll(demoDir: File?): SyncResult? {
        val sync = moex ?: return null
        syncStatus = syncStatus.copy(running = true)
        try {
            val r = sync.syncAll(demoDir)
            syncStatus = SyncStatus(true, false, Instant.now().toString(), r.imported, r.refreshed, r.failed)
            return r
        } catch (e: Exception) {
            syncStatus = syncStatus.copy(running = false, lastFinishedAt = Instant.now().toString())
            throw e
        }
    }

    private fun moexOrFail(): MoexSync = moex ?: throw IllegalArgumentException("Moscow Exchange import is disabled on this server")

    /** Загрузка истории бумаги с Мосбиржи по тикеру (SBER, GAZP, IMOEX, ...). */
    fun importFromMoex(ticker: String): InstrumentInfo {
        checkTicker(ticker)
        return moexOrFail().import(ticker.uppercase())
    }

    /** Дозагрузка свежих свечей для бумаги, ранее загруженной с биржи. */
    fun refreshFromMoex(ticker: String): InstrumentInfo {
        checkTicker(ticker)
        val info = repo.instruments().firstOrNull { it.ticker == ticker } ?: throw NotFoundException("instrument not found: $ticker")
        require(info.source == InstrumentSource.MOEX.db) { "$ticker was not loaded from Moscow Exchange" }
        return moexOrFail().refresh(ticker)
    }

    val storageKind: String get() = repo.kind

    fun engines() = EnginesInfo(kotlin = true, cpp = cpp.available)

    fun health(): HealthInfo {
        val ok = repo.healthy()
        return HealthInfo(if (ok) "ok" else "degraded", repo.kind, ok)
    }

    fun instruments(): List<InstrumentInfo> = repo.instruments()

    private fun checkTicker(ticker: String) {
        if (!tickerPattern.matches(ticker)) throw IllegalArgumentException("invalid ticker: $ticker")
    }

    fun candles(ticker: String): List<Candle> {
        checkTicker(ticker)
        return repo.candles(ticker) ?: throw NotFoundException("instrument not found: $ticker")
    }

    /** Импорт CSV пользователя в хранилище (создание или полная замена котировок). */
    fun importInstrument(ticker: String, csv: String): InstrumentInfo {
        checkTicker(ticker)
        return repo.saveInstrument(ticker, InstrumentSource.UPLOAD, CsvLoader.load(csv))
    }

    fun deleteInstrument(ticker: String) {
        checkTicker(ticker)
        if (!repo.deleteInstrument(ticker)) throw NotFoundException("instrument not found: $ticker")
    }

    /** Анализ сохранённого инструмента; результат записывается в историю запусков. */
    fun analyze(ticker: String, params: AnalysisParams, engine: Engine = Engine.KOTLIN): AnalysisReport {
        val report = compute(ticker, candles(ticker), params, engine)
        return report.copy(runId = repo.saveRun(report))
    }

    /** Разовый анализ CSV без сохранения в хранилище. */
    fun analyzeUpload(ticker: String, csv: String, params: AnalysisParams, engine: Engine): AnalysisReport {
        checkTicker(ticker)
        return compute(ticker, CsvLoader.load(csv), params, engine)
    }

    private fun compute(ticker: String, candles: List<Candle>, params: AnalysisParams, engine: Engine): AnalysisReport =
        when (engine) {
            Engine.KOTLIN -> Analyzer.analyze(ticker, candles, params)
            Engine.CPP -> {
                if (!cpp.available) throw IllegalArgumentException("C++ engine is not available on this server")
                val tmp = File.createTempFile("quotes-", ".csv")
                try {
                    tmp.writeText(CsvLoader.toCsv(candles))
                    cpp.analyze(tmp, ticker, params)
                } finally {
                    tmp.delete()
                }
            }
        }

    fun runs(ticker: String?, limit: Int): List<RunSummary> {
        ticker?.let(::checkTicker)
        require(limit in 1..200) { "limit must be in 1..200" }
        return repo.runs(ticker, limit)
    }

    fun run(runId: Long): AnalysisReport = repo.run(runId) ?: throw NotFoundException("analysis run not found: $runId")

    fun compare(tickers: List<String>, params: AnalysisParams): CompareResult {
        val unique = tickers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (unique.size !in 2..8) throw IllegalArgumentException("select from 2 to 8 instruments")
        val data = unique.associateWith { candles(it) }
        // выравнивание по общим датам
        val common = data.values.map { c -> c.map { it.date }.toSet() }.reduce { a, b -> a intersect b }
        val dates = data.values.first().map { it.date }.filter { it in common }
        if (dates.size < 3) throw MarketDataException("instruments have fewer than 3 common dates")
        val closes = data.mapValues { (_, c) -> c.filter { it.date in common }.map { it.close } }
        val normalized = closes.mapValues { (_, v) -> v.map { it / v.first() * 100 } }
        val returns = closes.mapValues { (_, v) -> Indicators.dailyReturns(v) }
        val rows = unique.map { t ->
            val r = Analyzer.analyze(t, data.getValue(t), params)
            CompareRow(t, r.totalReturnPct, r.annualVolatilityPct, r.maxDrawdownPct, r.sharpeRatio, r.rsi, r.trend.name)
        }
        val correlation = unique.map { a -> unique.map { b -> Indicators.correlation(returns.getValue(a), returns.getValue(b)) } }
        val result = CompareResult(dates, normalized, rows, correlation)
        return result.copy(comparisonId = repo.saveComparison(result, params))
    }
}
