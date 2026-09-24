package com.watchdawg.market.harness

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestContext
import org.springframework.test.context.support.AbstractTestExecutionListener

/**
 * Empties the database before every Spring test method (R2.7).
 *
 * Test classes with the same annotations share one context, and so one container. Without this, a
 * test that counts rows sees whatever an earlier test left behind, and passes or fails depending
 * on which ran first.
 *
 * Tables and continuous aggregates are read from the catalog rather than listed, so one a later
 * migration adds is emptied with no edit here. `flyway_schema_history` is kept: without it the
 * next context would re-run every migration against tables that already exist.
 */
class DatabaseResetListener : AbstractTestExecutionListener() {
    override fun beforeTestMethod(testContext: TestContext) {
        testContext.applicationContext.getBeanProvider(JdbcTemplate::class.java).ifAvailable(::reset)
    }

    private fun reset(jdbc: JdbcTemplate) {
        val tables = jdbc.queryForList(
            """
            select format('%I.%I', schemaname, tablename) from pg_tables
            where schemaname = 'public' and tablename <> 'flyway_schema_history'
            """,
            String::class.java,
        )
        if (tables.isNotEmpty()) jdbc.execute("truncate ${tables.joinToString()} restart identity cascade")

        // An aggregate keeps its materialized rows when its source hypertable is truncated.
        jdbc.queryForList(
            "select format('%I.%I', view_schema, view_name) from timescaledb_information.continuous_aggregates",
            String::class.java,
        ).forEach { jdbc.execute("truncate $it") }
    }
}
