package market.web

import kotlinx.serialization.json.Json
import market.core.AnalysisParams
import market.core.AnalysisReport
import market.core.MarketDataException
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Адаптер C++ модуля (market_cli): запускает его как внешний процесс и возвращает JSON.
 * Основная задача — квант-лаборатория (бэктест, Монте-Карло, портфель, см. [LabService]);
 * команда analyze используется для проверки паритета C++ ядра и Kotlin-порта.
 */
class CppEngine(private val binary: File?, private val timeoutSeconds: Long = 120) {
    private val json = Json { ignoreUnknownKeys = true }
    private val linePrefix = Regex("""^error: line (\d+): (.*)$""")

    val available: Boolean get() = binary != null && binary.canExecute()

    fun analyze(csv: File, ticker: String, p: AnalysisParams): AnalysisReport {
        val out = run(
            listOf(
                "analyze", csv.absolutePath,
                "--ticker", ticker,
                "--fast", p.fastPeriod.toString(),
                "--slow", p.slowPeriod.toString(),
                "--rsi", p.rsiPeriod.toString(),
                "--rf", p.riskFreeRatePct.toString(),
            ),
        )
        return json.decodeFromString(AnalysisReport.serializer(), out)
    }

    /** Запуск подкоманды market_cli; возвращает JSON из stdout. Ошибки данных -> MarketDataException. */
    fun run(args: List<String>): String {
        check(available) { "C++ engine is not available" }
        val process = ProcessBuilder(listOf(binary!!.absolutePath) + args).redirectError(ProcessBuilder.Redirect.PIPE).start()
        // stdout и stderr читаем параллельно, иначе большой вывод может заблокировать процесс
        val stderrText = StringBuilder()
        val errReader = Thread { stderrText.append(process.errorStream.bufferedReader().readText()) }.apply { start() }
        val stdout = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("C++ engine timed out")
        }
        errReader.join()
        if (process.exitValue() != 0) {
            val first = stderrText.toString().trim().lineSequence().firstOrNull().orEmpty()
            val m = linePrefix.find(first)
            if (m != null) throw MarketDataException(m.groupValues[2], m.groupValues[1].toInt())
            throw MarketDataException(first.removePrefix("error: ").ifEmpty { "C++ engine failed" })
        }
        return stdout
    }

    companion object {
        /** Ищет бинарник: переменная MARKET_CLI или стандартный путь сборки CMake. */
        fun locate(): CppEngine {
            val candidates = listOfNotNull(
                System.getenv("MARKET_CLI"),
                System.getProperty("market.cli"),
                "../core-cpp/build/market_cli",
                "core-cpp/build/market_cli",
            )
            return CppEngine(candidates.map(::File).firstOrNull { it.canExecute() })
        }
    }
}
