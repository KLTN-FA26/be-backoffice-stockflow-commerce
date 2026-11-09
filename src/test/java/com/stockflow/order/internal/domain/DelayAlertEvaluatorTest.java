package com.stockflow.order.internal.domain;

import com.stockflow.order.internal.domain.DelayAlertEvaluator.Batch;
import com.stockflow.order.internal.domain.DelayAlertEvaluator.Condition;
import com.stockflow.order.internal.domain.DelayAlertEvaluator.Facts;
import com.stockflow.order.internal.domain.DelayAlertEvaluator.Production;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4A-7 (kltn-docs 17 §4.12 steps 48-49, BR-30, B44): each delay alert opens when its condition starts to hold, stays
 * open while it holds and closes once it does not - decided without a database.
 */
class DelayAlertEvaluatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final Instant NOW = Instant.parse("2026-11-20T01:00:00Z");
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID PRODUCTION = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID LINE_A = UUID.randomUUID();
    private static final UUID LINE_B = UUID.randomUUID();

    private static Facts production(ProductionOrderStatus status, LocalDate needBy, LocalDate expected) {
        return new Facts(List.of(new Production(PRODUCTION, status, needBy, expected)), List.of(), true, true, 14);
    }

    private static Facts batch(int uncovered, boolean handedOver, LocalDate start, LocalDate end,
                               boolean shortageFollowed, boolean deliveryFollowed, int leadDays) {
        return new Facts(List.of(), List.of(new Batch(BATCH, start, end, uncovered, handedOver)), shortageFollowed,
                deliveryFollowed, leadDays);
    }

    private static Set<Condition> holding(Facts facts) {
        return DelayAlertEvaluator.conditions(facts, TODAY);
    }

    private static Set<Condition> holding(Facts facts, LocalDate day) {
        return DelayAlertEvaluator.conditions(facts, day);
    }

    private static Condition condition(DelayAlertType type) {
        return new Condition(type, type.subject() == DelayAlertType.Subject.PRODUCTION_ORDER ? PRODUCTION : BATCH);
    }

    private static DelayAlert openAlert(DelayAlertType type) {
        return DelayAlert.open(UUID.randomUUID(), condition(type), ORDER, NOW.minusSeconds(86_400));
    }

    /** One alert's life: absent → opened; open + still holding → nothing; open + no longer → closed. */
    private static void opensStaysCloses(DelayAlertType type, Set<Condition> before, Set<Condition> after) {
        assertThat(before).as("the condition holds").contains(condition(type));
        DelayAlertEvaluator.Changes first = DelayAlertEvaluator.reconcile(List.of(), before);
        assertThat(first.toOpen()).contains(condition(type));
        assertThat(first.toClose()).isEmpty();

        DelayAlert open = openAlert(type);
        DelayAlertEvaluator.Changes again = DelayAlertEvaluator.reconcile(List.of(open), before);
        assertThat(again.toOpen()).as("open already: not opened twice").doesNotContain(condition(type));
        assertThat(again.toClose()).as("still holds: stays open").doesNotContain(open);

        assertThat(after).as("the condition cleared").doesNotContain(condition(type));
        DelayAlertEvaluator.Changes cleared = DelayAlertEvaluator.reconcile(List.of(open), after);
        assertThat(cleared.toClose()).containsExactly(open);
        assertThat(cleared.toOpen()).doesNotContain(condition(type));
    }

    @Nested
    @DisplayName("AT_RISK: accepted onward and expected done after the need-by day")
    class AtRisk {

        @Test
        @DisplayName("opens, stays open, closes when the expected day is moved to the need-by day")
        void lifecycle() {
            LocalDate needBy = TODAY.plusDays(10);
            opensStaysCloses(DelayAlertType.AT_RISK,
                    holding(production(ProductionOrderStatus.ACCEPTED, needBy, needBy.plusDays(1))),
                    holding(production(ProductionOrderStatus.ACCEPTED, needBy, needBy)));
        }

        @ParameterizedTest
        @EnumSource(value = ProductionOrderStatus.class,
                names = {"ACCEPTED", "SAMPLE_PENDING", "IN_PRODUCTION", "PARTIALLY_RECEIVED"})
        @DisplayName("holds from ACCEPTED while it is still to bring goods")
        void holdsWhileMaking(ProductionOrderStatus status) {
            LocalDate needBy = TODAY.plusDays(10);
            assertThat(holding(production(status, needBy, needBy.plusDays(3))))
                    .contains(condition(DelayAlertType.AT_RISK));
        }

        @ParameterizedTest
        @EnumSource(value = ProductionOrderStatus.class,
                names = {"DRAFT", "PENDING_APPROVAL", "SENT", "RECEIVED", "CANCELLED", "CLOSED"})
        @DisplayName("not before the producer accepted, nor once received, cancelled or closed")
        void notOutsideIt(ProductionOrderStatus status) {
            LocalDate needBy = TODAY.plusDays(10);
            assertThat(holding(production(status, needBy, needBy.plusDays(3))))
                    .doesNotContain(condition(DelayAlertType.AT_RISK));
        }

        @Test
        @DisplayName("no expected day, no risk to compare")
        void noExpectedDay() {
            assertThat(holding(production(ProductionOrderStatus.IN_PRODUCTION, TODAY.plusDays(3), null)))
                    .doesNotContain(condition(DelayAlertType.AT_RISK));
        }

        @Test
        @DisplayName("closes when the production order is received in full")
        void closesOnReceipt() {
            LocalDate needBy = TODAY.plusDays(5);
            opensStaysCloses(DelayAlertType.AT_RISK,
                    holding(production(ProductionOrderStatus.PARTIALLY_RECEIVED, needBy, needBy.plusDays(2))),
                    holding(production(ProductionOrderStatus.RECEIVED, needBy, needBy.plusDays(2))));
        }
    }

    @Nested
    @DisplayName("PRODUCTION_LATE: past the need-by day, sent and not received in full")
    class ProductionLate {

        @Test
        @DisplayName("opens the day after the need-by day, stays open, closes when received")
        void lifecycle() {
            LocalDate needBy = TODAY.minusDays(1);
            assertThat(holding(production(ProductionOrderStatus.SENT, TODAY, null)))
                    .as("on the need-by day itself it is not late yet")
                    .doesNotContain(condition(DelayAlertType.PRODUCTION_LATE));
            opensStaysCloses(DelayAlertType.PRODUCTION_LATE,
                    holding(production(ProductionOrderStatus.IN_PRODUCTION, needBy, null)),
                    holding(production(ProductionOrderStatus.RECEIVED, needBy, null)));
        }

        @ParameterizedTest
        @EnumSource(value = ProductionOrderStatus.class,
                names = {"SENT", "ACCEPTED", "SAMPLE_PENDING", "IN_PRODUCTION", "PARTIALLY_RECEIVED"})
        @DisplayName("holds from SENT to PARTIALLY_RECEIVED")
        void holdsWhileWithTheProducer(ProductionOrderStatus status) {
            assertThat(holding(production(status, TODAY.minusDays(3), null)))
                    .contains(condition(DelayAlertType.PRODUCTION_LATE));
        }

        @ParameterizedTest
        @EnumSource(value = ProductionOrderStatus.class,
                names = {"DRAFT", "PENDING_APPROVAL", "RECEIVED", "CANCELLED", "CLOSED"})
        @DisplayName("never for one not with a producer, or no longer bringing anything")
        void notOutsideIt(ProductionOrderStatus status) {
            assertThat(holding(production(status, TODAY.minusDays(3), null)))
                    .doesNotContain(condition(DelayAlertType.PRODUCTION_LATE));
        }

        @Test
        @DisplayName("closes when cancelled or closed short")
        void closesWhenStopped() {
            LocalDate needBy = TODAY.minusDays(2);
            opensStaysCloses(DelayAlertType.PRODUCTION_LATE,
                    holding(production(ProductionOrderStatus.PARTIALLY_RECEIVED, needBy, null)),
                    holding(production(ProductionOrderStatus.CLOSED, needBy, null)));
        }

        @Test
        @DisplayName("independent of AT_RISK: a late order expected later shows both")
        void bothAtOnce() {
            LocalDate needBy = TODAY.minusDays(1);
            assertThat(holding(production(ProductionOrderStatus.IN_PRODUCTION, needBy, TODAY.plusDays(4))))
                    .containsExactlyInAnyOrder(condition(DelayAlertType.AT_RISK),
                            condition(DelayAlertType.PRODUCTION_LATE));
        }
    }

    @Nested
    @DisplayName("PO_NEEDED: short and uncovered, the window starting within the lead time")
    class PoNeeded {

        @Test
        @DisplayName("opens when the window comes within the lead time, stays open, closes once covered")
        void lifecycle() {
            LocalDate start = TODAY.plusDays(14);
            assertThat(holding(batch(5, false, start.plusDays(1), start.plusDays(3), true, true, 14)))
                    .as("15 days out with a 14-day lead time: not yet")
                    .doesNotContain(condition(DelayAlertType.PO_NEEDED));
            opensStaysCloses(DelayAlertType.PO_NEEDED,
                    holding(batch(5, false, start, start.plusDays(2), true, true, 14)),
                    holding(batch(0, false, start, start.plusDays(2), true, true, 14)));
        }

        @Test
        @DisplayName("counts with the lead time given (B44: per producer, 14 by default)")
        void leadTime() {
            LocalDate start = TODAY.plusDays(20);
            assertThat(holding(batch(1, false, start, start, true, true, 14)))
                    .doesNotContain(condition(DelayAlertType.PO_NEEDED));
            assertThat(holding(batch(1, false, start, start, true, true, 21)))
                    .contains(condition(DelayAlertType.PO_NEEDED));
        }

        @Test
        @DisplayName("still holds once the window started, while the batch is short")
        void windowStarted() {
            assertThat(holding(batch(2, false, TODAY.minusDays(1), TODAY.plusDays(1), true, true, 14)))
                    .contains(condition(DelayAlertType.PO_NEEDED));
        }

        @Test
        @DisplayName("closes when the window is moved beyond the lead time")
        void closesWhenWindowMoved() {
            opensStaysCloses(DelayAlertType.PO_NEEDED,
                    holding(batch(3, false, TODAY.plusDays(7), TODAY.plusDays(9), true, true, 14)),
                    holding(batch(3, false, TODAY.plusDays(30), TODAY.plusDays(32), true, true, 14)));
        }

        @Test
        @DisplayName("closes when the order's shortage is no longer followed (cancelled, all shipped)")
        void closesWhenNoLongerFollowed() {
            opensStaysCloses(DelayAlertType.PO_NEEDED,
                    holding(batch(3, false, TODAY.plusDays(7), TODAY.plusDays(9), true, true, 14)),
                    holding(batch(3, false, TODAY.plusDays(7), TODAY.plusDays(9), false, true, 14)));
        }

        @Test
        @DisplayName("a batch handed over needs nothing made, whatever its count says")
        void notForABatchHandedOver() {
            assertThat(holding(batch(3, true, TODAY.plusDays(7), TODAY.plusDays(9), true, true, 14)))
                    .doesNotContain(condition(DelayAlertType.PO_NEEDED));
        }

        @Test
        @DisplayName("a batch with no window yet has no day to count to")
        void noWindow() {
            assertThat(holding(batch(3, false, null, null, true, true, 14))).isEmpty();
        }
    }

    @Nested
    @DisplayName("DELIVERY_LATE: past the window's end and not all handed over")
    class DeliveryLate {

        @Test
        @DisplayName("opens the day after the window ends, stays open, closes when handed over")
        void lifecycle() {
            assertThat(holding(batch(0, false, TODAY.minusDays(2), TODAY, true, true, 14)))
                    .as("on the window's last day it is not late yet")
                    .doesNotContain(condition(DelayAlertType.DELIVERY_LATE));
            LocalDate end = TODAY.minusDays(1);
            opensStaysCloses(DelayAlertType.DELIVERY_LATE,
                    holding(batch(0, false, end.minusDays(2), end, false, true, 14)),
                    holding(batch(0, true, end.minusDays(2), end, false, true, 14)));
        }

        @Test
        @DisplayName("closes when the window is moved")
        void closesWhenMoved() {
            opensStaysCloses(DelayAlertType.DELIVERY_LATE,
                    holding(batch(0, false, TODAY.minusDays(5), TODAY.minusDays(3), false, true, 14)),
                    holding(batch(0, false, TODAY.plusDays(1), TODAY.plusDays(3), false, true, 14)));
        }

        @Test
        @DisplayName("closes once the order no longer owes a delivery (shipped, cancelled)")
        void closesWhenNotFollowed() {
            opensStaysCloses(DelayAlertType.DELIVERY_LATE,
                    holding(batch(0, false, TODAY.minusDays(5), TODAY.minusDays(3), false, true, 14)),
                    holding(batch(0, false, TODAY.minusDays(5), TODAY.minusDays(3), false, false, 14)));
        }

        @Test
        @DisplayName("a late batch that also lacks production shows both")
        void withPoNeeded() {
            assertThat(holding(batch(4, false, TODAY.minusDays(5), TODAY.minusDays(3), true, true, 14)))
                    .containsExactlyInAnyOrder(condition(DelayAlertType.PO_NEEDED),
                            condition(DelayAlertType.DELIVERY_LATE));
        }
    }

    @Nested
    @DisplayName("reconcile")
    class Reconcile {

        @Test
        @DisplayName("closed alerts are ignored; a duplicate open alert is closed")
        void closedIgnoredDuplicateClosed() {
            DelayAlert open = openAlert(DelayAlertType.DELIVERY_LATE);
            DelayAlert duplicate = openAlert(DelayAlertType.DELIVERY_LATE);
            DelayAlert closed = new DelayAlert(UUID.randomUUID(), DelayAlertType.PO_NEEDED, null, BATCH, ORDER,
                    NOW.minusSeconds(100), NOW);
            DelayAlertEvaluator.Changes changes = DelayAlertEvaluator.reconcile(List.of(open, duplicate, closed),
                    Set.of(condition(DelayAlertType.DELIVERY_LATE)));
            assertThat(changes.toOpen()).isEmpty();
            assertThat(changes.toClose()).containsExactly(duplicate);
        }

        @Test
        @DisplayName("nothing holds and nothing open: no change")
        void none() {
            assertThat(DelayAlertEvaluator.reconcile(List.of(), Set.of()).none()).isTrue();
        }
    }

    @Nested
    @DisplayName("handed over: what left counts for the earliest batch first")
    class HandedOver {

        private final UUID first = UUID.randomUUID();
        private final UUID second = UUID.randomUUID();
        private final List<DeliveryBatch> schedule = List.of(
                new DeliveryBatch(second, 2, TODAY, TODAY, List.of(new DeliveryBatch.Line(UUID.randomUUID(), LINE_A, 8))),
                new DeliveryBatch(first, 1, TODAY, TODAY, List.of(new DeliveryBatch.Line(UUID.randomUUID(), LINE_A, 12),
                        new DeliveryBatch.Line(UUID.randomUUID(), LINE_B, 5))));

        @Test
        void nothingLeft() {
            assertThat(DelayAlertEvaluator.handedOver(schedule, Map.of())).isEmpty();
        }

        @Test
        void firstBatchNeedsEveryLine() {
            assertThat(DelayAlertEvaluator.handedOver(schedule, Map.of(LINE_A, 12, LINE_B, 4))).isEmpty();
            assertThat(DelayAlertEvaluator.handedOver(schedule, Map.of(LINE_A, 12, LINE_B, 5))).containsExactly(first);
        }

        @Test
        void secondCountsWhatTheFirstTook() {
            assertThat(DelayAlertEvaluator.handedOver(schedule, Map.of(LINE_A, 19, LINE_B, 5))).containsExactly(first);
            assertThat(DelayAlertEvaluator.handedOver(schedule, Map.of(LINE_A, 20, LINE_B, 5)))
                    .containsExactlyInAnyOrder(first, second);
        }
    }

    @Test
    @DisplayName("an alert is about exactly the kind of subject its type names, and closed after it opened")
    void alertShape() {
        assertThatThrownBy(() -> new DelayAlert(UUID.randomUUID(), DelayAlertType.AT_RISK, null, BATCH, ORDER, NOW,
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DelayAlert(UUID.randomUUID(), DelayAlertType.PO_NEEDED, PRODUCTION, BATCH, ORDER,
                NOW, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DelayAlert(UUID.randomUUID(), DelayAlertType.DELIVERY_LATE, null, BATCH, ORDER,
                NOW, NOW.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(openAlert(DelayAlertType.PRODUCTION_LATE).subjectId()).isEqualTo(PRODUCTION);
        assertThat(openAlert(DelayAlertType.PO_NEEDED).subjectId()).isEqualTo(BATCH);
        assertThat(DelayAlertType.DELIVERY_LATE.aboutDelivery()).isTrue();
        assertThat(DelayAlertType.PO_NEEDED.aboutDelivery()).isFalse();
    }
}
