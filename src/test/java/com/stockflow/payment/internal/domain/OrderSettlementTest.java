package com.stockflow.payment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.payment.api.FeeBearer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 4B-9 (kltn-docs 15 §4.5, §4.6, BR-14..BR-16; 17 §4.8 steps 30 and 33; D30, D32): the money a cancellation, a
 * closure of the rest or - since 4D-3 - goods returned settles, worked through dong by dong.
 *
 * <p>The contract of every scenario: 1,000 pieces at 2,000 with 10% VAT (goods 2,200,000), a mould / print fee line of
 * 500,000 + 10% (550,000) and a shipping fee line of 300,000 + 10% (330,000) - 3,080,000 with tax - paid 30% on signing
 * (924,000), 40% before delivery (1,232,000) and 30% after delivery (924,000).</p>
 */
class OrderSettlementTest {

    private static final Instant AT = Instant.parse("2026-11-09T03:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 9);
    private static final UUID STAFF = UUID.randomUUID();

    private final UUID contractId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    private Installment installment(int seq, InstallmentMilestone milestone, long amount, Integer dueDays) {
        return Installment.open(contractId, UUID.randomUUID(), orderId, customerId, "HD-20261109-000001-1",
                "SO-20261109-000001", seq, "Installment " + seq, milestone, null, null, BigDecimal.valueOf(amount),
                "VND", dueDays);
    }

    /** The plan above; {@code paidOnSigning}, {@code paidBefore} paid on the first two. */
    private List<Installment> plan(long paidOnSigning, long paidBefore) {
        Installment onSigning = installment(1, InstallmentMilestone.ON_SIGNING, 924_000, 0);
        onSigning.reach(AT.minusSeconds(864_000), DAY.minusDays(10));
        Installment before = installment(2, InstallmentMilestone.BEFORE_DELIVERY, 1_232_000, null);
        Installment after = installment(3, InstallmentMilestone.AFTER_DELIVERY, 924_000, 30);
        if (paidOnSigning > 0) {
            onSigning.pay(dong(paidOnSigning), AT.minusSeconds(86_400));
        }
        if (paidBefore > 0) {
            before.pay(dong(paidBefore), AT.minusSeconds(86_400));
        }
        return List.of(onSigning, before, after);
    }

    private static BigDecimal dong(long amount) {
        return BigDecimal.valueOf(amount);
    }

    private static OrderSettlement.Fee agreed(long amount) {
        return new OrderSettlement.Fee(null, dong(amount), "Goods printed to the customer's design",
                "1,000 cups printed before the order was cancelled", FeeBearer.CUSTOMER);
    }

    private static OrderSettlement.Fee share(String percent) {
        return new OrderSettlement.Fee(new BigDecimal(percent), null, null, null, FeeBearer.CUSTOMER);
    }

    private static ErrorCode code(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Nested
    @DisplayName("a cancellation (15 §4.6 step 18, BR-15)")
    class Cancellation {

        @Test
        @DisplayName("17 BR-11 - no fee: every dong collected is refunded; the installments not paid are cancelled")
        void noFeeRefundsEverything() {
            List<Installment> plan = plan(924_000, 300_000);
            OrderSettlement.Outcome outcome = OrderSettlement.cancel(plan, OrderSettlement.Fee.none(), STAFF, AT, DAY);
            OrderSettlement s = outcome.settlement();

            assertThat(s.kind()).isEqualTo(OrderSettlement.Kind.ORDER_CANCELLED);
            assertThat(s.sourceId()).isEqualTo(orderId);
            assertThat(s.collected()).isEqualByComparingTo(dong(1_224_000));
            assertThat(s.surplus()).isEqualByComparingTo(dong(1_224_000));
            assertThat(s.fee()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(s.refundable()).isEqualByComparingTo(dong(1_224_000));
            assertThat(s.feeOwed()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(outcome.feeItem()).isNull();

            assertThat(plan.get(0).status()).as("paid in full, it stays PAID").isEqualTo(InstallmentStatus.PAID);
            assertThat(plan.get(1).status()).isEqualTo(InstallmentStatus.CANCELLED);
            assertThat(plan.get(1).paidAmount()).as("what it was paid stays on it").isEqualByComparingTo(dong(300_000));
            assertThat(plan.get(2).status()).isEqualTo(InstallmentStatus.CANCELLED);
            assertThat(outcome.changed()).containsExactly(plan.get(1), plan.get(2));
        }

        @Test
        @DisplayName("paid more than the fee: the money collected pays the share first, the rest is refunded")
        void paidMoreThanTheFee() {
            OrderSettlement s = OrderSettlement.cancel(plan(924_000, 616_000), share("10"), STAFF, AT, DAY)
                    .settlement();
            // 10% of 1,540,000 collected.
            assertThat(s.fee()).isEqualByComparingTo(dong(154_000));
            assertThat(s.feeCovered()).isEqualByComparingTo(dong(154_000));
            assertThat(s.refundable()).isEqualByComparingTo(dong(1_386_000));
            assertThat(s.feeOwed()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(s.feeItemId()).isNull();
            assertThat(s.feeTerms().percent()).isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("a share is rounded to the dong, half up - as ROUND() in ck_order_settlement_fee_terms")
        void shareRoundsHalfUp() {
            Installment onSigning = installment(1, InstallmentMilestone.ON_SIGNING, 1_234_567, 0);
            onSigning.reach(AT, DAY);
            onSigning.pay(dong(1_234_567), AT);
            // 7.5% of 1,234,567 = 92,592.525
            OrderSettlement s = OrderSettlement.cancel(List.of(onSigning), share("7.5"), STAFF, AT, DAY).settlement();
            assertThat(s.fee()).isEqualByComparingTo(dong(92_593));
            assertThat(s.refundable()).isEqualByComparingTo(dong(1_141_974));
        }

        @Test
        @DisplayName("a fee above the money collected: all of it pays the fee, the shortfall is a fee item due today")
        void feeAboveWhatWasPaid() {
            List<Installment> plan = plan(660_000, 0);
            OrderSettlement.Outcome outcome = OrderSettlement.cancel(plan, agreed(1_000_000), STAFF, AT, DAY);
            OrderSettlement s = outcome.settlement();

            assertThat(s.collected()).isEqualByComparingTo(dong(660_000));
            assertThat(s.feeCovered()).isEqualByComparingTo(dong(660_000));
            assertThat(s.refundable()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(s.feeOwed()).isEqualByComparingTo(dong(340_000));

            Installment fee = outcome.feeItem();
            assertThat(fee).isNotNull();
            assertThat(s.feeItemId()).isEqualTo(fee.id());
            assertThat(fee.isFeeItem()).isTrue();
            assertThat(fee.milestone()).isEqualTo(InstallmentMilestone.CANCELLATION_FEE);
            assertThat(fee.contractInstallmentId()).as("no plan row").isNull();
            assertThat(fee.seq()).isEqualTo(Installment.FIRST_FEE_SEQ);
            assertThat(fee.amount()).isEqualByComparingTo(dong(340_000));
            assertThat(fee.status()).as("15 §5.1: it starts DUE").isEqualTo(InstallmentStatus.DUE);
            assertThat(fee.dueDate()).as("due the day of the cancellation").isEqualTo(DAY);
            assertThat(fee.orderId()).isEqualTo(orderId);
            assertThat(fee.customerId()).isEqualTo(customerId);
            assertThat(fee.transferReference()).isEqualTo("HD-20261109-000001-1 FEE1");

            // The customer pays it like an installment.
            fee.pay(dong(340_000), AT);
            assertThat(fee.status()).isEqualTo(InstallmentStatus.PAID);
            assertThat(fee.cancel(AT)).as("a fee item is never cancelled").isFalse();
        }

        @Test
        @DisplayName("nothing collected and an agreed fee: all of it is owed")
        void nothingCollected() {
            OrderSettlement.Outcome outcome = OrderSettlement.cancel(plan(0, 0), agreed(500_000), STAFF, AT, DAY);
            assertThat(outcome.settlement().surplus()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(outcome.settlement().feeOwed()).isEqualByComparingTo(dong(500_000));
            assertThat(outcome.feeItem().amount()).isEqualByComparingTo(dong(500_000));
        }

        @Test
        @DisplayName("17 §4.8 step 30: a share or an agreed amount with its reason and detail - never both, never bare")
        void feeTerms() {
            assertThatThrownBy(() -> new OrderSettlement.Fee(BigDecimal.TEN, dong(100), "r", "d", FeeBearer.CUSTOMER))
                    .extracting(OrderSettlementTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> new OrderSettlement.Fee(null, dong(100), null, null, FeeBearer.CUSTOMER))
                    .as("an agreed amount needs its reason and detail")
                    .extracting(OrderSettlementTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> new OrderSettlement.Fee(null, dong(100), "r", "x".repeat(2001), FeeBearer.CUSTOMER))
                    .extracting(OrderSettlementTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> new OrderSettlement.Fee(null, new BigDecimal("100.5"), "r", "d", FeeBearer.CUSTOMER))
                    .as("whole dong").extracting(OrderSettlementTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> share("0")).extracting(OrderSettlementTest::code)
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> share("100.01")).extracting(OrderSettlementTest::code)
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(share("100").percent()).isEqualByComparingTo("100");
        }
    }

    @Nested
    @DisplayName("who bears the fee (D46, D50): the company bearing it charges the customer nothing")
    class FeeBearerRules {

        private OrderSettlement.Fee companyAgreed(long amount) {
            return new OrderSettlement.Fee(null, dong(amount), "Goods printed to the customer's design",
                    "1,000 cups printed before the order was cancelled", FeeBearer.COMPANY);
        }

        @Test
        @DisplayName("a fee needs its bearer, and no fee has none")
        void bearerIsRequiredWithAFee() {
            assertThat(code(catchThrowable(() -> new OrderSettlement.Fee(null, dong(100), "r", "d", null))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(code(catchThrowable(() -> new OrderSettlement.Fee(new BigDecimal("10"), null, null, null,
                    null)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(code(catchThrowable(() -> new OrderSettlement.Fee(null, null, null, null, FeeBearer.COMPANY))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("CUSTOMER bears an agreed amount: charged as always - the money pays it first, the rest is refunded")
        void customerBearsTheAgreedAmount() {
            OrderSettlement s = OrderSettlement.cancel(plan(924_000, 616_000), agreed(400_000), STAFF, AT, DAY)
                    .settlement();
            assertThat(s.feeBearer()).isEqualTo(FeeBearer.CUSTOMER);
            assertThat(s.fee()).isEqualByComparingTo(dong(400_000));
            assertThat(s.refundable()).isEqualByComparingTo(dong(1_140_000));
            assertThat(s.companyFee()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("COMPANY bears an agreed amount: nothing charged, everything collected refundable, no fee item - "
                + "the amount and its terms are kept as the company's cost")
        void companyBearsTheAgreedAmount() {
            List<Installment> plan = plan(924_000, 616_000);
            OrderSettlement.Outcome outcome = OrderSettlement.cancel(plan, companyAgreed(400_000), STAFF, AT, DAY);
            OrderSettlement s = outcome.settlement();

            assertThat(s.feeBearer()).isEqualTo(FeeBearer.COMPANY);
            assertThat(s.fee()).as("what the customer is charged").isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(s.feeCovered()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(s.refundable()).as("as if no fee was kept").isEqualByComparingTo(dong(1_540_000));
            assertThat(s.feeOwed()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(outcome.feeItem()).as("no receivable").isNull();
            assertThat(s.companyFee()).as("the company's cost").isEqualByComparingTo(dong(400_000));
            assertThat(s.feeTerms().reason()).isEqualTo("Goods printed to the customer's design");
            assertThat(s.feeTerms().detail()).isEqualTo("1,000 cups printed before the order was cancelled");
        }

        @Test
        @DisplayName("COMPANY bearing an agreed amount above what was collected owes nothing either: no shortfall, no item")
        void companyBearingMoreThanWasCollectedOwesNothing() {
            OrderSettlement.Outcome outcome = OrderSettlement.cancel(plan(924_000, 0), companyAgreed(2_000_000), STAFF,
                    AT, DAY);
            assertThat(outcome.feeItem()).isNull();
            assertThat(outcome.settlement().feeOwed()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(outcome.settlement().refundable()).isEqualByComparingTo(dong(924_000));
            assertThat(outcome.settlement().companyFee()).isEqualByComparingTo(dong(2_000_000));
            // the same fee on the customer is a shortfall owed on a fee item
            assertThat(OrderSettlement.cancel(plan(924_000, 0), agreed(2_000_000), STAFF, AT, DAY).feeItem()).isNotNull();
        }

        @Test
        @DisplayName("COMPANY bears a share: the share of what was collected is the company's cost, the customer is "
                + "refunded all of it")
        void companyBearsAShare() {
            OrderSettlement.Fee fee = new OrderSettlement.Fee(new BigDecimal("10"), null, null, null, FeeBearer.COMPANY);
            OrderSettlement s = OrderSettlement.cancel(plan(924_000, 616_000), fee, STAFF, AT, DAY).settlement();
            assertThat(s.fee()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(s.refundable()).isEqualByComparingTo(dong(1_540_000));
            assertThat(s.companyFee()).as("10% of 1,540,000").isEqualByComparingTo(dong(154_000));
            assertThat(s.feeTerms().percent()).isEqualByComparingTo("10");
            assertThat(s.feeBearer()).isEqualTo(FeeBearer.COMPANY);
        }

        @Test
        @DisplayName("a closure: COMPANY bearing the fee leaves the refund as with no fee and writes no fee item")
        void companyBearsTheClosureFee() {
            OrderSettlement.Outcome noFee = OrderSettlement.closeRemainder(plan(924_000, 1_232_000), UUID.randomUUID(),
                    dong(1_100_000), OrderSettlement.Fee.none(), STAFF, AT, DAY);
            OrderSettlement.Outcome company = OrderSettlement.closeRemainder(plan(924_000, 1_232_000),
                    UUID.randomUUID(), dong(1_100_000), companyAgreed(100_000), STAFF, AT, DAY);
            OrderSettlement.Outcome customer = OrderSettlement.closeRemainder(plan(924_000, 1_232_000),
                    UUID.randomUUID(), dong(1_100_000), agreed(100_000), STAFF, AT, DAY);

            assertThat(company.settlement().refundable()).isEqualByComparingTo(noFee.settlement().refundable());
            assertThat(company.settlement().fee()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(company.settlement().companyFee()).isEqualByComparingTo(dong(100_000));
            assertThat(company.feeItem()).isEqualTo(noFee.feeItem());
            assertThat(customer.settlement().fee()).isEqualByComparingTo(dong(100_000));
            assertThat(customer.settlement().refundable())
                    .isEqualByComparingTo(noFee.settlement().refundable().subtract(dong(100_000)));
        }
    }

    @Nested
    @DisplayName("a closure of the rest (15 §4.6 step 19, BR-16; D30, D32)")
    class Closure {

        @Test
        @DisplayName("D30, D32: half the goods closed after the first two installments were paid - the customer pays "
                + "the goods received with their VAT and both fee lines in full, and the rest comes back")
        void halfTheGoodsClosed() {
            List<Installment> plan = plan(924_000, 1_232_000);
            // 500 pieces closed: 1,000,000 + 10% VAT. The fee lines are never part of what is closed (D30, D32).
            OrderSettlement.Outcome outcome = OrderSettlement.closeRemainder(plan, UUID.randomUUID(),
                    dong(1_100_000), OrderSettlement.Fee.none(), STAFF, AT, DAY);
            OrderSettlement s = outcome.settlement();

            Installment after = plan.get(2);
            assertThat(after.reducedAmount()).as("after delivery is taken off first, all of it")
                    .isEqualByComparingTo(dong(924_000));
            assertThat(after.status()).as("taken off whole with nothing paid: no longer owed")
                    .isEqualTo(InstallmentStatus.CANCELLED);
            assertThat(s.reduced()).isEqualByComparingTo(dong(924_000));
            assertThat(s.reductions()).singleElement().satisfies(r -> {
                assertThat(r.installmentId()).isEqualTo(after.id());
                assertThat(r.amount()).isEqualByComparingTo(dong(924_000));
            });
            // 176,000 of the goods closed was already paid: it is refunded.
            assertThat(s.surplus()).isEqualByComparingTo(dong(176_000));
            assertThat(s.refundable()).isEqualByComparingTo(dong(176_000));

            BigDecimal kept = s.collected().subtract(s.refundable());
            BigDecimal owedStill = plan.stream().map(Installment::outstanding).reduce(BigDecimal.ZERO,
                    BigDecimal::add);
            assertThat(kept.add(owedStill)).as("BR-16: goods received with VAT (1,100,000) + mould / print fee "
                    + "(550,000) + shipping (330,000)").isEqualByComparingTo(dong(1_980_000));
        }

        @Test
        @DisplayName("BR-16: a part closed while the installments still owe more - only what they owe is lowered")
        void partialCloseLowersWhatIsOwed() {
            List<Installment> plan = plan(924_000, 0);
            OrderSettlement.Outcome outcome = OrderSettlement.closeRemainder(plan, UUID.randomUUID(), dong(1_100_000),
                    OrderSettlement.Fee.none(), STAFF, AT, DAY);
            OrderSettlement s = outcome.settlement();

            assertThat(plan.get(2).reducedAmount()).isEqualByComparingTo(dong(924_000));
            assertThat(plan.get(1).reducedAmount()).as("then before delivery, for the rest")
                    .isEqualByComparingTo(dong(176_000));
            assertThat(plan.get(1).outstanding()).isEqualByComparingTo(dong(1_056_000));
            assertThat(plan.get(1).status()).isEqualTo(InstallmentStatus.NOT_DUE);
            assertThat(plan.get(0).reducedAmount()).as("what was paid is not touched").isEqualByComparingTo("0");
            assertThat(s.surplus()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(s.refundable()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(outcome.feeItem()).isNull();
            BigDecimal total = plan.stream().map(Installment::owedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(total).as("the contract is now worth 3,080,000 - 1,100,000").isEqualByComparingTo(
                    dong(1_980_000));
        }

        @Test
        @DisplayName("an installment paid in part and the rest taken off is PAID")
        void partlyPaidAndTakenOffIsPaid() {
            List<Installment> plan = plan(924_000, 1_232_000);
            Installment after = plan.get(2);
            after.reach(AT, DAY);
            after.pay(dong(400_000), AT);
            OrderSettlement.closeRemainder(plan, UUID.randomUUID(), dong(524_000), OrderSettlement.Fee.none(), STAFF,
                    AT, DAY);
            assertThat(after.status()).isEqualTo(InstallmentStatus.PAID);
            assertThat(after.paidAmount().add(after.reducedAmount())).isEqualByComparingTo(after.amount());
        }

        @Test
        @DisplayName("the surplus pays the closure's fee first; the rest is refunded")
        void feeFromTheSurplus() {
            OrderSettlement s = OrderSettlement.closeRemainder(plan(924_000, 1_232_000), UUID.randomUUID(),
                    dong(1_100_000), agreed(100_000), STAFF, AT, DAY).settlement();
            assertThat(s.surplus()).isEqualByComparingTo(dong(176_000));
            assertThat(s.feeCovered()).isEqualByComparingTo(dong(100_000));
            assertThat(s.refundable()).isEqualByComparingTo(dong(76_000));
            assertThat(s.feeOwed()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a closure's fee above the surplus: the shortfall is a fee item")
        void feeAboveTheSurplus() {
            OrderSettlement.Outcome outcome = OrderSettlement.closeRemainder(plan(924_000, 1_232_000),
                    UUID.randomUUID(), dong(1_100_000), agreed(300_000), STAFF, AT, DAY);
            assertThat(outcome.settlement().refundable()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(outcome.settlement().feeOwed()).isEqualByComparingTo(dong(124_000));
            assertThat(outcome.feeItem().amount()).isEqualByComparingTo(dong(124_000));
            assertThat(outcome.feeItem().name()).isEqualTo("Fee for the rest closed");
        }

        @Test
        @DisplayName("17 §4.8 step 33: a closure keeps an agreed amount at most the goods closed - never a share")
        void closureFeeTerms() {
            assertThatThrownBy(() -> OrderSettlement.closeRemainder(plan(924_000, 0), UUID.randomUUID(),
                    dong(100_000), share("10"), STAFF, AT, DAY))
                    .extracting(OrderSettlementTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> OrderSettlement.closeRemainder(plan(924_000, 0), UUID.randomUUID(),
                    dong(100_000), agreed(100_001), STAFF, AT, DAY))
                    .extracting(OrderSettlementTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("15 §4.6 step 19: an installment tied to a batch that never came goes first, then the latest")
        void tiedInstallmentFirst() {
            UUID batch = UUID.randomUUID();
            Installment onSigning = installment(1, InstallmentMilestone.ON_SIGNING, 1_000_000, 0);
            onSigning.reach(AT, DAY);
            Installment tied = Installment.open(contractId, UUID.randomUUID(), orderId, customerId,
                    "HD-20261109-000001-1", "SO-20261109-000001", 2, "Batch 2 before delivery",
                    InstallmentMilestone.BEFORE_DELIVERY, batch, 2, dong(500_000), "VND", null);
            Installment after = installment(3, InstallmentMilestone.AFTER_DELIVERY, 500_000, 30);
            OrderSettlement.closeRemainder(List.of(onSigning, tied, after), UUID.randomUUID(), dong(600_000),
                    OrderSettlement.Fee.none(), STAFF, AT, DAY);
            assertThat(tied.reducedAmount()).isEqualByComparingTo(dong(500_000));
            assertThat(after.reducedAmount()).isEqualByComparingTo(dong(100_000));
            assertThat(onSigning.reducedAmount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("review: the latest batch's installment goes first, not one of an earlier batch delivered")
        void latestBatchFirst() {
            Installment onSigning = installment(1, InstallmentMilestone.ON_SIGNING, 1_000_000, 0);
            onSigning.reach(AT, DAY);
            // Batch 1 delivered: its installment after delivery is still not reached (it falls due with the whole
            // order until 4B-8), which must not put it first.
            Installment afterBatch1 = Installment.open(contractId, UUID.randomUUID(), orderId, customerId,
                    "HD-20261109-000001-1", "SO-20261109-000001", 2, "Batch 1 after delivery",
                    InstallmentMilestone.AFTER_DELIVERY, UUID.randomUUID(), 1, dong(500_000), "VND", 30);
            Installment beforeBatch2 = Installment.open(contractId, UUID.randomUUID(), orderId, customerId,
                    "HD-20261109-000001-1", "SO-20261109-000001", 3, "Batch 2 before delivery",
                    InstallmentMilestone.BEFORE_DELIVERY, UUID.randomUUID(), 2, dong(500_000), "VND", null);
            beforeBatch2.reach(AT, DAY);
            OrderSettlement.closeRemainder(List.of(onSigning, afterBatch1, beforeBatch2), UUID.randomUUID(),
                    dong(500_000), OrderSettlement.Fee.none(), STAFF, AT, DAY);
            assertThat(beforeBatch2.status()).isEqualTo(InstallmentStatus.CANCELLED);
            assertThat(afterBatch1.reducedAmount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("WF-05 TT-5: batch 1 failed and closed while batch 2 was delivered - batch 1's installment is "
                + "lowered, not batch 2's (the latest)")
        void closedBatchFirst() {
            UUID batch1 = UUID.randomUUID();
            UUID batch2 = UUID.randomUUID();
            Installment onSigning = installment(1, InstallmentMilestone.ON_SIGNING, 1_000_000, 0);
            onSigning.reach(AT, DAY);
            Installment afterBatch1 = Installment.open(contractId, UUID.randomUUID(), orderId, customerId,
                    "HD-20261109-000001-1", "SO-20261109-000001", 2, "Batch 1 after delivery",
                    InstallmentMilestone.AFTER_DELIVERY, batch1, 1, dong(500_000), "VND", 30);
            Installment afterBatch2 = Installment.open(contractId, UUID.randomUUID(), orderId, customerId,
                    "HD-20261109-000001-1", "SO-20261109-000001", 3, "Batch 2 after delivery",
                    InstallmentMilestone.AFTER_DELIVERY, batch2, 2, dong(500_000), "VND", 30);
            OrderSettlement s = OrderSettlement.closeRemainder(List.of(onSigning, afterBatch1, afterBatch2),
                    UUID.randomUUID(), dong(500_000), OrderSettlement.Fee.none(), STAFF, AT, DAY,
                    java.util.Set.of(batch2)).settlement();
            assertThat(afterBatch1.status()).isEqualTo(InstallmentStatus.CANCELLED);
            assertThat(afterBatch2.reducedAmount()).isEqualByComparingTo("0");
            assertThat(s.reductions()).singleElement()
                    .satisfies(r -> assertThat(r.installmentId()).isEqualTo(afterBatch1.id()));

            // Not known which batches left: the latest first, as before.
            Installment b1 = Installment.open(contractId, UUID.randomUUID(), orderId, customerId,
                    "HD-20261109-000001-1", "SO-20261109-000001", 2, "Batch 1 after delivery",
                    InstallmentMilestone.AFTER_DELIVERY, batch1, 1, dong(500_000), "VND", 30);
            Installment b2 = Installment.open(contractId, UUID.randomUUID(), orderId, customerId,
                    "HD-20261109-000001-1", "SO-20261109-000001", 3, "Batch 2 after delivery",
                    InstallmentMilestone.AFTER_DELIVERY, batch2, 2, dong(500_000), "VND", 30);
            OrderSettlement.closeRemainder(List.of(onSigning, b1, b2), UUID.randomUUID(), dong(100_000),
                    OrderSettlement.Fee.none(), STAFF, AT, DAY);
            assertThat(b2.reducedAmount()).isEqualByComparingTo(dong(100_000));
            assertThat(b1.reducedAmount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a second fee item of the contract is numbered after the first")
        void secondFeeItem() {
            List<Installment> plan = new java.util.ArrayList<>(plan(924_000, 1_232_000));
            OrderSettlement.Outcome first = OrderSettlement.closeRemainder(plan, UUID.randomUUID(), dong(200_000),
                    agreed(200_000), STAFF, AT, DAY);
            plan.add(first.feeItem());
            OrderSettlement.Outcome second = OrderSettlement.closeRemainder(plan, UUID.randomUUID(), dong(100_000),
                    agreed(100_000), STAFF, AT, DAY);
            assertThat(first.feeItem().seq()).isEqualTo(21);
            assertThat(second.feeItem().seq()).isEqualTo(22);
            assertThat(second.feeItem().transferReference()).endsWith(" FEE2");
            assertThat(first.feeItem().reducedAmount()).as("a closure never lowers a fee item")
                    .isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("goods returned (17 §4.10 step 40; 15 §4.5 step 15, BR-14; team plan 4D-3)")
    class GoodsReturned {

        @Test
        @DisplayName("what the goods were worth comes off what is still owed, after delivery first: nothing was paid "
                + "for them, so nothing is refundable and there is no fee")
        void comesOffWhatIsStillOwed() {
            List<Installment> plan = plan(924_000, 1_232_000);
            UUID returnId = UUID.randomUUID();
            OrderSettlement.Outcome outcome = OrderSettlement.returned(plan, returnId, dong(330_000), STAFF, AT, DAY);
            OrderSettlement s = outcome.settlement();

            assertThat(s.kind()).isEqualTo(OrderSettlement.Kind.GOODS_RETURNED);
            assertThat(s.sourceId()).as("one settlement per return").isEqualTo(returnId);
            assertThat(s.closed()).as("the goods returned, with VAT").isEqualByComparingTo(dong(330_000));
            assertThat(s.reduced()).isEqualByComparingTo(dong(330_000));
            assertThat(s.surplus()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(s.refundable()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(s.fee()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(s.feeOwed()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(outcome.feeItem()).isNull();
            assertThat(s.collected()).isEqualByComparingTo(dong(2_156_000));
            Installment after = plan.get(2);
            assertThat(after.reducedAmount()).isEqualByComparingTo(dong(330_000));
            assertThat(after.status()).as("still owed in part").isNotEqualTo(InstallmentStatus.CANCELLED);
            assertThat(s.reductions()).singleElement().satisfies(r -> {
                assertThat(r.installmentId()).isEqualTo(after.id());
                assertThat(r.amount()).isEqualByComparingTo(dong(330_000));
            });
            assertThat(outcome.changed()).containsExactly(after);
        }

        @Test
        @DisplayName("more than the last installment still owes goes on to the one before it, never to a paid one")
        void spillsOverToTheInstallmentBefore() {
            List<Installment> plan = plan(924_000, 300_000);
            OrderSettlement s = OrderSettlement.returned(plan, UUID.randomUUID(), dong(1_500_000), STAFF, AT, DAY)
                    .settlement();

            assertThat(plan.get(2).reducedAmount()).isEqualByComparingTo(dong(924_000));
            assertThat(plan.get(1).reducedAmount()).isEqualByComparingTo(dong(576_000));
            assertThat(plan.get(0).reducedAmount()).as("paid in full: nothing to take off").isEqualByComparingTo(
                    BigDecimal.ZERO);
            assertThat(s.reduced()).isEqualByComparingTo(dong(1_500_000));
            assertThat(s.refundable()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(s.reductions()).hasSize(2);
        }

        @Test
        @DisplayName("what cannot come off was paid already: it is refundable, up to what was collected")
        void whatWasPaidIsRefundable() {
            List<Installment> plan = plan(924_000, 1_232_000);
            OrderSettlement s = OrderSettlement.returned(plan, UUID.randomUUID(), dong(1_000_000), STAFF, AT, DAY)
                    .settlement();

            assertThat(s.reduced()).as("the 924,000 still owed after delivery").isEqualByComparingTo(dong(924_000));
            assertThat(s.surplus()).isEqualByComparingTo(dong(76_000));
            assertThat(s.refundable()).isEqualByComparingTo(dong(76_000));

            Installment onSigning = installment(1, InstallmentMilestone.ON_SIGNING, 924_000, 0);
            onSigning.reach(AT, DAY);
            onSigning.pay(dong(924_000), AT);
            OrderSettlement paidInFull = OrderSettlement.returned(List.of(onSigning), UUID.randomUUID(), dong(220_000),
                    STAFF, AT, DAY).settlement();
            assertThat(paidInFull.reduced()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(paidInFull.refundable()).isEqualByComparingTo(dong(220_000));
            assertThat(onSigning.reducedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a return refunds goods worth a positive whole number of dong, and a fee item is never reduced")
        void amountsAndFeeItems() {
            List<Installment> plan = plan(924_000, 300_000);
            assertThatThrownBy(() -> OrderSettlement.returned(plan, UUID.randomUUID(), BigDecimal.ZERO, STAFF, AT, DAY))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OrderSettlement.returned(plan, UUID.randomUUID(), new BigDecimal("100.50"), STAFF,
                    AT, DAY)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OrderSettlement.returned(plan, null, dong(1_000), STAFF, AT, DAY))
                    .isInstanceOf(NullPointerException.class);
            Installment fee = Installment.openFee(plan.get(0), Installment.FIRST_FEE_SEQ, "Fee", dong(50_000), AT, DAY);
            List<Installment> withFee = new java.util.ArrayList<>(plan);
            withFee.add(fee);
            OrderSettlement.returned(withFee, UUID.randomUUID(), dong(100_000), STAFF, AT, DAY);
            assertThat(fee.reducedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("WF-05 TH-1: a return of one delivery batch")
    class ReturnOfABatch {

        private final UUID batch1 = UUID.randomUUID();
        private final UUID batch2 = UUID.randomUUID();

        /** 40% on signing (paid), 30% after batch 1, 30% before batch 2 - the one that gates batch 2's handover. */
        private List<Installment> twoBatches() {
            Installment onSigning = installment(1, InstallmentMilestone.ON_SIGNING, 400_000, 0);
            onSigning.reach(AT.minusSeconds(864_000), DAY.minusDays(10));
            onSigning.pay(dong(400_000), AT.minusSeconds(86_400));
            Installment afterBatch1 = Installment.open(contractId, UUID.randomUUID(), orderId, customerId,
                    "HD-20261109-000001-1", "SO-20261109-000001", 2, "Batch 1 after delivery",
                    InstallmentMilestone.AFTER_DELIVERY, batch1, 1, dong(300_000), "VND", 10);
            Installment beforeBatch2 = Installment.open(contractId, UUID.randomUUID(), orderId, customerId,
                    "HD-20261109-000001-1", "SO-20261109-000001", 3, "Batch 2 before delivery",
                    InstallmentMilestone.BEFORE_DELIVERY, batch2, 2, dong(300_000), "VND", null);
            return List.of(onSigning, afterBatch1, beforeBatch2);
        }

        @Test
        @DisplayName("goods of batch 1 come off batch 1's installments first; batch 2's before-delivery installment is "
                + "left as it is")
        void theReturnedBatchFirst() {
            List<Installment> plan = twoBatches();
            OrderSettlement s = OrderSettlement.returned(plan, UUID.randomUUID(), batch1, dong(100_000), STAFF, AT,
                    DAY).settlement();

            assertThat(plan.get(1).reducedAmount()).isEqualByComparingTo(dong(100_000));
            assertThat(plan.get(2).reducedAmount()).as("batch 2 still owes for its own goods")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(s.reductions()).singleElement().satisfies(r ->
                    assertThat(r.installmentId()).isEqualTo(plan.get(1).id()));
        }

        @Test
        @DisplayName("more than batch 1 still owes goes on to the rest in the closure's order; without a batch, the "
                + "latest batch comes first, as before")
        void theRestAsBefore() {
            List<Installment> plan = twoBatches();
            OrderSettlement s = OrderSettlement.returned(plan, UUID.randomUUID(), batch1, dong(350_000), STAFF, AT,
                    DAY).settlement();
            assertThat(plan.get(1).reducedAmount()).isEqualByComparingTo(dong(300_000));
            assertThat(plan.get(2).reducedAmount()).isEqualByComparingTo(dong(50_000));
            assertThat(s.reductions()).hasSize(2);

            List<Installment> whole = twoBatches();
            OrderSettlement.returned(whole, UUID.randomUUID(), dong(100_000), STAFF, AT, DAY);
            assertThat(whole.get(2).reducedAmount()).isEqualByComparingTo(dong(100_000));
            assertThat(whole.get(1).reducedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("WF-05 TT-5: batch 1 paid in full, batch 2 still to come - the value is refunded, batch 2's "
                + "before-delivery installment (its handover's gate) is left as it is")
        void paidBatchRefundedNotTakenOffTheNext() {
            List<Installment> plan = twoBatches();
            plan.get(1).reach(AT, DAY);
            plan.get(1).pay(dong(300_000), AT);
            OrderSettlement s = OrderSettlement.returned(plan, UUID.randomUUID(), batch1, dong(250_000),
                    java.util.Set.of(batch1), BigDecimal.ZERO, STAFF, AT, DAY).settlement();
            assertThat(plan.get(2).reducedAmount()).as("batch 2 still owes for its own goods")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(s.reduced()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(s.refundable()).isEqualByComparingTo(dong(250_000));
        }

        @Test
        @DisplayName("WF-05 TT-5: what the customer still owes for goods they have is set off first, then refunded")
        void debtForGoodsHeldFirst() {
            List<Installment> plan = twoBatches();
            Installment untied = installment(4, InstallmentMilestone.AFTER_DELIVERY, 100_000, 30);
            List<Installment> withUntied = List.of(plan.get(0), plan.get(1), plan.get(2), untied);
            plan.get(1).reach(AT, DAY);
            plan.get(1).pay(dong(300_000), AT);
            OrderSettlement s = OrderSettlement.returned(withUntied, UUID.randomUUID(), batch1, dong(250_000),
                    java.util.Set.of(batch1), BigDecimal.ZERO, STAFF, AT, DAY).settlement();
            assertThat(untied.reducedAmount()).isEqualByComparingTo(dong(100_000));
            assertThat(plan.get(2).reducedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(s.refundable()).isEqualByComparingTo(dong(150_000));
        }

        @Test
        @DisplayName("WF-05 TT-5 (BR-14): never more refunded than was collected and not refundable already; past that "
                + "a batch still to come owes less, so the order still adds up")
        void refundBoundedByWhatWasPaid() {
            List<Installment> plan = twoBatches();
            plan.get(1).reach(AT, DAY);
            plan.get(1).pay(dong(300_000), AT);
            // 700 000 collected, 650 000 made refundable by earlier settlements: 50 000 left to hand back.
            OrderSettlement s = OrderSettlement.returned(plan, UUID.randomUUID(), batch1, dong(250_000),
                    java.util.Set.of(batch1), dong(650_000), STAFF, AT, DAY).settlement();
            assertThat(s.refundable()).isEqualByComparingTo(dong(50_000));
            assertThat(plan.get(2).reducedAmount()).isEqualByComparingTo(dong(200_000));
            assertThat(s.reduced()).isEqualByComparingTo(dong(200_000));
        }
    }

    @Nested
    @DisplayName("an order's refund (15 §5.3)")
    class Standing {

        private RefundRecord refund(long amount) {
            return RefundRecord.record(UUID.randomUUID(), UUID.randomUUID(), "SO-1", dong(amount), "VND", DAY,
                    "FT" + amount, null, STAFF, AT, DAY);
        }

        @Test
        @DisplayName("PENDING until a refund is recorded, PARTIALLY_REFUNDED, then REFUNDED; a voided one does not count")
        void statuses() {
            OrderSettlement s = OrderSettlement.cancel(plan(924_000, 0), OrderSettlement.Fee.none(), STAFF, AT, DAY)
                    .settlement();
            assertThat(RefundStanding.of(orderId, List.of(), List.of()).status())
                    .isEqualTo(RefundStanding.Status.NONE);
            assertThat(RefundStanding.of(orderId, List.of(s), List.of()).status())
                    .isEqualTo(RefundStanding.Status.PENDING);
            RefundRecord part = refund(400_000);
            RefundStanding partly = RefundStanding.of(orderId, List.of(s), List.of(part));
            assertThat(partly.status()).isEqualTo(RefundStanding.Status.PARTIALLY_REFUNDED);
            assertThat(partly.remaining()).isEqualByComparingTo(dong(524_000));
            RefundRecord wrong = refund(524_000);
            wrong.voidRecord("Wrong account", STAFF, AT);
            assertThat(RefundStanding.of(orderId, List.of(s), List.of(part, wrong)).status())
                    .isEqualTo(RefundStanding.Status.PARTIALLY_REFUNDED);
            assertThat(RefundStanding.of(orderId, List.of(s), List.of(part, wrong, refund(524_000))).status())
                    .isEqualTo(RefundStanding.Status.REFUNDED);
        }

        @Test
        @DisplayName("a record is a positive whole amount, made no later than today, with its statement line")
        void recordRules() {
            assertThatThrownBy(() -> RefundRecord.record(orderId, customerId, "SO-1", dong(0), "VND", DAY, "FT1",
                    null, STAFF, AT, DAY)).extracting(OrderSettlementTest::code)
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> RefundRecord.record(orderId, customerId, "SO-1", dong(10), "VND",
                    DAY.plusDays(1), "FT1", null, STAFF, AT, DAY)).extracting(OrderSettlementTest::code)
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> RefundRecord.record(orderId, customerId, "SO-1", dong(10), "VND", DAY, "  ",
                    null, STAFF, AT, DAY)).extracting(OrderSettlementTest::code)
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            RefundRecord r = refund(10);
            r.voidRecord("Wrong order", STAFF, AT);
            assertThatThrownBy(() -> r.voidRecord("again", STAFF, AT)).extracting(OrderSettlementTest::code)
                    .isEqualTo(ErrorCode.REFUND_RECORD_ALREADY_VOIDED);
        }
    }
}
