package com.apollodeploy.billing.infrastructure.persistence

import com.apollodeploy.billing.infrastructure.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

class DatabasePool private constructor(
    private val dataSource: HikariDataSource?,
) : AutoCloseable {
    @Volatile
    private var closed = false

    fun isClosed(): Boolean = closed

    companion object {
        fun create(): DatabasePool =
            create(
                jdbcUrl = "jdbc:postgresql://${AppConfig.platformDbHost}:${AppConfig.platformDbPort}/${AppConfig.platformDbName}",
                username = AppConfig.platformDbUser,
                password = AppConfig.platformDbPassword,
                maximumPoolSize = AppConfig.platformDbPoolMaxSize,
                idleTimeoutMs = AppConfig.platformDbIdleTimeoutMs,
                connectionTimeoutMs = AppConfig.platformDbConnectionTimeoutMs,
                statementTimeoutMs = AppConfig.platformDbStatementTimeoutMs,
            )

        /**
         * Read-only pool — billing_superuser on the platform database.
         * Used for SELECT on apikey. Shares the platform DB host/name but uses
         * the billing_superuser role instead of billing_app.
         */
        fun createPlatformReader(): DatabasePool =
            create(
                jdbcUrl = "jdbc:postgresql://${AppConfig.platformDbHost}:${AppConfig.platformDbPort}/${AppConfig.platformDbName}",
                username = AppConfig.platformReaderDbUser,
                password = AppConfig.platformReaderDbPassword,
                maximumPoolSize = AppConfig.platformReaderDbPoolMaxSize,
                idleTimeoutMs = AppConfig.platformReaderDbIdleTimeoutMs,
                connectionTimeoutMs = AppConfig.platformReaderDbConnectionTimeoutMs,
                statementTimeoutMs = AppConfig.platformReaderDbStatementTimeoutMs,
            )

        /**
         * Read-only pool — billing_superuser on the signal database.
         * Used for SELECT on projects, domains, webhook_endpoints, emails.
         *
         * Returns null if the signal database is not reachable, allowing the
         * app to start without it (signal features will be unavailable).
         */
        fun createSignal(): DatabasePool? =
            try {
                create(
                    jdbcUrl = "jdbc:postgresql://${AppConfig.signalDbHost}:${AppConfig.signalDbPort}/${AppConfig.signalDbName}",
                    username = AppConfig.signalDbUser,
                    password = AppConfig.signalDbPassword,
                    maximumPoolSize = AppConfig.signalDbPoolMaxSize,
                    idleTimeoutMs = AppConfig.signalDbIdleTimeoutMs,
                    connectionTimeoutMs = AppConfig.signalDbConnectionTimeoutMs,
                    statementTimeoutMs = AppConfig.signalDbStatementTimeoutMs,
                )
            } catch (e: Exception) {
                LoggerFactory
                    .getLogger(DatabasePool::class.java)
                    .warn("[billing] Signal DB unavailable — signal features disabled. Reason: {}", e.message)
                null
            }

        /**
         * Stub pool for manifest-only / SDK generation mode (TESSERACT_GENERATE=1).
         *
         * No real database connection is ever opened. Any call to [withConnection] or
         * [withTransaction] will throw [IllegalStateException] because the pool is
         * already closed, which is intentional — SDK generation never executes request
         * handlers so the pool should never be called.
         */
        fun createStub(): DatabasePool = DatabasePool(dataSource = null).also { it.close() }

        fun create(
            jdbcUrl: String,
            username: String,
            password: String,
            maximumPoolSize: Int = 10,
            idleTimeoutMs: Long = 10_000,
            connectionTimeoutMs: Long = 3_000,
            statementTimeoutMs: Long = 30_000,
        ): DatabasePool {
            val hikariConfig =
                HikariConfig().apply {
                    this.jdbcUrl = jdbcUrl
                    this.username = username
                    if (password.isNotBlank()) this.password = password
                    this.maximumPoolSize = maximumPoolSize
                    idleTimeout = idleTimeoutMs
                    connectionTimeout = connectionTimeoutMs
                    isAutoCommit = true
                    addDataSourceProperty("statement_timeout", statementTimeoutMs.toString())
                    addDataSourceProperty("ApplicationName", "apollo-billing")
                }
            return DatabasePool(HikariDataSource(hikariConfig))
        }
    }

    fun <T> withConnection(block: (Connection) -> T): T {
        checkOpen()
        dataSource!!.connection.use { return block(it) }
    }

    fun <T> withTransaction(block: (Connection) -> T): T {
        checkOpen()
        dataSource!!.connection.use { conn ->
            conn.autoCommit = false
            return try {
                val result = block(conn)
                conn.commit()
                result
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        dataSource?.close()
    }

    private fun checkOpen() {
        if (closed) throw IllegalStateException("Database pool is closed")
    }
}

// ─── Query helpers ────────────────────────────────────────────────────────────

fun <T> Connection.prepareAndQuery(
    sql: String,
    params: List<Any?>,
    mapper: (ResultSet) -> T,
): List<T> {
    prepareStatement(sql).use { stmt ->
        stmt.bindParams(params)
        stmt.executeQuery().use { rs ->
            val results = mutableListOf<T>()
            while (rs.next()) results.add(mapper(rs))
            return results
        }
    }
}

fun Connection.executeUpdate(
    sql: String,
    params: List<Any?>,
): Int {
    prepareStatement(sql).use { stmt ->
        stmt.bindParams(params)
        return stmt.executeUpdate()
    }
}

private fun PreparedStatement.bindParams(params: List<Any?>) {
    params.forEachIndexed { index, param ->
        val i = index + 1
        when (param) {
            null -> setNull(i, java.sql.Types.NULL)
            is String -> setString(i, param)
            is Int -> setInt(i, param)
            is Long -> setLong(i, param)
            is Double -> setDouble(i, param)
            is Boolean -> setBoolean(i, param)
            is java.time.Instant -> setObject(i, java.sql.Timestamp.from(param))
            is java.util.UUID -> setObject(i, param)
            else -> setObject(i, param)
        }
    }
}
