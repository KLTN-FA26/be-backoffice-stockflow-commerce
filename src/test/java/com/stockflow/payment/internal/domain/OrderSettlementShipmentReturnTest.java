package com.stockflow.payment.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WF-05 BE-03 (decision Q-2, audit M-2): the units of a shipment the carrier brought back are settled as a customer's
 * return is - the shipment's own batch first, the older debt next, then refunded up to what was collected, the batches
 * still to come last; no fee. Before, they were settled as a closure of the rest, which takes the latest batch first.
 *
 * <p>The contract of every scenario, 2,100,000 with VAT: 300,000 on signing (paid), and for each of three delivery
 * batches 300,000 before delivery - the gate of its handover - and 300,000 after delivery. Batch 1 was paid and handed
 * over, and the carrier brought it back: its goods are worth 700,000. Batches 2 and 3 are still to come.</p>
 */
class OrderSettlementShipmentReturnTest {

    private static final Instant AT = Instant.parse("2026-11-20T03:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);
    private static final UUID STAFF = UUID.randomUUID();

    private final UUID contractId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID batch1 = UUID.randomUUID();
    private final UUID batch2 = UUID.randomUUID();
    private final UUID batch3 = UUID.randomUUID();
    private final UUID shipment = UUID.randomUUID();
    private final UUID closure = UUID.randomUUID();

    private Installment installment(int seq, InstallmentMilestone milestone, UUID batch, Integer batchSeq,
                                    Integer dueDays) {
        return Installment.open(contractId, UUID.randomUUID(), orderId, customerId, "HD-20261120-000001-1",
                "SO-20261120-000001", seq, "Installment " + seq, milestone, batch, batchSeq, dong(300_000), "VND",
                dueDays);
    }

    /** The plan above: on signing and batch 1's before delivery paid; the rest not. */
    private List<Installment> plan() {
        Installment onSigning = installment(1, InstallmentMilestone.ON_SIGNING, null, null, 0);
        onSigning.reach(AT.minusSeconds(864_000), DAY.minusDays(10));
        onSigning.pay(dong(300_000), AT.minusSeconds(864_000));
        Installment before1 = installment(2, InstallmentMilestone.BEFORE_DELIVERY, batch1, 1, null);
        before1.reach(AT.minusSeconds(432_000), DAY.minusDays(5));
        before1.pay(dong(300_000), AT.minusSeconds(432_000));
        return List.of(onSigning, before1,
                installment(3, InstallmentMilestone.AFTER_DELIVERY, batch1, 1, 30),
                installment(4, InstallmentMilestone.BEFORE_DELIVERY, batch2, 2, null),
                installment(5, InstallmentMilestone.AFTER_DELIVERY, batch2, 2, 30),
                installment(6, InstallmentMilestone.BEFORE_DELIVERY, batch3, 3, null),
                installment(7, InstallmentMilestone.AFTER_DELIVERY, batch3, 3, 30));
    }

    private static BigDecimal dong(long amount) {
        return BigDecimal.valueOf(amount);
    }

    private static Map<Integer, Installment> bySeq(List<Installment> plan) {
        return plan.stream().collect(Collectors.toMap(Installment::seq, Function.identity()));
    }

    private static BigDecimal sum(List<Installment> plan, Function<Installment, BigDecimal> amount) {
        return plan.stream().map(amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Money is conserved: what the customer paid less what is to be refunded is what they keep (the contract less the
     * goods closed) less what they still owe. No dong appears or disappears.
     */
    private static void conserved(List<Installment> plan, OrderSettlement s) {
        BigDecimal contract = sum(plan, Installment::amount);
        BigDecimal paid = sum(plan, Installment::paidAmount);
        BigDecimal outstanding = sum(plan, Installment::outstanding);
        assertThat(paid.subtract(s.refundable()))
                .as("paid - refunded = kept - still owed")
                .isEqualByComparingTo(contract.subtract(s.closed()).subtract(outstanding));
        assertThat(s.surplus()).isEqualByComparingTo(s.closed().subtract(s.reduced()));
        assertThat(s.refundable()).isEqualByComparingTo(s.surplus());
        assertThat(s.fee()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("M-2: batch 1 brought back - its own installment first, then refunded; batches 2 and 3 untouched")
    void ownBatchFirstThenRefund() {
        List<Installment> plan = plan();
        OrderSettlement.Outcome outcome = OrderSettlement.shipmentReturned(plan, closure, shipment, batch1,
                dong(700_000), BigDecimal.ZERO, Set.of(), BigDecimal.ZERO, STAFF, AT, DAY);
        OrderSettlement s = outcome.settlement();
        Map<Integer, Installment> i = bySeq(plan);

        assertThat(s.kind()).isEqualTo(OrderSettlement.Kind.SHIPMENT_RETURNED);
        assertThat(s.sourceId()).isEqualTo(closure);
        assertThat(s.shipmentId()).isEqualTo(shipment);
        assertThat(s.closed()).isEqualByComparingTo(dong(700_000));
        assertThat(s.collected()).isEqualByComparingTo(dong(600_000));
        // Batch 1's own debt - its installment after delivery - takes 300,000; nothing else is owed for goods the
        // customer has, so the other 400,000 was paid and is refunded.
        assertThat(s.reductions()).singleElement().satisfies(r -> {
            assertThat(r.installmentId()).isEqualTo(i.get(3).id());
            assertThat(r.amount()).isEqualByComparingTo(dong(300_000));
        });
        assertThat(i.get(3).status()).isEqualTo(InstallmentStatus.CANCELLED);
        assertThat(s.refundable()).isEqualByComparingTo(dong(400_000));
        for (int seq = 4; seq <= 7; seq++) {
            assertThat(i.get(seq).outstanding()).as("installment %d of a batch still to come", seq)
                    .isEqualByComparingTo(dong(300_000));
        }
        assertThat(outcome.feeItem()).isNull();
        conserved(plan, s);
    }

    @Test
    @DisplayName("the old closure's order lowered batch 3 first - its handover gate included; the return's does not")
    void notTheClosureOrder() {
        List<Installment> asClosure = plan();
        OrderSettlement.closeRemainder(asClosure, closure, dong(700_000), OrderSettlement.Fee.none(), STAFF, AT, DAY,
                Set.of());
        assertThat(bySeq(asClosure).get(6).reducedAmount()).as("what BE-03 was: batch 3's gate lowered")
                .isPositive();

        List<Installment> asReturn = plan();
        OrderSettlement.shipmentReturned(asReturn, closure, shipment, batch1, dong(700_000), BigDecimal.ZERO,
                Set.of(), BigDecimal.ZERO, STAFF, AT, DAY);
        assertThat(bySeq(asReturn).get(4).reducedAmount()).isZero();
        assertThat(bySeq(asReturn).get(6).reducedAmount()).isZero();
    }

    @Test
    @DisplayName("past what was collected and not yet refundable, the batches still to come are lowered last, after "
            + "delivery before before delivery, the latest first")
    void futureBatchesLast() {
        List<Installment> plan = plan();
        // Earlier settlements made 500,000 of the 600,000 collected refundable already: 100,000 is left to refund.
        OrderSettlement s = OrderSettlement.shipmentReturned(plan, closure, shipment, batch1, dong(700_000),
                BigDecimal.ZERO, Set.of(), dong(500_000), STAFF, AT, DAY).settlement();
        Map<Integer, Installment> i = bySeq(plan);

        assertThat(s.refundable()).isEqualByComparingTo(dong(100_000));
        // 300,000 off batch 1, 100,000 refunded, the last 300,000 off batch 3's installment after delivery - never a
        // gate while an installment after delivery is left to take it.
        assertThat(i.get(3).reducedAmount()).isEqualByComparingTo(dong(300_000));
        assertThat(i.get(7).reducedAmount()).isEqualByComparingTo(dong(300_000));
        assertThat(i.get(4).reducedAmount()).isZero();
        assertThat(i.get(5).reducedAmount()).isZero();
        assertThat(i.get(6).reducedAmount()).isZero();
        conserved(plan, s);
    }

    @Test
    @DisplayName("batch 2 delivered: its installment after delivery is older debt, set off before anything is refunded")
    void olderDebtBeforeRefund() {
        List<Installment> plan = plan();
        Map<Integer, Installment> i = bySeq(plan);
        // Batch 2 left: its gate was paid.
        i.get(4).reach(AT.minusSeconds(86_400), DAY.minusDays(1));
        i.get(4).pay(dong(300_000), AT.minusSeconds(86_400));
        OrderSettlement s = OrderSettlement.shipmentReturned(plan, closure, shipment, batch1, dong(700_000),
                BigDecimal.ZERO, Set.of(batch2), BigDecimal.ZERO, STAFF, AT, DAY).settlement();

        // Batch 1 takes 300,000; batch 2 - handed over, the customer's debt - takes the next 300,000 in the closure's
        // order (after delivery first); 100,000 is refunded. Batch 3, still to come, is not touched.
        assertThat(i.get(3).reducedAmount()).isEqualByComparingTo(dong(300_000));
        assertThat(i.get(5).reducedAmount()).isEqualByComparingTo(dong(300_000));
        assertThat(i.get(4).reducedAmount()).isZero();
        assertThat(i.get(6).reducedAmount()).isZero();
        assertThat(i.get(7).reducedAmount()).isZero();
        assertThat(s.refundable()).isEqualByComparingTo(dong(100_000));
        conserved(plan, s);
    }

    @Test
    @DisplayName("Q-19: the shipping fee waived with it comes off and is refunded as the goods are, stated apart")
    void shippingFeeWaived() {
        List<Installment> plan = plan();
        OrderSettlement s = OrderSettlement.shipmentReturned(plan, closure, shipment, batch1, dong(700_000),
                dong(50_000), Set.of(), BigDecimal.ZERO, STAFF, AT, DAY).settlement();

        assertThat(s.closed()).isEqualByComparingTo(dong(750_000));
        assertThat(s.shippingFeeWaived()).isEqualByComparingTo(dong(50_000));
        assertThat(s.refundable()).isEqualByComparingTo(dong(450_000));
        conserved(plan, s);
    }

    @Test
    @DisplayName("nothing worth settling, or a shipment not named, is refused; only this kind names a shipment")
    void refusals() {
        assertThatThrownBy(() -> OrderSettlement.shipmentReturned(plan(), closure, shipment, batch1, BigDecimal.ZERO,
                BigDecimal.ZERO, Set.of(), BigDecimal.ZERO, STAFF, AT, DAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderSettlement.shipmentReturned(plan(), closure, null, batch1, dong(1),
                BigDecimal.ZERO, Set.of(), BigDecimal.ZERO, STAFF, AT, DAY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> OrderSettlement.shipmentReturned(plan(), closure, shipment, batch1, dong(-1),
                dong(5), Set.of(), BigDecimal.ZERO, STAFF, AT, DAY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderSettlement.shipmentReturned(plan(), closure, shipment, batch1,
                new BigDecimal("100.5"), new BigDecimal("0.5"), Set.of(), BigDecimal.ZERO, STAFF, AT, DAY))
                .as("ck_order_settlement_shipment: the fee waived is whole dong, even when the sum is")
                .isInstanceOf(IllegalArgumentException.class);
        OrderSettlement made = OrderSettlement.shipmentReturned(plan(), closure, shipment, batch1, dong(700_000),
                BigDecimal.ZERO, Set.of(), BigDecimal.ZERO, STAFF, AT, DAY).settlement();
        assertThatThrownBy(() -> new OrderSettlement(made.id(), made.orderId(), made.contractId(), made.customerId(),
                made.orderNumber(), made.contractNumber(), OrderSettlement.Kind.GOODS_RETURNED, made.sourceId(),
                made.currency(), made.collected(), made.closed(), made.reduced(), made.surplus(), made.fee(), null,
                made.feeCovered(), made.refundable(), made.feeOwed(), null, STAFF, AT, made.reductions(), shipment,
                BigDecimal.ZERO)).as("a return names no shipment").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderSettlement(made.id(), made.orderId(), made.contractId(), made.customerId(),
                made.orderNumber(), made.contractNumber(), OrderSettlement.Kind.SHIPMENT_RETURNED, made.sourceId(),
                made.currency(), made.collected(), made.closed(), made.reduced(), made.surplus(), made.fee(), null,
                made.feeCovered(), made.refundable(), made.feeOwed(), null, STAFF, AT, made.reductions(), shipment,
                made.closed().add(BigDecimal.ONE))).as("never more waived than closed")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
