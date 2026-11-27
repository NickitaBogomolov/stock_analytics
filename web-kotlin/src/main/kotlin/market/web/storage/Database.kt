package market.web.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import market.core.CsvLoader
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.io.File

data class DbConfig(val url: String, val user: String, val password: String) {
    companion object {
        /** MARKET_DB_URL=jdbc:postgresql://host:5432/db, MARKET_DB_USER, MARKET_DB_PASSWORD. */
        fun fromEnv(env: Map<String, String> = System.getenv()): DbConfig? {
            val url = env["MARKET_DB_URL"]?.takeIf { it.isNotBlank() } ?: return null
            return DbConfig(url, env["MARKET_DB_USER"] ?: "market", env["MARKET_DB_PASSWORD"] ?: "")
        }
    }
}

object Database {
    private val log = LoggerFactory.getLogger(Database::class.java)

    /** Пул соединений + применение миграций Flyway. Ждёт базу до 60 с (старт в docker compose). */
    fun connect(cfg: DbConfig): HikariDataSource {
        val hc = HikariConfig().apply {
            jdbcUrl = cfg.url
            username = cfg.user
            password = cfg.password
            maximumPoolSize = 10
            poolName = "market-db"
            initializationFailTimeout = 60_000
            addDataSourceProperty("reWriteBatchedInserts", "true")
        }
        val ds = HikariDataSource(hc)
        val result = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
        log.info("PostgreSQL connected ({}), migrations applied: {}", cfg.url, result.migrationsExecuted)
        return ds
    }
}

/** Загружает демо-котировки из каталога в хранилище, если такого инструмента ещё нет. */
object DemoDataSeeder {
    private val log = LoggerFactory.getLogger(DemoDataSeeder::class.java)
    private val tickerPattern = Regex("[A-Za-z0-9_.-]{1,32}")

    fun seed(repo: MarketRepository, dir: File): Int {
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("csv", true) }?.sortedBy { it.name } ?: return 0
        var imported = 0
        for (f in files) {
            val ticker = f.nameWithoutExtension
            if (!tickerPattern.matches(ticker) || repo.exists(ticker)) continue
            runCatching { repo.saveInstrument(ticker, InstrumentSource.DEMO, CsvLoader.loadFile(f)) }
                .onSuccess { imported++ }
                .onFailure { log.warn("Skip demo file {}: {}", f.name, it.message) }
        }
        if (imported > 0) log.info("Imported {} demo instrument(s) from {}", imported, dir)
        return imported
    }
}
