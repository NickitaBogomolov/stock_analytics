package market.web.moex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import market.core.Candle
import market.core.CsvLoader
import market.core.MarketDataException
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import kotlin.math.roundToLong

/** Биржа недоступна или ответила ошибкой (сеть, 5xx, неожиданный формат). */
class MoexUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Бумага с таким кодом не торгуется на Мосбирже (в режиме TQBR) и не является индексом. */
class MoexNotFoundException(ticker: String) : Exception("ticker $ticker is not found on Moscow Exchange")

data class MoexSecurity(val ticker: String, val name: String?, val candles: List<Candle>)

/** Источник биржевых котировок (реализация — [MoexClient]; в тестах — заглушка). */
fun interface QuoteProvider {
    fun fetch(ticker: String, from: LocalDate): MoexSecurity
}

/**
 * Клиент ISS API Московской биржи (https://iss.moex.com, без ключа).
 * Дневные свечи: /iss/engines/stock/markets/{market}/[boards/TQBR/]securities/{ticker}/candles.json?interval=24.
 * Акции ищутся в основном режиме торгов TQBR, индексы (IMOEX, RTSI, ...) — на рынке index.
 */
class MoexClient(
    private val baseUrl: String = System.getenv("MARKET_MOEX_URL")?.trimEnd('/') ?: "https://iss.moex.com",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : QuoteProvider {
    private val log = LoggerFactory.getLogger(MoexClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private enum class Market(val path: String) {
        SHARES("engines/stock/markets/shares/boards/TQBR/securities"),
        INDEX("engines/stock/markets/index/securities"),
    }

    /** Дневные свечи с [from] по сегодняшний день (последняя свеча может быть ещё не закрыта). */
    override fun fetch(ticker: String, from: LocalDate): MoexSecurity {
        val code = ticker.uppercase()
        for (market in Market.entries) {
            val candles = candles(market, code, from)
            if (candles.isNotEmpty()) return MoexSecurity(code, name(market, code), candles)
        }
        throw MoexNotFoundException(code)
    }

    private fun candles(market: Market, ticker: String, from: LocalDate): List<Candle> {
        val out = mutableListOf<Candle>()
        var start = 0
        while (true) {
            val page = get("${market.path}/${enc(ticker)}/candles.json?interval=24&from=$from&start=$start&iss.meta=off")
            val rows = table(page, "candles")
            if (rows.isEmpty()) break
            rows.mapNotNullTo(out) { it.toCandle(ticker) }
            start += rows.size
            if (start > 20_000) break // защита от бесконечной пагинации
        }
        // ISS отдаёт свечи по возрастанию; на всякий случай упорядочиваем и убираем дубликаты дат
        return out.sortedBy { it.date }.distinctBy { it.date }
    }

    private fun name(market: Market, ticker: String): String? = runCatching {
        val page = get("${market.path}/${enc(ticker)}.json?iss.only=securities&iss.meta=off")
        table(page, "securities").firstOrNull()?.let { it["SHORTNAME"] ?: it["NAME"] }
    }.getOrNull()

    private fun Map<String, String?>.toCandle(ticker: String): Candle? {
        val date = this["begin"]?.take(10) ?: return null
        val c = Candle(
            date = date,
            open = this["open"]?.toDoubleOrNull() ?: return null,
            high = this["high"]?.toDoubleOrNull() ?: return null,
            low = this["low"]?.toDoubleOrNull() ?: return null,
            close = this["close"]?.toDoubleOrNull() ?: return null,
            volume = this["volume"]?.toDoubleOrNull()?.roundToLong() ?: 0,
        )
        return try {
            CsvLoader.validate(c)
            c
        } catch (e: MarketDataException) {
            log.warn("MOEX {}: skip candle {}: {}", ticker, date, e.reason)
            null
        }
    }

    /** Блок ISS вида {"columns":[...], "data":[[...], ...]} → список строк «колонка → значение». */
    private fun table(root: JsonObject, block: String): List<Map<String, String?>> {
        val b = root[block]?.jsonObject ?: throw MoexUnavailableException("unexpected MOEX response: no '$block' block")
        val columns = b["columns"]!!.jsonArray.map { it.jsonPrimitive.content }
        return b["data"]!!.jsonArray.map { row ->
            columns.zip((row as JsonArray).map(::scalar)).toMap()
        }
    }

    private fun scalar(e: JsonElement): String? = if (e is JsonNull) null else e.jsonPrimitive.contentOrNull

    private fun get(path: String): JsonObject {
        val uri = URI.create("$baseUrl/iss/$path")
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
            .header("User-Agent", "market-analytics/1.0").GET().build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw MoexUnavailableException("Moscow Exchange is unavailable: ${e.message ?: e.javaClass.simpleName}", e)
        }
        if (response.statusCode() != 200) throw MoexUnavailableException("Moscow Exchange responded with HTTP ${response.statusCode()}")
        return try {
            json.parseToJsonElement(response.body()).jsonObject
        } catch (e: Exception) {
            throw MoexUnavailableException("unexpected MOEX response format", e)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}
