package market.web.storage

import market.core.AnalysisParams
import market.core.AnalysisReport
import market.core.Candle
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Хранилище в памяти процесса: используется без PostgreSQL (локальный запуск, тесты). */
class InMemoryRepository : MarketRepository {
    override val kind = "memory"

    private data class Stored(val source: String, val candles: List<Candle>, val name: String?, val syncedAt: String?)
    private data class StoredRun(val report: AnalysisReport, val createdAt: String)

    private val instruments = ConcurrentHashMap<String, Stored>()
    private val runs = ConcurrentHashMap<Long, StoredRun>()
    private val runSeq = AtomicLong()
    private val comparisonSeq = AtomicLong()
    private val labRuns = ConcurrentHashMap<Long, LabRunRecord>()
    private val labSeq = AtomicLong()

    override fun instruments() = instruments.entries.sortedBy { it.key }.map { (t, s) -> infoOf(t, s.source, s.candles, s.name, s.syncedAt) }
    override fun exists(ticker: String) = instruments.containsKey(ticker)
    override fun candles(ticker: String) = instruments[ticker]?.candles

    override fun saveInstrument(ticker: String, source: InstrumentSource, candles: List<Candle>, name: String?): InstrumentInfo {
        require(candles.isNotEmpty()) { "no quotes to save" }
        val synced = if (source == InstrumentSource.MOEX) Instant.now().toString() else null
        instruments[ticker] = Stored(source.db, candles.toList(), name, synced)
        return infoOf(ticker, source.db, candles, name, synced)
    }

    override fun upsertQuotes(ticker: String, candles: List<Candle>): InstrumentInfo {
        val old = instruments[ticker] ?: throw IllegalArgumentException("instrument not found: $ticker")
        val merged = (old.candles.associateBy { it.date } + candles.associateBy { it.date }).values.sortedBy { it.date }
        val updated = old.copy(candles = merged, syncedAt = Instant.now().toString())
        instruments[ticker] = updated
        return infoOf(ticker, updated.source, merged, updated.name, updated.syncedAt)
    }

    override fun deleteInstrument(ticker: String): Boolean {
        val removed = instruments.remove(ticker) != null
        if (removed) {
            runs.entries.removeIf { it.value.report.ticker == ticker }
            labRuns.entries.removeIf { ticker in it.value.summary.tickers }
        }
        return removed
    }

    override fun saveRun(report: AnalysisReport): Long {
        require(exists(report.ticker)) { "instrument not found: ${report.ticker}" }
        val id = runSeq.incrementAndGet()
        runs[id] = StoredRun(report.copy(runId = id), Instant.now().toString())
        return id
    }

    override fun run(runId: Long) = runs[runId]?.report

    override fun runs(ticker: String?, limit: Int) = runs.entries
        .filter { ticker == null || it.value.report.ticker == ticker }
        .sortedByDescending { it.key }
        .take(limit)
        .map { (id, s) ->
            val r = s.report
            RunSummary(id, r.ticker, r.engine, r.params, s.createdAt, r.totalReturnPct, r.sharpeRatio, r.rsi, r.trend.name)
        }

    override fun saveComparison(result: CompareResult, params: AnalysisParams) = comparisonSeq.incrementAndGet()

    override fun saveLabRun(kind: String, tickers: List<String>, headline: String, paramsJson: String, resultJson: String, computeMs: Double): Long {
        tickers.forEach { require(exists(it)) { "instrument not found: $it" } }
        val id = labSeq.incrementAndGet()
        labRuns[id] = LabRunRecord(LabRunSummary(id, kind, tickers, headline, computeMs, Instant.now().toString()), paramsJson, resultJson)
        return id
    }

    override fun labRuns(limit: Int) = labRuns.values.map { it.summary }.sortedByDescending { it.labRunId }.take(limit)

    override fun labRun(id: Long) = labRuns[id]
}
