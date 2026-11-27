package market.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import market.core.CsvLoader
import market.web.storage.LabRunSummary
import market.web.storage.MarketRepository
import java.io.File
import java.util.Locale

/** C++ модуль не собран/не найден — лаборатория недоступна (HTTP 503). */
class LabUnavailableException : Exception("C++ quant module (market_cli) is not available on this server")

@Serializable
data class LabRunResponse(val summary: LabRunSummary, val params: JsonElement, val result: JsonElement)

/**
 * Квант-лаборатория: Kotlin готовит данные из БД и сохраняет результат, а вычисления
 * (перебор параметров, десятки тысяч сценариев и портфелей) выполняет C++ модуль.
 */
class LabService(private val repo: MarketRepository, private val cpp: CppEngine) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tickerPattern = Regex("[A-Za-z0-9_.-]{1,32}")

    val available: Boolean get() = cpp.available

    /** Бэктест SMA-стратегии с подбором параметров на обучающей части и проверкой вне выборки. */
    fun backtest(ticker: String, commissionPct: Double = 0.05, trainShare: Double = 0.7, riskFreePct: Double = 0.0): LabRunResponse {
        require(commissionPct in 0.0..2.0) { "commission must be within 0..2 %" }
        require(trainShare in 0.5..0.9) { "train share must be within 0.5..0.9" }
        require(riskFreePct in -50.0..100.0) { "risk-free rate must be within -50..100 %" }
        val params = buildJsonObject {
            put("commissionPct", commissionPct); put("trainShare", trainShare); put("riskFreeRatePct", riskFreePct)
            put("fastMin", 2); put("fastMax", 50); put("fastStep", 2); put("slowMin", 10); put("slowMax", 250); put("slowStep", 10)
        }
        val args = listOf(
            "--commission", commissionPct.toString(), "--train", trainShare.toString(), "--rf", riskFreePct.toString(),
            "--fast-min", "2", "--fast-max", "50", "--fast-step", "2", "--slow-min", "10", "--slow-max", "250", "--slow-step", "10",
        )
        return execute("backtest", listOf(ticker), params, args) { r ->
            val ins = r["inSample"]!!.jsonObject
            val out = r["outSample"]!!.jsonObject
            val bh = r["buyHoldOut"]!!.jsonObject
            "SMA ${r.int("bestFast")}/${r.int("bestSlow")}: Шарп ${f(ins.d("sharpe"))} на обучении → ${f(out.d("sharpe"))} вне выборки " +
                "(купить и держать: ${f(bh.d("sharpe"))})"
        }
    }

    /** Монте-Карло: бутстреп дневных доходностей, VaR/CVaR и веер сценариев на horizon дней. */
    fun monteCarlo(ticker: String, paths: Int = 10_000, horizon: Int = 21, seed: Long = 42): LabRunResponse {
        require(paths in 1_000..200_000) { "paths must be within 1000..200000" }
        require(horizon in 5..252) { "horizon must be within 5..252 trading days" }
        require(seed in 1..Int.MAX_VALUE) { "seed must be positive" }
        val params = buildJsonObject { put("paths", paths); put("horizon", horizon); put("seed", seed) }
        val args = listOf("--paths", paths.toString(), "--horizon", horizon.toString(), "--seed", seed.toString())
        return execute("montecarlo", listOf(ticker), params, args) { r ->
            "$horizon дн., $paths сценариев: VaR95 ${f(r.d("var95"), 1)} %, CVaR95 ${f(r.d("cvar95"), 1)} %, " +
                "вероятность убытка ${f(r.d("probLossPct"), 1)} %"
        }
    }

    /** Оптимизация портфеля: случайный поиск по весам, максимум Шарпа и минимум риска. */
    fun portfolio(tickers: List<String>, samples: Int = 20_000, riskFreePct: Double = 0.0): LabRunResponse {
        val unique = tickers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(unique.size in 2..10) { "select from 2 to 10 instruments" }
        require(samples in 1_000..500_000) { "samples must be within 1000..500000" }
        require(riskFreePct in -50.0..100.0) { "risk-free rate must be within -50..100 %" }
        val params = buildJsonObject { put("samples", samples); put("riskFreeRatePct", riskFreePct) }
        val args = listOf("--tickers", unique.joinToString(","), "--samples", samples.toString(), "--rf", riskFreePct.toString())
        return execute("portfolio", unique, params, args) { r ->
            val best = r["maxSharpe"]!!.jsonObject
            val weights = best["weights"]!!.jsonArray.map { it.jsonPrimitive.double }
            val top = unique.zip(weights).filter { it.second >= 0.05 }.sortedByDescending { it.second }
                .joinToString(", ") { "${it.first} ${(it.second * 100).toInt()} %" }
            "Макс. Шарп ${f(best.d("sharpe"))}: $top; мин. риск ${f(r["minVolatility"]!!.jsonObject.d("volatilityPct"), 1)} %"
        }
    }

    fun runs(limit: Int): List<LabRunSummary> {
        require(limit in 1..200) { "limit must be in 1..200" }
        return repo.labRuns(limit)
    }

    fun run(id: Long): LabRunResponse {
        val rec = repo.labRun(id) ?: throw NotFoundException("lab run not found: $id")
        return LabRunResponse(rec.summary, json.parseToJsonElement(rec.paramsJson), json.parseToJsonElement(rec.resultJson))
    }

    private fun execute(
        kind: String,
        tickers: List<String>,
        params: JsonObject,
        args: List<String>,
        headline: (JsonObject) -> String,
    ): LabRunResponse {
        if (!cpp.available) throw LabUnavailableException()
        tickers.forEach { if (!tickerPattern.matches(it)) throw IllegalArgumentException("invalid ticker: $it") }
        // котировки из БД -> временные CSV для C++ модуля
        val files = tickers.map { t ->
            val candles = repo.candles(t) ?: throw NotFoundException("instrument not found: $t")
            File.createTempFile("lab-$t-", ".csv").apply { writeText(CsvLoader.toCsv(candles)) }
        }
        try {
            val tickerArgs = if (kind == "portfolio") emptyList() else listOf("--ticker", tickers.single())
            val out = cpp.run(listOf(kind) + files.map { it.absolutePath } + tickerArgs + args)
            val result = json.parseToJsonElement(out).jsonObject
            val ms = result.d("computeMs")
            val title = headline(result)
            val id = repo.saveLabRun(kind, tickers, title, params.toString(), out.trim(), ms)
            return run(id)
        } finally {
            files.forEach { it.delete() }
        }
    }

    private fun JsonObject.d(key: String) = this[key]!!.jsonPrimitive.double
    private fun JsonObject.int(key: String) = this[key]!!.jsonPrimitive.int
    private fun f(v: Double, digits: Int = 2) = String.format(Locale("ru"), "%.${digits}f", v)
}
