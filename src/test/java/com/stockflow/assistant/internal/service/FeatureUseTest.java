package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.FeatureUseRepository;
import com.stockflow.assistant.internal.domain.FeatureUseRepository.FeatureCount;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class FeatureUseTest {

    /** 2026-11-27 23:30 in Vietnam: 16:30 UTC, still the 27th there. */
    private static final Clock LATE_EVENING = Clock.fixed(Instant.parse("2026-11-27T16:30:00Z"), ZoneOffset.UTC);

    private static final CurrentUser SALES = user(Set.of(Role.SALES_STAFF));
    private static final CurrentUser SALES_LEAD = user(Set.of(Role.SALES_STAFF, Role.SALES_MANAGER));

    /** The rows written, and a switch to make the next write fail. */
    private static final class Rows implements FeatureUseRepository {

        final List<FeatureCount> added = new ArrayList<>();
        final AtomicBoolean failNext = new AtomicBoolean();

        @Override
        public void add(List<FeatureCount> counts) {
            if (failNext.getAndSet(false)) {
                throw new IllegalStateException("database down");
            }
            added.addAll(counts);
        }

        @Override
        public List<FeatureCount> between(LocalDate from, LocalDate to) {
            return added.stream().filter(count -> !count.day().isBefore(from) && !count.day().isAfter(to)).toList();
        }

        @Override
        public int deleteBefore(LocalDate day, int limit) {
            return 0;
        }
    }

    @Test
    @DisplayName("uses are counted in memory per feature, role and Vietnam day, and written as one count each on "
            + "the flush")
    void countsAndFlushes() {
        Rows rows = new Rows();
        FeatureUse use = new FeatureUse(rows, mock(PlatformTransactionManager.class), LATE_EVENING);

        use.record(FeatureUse.CHAT, SALES);
        use.record(FeatureUse.CHAT, SALES);
        use.record(FeatureUse.CHAT, SALES_LEAD);
        use.record(FeatureUse.ERROR_EXPLANATION, SALES);
        assertThat(rows.added).as("nothing reaches the database on the request").isEmpty();

        use.flush();

        LocalDate day = LocalDate.of(2026, 11, 27);
        assertThat(rows.added).containsExactlyInAnyOrder(
                new FeatureCount("chat", "SALES_STAFF", day, 2),
                new FeatureCount("chat", "SALES_STAFF+SALES_MANAGER", day, 1),
                new FeatureCount("error-explanation", "SALES_STAFF", day, 1));

        use.flush();
        assertThat(rows.added).as("a flush with nothing new writes nothing").hasSize(3);
    }

    @Test
    @DisplayName("a failed write keeps its counts for the next flush; nothing reaches the caller of record")
    void aFailedWriteKeepsTheCounts() {
        Rows rows = new Rows();
        FeatureUse use = new FeatureUse(rows, mock(PlatformTransactionManager.class), LATE_EVENING);
        use.record(FeatureUse.TODAY, SALES);
        rows.failNext.set(true);

        assertThatThrownBy(use::flush).isInstanceOf(IllegalStateException.class);
        use.record(FeatureUse.TODAY, SALES);
        use.flush();

        assertThat(rows.added).containsExactly(new FeatureCount("today", "SALES_STAFF", LocalDate.of(2026, 11, 27), 2));
    }

    @Test
    @DisplayName("a key outside the contract's list, a user without roles and no component at all never throw")
    void neverThrows() {
        Rows rows = new Rows();
        FeatureUse use = new FeatureUse(rows, mock(PlatformTransactionManager.class), LATE_EVENING);

        assertThatCode(() -> {
            use.record("made-up", SALES);
            use.record(null, SALES);
            use.record(FeatureUse.WORK_QUEUE, user(Set.of()));
            use.record(FeatureUse.WORK_QUEUE, null);
            FeatureUse.NONE.record(FeatureUse.CHAT, SALES);
        }).doesNotThrowAnyException();
        use.flush();

        assertThat(rows.added).containsExactly(
                new FeatureCount("work-queue", "OTHER", LocalDate.of(2026, 11, 27), 2));
    }

    @Test
    @DisplayName("a period is read whole, both days included, and refused backwards or longer than 92 days")
    void periods() {
        Rows rows = new Rows();
        FeatureUse use = new FeatureUse(rows, mock(PlatformTransactionManager.class), LATE_EVENING);
        LocalDate first = LocalDate.of(2026, 9, 1);

        assertThat(use.between(first, first.plusDays(91))).isEmpty();
        assertThatThrownBy(() -> use.between(first, first.plusDays(92)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> use.between(first, first.minusDays(1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("every feature key fits the table's check, and roles join in the enum's order")
    void keysAndRoles() {
        assertThat(FeatureUse.KEYS).hasSize(14).allSatisfy(key ->
                assertThat(key).matches("^[a-z][a-z0-9]*(-[a-z0-9]+)*$").hasSizeLessThanOrEqualTo(40));
        assertThat(FeatureUse.roleOf(SALES_LEAD)).isEqualTo("SALES_STAFF+SALES_MANAGER")
                .matches("^[A-Z][A-Z_]*(\\+[A-Z][A-Z_]*)*$");
        assertThat(FeatureUse.roles("SALES_STAFF+SALES_MANAGER")).containsExactly("SALES_STAFF", "SALES_MANAGER");
        assertThat(FeatureUse.roles(null)).isEmpty();
    }

    @Test
    @DisplayName("the telemetry's featureUsed, which every stream calls, both counts the metric and keeps the count")
    void telemetryRecordsIntoFeatureUse() {
        Rows rows = new Rows();
        FeatureUse use = new FeatureUse(rows, mock(PlatformTransactionManager.class), LATE_EVENING);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AssistantTelemetry telemetry = new AssistantTelemetry(ObservationRegistry.NOOP, meters, use);

        telemetry.featureUsed(FeatureUse.NEXT_STEPS, SALES);
        use.flush();

        assertThat(meters.counter("assistant.feature.used", "feature", "next-steps", "role", "SALES_STAFF").count())
                .isEqualTo(1.0);
        assertThat(rows.added).containsExactly(
                new FeatureCount("next-steps", "SALES_STAFF", LocalDate.of(2026, 11, 27), 1));
    }

    @Test
    @DisplayName("stopping lets a flush in flight finish and write its counts: interrupting it lost them, since its "
            + "failed write put them back only after the final flush had found the counters empty")
    void stopKeepsTheFlushInFlight() throws Exception {
        java.util.concurrent.CountDownLatch writing = new java.util.concurrent.CountDownLatch(1);
        List<FeatureCount> written = java.util.Collections.synchronizedList(new ArrayList<>());
        FeatureUseRepository slow = new FeatureUseRepository() {
            @Override
            public void add(List<FeatureCount> counts) {
                writing.countDown();
                try {
                    // A slow write: the database answers after 300 ms.
                    new java.util.concurrent.CountDownLatch(1).await(300, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    // As JDBC fails a statement whose thread is interrupted: late, after the stop has moved on.
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("interrupted mid-write");
                }
                written.addAll(counts);
            }

            @Override
            public List<FeatureCount> between(LocalDate from, LocalDate to) {
                return List.of();
            }

            @Override
            public int deleteBefore(LocalDate day, int limit) {
                return 0;
            }
        };
        FeatureUse use = new FeatureUse(slow, mock(PlatformTransactionManager.class), LATE_EVENING,
                java.time.Duration.ofMillis(20));
        use.record(FeatureUse.CHAT, SALES);
        assertThat(writing.await(5, java.util.concurrent.TimeUnit.SECONDS)).as("a flush started").isTrue();

        use.stop();

        assertThat(written).containsExactly(new FeatureCount("chat", "SALES_STAFF", LocalDate.of(2026, 11, 27), 1));
    }

    private static CurrentUser user(Set<Role> roles) {
        return new CurrentUser(UUID.randomUUID(), "u", roles, Set.of(), DataScope.ALL, Set.of());
    }
}
