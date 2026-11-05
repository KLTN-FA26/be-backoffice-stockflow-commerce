package com.stockflow.order.internal.service;

import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentFailed;
import com.stockflow.contracts.PaymentMethod;
import com.stockflow.contracts.PaymentReversed;
import com.stockflow.contracts.ReleaseReason;
import com.stockflow.contracts.StockReservationReleased;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.payment.api.PayableOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.order.internal.service.OrderFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The order side of the events payment and inventory publish (kltn-docs 14 BR-07, 15 BR-02, BR-06), and what it tells payment. */
class OrderEventListenersTest {

    private final OrderServiceImpl orders = mock(OrderServiceImpl.class);

    @Nested
    @DisplayName("payment events")
    class PaymentEvents {

        private final PaymentEventListener listener = new PaymentEventListener(orders);
        private final UUID order = UUID.randomUUID();
        private final UUID payment = UUID.randomUUID();

        @Test
        @DisplayName("a captured payment is a plain payment: not a receivable, not a fee")
        void plainCapture() {
            listener.on(new PaymentCaptured(order, payment, new BigDecimal("330000"), "VND", PaymentMethod.BANK_TRANSFER));

            verify(orders).recordPayment(order, payment, new BigDecimal("330000"), "VND", false, false);
        }

        @Test
        @DisplayName("an allocation to a receivable is counted as money of a receivable, not a fee")
        void receivableAllocation() {
            listener.on(new PaymentCaptured(order, payment, BigDecimal.TEN, "VND", PaymentMethod.BANK_TRANSFER,
                    "RECEIVABLE_ALLOCATION"));

            verify(orders).recordPayment(order, payment, BigDecimal.TEN, "VND", true, false);
        }

        @Test
        @DisplayName("an allocation to a closure fee's receivable is both a receivable's money and a fee's")
        void feeReceivableAllocation() {
            listener.on(new PaymentCaptured(order, payment, BigDecimal.TEN, "VND", PaymentMethod.BANK_TRANSFER,
                    "FEE_RECEIVABLE_ALLOCATION"));

            verify(orders).recordPayment(order, payment, BigDecimal.TEN, "VND", true, true);
        }

        @Test
        @DisplayName("an unknown source is treated as a plain payment")
        void unknownSource() {
            listener.on(new PaymentCaptured(order, payment, BigDecimal.TEN, "VND", PaymentMethod.BANK_TRANSFER,
                    "SOMETHING_ELSE"));

            verify(orders).recordPayment(order, payment, BigDecimal.TEN, "VND", false, false);
        }

        @Test
        @DisplayName("a reversal carries its allocation and reversal ids in the reason, and who and when")
        void reversal() {
            UUID reversal = UUID.randomUUID();
            UUID allocation = UUID.randomUUID();
            UUID by = UUID.randomUUID();

            listener.on(new PaymentReversed(order, reversal, allocation, BigDecimal.TEN, "VND",
                    "FEE_RECEIVABLE_ALLOCATION", by, NOW));

            verify(orders).reversePayment(order, reversal, BigDecimal.TEN, "VND", true, true,
                    "allocation %s reversed (reversal %s)".formatted(allocation, reversal), by, NOW);
        }

        @Test
        @DisplayName("a failed payment cancels the order with the payment's failure code")
        void failure() {
            listener.on(new PaymentFailed(order, payment, "CARD_DECLINED", "declined by the bank"));

            verify(orders).cancelAfterPaymentFailure(order, "CARD_DECLINED");
        }
    }

    @Nested
    @DisplayName("inventory events")
    class InventoryEvents {

        private final InventoryEventListener listener = new InventoryEventListener(orders);

        @Test
        @DisplayName("an expired reservation cancels the unpaid order (BR-07)")
        void expiredReservation() {
            UUID order = UUID.randomUUID();

            listener.on(new StockReservationReleased(order, "CUP-12OZ", 10, ReleaseReason.RESERVATION_EXPIRED));

            verify(orders).cancelAfterHoldExpired(order);
        }

        @Test
        @DisplayName("every other release reason is a consequence of something the order did and is ignored")
        void otherReasonsAreIgnored() {
            for (ReleaseReason reason : ReleaseReason.values()) {
                if (reason != ReleaseReason.RESERVATION_EXPIRED) {
                    listener.on(new StockReservationReleased(UUID.randomUUID(), "CUP-12OZ", 10, reason));
                }
            }

            verifyNoInteractions(orders);
        }
    }

    @Nested
    @DisplayName("what the order tells the money ledger")
    class Payables {

        private final OrderRepository repository = mock(OrderRepository.class);
        private final OrderPayablesAdapter adapter = new OrderPayablesAdapter(repository);

        @Test
        @DisplayName("a confirmed deposit order reports its billable amount, deposit threshold and that it may be held")
        void depositOrder() {
            Order order = OrderFixtures.draft(PaymentTerm.DEPOSIT, UUID.randomUUID());
            order.submit();
            order.recordPayment(new com.stockflow.common.domain.Money(new BigDecimal("330000"),
                    order.total().currency()), NOW);
            when(repository.findById(order.id())).thenReturn(Optional.of(order));

            PayableOrder payable = adapter.find(order.id().value()).orElseThrow();

            assertThat(payable.status()).isEqualTo(OrderStatus.CONFIRMED.name());
            assertThat(payable.paymentTerm()).isEqualTo("DEPOSIT");
            assertThat(payable.balancePolicy()).isEqualTo("ON_DELIVERY");
            assertThat(payable.currency()).isEqualTo("VND");
            assertThat(payable.billable()).isEqualByComparingTo("1100000");
            assertThat(payable.confirmationThreshold()).isEqualByComparingTo("330000");
            assertThat(payable.holdable()).isTrue();
        }

        @Test
        @DisplayName("a cancelled order has no confirmation threshold any more")
        void cancelledOrderHasNoThreshold() {
            Order order = OrderFixtures.draft(PaymentTerm.PREPAID, UUID.randomUUID());
            order.submit();
            order.cancel(com.stockflow.order.api.CancellationReasonCode.CUSTOMER_REQUEST, null, null, null, NOW);
            when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));

            PayableOrder payable = adapter.lockForPayment(order.id().value()).orElseThrow();

            assertThat(payable.status()).isEqualTo("CANCELLED");
            assertThat(payable.confirmationThreshold()).isNull();
            assertThat(payable.holdable()).isFalse();
        }

        @Test
        @DisplayName("an unknown order is empty, never an exception")
        void unknown() {
            when(repository.findById(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());

            assertThat(adapter.find(UUID.randomUUID())).isEmpty();
            verify(repository, never()).findByIdForUpdate(org.mockito.ArgumentMatchers.any());
        }
    }
}
