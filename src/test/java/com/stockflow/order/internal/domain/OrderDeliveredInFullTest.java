package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.ShipmentReturnSettled;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.ShipmentHandover;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WF-05 on the aggregate: when an order counts as delivered in full (BE-02, BE-04; X-3; L-I1), and that a carrier's
 * word alone closes nothing (BE-14, Q-7; L-I8) while a confirmed receipt or a lost-closure (BE-17, Q-12) closes the
 * shipment's units once.
 */
class OrderDeliveredInFullTest {

    private static final Instant T0 = Instant.parse("2026-11-01T03:00:00Z");
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();

    /** 10 cups at 100 000 and 5 lids at 20 000, packed, split shipments allowed. */
    private static Order packed() {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 1), 3), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(100_000), null),
                        Order.line(new Sku("LID-90"), 5, Money.vnd(20_000), null)), T0);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.decidePartialShipment(true);
        order.sendContract(STAFF, T0);
        order.contractSigned(false, false, STAFF, T0);
        UUID warehouse = UUID.randomUUID();
        order.chooseShippingWarehouse(warehouse, true, STAFF, T0);
        order.release(warehouse, "HCM", STAFF, T0);
        order.startPicking(STAFF, T0);
        order.markPacked(T0, STAFF);
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    /** Cups on S1, lids on S2, both handed over at T0. */
    private static Order twoShipments() {
        Order order = packed();
        order.recordHandover(new ShipmentHandover(S1, List.of(new ShipmentHandover.Line(order.lines().get(0).id(), 10)),
                T0, STAFF));
        order.recordHandover(new ShipmentHandover(S2, List.of(new ShipmentHandover.Line(order.lines().get(1).id(), 5)),
                T0, STAFF));
        order.pullDomainEvents();
        return order;
    }

    private static Instant day(int days) {
        return T0.plus(Duration.ofDays(days));
    }

    private static List<ShipmentReturnSettled> settled(Order order) {
        return order.pullDomainEvents().stream().filter(OrderEvent.ShipmentSettled.class::isInstance)
                .map(e -> ((OrderEvent.ShipmentSettled) e).payload()).toList();
    }

    private static ErrorCode code(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("BE-04: a delivery recorded late with its real, earlier date does not pull the date back - the latest "
            + "delivery is the date")
    void latestDeliveryNotLastRecorded() {
        Order order = twoShipments();
        order.recordShipmentDelivered(S2, day(9), STAFF);
        order.recordShipmentDelivered(S1, day(4), STAFF);

        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.deliveredAt()).isEqualTo(day(9));
        assertThat(order.deliveredInFullAt()).contains(day(9));
    }

    @Test
    @DisplayName("BE-04: a delivery corrected to a failure and back to DELIVERED with its earlier date keeps the latest")
    void correctionBackToDeliveredKeepsLatest() {
        Order order = twoShipments();
        order.recordShipmentDelivered(S1, day(2), STAFF);
        order.recordShipmentDelivered(S2, day(6), STAFF);
        order.correctShipmentToFailed(S1, DeliveryFailureReason.CUSTOMER_ABSENT, null, day(7), STAFF);
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERY_FAILED);
        assertThat(order.deliveredAt()).as("no longer delivered in full").isNull();
        assertThat(order.deliveredInFullAt()).isEmpty();

        order.recordShipmentDelivered(S1, day(2), STAFF);
        assertThat(order.deliveredAt()).isEqualTo(day(6));
    }

    @Test
    @DisplayName("BE-14 / L-I8: the carrier's RETURNED closes nothing; the receipt closes the units once, settles once, "
            + "and BE-02: the order is delivered in full from max(last delivery, receipt)")
    void partialCarrierReturnClosesAtReceipt() {
        Order order = twoShipments();
        order.recordShipmentDelivered(S1, day(1), STAFF);
        order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.REFUSED, null, day(1), STAFF);
        order.recordShipmentReturned(S2, day(2), STAFF);

        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(order.lines().get(1).cancelledQuantity()).as("nothing closed on the carrier's word").isZero();
        assertThat(order.outstandingUnits()).isZero();
        assertThat(order.deliveriesOver()).as("the receipt is still to come").isFalse();
        assertThat(order.deliveredInFullAt()).isEmpty();
        assertThat(order.partlyReturnedAndDone()).as("no refund may move it yet").isFalse();
        assertThat(settled(order)).isEmpty();

        var closure = order.closeReturnedShipment(S2, day(5), day(5), STAFF);

        assertThat(closure).isPresent();
        assertThat(closure.get().goodsValue()).isEqualByComparingTo("100000");
        assertThat(closure.get().closureId()).isEqualTo(Order.shipmentClosureId(S2));
        assertThat(order.lines().get(1).cancelledQuantity()).isEqualTo(5);
        assertThat(order.outstandingUnits()).isZero();
        assertThat(order.deliveriesOver()).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(order.deliveredInFullAt()).contains(day(5));
        assertThat(order.deliveredAt()).isEqualTo(day(5));
        assertThat(order.partlyReturnedAndDone()).isTrue();
        List<ShipmentReturnSettled> events = settled(order);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.cause()).isEqualTo(ShipmentReturnSettled.Cause.CARRIER_RETURN);
            assertThat(event.goodsBackAt()).isEqualTo(day(5));
            assertThat(event.nothingDelivered()).isFalse();
            assertThat(event.nothingMoreComing()).isTrue();
            assertThat(event.closureId()).isEqualTo(closure.get().closureId());
        });

        // A second receipt of the same shipment, or the event replayed: nothing closed twice, nothing announced.
        assertThat(order.closeReturnedShipment(S2, day(6), day(6), STAFF)).isEmpty();
        assertThat(order.lines().get(1).cancelledQuantity()).isEqualTo(5);
        assertThat(settled(order)).isEmpty();
    }

    @Test
    @DisplayName("BE-02: the receipt earlier than the last delivery - the last delivery is the date")
    void receiptBeforeLastDelivery() {
        Order order = twoShipments();
        order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.REFUSED, null, day(1), STAFF);
        order.recordShipmentReturned(S2, day(1), STAFF);
        order.closeReturnedShipment(S2, day(2), day(2), STAFF);
        assertThat(order.deliveredInFullAt()).as("S1 still in transit").isEmpty();
        order.recordShipmentDelivered(S1, day(8), STAFF);

        assertThat(order.deliveredInFullAt()).contains(day(8));
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
    }

    @Test
    @DisplayName("everything came back: RETURNED, never delivered in full, the closure says nothing was delivered")
    void wholeCarrierReturn() {
        Order order = twoShipments();
        for (UUID shipment : List.of(S1, S2)) {
            order.recordShipmentDeliveryFailed(shipment, DeliveryFailureReason.REFUSED, null, day(1), STAFF);
            order.recordShipmentReturned(shipment, day(2), STAFF);
        }
        assertThat(order.status()).isEqualTo(OrderStatus.RETURNED);
        order.closeReturnedShipment(S1, day(3), day(3), STAFF);
        List<ShipmentReturnSettled> first = settled(order);
        assertThat(first).singleElement().satisfies(event -> {
            assertThat(event.nothingDelivered()).isTrue();
            assertThat(event.nothingMoreComing()).as("S2's receipt is still to come").isFalse();
        });
        order.closeReturnedShipment(S2, day(4), day(4), STAFF);
        assertThat(settled(order)).singleElement().satisfies(event -> assertThat(event.nothingMoreComing()).isTrue());

        assertThat(order.status()).isEqualTo(OrderStatus.RETURNED);
        assertThat(order.deliveredInFullAt()).isEmpty();
        assertThat(order.deliveredSomething()).isFalse();
        assertThat(order.deliveriesOver()).isTrue();
    }

    @Test
    @DisplayName("BE-17 / Q-12: a LOST shipment closed by Sales - units closed and settled now, the order out of "
            + "DELIVERY_FAILED; never delivered on this order afterwards; its reason no longer changes")
    void lostShipmentClosed() {
        Order order = twoShipments();
        order.recordShipmentDelivered(S1, day(1), STAFF);
        order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.LOST, null, day(2), STAFF);
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERY_FAILED);

        Order.ShipmentClosure closure = order.closeLostShipment(S2, "Hãng xác nhận thất lạc", day(10), STAFF);

        assertThat(closure.goodsValue()).isEqualByComparingTo("100000");
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(order.deliveryFailureReason()).isNull();
        assertThat(order.deliveredInFullAt()).contains(day(10));
        assertThat(order.partlyReturnedAndDone()).isTrue();
        assertThat(settled(order)).singleElement()
                .satisfies(event -> assertThat(event.cause()).isEqualTo(ShipmentReturnSettled.Cause.LOST));

        assertThat(code(org.assertj.core.api.Assertions.catchThrowable(() ->
                order.recordShipmentDelivered(S2, day(11), STAFF))))
                .isEqualTo(ErrorCode.ORDER_SHIPMENT_INVALID_TRANSITION);
        assertThat(code(org.assertj.core.api.Assertions.catchThrowable(() ->
                order.correctShipmentFailureReason(S2, DeliveryFailureReason.DAMAGED, null))))
                .isEqualTo(ErrorCode.ORDER_SHIPMENT_INVALID_TRANSITION);
        assertThat(code(org.assertj.core.api.Assertions.catchThrowable(() ->
                order.closeLostShipment(S2, "again", day(12), STAFF))))
                .as("closed once").isEqualTo(ErrorCode.ORDER_SHIPMENT_NOT_LOST_CLOSABLE);
        // Found and brought back after all: recorded, nothing closed again.
        order.recordShipmentReturned(S2, day(20), STAFF);
        assertThat(order.closeReturnedShipment(S2, day(21), day(21), STAFF)).isEmpty();
    }

    @Test
    @DisplayName("L-I6: a shipment reported back whose goods never reach the warehouse is closed as lost - the order "
            + "does not wait for a receipt for good")
    void lostOnTheWayBack() {
        Order order = twoShipments();
        order.recordShipmentDelivered(S1, day(1), STAFF);
        order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.REFUSED, null, day(1), STAFF);
        order.recordShipmentReturned(S2, day(2), STAFF);
        assertThat(order.deliveriesOver()).isFalse();

        order.closeLostShipment(S2, "Hãng làm mất trên đường hoàn", day(20), STAFF);

        assertThat(order.deliveriesOver()).isTrue();
        assertThat(order.partlyReturnedAndDone()).isTrue();
        assertThat(order.deliveredInFullAt()).contains(day(20));
        assertThat(order.closeReturnedShipment(S2, day(30), day(30), STAFF)).as("found later: closes nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("BE-17: only a shipment failed as LOST is closed as lost; a note is required")
    void lostClosureRefusals() {
        Order order = twoShipments();
        order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.CUSTOMER_ABSENT, null, day(2), STAFF);

        assertThatThrownBy(() -> order.closeLostShipment(S2, "lost?", day(3), STAFF))
                .satisfies(thrown -> assertThat(code(thrown)).isEqualTo(ErrorCode.ORDER_SHIPMENT_NOT_LOST_CLOSABLE));
        assertThatThrownBy(() -> order.closeLostShipment(S1, "in transit", day(3), STAFF))
                .satisfies(thrown -> assertThat(code(thrown)).isEqualTo(ErrorCode.ORDER_SHIPMENT_NOT_LOST_CLOSABLE));
        order.correctShipmentFailureReason(S2, DeliveryFailureReason.LOST, null);
        assertThatThrownBy(() -> order.closeLostShipment(S2, "  ", day(3), STAFF))
                .satisfies(thrown -> assertThat(code(thrown)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(order.lines().get(1).cancelledQuantity()).isZero();
    }

    @Test
    @DisplayName("L-I6: an order whose shipment is still to be decided, or back without its receipt, never closes")
    void closeWaitsForTheShipments() {
        Order order = twoShipments();
        order.recordShipmentDelivered(S1, day(1), STAFF);
        order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.REFUSED, null, day(1), STAFF);
        order.recordShipmentReturned(S2, day(2), STAFF);
        order.closeReturnedShipment(S2, day(3), day(3), STAFF);
        order.refunded(false, STAFF, day(4), "refund recorded");
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);

        assertThat(order.close(day(60), Duration.ofDays(30), false, false, true, null))
                .as("held (Q-17)").isFalse();
        assertThat(order.close(day(60), Duration.ofDays(30), false, false, false, day(61)))
                .as("a return window still open (Q-16)").isFalse();
        assertThat(order.close(day(60), Duration.ofDays(30), false, false, false, day(59))).isTrue();
    }
}
