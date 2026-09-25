package com.watchdawg.market.store

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import javax.sql.DataSource

/**
 * Throwaway databases inside the shared test container, for tests that migrate from scratch without
 * touching the database the rest of the suite uses.
 */
class ScratchDatabases(private val container: PostgreSQLContainer, private val admin: JdbcTemplate) {

    /** Creates an empty database, hands [test] a data source for it, and drops it afterwards. */
    fun use(test: (DataSource) -> Unit) {
        val name = "scratch_" + UUID.randomUUID().toString().replace("-", "").take(12)
        admin.execute("create database $name")
        try {
            test(DriverManagerDataSource(url(name), container.username, container.password))
        } finally {
            admin.execute("drop database $name with (force)")
        }
    }

    private fun url(db: String) =
        "jdbc:postgresql://${container.host}:${container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$db"
}
