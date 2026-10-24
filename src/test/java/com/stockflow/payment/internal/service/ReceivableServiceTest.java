package com.stockflow.payment.internal.service;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderDelivered;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.payment.api.OrderPayables;
import com.stockflow.payment.internal.domain.CustomerTransfer;
import com.stockflow.payment.internal.domain.Receivable;
import com.stockflow.payment.internal.domain.ReceivableBook;
import com.stockflow.payment.internal.domain.ReceivableStatus;
import com.stockflow.payment.internal.service.ReceivableService.OrderPayment;
import com.stockflow.payment.internal.service.ReceivableService.RecordTransferCommand;
import com.stockflow.payment.internal.service.ReceivableService.TransferResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.payment.internal.service.PaymentFixtures.NOW;
import static com.stockflow.payment.internal.service.PaymentFixtures.TODAY;
import static com.stockflow.payment.internal.service.PaymentFixtures.codeOf;
import static com.stockflow.payment.internal.service.PaymentFixtures.order;
import static com.stockflow.payment.internal.service.PaymentFixtures.receivable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SCRUM-431, kltn-docs 15 §4.3, BR-04, BR-07, BR-09: receivables of delivered credit orders, the transfers that pay
 * them (earliest due first unless the customer says which) and the overdue sweep.
 */
class ReceivableServiceTest {

    private final ReceivableBook book = mock(ReceivableBook.class);
    private final OrderPayables orders = mock(OrderPayables.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final OrderAllocator orderAllocator = new OrderAllocator(book, orders, events, clock);
    private final ReceivableService service = new ReceivableService(book, null, orderAllocator, orders, events, clock);

    private final UUID customer = UUID.randomUUID();
    private final UUID by = UUID.randomUUID();

    @BeforeEach
    void stubs() {
        when(book.save(any(CustomerTransfer.class))).thenAnswer(call -> call.getArgument(0));
        when(book.save(any(Receivable.class))).thenAnswer(call -> call.getArgument(0));
        when(book.lockUnpaid(any())).thenReturn(new ArrayList<>());
        when(book.lockWithCredit(any())).thenReturn(new ArrayList<>());
        when(book.recordAllocation(any(), any(), any(), any(), any(), any())).thenAnswer(call ->
                new ReceivableBook.Allocation(UUID.randomUUID(), call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), call.getArgument(3), NOW, by, null, null));
    }

    private Receivable savedReceivable() {
        ArgumentCaptor<Receivable> saved = ArgumentCaptor.forClass(Receivable.class);
        verify(book).save(saved.capture());
        return saved.getValue();
    }

    // ------------------------------------------------------------------ delivery

    @Nested
    class Delivery {

        private OrderDelivered delivered(String term, String total, String paid, Integer days, Instant at,
                                         UUID shipment, String billed, String policy) {
            return new OrderDelivered(UUID.randomUUID(), "SO-1", customer, term, new BigDecimal(total),
                    paid == null ? null : new BigDecimal(paid), "VND", days, at, shipment,
                    billed == null ? null : new BigDecimal(billed), policy);
        }

        @Test
        @DisplayName("15 §4.3 step 2: a delivered credit order bills what it still owes, due delivery day + days to pay")
        void creditOrderBecomesAReceivable() {
            OrderDelivered event = delivered("CREDIT", "12000000", "2000000", 30, NOW, null, null, null);

            service.orderDelivered(event);

            ArgumentCaptor<Receivable> saved = ArgumentCaptor.forClass(Receivable.class);
            verify(book).save(saved.capture());
            Receivable r = saved.getValue();
            assertThat(r.amount()).isEqualByComparingTo("10000000");
            assertThat(r.kind()).isEqualTo("CREDIT_ORDER");
            assertThat(r.dueDate()).isEqualTo(TODAY.plusDays(30));
            assertThat(r.status()).isEqualTo(ReceivableStatus.OPEN);
            assertThat(r.carrierCollected()).isFalse();
            assertThat(r.shipmentId()).isNull();
        }

