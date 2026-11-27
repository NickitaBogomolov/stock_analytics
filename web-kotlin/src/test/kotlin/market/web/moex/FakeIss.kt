package market.web.moex

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * Локальная заглушка ISS API Мосбиржи: отдаёт ответы в том же формате
 * ({"candles":{"columns":[...],"data":[[...]]}}), с постраничной выдачей по параметру start.
 */
class FakeIss(private val pageSize: Int = 2) : AutoCloseable {
    /** ticker → строки свечей (open, close, high, low, value, volume, begin, end) для рынка акций. */
    val shares = mutableMapOf<String, List<List<Any?>>>()
    val index = mutableMapOf<String, List<List<Any?>>>()
    val names = mutableMapOf<String, String>()
    var failWith: Int? = null
    val requests = mutableListOf<String>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val uri = ex.requestURI.toString()
            synchronized(requests) { requests += uri }
            val (code, body) = failWith?.let { it to "oops" } ?: respond(ex.requestURI.path, ex.requestURI.query.orEmpty())
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    private fun respond(path: String, query: String): Pair<Int, String> {
        val params = query.split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val m = Regex("""/iss/engines/stock/markets/(shares|index)/(?:boards/TQBR/)?securities/([^/]+?)(/candles)?\.json""").find(path)
            ?: return 404 to "{}"
        val (market, ticker, candles) = m.destructured
        val source = if (market == "shares") shares else index
        if (candles.isEmpty()) {
            val row = names[ticker]?.takeIf { ticker in source }?.let { listOf("[\"$ticker\",\"$it\"]") } ?: emptyList()
            return 200 to """{"securities":{"columns":["SECID","SHORTNAME"],"data":[${row.joinToString(",")}]}}"""
        }
        val from = params["from"] ?: "0000-00-00"
        val start = params["start"]?.toInt() ?: 0
        val rows = (source[ticker] ?: emptyList()).filter { (it[6] as String).take(10) >= from }.drop(start).take(pageSize)
        val data = rows.joinToString(",") { r -> r.joinToString(",", "[", "]") { v -> if (v is String) "\"$v\"" else v.toString() } }
        return 200 to """{"candles":{"columns":["open","close","high","low","value","volume","begin","end"],"data":[$data]}}"""
    }

    override fun close() = server.stop(0)

    companion object {
        fun candle(date: String, open: Double, close: Double, high: Double, low: Double, volume: Long = 1000): List<Any?> =
            listOf(open, close, high, low, volume * close, volume, "$date 00:00:00", "$date 23:59:59")
    }
}
