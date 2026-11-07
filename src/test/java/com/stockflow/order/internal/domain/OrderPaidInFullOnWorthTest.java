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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Follow-up of #196, owner decision SHORT-LIMITS 2026-11-02: "paid in full" is measured against what the order is worth
 * now, not against its original total - so an order that has paid what closing its rest left it worth reads PAID.
 */
class OrderPaidInFullOnWorthTest {

    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");
    private static final Instant LATER = Instant.parse("2026-11-03T03:00:00Z");
    private static final UUID SALES = UUID.randomUUID();

    /**
     * 10 plain cups at 100 000 and 100 printed cups at 20 000 - 3 000 000 VND - on deposit terms (900 000), the
     * customer paying {@code paid} in all, released, printing started, and the plain cups handed over first: closing
     * the rest then leaves the order worth 1 000 000.
     */
    private static Order plainCupsShippedFirst(long paid) {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 2), 4), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(100_000), null),
                        Order.line(new Sku("CUP-12OZ-PRINT"), 100, Money.vnd(20_000), UUID.randomUUID())), NOW);
        order.applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("30"), null, BalancePolicy.ON_DELIVERY);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.decidePartialShipment(true);
        order.submit();
        order.recordPayment(Money.vnd(900_000), NOW);
        order.release(UUID.randomUUID(), "HCM", SALES, NOW);
        order.startPicking(SALES, NOW);
        // The Production Order records when printing started (17 BR-11); the order reads it back from its row.
        order.restoreFulfilmentFacts(NOW, null);
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
        Order order = plainCupsShippedFirst(1_000_000);

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
        Order withFee = plainCupsShippedFirst(1_050_000);
        withFee.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, FEE, SALES, LATER);
        assertThat(withFee.remainderFeeRetainedAmount()).isEqualByComparingTo("50000");
        assertThat(withFee.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(withFee.paidInFullAt()).isEqualTo(LATER);

        Order noFee = plainCupsShippedFirst(1_000_000);
        noFee.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, null, SALES, LATER);
        assertThat(noFee.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(noFee.closedAmount()).isEqualByComparingTo("2000000");
    }
}
