package market.web.storage

import kotlinx.serialization.Serializable
import market.core.AnalysisParams
import market.core.AnalysisReport
import market.core.Candle

@Serializable
data class InstrumentInfo(
    val ticker: String,
    val source: String,
    /** Наименование (для бумаг с Мосбиржи), иначе null. */
    val name: String? = null,
    val sessions: Int,
    val firstDate: String,
    val lastDate: String,
    val lastClose: Double,
    val changePct: Double,
    /** Время последней синхронизации с биржей (ISO-8601) или null. */
    val syncedAt: String? = null,
)

/** Краткая запись истории анализов (без рядов). */
@Serializable
data class RunSummary(
    val runId: Long,
    val ticker: String,
    val engine: String,
    val params: AnalysisParams,
    val createdAt: String,
    val totalReturnPct: Double,
    val sharpeRatio: Double,
    val rsi: Double,
    val trend: String,
)

@Serializable
data class CompareRow(
    val ticker: String,
    val totalReturnPct: Double,
    val annualVolatilityPct: Double,
    val maxDrawdownPct: Double,
    val sharpeRatio: Double,
    val rsi: Double,
    val trend: String,
)

@Serializable
data class CompareResult(
    val dates: List<String>,
    /** Цены, приведённые к 100 на первую общую дату. */
    val normalized: Map<String, List<Double>>,
    val rows: List<CompareRow>,
    /** Матрица корреляций дневных доходностей, порядок как в rows. */
    val correlation: List<List<Double>>,
    val comparisonId: Long? = null,
)

/** Запись истории квант-лаборатории (без тяжёлого результата). */
@Serializable
data class LabRunSummary(
    val labRunId: Long,
    val kind: String,
    val tickers: List<String>,
    val headline: String,
    val computeMs: Double,
    val createdAt: String,
)

/** Полная запись: параметры и результат — JSON, как их вернул C++ модуль. */
data class LabRunRecord(val summary: LabRunSummary, val paramsJson: String, val resultJson: String)

enum class InstrumentSource(val db: String) { DEMO("demo"), UPLOAD("upload"), MOEX("moex") }

/**
 * Хранилище инструментов, котировок и результатов анализа.
 * Реализации: [PostgresRepository] (основная) и [InMemoryRepository] (без БД, для тестов и демо).
 */
interface MarketRepository {
    val kind: String

    fun instruments(): List<InstrumentInfo>
    fun exists(ticker: String): Boolean
    /** Котировки по возрастанию даты или null, если инструмента нет. */
    fun candles(ticker: String): List<Candle>?
    /** Создаёт инструмент или полностью заменяет его котировки (в одной транзакции). */
    fun saveInstrument(ticker: String, source: InstrumentSource, candles: List<Candle>, name: String? = null): InstrumentInfo

    /**
     * Дописывает свежие котировки биржевого инструмента: новые даты добавляются, существующие
     * перезаписываются (последняя дневная свеча меняется в течение торгового дня). Ставит synced_at.
     */
    fun upsertQuotes(ticker: String, candles: List<Candle>): InstrumentInfo
    fun deleteInstrument(ticker: String): Boolean

    /** Сохраняет запуск анализа (параметры, метрики, ряды, сигналы), возвращает run_id. */
    fun saveRun(report: AnalysisReport): Long
    fun run(runId: Long): AnalysisReport?
    fun runs(ticker: String?, limit: Int): List<RunSummary>

    fun saveComparison(result: CompareResult, params: AnalysisParams): Long

    /** Сохраняет расчёт лаборатории; тикеры должны существовать. */
    fun saveLabRun(kind: String, tickers: List<String>, headline: String, paramsJson: String, resultJson: String, computeMs: Double): Long
    fun labRuns(limit: Int): List<LabRunSummary>
    fun labRun(id: Long): LabRunRecord?

    /** Проверка доступности хранилища (для /api/health). */
    fun healthy(): Boolean = true
}

internal fun infoOf(ticker: String, source: String, c: List<Candle>, name: String? = null, syncedAt: String? = null) = InstrumentInfo(
    ticker = ticker,
    source = source,
    name = name,
    syncedAt = syncedAt,
    sessions = c.size,
    firstDate = c.first().date,
    lastDate = c.last().date,
    lastClose = c.last().close,
    changePct = (c.last().close / c.first().close - 1) * 100,
)
