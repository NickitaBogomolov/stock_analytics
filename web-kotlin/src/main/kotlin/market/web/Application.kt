package market.web

import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import market.core.AnalysisParams
import market.core.MarketDataException
import market.web.storage.Database
import market.web.storage.DbConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import market.web.moex.MoexClient
import market.web.moex.MoexConfig
import market.web.moex.MoexNotFoundException
import market.web.moex.MoexSync
import market.web.moex.MoexUnavailableException
import market.web.storage.InMemoryRepository
import market.web.storage.PostgresRepository
import org.slf4j.LoggerFactory
import java.io.File

@Serializable
data class ApiError(val error: String, val line: Int? = null)

const val MAX_UPLOAD_BYTES = 5L * 1024 * 1024

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        val app = createDefaultServices()
        environment.monitor.subscribe(ApplicationStopped) { app.close() }
        module(app.market, app.lab)
        startMarketDataSync(app.market, MoexConfig.fromEnv())
    }.start(wait = true)
}

/**
 * Фоновая синхронизация с Мосбиржей: сразу после старта (сервер уже отвечает) и далее
 * каждые MARKET_REFRESH_HOURS часов. Ошибки сети не роняют приложение.
 */
fun Application.startMarketDataSync(service: MarketService, cfg: MoexConfig) {
    launch(Dispatchers.IO) {
        while (isActive) {
            runCatching { service.syncAll(defaultDataDir()) }
                .onFailure { environment.log.warn("Market data sync failed: {}", it.message) }
            if (!cfg.enabled) break
            delay(cfg.refreshHours * 3_600_000)
        }
    }
}

fun defaultDataDir(): File =
    listOfNotNull(System.getenv("MARKET_DATA_DIR"), System.getProperty("market.dataDir"), "../data", "data")
        .map(::File).firstOrNull { it.isDirectory } ?: File("data")

class Services(val market: MarketService, val lab: LabService, val close: () -> Unit)

/**
 * Выбор хранилища: PostgreSQL, если задана MARKET_DB_URL, иначе — в памяти.
 * Kotlin-сервис (данные, анализ, API) и C++ лаборатория работают с одним хранилищем.
 */
fun createDefaultServices(): Services {
    val log = LoggerFactory.getLogger("market.web")
    val cfg = DbConfig.fromEnv()
    val (repo, close) = if (cfg != null) {
        val ds = Database.connect(cfg)
        PostgresRepository(ds) to { ds.close() }
    } else {
        log.warn("MARKET_DB_URL is not set: using in-memory storage (data is lost on restart)")
        InMemoryRepository() to {}
    }
    val moexCfg = MoexConfig.fromEnv()
    // MoexSync создаётся и при выключенной бирже: тогда он только подгружает демо-данные в пустое хранилище
    val sync = MoexSync(repo, MoexClient(), moexCfg)
    val cpp = CppEngine.locate()
    return Services(MarketService(repo, cpp, sync), LabService(repo, cpp), close)
}

