package com.stockflow.order.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4A-4 (kltn-docs 17 §4.3 steps 8-10, §5.3, BR-24; 13 BR-13; 14 BR-18): the producer's acceptance, the start of mass
 * printing, and the print sample rounds - decided by the aggregates, without a database.
 */
class SampleRoundsTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);
    private static final LocalDate DONE_BY = LocalDate.of(2026, 11, 30);
    private static final UUID COORDINATOR = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID CUSTOMER_USER = UUID.randomUUID();

    /** SENT: drawn up, submitted and approved by someone else. A first-time SKU wants a sample unless waived. */
    private static ProductionOrder sent(boolean sample) {
        ProductionOrder order = ProductionOrder.drawUp("PRD-20261109-0007", UUID.randomUUID(), UUID.randomUUID(),
                CUSTOMER, UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 12, 1), true,
                List.of(new ProductionOrder.Line(UUID.randomUUID(), UUID.randomUUID(), "CUP-12OZ-ACME", 8, 50,
                        UUID.randomUUID(), "a".repeat(64), null, 0)), "VND", COORDINATOR, NOW);
        if (!sample) {
            order.edit(new ProductionOrder.Draft(order.producerId(), order.needByDate(), null, null, false,
                    "re-run of a tested design", null), java.util.Map.of());
        }
        order.submit(COORDINATOR, NOW);
        order.approve(MANAGER, NOW);
        return order;
    }

    private static ProductionOrder accepted(boolean sample) {
        ProductionOrder order = sent(sample);
        order.accept(DONE_BY, COORDINATOR, NOW);
        return order;
    }

    private static List<SampleRound.Media> photos(int count) {
        return IntStream.range(0, count).mapToObj(i -> new SampleRound.Media(UUID.randomUUID(),
                "print-samples/2026/11/09/" + UUID.randomUUID() + ".jpg", "sample-" + i + ".jpg", "image/jpeg", 1_000,
                COORDINATOR, NOW)).toList();
    }

    private static ErrorCode code(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("17 §4.3 step 8: accepted with the producer's day, which moves while goods are to come")
    void acceptedWithItsDay() {
        ProductionOrder order = sent(true);
        assertThatThrownBy(() -> order.accept(null, COORDINATOR, NOW)).satisfies(e ->
                assertThat(code(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> order.expectCompletion(DONE_BY)).as("not before it is accepted").satisfies(e ->
                assertThat(code(e)).isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID));
        order.accept(DONE_BY, COORDINATOR, NOW);
        assertThat(order.status()).isEqualTo(ProductionOrderStatus.ACCEPTED);
        assertThat(order.expectedCompletionDate()).isEqualTo(DONE_BY);
        assertThat(order.pullChanges()).extracting(ProductionOrder.Change::to).endsWith(ProductionOrderStatus.ACCEPTED);
        assertThat(order.expectCompletion(DONE_BY)).as("the same day changes nothing").isFalse();
        assertThat(order.expectCompletion(DONE_BY.plusDays(3))).isTrue();
        assertThat(order.expectedCompletionDate()).isEqualTo(DONE_BY.plusDays(3));
        assertThatThrownBy(() -> order.accept(DONE_BY, COORDINATOR, NOW)).as("accepted once").satisfies(e ->
                assertThat(code(e)).isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID));
    }

    @Test
    @DisplayName("17 §4.3 step 10: the coordinator starts mass printing only with no sample to approve (BR-24)")
    void startedByHandOnlyWithoutASample() {
        ProductionOrder withSample = accepted(true);
        assertThatThrownBy(() -> withSample.startProduction(null, COORDINATOR, NOW)).satisfies(e ->
                assertThat(code(e)).isEqualTo(ErrorCode.PRODUCTION_ORDER_SAMPLE_REQUIRED));
        assertThat(withSample.status()).isEqualTo(ProductionOrderStatus.ACCEPTED);

        ProductionOrder sentOnly = sent(false);
        assertThatThrownBy(() -> sentOnly.startProduction(null, COORDINATOR, NOW)).as("from ACCEPTED, by hand")
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID));

        ProductionOrder order = accepted(false);
        order.startProduction(DONE_BY.plusDays(2), COORDINATOR, NOW);
        assertThat(order.status()).isEqualTo(ProductionOrderStatus.IN_PRODUCTION);
        assertThat(order.productionStartedAt()).isEqualTo(NOW);
        assertThat(order.expectedCompletionDate()).isEqualTo(DONE_BY.plusDays(2));
        assertThat(order.receivable()).isTrue();
    }

    @Test
    @DisplayName("17 §4.3 step 9: round 1 from ACCEPTED makes it SAMPLE_PENDING; receipts wait for the approval")
    void roundOneMakesItPending() {
        ProductionOrder order = accepted(true);
        SampleRounds rounds = new SampleRounds(order.id(), List.of());
        assertThatThrownBy(() -> rounds.open(order, null, null, List.of(), COORDINATOR, NOW, TODAY))
                .as("a round shows at least one photo or video").satisfies(e ->
                        assertThat(code(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> rounds.open(order, null, null, photos(11), COORDINATOR, NOW, TODAY))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> rounds.open(order, ReprintReason.PRINT_DEFECT, null, photos(1), COORDINATOR, NOW,
                TODAY)).as("round 1 is no reprint").satisfies(e ->
                assertThat(code(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> rounds.open(order, null, TODAY.plusDays(1), photos(1), COORDINATOR, NOW, TODAY))
                .as("a physical sample is recorded once sent").satisfies(e ->
                        assertThat(code(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(order.status()).as("nothing moved on a refusal").isEqualTo(ProductionOrderStatus.ACCEPTED);
        order.pullChanges();

        SampleRound first = rounds.open(order, null, TODAY, photos(2), COORDINATOR, NOW, TODAY);
        assertThat(first.roundNo()).isEqualTo(1);
        assertThat(first.awaitingAnswer()).isTrue();
        assertThat(first.physicalSampleSentOn()).isEqualTo(TODAY);
        assertThat(order.status()).isEqualTo(ProductionOrderStatus.SAMPLE_PENDING);
        assertThat(order.pullChanges()).singleElement().satisfies(change -> {
            assertThat(change.from()).isEqualTo(ProductionOrderStatus.ACCEPTED);
            assertThat(change.to()).isEqualTo(ProductionOrderStatus.SAMPLE_PENDING);
        });
        assertThat(order.receivable()).as("03 BR-15: nothing comes in while the sample waits").isFalse();
        assertThat(rounds.waiting()).contains(first);
        assertThatThrownBy(() -> rounds.open(order, ReprintReason.CUSTOMER_CHANGE, null, photos(1), COORDINATOR, NOW,
                TODAY)).as("one round waits at a time").satisfies(e ->
                assertThat(code(e)).isEqualTo(ErrorCode.SAMPLE_ROUND_NOT_OPENABLE));
    }

    @Test
    @DisplayName("BR-24: no rounds without a sample to approve, and round 1 not before the producer accepted")
    void roundsOnlyWhereTheyApply() {
        ProductionOrder waived = accepted(false);
        assertThatThrownBy(() -> new SampleRounds(waived.id(), List.of()).open(waived, null, null, photos(1),
                COORDINATOR, NOW, TODAY)).satisfies(e ->
                assertThat(code(e)).isEqualTo(ErrorCode.PRODUCTION_ORDER_SAMPLE_NOT_REQUIRED));
        ProductionOrder sent = sent(true);
        assertThatThrownBy(() -> new SampleRounds(sent.id(), List.of()).open(sent, null, null, photos(1), COORDINATOR,
                NOW, TODAY)).satisfies(e ->
                assertThat(code(e)).isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID));
    }

    @Test
    @DisplayName("13 BR-13: only the owner answers, once; a reprint request says what to change")
    void answeredOnceByItsOwner() {
        ProductionOrder order = accepted(true);
        SampleRounds rounds = new SampleRounds(order.id(), List.of());
        SampleRound first = rounds.open(order, null, null, photos(1), COORDINATOR, NOW, TODAY);

        assertThatThrownBy(() -> rounds.answer(order, UUID.randomUUID(), first.id(), SampleRound.Result.APPROVED, null,
                UUID.randomUUID(), NOW)).as("another customer: no such round").satisfies(e ->
                assertThat(code(e)).isEqualTo(ErrorCode.SAMPLE_ROUND_NOT_FOUND));
        assertThatThrownBy(() -> rounds.answer(order, CUSTOMER, UUID.randomUUID(), SampleRound.Result.APPROVED, null,
                CUSTOMER_USER, NOW)).satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SAMPLE_ROUND_NOT_FOUND));
        assertThatThrownBy(() -> rounds.answer(order, CUSTOMER, first.id(), SampleRound.Result.REPRINT_REQUESTED, "  ",
                CUSTOMER_USER, NOW)).satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(first.awaitingAnswer()).isTrue();

        rounds.answer(order, CUSTOMER, first.id(), SampleRound.Result.REPRINT_REQUESTED, " logo 2 mm higher ",
                CUSTOMER_USER, NOW);
        assertThat(first.result()).isEqualTo(SampleRound.Result.REPRINT_REQUESTED);
        assertThat(first.customerNote()).isEqualTo("logo 2 mm higher");
        assertThat(first.answeredBy()).isEqualTo(CUSTOMER_USER);
        assertThat(order.status()).as("a reprint keeps it pending").isEqualTo(ProductionOrderStatus.SAMPLE_PENDING);
        assertThatThrownBy(() -> rounds.answer(order, CUSTOMER, first.id(), SampleRound.Result.APPROVED, null,
                CUSTOMER_USER, NOW)).as("answered once, never changed").satisfies(e ->
                assertThat(code(e)).isEqualTo(ErrorCode.SAMPLE_ROUND_ANSWERED));
        assertThatThrownBy(() -> rounds.physicalSampleSent(order, first.id(), TODAY, TODAY))
                .as("an answered round stays as the customer saw it").satisfies(e ->
                        assertThat(code(e)).isEqualTo(ErrorCode.SAMPLE_ROUND_ANSWERED));

        assertThatThrownBy(() -> rounds.open(order, null, null, photos(1), COORDINATOR, NOW, TODAY))
                .as("a reprint says why").satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        SampleRound second = rounds.open(order, ReprintReason.PRINT_DEFECT, null, photos(1), COORDINATOR, NOW, TODAY);
        assertThat(second.roundNo()).isEqualTo(2);
        rounds.physicalSampleSent(order, second.id(), TODAY, TODAY);
        assertThat(second.physicalSampleSentOn()).isEqualTo(TODAY);
        rounds.addMedia(order, second.id(), photos(2));
        assertThat(second.media()).hasSize(3);
        order.pullChanges();

        rounds.answer(order, CUSTOMER, second.id(), SampleRound.Result.APPROVED, "ignored", CUSTOMER_USER, NOW);
        assertThat(second.customerNote()).as("an approval keeps no note").isNull();
        assertThat(order.status()).isEqualTo(ProductionOrderStatus.IN_PRODUCTION);
        assertThat(order.productionStartedAt()).isEqualTo(NOW);
        assertThat(order.pullChanges()).singleElement().satisfies(change -> {
            assertThat(change.to()).isEqualTo(ProductionOrderStatus.IN_PRODUCTION);
            assertThat(change.by()).isEqualTo(CUSTOMER_USER);
            assertThat(change.reason()).contains("round 2");
        });
        assertThat(order.receivable()).as("approved, goods are received against it").isTrue();
        assertThat(rounds.waiting()).isEmpty();
        assertThatThrownBy(() -> rounds.open(order, ReprintReason.CUSTOMER_CHANGE, null, photos(1), COORDINATOR, NOW,
                TODAY)).satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID));
    }

    @Test
    @DisplayName("Cancelled while the sample waits: the round is no longer answered")
    void cancelledWhilePending() {
        ProductionOrder order = accepted(true);
        SampleRounds rounds = new SampleRounds(order.id(), List.of());
        SampleRound first = rounds.open(order, null, null, photos(1), COORDINATOR, NOW, TODAY);
        order.cancel("the customer revised the design", COORDINATOR, NOW);
        assertThatThrownBy(() -> rounds.answer(order, CUSTOMER, first.id(), SampleRound.Result.APPROVED, null,
                CUSTOMER_USER, NOW)).satisfies(e ->
                assertThat(code(e)).isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID));
        assertThat(first.awaitingAnswer()).isTrue();
    }

    @Test
    @DisplayName("14 BR-18, 17 §4.3 step 9: round 1 and CUSTOMER_CHANGE count, PRINT_DEFECT does not; past the free "
            + "rounds warns")
    void freeRoundsCountWhatTheCustomerAskedFor() {
        ProductionOrder order = accepted(true);
        SampleRounds rounds = new SampleRounds(order.id(), List.of());
        SampleRound round = rounds.open(order, null, null, photos(1), COORDINATOR, NOW, TODAY);
        assertThat(rounds.allowance(2)).isEqualTo(new SampleRounds.Allowance(2, 1));
        for (ReprintReason reason : List.of(ReprintReason.PRINT_DEFECT, ReprintReason.CUSTOMER_CHANGE,
                ReprintReason.PRINT_DEFECT, ReprintReason.CUSTOMER_CHANGE)) {
            rounds.answer(order, CUSTOMER, round.id(), SampleRound.Result.REPRINT_REQUESTED, "again", CUSTOMER_USER,
                    NOW);
            round = rounds.open(order, reason, null, photos(1), COORDINATOR, NOW, TODAY);
        }
        assertThat(rounds.rounds()).extracting(SampleRound::counted).containsExactly(true, false, true, false, true);
        SampleRounds.Allowance allowance = rounds.allowance(2);
        assertThat(allowance.counted()).isEqualTo(3);
        assertThat(allowance.exceeded()).as("3 counted, 2 free: a warning").isTrue();
        assertThat(rounds.allowance(3).exceeded()).isFalse();
        assertThat(rounds.allowance(null).exceeded()).as("no contract to say: no warning").isFalse();
        assertThat(rounds.rounds()).extracting(SampleRound::roundNo).containsExactly(1, 2, 3, 4, 5);
    }
}
