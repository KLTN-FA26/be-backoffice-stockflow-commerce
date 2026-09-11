package com.stockflow.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real Postgres for integration tests.
 *
 * <p><b>Why not H2.</b> Every interesting thing in the schema is Postgres-specific: partial
 * indexes, {@code ON CONFLICT DO NOTHING}, {@code UPDATE ... RETURNING}, {@code TIMESTAMPTZ},
 * {@code SELECT ... FOR UPDATE} semantics. An H2 test would pass while the production migration
 * fails, which is worse than having no test.</p>
 *
 * <p>{@code @ServiceConnection} wires the container's URL, user and password into the datasource
 * automatically — no {@code @DynamicPropertySource} block. Spring's test context cache keeps one
 * container per distinct context configuration, so the {@code @SpringBootTest} classes share one
 * and the {@code @ApplicationModuleTest} starts a second — two containers per build, not one per
 * test class.</p>
 *
 * <p><b>No {@code withReuse(true)}.</b> Reuse would keep the database between builds, and since
 * Flyway records its migrations, the demo seed would not be reapplied — so stock consumed by one
 * run would still be missing on the next, and tests would start failing after a few builds for
 * reasons that look nothing like their cause. A fresh container per run costs a few seconds and
 * removes an entire class of "works on my machine".</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainer {

    @Bean
    @ServiceConnection
    @SuppressWarnings("resource") // Testcontainers' Ryuk stops the container when the JVM exits.
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("stockflow")
                .withUsername("stockflow")
                .withPassword("stockflow");
    }
}