        @Test
        @DisplayName("the delivery day is a Saigon calendar day: 18:00Z on the 22nd is already the 23rd in Vietnam")
        void deliveryDayIsSaigonDay() {
            Instant lateEvening = Instant.parse("2026-10-22T18:00:00Z");
            service.orderDelivered(delivered("CREDIT", "100", "0", 0, lateEvening, null, null, null));
            assertThat(savedReceivable().dueDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        }

        @Test
        @DisplayName("an event without days to pay is due on the delivery day")
        void noCreditDays() {
            service.orderDelivered(delivered("CREDIT", "100", null, null, NOW, null, null, null));
            Receivable r = savedReceivable();
            assertThat(r.dueDate()).isEqualTo(TODAY);
            assertThat(r.amount()).isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("G3-2: a delivered shipment bills what the order billed for it, tied to the shipment")
        void splitShipmentBillsBilledAmount() {
            UUID shipment = UUID.randomUUID();
            service.orderDelivered(delivered("CREDIT", "1000", "0", 10, NOW, shipment, "400", null));
            Receivable r = savedReceivable();
            assertThat(r.amount()).isEqualByComparingTo("400");
            assertThat(r.shipmentId()).isEqualTo(shipment);
        }

        @Test
        @DisplayName("idempotent: a redelivered event finds the receivable (per order, or per shipment) and bills nothing")
        void idempotent() {
            OrderDelivered whole = delivered("CREDIT", "100", "0", 0, NOW, null, null, null);
            when(book.findReceivableOfOrder(whole.orderId())).thenReturn(Optional.of(receivable(customer, "100", TODAY)));
            service.orderDelivered(whole);

            UUID shipment = UUID.randomUUID();
            OrderDelivered split = delivered("CREDIT", "100", "0", 0, NOW, shipment, "50", null);
            when(book.findReceivableOfShipment(split.orderId(), shipment))
                    .thenReturn(Optional.of(receivable(customer, "50", TODAY)));
            service.orderDelivered(split);

            verify(book, never()).save(any(Receivable.class));
        }

        @Test
        @DisplayName("a credit order already paid in full (or billing nothing) opens no receivable")
        void nothingOwed() {
            service.orderDelivered(delivered("CREDIT", "100", "100", 30, NOW, null, null, null));
            service.orderDelivered(delivered("CREDIT", "100", "0", 30, NOW, UUID.randomUUID(), "0", null));
            verify(book, never()).save(any(Receivable.class));
        }

        @Test
        @DisplayName("a prepaid order, and an event without a customer, are not billed")
        void notBilled() {
            service.orderDelivered(delivered("PREPAID", "100", "100", null, NOW, null, null, null));
            service.orderDelivered(new OrderDelivered(UUID.randomUUID(), "SO-2", null, "CREDIT", BigDecimal.TEN,
                    BigDecimal.ZERO, "VND", 0, NOW));
            verify(book, never()).save(any(Receivable.class));
        }

        @Test
        @DisplayName("BR-08 / G2-3: a deposit order whose balance the carrier collects bills a carrier-collected "
                + "DEPOSIT_BALANCE due on the delivery day, never allocated from the customer's credit")
        void carrierCollectedBalance() {
            OrderDelivered event = delivered("DEPOSIT", "1000000", "230000", 30, NOW, null, "770000", "ON_DELIVERY");

            service.orderDelivered(event);

            Receivable r = savedReceivable();
            assertThat(r.carrierCollected()).isTrue();
            assertThat(r.kind()).isEqualTo("DEPOSIT_BALANCE");
            assertThat(r.amount()).isEqualByComparingTo("770000");
            assertThat(r.dueDate()).as("due the delivery day, the credit days do not apply").isEqualTo(TODAY);
            verify(book, never()).lockWithCredit(any());
        }

        @Test
        @DisplayName("BALCREDIT: a deposit order whose balance goes on credit bills a customer DEPOSIT_BALANCE due after "
                + "the days to pay, and the customer's credit pays it first")
        void balanceOnCredit() {
            OrderDelivered event = delivered("DEPOSIT", "1000", "300", 15, NOW, null, "700", "ON_CREDIT");
            CustomerTransfer credit = CustomerTransfer.record(customer, "FT-C", new BigDecimal("200"), "VND", TODAY, by,
                    NOW, null);
            when(book.lockWithCredit(customer)).thenReturn(new ArrayList<>(List.of(credit)));
            when(book.lockUnpaid(customer)).thenAnswer(call -> {
                ArgumentCaptor<Receivable> saved = ArgumentCaptor.forClass(Receivable.class);
                verify(book).save(saved.capture());
                return new ArrayList<>(List.of(saved.getValue()));
            });

            service.orderDelivered(event);

            ArgumentCaptor<Receivable> saved = ArgumentCaptor.forClass(Receivable.class);
            verify(book, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
            Receivable r = saved.getAllValues().get(0);
            assertThat(r.kind()).isEqualTo("DEPOSIT_BALANCE");
            assertThat(r.carrierCollected()).isFalse();
            assertThat(r.dueDate()).isEqualTo(TODAY.plusDays(15));
            assertThat(r.paidAmount()).as("200 of credit paid it first").isEqualByComparingTo("200");
            assertThat(r.status()).isEqualTo(ReceivableStatus.PARTIALLY_PAID);
            assertThat(credit.unallocatedAmount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a credit order's delivery is paid first from the customer's unallocated credit, oldest transfer first")
        void creditPaysTheNewReceivableFirst() {
            OrderDelivered event = delivered("CREDIT", "500", "0", 30, NOW, null, null, null);
            CustomerTransfer older = CustomerTransfer.record(customer, "FT-1", new BigDecimal("200"), "VND", TODAY, by,
                    NOW, null);
            CustomerTransfer newer = CustomerTransfer.record(customer, "FT-2", new BigDecimal("500"), "VND", TODAY, by,
                    NOW, null);
            when(book.lockWithCredit(customer)).thenReturn(new ArrayList<>(List.of(older, newer)));
            when(book.lockUnpaid(customer)).thenAnswer(call -> {
                ArgumentCaptor<Receivable> saved = ArgumentCaptor.forClass(Receivable.class);
                verify(book).save(saved.capture());
                return new ArrayList<>(List.of(saved.getValue()));
            });

            service.orderDelivered(event);

            assertThat(older.unallocatedAmount()).isEqualByComparingTo("0");
            assertThat(newer.unallocatedAmount()).isEqualByComparingTo("200");
            verify(events, org.mockito.Mockito.times(2)).publishEvent(any(PaymentCaptured.class));
        }

        @Test
        @DisplayName("a deposit order delivered with its balance unpaid (to be paid before delivery) bills what the "
                + "ledger says is still owed, measured on payment's own ledger, not on the event")
        void balanceDeliveredUnpaid() {
            OrderDelivered event = delivered("DEPOSIT", "1000", "1000", 0, NOW, null, null, "BEFORE_DELIVERY");
            when(orders.find(event.orderId())).thenReturn(
                    Optional.of(order(event.orderId(), customer, "DELIVERED", "DEPOSIT", "900")));
            when(book.collectedFor(event.orderId())).thenReturn(new BigDecimal("300"));
            when(book.outstandingReceivablesOf(event.orderId())).thenReturn(new BigDecimal("100"));

            service.orderDelivered(event);

            ArgumentCaptor<Receivable> saved = ArgumentCaptor.forClass(Receivable.class);
            verify(book, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
            Receivable r = saved.getAllValues().get(0);
            assertThat(r.amount()).as("billable 900 - collected 300 - receivables 100").isEqualByComparingTo("500");
            assertThat(r.kind()).isEqualTo("DEPOSIT_BALANCE");
            assertThat(r.dueDate()).isEqualTo(TODAY);
        }

        @Test
        @DisplayName("a deposit order delivered fully paid before delivery bills nothing")
        void balancePaidBeforeDelivery() {
            OrderDelivered event = delivered("DEPOSIT", "1000", "1000", 0, NOW, null, null, "BEFORE_DELIVERY");
            when(orders.find(event.orderId())).thenReturn(
                    Optional.of(order(event.orderId(), customer, "DELIVERED", "DEPOSIT", "1000")));
            when(book.collectedFor(event.orderId())).thenReturn(new BigDecimal("1000"));
            when(book.outstandingReceivablesOf(event.orderId())).thenReturn(BigDecimal.ZERO);

            service.orderDelivered(event);

            verify(book, never()).save(any(Receivable.class));
        }
    }

    // ------------------------------------------------------------------ cancellation fee

    @Nested
    class CancellationFee {

        private OrderCancelled cancelled(UUID customerId, BigDecimal owed) {
            return new OrderCancelled(UUID.randomUUID(), "SO-9", customerId, "CONFIRMED", "CUSTOMER_REQUEST", null,
                    BigDecimal.ZERO, BigDecimal.ZERO, "VND", by, Instant.parse("2026-10-22T18:30:00Z"), owed);
        }

        @Test
        @DisplayName("Q6a: an agreed fee the money received did not cover is owed from the cancellation day (Saigon)")
        void feeIsOwed() {
            service.cancellationOwed(cancelled(customer, new BigDecimal("1500000")));
            Receivable r = savedReceivable();
            assertThat(r.kind()).isEqualTo("CANCELLATION_FEE");
            assertThat(r.amount()).isEqualByComparingTo("1500000");
            assertThat(r.dueDate()).isEqualTo(LocalDate.of(2026, 10, 23));
            assertThat(r.paidAmount()).as("nothing is collected here").isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("nothing owed, no customer: no receivable")
        void nothingOwed() {
            service.cancellationOwed(cancelled(customer, BigDecimal.ZERO));
            service.cancellationOwed(cancelled(customer, null));
            service.cancellationOwed(cancelled(null, BigDecimal.TEN));
            verify(book, never()).save(any(Receivable.class));
        }

        @Test
        @DisplayName("idempotent: a redelivered event finds the fee already open")
        void idempotent() {
            OrderCancelled event = cancelled(customer, BigDecimal.TEN);
            when(book.findReceivableOfOrder(event.orderId())).thenReturn(Optional.of(
                    Receivable.openCancellationFee(event.orderId(), customer, BigDecimal.TEN, "VND", NOW, TODAY)));
            service.cancellationOwed(event);
            verify(book, never()).save(any(Receivable.class));
        }

        @Test
        @DisplayName("an order that already has another kind of receivable is not billed twice: it needs the accountant")
        void otherReceivableExists() {
            OrderCancelled event = cancelled(customer, BigDecimal.TEN);
            when(book.findReceivableOfOrder(event.orderId())).thenReturn(Optional.of(
                    Receivable.open(event.orderId(), customer, BigDecimal.TEN, "VND", NOW, TODAY)));
            assertThatThrownBy(() -> service.cancellationOwed(event)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CREDIT_ORDER");
        }
    }

    // ------------------------------------------------------------------ recording a transfer

    @Nested
    class RecordTransfer {

        private RecordTransferCommand command(String amount, List<UUID> allocateTo, List<OrderPayment> toOrders) {
            return new RecordTransferCommand(customer, "FT-100", new BigDecimal(amount), "VND", TODAY, allocateTo,
                    null, by, toOrders);
        }

        private Receivable unpaid(String amount, int dueInDays) {
            return receivable(customer, amount, TODAY.plusDays(dueInDays));
        }

        @Test
        @DisplayName("BR-07: a statement reference already recorded is TRANSFER_REFERENCE_ALREADY_RECORDED")
        void duplicateReference() {
            when(book.referenceRecorded("FT-100")).thenReturn(true);
            assertThat(codeOf(() -> service.recordTransfer(command("100", null, null))))
                    .isEqualTo(ErrorCode.TRANSFER_REFERENCE_ALREADY_RECORDED);
            verify(book, never()).save(any(CustomerTransfer.class));
        }

        @Test
        @DisplayName("BR-07: losing the race on the unique reference is TRANSFER_REFERENCE_ALREADY_RECORDED too")
        void referenceRace() {
            when(book.save(any(CustomerTransfer.class))).thenThrow(new DataIntegrityViolationException("uk"));
            assertThat(codeOf(() -> service.recordTransfer(command("100", null, null))))
                    .isEqualTo(ErrorCode.TRANSFER_REFERENCE_ALREADY_RECORDED);
        }

        @Test
        @DisplayName("BR-09: with nothing named, the earliest due receivable is paid first, then the next")
        void earliestDueFirst() {
            Receivable first = unpaid("600", 1);
            Receivable second = unpaid("600", 5);
            when(book.lockUnpaid(customer)).thenReturn(new ArrayList<>(List.of(first, second)));

            TransferResult result = service.recordTransfer(command("900", null, null));

            assertThat(first.status()).isEqualTo(ReceivableStatus.PAID);
            assertThat(second.paidAmount()).isEqualByComparingTo("300");
            assertThat(second.status()).isEqualTo(ReceivableStatus.PARTIALLY_PAID);
            assertThat(result.allocations()).hasSize(2);
            assertThat(result.transfer().unallocatedAmount()).isEqualByComparingTo("0");
            verify(events, org.mockito.Mockito.times(2)).publishEvent(any(PaymentCaptured.class));
        }

        @Test
        @DisplayName("an overpayment pays every receivable and the excess stays the customer's credit")
        void excessIsCredit() {
            Receivable only = unpaid("250.25", 0);
            when(book.lockUnpaid(customer)).thenReturn(new ArrayList<>(List.of(only)));

            TransferResult result = service.recordTransfer(command("1000", null, null));

            assertThat(only.status()).isEqualTo(ReceivableStatus.PAID);
            assertThat(result.transfer().unallocatedAmount()).isEqualByComparingTo("749.75");
            verify(book, org.mockito.Mockito.atLeastOnce()).save(result.transfer());
        }

        @Test
        @DisplayName("a transfer with no receivable to pay is all credit and announces nothing")
        void noReceivables() {
            TransferResult result = service.recordTransfer(command("500", null, null));
            assertThat(result.allocations()).isEmpty();
            assertThat(result.transfer().unallocatedAmount()).isEqualByComparingTo("500");
            assertThat(result.transfer().currency()).isEqualTo("VND");
            verify(events, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("a missing currency defaults to VND")
        void defaultCurrency() {
            TransferResult result = service.recordTransfer(new RecordTransferCommand(customer, "FT-9",
                    BigDecimal.TEN, null, TODAY, null, null, by));
            assertThat(result.transfer().currency()).isEqualTo("VND");
        }

        @Test
        @DisplayName("a receivable in another currency than the transfer is skipped, not paid")
        void currencyMismatchIsSkipped() {
            Receivable usd = Receivable.open(UUID.randomUUID(), customer, new BigDecimal("100"), "USD", NOW, TODAY);
            when(book.lockUnpaid(customer)).thenReturn(new ArrayList<>(List.of(usd)));
            TransferResult result = service.recordTransfer(command("100", null, null));
            assertThat(usd.paidAmount()).isEqualByComparingTo("0");
            assertThat(result.transfer().unallocatedAmount()).isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("BR-09: the customer names the receivable: it is paid, the earlier one left alone")
        void namedReceivable() {
            Receivable earlier = unpaid("500", 1);
            Receivable named = unpaid("500", 9);
            when(book.lockUnpaid(customer)).thenReturn(new ArrayList<>(List.of(earlier, named)));
            when(book.findReceivable(named.id())).thenReturn(Optional.of(named));

            service.recordTransfer(command("300", List.of(named.id()), null));

            assertThat(named.paidAmount()).isEqualByComparingTo("300");
            assertThat(earlier.paidAmount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("naming an unknown receivable is RECEIVABLE_NOT_FOUND; another customer's is ALLOCATION_CUSTOMER_MISMATCH")
        void namedReceivableGuards() {
            UUID missing = UUID.randomUUID();
            when(book.findReceivable(missing)).thenReturn(Optional.empty());
            assertThat(codeOf(() -> service.recordTransfer(command("1", List.of(missing), null))))
                    .isEqualTo(ErrorCode.RECEIVABLE_NOT_FOUND);

            Receivable foreign = receivable(UUID.randomUUID(), "10", TODAY);
            when(book.findReceivable(foreign.id())).thenReturn(Optional.of(foreign));
            assertThat(codeOf(() -> service.recordTransfer(command("1", List.of(foreign.id()), null))))
                    .isEqualTo(ErrorCode.ALLOCATION_CUSTOMER_MISMATCH);
            verify(book, never()).save(any(CustomerTransfer.class));
        }

        @Test
        @DisplayName("09 §2: a receivable the carrier collects cannot be named in the customer's transfer")
        void carrierCollectedCannotBeNamed() {
            Receivable cod = Receivable.openCarrierCollected(UUID.randomUUID(), customer, BigDecimal.TEN, "VND", NOW,
                    TODAY);
            when(book.findReceivable(cod.id())).thenReturn(Optional.of(cod));
            assertThat(codeOf(() -> service.recordTransfer(command("10", List.of(cod.id()), null))))
                    .isEqualTo(ErrorCode.RECEIVABLE_CARRIER_COLLECTED);
        }

        @Test
        @DisplayName("a named receivable that is already paid (not in the unpaid set) takes nothing")
        void namedButAlreadyPaid() {
            Receivable paid = unpaid("10", 0);
            paid.allocate(BigDecimal.TEN, NOW);
            when(book.findReceivable(paid.id())).thenReturn(Optional.of(paid));
            TransferResult result = service.recordTransfer(command("50", List.of(paid.id()), null));
            assertThat(result.allocations()).isEmpty();
            assertThat(result.transfer().unallocatedAmount()).isEqualByComparingTo("50");
        }

        @Test
        @DisplayName("G2-3: a transfer that names orders pays them, and the rest stays credit (no receivable is auto-paid)")
        void paysNamedOrders() {
            UUID orderId = UUID.randomUUID();
            when(orders.lockForPayment(orderId)).thenReturn(
                    Optional.of(order(orderId, customer, "PENDING_PAYMENT", "PREPAID", "600")));
            when(book.collectedFor(orderId)).thenReturn(BigDecimal.ZERO);
            when(book.outstandingReceivablesOf(orderId)).thenReturn(BigDecimal.ZERO);
            Receivable untouched = unpaid("100", 0);
            when(book.lockUnpaid(customer)).thenReturn(new ArrayList<>(List.of(untouched)));

            TransferResult result = service.recordTransfer(
                    command("1000", null, List.of(new OrderPayment(orderId, new BigDecimal("600")))));

            assertThat(result.allocations()).hasSize(1);
            assertThat(result.allocations().get(0).paysOrder()).isTrue();
            assertThat(result.transfer().unallocatedAmount()).isEqualByComparingTo("400");
            assertThat(untouched.paidAmount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("orders and receivables named together: the order first, then the named receivable from the rest")
        void ordersThenReceivables() {
            UUID orderId = UUID.randomUUID();
            when(orders.lockForPayment(orderId)).thenReturn(
                    Optional.of(order(orderId, customer, "CONFIRMED", "PREPAID", "600")));
            when(book.collectedFor(orderId)).thenReturn(BigDecimal.ZERO);
            when(book.outstandingReceivablesOf(orderId)).thenReturn(BigDecimal.ZERO);
            Receivable named = unpaid("1000", 2);
            when(book.lockUnpaid(customer)).thenReturn(new ArrayList<>(List.of(named)));
            when(book.findReceivable(named.id())).thenReturn(Optional.of(named));

            TransferResult result = service.recordTransfer(
                    command("1000", List.of(named.id()), List.of(new OrderPayment(orderId, new BigDecimal("600")))));

            assertThat(named.paidAmount()).isEqualByComparingTo("400");
            assertThat(result.transfer().unallocatedAmount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("an order named twice is VALIDATION_FAILED")
        void orderNamedTwice() {
            UUID orderId = UUID.randomUUID();
            assertThat(codeOf(() -> service.recordTransfer(command("100", null,
                    List.of(new OrderPayment(orderId, BigDecimal.ONE), new OrderPayment(orderId, BigDecimal.TEN))))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("orders asking for more than the transfer brings: ALLOCATION_EXCEEDS_TRANSFER")
        void ordersAskForMoreThanTheTransfer() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            for (UUID id : List.of(a, b)) {
                when(orders.lockForPayment(id)).thenReturn(
                        Optional.of(order(id, customer, "CONFIRMED", "PREPAID", "600")));
                when(book.collectedFor(id)).thenReturn(BigDecimal.ZERO);
                when(book.outstandingReceivablesOf(id)).thenReturn(BigDecimal.ZERO);
            }
            assertThat(codeOf(() -> service.recordTransfer(command("1000", null, List.of(
                    new OrderPayment(a, new BigDecimal("600")), new OrderPayment(b, new BigDecimal("600")))))))
                    .isEqualTo(ErrorCode.ALLOCATION_EXCEEDS_TRANSFER);
        }

        @Test
        @DisplayName("a named order of another customer is refused before any money is recorded")
        void orderOfAnotherCustomer() {
            UUID orderId = UUID.randomUUID();
            when(orders.lockForPayment(orderId)).thenReturn(
                    Optional.of(order(orderId, UUID.randomUUID(), "CONFIRMED", "PREPAID", "600")));
            assertThat(codeOf(() -> service.recordTransfer(
                    command("100", null, List.of(new OrderPayment(orderId, BigDecimal.TEN))))))
                    .isEqualTo(ErrorCode.ALLOCATION_CUSTOMER_MISMATCH);
            verify(book, never()).save(any(CustomerTransfer.class));
        }
    }

    // ------------------------------------------------------------------ overdue

    @Nested
    class Overdue {

        @Test
        @DisplayName("15 §4.3 step 4: the sweep marks receivables due before today (Saigon) and reports how many")
        void marksOverdue() {
            when(book.markOverdue(LocalDate.of(2026, 10, 22))).thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));
            assertThat(service.markOverdue()).isEqualTo(2);
        }

        @Test
        @DisplayName("late evening UTC is the next Saigon day: the sweep uses the business date")
        void usesBusinessDate() {
            ReceivableService late = new ReceivableService(book, null, orderAllocator, orders, events,
                    Clock.fixed(Instant.parse("2026-10-22T18:00:00Z"), ZoneOffset.UTC));
            when(book.markOverdue(LocalDate.of(2026, 10, 23))).thenReturn(List.of(UUID.randomUUID()));
            assertThat(late.markOverdue()).isEqualTo(1);
        }

        @Test
        @DisplayName("nothing overdue: zero")
        void none() {
            when(book.markOverdue(any())).thenReturn(List.of());
            assertThat(service.markOverdue()).isZero();
        }

        @Test
        @DisplayName("the nightly job runs the sweep")
        void jobRunsTheSweep() {
            ReceivableService sweep = mock(ReceivableService.class);
            new ReceivableOverdueJob(sweep).markOverdueReceivables();
            verify(sweep).markOverdue();
        }
    }

    // ------------------------------------------------------------------ reads

    @Test
    @DisplayName("an unknown receivable is RECEIVABLE_NOT_FOUND; a known one is returned")
    void receivableLookup() {
        UUID missing = UUID.randomUUID();
        when(book.findReceivable(missing)).thenReturn(Optional.empty());
        assertThat(codeOf(() -> service.receivable(missing))).isEqualTo(ErrorCode.RECEIVABLE_NOT_FOUND);

        Receivable r = receivable(customer, "10", TODAY);
        when(book.findReceivable(r.id())).thenReturn(Optional.of(r));
        assertThat(service.receivable(r.id())).isSameAs(r);
    }

    @Test
    @DisplayName("the allocations of a receivable come from the ledger")
    void allocationsOf() {
        UUID id = UUID.randomUUID();
        when(book.allocationsOfReceivable(id)).thenReturn(List.of());
        assertThat(service.allocationsOf(id)).isEmpty();
        verify(book).allocationsOfReceivable(id);
        verify(book, never()).referenceRecorded(anyString());
    }
}
