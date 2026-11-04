package com.stockflow.payment.internal.service;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.payment.api.OrderPayables;
import com.stockflow.payment.api.PayableOrder;
import com.stockflow.payment.internal.domain.CustomerTransfer;
import com.stockflow.payment.internal.domain.ReceivableBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.payment.internal.service.PaymentFixtures.NOW;
import static com.stockflow.payment.internal.service.PaymentFixtures.allocation;
import static com.stockflow.payment.internal.service.PaymentFixtures.codeOf;
import static com.stockflow.payment.internal.service.PaymentFixtures.order;
import static com.stockflow.payment.internal.service.PaymentFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** kltn-docs 15 §4.1-4.2, BR-05: money paid to an order itself never takes it past what it is worth. */
class OrderAllocatorTest {

    private final ReceivableBook book = mock(ReceivableBook.class);
    private final OrderPayables orders = mock(OrderPayables.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final OrderAllocator allocator =
            new OrderAllocator(book, orders, events, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID customer = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    private void ledger(String collected, String receivablesOutstanding) {
        when(book.collectedFor(orderId)).thenReturn(new BigDecimal(collected));
        when(book.outstandingReceivablesOf(orderId)).thenReturn(new BigDecimal(receivablesOutstanding));
    }

    // ---- lockPayable

    @Test
    @DisplayName("lockPayable: an unknown order is NOT_FOUND")
    void unknownOrder() {
        when(orders.lockForPayment(orderId)).thenReturn(Optional.empty());
        assertThat(codeOf(() -> allocator.lockPayable(orderId, customer))).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("lockPayable: another customer's order is ALLOCATION_CUSTOMER_MISMATCH, so is an order with no customer")
    void otherCustomersOrder() {
        when(orders.lockForPayment(orderId))
                .thenReturn(Optional.of(order(orderId, UUID.randomUUID(), "CONFIRMED", "PREPAID", "100")));
        assertThat(codeOf(() -> allocator.lockPayable(orderId, customer)))
                .isEqualTo(ErrorCode.ALLOCATION_CUSTOMER_MISMATCH);

        when(orders.lockForPayment(orderId))
                .thenReturn(Optional.of(order(orderId, null, "CONFIRMED", "PREPAID", "100")));
        assertThat(codeOf(() -> allocator.lockPayable(orderId, customer)))
                .isEqualTo(ErrorCode.ALLOCATION_CUSTOMER_MISMATCH);
    }

    @Test
    @DisplayName("lockPayable: a cancelled order refuses with ALLOCATION_ORDER_CANCELLED (the money stays credit, 15 edge)")
    void cancelledOrder() {
        when(orders.lockForPayment(orderId))
                .thenReturn(Optional.of(order(orderId, customer, "CANCELLED", "PREPAID", "100")));
        assertThat(codeOf(() -> allocator.lockPayable(orderId, customer)))
                .isEqualTo(ErrorCode.ALLOCATION_ORDER_CANCELLED);
    }

    @Test
    @DisplayName("lockPayable: a credit order is paid on its receivables, never by allocation (15 §4.3)")
    void creditOrder() {
        when(orders.lockForPayment(orderId))
                .thenReturn(Optional.of(order(orderId, customer, "CONFIRMED", "CREDIT", "100")));
        assertThat(codeOf(() -> allocator.lockPayable(orderId, customer)))
                .isEqualTo(ErrorCode.ALLOCATION_ORDER_NOT_PAYABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "COMPLETED", "CLOSED", "RETURNED", "REFUNDED"})
    @DisplayName("lockPayable: a draft or settled order takes no payment")
    void settledOrDraft(String status) {
        when(orders.lockForPayment(orderId))
                .thenReturn(Optional.of(order(orderId, customer, status, "PREPAID", "100")));
        assertThat(codeOf(() -> allocator.lockPayable(orderId, customer)))
                .isEqualTo(ErrorCode.ALLOCATION_ORDER_NOT_PAYABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_PAYMENT", "CONFIRMED", "IN_PRODUCTION", "READY_TO_FULFILL", "PICKING",
            "PARTIALLY_FULFILLED", "PACKED", "ON_HOLD", "SHIPPED", "DELIVERY_FAILED", "DELIVERED"})
    @DisplayName("lockPayable: every live status of a prepaid or deposit order takes payment")
    void payableStatuses(String status) {
        PayableOrder order = order(orderId, customer, status, "DEPOSIT", "100");
        when(orders.lockForPayment(orderId)).thenReturn(Optional.of(order));
        assertThat(allocator.lockPayable(orderId, customer)).isSameAs(order);
    }

    // ---- mayStillTake

    @Test
    @DisplayName("mayStillTake = billable - collected - receivables outstanding - COD in transit")
    void mayStillTake() {
        ledger("300", "200");
        PayableOrder order = new PayableOrder(orderId, "SO-1", customer, "SHIPPED", "DEPOSIT", "ON_DELIVERY", "VND",
                new BigDecimal("1000"), new BigDecimal("300"), new BigDecimal("150"), null, false);
        assertThat(allocator.mayStillTake(order)).isEqualByComparingTo("350");
    }

    @Test
    @DisplayName("mayStillTake: a null COD-in-transit counts as zero, and the room is never below zero")
    void mayStillTakeFloorsAtZero() {
        ledger("900", "300");
        PayableOrder order = new PayableOrder(orderId, "SO-1", customer, "CONFIRMED", "PREPAID", null, "VND",
                new BigDecimal("1000"), null, null, null, true);
        assertThat(allocator.mayStillTake(order)).isEqualByComparingTo("0");
    }

    // ---- allocate

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5"})
    @DisplayName("allocate: an allocation brings money (zero or negative is VALIDATION_FAILED)")
    void nonPositiveAmount(String amount) {
        CustomerTransfer t = transfer(customer, "1000");
        PayableOrder o = order(orderId, customer, "CONFIRMED", "PREPAID", "1000");
        assertThat(codeOf(() -> allocator.allocate(t, o, new BigDecimal(amount), null)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(codeOf(() -> allocator.allocate(t, o, null, null))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("allocate: transfer and order must be in the same currency")
    void currencyMismatch() {
        CustomerTransfer t = transfer(customer, "1000");
        PayableOrder usd = new PayableOrder(orderId, "SO-1", customer, "CONFIRMED", "PREPAID", null, "USD",
                new BigDecimal("1000"), null, BigDecimal.ZERO, null, true);
        assertThat(codeOf(() -> allocator.allocate(t, usd, BigDecimal.TEN, null)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(t.unallocatedAmount()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("allocate: more than the order may still take is ALLOCATION_EXCEEDS_AMOUNT_DUE, nothing is recorded")
    void exceedsAmountDue() {
        ledger("700", "0");
        CustomerTransfer t = transfer(customer, "1000");
        PayableOrder o = order(orderId, customer, "CONFIRMED", "PREPAID", "1000");

        assertThat(codeOf(() -> allocator.allocate(t, o, new BigDecimal("300.01"), null)))
                .isEqualTo(ErrorCode.ALLOCATION_EXCEEDS_AMOUNT_DUE);
        assertThat(t.unallocatedAmount()).isEqualByComparingTo("1000");
        verify(book, never()).recordAllocation(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("allocate: more than the transfer holds is ALLOCATION_EXCEEDS_TRANSFER even when the order has room")
    void exceedsTransfer() {
        ledger("0", "0");
        CustomerTransfer t = transfer(customer, "100");
        PayableOrder o = order(orderId, customer, "CONFIRMED", "PREPAID", "1000");
        assertThat(codeOf(() -> allocator.allocate(t, o, new BigDecimal("101"), null)))
                .isEqualTo(ErrorCode.ALLOCATION_EXCEEDS_TRANSFER);
        verify(book, never()).recordAllocation(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("allocate: exactly the room left is accepted, takes it from the transfer and announces PaymentCaptured "
            + "(ORDER_ALLOCATION, BANK_TRANSFER) after commit")
    void allocatesExactlyTheRoom() {
        ledger("700", "0");
        CustomerTransfer t = transfer(customer, "1000");
        PayableOrder o = order(orderId, customer, "CONFIRMED", "PREPAID", "1000");
        UUID by = UUID.randomUUID();
        ReceivableBook.Allocation recorded = allocation(t.id(), orderId, null, "300");
        when(book.recordAllocation(t.id(), orderId, null, new BigDecimal("300"), NOW, by)).thenReturn(recorded);

        ReceivableBook.Allocation result = allocator.allocate(t, o, new BigDecimal("300"), by);

        assertThat(result).isSameAs(recorded);
        assertThat(t.unallocatedAmount()).isEqualByComparingTo("700");
        ArgumentCaptor<PaymentCaptured> event = ArgumentCaptor.forClass(PaymentCaptured.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().orderId()).isEqualTo(orderId);
        assertThat(event.getValue().paymentId()).isEqualTo(recorded.id());
        assertThat(event.getValue().amount()).isEqualByComparingTo("300");
        assertThat(event.getValue().currency()).isEqualTo("VND");
        assertThat(event.getValue().method()).isEqualTo(com.stockflow.contracts.PaymentMethod.BANK_TRANSFER);
        assertThat(event.getValue().source()).isEqualTo("ORDER_ALLOCATION");
        verify(book).recordAllocation(eq(t.id()), eq(orderId), isNull(), any(), eq(NOW), eq(by));
    }

    @Test
    @DisplayName("allocate: receivables outstanding and COD in transit are billed already, a transfer does not pay them twice")
    void doesNotPayBilledMoneyTwice() {
        ledger("0", "400");
        CustomerTransfer t = transfer(customer, "1000");
        PayableOrder o = new PayableOrder(orderId, "SO-1", customer, "SHIPPED", "DEPOSIT", "ON_DELIVERY", "VND",
                new BigDecimal("1000"), new BigDecimal("300"), new BigDecimal("500"), null, false);
        assertThat(allocator.mayStillTake(o)).isEqualByComparingTo("100");
        assertThat(codeOf(() -> allocator.allocate(t, o, new BigDecimal("101"), null)))
                .isEqualTo(ErrorCode.ALLOCATION_EXCEEDS_AMOUNT_DUE);
    }

    @Test
    @DisplayName("allocate: a fractional amount keeps its scale (no rounding of money)")
    void fractionalAmount() {
        ledger("0", "0");
        CustomerTransfer t = transfer(customer, "100.50");
        PayableOrder o = order(orderId, customer, "CONFIRMED", "PREPAID", "100.50");
        when(book.recordAllocation(any(), any(), any(), any(), any(), any()))
                .thenReturn(allocation(t.id(), orderId, null, "100.50"));
        allocator.allocate(t, o, new BigDecimal("100.50"), null);
        assertThat(t.unallocatedAmount()).isEqualByComparingTo("0");
    }
}
