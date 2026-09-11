package com.stockflow.common.lock;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Makes {@code @Scheduled} jobs safe to run on more than one instance.
 *
 * <h2>The problem</h2>
 *
 * <p>A {@code @Scheduled} method runs on <b>every</b> instance. With one instance that is invisible;
 * the day a second is started for availability, every job doubles. For the jobs in this system that
 * ranges from wasteful to wrong: the reservation sweeper would do redundant work (safe, because row
 * locks protect it), the audit purge would delete in parallel (safe but slower), and any future job
 * that sends an email or calls a supplier API would do it twice (not safe at all).</p>
 *
 * <p>ADR-0005 records this as the outstanding item before scaling past one instance. This is it,
 * done.</p>
 *
 * <h2>Why the database and not Redis</h2>
 *
 * <p>{@link RedisDistributedLock} exists and would work. The scheduler lock uses Postgres instead
 * because it must survive a Redis eviction or restart — Redis is configured with
 * {@code allkeys-lru} and no persistence, so under memory pressure it may drop a lock key and let
 * two instances run the same job. The database has no such behaviour, and the lock table is written
 * a few times an hour, so the cost is nil.</p>
 *
 * <p>{@code usingDbTime()} makes ShedLock take timestamps from Postgres rather than from each
 * application's clock. Two instances whose clocks differ by a few seconds would otherwise disagree
 * about when a lock expires — the classic way this protection fails quietly.</p>
 *
 * <h2>Using it</h2>
 * <pre>
 * &#64;Scheduled(cron = "0 30 3 * * *")
 * &#64;SchedulerLock(name = "auditRetention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
 * public void purgeExpired() { ... }
 * </pre>
 *
 * <p>{@code lockAtMostFor} must exceed the job's slowest plausible run, or a second instance starts
 * while the first is still going. {@code lockAtLeastFor} guards against a job that finishes in
 * milliseconds being started again by an instance whose clock is slightly ahead.</p>
 *
 * <p>All three scheduled jobs in the application carry it today: {@code AuditRetention},
 * {@code IdempotencyHousekeeping} and {@code ReservationSweeper}.</p>
 *
 * <h2>The annotation is not optional, and forgetting it is silent</h2>
 *
 * <p>{@code @EnableSchedulerLock} installs an advisor that matches {@code @SchedulerLock} <b>and
 * nothing else</b>. A {@code @Scheduled} method without it is simply not advised: no warning, no
 * log line, no failed startup — the configuration on this class is present and correct, and the job
 * is unprotected anyway. The symptom appears only on the day a second instance is started.</p>
 *
 * <p>{@code ArchitectureTest.everyScheduledJobIsLockedAcrossInstances} is what makes that omission
 * fail the build instead.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName("platform.shedlock")
                        .usingDbTime()
                        .build());
    }
}
