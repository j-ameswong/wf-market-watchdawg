package com.watchdawg.market.store

import com.watchdawg.market.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * R2.2: fact tables are hypertables, and every unique index on one includes its partition column.
 *
 * The index check enumerates every hypertable, so a fact table a later capability adds is covered
 * without editing this class. Only [FACT_TABLES] needs a new entry.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class FactTablesTest {

    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `every fact table is a hypertable partitioned on its observation time`() {
        val partitionColumns = jdbc.query(
            """
            select hypertable_name, column_name from timescaledb_information.dimensions
            where hypertable_schema = 'public'
            """,
        ) { rs, _ -> rs.getString(1) to rs.getString(2) }.toMap()

        assertEquals(FACT_TABLES, partitionColumns.filterKeys { it in FACT_TABLES })
    }

    @Test
    fun `every unique index on a hypertable includes its partition column`() {
        val uniqueIndexes = jdbc.query(
            """
            select i.indexrelid::regclass::text,
                   exists (
                       select 1 from unnest(i.indkey) k
                       join pg_attribute a on a.attrelid = i.indrelid and a.attnum = k
                       where a.attname = d.column_name
                   )
            from timescaledb_information.dimensions d
            join pg_index i on i.indrelid = format('%I.%I', d.hypertable_schema, d.hypertable_name)::regclass
            where i.indisunique
            """,
        ) { rs, _ -> rs.getString(1) to rs.getBoolean(2) }

        // Not vacuous: every fact table has a primary key, so there is always something to check.
        assertTrue(uniqueIndexes.size >= FACT_TABLES.size, "found only $uniqueIndexes")
        assertEquals(emptyList(), uniqueIndexes.filterNot { it.second }.map { it.first })
    }

    @Test
    fun `TimescaleDB itself refuses a unique index without the partition column`() {
        // Which is why the index check above can be a standing guard rather than a code review
        // rule: the engine will not let such an index exist.
        assertFailsWith<UncategorizedSQLException> {
            jdbc.execute("create unique index order_event_order_id_only on order_event (order_id)")
        }
    }

    private companion object {
        /** Every fact table, and the column it is partitioned on. */
        val FACT_TABLES = mapOf("order_event" to "observed_at")
    }
}
