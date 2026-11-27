package market.core

import java.io.File
import java.io.Reader
import java.time.LocalDate

/** Чтение котировок из CSV: Date,Open,High,Low,Close,Volume. Порт CsvLoader.cpp. */
object CsvLoader {
    private val numberChars = Regex("[0-9.eE+-]+")
    private val isoDate = Regex("""\d{4}-\d{2}-\d{2}""")

    fun load(reader: Reader): List<Candle> {
        val candles = mutableListOf<Candle>()
        var headerSeen = false
        reader.useLines { lines ->
            lines.forEachIndexed { index, raw ->
                val lineNo = index + 1
                val line = raw.trim()
                if (line.isEmpty()) return@forEachIndexed
                if (!headerSeen) {
                    if (!line.lowercase().startsWith("date")) {
                        throw MarketDataException("missing header 'Date,Open,High,Low,Close,Volume'", lineNo)
                    }
                    headerSeen = true
                    return@forEachIndexed
                }
                val f = line.split(',').map { it.trim() }
                if (f.size != 6) throw MarketDataException("expected 6 columns, got ${f.size}", lineNo)
                val volume = number(f[5], "Volume", lineNo)
                if (volume != Math.floor(volume)) throw MarketDataException("volume must be an integer", lineNo)
                val candle = Candle(
                    date = f[0],
                    open = number(f[1], "Open", lineNo),
                    high = number(f[2], "High", lineNo),
                    low = number(f[3], "Low", lineNo),
                    close = number(f[4], "Close", lineNo),
                    volume = volume.toLong(),
                )
                validate(candle, lineNo)
                if (candles.isNotEmpty() && candle.date <= candles.last().date) {
                    throw MarketDataException("dates must be strictly ascending", lineNo)
                }
                candles += candle
            }
        }
        if (candles.isEmpty()) throw MarketDataException("file contains no quotes")
        return candles
    }

    fun load(text: String): List<Candle> = load(text.reader())

    /** Обратное преобразование: котировки в CSV того же формата (для передачи в C++ CLI). */
    fun toCsv(candles: List<Candle>): String = buildString {
        append("Date,Open,High,Low,Close,Volume\n")
        for (c in candles) append("${c.date},${c.open},${c.high},${c.low},${c.close},${c.volume}\n")
    }

    fun loadFile(file: File): List<Candle> {
        if (!file.isFile) throw MarketDataException("cannot open file: ${file.path}")
        return file.bufferedReader().use { load(it) }
    }

    fun validate(c: Candle, line: Int = 0) {
        // проверяем реальную календарную дату (2024-02-30 — ошибка), как и PostgreSQL DATE
        val valid = isoDate.matches(c.date) && runCatching { LocalDate.parse(c.date) }.isSuccess
        if (!valid) {
            throw MarketDataException("invalid date '${c.date}', expected YYYY-MM-DD", line)
        }
        if (listOf(c.open, c.high, c.low, c.close).any { it <= 0 }) {
            throw MarketDataException("prices must be positive", line)
        }
        if (c.high < maxOf(c.open, c.close) || c.low > minOf(c.open, c.close) || c.low > c.high) {
            throw MarketDataException("inconsistent OHLC: low <= open,close <= high violated", line)
        }
        if (c.volume < 0) throw MarketDataException("volume must be non-negative", line)
    }

    private fun number(s: String, field: String, line: Int): Double {
        // toDoubleOrNull принимает "NaN", "0x1p3", суффиксы d/f — допускаем только десятичную запись
        val v = if (numberChars.matches(s)) s.toDoubleOrNull() else null
        if (v == null || !v.isFinite()) {
            throw MarketDataException("invalid number in $field: '$s'", line)
        }
        return v
    }
}
