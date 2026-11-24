package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.ShipmentHandover;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-05 audit round 2 on the aggregate: the facts the settlement reads.
 * <ul>
 *   <li>money M-1 / lifecycle M-2: an order that delivered nothing ends at its last closure - a closure of its rest as
 *       well as a shipment's - and its installments after delivery count from there
 *       ({@link Order#endedWithNothingDeliveredAt});</li>
 *   <li>lifecycle M-3: a shipment Sales closed as lost and the carrier later brought back is not a legacy row owed for
 *       a decision, though a later batch of the same line is still to ship ({@link Order#owesUnitsOf}); a row the
 *       backfill closed still is;</li>
 *   <li>contract M-6: what {@link Order#endedByCarrier} and {@link Order#closeWithoutRefund} accept.</li>
 * </ul>
 */
class OrderSettlementFactsTest {

    private static final Instant T0 = Instant.parse("2026-11-01T03:00:00Z");
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();

    /** 10 cups and 5 lids, packed, split shipments allowed; S1 carries 4 cups (batch 1), the rest still to ship. */
    private static Order firstBatchHandedOver() {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 1), 7), UUID.randomUUID(), UUID.randomUUID(),
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
        order.recordHandover(new ShipmentHandover(S1, List.of(new ShipmentHandover.Line(order.lines().get(0).id(), 4)),
                T0, STAFF));
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    private static Instant day(int days) {
        return T0.plus(Duration.ofDays(days));
    }

    @Test
    @DisplayName("money M-1: batch 1 brought back while the rest is still to ship - no day; the rest closed last ends the "
            + "order with nothing delivered, at the closure, as the receipt coming last would")
    void restClosedLastEndsANothingDeliveredOrder() {
        Order order = firstBatchHandedOver();
        order.recordShipmentDeliveryFailed(S1, DeliveryFailureReason.REFUSED, null, day(1), STAFF);
        order.recordShipmentReturned(S1, day(2), STAFF);
        order.closeReturnedShipment(S1, day(3), day(3), STAFF);
        assertThat(order.endedWithNothingDeliveredAt()).as("the rest still to ship").isEmpty();
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);

        order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, "Khách không lấy phần còn lại", STAFF, day(5));

        assertThat(order.deliveredSomething()).isFalse();
        assertThat(order.deliveredInFullAt()).as("nothing delivered").isEmpty();
        assertThat(order.endedWithNothingDeliveredAt()).contains(day(5));
        assertThat(order.deliveriesEndedAt()).contains(day(5));
    }

    @Test
    @DisplayName("lifecycle M-3: lost-closed, then found and brought back while batch 2 of the same line is still to "
            + "ship - owed to nobody, not a legacy row; a row the backfill closed (no value) still is")
    void aLostShipmentFoundLaterIsNotLegacy() {
        Order order = firstBatchHandedOver();
        order.recordShipmentDeliveryFailed(S1, DeliveryFailureReason.LOST, null, day(1), STAFF);
        order.closeLostShipment(S1, "Hãng xác nhận thất lạc", day(2), STAFF);
        order.recordShipmentReturned(S1, day(9), STAFF);
        assertThat(order.unshippedUnits(order.lines().get(0).id())).as("batch 2 of the same line").isPositive();

        assertThat(order.owesUnitsOf(S1)).isFalse();

        // The backfill's row: units closed as of the outcome, no value ever settled (V20261217000400).
        Order legacy = firstBatchHandedOver();
        legacy.restoreShipments(List.of(new OrderShipment(S1, java.util.Map.of(legacy.lines().get(0).id(), 4),
                BigDecimal.valueOf(400_000), T0, STAFF, com.stockflow.order.api.OrderShipmentStatus.RETURNED, day(2),
                STAFF, DeliveryFailureReason.REFUSED, null, null).restoreUnitsClosed(day(2))));
        assertThat(legacy.shipments().getFirst().closedBeforeSettlement()).isTrue();
        assertThat(legacy.owesUnitsOf(S1)).as("a legacy row whose units are owed again").isTrue();
    }

    @Test
    @DisplayName("contract M-6: closed without a refund only once ended by the carrier, never with money made "
            + "refundable, and after the closing delay from when its deliveries ended")
    void closeWithoutRefundRules() {
        Order order = firstBatchHandedOver();
        order.recordShipmentDeliveryFailed(S1, DeliveryFailureReason.LOST, null, day(1), STAFF);
        assertThat(order.endedByCarrier()).isFalse();
        order.closeLostShipment(S1, "Hãng xác nhận thất lạc", day(2), STAFF);
        order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, "Không giao lại", STAFF, day(3));
        assertThat(order.status()).isEqualTo(OrderStatus.RETURNED);
        assertThat(order.endedByCarrier()).isTrue();

        Duration after = Duration.ofDays(30);
        assertThat(order.closeWithoutRefund(day(20), after, false, false, false, null)).as("within the delay")
                .isFalse();
        assertThat(order.closeWithoutRefund(day(40), after, false, true, false, null)).as("money refundable")
                .isFalse();
        assertThat(order.closeWithoutRefund(day(40), after, true, false, false, null)).as("owing").isFalse();
        assertThat(order.closeWithoutRefund(day(40), after, false, false, true, null)).as("held").isFalse();
        assertThat(order.closeWithoutRefund(day(40), after, false, false, false, null)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSED);
        assertThat(order.closedAt()).isEqualTo(day(40));
    }
}
