package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.ShipmentHandover;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A delivery the carrier reported wrongly, corrected to a failure by hand (kltn-docs 09 §4.3 step 5, E17; 17 §5.1), on
 * the aggregate alone. Kept from the money ledger's {@code OrderBalanceTest} (4B-10): the correction is the order's own
 * rule. A delivery billed to the customer in the money ledger before 4B-10 is still not corrected here - such a
 * shipment is only ever restored from its row now, since nothing bills on delivery any more.
 */
class OrderDeliveryCorrectionTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();

    /** 10 cups and 5 lids of a signed contract, confirmed, released from HCM and packed; split shipments allowed. */
    private static Order packed() {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 10), 9), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(100_000), null),
                        Order.line(new Sku("LID-90"), 5, Money.vnd(20_000), null)), NOW);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.decidePartialShipment(true);
        order.sendContract(STAFF, NOW);
        order.contractSigned(false, false, STAFF, NOW);
        order.chooseShippingWarehouse(HCM, true, STAFF, NOW);
        order.release(HCM, "HCM", STAFF, NOW);
        order.startPicking(STAFF, NOW);
        order.markPacked(NOW, STAFF);
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    private static ShipmentHandover everything(Order order) {
        return new ShipmentHandover(S1, List.of(new ShipmentHandover.Line(order.lines().get(0).id(), 10),
                new ShipmentHandover.Line(order.lines().get(1).id(), 5)), NOW, STAFF);
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("a delivery becomes a failure and the order follows; corrected back, delivered")
    void deliveryCorrected() {
        Order order = packed();
        order.recordHandover(everything(order), BigDecimal.ZERO);
        order.recordShipmentDelivered(S1, NOW, STAFF);
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);

        assertThat(order.correctShipmentToFailed(S1, DeliveryFailureReason.CUSTOMER_ABSENT, null, NOW, STAFF))
                .isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERY_FAILED);
        assertThat(order.deliveredAt()).as("no longer delivered: the time shows nowhere").isNull();
        assertThat(order.correctShipmentToFailed(S1, DeliveryFailureReason.CUSTOMER_ABSENT, null, NOW, STAFF))
                .as("corrected again: nothing changes").isFalse();
        assertThat(order.recordShipmentDelivered(S1, NOW, STAFF)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("the new edge is the correction's only: a delivered order is not failed as a whole order")
    void notAWholeOrderFailure() {
        Order order = packed();
        order.markShipped(NOW, STAFF, BigDecimal.ZERO);
        order.markDelivered(NOW, STAFF);
        assertThat(catchThrowable(() -> order.markDeliveryFailed(DeliveryFailureReason.LOST, null, NOW, STAFF)))
                .isInstanceOf(InvalidOrderTransitionException.class);
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("refused: OTHER without a note; a shipment not delivered; a delivery billed in the money ledger before")
    void refused() {
        Order order = packed();
        order.recordHandover(everything(order), BigDecimal.ZERO);
        assertThat(code(catchThrowable(() -> order.correctShipmentToFailed(S1, DeliveryFailureReason.OTHER, " ", NOW,
                STAFF)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> order.correctShipmentToFailed(S1, DeliveryFailureReason.LOST, null, NOW,
                STAFF)))).isEqualTo(ErrorCode.ORDER_SHIPMENT_INVALID_TRANSITION);

        Order billed = packed();
        billed.recordHandover(everything(billed), BigDecimal.ZERO);
        billed.recordShipmentDelivered(S1, NOW, STAFF);
        // As its row restores a delivery that billed the customer on credit before 4B-10.
        billed.restoreShipments(List.of(new OrderShipment(S1, Map.of(billed.lines().get(0).id(), 10,
                billed.lines().get(1).id(), 5), new BigDecimal("1100000"), NOW, STAFF, OrderShipmentStatus.DELIVERED,
                NOW, STAFF, null, null, new BigDecimal("1100000"))));
        assertThat(code(catchThrowable(() -> billed.correctShipmentToFailed(S1, DeliveryFailureReason.LOST, null, NOW,
                STAFF)))).isEqualTo(ErrorCode.ORDER_DELIVERY_NOT_CORRECTABLE);
    }
}
