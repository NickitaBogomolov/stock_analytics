package market.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class CsvLoaderTest {
    private val header = "Date,Open,High,Low,Close,Volume\n"

    private fun errorLine(text: String): Int = shouldThrow<MarketDataException> { CsvLoader.load(text) }.line

    @Test
    fun `parses valid file`() {
        val c = CsvLoader.load(header + "2024-01-02,10,11,9,10.5,1000\n2024-01-03,10.5,12,10,11.5,2000\n")
        c shouldHaveSize 2
        c[1] shouldBe Candle("2024-01-03", 10.5, 12.0, 10.0, 11.5, 2000)
    }

    @Test
    fun `tolerates whitespace crlf and blank lines`() {
        val c = CsvLoader.load("date, open, high, low, close, volume\r\n\r\n 2024-01-02 , 10 , 11 , 9 , 10 , 5 \r\n\n")
        c shouldHaveSize 1
    }

    @Test
    fun `empty input`() {
        shouldThrow<MarketDataException> { CsvLoader.load("") }
        shouldThrow<MarketDataException> { CsvLoader.load(header) }
    }

    @Test
    fun `missing header is reported on line 1`() {
        errorLine("2024-01-02,10,11,9,10,1\n") shouldBe 1
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "2024-01-02,10,11,9,10",          // 5 колонок
            "2024-01-02,10,11,9,10,1,",       // 7 колонок
            "2024-01-02,10,abc,9,10,1",       // не число
            "2024-01-02,10,11x,9,10,1",
            "2024-01-02,10,11,9,NaN,1",
            "2024-01-02,0x0A,11,9,10,1",
            "2024-01-02,10,11d,9,10,1",
            "2024-01-02,10,,9,10,1",
            "02.01.2024,10,11,9,10,1",        // формат даты
            "2024-13-02,10,11,9,10,1",
            "2023-02-29,10,11,9,10,1",        // не високосный год
            "2024-04-31,10,11,9,10,1",
            "2024-01-02,0,11,9,10,1",         // неположительная цена
            "2024-01-02,10,11,-9,10,1",
            "2024-01-02,10,9.5,9,10,1",       // high < open
            "2024-01-02,10,11,10.5,10,1",     // low > close
            "2024-01-02,10,11,9,10,-1",       // объём
            "2024-01-02,10,11,9,10,1.5",
        ],
    )
    fun `invalid row is rejected with its line number`(row: String) {
        errorLine(header + row + "\n") shouldBe 2
    }

    @Test
    fun `dates must be strictly ascending`() {
        errorLine(header + "2024-01-03,10,11,9,10,1\n2024-01-02,10,11,9,10,1\n") shouldBe 3
        errorLine(header + "2024-01-03,10,11,9,10,1\n2024-01-03,10,11,9,10,1\n") shouldBe 3
    }

    @Test
    fun `leap day is accepted and csv round-trips`() {
        val c = CsvLoader.load(header + "2024-02-29,10,11,9,10.25,1\n")
        CsvLoader.load(CsvLoader.toCsv(c)) shouldBe c
    }

    @Test
    fun `missing file`() {
        shouldThrow<MarketDataException> { CsvLoader.loadFile(File("/nonexistent/file.csv")) }
    }

    @Test
    fun `loads demo data`() {
        CsvLoader.loadFile(File(TestData.dir, "DEMO_TECH.csv")) shouldHaveSize 500
    }
}

object TestData {
    val dir: File = File(System.getProperty("market.dataDir") ?: "../data")
}
