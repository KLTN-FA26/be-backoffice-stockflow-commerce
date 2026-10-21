package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.OrderDelivered;
import com.stockflow.contracts.OrderRemainderClosed;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.api.ShipmentHandover;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * G3-2 (SCRUM-239/246/247) on the aggregate alone: the warehouse and carrier steps, hold and resolve, split shipments
 * (decision G4-Q3) and their money, completion and closing. Every refusal is checked for its error code.
 */
class OrderLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");
    private static final OrderNumber NUMBER = OrderNumber.of(LocalDate.of(2026, 10, 19), 7);
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();
    private static final UUID S3 = UUID.randomUUID();

    /** 10 cups at 100 000 and 5 lids at 20 000: 1 100 000 VND. */
    private static Order order(PaymentTerm term) {
        Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(100_000), null),
                Order.line(new Sku("LID-90"), 5, Money.vnd(20_000), null)), NOW);
        order.applyTerms(term, term == PaymentTerm.DEPOSIT ? new BigDecimal("30") : null,
                term == PaymentTerm.CREDIT ? 30 : null);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        if (term == PaymentTerm.CREDIT) {
            order.submitOnCredit(true);
        } else {
            order.submit();
            order.recordPayment(term == PaymentTerm.PREPAID ? order.total() : Money.vnd(330_000), NOW);
        }
        order.release(HCM, "HCM", STAFF, NOW, java.util.Map.of());
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    private static Order picking(PaymentTerm term) {
        Order order = order(term);
        order.startPicking(STAFF, NOW);
        return order;
    }

    private static Order packed(PaymentTerm term) {
        Order order = picking(term);
        order.markPacked(NOW, STAFF);
        return order;
    }

    private static UUID cups(Order order) {
        return order.lines().get(0).id();
    }

    private static UUID lids(Order order) {
        return order.lines().get(1).id();
    }

    private static ShipmentHandover handover(UUID shipment, Object... lineAndQuantity) {
        List<ShipmentHandover.Line> lines = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < lineAndQuantity.length; i += 2) {
            lines.add(new ShipmentHandover.Line((UUID) lineAndQuantity[i], (Integer) lineAndQuantity[i + 1]));
        }
        return new ShipmentHandover(shipment, lines, NOW, STAFF);
    }

    private static ShipmentHandover everything(Order order, UUID shipment) {
        return handover(shipment, cups(order), 10, lids(order), 5);
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static List<OrderDelivered> deliveries(Order order) {
        return order.pullDomainEvents().stream().filter(OrderEvent.Delivered.class::isInstance)
                .map(e -> ((OrderEvent.Delivered) e).payload()).toList();
    }

    @Nested
    @DisplayName("the warehouse")
    class Warehouse {

        @Test
        @DisplayName("READY_TO_FULFILL → PICKING → PACKED, each idempotent, with who and when in the history")
        void pickAndPack() {
            Order order = order(PaymentTerm.PREPAID);
            assertThat(order.startPicking(STAFF, NOW)).isTrue();
            assertThat(order.startPicking(STAFF, NOW)).isFalse();
            assertThat(order.markPacked(NOW, STAFF)).isTrue();
            assertThat(order.markPacked(NOW, STAFF)).isFalse();
            assertThat(order.status()).isEqualTo(OrderStatus.PACKED);
            assertThat(order.packedAt()).isEqualTo(NOW);
            assertThat(order.pullTransitions()).extracting(OrderTransition::to, OrderTransition::actorId)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(OrderStatus.PICKING, STAFF),
                            org.assertj.core.groups.Tuple.tuple(OrderStatus.PACKED, STAFF));
        }

        @Test
        @DisplayName("printed goods only: READY_TO_FULFILL → PACKED, no picking (17 §4.2.6)")
        void printedOnlyIsPackedStraightAway() {
            Order order = order(PaymentTerm.PREPAID);
            order.markPacked(NOW, STAFF);
            assertThat(order.status()).isEqualTo(OrderStatus.PACKED);
        }

        @Test
        @DisplayName("CONFIRMED is not picked: released first (17 BR-02)")
        void confirmedIsNotPicked() {
            Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                    Order.line(new Sku("CUP"), 1, Money.vnd(1_000), null)), NOW);
            order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
            order.submit();
            order.recordPayment(order.total(), NOW);
            assertThat(code(catchThrowable(() -> order.startPicking(STAFF, NOW))))
                    .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
        }

        @Test
        @DisplayName("a packed order is not cancelled; a picking one is, by staff")
        void cancellationUpToPicking() {
            Order picking = picking(PaymentTerm.PREPAID);
            picking.cancel(CancellationReasonCode.OUT_OF_STOCK, null, null, STAFF, NOW);
            assertThat(picking.status()).isEqualTo(OrderStatus.CANCELLED);

            Order packed = packed(PaymentTerm.PREPAID);
            assertThat(code(catchThrowable(() -> packed.cancel(CancellationReasonCode.OUT_OF_STOCK, null, null, STAFF,
                    NOW)))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        }

        @Test
        @DisplayName("a cancelled order is not packed (ORDER_CANCELLED)")
        void cancelledIsNotPacked() {
            Order order = picking(PaymentTerm.PREPAID);
            order.cancel(CancellationReasonCode.OUT_OF_STOCK, null, null, STAFF, NOW);
            assertThat(code(catchThrowable(() -> order.markPacked(NOW, STAFF)))).isEqualTo(ErrorCode.ORDER_CANCELLED);
        }
    }

    @Nested
    @DisplayName("hold and resolve")
    class Hold {

        @Test
        @DisplayName("held from PICKING, resolved back to PICKING; the reason and note go to the history")
        void heldAndResolved() {
            Order order = picking(PaymentTerm.PREPAID);
            order.pullTransitions();
            order.putOnHold(HoldReason.ADDRESS, "sai phường", STAFF, NOW);
            assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);
            assertThat(order.holdReason()).isEqualTo(HoldReason.ADDRESS);
            assertThat(order.statusBeforeHold()).isEqualTo(OrderStatus.PICKING);
            order.resolveHold("khách xác nhận địa chỉ", STAFF, NOW);
            assertThat(order.status()).isEqualTo(OrderStatus.PICKING);
            assertThat(order.holdReason()).isNull();
            assertThat(order.statusBeforeHold()).isNull();
            assertThat(order.pullTransitions()).extracting(OrderTransition::reason)
                    .containsExactly("ADDRESS: sai phường", "HOLD_RESOLVED_ADDRESS: khách xác nhận địa chỉ");
        }

        @Test
        @DisplayName("a second hold, a hold after shipping, OTHER without a note, a hand-raised CREDIT: refused")
        void refusals() {
            Order order = picking(PaymentTerm.PREPAID);
            order.putOnHold(HoldReason.INVENTORY, null, STAFF, NOW);
            assertThat(code(catchThrowable(() -> order.putOnHold(HoldReason.ADDRESS, null, STAFF, NOW))))
                    .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
            Order shipped = packed(PaymentTerm.PREPAID);
            shipped.markShipped(NOW, STAFF);
            assertThat(code(catchThrowable(() -> shipped.putOnHold(HoldReason.ADDRESS, null, STAFF, NOW))))
                    .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
            Order other = picking(PaymentTerm.PREPAID);
            assertThat(code(catchThrowable(() -> other.putOnHold(HoldReason.OTHER, " ", STAFF, NOW))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(catchThrowable(() -> other.putOnHold(HoldReason.CREDIT, "x", STAFF, NOW)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("resolving: not on hold is ORDER_NOT_ON_HOLD, a credit hold ORDER_CREDIT_HOLD_USE_APPROVAL, a note is required")
        void resolveRefusals() {
            Order order = picking(PaymentTerm.PREPAID);
            assertThat(code(catchThrowable(() -> order.resolveHold("x", STAFF, NOW)))).isEqualTo(ErrorCode.ORDER_NOT_ON_HOLD);
            order.putOnHold(HoldReason.FRAUD, null, STAFF, NOW);
            assertThat(code(catchThrowable(() -> order.resolveHold(" ", STAFF, NOW)))).isEqualTo(ErrorCode.VALIDATION_FAILED);

            Order credit = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                    Order.line(new Sku("CUP"), 1, Money.vnd(1_000), null)), NOW);
            credit.applyTerms(PaymentTerm.CREDIT, null, 30);
            credit.lines().forEach(line -> credit.attachReservations(line.id(), List.of(UUID.randomUUID())));
            credit.submitOnCredit(false);
            assertThat(credit.holdReason()).isEqualTo(HoldReason.CREDIT);
            assertThat(code(catchThrowable(() -> credit.resolveHold("ok", STAFF, NOW))))
                    .isEqualTo(ErrorCode.ORDER_CREDIT_HOLD_USE_APPROVAL);
            credit.approveCredit(STAFF, NOW);
            assertThat(credit.status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(credit.holdReason()).isNull();
        }

        @Test
        @DisplayName("a held order is not packed, handed over or delivered")
        void heldBlocksTheWarehouse() {
            Order order = picking(PaymentTerm.PREPAID);
            order.putOnHold(HoldReason.ADDRESS, null, STAFF, NOW);
            assertThat(code(catchThrowable(() -> order.markPacked(NOW, STAFF))))
                    .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
            assertThat(code(catchThrowable(() -> order.recordHandover(handover(S1, cups(order), 1)))))
                    .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
        }

        @Test
        @DisplayName("cancelling a held order clears the hold")
        void cancelHeld() {
            Order order = picking(PaymentTerm.PREPAID);
            order.putOnHold(HoldReason.CUSTOMER, "chờ khách", STAFF, NOW);
            order.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, null, STAFF, NOW);
            assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.holdReason()).isNull();
            assertThat(order.holdNote()).isNull();
        }
    }

    @Nested
    @DisplayName("whole-order handover and delivery (no shipments, G4-Q5)")
    class WholeOrder {

        @Test
        @DisplayName("PACKED → SHIPPED → DELIVERY_FAILED → DELIVERED; a credit order bills what it owes")
        void failedThenDelivered() {
            Order order = packed(PaymentTerm.CREDIT);
            order.markShipped(NOW, STAFF);
            assertThat(order.shippedAt()).isEqualTo(NOW);
            assertThat(order.markDeliveryFailed(DeliveryFailureReason.CUSTOMER_ABSENT, null, NOW, STAFF)).isTrue();
            assertThat(order.markDeliveryFailed(DeliveryFailureReason.CUSTOMER_ABSENT, null, NOW, STAFF)).isFalse();
            assertThat(order.deliveryFailureReason()).isEqualTo(DeliveryFailureReason.CUSTOMER_ABSENT);
            order.pullDomainEvents();
            order.markDelivered(NOW, STAFF);
            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(order.deliveryFailureReason()).isNull();
            assertThat(deliveries(order)).singleElement().satisfies(event -> {
                assertThat(event.shipmentId()).isNull();
                assertThat(event.billedAmount()).isEqualByComparingTo("1100000");
            });
            assertThat(order.billedAmount()).isEqualByComparingTo("1100000");
        }

        @Test
        @DisplayName("failed, then back at the warehouse: RETURNED, nothing billed")
        void returnedToOrigin() {
            Order order = packed(PaymentTerm.CREDIT);
            order.markShipped(NOW, STAFF);
            order.markDeliveryFailed(DeliveryFailureReason.REFUSED, null, NOW, STAFF);
            assertThat(order.markReturnedToOrigin(NOW, STAFF)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.RETURNED);
            assertThat(order.billedAmount()).isZero();
        }

        @Test
        @DisplayName("SHIPPED → CANCELLED is ORDER_NOT_CANCELLABLE (BR-031)")
        void shippedIsNotCancelled() {
            Order order = packed(PaymentTerm.PREPAID);
            order.markShipped(NOW, STAFF);
            assertThat(code(catchThrowable(() -> order.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, null,
                    STAFF, NOW)))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        }

        @Test
        @DisplayName("an order with shipments is not delivered as a whole (ORDER_DELIVERY_VIA_SHIPMENT)")
        void shipmentsOnlyPerShipment() {
            Order order = packed(PaymentTerm.PREPAID);
            order.recordHandover(everything(order, S1));
            assertThat(code(catchThrowable(() -> order.markDelivered(NOW, STAFF))))
                    .isEqualTo(ErrorCode.ORDER_DELIVERY_VIA_SHIPMENT);
            assertThat(code(catchThrowable(() -> order.markDeliveryFailed(DeliveryFailureReason.LOST, null, NOW, STAFF))))
                    .isEqualTo(ErrorCode.ORDER_DELIVERY_VIA_SHIPMENT);
        }

        @Test
        @DisplayName("an unpaid prepaid balance blocks the handover (G3-Q4)")
        void unpaidPrepaidBlocksHandover() {
            Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                    Order.line(new Sku("CUP"), 2, Money.vnd(1_000), null)), NOW);
            order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
            order.submit();
            order.recordPayment(order.total(), NOW);
            order.release(HCM, "HCM", STAFF, NOW, java.util.Map.of());
            order.markPacked(NOW, STAFF);
            assertThat(order.amountDueBeforeHandover()).isZero();
            // A prepaid order is confirmed only paid in full, so the gate can only bite if money were taken back -
            // it stays the one place the rule lives (deposit balances join it with G2-3).
            order.restorePaymentAndCancellation(BigDecimal.valueOf(1_000), null, null, null);
            assertThat(order.amountDueBeforeHandover()).isEqualByComparingTo("1000");
            assertThat(code(catchThrowable(() -> order.markShipped(NOW, STAFF)))).isEqualTo(ErrorCode.ORDER_BALANCE_NOT_PAID);
        }
    }

    @Nested
    @DisplayName("split shipments (G4-Q3)")
    class Split {

        @Test
        @DisplayName("part handed over from PICKING: PARTIALLY_FULFILLED; the rest packed: PACKED; handed over: SHIPPED")
        void partThenRest() {
            Order order = picking(PaymentTerm.PREPAID);
            order.recordHandover(handover(S1, cups(order), 6));
            assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            assertThat(order.outstandingUnits()).isEqualTo(9);
            assertThat(order.packedAt()).as("what was handed over was packed").isEqualTo(NOW);
            order.markPacked(NOW, STAFF);
            assertThat(order.status()).isEqualTo(OrderStatus.PACKED);
            order.recordHandover(handover(S2, cups(order), 4, lids(order), 5));
            assertThat(order.status()).isEqualTo(OrderStatus.SHIPPED);
            assertThat(order.shippedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("the same shipment again changes nothing; with other goods it is ORDER_SHIPMENT_CONFLICT")
        void handoverIdempotency() {
            Order order = packed(PaymentTerm.PREPAID);
            assertThat(order.recordHandover(handover(S1, cups(order), 6))).isTrue();
            assertThat(order.recordHandover(handover(S1, cups(order), 6))).isFalse();
            assertThat(code(catchThrowable(() -> order.recordHandover(handover(S1, cups(order), 5)))))
                    .isEqualTo(ErrorCode.ORDER_SHIPMENT_CONFLICT);
            assertThat(order.shipments()).hasSize(1);
        }

        @Test
        @DisplayName("more than is left, a foreign line, no lines, zero units: refused")
        void quantityRefusals() {
            Order order = packed(PaymentTerm.PREPAID);
            order.recordHandover(handover(S1, cups(order), 6));
            assertThat(code(catchThrowable(() -> order.recordHandover(handover(S2, cups(order), 5)))))
                    .isEqualTo(ErrorCode.ORDER_SHIPMENT_QUANTITY_EXCEEDED);
            assertThat(code(catchThrowable(() -> order.recordHandover(handover(S2, UUID.randomUUID(), 1)))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(code(catchThrowable(() -> order.recordHandover(handover(S2))))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(code(catchThrowable(() -> order.recordHandover(handover(S2, cups(order), 0)))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("everything handed over while still PICKING is refused: packed first")
        void everythingFromPickingNeedsPacking() {
            Order order = picking(PaymentTerm.PREPAID);
            assertThat(code(catchThrowable(() -> order.recordHandover(everything(order, S1)))))
                    .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
        }

        @Test
        @DisplayName("partial shipment then the rest closed: SHIPPED, then DELIVERED; the prepaid rest is refundable")
        void partThenCloseTheRest() {
            Order order = picking(PaymentTerm.PREPAID);
            order.recordHandover(handover(S1, cups(order), 6));
            order.pullDomainEvents();
            BigDecimal closed = order.closeRemainder(CancellationReasonCode.OUT_OF_STOCK, "hết hàng", STAFF, NOW);
            assertThat(closed).isEqualByComparingTo("500000");   // 4 cups + 5 lids
            assertThat(order.status()).isEqualTo(OrderStatus.SHIPPED);
            assertThat(order.lines().get(0).cancelledQuantity()).isEqualTo(4);
            assertThat(order.lines().get(1).cancelledQuantity()).isEqualTo(5);
            assertThat(order.billable()).isEqualByComparingTo("600000");
            OrderRemainderClosed event = order.pullDomainEvents().stream()
                    .filter(OrderEvent.RemainderClosed.class::isInstance)
                    .map(e -> ((OrderEvent.RemainderClosed) e).payload()).findFirst().orElseThrow();
            assertThat(event.refundableAmount()).isEqualByComparingTo("500000");
            assertThat(event.effectiveTotal()).isEqualByComparingTo("600000");
            assertThat(event.lines()).extracting(OrderRemainderClosed.Line::quantity).containsExactly(4, 5);
            order.recordShipmentDelivered(S1, NOW, STAFF);
            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(order.deliveredAt()).isEqualTo(NOW);
            assertThat(order.amountDue()).isZero();
        }

        @Test
        @DisplayName("closing the rest: only from PARTIALLY_FULFILLED; on hold it is resolved first")
        void closeRemainderRefusals() {
            Order order = picking(PaymentTerm.PREPAID);
            assertThat(code(catchThrowable(() -> order.closeRemainder(CancellationReasonCode.OUT_OF_STOCK, null, STAFF,
                    NOW)))).isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
            order.recordHandover(handover(S1, cups(order), 6));
            order.putOnHold(HoldReason.INVENTORY, "thiếu ly", STAFF, NOW);
            assertThat(code(catchThrowable(() -> order.closeRemainder(CancellationReasonCode.OUT_OF_STOCK, null, STAFF,
                    NOW)))).isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
            assertThat(code(catchThrowable(() -> order.closeRemainder(CancellationReasonCode.OTHER, null, STAFF,
                    NOW)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("one shipment delivered, another failed: DELIVERY_FAILED; redelivered: DELIVERED")
        void oneDeliveredOneFailedThenRedelivered() {
            Order order = packed(PaymentTerm.PREPAID);
            order.recordHandover(handover(S1, cups(order), 10));
            order.recordHandover(handover(S2, lids(order), 5));
            assertThat(order.status()).isEqualTo(OrderStatus.SHIPPED);
            order.recordShipmentDelivered(S1, NOW, STAFF);
            assertThat(order.status()).as("one still in transit").isEqualTo(OrderStatus.SHIPPED);
            order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.CUSTOMER_ABSENT, null, NOW, STAFF);
            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERY_FAILED);
            assertThat(order.deliveryFailureReason()).isEqualTo(DeliveryFailureReason.CUSTOMER_ABSENT);
            order.recordShipmentDelivered(S2, NOW, STAFF);
            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(order.deliveryFailureReason()).isNull();
        }

        @Test
        @DisplayName("one shipment delivered, another failed and returned: PARTIALLY_FULFILLED; the rest closed: DELIVERED")
        void oneDeliveredOneReturned() {
            Order order = packed(PaymentTerm.CREDIT);
            order.recordHandover(handover(S1, cups(order), 10));
            order.recordHandover(handover(S2, lids(order), 5));
            order.recordShipmentDelivered(S1, NOW, STAFF);
            order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.DAMAGED, null, NOW, STAFF);
            order.recordShipmentReturned(S2, NOW, STAFF);
            assertThat(order.status()).as("09 edge: giao một phần").isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            assertThat(order.outstandingUnits()).isEqualTo(5);
            assertThat(order.billedAmount()).as("only the delivered cups are billed").isEqualByComparingTo("1000000");
            order.closeRemainder(CancellationReasonCode.OUT_OF_STOCK, null, STAFF, NOW);
            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(order.billable()).isEqualByComparingTo("1000000");
            assertThat(order.unbilled()).isZero();
        }

        @Test
        @DisplayName("everything failed and came back: RETURNED")
        void allReturned() {
            Order order = packed(PaymentTerm.PREPAID);
            order.recordHandover(handover(S1, cups(order), 10));
            order.recordHandover(handover(S2, lids(order), 5));
            order.recordShipmentDeliveryFailed(S1, DeliveryFailureReason.WRONG_ADDRESS, null, NOW, STAFF);
            order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.WRONG_ADDRESS, null, NOW, STAFF);
            order.recordShipmentReturned(S1, NOW, STAFF);
            assertThat(order.status()).as("one still failed, not back").isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            order.recordShipmentReturned(S2, NOW, STAFF);
            assertThat(order.status()).isEqualTo(OrderStatus.RETURNED);
        }

        @Test
        @DisplayName("shipment outcomes: delivered is final, returned only after failing, unknown shipment is 404")
        void shipmentTransitions() {
            Order order = packed(PaymentTerm.PREPAID);
            order.recordHandover(everything(order, S1));
            assertThat(code(catchThrowable(() -> order.recordShipmentReturned(S1, NOW, STAFF))))
                    .isEqualTo(ErrorCode.ORDER_SHIPMENT_INVALID_TRANSITION);
            order.recordShipmentDelivered(S1, NOW, STAFF);
            assertThat(order.recordShipmentDelivered(S1, NOW, STAFF)).isFalse();
            assertThat(code(catchThrowable(() -> order.recordShipmentDeliveryFailed(S1, DeliveryFailureReason.LOST,
                    null, NOW, STAFF)))).isEqualTo(ErrorCode.ORDER_SHIPMENT_INVALID_TRANSITION);
            assertThat(code(catchThrowable(() -> order.recordShipmentDelivered(S3, NOW, STAFF))))
                    .isEqualTo(ErrorCode.ORDER_SHIPMENT_NOT_FOUND);
            assertThat(order.shipments()).singleElement()
                    .satisfies(s -> assertThat(s.status()).isEqualTo(OrderShipmentStatus.DELIVERED));
        }

        @Test
        @DisplayName("held while partly shipped: outcomes are recorded, the hold stays; resolving catches up")
        void holdWhilePartlyShipped() {
            Order order = picking(PaymentTerm.CREDIT);
            order.recordHandover(handover(S1, cups(order), 6));
            order.putOnHold(HoldReason.INVENTORY, "thiếu 4 ly", STAFF, NOW);
            assertThat(order.statusBeforeHold()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            order.pullDomainEvents();
            order.recordShipmentDelivered(S1, NOW, STAFF);
            assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);
            assertThat(deliveries(order)).singleElement()
                    .satisfies(e -> assertThat(e.billedAmount()).isEqualByComparingTo("600000"));
            assertThat(code(catchThrowable(() -> order.recordHandover(handover(S2, cups(order), 4)))))
                    .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
            order.resolveHold("đã có hàng", STAFF, NOW);
            assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        }

        @Test
        @DisplayName("resolving a hold of a partly shipped order whose shipment came back meanwhile: still partial")
        void resolveCatchesUp() {
            Order order = packed(PaymentTerm.PREPAID);
            order.recordHandover(handover(S1, cups(order), 10));
            order.recordShipmentDeliveryFailed(S1, DeliveryFailureReason.REFUSED, null, NOW, STAFF);
            order.recordShipmentReturned(S1, NOW, STAFF);
            assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            order.putOnHold(HoldReason.CUSTOMER, "hỏi khách", STAFF, NOW);
            order.resolveHold("khách huỷ phần còn lại", STAFF, NOW);
            order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, STAFF, NOW);
            assertThat(order.status()).as("nothing was delivered, every shipment came back").isEqualTo(OrderStatus.RETURNED);
        }

        @Test
        @DisplayName("a partly shipped order is not cancelled (a package was completed)")
        void partlyShippedIsNotCancelled() {
            Order order = packed(PaymentTerm.PREPAID);
            order.recordHandover(handover(S1, cups(order), 6));
            assertThat(code(catchThrowable(() -> order.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, null,
                    STAFF, NOW)))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        }
    }

    @Nested
    @DisplayName("money of split shipments")
    class Money_ {

        @Test
        @DisplayName("credit: each delivered shipment bills its goods; the order counts only what is unbilled")
        void creditBillsPerShipment() {
            Order order = packed(PaymentTerm.CREDIT);
            order.recordHandover(handover(S1, cups(order), 6));
            order.recordHandover(handover(S2, cups(order), 4, lids(order), 5));
            order.pullDomainEvents();
            order.recordShipmentDelivered(S1, NOW, STAFF);
            assertThat(deliveries(order)).singleElement().satisfies(e -> {
                assertThat(e.shipmentId()).isEqualTo(S1);
                assertThat(e.billedAmount()).isEqualByComparingTo("600000");
            });
            assertThat(order.unbilled()).isEqualByComparingTo("500000");
            // The customer pays the first receivable: it pays what was billed, the rest is still to bill.
            order.recordPayment(Money.vnd(600_000), NOW, true);
            assertThat(order.unbilled()).isEqualByComparingTo("500000");
            order.recordShipmentDelivered(S2, NOW, STAFF);
            assertThat(deliveries(order)).singleElement()
                    .satisfies(e -> assertThat(e.billedAmount()).isEqualByComparingTo("500000"));
            assertThat(order.unbilled()).isZero();
            assertThat(order.billedAmount()).isEqualByComparingTo("1100000");
        }

        @Test
        @DisplayName("credit: money paid ahead (outside receivables) is not billed again")
        void paidAheadIsNotBilled() {
            Order order = packed(PaymentTerm.CREDIT);
            order.recordPayment(Money.vnd(100_000), NOW, false);
            order.recordHandover(handover(S1, cups(order), 6));
            order.recordHandover(handover(S2, cups(order), 4, lids(order), 5));
            order.pullDomainEvents();
            order.recordShipmentDelivered(S1, NOW, STAFF);
            order.recordShipmentDelivered(S2, NOW, STAFF);
            assertThat(order.billedAmount()).isEqualByComparingTo("1000000");
            assertThat(order.unbilled()).isZero();
        }

        @Test
        @DisplayName("deposit + partial shipment: nothing billed, the balance is due; closing the rest leaves a refund only "
                + "above what was received")
        void depositAndPartialShipment() {
            Order order = picking(PaymentTerm.DEPOSIT);   // 330 000 of 1 100 000 received
            order.recordHandover(handover(S1, cups(order), 2));   // 200 000 of goods
            order.pullDomainEvents();
            order.recordShipmentDelivered(S1, NOW, STAFF);
            assertThat(deliveries(order)).singleElement()
                    .satisfies(e -> assertThat(e.billedAmount()).isZero());
            assertThat(order.amountDue()).isEqualByComparingTo("770000");
            order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, STAFF, NOW);
            assertThat(order.billable()).isEqualByComparingTo("200000");
            assertThat(order.amountDue()).isZero();
            assertThat(order.remainderRefundAmount()).as("330 000 received for 200 000 of goods")
                    .isEqualByComparingTo("130000");
            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        }

        @Test
        @DisplayName("a deposit order's balance does not block the handover until G2-3 brings its policy")
        void depositBalanceDoesNotBlockYet() {
            Order order = packed(PaymentTerm.DEPOSIT);
            assertThat(order.amountDue()).isEqualByComparingTo("770000");
            assertThat(order.amountDueBeforeHandover()).isZero();
            order.recordHandover(everything(order, S1));
            assertThat(order.status()).isEqualTo(OrderStatus.SHIPPED);
        }
    }

    @Nested
    @DisplayName("review of G3-2: money, stuck states, re-shipping")
    class Review {

        private Order creditPaidAhead(long ahead) {
            Order order = packed(PaymentTerm.CREDIT);
            order.recordPayment(Money.vnd(ahead), NOW, false);
            order.recordHandover(handover(S1, cups(order), 5));                  // 500 000
            order.recordHandover(handover(S2, cups(order), 5, lids(order), 5));  // 600 000
            return order;
        }

        @Test
        @DisplayName("paid ahead pays the earliest delivery; the later one comes back and the rest is closed: the "
                + "customer pays exactly for what arrived")
        void paidAheadPaysTheEarliestDelivery() {
            Order order = creditPaidAhead(600_000);
            order.pullDomainEvents();
            order.recordShipmentDelivered(S1, NOW, STAFF);
            assertThat(deliveries(order)).singleElement()
                    .satisfies(e -> assertThat(e.billedAmount()).as("covered by the money paid ahead").isZero());
            order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.REFUSED, null, NOW, STAFF);
            order.recordShipmentReturned(S2, NOW, STAFF);
            order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, STAFF, NOW);
            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(order.billedAmount()).isZero();
            assertThat(order.remainderRefundAmount()).as("600 000 paid for 500 000 delivered")
                    .isEqualByComparingTo("100000");
            assertThat(order.unbilled()).isZero();
        }

        @Test
        @DisplayName("paid ahead less than the first delivery: the rest of it is billed, nothing refunded")
        void paidAheadLessThanTheFirstDelivery() {
            Order order = creditPaidAhead(300_000);
            order.pullDomainEvents();
            order.recordShipmentDelivered(S1, NOW, STAFF);
            assertThat(deliveries(order)).singleElement()
                    .satisfies(e -> assertThat(e.billedAmount()).isEqualByComparingTo("200000"));
            order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.REFUSED, null, NOW, STAFF);
            order.recordShipmentReturned(S2, NOW, STAFF);
            assertThat(order.unbilled()).as("the returned goods may still ship").isEqualByComparingTo("600000");
            order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, STAFF, NOW);
            assertThat(order.billedAmount().add(order.paidAmount())).isEqualByComparingTo("500000");
            assertThat(order.remainderRefundAmount()).isZero();
        }

        @Test
        @DisplayName("deposit: one shipment delivered, the other back, the rest closed: the balance of what arrived "
                + "is due, nothing refunded")
        void depositDeliveredAndReturned() {
            Order order = packed(PaymentTerm.DEPOSIT);   // 330 000 received
            order.recordHandover(handover(S1, cups(order), 5));
            order.recordHandover(handover(S2, cups(order), 5, lids(order), 5));
            order.recordShipmentDelivered(S1, NOW, STAFF);
            order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.REFUSED, null, NOW, STAFF);
            order.recordShipmentReturned(S2, NOW, STAFF);
            order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, STAFF, NOW);
            assertThat(order.billable()).isEqualByComparingTo("500000");
            assertThat(order.amountDue()).isEqualByComparingTo("170000");
            assertThat(order.remainderRefundAmount()).isZero();
            assertThat(order.billedAmount()).isZero();
        }

        @Test
        @DisplayName("production finishing while the order is held: resolving goes to READY_TO_FULFILL")
        void productionFinishedWhileHeld() {
            UUID snapshot = UUID.randomUUID();
            Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                    Order.line(new Sku("CUP-PRINTED"), 10, Money.vnd(1_000), snapshot)), NOW);
            order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
            order.submit();
            order.recordPayment(order.total(), NOW);
            order.release(HCM, "HCM", STAFF, NOW, java.util.Map.of());
            assertThat(order.status()).isEqualTo(OrderStatus.IN_PRODUCTION);
            order.putOnHold(HoldReason.CUSTOMER, "chờ khách", STAFF, NOW);
            order.pullDomainEvents();
            assertThat(order.completeProduction(java.util.Map.of(order.lines().getFirst().id(), 10), "HCM", NOW))
                    .isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);
            assertThat(order.statusBeforeHold()).isEqualTo(OrderStatus.READY_TO_FULFILL);
            assertThat(order.pullDomainEvents()).hasAtLeastOneElementOfType(OrderEvent.Released.class);
            order.resolveHold("khách xác nhận", STAFF, NOW);
            assertThat(order.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        }

        @Test
        @DisplayName("a design hold is not resolved by the coordinator; reverification resolves it, or does nothing "
                + "when the order is not held, and refuses while it is held for something else")
        void designHold() {
            Order order = picking(PaymentTerm.PREPAID);
            order.putOnHold(HoldReason.DESIGN_MISMATCH, null, null, NOW);
            assertThat(code(catchThrowable(() -> order.resolveHold("ok", STAFF, NOW))))
                    .isEqualTo(ErrorCode.ORDER_DESIGN_HOLD_USE_REVERIFICATION);
            assertThat(order.resolveDesignHold("QC: khớp", STAFF, NOW)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.PICKING);
            assertThat(order.resolveDesignHold("QC: khớp", STAFF, NOW)).as("not held: nothing to do").isFalse();
            order.putOnHold(HoldReason.ADDRESS, null, STAFF, NOW);
            assertThat(code(catchThrowable(() -> order.resolveDesignHold("QC", STAFF, NOW))))
                    .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
            assertThat(code(catchThrowable(() -> order.putOnHold(HoldReason.DESIGN_MISMATCH, null, null, NOW))))
                    .as("a design mismatch on an order held for another reason is refused").isEqualTo(
                            ErrorCode.ORDER_INVALID_TRANSITION);
        }

        @Test
        @DisplayName("units that came back are shipped again: packed, handed over, SHIPPED - no dead end")
        void returnedUnitsShipAgain() {
            Order order = packed(PaymentTerm.PREPAID);
            order.recordHandover(handover(S1, cups(order), 10));
            order.recordHandover(handover(S2, lids(order), 5));
            order.recordShipmentDelivered(S1, NOW, STAFF);
            order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.WRONG_ADDRESS, null, NOW, STAFF);
            order.recordShipmentReturned(S2, NOW, STAFF);
            assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            order.markPacked(NOW, STAFF);
            assertThat(order.status()).isEqualTo(OrderStatus.PACKED);
            assertThat(code(catchThrowable(() -> order.recordHandover(handover(S3, lids(order), 6)))))
                    .isEqualTo(ErrorCode.ORDER_SHIPMENT_QUANTITY_EXCEEDED);
            order.recordHandover(handover(S3, lids(order), 5));
            assertThat(order.status()).isEqualTo(OrderStatus.SHIPPED);
            order.recordShipmentDelivered(S3, NOW, STAFF);
            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        }
    }

    @Nested
    @DisplayName("after delivery")
    class AfterDelivery {

        private Order delivered(PaymentTerm term) {
            Order order = packed(term);
            order.markShipped(NOW, STAFF);
            order.markDelivered(NOW, STAFF);
            return order;
        }

        @Test
        @DisplayName("COMPLETED after the return window, paid, no return open; CLOSED 30 days later; then nothing")
        void completeThenClose() {
            Order order = delivered(PaymentTerm.PREPAID);
            Duration week = Duration.ofDays(7);
            assertThat(order.complete(NOW.plus(Duration.ofDays(6)), week, false)).isFalse();
            assertThat(order.complete(NOW.plus(week), week, true)).as("a return is open").isFalse();
            assertThat(order.complete(NOW.plus(week), week, false)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.COMPLETED);
            assertThat(order.close(NOW.plus(Duration.ofDays(20)), Duration.ofDays(30))).isFalse();
            assertThat(order.close(NOW.plus(Duration.ofDays(40)), Duration.ofDays(30))).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.CLOSED);
            assertThat(order.closedAt()).isNotNull();
            assertThat(order.status().isTerminal()).isTrue();
        }

        @Test
        @DisplayName("a credit order still owing stays DELIVERED (17 edge)")
        void owingStaysDelivered() {
            Order order = delivered(PaymentTerm.CREDIT);
            assertThat(order.complete(NOW.plus(Duration.ofDays(30)), Duration.ofDays(7), false)).isFalse();
            order.recordPayment(Money.vnd(1_100_000), NOW, true);
            assertThat(order.complete(NOW.plus(Duration.ofDays(30)), Duration.ofDays(7), false)).isTrue();
        }

        @Test
        @DisplayName("RMA: DELIVERED → RETURN_REQUESTED → RETURNED → PARTIALLY_REFUNDED → CLOSED")
        void returnPath() {
            Order order = delivered(PaymentTerm.PREPAID);
            order.requestReturn(STAFF, NOW);
            order.markReturned(STAFF, NOW);
            order.markRefunded(true, STAFF, NOW);
            assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
            assertThat(order.close(NOW.plus(Duration.ofDays(31)), Duration.ofDays(30))).isTrue();
            assertThat(code(catchThrowable(() -> order.requestReturn(STAFF, NOW))))
                    .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
        }
    }
}
