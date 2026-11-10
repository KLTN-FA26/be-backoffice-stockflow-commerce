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
 * (decision G4-Q3), completion and closing. Every refusal is checked for its error code.
 *
 * <p>Since 4B-10 the order counts no money: what each delivery bills, what is paid ahead or refunded on a closure is
 * the contract's installments' (payment: {@code OrderDeliveredListener}, {@code OrderRemainderClosedListener}). What
 * stays here is what the order is handed - whether its installments still owe ({@code complete}, {@code close}) and what
 * they owe before the goods leave (the handover gate).</p>
 */
class OrderLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");
    private static final OrderNumber NUMBER = OrderNumber.of(LocalDate.of(2026, 10, 19), 7);
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();
    private static final UUID S3 = UUID.randomUUID();

    /**
     * 10 cups at 100 000 and 5 lids at 20 000: 1 100 000 VND, its contract signed with nothing due on signing - CONFIRMED
     * - shipping from HCM and released there.
     */
    private static Order order() {
        Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(100_000), null),
                Order.line(new Sku("LID-90"), 5, Money.vnd(20_000), null)), NOW);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        // Split shipments throughout: Sales allowed them (PACK-PARTIAL); OrderPartialShipmentTest covers the refusals.
        order.decidePartialShipment(true);
        order.sendContract(STAFF, NOW);
        order.contractSigned(false, false, STAFF, NOW);
        order.chooseShippingWarehouse(HCM, true, STAFF, NOW);
        order.release(HCM, "HCM", STAFF, NOW);
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    private static Order picking() {
        Order order = order();
        order.startPicking(STAFF, NOW);
        return order;
    }

    private static Order packed() {
        Order order = picking();
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
            Order order = order();
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
            Order order = order();
            order.markPacked(NOW, STAFF);
            assertThat(order.status()).isEqualTo(OrderStatus.PACKED);
        }

        @Test
        @DisplayName("CONFIRMED is not picked: released first (17 BR-02)")
        void confirmedIsNotPicked() {
            Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                    Order.line(new Sku("CUP"), 1, Money.vnd(1_000), null)), NOW);
            order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
            order.sendContract(STAFF, NOW);
            order.contractSigned(false, false, STAFF, NOW);
            assertThat(code(catchThrowable(() -> order.startPicking(STAFF, NOW))))
                    .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
        }

        @Test
        @DisplayName("a packed order is not cancelled; a picking one is, by staff")
        void cancellationUpToPicking() {
            Order picking = picking();
            picking.cancel(CancellationReasonCode.OUT_OF_STOCK, null, null, STAFF, NOW);
            assertThat(picking.status()).isEqualTo(OrderStatus.CANCELLED);

            Order packed = packed();
            assertThat(code(catchThrowable(() -> packed.cancel(CancellationReasonCode.OUT_OF_STOCK, null, null, STAFF,
                    NOW)))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        }

        @Test
        @DisplayName("a cancelled order is not packed (ORDER_CANCELLED)")
        void cancelledIsNotPacked() {
            Order order = picking();
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
            Order order = picking();
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
            Order order = picking();
            order.putOnHold(HoldReason.INVENTORY, null, STAFF, NOW);
            assertThat(code(catchThrowable(() -> order.putOnHold(HoldReason.ADDRESS, null, STAFF, NOW))))
                    .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
            Order shipped = packed();
            shipped.markShipped(NOW, STAFF);
            assertThat(code(catchThrowable(() -> shipped.putOnHold(HoldReason.ADDRESS, null, STAFF, NOW))))
                    .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
            Order other = picking();
            assertThat(code(catchThrowable(() -> other.putOnHold(HoldReason.OTHER, " ", STAFF, NOW))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(catchThrowable(() -> other.putOnHold(HoldReason.CREDIT, "x", STAFF, NOW)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("resolving: not on hold is ORDER_NOT_ON_HOLD, a credit hold ORDER_CREDIT_HOLD_USE_APPROVAL, a note is required")
        void resolveRefusals() {
            Order order = picking();
            assertThat(code(catchThrowable(() -> order.resolveHold("x", STAFF, NOW)))).isEqualTo(ErrorCode.ORDER_NOT_ON_HOLD);
            order.putOnHold(HoldReason.FRAUD, null, STAFF, NOW);
            assertThat(code(catchThrowable(() -> order.resolveHold(" ", STAFF, NOW)))).isEqualTo(ErrorCode.VALIDATION_FAILED);

            Order credit = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                    Order.line(new Sku("CUP"), 1, Money.vnd(1_000), null)), NOW);
            credit.lines().forEach(line -> credit.attachReservations(line.id(), List.of(UUID.randomUUID())));
            // 15 BR-11: signed while what it pays after delivery does not fit the customer's credit.
            credit.sendContract(STAFF, NOW);
            credit.contractSigned(true, false, STAFF, NOW);
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
            Order order = picking();
            order.putOnHold(HoldReason.ADDRESS, null, STAFF, NOW);
            assertThat(code(catchThrowable(() -> order.markPacked(NOW, STAFF))))
                    .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
            assertThat(code(catchThrowable(() -> order.recordHandover(handover(S1, cups(order), 1)))))
                    .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
        }

        @Test
        @DisplayName("cancelling a held order clears the hold")
        void cancelHeld() {
            Order order = picking();
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
        @DisplayName("PACKED → SHIPPED → DELIVERY_FAILED → DELIVERED, announced once delivered")
        void failedThenDelivered() {
            Order order = packed();
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
                assertThat(event.orderTotal()).isEqualByComparingTo("1100000");
                assertThat(event.billedAmount()).as("nothing is billed on the order since 4B-10").isNull();
            });
        }

        @Test
        @DisplayName("failed, then back at the warehouse: RETURNED, nothing announced as delivered")
        void returnedToOrigin() {
            Order order = packed();
            order.markShipped(NOW, STAFF);
            order.markDeliveryFailed(DeliveryFailureReason.REFUSED, null, NOW, STAFF);
            assertThat(order.markReturnedToOrigin(NOW, STAFF)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.RETURNED);
            assertThat(deliveries(order)).isEmpty();
        }

        @Test
        @DisplayName("SHIPPED → CANCELLED is ORDER_NOT_CANCELLABLE (BR-031)")
        void shippedIsNotCancelled() {
            Order order = packed();
            order.markShipped(NOW, STAFF);
            assertThat(code(catchThrowable(() -> order.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, null,
                    STAFF, NOW)))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        }

        @Test
        @DisplayName("an order with shipments is not delivered as a whole (ORDER_DELIVERY_VIA_SHIPMENT)")
        void shipmentsOnlyPerShipment() {
            Order order = packed();
            order.recordHandover(everything(order, S1));
            assertThat(code(catchThrowable(() -> order.markDelivered(NOW, STAFF))))
                    .isEqualTo(ErrorCode.ORDER_DELIVERY_VIA_SHIPMENT);
            assertThat(code(catchThrowable(() -> order.markDeliveryFailed(DeliveryFailureReason.LOST, null, NOW, STAFF))))
                    .isEqualTo(ErrorCode.ORDER_DELIVERY_VIA_SHIPMENT);
        }

        @Test
        @DisplayName("what its installments owe before delivery blocks the handover (15 BR-08, G3-Q4), shipment or whole")
        void unpaidInstallmentsBlockTheHandover() {
            Order whole = packed();
            assertThat(code(catchThrowable(() -> whole.markShipped(NOW, STAFF, new BigDecimal("1000")))))
                    .isEqualTo(ErrorCode.ORDER_BALANCE_NOT_PAID);
            assertThat(whole.status()).isEqualTo(OrderStatus.PACKED);
            assertThat(whole.markShipped(NOW, STAFF, BigDecimal.ZERO)).isTrue();

            Order split = packed();
            assertThat(code(catchThrowable(() -> split.recordHandover(handover(S1, cups(split), 6),
                    new BigDecimal("1000"))))).isEqualTo(ErrorCode.ORDER_BALANCE_NOT_PAID);
            assertThat(split.shipments()).isEmpty();
            assertThat(split.recordHandover(handover(S1, cups(split), 6), BigDecimal.ZERO)).isTrue();
        }
    }

    @Nested
    @DisplayName("split shipments (G4-Q3)")
    class Split {

        @Test
        @DisplayName("part handed over from PICKING: PARTIALLY_FULFILLED; the rest packed: PACKED; handed over: SHIPPED")
        void partThenRest() {
            Order order = picking();
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
            Order order = packed();
            assertThat(order.recordHandover(handover(S1, cups(order), 6))).isTrue();
            assertThat(order.recordHandover(handover(S1, cups(order), 6))).isFalse();
            assertThat(code(catchThrowable(() -> order.recordHandover(handover(S1, cups(order), 5)))))
                    .isEqualTo(ErrorCode.ORDER_SHIPMENT_CONFLICT);
            assertThat(order.shipments()).hasSize(1);
        }

        @Test
        @DisplayName("more than is left, a foreign line, no lines, zero units: refused")
        void quantityRefusals() {
            Order order = packed();
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
            Order order = picking();
            assertThat(code(catchThrowable(() -> order.recordHandover(everything(order, S1)))))
                    .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
        }

        @Test
        @DisplayName("partial shipment then the rest closed: SHIPPED, then DELIVERED; the closure says what it took off")
        void partThenCloseTheRest() {
            Order order = picking();
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
            assertThat(event.closedAmount()).isEqualByComparingTo("500000");
            assertThat(event.effectiveTotal()).isEqualByComparingTo("600000");
            assertThat(event.refundableAmount()).as("the money is the installments', settled by payment (4B-9)")
                    .isEqualByComparingTo("0");
            assertThat(event.closureId()).isEqualTo(order.lastClosureId());
            assertThat(event.lines()).extracting(OrderRemainderClosed.Line::quantity).containsExactly(4, 5);
            order.recordShipmentDelivered(S1, NOW, STAFF);
            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(order.deliveredAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("closing the rest: only from PARTIALLY_FULFILLED; on hold it is resolved first")
        void closeRemainderRefusals() {
            Order order = picking();
            assertThat(code(catchThrowable(() -> order.closeRemainder(CancellationReasonCode.OUT_OF_STOCK, null, STAFF,
                    NOW)))).isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
            order.recordHandover(handover(S1, cups(order), 6));
            order.putOnHold(HoldReason.INVENTORY, "thiếu ly", STAFF, NOW);
            assertThat(code(catchThrowable(() -> order.closeRemainder(CancellationReasonCode.OUT_OF_STOCK, null, STAFF,
                    NOW)))).isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
            assertThat(code(catchThrowable(() -> order.closeRemainder(CancellationReasonCode.OTHER, null, STAFF,
                    NOW)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            order.resolveHold("có ly", STAFF, NOW);
            assertThat(code(catchThrowable(() -> order.closeRemainder(CancellationReasonCode.REPLACEMENT_WITHDRAWN,
                    null, STAFF, NOW)))).as("only a replacement is closed as withdrawn")
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("one shipment delivered, another failed: DELIVERY_FAILED; redelivered: DELIVERED")
        void oneDeliveredOneFailedThenRedelivered() {
            Order order = packed();
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
            Order order = packed();
            order.recordHandover(handover(S1, cups(order), 10));
            order.recordHandover(handover(S2, lids(order), 5));
            order.recordShipmentDelivered(S1, NOW, STAFF);
            order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.DAMAGED, null, NOW, STAFF);
            order.recordShipmentReturned(S2, NOW, STAFF);
            assertThat(order.status()).as("09 edge: giao một phần").isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            assertThat(order.outstandingUnits()).isEqualTo(5);
            order.closeRemainder(CancellationReasonCode.OUT_OF_STOCK, null, STAFF, NOW);
            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(order.billable()).as("only the delivered cups are what it is worth now")
                    .isEqualByComparingTo("1000000");
            assertThat(order.closedAmount()).isEqualByComparingTo("100000");
        }

        @Test
        @DisplayName("everything failed and came back: RETURNED")
        void allReturned() {
            Order order = packed();
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
            Order order = packed();
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
            Order order = picking();
            order.recordHandover(handover(S1, cups(order), 6));
            order.putOnHold(HoldReason.INVENTORY, "thiếu 4 ly", STAFF, NOW);
            assertThat(order.statusBeforeHold()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            order.pullDomainEvents();
            order.recordShipmentDelivered(S1, NOW, STAFF);
            assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);
            assertThat(deliveries(order)).singleElement()
                    .satisfies(e -> assertThat(e.shipmentId()).isEqualTo(S1));
            assertThat(code(catchThrowable(() -> order.recordHandover(handover(S2, cups(order), 4)))))
                    .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
            order.resolveHold("đã có hàng", STAFF, NOW);
            assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        }

        @Test
        @DisplayName("resolving a hold of a partly shipped order whose shipment came back meanwhile: still partial")
        void resolveCatchesUp() {
            Order order = packed();
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
            Order order = packed();
            order.recordHandover(handover(S1, cups(order), 6));
            assertThat(code(catchThrowable(() -> order.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, null,
                    STAFF, NOW)))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        }
    }

    @Nested
    @DisplayName("review of G3-2: stuck states, re-shipping")
    class Review {

        @Test
        @DisplayName("units that came back are shipped again: packed, handed over, SHIPPED - no dead end")
        void returnedUnitsShipAgain() {
            Order order = packed();
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

        private Order delivered() {
            Order order = packed();
            order.markShipped(NOW, STAFF);
            order.markDelivered(NOW, STAFF);
            return order;
        }

        @Test
        @DisplayName("COMPLETED after the return window (whole calendar days), paid, no return open; CLOSED 30 days "
                + "later; then nothing")
        void completeThenClose() {
            Order order = delivered();
            Duration week = Duration.ofDays(7);
            Instant windowEnd = ReturnPolicy.endOfDays(NOW, week);
            assertThat(order.complete(windowEnd.minusSeconds(1), week, false, false))
                    .as("the customer may still claim on the 7th day").isFalse();
            assertThat(order.complete(windowEnd, week, true, false)).as("a return is open").isFalse();
            assertThat(order.complete(windowEnd, week, false, false)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.COMPLETED);
            assertThat(order.close(NOW.plus(Duration.ofDays(20)), Duration.ofDays(30), false)).isFalse();
            assertThat(order.close(NOW.plus(Duration.ofDays(40)), Duration.ofDays(30), false)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.CLOSED);
            assertThat(order.closedAt()).isNotNull();
            assertThat(order.status().isTerminal()).isTrue();
        }

        @Test
        @DisplayName("an order whose installments still owe stays DELIVERED (17 §4.6 step 21)")
        void owingStaysDelivered() {
            Order order = delivered();
            assertThat(order.complete(NOW.plus(Duration.ofDays(30)), Duration.ofDays(7), false, true)).isFalse();
            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(order.complete(NOW.plus(Duration.ofDays(30)), Duration.ofDays(7), false, false)).isTrue();
        }

        @Test
        @DisplayName("RMA: DELIVERED → RETURN_REQUESTED → RETURNED → PARTIALLY_REFUNDED → CLOSED")
        void returnPath() {
            Order order = delivered();
            order.requestReturn(STAFF, NOW);
            order.goodsReturned(STAFF, NOW, null);
            order.refunded(false, STAFF, NOW, null);
            assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
            assertThat(order.close(NOW.plus(Duration.ofDays(31)), Duration.ofDays(30), false)).isTrue();
            assertThat(order.requestReturn(STAFF, NOW, "a claim after it closed")).as("BR-05: a closed order stays")
                    .isFalse();
            assertThat(order.status()).isEqualTo(OrderStatus.CLOSED);
        }

        @Test
        @DisplayName("a further claim reopens a COMPLETED or (PARTIALLY_)REFUNDED order; one still owed money never closes")
        void furtherClaims() {
            Order completed = delivered();
            completed.complete(ReturnPolicy.endOfDays(NOW, Duration.ofDays(7)), Duration.ofDays(7), false, false);
            assertThat(completed.requestReturn(STAFF, NOW, "late claim")).isTrue();
            assertThat(completed.status()).isEqualTo(OrderStatus.RETURN_REQUESTED);

            Order credit = delivered();
            credit.requestReturn(STAFF, NOW);
            credit.goodsReturned(STAFF, NOW, null);
            credit.refunded(false, STAFF, NOW, "REFUNDED");
            assertThat(credit.status()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
            assertThat(credit.close(NOW.plus(Duration.ofDays(60)), Duration.ofDays(30), true))
                    .as("its installments still owe").isFalse();
            assertThat(credit.status()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
            assertThat(credit.requestReturn(STAFF, NOW, "second claim")).isTrue();
        }
    }
}
