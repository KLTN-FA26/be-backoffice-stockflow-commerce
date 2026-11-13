package com.stockflow.order.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.FeeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 4B-4 (kltn-docs 14 §4.3 steps 11-12, BR-13, BR-23; 15 BR-12, BR-18): the delivery schedule and payment plan of a
 * contract against its order.
 */
class ContractTermsTest {

    private static final BigDecimal TEN = new BigDecimal("10");
    private static final LocalDate ASKED = LocalDate.of(2026, 12, 1);

    /**
     * Cups: 20 cartons of 50 at 2,000 a piece, 10% VAT (2,200,000 with tax); lids: 10 cartons of 100 at 500, 10%
     * (550,000); a shipping fee of 300,000 at 10% (330,000). The total with tax is 3,080,000.
     */
    private static Order order() {
        OrderLine cups = Order.line(UUID.randomUUID(), new Sku("LY-A"), 1000, Money.vnd(2_000), null)
                .withCartons(UUID.randomUUID(), 20, 50).withTax(TEN).withRequestedDeliveryDate(ASKED);
        OrderLine lids = Order.line(UUID.randomUUID(), new Sku("NAP-A"), 1000, Money.vnd(500), null)
                .withCartons(UUID.randomUUID(), 10, 100).withTax(TEN).withRequestedDeliveryDate(ASKED);
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 9), 7), UUID.randomUUID(), UUID.randomUUID(),
                List.of(cups, lids), Instant.now());
        order.attachFees(List.of(OrderFee.of(UUID.randomUUID(), FeeKind.SHIPPING_FEE, null, Money.vnd(300_000), TEN)));
        return order;
    }

    private static UUID cups(Order order) {
        return order.lines().get(0).id();
    }

    private static UUID lids(Order order) {
        return order.lines().get(1).id();
    }

    private static List<FieldError> refused(ErrorCode code, Runnable call) {
        BusinessException e = catchThrowableOfType(BusinessException.class, call::run);
        assertThat(e).as("refused").isNotNull();
        assertThat(e.errorCode()).isEqualTo(code);
        return e.fieldErrors();
    }

    private static ContractTerms.BatchEntry batch(LocalDate from, LocalDate to, Object... lineAndCartons) {
        List<ContractTerms.LineEntry> lines = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < lineAndCartons.length; i += 2) {
            lines.add(new ContractTerms.LineEntry((UUID) lineAndCartons[i], (Integer) lineAndCartons[i + 1]));
        }
        return new ContractTerms.BatchEntry(from, to, lines);
    }

    private static ContractTerms.InstallmentEntry share(String percent, PaymentMilestone milestone) {
        return new ContractTerms.InstallmentEntry("Dot", milestone, null, new BigDecimal(percent), null, null);
    }

    private static ContractTerms.InstallmentEntry amount(long amount, PaymentMilestone milestone, Integer batch) {
        return new ContractTerms.InstallmentEntry("Dot", milestone, BigDecimal.valueOf(amount), null, null, batch);
    }

    @Test
    @DisplayName("the default schedule: one batch of every carton, in the window the customer asked for")
    void defaultSchedule() {
        Order order = order();
        assertThat(ContractTerms.defaultSchedule(order)).singleElement().satisfies(batch -> {
            assertThat(batch.seq()).isEqualTo(1);
            assertThat(batch.windowStart()).isEqualTo(ASKED);
            assertThat(batch.windowEnd()).isEqualTo(ASKED);
            assertThat(batch.cartonsOf(cups(order))).isEqualTo(20);
            assertThat(batch.cartonsOf(lids(order))).isEqualTo(10);
        });
    }

    @Test
    @DisplayName("two batches splitting the cartons; each batch's goods with tax, adding up to the goods exactly")
    void twoBatches() {
        Order order = order();
        List<DeliveryBatch> schedule = ContractTerms.schedule(order, List.of(
                batch(ASKED, ASKED.plusDays(2), cups(order), 7, lids(order), 10),
                batch(ASKED.plusDays(14), ASKED.plusDays(14), cups(order), 13)));
        assertThat(schedule).extracting(DeliveryBatch::seq).containsExactly(1, 2);
        Map<Integer, Money> values = ContractTerms.batchValues(order, schedule);
        // 350 cups with tax: 2,200,000 x 350 / 1000 = 770,000; and all the lids, 550,000.
        assertThat(values.get(1)).isEqualTo(Money.vnd(1_320_000));
        assertThat(values.get(2)).isEqualTo(Money.vnd(1_430_000));
        assertThat(values.get(1).plus(values.get(2))).as("the goods with tax, fees apart")
                .isEqualTo(Money.vnd(2_750_000));
    }

    @Test
    @DisplayName("BR-23: every rule a schedule breaks is named, by field")
    void scheduleRules() {
        Order order = order();
        List<FieldError> errors = refused(ErrorCode.DELIVERY_SCHEDULE_INVALID, () -> ContractTerms.schedule(order,
                List.of(batch(ASKED.plusDays(5), ASKED.plusDays(4), cups(order), 10, cups(order), 1),
                        batch(ASKED, ASKED, lids(order), 0, UUID.randomUUID(), 3),
                        batch(ASKED, null))));
        assertThat(errors).extracting(FieldError::field).contains(
                "deliverySchedule[0].windowEnd",            // ends before it starts
                "deliverySchedule[0].lines[1].orderLineId", // the same line twice
                "deliverySchedule[1].windowStart",          // starts before the batch before it
                "deliverySchedule[1].lines[0].cartons",     // no cartons
                "deliverySchedule[1].lines[1].orderLineId", // a line of another order
                "deliverySchedule[2].windowEnd",            // half a window
                "deliverySchedule[2].lines",                // no cartons at all
                "deliverySchedule");                        // cartons not adding up to the lines
        assertThat(errors).extracting(FieldError::message)
                .anyMatch(message -> message.startsWith("LY-A: 10 carton(s) over the batches, the order has 20"))
                .anyMatch(message -> message.startsWith("NAP-A: 0 carton(s)"));
        assertThat(refused(ErrorCode.DELIVERY_SCHEDULE_INVALID, () -> ContractTerms.schedule(order, List.of())))
                .extracting(FieldError::field).containsExactly("deliverySchedule");
    }

    @Test
    @DisplayName("BR-23: sent with every window known, none in the past")
    void windowsWhenSent() {
        Order order = order();
        List<DeliveryBatch> schedule = ContractTerms.schedule(order, List.of(
                batch(null, null, cups(order), 20), batch(ASKED, ASKED, lids(order), 10)));
        assertThat(refused(ErrorCode.DELIVERY_SCHEDULE_INVALID,
                () -> ContractTerms.requireWindows(schedule, ASKED.minusDays(1))))
                .extracting(FieldError::field).containsExactly("deliverySchedule[0].windowStart");
        assertThat(refused(ErrorCode.DELIVERY_SCHEDULE_INVALID,
                () -> ContractTerms.requireWindows(ContractTerms.defaultSchedule(order), ASKED.plusDays(1))))
                .extracting(FieldError::message).containsExactly("not in the past when the contract is sent");
        ContractTerms.requireWindows(ContractTerms.defaultSchedule(order), ASKED);
    }

    @Test
    @DisplayName("BR-13: shares rounded to the dong, what is left on the last; due days defaulted by milestone")
    void sharesOfTheTotal() {
        Order order = order();
        List<PlannedInstallment> plan = ContractTerms.plan(order, 1, List.of(share("33.33", PaymentMilestone.ON_SIGNING),
                share("33.33", PaymentMilestone.BEFORE_DELIVERY), share("33.34", PaymentMilestone.AFTER_DELIVERY)),
                true, 45);
        // 3,080,000 x 33.33% = 1,026,564; the last takes 3,080,000 - 2,053,128 = 1,026,872.
        assertThat(plan).extracting(i -> i.amount().amount().longValueExact())
                .containsExactly(1_026_564L, 1_026_564L, 1_026_872L);
        assertThat(plan).extracting(PlannedInstallment::dueDays).containsExactly(0, null, 45);
        assertThat(plan).extracting(PlannedInstallment::seq).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("BR-13: amounts add up to the total with tax, fees and VAT included")
    void amountsAddUp() {
        Order order = order();
        List<PlannedInstallment> plan = ContractTerms.plan(order, 2, List.of(
                amount(1_000_000, PaymentMilestone.ON_SIGNING, null),
                amount(1_320_000, PaymentMilestone.BEFORE_DELIVERY, 1),
                amount(760_000, PaymentMilestone.BEFORE_DELIVERY, 2)), false, null);
        assertThat(plan).extracting(PlannedInstallment::deliveryBatchSeq).containsExactly(null, 1, 2);
        assertThat(refused(ErrorCode.PAYMENT_PLAN_INVALID, () -> ContractTerms.plan(order, 2, List.of(
                amount(1_000_000, PaymentMilestone.ON_SIGNING, null)), false, null)))
                .extracting(FieldError::message)
                .containsExactly("the installments add up to 1000000; the order's total with tax is 3080000");
    }

    /** 1 carton of 7 cups at 1,001 a piece, 10% VAT: 7,007 + 701 = 7,708 - a total whose shares fall between dongs. */
    private static Order oddOrder() {
        OrderLine cups = Order.line(UUID.randomUUID(), new Sku("LY-B"), 7, Money.vnd(1_001), null)
                .withCartons(UUID.randomUUID(), 1, 7).withTax(TEN);
        return Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 9), 8), UUID.randomUUID(), UUID.randomUUID(),
                List.of(cups), Instant.now());
    }

    @Test
    @DisplayName("A8 (14 §6): amounts and shares mixed - the dong the shares' rounding leaves goes on the last one")
    void mixedPlanPutsTheRoundingOnTheLast() {
        Order order = oddOrder();
        assertThat(order.total()).isEqualTo(Money.vnd(7_708));
        // 5,781 + 12.5% + 12.5%: exactly the total (963.5 each), but each share rounds up to 964 - one dong over.
        List<PlannedInstallment> plan = ContractTerms.plan(order, 1, List.of(
                amount(5_781, PaymentMilestone.ON_SIGNING, null), share("12.5", PaymentMilestone.BEFORE_DELIVERY),
                share("12.5", PaymentMilestone.BEFORE_DELIVERY)), false, null);
        assertThat(plan).extracting(i -> i.amount().amount().longValueExact()).containsExactly(5_781L, 964L, 963L);
        assertThat(plan.getLast().percent()).as("the share as Sales entered it").isEqualByComparingTo("12.5");
        // A share first and an amount last: the amount takes the dong.
        assertThat(ContractTerms.plan(order, 1, List.of(share("12.5", PaymentMilestone.ON_SIGNING),
                        share("12.5", PaymentMilestone.ON_SIGNING), amount(5_781, PaymentMilestone.BEFORE_DELIVERY,
                                null)), false, null))
                .extracting(i -> i.amount().amount().longValueExact()).containsExactly(964L, 964L, 5_780L);
        // Further off than rounding can explain: refused, with what it adds up to.
        assertThat(refused(ErrorCode.PAYMENT_PLAN_INVALID, () -> ContractTerms.plan(order, 1, List.of(
                amount(5_000, PaymentMilestone.ON_SIGNING, null), share("12.5", PaymentMilestone.BEFORE_DELIVERY),
                share("12.5", PaymentMilestone.BEFORE_DELIVERY)), false, null)))
                .extracting(FieldError::message)
                .containsExactly("the installments add up to 6928; the order's total with tax is 7708");
        // Amounts alone are what Sales typed: one dong off is refused, never adjusted.
        assertThat(refused(ErrorCode.PAYMENT_PLAN_INVALID, () -> ContractTerms.plan(order, 1, List.of(
                amount(5_781, PaymentMilestone.ON_SIGNING, null), amount(1_926, PaymentMilestone.BEFORE_DELIVERY,
                        null)), false, null))).hasSize(1);
    }

    @Test
    @DisplayName("A4 (BR-13, DS-02b): tied installments suggested again from the batches; the plan adds up to the total")
    void tiedInstallmentsAreSuggestedFromTheBatches() {
        Order order = order();
        List<DeliveryBatch> schedule = ContractTerms.schedule(order, List.of(
                batch(ASKED, ASKED.plusDays(2), cups(order), 7, lids(order), 10),
                batch(ASKED.plusDays(14), ASKED.plusDays(14), cups(order), 13)));
        // Worth 1,320,000 and 1,430,000 with tax; the shipping fee (330,000) is in no batch.
        List<ContractTerms.InstallmentEntry> stale = List.of(amount(330_000, PaymentMilestone.ON_SIGNING, null),
                amount(2_000_000, PaymentMilestone.BEFORE_DELIVERY, 1),
                amount(750_000, PaymentMilestone.AFTER_DELIVERY, 2));
        List<ContractTerms.InstallmentEntry> suggested = ContractTerms.suggestTied(order, schedule, stale);
        assertThat(suggested).extracting(e -> e.amount().longValueExact())
                .as("the fees on signing: each tied one is its batch's value").containsExactly(330_000L, 1_320_000L,
                        1_430_000L);
        assertThat(suggested).extracting(ContractTerms.InstallmentEntry::deliveryBatchSeq).containsExactly(null, 1, 2);

        // A third on signing: the rest shared as the batches are worth, the last tied one taking the rounding.
        suggested = ContractTerms.suggestTied(order, schedule, List.of(share("33.33", PaymentMilestone.ON_SIGNING),
                amount(1, PaymentMilestone.BEFORE_DELIVERY, 1), amount(1, PaymentMilestone.AFTER_DELIVERY, 2)));
        assertThat(suggested.getFirst().percent()).as("the untied share is kept").isEqualByComparingTo("33.33");
        // 3,080,000 - 1,026,564 = 2,053,436; x 1,320,000 / 2,750,000 = 985,649.28 -> 985,649; the last 1,067,787.
        assertThat(suggested.subList(1, 3)).extracting(e -> e.amount().longValueExact())
                .containsExactly(985_649L, 1_067_787L);
        List<PlannedInstallment> plan = ContractTerms.plan(order, 2, suggested, true, 30);
        assertThat(plan.stream().map(PlannedInstallment::amount).reduce(Money.vnd(0), Money::plus))
                .isEqualTo(order.total());

        // Nothing tied: nothing to suggest. Everything already taken by the untied ones: refused.
        List<ContractTerms.InstallmentEntry> untied = List.of(share("100", PaymentMilestone.ON_SIGNING));
        assertThat(ContractTerms.suggestTied(order, schedule, untied)).isEqualTo(untied);
        assertThat(refused(ErrorCode.PAYMENT_PLAN_INVALID, () -> ContractTerms.suggestTied(order, schedule, List.of(
                share("100", PaymentMilestone.ON_SIGNING), amount(1, PaymentMilestone.BEFORE_DELIVERY, 2)))))
                .extracting(FieldError::field).containsExactly("paymentPlan");
        assertThat(refused(ErrorCode.PAYMENT_PLAN_INVALID, () -> ContractTerms.suggestTied(order, schedule, List.of(
                amount(1, PaymentMilestone.BEFORE_DELIVERY, 3))))).extracting(FieldError::field)
                .containsExactly("paymentPlan[0].deliveryBatchSeq");
    }

    @Test
    @DisplayName("BR-13: every rule an installment breaks is named; paying after delivery needs credit (15 BR-12)")
    void planRules() {
        Order order = order();
        List<FieldError> errors = refused(ErrorCode.PAYMENT_PLAN_INVALID, () -> ContractTerms.plan(order, 1, List.of(
                new ContractTerms.InstallmentEntry(" ", PaymentMilestone.ON_SIGNING, BigDecimal.ONE, null, null, 1),
                new ContractTerms.InstallmentEntry("B", PaymentMilestone.BEFORE_DELIVERY, BigDecimal.TEN,
                        BigDecimal.ONE, 5, 2),
                new ContractTerms.InstallmentEntry("C", null, new BigDecimal("0.5"), null, -1, null),
                new ContractTerms.InstallmentEntry("D", PaymentMilestone.ON_SIGNING, null, new BigDecimal("100.01"),
                        null, null)), false, null));
        assertThat(errors).extracting(FieldError::field).contains(
                "paymentPlan[0].name", "paymentPlan[0].deliveryBatchSeq", "paymentPlan[1].deliveryBatchSeq",
                "paymentPlan[1].dueDays", "paymentPlan[1]", "paymentPlan[2].milestone", "paymentPlan[2].dueDays",
                "paymentPlan[2].amount", "paymentPlan[3].percent");
        assertThat(refused(ErrorCode.PAYMENT_SCHEDULE_NEEDS_CREDIT, () -> ContractTerms.plan(order, 1, List.of(
                share("100", PaymentMilestone.AFTER_DELIVERY)), false, 30))).isEmpty();
    }
}
