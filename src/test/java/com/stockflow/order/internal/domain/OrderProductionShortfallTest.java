package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderRemainderClosed;
import com.stockflow.order.api.CancellationFee;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Review 2026-10-25 of S6 on the aggregate alone: production completes a print line short only within what the
 * customer agreed with Sales, and a cancellation afterwards keeps and refunds out of the money still the order's.
 */
class OrderProductionShortfallTest {

    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");
    private static final UUID SALES = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();

    /** 10 000 printed cups at 5 000 and 4 lids at 10 000 (a stock line): 50 040 000 VND, paid in full, printing. */
    private static Order printing() {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 10, 25), 9), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("CUP-PRINTED"), 10_000, Money.vnd(5_000), UUID.randomUUID()),
                        Order.line(new Sku("LID-90"), 4, Money.vnd(10_000), null)), NOW);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.submit();
        order.recordPayment(order.total(), NOW);
        order.release(UUID.randomUUID(), "HCM", SALES, NOW, java.util.Map.of());
        order.recordPrintingStarted(NOW);
        order.pullDomainEvents();
        return order;
    }

    private static UUID printLine(Order order) {
        return order.lines().get(0).id();
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("Sales records the agreement on a print line: a note, at least one unit kept, not on a stock line, "
            + "only while printing; a new one replaces it, never below what was closed")
    void agreement() {
        Order order = printing();
        UUID cups = printLine(order);
        UUID lids = order.lines().get(1).id();
        assertThat(code(catchThrowable(() -> order.agreeProductionShortfall(lids, 1, "x", SALES, NOW))))
                .as("a stock line").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> order.agreeProductionShortfall(cups, 60, " ", SALES, NOW))))
                .as("no note").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> order.agreeProductionShortfall(cups, 10_000, "all", SALES, NOW))))
                .as("the whole line is a cancellation").isEqualTo(ErrorCode.VALIDATION_FAILED);
        order.agreeProductionShortfall(cups, 0, "none", SALES, NOW);
        assertThat(order.lines().get(0).shortfallAgreement()).as("0 withdraws: nothing to withdraw yet").isNull();
        assertThat(code(catchThrowable(() -> order.agreeProductionShortfall(cups, -1, "x", SALES, NOW))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> order.agreeProductionShortfall(UUID.randomUUID(), 1, "x", SALES, NOW))))
                .isEqualTo(ErrorCode.NOT_FOUND);

        order.agreeProductionShortfall(cups, 100, "Khách đồng ý nhận thiếu tối đa 100 ly (Zalo 25/10)", SALES, NOW);
        assertThat(order.lines().get(0).shortfallAgreement()).satisfies(agreed -> {
            assertThat(agreed.quantity()).isEqualTo(100);
            assertThat(agreed.note()).contains("Zalo");
            assertThat(agreed.agreedBy()).isEqualTo(SALES);
        });
        order.closeProductionShortfall(cups, 60, MANAGER, NOW);
        assertThat(code(catchThrowable(() -> order.agreeProductionShortfall(cups, 50, "less", SALES, NOW))))
                .as("below the 60 already closed").isEqualTo(ErrorCode.VALIDATION_FAILED);
        order.agreeProductionShortfall(cups, 60, "Khách chỉ đồng ý 60", SALES, NOW);
        assertThat(order.lines().get(0).shortfallAgreement().quantity()).isEqualTo(60);

        order.completeProduction(java.util.Map.of(cups, 9_940), "HCM", NOW);
        assertThat(order.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        assertThat(catchThrowable(() -> order.agreeProductionShortfall(cups, 70, "late", SALES, NOW)))
                .as("printing is over").isInstanceOf(InvalidOrderTransitionException.class);
    }

    @Test
    @DisplayName("production closes only within the agreement: none, or beyond it, is refused and changes nothing; "
            + "within it the line gives up the units and the refund is asked once")
    void closureWithinTheAgreement() {
        Order order = printing();
        UUID cups = printLine(order);
        assertThat(code(catchThrowable(() -> order.closeProductionShortfall(cups, 60, MANAGER, NOW))))
                .as("Sales recorded nothing").isEqualTo(ErrorCode.PRODUCTION_SHORTFALL_NOT_AGREED);
        order.agreeProductionShortfall(cups, 100, "Khách đồng ý nhận thiếu tối đa 100 ly", SALES, NOW);
        assertThat(code(catchThrowable(() -> order.closeProductionShortfall(cups, 101, MANAGER, NOW))))
                .isEqualTo(ErrorCode.PRODUCTION_SHORTFALL_NOT_AGREED);
        assertThat(order.lines().get(0).cancelledQuantity()).isZero();
        assertThat(order.pullDomainEvents()).isEmpty();

        assertThat(order.closeProductionShortfall(cups, 60, MANAGER, NOW)).isEqualByComparingTo("300000");
        assertThat(order.lines().get(0).cancelledQuantity()).isEqualTo(60);
        assertThat(order.lines().get(0).shortfallClosedQuantity()).isEqualTo(60);
        assertThat(order.remainderRefundAmount()).isEqualByComparingTo("300000");
        assertThat(order.pullDomainEvents()).singleElement().satisfies(event -> {
            OrderRemainderClosed closed = ((OrderEvent.RemainderClosed) event).payload();
            assertThat(closed.reasonCode()).isEqualTo(OrderRemainderClosed.PRODUCTION_SHORTFALL);
            assertThat(closed.refundableAmount()).isEqualByComparingTo("300000");
            assertThat(closed.closedBy()).isEqualTo(MANAGER);
        });
        assertThat(code(catchThrowable(() -> order.closeProductionShortfall(cups, 41, MANAGER, NOW))))
                .as("60 + 41 > 100").isEqualTo(ErrorCode.PRODUCTION_SHORTFALL_NOT_AGREED);
        assertThat(code(catchThrowable(() -> order.closeProductionShortfall(cups, 40, SALES, NOW))))
                .as("who recorded the agreement does not close it too").isEqualTo(
                        ErrorCode.PRODUCTION_SHORTFALL_SELF_APPROVAL);
        order.closeProductionShortfall(cups, 40, MANAGER, NOW);
        assertThat(order.remainderRefundAmount()).as("the second closure asks only for its own")
                .isEqualByComparingTo("500000");
        assertThat(order.status()).isEqualTo(OrderStatus.IN_PRODUCTION);
    }

    @Test
    @DisplayName("an agreement is withdrawn while nothing was closed under it; closing the whole line is a cancellation")
    void withdrawnAgreementAndWholeLine() {
        Order order = printing();
        UUID cups = printLine(order);
        order.agreeProductionShortfall(cups, 100, "Khách đồng ý nhận thiếu tối đa 100 ly", SALES, NOW);
        order.agreeProductionShortfall(cups, 0, "Ghi nhầm dòng", SALES, NOW);
        assertThat(order.lines().get(0).shortfallAgreement()).isNull();
        assertThat(code(catchThrowable(() -> order.closeProductionShortfall(cups, 60, MANAGER, NOW))))
                .isEqualTo(ErrorCode.PRODUCTION_SHORTFALL_NOT_AGREED);

        order.agreeProductionShortfall(cups, 9_999, "Khách đồng ý nhận ít nhất 1 ly", SALES, NOW);
        assertThat(code(catchThrowable(() -> order.closeProductionShortfall(cups, 10_000, MANAGER, NOW))))
                .as("nothing of the line left: a cancellation").isEqualTo(ErrorCode.VALIDATION_FAILED);
        order.closeProductionShortfall(cups, 10, MANAGER, NOW);
        assertThat(code(catchThrowable(() -> order.agreeProductionShortfall(cups, 0, "rút lại", SALES, NOW))))
                .as("10 already closed under it").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("cancelled after a shortfall: an agreed fee is kept, and the rest refunded, out of what is left of the "
            + "money - the 300 000 already asked back is not refunded twice")
    void cancelledAfterAShortfallWithAFee() {
        Order order = printing();
        UUID cups = printLine(order);
        order.agreeProductionShortfall(cups, 60, "Khách đồng ý nhận thiếu 60 ly", SALES, NOW);
        order.closeProductionShortfall(cups, 60, MANAGER, NOW);
        order.pullDomainEvents();

        order.cancel(CancellationReasonCode.CUSTOMER_REQUEST, "Khách huỷ phần còn lại", null,
                new CancellationFee(new BigDecimal("40000000"), "Đã in", "Thoả thuận qua điện thoại 25/10"), SALES, NOW);
        assertThat(order.cancellationRetainedAmount()).isEqualByComparingTo("40000000");
        assertThat(order.pullDomainEvents()).filteredOn(OrderEvent.Cancelled.class::isInstance).singleElement()
                .satisfies(event -> {
                    OrderCancelled cancelled = ((OrderEvent.Cancelled) event).payload();
                    assertThat(cancelled.paidAmount()).isEqualByComparingTo("50040000");
                    assertThat(cancelled.refundableAmount()).as("50 040 000 - 300 000 asked - 40 000 000 kept")
                            .isEqualByComparingTo("9740000");
                    assertThat(cancelled.owedAmount()).isZero();
                });
    }

    @Test
    @DisplayName("cancelled after a shortfall: a share is of the money left; an agreed fee above it is owed, and is "
            + "at most what the order is now worth")
    void cancelledAfterAShortfallWithAShareOrALargeFee() {
        Order share = printing();
        share.agreeProductionShortfall(printLine(share), 60, "Khách đồng ý nhận thiếu 60 ly", SALES, NOW);
        share.closeProductionShortfall(printLine(share), 60, MANAGER, NOW);
        share.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, new BigDecimal("10"), SALES, NOW);
        assertThat(share.cancellationRetainedAmount()).as("10% of 49 740 000").isEqualByComparingTo("4974000");

        Order large = printing();
        large.agreeProductionShortfall(printLine(large), 60, "Khách đồng ý nhận thiếu 60 ly", SALES, NOW);
        large.closeProductionShortfall(printLine(large), 60, MANAGER, NOW);
        large.pullDomainEvents();
        assertThat(code(catchThrowable(() -> large.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, null,
                new CancellationFee(new BigDecimal("49800000"), "Đã in", "x"), SALES, NOW))))
                .as("above the 49 740 000 the order is worth").isEqualTo(ErrorCode.VALIDATION_FAILED);
        large.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, null,
                new CancellationFee(new BigDecimal("49740000"), "Đã in", "Thoả thuận 25/10"), SALES, NOW);
        assertThat(large.cancellationRetainedAmount()).isEqualByComparingTo("49740000");
        assertThat(large.pullDomainEvents()).filteredOn(OrderEvent.Cancelled.class::isInstance).singleElement()
                .satisfies(event -> {
                    OrderCancelled cancelled = ((OrderEvent.Cancelled) event).payload();
                    assertThat(cancelled.refundableAmount()).isZero();
                    assertThat(cancelled.owedAmount()).isZero();
                });
    }
}
