package com.stockflow.support;

import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * A real Postgres for integration tests: a database of the context's own on the build's one shared
 * Postgres server ({@link SharedPostgres}).
 *
 * <p><b>Why not H2.</b> Every interesting thing in the schema is Postgres-specific: partial
 * indexes, {@code ON CONFLICT DO NOTHING}, {@code UPDATE ... RETURNING}, {@code TIMESTAMPTZ},
 * {@code SELECT ... FOR UPDATE} semantics. An H2 test would pass while the production migration
 * fails, which is worse than having no test.</p>
 *
 * <p>The {@link JdbcConnectionDetails} bean takes the place {@code @ServiceConnection} had: Boot's
 * datasource, and Flyway through it, connect wherever it points. Spring's test context cache keeps
 * one per distinct context configuration, so every context gets its own freshly migrated database
 * while all of them share one server — the class keeps its name so the seventy-odd
 * {@code @Import(PostgresContainer.class)} lines did not have to change.</p>
 *
 * <p><b>No {@code withReuse(true)}.</b> Reuse would keep the database between builds, and since
 * Flyway records its migrations, the demo seed would not be reapplied — so stock consumed by one
 * run would still be missing on the next, and tests would start failing after a few builds for
 * reasons that look nothing like their cause. The shared server is still started fresh per build;
 * only contexts within one build share it.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainer {

    @Bean
    JdbcConnectionDetails postgresDatabase(Environment environment) {
        return SharedPostgres.freshDatabase(environment);
    }
}
