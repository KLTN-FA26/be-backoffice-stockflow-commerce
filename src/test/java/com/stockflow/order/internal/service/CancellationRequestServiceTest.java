package com.stockflow.order.internal.service;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationFee;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.internal.domain.CancellationRequest;
import com.stockflow.order.internal.domain.CancellationRequestRepository;
import com.stockflow.order.internal.domain.CancellationRequestStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.repository.CancellationRequestSearch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.order.internal.service.OrderFixtures.NOW;
import static com.stockflow.order.internal.service.OrderFixtures.STAFF;
import static com.stockflow.order.internal.service.OrderFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * kltn-docs 17 §2, §4.4 (SCRUM-460): Sales decides a customer's cancellation request. Approving cancels the order in the
 * same transaction with whatever fee was agreed; rejecting needs a reason the customer can read; a decided request
 * is not decided twice.
 */
class CancellationRequestServiceTest {

    private final CancellationRequestRepository requests = mock(CancellationRequestRepository.class);
    private final OrderServiceImpl orders = mock(OrderServiceImpl.class);
    private final OrderService orderService = mock(OrderService.class);
    private final CancellationRequestService service = new CancellationRequestService(requests,
            mock(CancellationRequestSearch.class), orders, orderService, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID orderId = UUID.randomUUID();
    private final Order order = OrderFixtures.releasedPrepaid();
    private final OrderSummary summary = mock(OrderSummary.class);

    private CancellationRequest pending() {
        CancellationRequest request = CancellationRequest.open(orderId, UUID.randomUUID(),
                CancellationReasonCode.CUSTOMER_REQUEST, "wrong logo", NOW);
        when(requests.findById(request.id())).thenReturn(Optional.of(request));
        when(requests.save(any())).thenAnswer(call -> call.getArgument(0));
        when(orders.loadForUpdate(orderId)).thenReturn(order);
        when(orderService.findById(orderId)).thenReturn(Optional.of(summary));
        return request;
    }

    @Test
    @DisplayName("approving saves the decided request first, then cancels the order with the request's reason and share")
    void approvalCancelsTheOrderAfterSavingTheRequest() {
        CancellationRequest request = pending();

        OrderSummary result = service.approve(orderId, request.id(), new BigDecimal("30"), "agreed by phone", STAFF);

        assertThat(result).isSameAs(summary);
        assertThat(request.status()).isEqualTo(CancellationRequestStatus.APPROVED);
        assertThat(request.decidedBy()).isEqualTo(STAFF);
        assertThat(request.decidedAt()).isEqualTo(NOW);
        assertThat(request.retainedPercent()).isEqualByComparingTo("30");
        assertThat(request.decisionNote()).isEqualTo("agreed by phone");
        // Cancelling closes a still-pending request as EXPIRED, so the decision must be saved before the cancel.
        InOrder inOrder = inOrder(requests, orders);
        inOrder.verify(requests).save(request);
        ArgumentCaptor<CancelOrderCommand> command = ArgumentCaptor.forClass(CancelOrderCommand.class);
        inOrder.verify(orders).cancelLoaded(org.mockito.ArgumentMatchers.eq(order), command.capture());
        assertThat(command.getValue().reasonCode()).isEqualTo(CancellationReasonCode.CUSTOMER_REQUEST);
        assertThat(command.getValue().note()).isEqualTo("wrong logo");
        assertThat(command.getValue().retainedPercent()).isEqualByComparingTo("30");
        assertThat(command.getValue().cancelledBy()).isEqualTo(STAFF);
        assertThat(command.getValue().agreedFee()).isNull();
    }

    @Test
    @DisplayName("an agreed amount (decision Q2) is passed to the cancellation instead of a percentage")
    void approvalWithAnAgreedFee() {
        CancellationRequest request = pending();
        CancellationFee fee = new CancellationFee(new BigDecimal("500000"), "Printed blanks", "Plates and ink used");

        service.approve(orderId, request.id(), null, fee, null, STAFF);

        ArgumentCaptor<CancelOrderCommand> command = ArgumentCaptor.forClass(CancelOrderCommand.class);
        verify(orders).cancelLoaded(org.mockito.ArgumentMatchers.eq(order), command.capture());
        assertThat(command.getValue().agreedFee()).isEqualTo(fee);
        assertThat(command.getValue().retainedPercent()).isNull();
        assertThat(request.decisionNote()).isNull();
    }

    @Test
    @DisplayName("a request already decided cannot be approved again: ORDER_CANCELLATION_REQUEST_NOT_PENDING, no cancel")
    void aDecidedRequestIsNotDecidedTwice() {
        CancellationRequest request = pending();
        request.reject(STAFF, "too late", NOW);

        assertThat(codeOf(() -> service.approve(orderId, request.id(), null, null, STAFF)))
                .isEqualTo(ErrorCode.ORDER_CANCELLATION_REQUEST_NOT_PENDING);
        assertThat(codeOf(() -> service.reject(orderId, request.id(), "again", STAFF)))
                .isEqualTo(ErrorCode.ORDER_CANCELLATION_REQUEST_NOT_PENDING);
        verify(orders, never()).cancelLoaded(any(), any());
    }

    @Test
    @DisplayName("a request of another order is not found, whoever asks (CANCELLATION_REQUEST_NOT_FOUND)")
    void aRequestOfAnotherOrderIsNotFound() {
        CancellationRequest request = pending();

        assertThat(codeOf(() -> service.approve(UUID.randomUUID(), request.id(), null, null, STAFF)))
                .isEqualTo(ErrorCode.CANCELLATION_REQUEST_NOT_FOUND);
        assertThat(codeOf(() -> service.reject(UUID.randomUUID(), request.id(), "no", STAFF)))
                .isEqualTo(ErrorCode.CANCELLATION_REQUEST_NOT_FOUND);
        assertThat(codeOf(() -> service.reject(orderId, UUID.randomUUID(), "no", STAFF)))
                .isEqualTo(ErrorCode.CANCELLATION_REQUEST_NOT_FOUND);
        verify(requests, never()).save(any());
    }

    @Test
    @DisplayName("rejecting records who and why and leaves the order alone")
    void rejectionKeepsTheOrder() {
        CancellationRequest request = pending();

        CancellationRequest saved = service.reject(orderId, request.id(), "  Already printed  ", STAFF);

        assertThat(saved.status()).isEqualTo(CancellationRequestStatus.REJECTED);
        assertThat(saved.decidedBy()).isEqualTo(STAFF);
        assertThat(saved.decisionNote()).isEqualTo("Already printed");
        verify(orders, never()).cancelLoaded(any(), any());
    }

    @Test
    @DisplayName("a rejection must say why (VALIDATION_FAILED) and the request stays pending")
    void aRejectionNeedsAReason() {
        CancellationRequest request = pending();

        assertThat(codeOf(() -> service.reject(orderId, request.id(), " ", STAFF)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(request.status()).isEqualTo(CancellationRequestStatus.PENDING);
        verify(requests, never()).save(any());
    }
}
