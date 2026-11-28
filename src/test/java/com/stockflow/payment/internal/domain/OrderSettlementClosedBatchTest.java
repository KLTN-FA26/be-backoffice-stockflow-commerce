package com.stockflow.payment.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit r3 LM-3 (15 §4.6 steps 18-19, §5.1; D30, D32): a closure of the rest carries what the installments before
 * delivery of the batches it closed whole still owe - their share of the fee lines - onto its fee item, due from the
 * closure's day, instead of leaving it on an installment whose milestone (the batch's release) never comes.
 *
 * <p>The contract: 1,020,000 in all - goods 900,000 in two batches of 450,000, fee lines 120,000 - paid before each
 * batch, the whole total shared by batch value: 510,000 before batch 1, 510,000 before batch 2. Batch 1 left and was
 * paid for; the closure closes batch 2's 450,000 of goods, so 60,000 of batch 2's installment is the fee lines'.</p>
 */
class OrderSettlementClosedBatchTest {

    private static final Instant AT = Instant.parse("2026-11-24T03:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID BATCH_1 = UUID.randomUUID();
    private static final UUID BATCH_2 = UUID.randomUUID();
    private static final UUID BATCH_3 = UUID.randomUUID();

    private final UUID contractId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    private Installment before(int seq, UUID batch, int batchSeq, long amount) {
        return Installment.open(contractId, UUID.randomUUID(), orderId, customerId, "HD-20261124-000001-1",
                "SO-20261124-000001", seq, "Before batch " + batchSeq, InstallmentMilestone.BEFORE_DELIVERY, batch,
                batchSeq, BigDecimal.valueOf(amount), "VND", null);
    }

    /** Batch 1's installment reached at its release and paid; batch 2's never reached. */
    private List<Installment> paidForBatchOne() {
        Installment first = before(1, BATCH_1, 1, 510_000);
        first.reach(AT.minusSeconds(864_000), DAY.minusDays(10));
        first.pay(BigDecimal.valueOf(510_000), AT.minusSeconds(860_000));
        return List.of(first, before(2, BATCH_2, 2, 510_000));
    }

    private static OrderSettlement.Outcome close(List<Installment> plan, long goods, OrderSettlement.Fee fee,
                                                 Set<UUID> delivering) {
        return OrderSettlement.closeRemainder(plan, UUID.randomUUID(), BigDecimal.valueOf(goods), fee, STAFF, AT, DAY,
                Set.of(BATCH_1), BigDecimal.ZERO, delivering);
    }

    @Test
    @DisplayName("LM-3: batch 2 closed before its release - its goods come off its installment, the 60,000 of fee lines "
            + "it still owed is carried onto a fee item due today; nothing refundable, the same money owed")
    void carriesTheFeeShareOntoTheFeeItem() {
        List<Installment> plan = paidForBatchOne();
        Installment batchTwo = plan.get(1);

        OrderSettlement.Outcome outcome = close(plan, 450_000, OrderSettlement.Fee.none(), Set.of());
        OrderSettlement s = outcome.settlement();

        assertThat(batchTwo.status()).as("no longer owed: nothing was paid on it").isEqualTo(InstallmentStatus.CANCELLED);
        assertThat(batchTwo.reducedAmount()).isEqualByComparingTo("510000");
        assertThat(outcome.feeItem()).isNotNull();
        assertThat(outcome.feeItem().amount()).isEqualByComparingTo("60000");
        assertThat(outcome.feeItem().milestone()).isEqualTo(InstallmentMilestone.CANCELLATION_FEE);
        assertThat(outcome.feeItem().status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(outcome.feeItem().dueDate()).as("due from the closure's day (15 §5.1)").isEqualTo(DAY);
        assertThat(s.carriedToFee()).isEqualByComparingTo("60000");
        assertThat(s.reduced()).isEqualByComparingTo("510000");
        assertThat(s.reductions()).as("one reduction per installment (uk_installment_reduction)").singleElement()
                .satisfies(r -> assertThat(r.amount()).isEqualByComparingTo("510000"));
        // ck_order_settlement_surplus / fee_split as the migration states them.
        assertThat(s.surplus()).isEqualByComparingTo(s.closed().subtract(s.reduced().subtract(s.carriedToFee())));
        assertThat(s.surplus()).isEqualByComparingTo("0");
        assertThat(s.refundable()).isEqualByComparingTo("0");
        assertThat(s.feeOwed()).isEqualByComparingTo("0");
        assertThat(s.feeItemId()).isEqualTo(outcome.feeItem().id());
        assertThat(outcome.changed()).contains(batchTwo);
    }

    @Test
    @DisplayName("LM-3: an agreed fee and the carried share are owed on one fee item; paid early in part, the "
            + "installment is PAID and only what it still owed is carried")
    void agreedFeeAndEarlyPayment() {
        List<Installment> plan = paidForBatchOne();
        Installment batchTwo = plan.get(1);
        batchTwo.pay(BigDecimal.valueOf(20_000), AT.minusSeconds(3_600));

        OrderSettlement.Outcome outcome = close(plan, 450_000, new OrderSettlement.Fee(null,
                BigDecimal.valueOf(100_000), "Cups printed for batch 2", "1,000 cups printed before the closure",
                com.stockflow.payment.api.FeeBearer.CUSTOMER),
                Set.of());
        OrderSettlement s = outcome.settlement();

        assertThat(batchTwo.status()).isEqualTo(InstallmentStatus.PAID);
        assertThat(batchTwo.outstanding()).isEqualByComparingTo("0");
        assertThat(s.carriedToFee()).isEqualByComparingTo("40000");
        assertThat(s.feeOwed()).as("the agreed fee: no surplus covered it").isEqualByComparingTo("100000");
        assertThat(outcome.feeItem().amount()).isEqualByComparingTo("140000");
    }

    @Test
    @DisplayName("LM-3: a batch whose labelled cartons the closure keeps still gates their handover - nothing carried; "
            + "a later batch closed whole is carried")
    void aBatchStillDeliveringKeepsItsInstallment() {
        Installment first = before(1, BATCH_1, 1, 340_000);
        first.reach(AT.minusSeconds(864_000), DAY.minusDays(10));
        first.pay(BigDecimal.valueOf(340_000), AT.minusSeconds(860_000));
        Installment second = before(2, BATCH_2, 2, 340_000);
        second.reach(AT.minusSeconds(3_600), DAY);
        Installment third = before(3, BATCH_3, 3, 340_000);

        OrderSettlement.Outcome outcome = close(List.of(first, second, third), 300_000, OrderSettlement.Fee.none(),
                Set.of(BATCH_2));

        // The latest batch closed first (15 §4.6 step 19): 300,000 of goods off batch 3, its 40,000 left carried.
        assertThat(third.status()).isEqualTo(InstallmentStatus.CANCELLED);
        assertThat(outcome.settlement().carriedToFee()).isEqualByComparingTo("40000");
        assertThat(outcome.feeItem().amount()).isEqualByComparingTo("40000");
        assertThat(second.outstanding()).as("still the gate of the labelled cartons (BR-08)")
                .isEqualByComparingTo("340000");
        assertThat(second.status()).isEqualTo(InstallmentStatus.DUE);
    }

    @Test
    @DisplayName("LM-3: not knowing which batches still deliver, nothing is carried - as before")
    void unknownCarriesNothing() {
        List<Installment> plan = paidForBatchOne();
        OrderSettlement.Outcome outcome = close(plan, 450_000, OrderSettlement.Fee.none(), null);

        assertThat(plan.get(1).outstanding()).isEqualByComparingTo("60000");
        assertThat(outcome.settlement().carriedToFee()).isEqualByComparingTo("0");
        assertThat(outcome.feeItem()).isNull();
    }

    @Test
    @DisplayName("LM-3: an installment after delivery of the batch closed is not carried - it falls due with the whole "
            + "order; nor is one tied to a batch that left")
    void onlyBeforeDeliveryOfAClosedBatch() {
        Installment first = before(1, BATCH_1, 1, 510_000);
        first.reach(AT.minusSeconds(864_000), DAY.minusDays(10));
        first.pay(BigDecimal.valueOf(400_000), AT.minusSeconds(860_000));
        Installment after = Installment.open(contractId, UUID.randomUUID(), orderId, customerId,
                "HD-20261124-000001-1", "SO-20261124-000001", 2, "After batch 2",
                InstallmentMilestone.AFTER_DELIVERY, BATCH_2, 2, BigDecimal.valueOf(510_000), "VND", 30);

        OrderSettlement.Outcome outcome = close(List.of(first, after), 450_000, OrderSettlement.Fee.none(), Set.of());

        assertThat(after.outstanding()).isEqualByComparingTo("60000");
        assertThat(first.outstanding()).as("batch 1 left: its gate is not a closed batch's").isEqualByComparingTo(
                "110000");
        assertThat(outcome.settlement().carriedToFee()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("ck_order_settlement_carried, stated in the aggregate: only a closure of the rest carries, within what "
            + "it took off")
    void onlyAClosureCarries() {
        OrderSettlement made = close(paidForBatchOne(), 450_000, OrderSettlement.Fee.none(), Set.of()).settlement();
        assertThatThrownBy(() -> new OrderSettlement(made.id(), made.orderId(), made.contractId(), made.customerId(),
                made.orderNumber(), made.contractNumber(), OrderSettlement.Kind.GOODS_RETURNED, made.sourceId(),
                made.currency(), made.collected(), made.closed(), made.reduced(), made.surplus(), made.fee(),
                made.feeTerms(), made.feeCovered(), made.refundable(), made.feeOwed(), made.feeItemId(),
                made.settledBy(), made.settledAt(), made.reductions(), null, BigDecimal.ZERO, made.carriedToFee()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderSettlement(made.id(), made.orderId(), made.contractId(), made.customerId(),
                made.orderNumber(), made.contractNumber(), made.kind(), made.sourceId(), made.currency(),
                made.collected(), made.closed(), made.reduced(), made.surplus(), made.fee(), made.feeTerms(),
                made.feeCovered(), made.refundable(), made.feeOwed(), made.feeItemId(), made.settledBy(),
                made.settledAt(), made.reductions(), null, BigDecimal.ZERO, made.reduced().add(BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
