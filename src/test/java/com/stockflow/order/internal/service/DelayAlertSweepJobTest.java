package com.stockflow.order.internal.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review of 4A-7: the nightly delay-alert sweep says how long it took, its lock outlasts a realistic run by far, and one
 * order failing neither stops the rest nor shares their transaction.
 */
class DelayAlertSweepJobTest {

    @Test
    @DisplayName("counts what each order changed, survives one failing, one transaction per order, and times the pass")
    void sweepsEveryOrderAndTimesThePass() {
        UUID first = UUID.randomUUID();
        UUID failing = UUID.randomUUID();
        UUID last = UUID.randomUUID();
        DelayAlertService alerts = mock(DelayAlertService.class);
        when(alerts.sweepCandidates()).thenReturn(List.of(first, failing, last));
        when(alerts.recompute(first)).thenReturn(new DelayAlertService.Recomputed(2, 0));
        when(alerts.recompute(failing)).thenThrow(new IllegalStateException("boom"));
        when(alerts.recompute(last)).thenReturn(new DelayAlertService.Recomputed(0, 1));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        Clock clock = mock(Clock.class);
        Instant start = Instant.parse("2026-11-09T17:05:00Z");
        when(clock.instant()).thenReturn(start, start.plusSeconds(42));

        DelayAlertSweepJob.Summary summary = new DelayAlertSweepJob(alerts, transactions, clock).run();

        assertThat(summary).isEqualTo(new DelayAlertSweepJob.Summary(2, 2, 1, 1, Duration.ofSeconds(42)));
        verify(transactions, times(3)).getTransaction(any());
        verify(transactions, times(2)).commit(any());
        verify(transactions, times(1)).rollback(any());
    }

    @Test
    @DisplayName("the lock is held at most two hours: far above a pass of seconds, so it never lapses mid-run")
    void lockOutlastsARealisticRun() throws Exception {
        assertThat(Duration.parse(DelayAlertSweepJob.LOCK_AT_MOST)).isGreaterThanOrEqualTo(Duration.ofHours(1));
        net.javacrumbs.shedlock.spring.annotation.SchedulerLock lock = DelayAlertSweepJob.class.getMethod("sweep")
                .getAnnotation(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class);
        assertThat(lock.lockAtMostFor()).isEqualTo(DelayAlertSweepJob.LOCK_AT_MOST);
    }
}
