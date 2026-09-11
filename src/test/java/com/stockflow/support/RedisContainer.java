package com.stockflow.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

/**
 * A real Redis for tests that exercise the cache, the rate limiter or the distributed lock.
 *
 * <h2>Why a container and not an embedded fake</h2>
 *
 * <p>All three of those components put their correctness into <b>Lua scripts and atomic
 * commands</b> — the token bucket's read-modify-write, the lock's compare-and-delete,
 * {@code SET NX PX}. An in-memory fake either does not implement {@code EVAL} at all, or implements
 * it non-atomically, which means a test against it passes while the real behaviour under
 * concurrency is exactly what was never tested. For these three, a fake tests the wrong thing.</p>
 *
 * <p>{@code @ServiceConnection} points {@code spring.data.redis.*} at the container automatically,
 * so no {@code @DynamicPropertySource} block is needed.</p>
 *
 * <p>Import alongside {@link PostgresContainer} on a test that needs both:</p>
 * <pre>
 * &#64;IntegrationTest
 * &#64;Import({PostgresContainer.class, RedisContainer.class})
 * class RateLimitIntegrationTest { ... }
 * </pre>
 *
 * <p>Alpine, and no persistence configured: a test Redis that survived a restart would carry state
 * between runs, which is the same trap {@code withReuse} was removed for on the Postgres side.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisContainer {

    private static final int REDIS_PORT = 6379;

    @Bean
    @ServiceConnection(name = "redis")
    @SuppressWarnings("resource") // Testcontainers' Ryuk stops the container when the JVM exits.
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(REDIS_PORT)
                // Matches docker-compose: eviction rather than an out-of-memory error, and no
                // persistence. Testing against a differently-configured Redis than production runs
                // is how an eviction-related bug survives the whole test suite.
                .withCommand("redis-server", "--maxmemory", "64mb",
                        "--maxmemory-policy", "allkeys-lru", "--save", "");
    }
}