fun Application.module(service: MarketService, lab: LabService? = null) {
    install(CallLogging)
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true; prettyPrint = false })
    }
    install(StatusPages) {
        exception<MarketDataException> { call, e ->
            call.respond(HttpStatusCode.UnprocessableEntity, ApiError(e.reason, e.line.takeIf { it > 0 }))
        }
        exception<NotFoundException> { call, e -> call.respond(HttpStatusCode.NotFound, ApiError(e.message ?: "not found")) }
        exception<LabUnavailableException> { call, e ->
            call.respond(HttpStatusCode.ServiceUnavailable, ApiError(e.message ?: "C++ module is not available"))
        }
        exception<MoexNotFoundException> { call, e -> call.respond(HttpStatusCode.NotFound, ApiError(e.message ?: "not found")) }
        exception<MoexUnavailableException> { call, e ->
            call.respond(HttpStatusCode.BadGateway, ApiError(e.message ?: "Moscow Exchange is unavailable"))
        }
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "bad request"))
        }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal server error"))
        }
    }

    routing {
        staticResources("/", "static", index = "index.html")
        route("/api") {
            get("/health") {
                val h = service.health()
                call.respond(if (h.storageHealthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, h)
            }
            get("/engines") { call.respond(service.engines()) }
            get("/sync") { call.respond(service.syncStatus()) }
            route("/lab") {
                fun lab() = lab ?: throw LabUnavailableException()
                get("/status") { call.respond(mapOf("available" to (lab?.available == true))) }
                post("/backtest/{ticker}") {
                    val q = call.request.queryParameters
                    call.respond(
                        lab().backtest(
                            call.parameters["ticker"]!!,
                            commissionPct = q.double("commission") ?: 0.05,
                            trainShare = q.double("train") ?: 0.7,
                            riskFreePct = q.double("rf") ?: 0.0,
                        ),
                    )
                }
                post("/montecarlo/{ticker}") {
                    val q = call.request.queryParameters
                    call.respond(
                        lab().monteCarlo(
                            call.parameters["ticker"]!!,
                            paths = q.int("paths") ?: 10_000,
                            horizon = q.int("horizon") ?: 21,
                            seed = q.int("seed")?.toLong() ?: 42,
                        ),
                    )
                }
                post("/portfolio") {
                    val q = call.request.queryParameters
                    call.respond(
                        lab().portfolio(
                            q["tickers"].orEmpty().split(','),
                            samples = q.int("samples") ?: 20_000,
                            riskFreePct = q.double("rf") ?: 0.0,
                        ),
                    )
                }
                get("/runs") {
                    val limit = call.request.queryParameters.int("limit") ?: 20
                    call.respond(lab().runs(limit))
                }
                get("/runs/{id}") {
                    val id = call.parameters["id"]!!.toLongOrNull() ?: throw IllegalArgumentException("lab run id must be a number")
                    call.respond(lab().run(id))
                }
            }
            post("/instruments/{ticker}/moex") {
                call.respond(HttpStatusCode.Created, service.importFromMoex(call.parameters["ticker"]!!))
            }
            post("/instruments/{ticker}/refresh") {
                call.respond(service.refreshFromMoex(call.parameters["ticker"]!!))
            }
            get("/instruments") { call.respond(service.instruments()) }
            post("/instruments/{ticker}") {
                val info = service.importInstrument(call.parameters["ticker"]!!, call.receiveCsv())
                call.respond(HttpStatusCode.Created, info)
            }
            delete("/instruments/{ticker}") {
                service.deleteInstrument(call.parameters["ticker"]!!)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/instruments/{ticker}/candles") {
                call.respond(service.candles(call.parameters["ticker"]!!))
            }
            get("/instruments/{ticker}/analysis") {
                call.respond(service.analyze(call.parameters["ticker"]!!, call.analysisParams(), call.engine()))
            }
            post("/analysis") {
                val body = call.receiveCsv()
                val ticker = call.request.queryParameters["ticker"]?.ifBlank { null } ?: "UPLOAD"
                call.respond(service.analyzeUpload(ticker, body, call.analysisParams(), call.engine()))
            }
            get("/runs") {
                val ticker = call.request.queryParameters["ticker"]?.ifBlank { null }
                val limit = call.request.queryParameters["limit"]?.let {
                    it.toIntOrNull() ?: throw IllegalArgumentException("parameter 'limit' must be an integer")
                } ?: 20
                call.respond(service.runs(ticker, limit))
            }
            get("/runs/{id}") {
                val id = call.parameters["id"]!!.toLongOrNull() ?: throw IllegalArgumentException("run id must be a number")
                call.respond(service.run(id))
            }
            get("/compare") {
                val tickers = call.request.queryParameters["tickers"].orEmpty().split(',')
                call.respond(service.compare(tickers, call.analysisParams()))
            }
        }
    }
}

private fun Parameters.int(name: String): Int? =
    this[name]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("parameter '$name' must be an integer") }

private fun Parameters.double(name: String): Double? =
    this[name]?.let { v -> v.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("parameter '$name' must be a number") }

private suspend fun ApplicationCall.receiveCsv(): String {
    val length = request.contentLength()
    if (length != null && length > MAX_UPLOAD_BYTES) throw IllegalArgumentException("file is larger than 5 MB")
    val body = receiveText()
    if (body.length > MAX_UPLOAD_BYTES) throw IllegalArgumentException("file is larger than 5 MB")
    return body
}

private fun ApplicationCall.intParam(name: String, default: Int): Int {
    val raw = request.queryParameters[name] ?: return default
    val v = raw.toIntOrNull() ?: throw IllegalArgumentException("parameter '$name' must be an integer")
    require(v in 1..500) { "parameter '$name' must be in 1..500" }
    return v
}

fun ApplicationCall.analysisParams(): AnalysisParams {
    val d = AnalysisParams()
    val rf = request.queryParameters["rf"]?.let {
        it.toDoubleOrNull()?.takeIf { v -> v.isFinite() && v in -50.0..100.0 }
            ?: throw IllegalArgumentException("parameter 'rf' must be a number in -50..100")
    } ?: d.riskFreeRatePct
    return AnalysisParams(intParam("fast", d.fastPeriod), intParam("slow", d.slowPeriod), intParam("rsi", d.rsiPeriod), rf)
}

fun ApplicationCall.engine(): Engine = when (request.queryParameters["engine"]?.lowercase()) {
    null, "", "kotlin" -> Engine.KOTLIN
    "cpp" -> Engine.CPP
    else -> throw IllegalArgumentException("engine must be 'kotlin' or 'cpp'")
}
