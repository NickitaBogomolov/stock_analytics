package market.core

import kotlinx.serialization.Serializable

/** Одна торговая сессия (дневная свеча). */
@Serializable
data class Candle(
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

@Serializable
enum class SignalType { BUY, SELL }

@Serializable
data class TradeSignal(val date: String, val type: SignalType, val price: Double)

@Serializable
enum class Trend { UPTREND, DOWNTREND, SIDEWAYS }

@Serializable
enum class RsiZone { OVERBOUGHT, OVERSOLD, NEUTRAL }

@Serializable
data class AnalysisParams(
    val fastPeriod: Int = 10,
    val slowPeriod: Int = 30,
    val rsiPeriod: Int = 14,
    val riskFreeRatePct: Double = 0.0,
)

/** Временные ряды для графиков; null — значение ещё не определено (период «прогрева»). */
@Serializable
data class Series(
    val dates: List<String>,
    val close: List<Double>,
    val smaFast: List<Double?>,
    val smaSlow: List<Double?>,
    val rsi: List<Double?>,
)

@Serializable
data class AnalysisReport(
    val engine: String = "kotlin",
    val ticker: String,
    val params: AnalysisParams,
    val count: Int,
    val firstDate: String,
    val lastDate: String,
    val lastClose: Double,
    val totalReturnPct: Double,
    val annualVolatilityPct: Double,
    val maxDrawdownPct: Double,
    val sharpeRatio: Double,
    val rsi: Double,
    val trend: Trend,
    val rsiZone: RsiZone,
    val series: Series,
    val signals: List<TradeSignal>,
    /** Идентификатор сохранённого запуска (null — отчёт не сохранялся). */
    val runId: Long? = null,
)

/** Ошибка входных данных; [line] — номер строки файла (0, если неизвестен). */
class MarketDataException(message: String, val line: Int = 0) :
    Exception(if (line > 0) "line $line: $message" else message) {
    val reason: String = message
}
