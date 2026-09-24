package com.watchdawg.market.store

import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant

/**
 * Reads and runs TimescaleDB policy jobs. The test container has no background workers, so a
 * policy runs only when a test calls [run].
 *
 * [relation] is a hypertable or a continuous aggregate, named as `timescaledb_information.jobs`
 * names it.
 */
class Policies(private val jdbc: JdbcTemplate) {

    /** The policies attached to [relation], as their procedure names, e.g. `policy_retention`. */
    fun on(relation: String): List<String> = jdbc.queryForList(
        "select proc_name from timescaledb_information.jobs where hypertable_name = ? order by proc_name",
        String::class.java,
        relation,
    ).filterNotNull()

    /**
     * An instant whose whole chunk is older than the age one of [relation]'s policies acts on, such
     * as `compress_after` or `drop_after`. Read from the policy and the chunk interval rather than
     * hardcoded, so tests follow `R__storage_policies.sql`.
     */
    fun beyondAge(proc: String, relation: String, age: String): Instant = jdbc.queryForObject(
        """
        select now() - (j.config ->> ?)::interval - d.time_interval - interval '1 hour'
        from timescaledb_information.jobs j
        join timescaledb_information.dimensions d on d.hypertable_name = j.hypertable_name
        where j.proc_name = ? and j.hypertable_name = ?
        """,
        Timestamp::class.java,
        age,
        proc,
        relation,
    )!!.toInstant()

    fun run(proc: String, relation: String) {
        val job = jdbc.queryForObject(
            "select job_id from timescaledb_information.jobs where proc_name = ? and hypertable_name = ?",
            Int::class.java,
            proc,
            relation,
        )
        // A procedure call, which cannot be bound as a parameter.
        jdbc.execute("call run_job($job)")
    }

    companion object {
        const val COMPRESSION = "policy_compression"
        const val RETENTION = "policy_retention"
        const val REFRESH = "policy_refresh_continuous_aggregate"
    }
}
