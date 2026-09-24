package com.watchdawg.market

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withCommand(
                "postgres",
                "-c", "fsync=off",
                "-c", "shared_preload_libraries=timescaledb",
                "-c", "timescaledb.telemetry_level=off",
                // No background jobs: a policy runs only when a test calls run_job, so a
                // retention job cannot fire in the middle of a test.
                "-c", "timescaledb.max_background_workers=0",
            )

    companion object {
        /** The same image `compose.yaml` runs in dev. `TimescaleTest` fails if the two differ. */
        const val IMAGE = "timescale/timescaledb:2.30.1-pg18"
    }
}
