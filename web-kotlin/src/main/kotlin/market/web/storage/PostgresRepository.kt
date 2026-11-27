package market.web.storage

import market.core.AnalysisParams
import market.core.AnalysisReport
import market.core.Candle
import market.core.RsiZone
import market.core.Series
import market.core.SignalType
import market.core.TradeSignal
import market.core.Trend
import java.sql.Connection
import java.sql.Date
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.sql.DataSource

/** Хранилище в PostgreSQL на чистом JDBC. Схема — db/migration/V1__init_schema.sql. */
class PostgresRepository(private val ds: DataSource) : MarketRepository {
    override val kind = "postgres"

    private fun <T> tx(block: (Connection) -> T): T = ds.connection.use { c ->
        c.autoCommit = false
        try {
            block(c).also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }

    private fun <T> read(block: (Connection) -> T): T = ds.connection.use(block)

    private fun PreparedStatement.setNullableDouble(i: Int, v: Double?) =
        if (v == null) setNull(i, Types.DOUBLE) else setDouble(i, v)

    private fun date(s: String): Date = Date.valueOf(LocalDate.parse(s))

    private inline fun <T> ResultSet.mapRows(f: (ResultSet) -> T): List<T> {
        val out = mutableListOf<T>()
        while (next()) out += f(this)
        return out
    }

    // ---------- инструменты и котировки ----------

    override fun instruments(): List<InstrumentInfo> = read { c ->
        c.prepareStatement(
            """
            SELECT i.ticker, i.source, i.name, i.synced_at, s.sessions, s.first_date, s.last_date,
                   (SELECT close FROM quote q WHERE q.ticker = i.ticker ORDER BY trade_date ASC LIMIT 1)  AS first_close,
                   (SELECT close FROM quote q WHERE q.ticker = i.ticker ORDER BY trade_date DESC LIMIT 1) AS last_close
            FROM instrument i
            JOIN (SELECT ticker, count(*) AS sessions, min(trade_date) AS first_date, max(trade_date) AS last_date
                  FROM quote GROUP BY ticker) s ON s.ticker = i.ticker
            ORDER BY i.ticker
            """.trimIndent(),
        ).use { st ->
            st.executeQuery().use { rs ->
                rs.mapRows {
                    val first = it.getDouble("first_close")
                    val last = it.getDouble("last_close")
                    InstrumentInfo(
                        ticker = it.getString("ticker"),
                        source = it.getString("source"),
                        name = it.getString("name"),
                        syncedAt = it.getObject("synced_at", OffsetDateTime::class.java)?.toString(),
                        sessions = it.getInt("sessions"),
                        firstDate = it.getDate("first_date").toString(),
                        lastDate = it.getDate("last_date").toString(),
                        lastClose = last,
                        changePct = (last / first - 1) * 100,
                    )
                }
            }
        }
    }

    override fun exists(ticker: String): Boolean = read { c ->
        c.prepareStatement("SELECT 1 FROM instrument WHERE ticker = ?").use { st ->
            st.setString(1, ticker)
            st.executeQuery().use { it.next() }
        }
    }

    override fun candles(ticker: String): List<Candle>? = read { c ->
        if (!existsIn(c, ticker)) return@read null
        c.prepareStatement("SELECT trade_date, open, high, low, close, volume FROM quote WHERE ticker = ? ORDER BY trade_date").use { st ->
            st.setString(1, ticker)
            st.executeQuery().use { rs ->
                rs.mapRows {
                    Candle(it.getDate(1).toString(), it.getDouble(2), it.getDouble(3), it.getDouble(4), it.getDouble(5), it.getLong(6))
                }
            }
        }
    }

    private fun existsIn(c: Connection, ticker: String) =
        c.prepareStatement("SELECT 1 FROM instrument WHERE ticker = ?").use { st ->
            st.setString(1, ticker)
            st.executeQuery().use { it.next() }
        }

    override fun saveInstrument(ticker: String, source: InstrumentSource, candles: List<Candle>, name: String?): InstrumentInfo {
        require(candles.isNotEmpty()) { "no quotes to save" }
        tx { c ->
            c.prepareStatement(
                """
                INSERT INTO instrument (ticker, source, name, synced_at) VALUES (?, ?, ?, CASE WHEN ? THEN now() END)
                ON CONFLICT (ticker) DO UPDATE
                    SET source = EXCLUDED.source, name = EXCLUDED.name, synced_at = EXCLUDED.synced_at, updated_at = now()
                """.trimIndent(),
            ).use { st ->
                st.setString(1, ticker)
                st.setString(2, source.db)
                st.setString(3, name)
                st.setBoolean(4, source == InstrumentSource.MOEX)
                st.executeUpdate()
            }
            c.prepareStatement("DELETE FROM quote WHERE ticker = ?").use { st ->
                st.setString(1, ticker)
                st.executeUpdate()
            }
            insertQuotes(c, ticker, candles, upsert = false)
        }
        return instruments().first { it.ticker == ticker }
    }

    override fun upsertQuotes(ticker: String, candles: List<Candle>): InstrumentInfo {
        tx { c ->
            val updated = c.prepareStatement("UPDATE instrument SET synced_at = now(), updated_at = now() WHERE ticker = ?").use { st ->
                st.setString(1, ticker)
                st.executeUpdate()
            }
            require(updated == 1) { "instrument not found: $ticker" }
            insertQuotes(c, ticker, candles, upsert = true)
        }
        return instruments().first { it.ticker == ticker }
    }

    private fun insertQuotes(c: Connection, ticker: String, candles: List<Candle>, upsert: Boolean) {
        val conflict = if (upsert) {
            """ ON CONFLICT (ticker, trade_date) DO UPDATE SET open = EXCLUDED.open, high = EXCLUDED.high,
                low = EXCLUDED.low, close = EXCLUDED.close, volume = EXCLUDED.volume"""
        } else ""
        c.prepareStatement("INSERT INTO quote (ticker, trade_date, open, high, low, close, volume) VALUES (?, ?, ?, ?, ?, ?, ?)$conflict").use { st ->
            for (q in candles) {
                st.setString(1, ticker)
                st.setDate(2, date(q.date))
                st.setDouble(3, q.open)
                st.setDouble(4, q.high)
                st.setDouble(5, q.low)
                st.setDouble(6, q.close)
                st.setLong(7, q.volume)
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    override fun deleteInstrument(ticker: String): Boolean = tx { c ->
        // расчёты лаборатории по этой бумаге без неё теряют смысл (портфель неполный) — удаляем целиком
        c.prepareStatement(
            "DELETE FROM lab_run WHERE lab_run_id IN (SELECT lab_run_id FROM lab_run_instrument WHERE ticker = ?)",
        ).use { st ->
            st.setString(1, ticker)
            st.executeUpdate()
        }
        c.prepareStatement("DELETE FROM instrument WHERE ticker = ?").use { st ->
            st.setString(1, ticker)
            st.executeUpdate() > 0
        }
    }

    // ---------- запуски анализа ----------

    override fun saveRun(report: AnalysisReport): Long = tx { c ->
        val p = report.params
        val runId = c.prepareStatement(
            """
            INSERT INTO analysis_run (ticker, engine, fast_period, slow_period, rsi_period, risk_free_pct, sessions, first_date, last_date)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING run_id
            """.trimIndent(),
        ).use { st ->
            st.setString(1, report.ticker)
            st.setString(2, report.engine)
            st.setInt(3, p.fastPeriod)
            st.setInt(4, p.slowPeriod)
            st.setInt(5, p.rsiPeriod)
            st.setDouble(6, p.riskFreeRatePct)
            st.setInt(7, report.count)
            st.setDate(8, date(report.firstDate))
            st.setDate(9, date(report.lastDate))
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        c.prepareStatement(
            """
            INSERT INTO metrics_report (run_id, last_close, total_return_pct, annual_volatility_pct, max_drawdown_pct,
                                        sharpe_ratio, rsi, trend, rsi_zone)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, runId)
            st.setDouble(2, report.lastClose)
            st.setDouble(3, report.totalReturnPct)
            st.setDouble(4, report.annualVolatilityPct)
            st.setDouble(5, report.maxDrawdownPct)
            st.setDouble(6, report.sharpeRatio)
            st.setDouble(7, report.rsi)
            st.setString(8, report.trend.name)
            st.setString(9, report.rsiZone.name)
            st.executeUpdate()
        }
        val s = report.series
        c.prepareStatement("INSERT INTO indicator_point (run_id, trade_date, close, sma_fast, sma_slow, rsi) VALUES (?, ?, ?, ?, ?, ?)").use { st ->
            for (i in s.dates.indices) {
                st.setLong(1, runId)
                st.setDate(2, date(s.dates[i]))
                st.setDouble(3, s.close[i])
                st.setNullableDouble(4, s.smaFast[i])
                st.setNullableDouble(5, s.smaSlow[i])
                st.setNullableDouble(6, s.rsi[i])
                st.addBatch()
            }
            st.executeBatch()
        }
        c.prepareStatement("INSERT INTO trade_signal (run_id, trade_date, type, price) VALUES (?, ?, ?, ?)").use { st ->
            for (sig in report.signals) {
                st.setLong(1, runId)
                st.setDate(2, date(sig.date))
                st.setString(3, sig.type.name)
                st.setDouble(4, sig.price)
                st.addBatch()
            }
            st.executeBatch()
        }
        runId
    }

    override fun run(runId: Long): AnalysisReport? = read { c ->
        val head = c.prepareStatement(
            """
            SELECT r.ticker, r.engine, r.fast_period, r.slow_period, r.rsi_period, r.risk_free_pct, r.sessions,
                   r.first_date, r.last_date, m.last_close, m.total_return_pct, m.annual_volatility_pct,
                   m.max_drawdown_pct, m.sharpe_ratio, m.rsi, m.trend, m.rsi_zone
            FROM analysis_run r JOIN metrics_report m ON m.run_id = r.run_id
            WHERE r.run_id = ?
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, runId)
            st.executeQuery().use { rs -> if (rs.next()) rs.toHead() else null }
        } ?: return@read null

        val dates = mutableListOf<String>()
        val close = mutableListOf<Double>()
        val fast = mutableListOf<Double?>()
        val slow = mutableListOf<Double?>()
        val rsi = mutableListOf<Double?>()
        c.prepareStatement("SELECT trade_date, close, sma_fast, sma_slow, rsi FROM indicator_point WHERE run_id = ? ORDER BY trade_date").use { st ->
            st.setLong(1, runId)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    dates += rs.getDate(1).toString()
                    close += rs.getDouble(2)
                    fast += rs.getObject(3) as Double?
                    slow += rs.getObject(4) as Double?
                    rsi += rs.getObject(5) as Double?
                }
            }
        }
        val signals = c.prepareStatement("SELECT trade_date, type, price FROM trade_signal WHERE run_id = ? ORDER BY trade_date").use { st ->
            st.setLong(1, runId)
            st.executeQuery().use { rs ->
                rs.mapRows { TradeSignal(it.getDate(1).toString(), SignalType.valueOf(it.getString(2)), it.getDouble(3)) }
            }
        }
        head.copy(series = Series(dates, close, fast, slow, rsi), signals = signals, runId = runId)
    }

    private fun ResultSet.toHead() = AnalysisReport(
        engine = getString("engine"),
        ticker = getString("ticker"),
        params = AnalysisParams(getInt("fast_period"), getInt("slow_period"), getInt("rsi_period"), getDouble("risk_free_pct")),
        count = getInt("sessions"),
        firstDate = getDate("first_date").toString(),
        lastDate = getDate("last_date").toString(),
        lastClose = getDouble("last_close"),
        totalReturnPct = getDouble("total_return_pct"),
        annualVolatilityPct = getDouble("annual_volatility_pct"),
        maxDrawdownPct = getDouble("max_drawdown_pct"),
        sharpeRatio = getDouble("sharpe_ratio"),
        rsi = getDouble("rsi"),
        trend = Trend.valueOf(getString("trend")),
        rsiZone = RsiZone.valueOf(getString("rsi_zone")),
        series = Series(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        signals = emptyList(),
    )

    override fun runs(ticker: String?, limit: Int): List<RunSummary> = read { c ->
        c.prepareStatement(
            """
            SELECT r.run_id, r.ticker, r.engine, r.fast_period, r.slow_period, r.rsi_period, r.risk_free_pct, r.created_at,
                   m.total_return_pct, m.sharpe_ratio, m.rsi, m.trend
            FROM analysis_run r JOIN metrics_report m ON m.run_id = r.run_id
            WHERE (?::varchar IS NULL OR r.ticker = ?)
            ORDER BY r.run_id DESC
            LIMIT ?
            """.trimIndent(),
        ).use { st ->
            st.setString(1, ticker)
            st.setString(2, ticker)
            st.setInt(3, limit)
            st.executeQuery().use { rs ->
                rs.mapRows {
                    RunSummary(
                        runId = it.getLong("run_id"),
                        ticker = it.getString("ticker"),
                        engine = it.getString("engine"),
                        params = AnalysisParams(it.getInt("fast_period"), it.getInt("slow_period"), it.getInt("rsi_period"), it.getDouble("risk_free_pct")),
                        createdAt = it.getObject("created_at", OffsetDateTime::class.java).toString(),
                        totalReturnPct = it.getDouble("total_return_pct"),
                        sharpeRatio = it.getDouble("sharpe_ratio"),
                        rsi = it.getDouble("rsi"),
                        trend = it.getString("trend"),
                    )
                }
            }
        }
    }

    // ---------- сравнения ----------

    override fun saveComparison(result: CompareResult, params: AnalysisParams): Long = tx { c ->
        val id = c.prepareStatement(
            "INSERT INTO comparison (fast_period, slow_period, rsi_period, risk_free_pct, common_dates) VALUES (?, ?, ?, ?, ?) RETURNING comparison_id",
        ).use { st ->
            st.setInt(1, params.fastPeriod)
            st.setInt(2, params.slowPeriod)
            st.setInt(3, params.rsiPeriod)
            st.setDouble(4, params.riskFreeRatePct)
            st.setInt(5, result.dates.size)
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        c.prepareStatement(
            """
            INSERT INTO comparison_item (comparison_id, ticker, position, total_return_pct, annual_volatility_pct,
                                         max_drawdown_pct, sharpe_ratio, rsi, trend)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { st ->
            result.rows.forEachIndexed { pos, r ->
                st.setLong(1, id)
                st.setString(2, r.ticker)
                st.setInt(3, pos)
                st.setDouble(4, r.totalReturnPct)
                st.setDouble(5, r.annualVolatilityPct)
                st.setDouble(6, r.maxDrawdownPct)
                st.setDouble(7, r.sharpeRatio)
                st.setDouble(8, r.rsi)
                st.setString(9, r.trend)
                st.addBatch()
            }
            st.executeBatch()
        }
        c.prepareStatement("INSERT INTO correlation_cell (comparison_id, ticker_a, ticker_b, value) VALUES (?, ?, ?, ?)").use { st ->
            result.rows.forEachIndexed { i, a ->
                result.rows.forEachIndexed { j, b ->
                    st.setLong(1, id)
                    st.setString(2, a.ticker)
                    st.setString(3, b.ticker)
                    st.setDouble(4, result.correlation[i][j])
                    st.addBatch()
                }
            }
            st.executeBatch()
        }
        id
    }

    override fun healthy(): Boolean = runCatching {
        read { c -> c.createStatement().use { it.executeQuery("SELECT 1").use { rs -> rs.next() } } }
    }.getOrDefault(false)

    // ---------- квант-лаборатория ----------

    override fun saveLabRun(kind: String, tickers: List<String>, headline: String, paramsJson: String, resultJson: String, computeMs: Double): Long =
        tx { c ->
            val id = c.prepareStatement(
                """
                INSERT INTO lab_run (kind, headline, params, result, compute_ms)
                VALUES (?, ?, ?::jsonb, ?::jsonb, ?) RETURNING lab_run_id
                """.trimIndent(),
            ).use { st ->
                st.setString(1, kind)
                st.setString(2, headline.take(256))
                st.setString(3, paramsJson)
                st.setString(4, resultJson)
                st.setDouble(5, computeMs)
                st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
            c.prepareStatement("INSERT INTO lab_run_instrument (lab_run_id, ticker, position) VALUES (?, ?, ?)").use { st ->
                tickers.forEachIndexed { i, t ->
                    st.setLong(1, id)
                    st.setString(2, t)
                    st.setInt(3, i)
                    st.addBatch()
                }
                st.executeBatch()
            }
            id
        }

    private val labSummarySql = """
        SELECT r.lab_run_id, r.kind, r.headline, r.compute_ms, r.created_at,
               coalesce(array_agg(i.ticker ORDER BY i.position) FILTER (WHERE i.ticker IS NOT NULL), '{}') AS tickers
        FROM lab_run r LEFT JOIN lab_run_instrument i ON i.lab_run_id = r.lab_run_id
    """.trimIndent()

    private fun ResultSet.toLabSummary() = LabRunSummary(
        labRunId = getLong("lab_run_id"),
        kind = getString("kind"),
        tickers = (getArray("tickers").array as Array<*>).map { it.toString() },
        headline = getString("headline"),
        computeMs = getDouble("compute_ms"),
        createdAt = getObject("created_at", OffsetDateTime::class.java).toString(),
    )

    override fun labRuns(limit: Int): List<LabRunSummary> = read { c ->
        c.prepareStatement("$labSummarySql GROUP BY r.lab_run_id ORDER BY r.lab_run_id DESC LIMIT ?").use { st ->
            st.setInt(1, limit)
            st.executeQuery().use { rs -> rs.mapRows { it.toLabSummary() } }
        }
    }

    override fun labRun(id: Long): LabRunRecord? = read { c ->
        val summary = c.prepareStatement("$labSummarySql WHERE r.lab_run_id = ? GROUP BY r.lab_run_id").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.toLabSummary() else null }
        } ?: return@read null
        c.prepareStatement("SELECT params::text, result::text FROM lab_run WHERE lab_run_id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs -> rs.next(); LabRunRecord(summary, rs.getString(1), rs.getString(2)) }
        }
    }
}
