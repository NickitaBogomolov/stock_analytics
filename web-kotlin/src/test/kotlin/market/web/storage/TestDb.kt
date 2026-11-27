package market.web.storage

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Подключение к тестовой PostgreSQL. Задаётся переменными MARKET_TEST_DB_URL / _USER / _PASSWORD;
 * если их нет, тесты с БД пропускаются (skipped), а не падают.
 */
object TestDb {
    private val config: DbConfig? = System.getenv("MARKET_TEST_DB_URL")?.takeIf { it.isNotBlank() }?.let {
        DbConfig(it, System.getenv("MARKET_TEST_DB_USER") ?: "market", System.getenv("MARKET_TEST_DB_PASSWORD") ?: "market")
    }

    private val dataSource: HikariDataSource? by lazy { config?.let(Database::connect) }

    /** Чистая база (схема мигрирована, таблицы пусты) или пропуск теста. */
    fun freshDataSource(): HikariDataSource {
        assumeTrue(config != null, "MARKET_TEST_DB_URL is not set")
        val ds = dataSource!!
        ds.connection.use { c ->
            c.createStatement().use {
                it.execute("TRUNCATE instrument, comparison, lab_run RESTART IDENTITY CASCADE")
            }
        }
        return ds
    }
}
