package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
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
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * WF-05 on the aggregate: a claim that ends without money takes the order back to where it was opened from (BE-40),
 * a RETURNED order whose claims settled nothing goes back to the delivery path (X-8), a delivery is corrected while a
 * claim on another shipment is open and the claim's end does not undo it (BE-46), and a refund voided while a claim is
 * open leaves the claim ending in "waiting for the refund" (BE-52).
 */
class OrderReturnPathTest {

    private static final Instant T0 = Instant.parse("2026-11-01T03:00:00Z");
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();

    private static Instant day(int days) {
        return T0.plus(Duration.ofDays(days));
    }

    /** 10 cups on S1, 5 lids on S2, both delivered on day 1: DELIVERED. */
    private static Order delivered() {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 1), 5), UUID.randomUUID(), UUID.randomUUID(),
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
        order.recordHandover(new ShipmentHandover(S1, List.of(new ShipmentHandover.Line(order.lines().get(0).id(), 10)),
                T0, STAFF));
        order.recordHandover(new ShipmentHandover(S2, List.of(new ShipmentHandover.Line(order.lines().get(1).id(), 5)),
                T0, STAFF));
        order.recordShipmentDelivered(S1, day(1), STAFF);
        order.recordShipmentDelivered(S2, day(1), STAFF);
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    private static Order completed() {
        Order order = delivered();
        assertThat(order.complete(day(30), Duration.ofDays(7), false, false, false)).isTrue();
        return order;
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("BE-40: a late claim on a COMPLETED order, refused - COMPLETED again, completedAt untouched")
    void withdrawnClaimOfCompletedOrder() {
        Order order = completed();
        Instant completedAt = order.completedAt();
        order.requestReturn(STAFF, day(40), "late claim");
        assertThat(order.statusBeforeReturn()).isEqualTo(OrderStatus.COMPLETED);

        assertThat(order.withdrawReturn(STAFF, day(41), "RETURN_ENDED")).isTrue();

        assertThat(order.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.completedAt()).isEqualTo(completedAt);
        assertThat(order.statusBeforeReturn()).isNull();
        assertThat(order.complete(day(90), Duration.ofDays(7), false, false, false)).as("not completed twice")
                .isFalse();
    }

    @Test
    @DisplayName("BE-40: a claim opened from DELIVERED, REFUNDED or PARTIALLY_REFUNDED goes back there")
    void withdrawnClaimGoesBackToItsOrigin() {
        Order delivered = delivered();
        delivered.requestReturn(STAFF, day(2), null);
        delivered.withdrawReturn(STAFF, day(3), "RETURN_ENDED");
        assertThat(delivered.status()).isEqualTo(OrderStatus.DELIVERED);

        for (boolean full : List.of(true, false)) {
            Order order = delivered();
            order.requestReturn(STAFF, day(2), null);
            order.goodsReturned(STAFF, day(3), null);
            order.refunded(full, STAFF, day(4), "REFUNDED");
            OrderStatus refunded = order.status();
            order.requestReturn(STAFF, day(5), "further claim");
            assertThat(order.statusBeforeReturn()).isEqualTo(refunded);
            order.withdrawReturn(STAFF, day(6), "RETURN_ENDED");
            assertThat(order.status()).isEqualTo(refunded);
            assertThat(order.refundedAt()).isEqualTo(day(4));
        }
    }

    @Test
    @DisplayName("X-8: a RETURNED order whose claims settled nothing goes back to DELIVERED, or COMPLETED once "
            + "completed; a carrier's RETURNED order does not move")
    void returnsEndedWithoutRefund() {
        Order order = delivered();
        order.requestReturn(STAFF, day(2), null);
        order.goodsReturned(STAFF, day(3), null);
        assertThat(order.returnsEndedWithoutRefund(STAFF, day(4))).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.deliveredAt()).isEqualTo(day(1));
        assertThat(order.pullTransitions()).last()
                .satisfies(step -> assertThat(step.reason()).isEqualTo("RETURN_SETTLED_WITHOUT_REFUND"));

        Order late = completed();
        late.requestReturn(STAFF, day(40), null);
        late.goodsReturned(STAFF, day(41), null);
        assertThat(late.returnsEndedWithoutRefund(STAFF, day(42))).isTrue();
        assertThat(late.status()).isEqualTo(OrderStatus.COMPLETED);

        assertThat(delivered().returnsEndedWithoutRefund(STAFF, day(4))).as("not RETURNED").isFalse();

        // A further claim on a refunded order whose goods came back and were refused: back to REFUNDED, never
        // DELIVERED - it must not complete as if it was never refunded.
        Order refunded = delivered();
        refunded.requestReturn(STAFF, day(2), null);
        refunded.goodsReturned(STAFF, day(3), null);
        refunded.refunded(true, STAFF, day(4), "REFUNDED");
        refunded.requestReturn(STAFF, day(5), "further claim");
        refunded.goodsReturned(STAFF, day(6), null);
        assertThat(refunded.returnsEndedWithoutRefund(STAFF, day(7))).isTrue();
        assertThat(refunded.status()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("BE-46: a delivery corrected to a failure while a claim on the other shipment is open - the order "
            + "keeps RETURN_REQUESTED; the claim ending takes it where its shipments say")
    void correctionDuringClaim() {
        Order order = delivered();
        order.requestReturn(STAFF, day(2), null);

        assertThat(order.correctShipmentToFailed(S2, DeliveryFailureReason.CUSTOMER_ABSENT, null, day(3), STAFF))
                .isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(order.deliveredAt()).as("ck_order_delivered_at keeps it").isNotNull();
        assertThat(order.deliveredInFullAt()).as("payment's date is gone").isEmpty();

        order.withdrawReturn(STAFF, day(4), "RETURN_ENDED");
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERY_FAILED);
        assertThat(order.deliveryFailureReason()).isEqualTo(DeliveryFailureReason.CUSTOMER_ABSENT);
        assertThat(order.deliveredAt()).isNull();

        order.recordShipmentDelivered(S2, day(6), STAFF);
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.deliveredAt()).isEqualTo(day(6));
    }

    @Test
    @DisplayName("BE-46: allowed on REFUNDED / PARTIALLY_REFUNDED orders, which keep their status and do not close "
            + "while the shipment is undecided; never once completed")
    void correctionAfterRefund() {
        Order order = delivered();
        order.requestReturn(STAFF, day(2), null);
        order.goodsReturned(STAFF, day(3), null);
        order.refunded(false, STAFF, day(4), "REFUNDED");
        assertThat(order.correctShipmentToFailed(S2, DeliveryFailureReason.CUSTOMER_ABSENT, null, day(5), STAFF))
                .isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
        assertThat(order.close(day(90), Duration.ofDays(30), false, false)).as("S2 still to be decided").isFalse();
        order.recordShipmentDelivered(S2, day(8), STAFF);
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
        assertThat(order.close(day(90), Duration.ofDays(30), false, false)).isTrue();

        Order late = completed();
        late.requestReturn(STAFF, day(40), null);
        assertThat(code(catchThrowable(() -> late.correctShipmentToFailed(S2, DeliveryFailureReason.CUSTOMER_ABSENT,
                null, day(41), STAFF)))).isEqualTo(ErrorCode.ORDER_DELIVERY_NOT_CORRECTABLE);
    }

    @Test
    @DisplayName("BE-52: a refund voided while a further claim is open moves no status; the claim then ends in "
            + "waiting for the refund, not refunded")
    void refundVoidedDuringClaim() {
        Order order = delivered();
        order.requestReturn(STAFF, day(2), null);
        order.goodsReturned(STAFF, day(3), null);
        order.refunded(true, STAFF, day(4), "REFUNDED");
        order.requestReturn(STAFF, day(5), "further claim");

        assertThat(order.refundReopened(STAFF, day(6), "bank returned the transfer")).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(order.statusBeforeReturn()).isEqualTo(OrderStatus.RETURNED);

        order.withdrawReturn(STAFF, day(7), "RETURN_ENDED");
        assertThat(order.status()).isEqualTo(OrderStatus.RETURNED);
        assertThat(order.refunded(true, STAFF, day(8), "REFUNDED")).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("every new edge is in the transition table: RETURN_REQUESTED and RETURNED back to the delivery path")
    void newEdges() {
        assertThat(OrderStatus.edges()).contains("RETURN_REQUESTED>COMPLETED", "RETURN_REQUESTED>REFUNDED",
                "RETURN_REQUESTED>PARTIALLY_REFUNDED", "RETURN_REQUESTED>DELIVERY_FAILED",
                "RETURN_REQUESTED>PARTIALLY_FULFILLED", "RETURNED>DELIVERED", "RETURNED>COMPLETED",
                "RETURNED>DELIVERY_FAILED", "RETURNED>PARTIALLY_FULFILLED");
    }
}
