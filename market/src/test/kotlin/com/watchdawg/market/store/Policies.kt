package com.watchdawg.market.store

import org.springframework.jdbc.core.JdbcTemplate

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
