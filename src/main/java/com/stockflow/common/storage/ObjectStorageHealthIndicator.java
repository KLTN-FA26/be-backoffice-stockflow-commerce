package com.stockflow.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether the object store is actually reachable.
 *
 * <h2>Why this is hand-written when Redis and Postgres are not</h2>
 *
 * <p>Spring Boot auto-configures health indicators for the datasource and for Redis, so those two
 * appear in {@code /actuator/health} for free. Nothing knows about S3. Without this, an application
 * whose object store is unreachable reports itself perfectly healthy, keeps taking traffic, and
 * fails every single upload — with the first sign being a customer complaint rather than a
 * monitor.</p>
 *
 * <h2>Why it is deliberately not part of readiness</h2>
 *
 * <p>The readiness group in {@code application.yml} lists only {@code readinessState} and
 * {@code db}, so this indicator is <b>not part of it</b>. A storage outage therefore shows in
 * {@code /actuator/health} and on the dashboard but does <b>not</b> make Kubernetes pull the pod
 * out of the load balancer. The reasoning: uploads are a small fraction of
 * what this application does. Taking every instance out of service because MinIO is down would turn
 * a degraded feature into a total outage — the classic way a health check causes the incident it
 * was meant to detect. Postgres is the opposite case and is in readiness, because without it
 * nothing works at all.</p>
 *
 * <h2>The probe</h2>
 *
 * <p>An existence check on a key that will not be there. That is one cheap HEAD request which
 * exercises credentials, the endpoint, the bucket and the network — everything an upload needs —
 * without writing anything. Writing a probe object on every health check would put a few thousand
 * objects a day into the bucket and cost money for nothing.</p>
 *
 * <p>It is still a real network call per request, which is why the aggregate {@code /actuator/health}
 * is <b>not</b> anonymous: {@code /actuator/**} is exempt from rate limiting, so a public aggregate
 * endpoint would let anyone drive unbounded S3 traffic from a shell loop. Only the two probe groups,
 * which do not include this indicator, are open. See {@code ResourceServerSecurityConfig}.</p>
 */
@Component
class ObjectStorageHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageHealthIndicator.class);

    /**
     * A key that is valid in shape but will never exist. It has to pass
     * {@code StorageKeys.requireValid}, so it carries a real category prefix.
     */
    private static final String PROBE_KEY =
            "exports/1970/01/01/00000000-0000-7000-8000-000000000000.csv";

    private final FileStorage storage;
    private final StorageProperties properties;

    ObjectStorageHealthIndicator(FileStorage storage, StorageProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public Health health() {
        long startedAt = System.nanoTime();
        try {
            // The answer is expected to be false. What is being tested is that we can get an answer
            // at all - false means the round trip worked, which is the whole point.
            storage.exists(PROBE_KEY);
            long millis = (System.nanoTime() - startedAt) / 1_000_000;

            return Health.up()
                    .withDetail("bucket", properties.bucket())
                    .withDetail("endpoint", properties.usesCustomEndpoint()
                            ? properties.endpoint() : "aws")
                    .withDetail("responseTimeMs", millis)
                    .build();

        } catch (RuntimeException ex) {
            log.warn("Object storage health check failed: {}", ex.toString());
            return Health.down()
                    .withDetail("bucket", properties.bucket())
                    // The exception type, not the message: an SDK message can carry the endpoint,
                    // a request id and occasionally part of a credential, and /actuator/health is
                    // readable by anyone with the admin role.
                    .withDetail("error", ex.getClass().getSimpleName())
                    .build();
        }
    }
}
