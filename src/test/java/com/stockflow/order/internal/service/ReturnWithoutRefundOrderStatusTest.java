package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.StagedUploads;
import com.stockflow.customer.api.CustomerService;
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
import com.stockflow.order.internal.domain.ReturnRefusalReason;
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
 * WF-05 BE-45 (Q-4, audit R-12): a RETURNED order whose returns all ended with no unit refunded - refused with a
 * reason, or nothing usable back - and with nothing else made refundable goes back to the delivery path
 * ({@code Order.returnsEndedWithoutRefund}, G1's X-8), never to PARTIALLY_REFUNDED; one with a unit refunded, even worth
 * nothing, or with money made refundable otherwise, keeps the refund path. The order is a mock: what is asserted is
 * the return side's decision, the order's own move is G1's.
 */
class ReturnWithoutRefundOrderStatusTest {

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
    private final ReturnRequestService service = new ReturnRequestService(returns, orders,
            mock(OrderEventPublisher.class), payments, mock(CustomerService.class), mock(FileTransfers.class),
            mock(StagedUploads.class), mock(TransactionOperations.class), POLICY,
            Clock.fixed(NOW, ZoneOffset.UTC), mock(DeliveryFailureService.class), mock(ReturnAllowances.class),
            mock(com.stockflow.inventory.api.CartonReturns.class));

    private final UUID orderId = UUID.randomUUID();
    private final Order order = mock(Order.class);

    private static Order delivered() {
        Order real = new Order(new OrderId(UUID.randomUUID()), OrderNumber.of(LocalDate.of(2026, 10, 15), 1), CUSTOMER,
                UUID.randomUUID(), List.of(new OrderLine(LINE, new Sku("CUP-12OZ"), 100, Money.vnd(2_000), null,
                List.of())), OrderStatus.DELIVERED, DELIVERED.minus(Duration.ofDays(10)), null, 3L);
        real.restoreLifecycle(null, null, null, DELIVERED.minus(Duration.ofDays(1)), DELIVERED, null, null, null, null);
        return real;
    }

    /** RESOLVED: 10 approved and back, {@code refunded} of them refunded worth {@code amount}. */
    private static ReturnRequest resolved(int refunded, long amount, ReturnRefusalReason refusal) {
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
        request.proposeRefund(SALES, NOW, Map.of(line.id(), refunded), real, null, amount == 0 && refunded > 0,
                refusal, null, Map.of());
        request.resolve(ACCOUNTANT, NOW, new ReturnRequest.MoneyOutcome(BigDecimal.valueOf(amount), BigDecimal.ZERO));
        return request;
    }

    private void given(List<ReturnRequest> all, RefundProgress progress) {
        when(order.status()).thenReturn(OrderStatus.RETURNED);
        when(order.id()).thenReturn(new OrderId(orderId));
        when(order.lines()).thenReturn(List.of());
        when(orders.findByIdForUpdate(new OrderId(orderId))).thenReturn(Optional.of(order));
        when(returns.findByOrder(orderId)).thenReturn(all);
        when(payments.refundProgress(orderId)).thenReturn(progress);
    }

    @Test
    @DisplayName("a refusal, nothing else refundable: back to the delivery path, not PARTIALLY_REFUNDED")
    void refusedGoesBackToTheDeliveryPath() {
        given(List.of(resolved(0, 0, ReturnRefusalReason.CUSTOMER_DAMAGE)), RefundProgress.NONE);

        service.followRefunds(orderId, ACCOUNTANT);

        verify(order).returnsEndedWithoutRefund(eq(ACCOUNTANT), any());
        verify(order, never()).refunded(anyBoolean(), any(), any(), anyString());
    }

    @Test
    @DisplayName("units refunded - worth money, or worth nothing on an order with nothing paid: the refund path")
    void unitsRefundedKeepTheRefundPath() {
        given(List.of(resolved(4, 8_000, null)), new RefundProgress(BigDecimal.ZERO, BigDecimal.ZERO));
        service.followRefunds(orderId, ACCOUNTANT);
        verify(order).refunded(anyBoolean(), eq(ACCOUNTANT), any(), eq("REFUNDED"));
        verify(order, never()).returnsEndedWithoutRefund(any(), any());
    }

    @Test
    @DisplayName("units refunded worth nothing (TH-2): a return all the same, the refund path")
    void unitsWorthNothing() {
        given(List.of(resolved(10, 0, null)), RefundProgress.NONE);
        service.followRefunds(orderId, ACCOUNTANT);
        verify(order).refunded(anyBoolean(), eq(ACCOUNTANT), any(), eq("REFUNDED"));
        verify(order, never()).returnsEndedWithoutRefund(any(), any());
    }

    @Test
    @DisplayName("a refusal on an order the carrier's return made refundable: refunded once that is paid out")
    void somethingElseWasRefundable() {
        given(List.of(resolved(0, 0, ReturnRefusalReason.NOT_DEFECTIVE)),
                new RefundProgress(BigDecimal.valueOf(50_000), BigDecimal.valueOf(50_000)));
        service.followRefunds(orderId, ACCOUNTANT);
        verify(order).refunded(anyBoolean(), eq(ACCOUNTANT), any(), eq("REFUNDED"));
        verify(order, never()).returnsEndedWithoutRefund(any(), any());
    }
}
