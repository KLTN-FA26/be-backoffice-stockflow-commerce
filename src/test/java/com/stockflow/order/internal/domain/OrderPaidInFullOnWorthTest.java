package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.order.api.CancellationFee;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.BalancePolicy;
import com.stockflow.order.api.PaymentStatus;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.api.PaymentTerm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Follow-up of #196 (S6), owner decision SHORT-LIMITS 2026-11-02: "paid in full" is measured against what the order is
 * worth now, not against its original total - so an order that has paid what a short completion left it worth reads
 * PAID, whether the money came before or after the reduction.
 */
class OrderPaidInFullOnWorthTest {

    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");
    private static final Instant LATER = Instant.parse("2026-11-03T03:00:00Z");
    private static final UUID SALES = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();

    /**
     * 100 printed cups at 10 000 and 4 lids at 10 000 (a stock line): 1 040 000 VND on deposit terms (30 %: 312 000),
     * released and printing; the customer goes without at most 30 cups, as Sales recorded.
     */
    private static Order printing() {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 2), 3), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("CUP-PRINTED"), 100, Money.vnd(10_000), UUID.randomUUID()),
                        Order.line(new Sku("LID-90"), 4, Money.vnd(10_000), null)), NOW);
        order.applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("30"));
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.submit();
        order.recordPayment(Money.vnd(312_000), NOW);
        order.release(UUID.randomUUID(), "HCM", SALES, NOW, Map.of());
        order.recordPrintingStarted(NOW);
        order.agreeProductionShortfall(order.lines().get(0).id(), 30, "Khách đồng ý nhận thiếu tối đa 30 ly", SALES, NOW);
        order.pullDomainEvents();
        return order;
    }

    private static UUID cups(Order order) {
        return order.lines().get(0).id();
    }

    @Test
    @DisplayName("money that falls short of the original total but covers what the order is worth after a short completion "
            + "makes it PAID: the money came before the reduction")
    void paidBeforeTheReduction() {
        Order order = printing();
        order.recordPayment(Money.vnd(528_000), NOW);               // 840 000 in all
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);

        order.closeProductionShortfall(cups(order), 20, MANAGER, LATER);   // worth 840 000 now

        assertThat(order.billable()).isEqualByComparingTo("840000");
        assertThat(order.amountDue()).isEqualByComparingTo("0");
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(order.paidInFullAt()).isEqualTo(LATER);
        assertThat(order.closedAmount()).isEqualByComparingTo("200000");
    }

    @Test
    @DisplayName("the balance paid after the reduction makes it PAID, at what it is worth, not at the original total")
    void paidAfterTheReduction() {
        Order order = printing();
        order.closeProductionShortfall(cups(order), 20, MANAGER, NOW);
        assertThat(order.paymentStatus()).as("only the deposit in").isEqualTo(PaymentStatus.PARTIALLY_PAID);

        order.recordPayment(Money.vnd(527_999), LATER);
        assertThat(order.paymentStatus()).as("one dong short").isEqualTo(PaymentStatus.PARTIALLY_PAID);
        order.recordPayment(Money.vnd(1), LATER);

        assertThat(order.paidAmount()).isEqualByComparingTo("840000");
        assertThat(order.total().amount()).as("the total is what was placed").isEqualByComparingTo("1040000");
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(order.paidInFullAt()).isEqualTo(LATER);
    }

    @Test
    @DisplayName("money short of what the order is worth now stays PARTIALLY_PAID after the reduction")
    void stillShort() {
        Order order = printing();
        order.recordPayment(Money.vnd(400_000), NOW);               // 712 000 in all

        order.closeProductionShortfall(cups(order), 20, MANAGER, LATER);

        assertThat(order.amountDue()).isEqualByComparingTo("128000");
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
        assertThat(order.paidInFullAt()).isNull();
    }

    @Test
    @DisplayName("an order paid in full before the reduction stays PAID, and the refund the closure asks for leaves it so")
    void paidInFullStaysPaid() {
        Order order = printing();
        order.recordPayment(Money.vnd(728_000), NOW);               // 1 040 000: the whole total
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        Instant paidAt = order.paidInFullAt();

        order.closeProductionShortfall(cups(order), 20, MANAGER, LATER);

        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(order.paidInFullAt()).isEqualTo(paidAt);
        assertThat(order.remainderRefundAmount()).isEqualByComparingTo("200000");
    }

    @Test
    @DisplayName("an order nothing was paid for by money is not called paid: the deposit alone keeps it PARTIALLY_PAID")
    void depositOnly() {
        Order order = printing();

        order.closeProductionShortfall(cups(order), 20, MANAGER, LATER);

        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
        assertThat(order.paidInFullAt()).isNull();
    }

    @Test
    @DisplayName("money taken back below what the order is worth now makes it PARTIALLY_PAID again; above, it stays PAID")
    void reversalIsMeasuredOnTheWorthToo() {
        Order order = printing();
        order.recordPayment(Money.vnd(528_000), NOW);
        order.closeProductionShortfall(cups(order), 20, MANAGER, LATER);
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PAID);

        order.recordPayment(Money.vnd(100_000), LATER);             // 940 000 in: 100 000 over what it is worth
        order.reversePayment(Money.vnd(100_000), false, "mistaken transfer", MANAGER, LATER);
        assertThat(order.paymentStatus()).as("840 000 still covers it").isEqualTo(PaymentStatus.PAID);

        order.reversePayment(Money.vnd(1), false, "one more", MANAGER, LATER);
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
        assertThat(order.paidInFullAt()).isNull();
    }

    /**
     * 10 cups from stock at 100 000 and 100 printed cups at 20 000 - 3 000 000 VND - on deposit terms (900 000), the
     * customer paying {@code paid} in all, released, printing, and the stock handed over ahead of the prints: closing
     * the rest then leaves the order worth 1 000 000.
     */
    private static Order stockShippedWhilePrinting(long paid) {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 2), 4), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(100_000), null),
                        Order.line(new Sku("CUP-12OZ-PRINT"), 100, Money.vnd(20_000), UUID.randomUUID())), NOW);
        order.applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("30"), null, BalancePolicy.ON_DELIVERY);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.decidePartialShipment(true);
        order.submit();
        order.recordPayment(Money.vnd(900_000), NOW);
        order.release(UUID.randomUUID(), "HCM", SALES, NOW, Map.of());
        order.recordPrintingStarted(NOW);
        order.recordHandover(new ShipmentHandover(UUID.randomUUID(),
                List.of(new ShipmentHandover.Line(order.lines().get(0).id(), 10)), NOW, SALES));
        if (paid > 900_000) {
            order.recordPayment(Money.vnd(paid - 900_000), NOW);
        }
        order.pullDomainEvents();
        return order;
    }

    private static final CancellationFee FEE = new CancellationFee(new BigDecimal("50000"), "Đã in",
            "Thoả thuận với khách 02/11: phí in");

    @Test
    @DisplayName("closing the rest with a fee: money that covers the new worth but not the fee is not paid in full; "
            + "the fee's receivable paid, it is")
    void feeNotCoveredThenPaid() {
        Order order = stockShippedWhilePrinting(1_000_000);

        order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, FEE, SALES, LATER);

        assertThat(order.billable()).isEqualByComparingTo("1000000");
        assertThat(order.remainderFeeOwed()).isEqualByComparingTo("50000");
        assertThat(order.amountDue()).as("the fee is still due").isEqualByComparingTo("50000");
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
        assertThat(order.paidInFullAt()).isNull();

        order.recordPayment(Money.vnd(49_999), LATER, false, true);
        assertThat(order.paymentStatus()).as("one dong of the fee left").isEqualTo(PaymentStatus.PARTIALLY_PAID);
        order.recordPayment(Money.vnd(1), LATER, false, true);

        assertThat(order.amountDue()).isEqualByComparingTo("0");
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(order.paidInFullAt()).isEqualTo(LATER);
    }

    @Test
    @DisplayName("closing the rest with a fee the money covers (worth + fee) makes it PAID at the closure; without a fee, "
            + "the worth alone does")
    void feeCoveredAtTheClosure() {
        Order withFee = stockShippedWhilePrinting(1_050_000);
        withFee.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, FEE, SALES, LATER);
        assertThat(withFee.remainderFeeRetainedAmount()).isEqualByComparingTo("50000");
        assertThat(withFee.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(withFee.paidInFullAt()).isEqualTo(LATER);

        Order noFee = stockShippedWhilePrinting(1_000_000);
        noFee.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, null, SALES, LATER);
        assertThat(noFee.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(noFee.closedAmount()).isEqualByComparingTo("2000000");
    }
}
