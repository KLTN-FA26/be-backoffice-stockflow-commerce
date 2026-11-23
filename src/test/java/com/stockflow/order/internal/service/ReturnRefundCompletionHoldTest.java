package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.StagedUploads;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.inventory.api.CartonReturns;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ReturnChannel;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnLine;
import com.stockflow.order.internal.domain.ReturnPolicy;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnRequestRepository;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.api.RefundProgress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 Q-17 = a, audit r2 lifecycle L-1 / money L-3: a customer's return does not take an order whose completion
 * Sales hold into the refund branch - the carrier's path already refused it ({@code DeliveryFailureService
 * .followRefunds}); the return's path moved the held order RETURNED → PARTIALLY_REFUNDED all the same. Released, it
 * moves ({@code OrderCompletionHolds.release} follows it on, see {@code OrderCompletionHoldGuardsTest}). The order is a
 * mock: what is asserted is the return side's decision.
 */
class ReturnRefundCompletionHoldTest {

    private static final Instant DELIVERED = Instant.parse("2026-10-20T03:00:00Z");
    private static final Instant NOW = DELIVERED.plus(Duration.ofDays(2));
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID SALES = UUID.randomUUID();
    private static final UUID ACCOUNTANT = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();
    private static final ReturnPolicy POLICY = ReturnPolicy.defaults();

    private final ReturnRequestRepository returns = mock(ReturnRequestRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final DeliveryFailureService deliveryFailures = mock(DeliveryFailureService.class);
    private final ReturnRequestService service = new ReturnRequestService(returns, orders,
            mock(OrderEventPublisher.class), payments, mock(CustomerService.class), mock(FileTransfers.class),
            mock(StagedUploads.class), mock(TransactionOperations.class), POLICY,
            Clock.fixed(NOW, ZoneOffset.UTC), deliveryFailures, mock(ReturnAllowances.class),
            mock(CartonReturns.class), mock(ReturnWarehouseScope.class));

    private final UUID orderId = UUID.randomUUID();
    private final Order order = mock(Order.class);

    private static Order delivered() {
        Order real = new Order(new OrderId(UUID.randomUUID()), OrderNumber.of(LocalDate.of(2026, 10, 15), 1), CUSTOMER,
                UUID.randomUUID(), List.of(new OrderLine(LINE, new Sku("CUP-12OZ"), 100, Money.vnd(2_000), null,
                List.of())), OrderStatus.DELIVERED, DELIVERED.minus(Duration.ofDays(10)), null, 3L);
        real.restoreLifecycle(null, null, null, DELIVERED.minus(Duration.ofDays(1)), DELIVERED, null, null, null, null);
        return real;
    }

    /** RESOLVED: 10 approved and back, 4 of them refunded worth 8,000. */
    private static ReturnRequest resolved() {
        Order real = delivered();
        ReturnRequest request = ReturnRequest.open("RMA-1", UUID.randomUUID(), real, ReturnChannel.PORTAL,
                ReturnReason.DAMAGED_IN_TRANSIT, null, ReturnResolution.REFUND, List.of(new ReturnRequest.Claim(LINE,
                10)), Map.of(), Set.of(), CUSTOMER, NOW, POLICY);
        request.addEvidence(new ReturnEvidence(UUID.randomUUID(), ReturnEvidence.Stage.CLAIM, "k", "a.jpg",
                "image/jpeg", 10, CUSTOMER, NOW), POLICY);
        request.approve(SALES, NOW, null, null, null, POLICY);
        request.goodsReceived(UUID.randomUUID(), "GR-1", "HCM-QC01",
                List.of(new ReturnRequest.ReturnedCarton("CUP-12OZ", 10)), SALES, NOW);
        ReturnLine line = request.lines().getFirst();
        request.proposeRefund(SALES, NOW, Map.of(line.id(), 4), real, null, false, null, null, Map.of());
        request.resolve(ACCOUNTANT, NOW, new ReturnRequest.MoneyOutcome(BigDecimal.valueOf(8_000), BigDecimal.ZERO));
        return request;
    }

    private void given(boolean held) {
        when(order.status()).thenReturn(OrderStatus.RETURNED);
        when(order.id()).thenReturn(new OrderId(orderId));
        when(order.lines()).thenReturn(List.of());
        when(orders.findByIdForUpdate(new OrderId(orderId))).thenReturn(Optional.of(order));
        when(returns.findByOrder(orderId)).thenReturn(List.of(resolved()));
        when(payments.refundProgress(orderId)).thenReturn(new RefundProgress(BigDecimal.valueOf(8_000),
                BigDecimal.valueOf(8_000)));
        when(deliveryFailures.completionHeld(orderId)).thenReturn(held);
    }

    @Test
    @DisplayName("L-1: the refund recorded, the completion held - the order stays RETURNED, not PARTIALLY_REFUNDED")
    void aHeldOrderStaysOutOfTheRefundBranch() {
        given(true);

        service.followRefunds(orderId, ACCOUNTANT);

        verify(order, never()).refunded(anyBoolean(), any(), any(), anyString());
    }

    @Test
    @DisplayName("L-1: the hold released - the same refund moves the order on")
    void releasedItMoves() {
        given(false);

        service.followRefunds(orderId, ACCOUNTANT);

        verify(order).refunded(anyBoolean(), eq(ACCOUNTANT), any(), eq("REFUNDED"));
    }
}
