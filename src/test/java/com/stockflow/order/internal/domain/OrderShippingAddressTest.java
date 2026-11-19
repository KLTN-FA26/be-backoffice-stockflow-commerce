package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * WF04 G2 (kltn-docs 17 §4.7 steps 23-24, BR-16; 08 BR-04, §6 edge case): Sales change the delivery address of an order
 * until its first carton is labelled - in DRAFT, from PENDING_PAYMENT to PICKING, and while it is held from one of those
 * (held ADDRESS, corrected, released) - and never after.
 */
class OrderShippingAddressTest {

    private static final Instant NOW = Instant.parse("2026-11-18T03:00:00Z");
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final OrderAddressSnapshot FIRST = address("1 Lê Lợi", "0901234567");
    private static final OrderAddressSnapshot CORRECTED = address("9 Hai Bà Trưng", "0907654321");

    private static OrderAddressSnapshot address(String line1, String phone) {
        return new OrderAddressSnapshot("Kho Bến Thành", phone, line1, null, "26734", "Phường Bến Nghé", "79",
                "TP. Hồ Chí Minh", "VN", null);
    }

    private static Order draft() {
        return Order.customerDraft(OrderNumber.of(LocalDate.of(2026, 11, 18), 7), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(100_000), null)), NOW, "Khách", "k@example.com",
                "0901234567", FIRST, FIRST);
    }

    private static Order picking() {
        Order order = draft();
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.sendContract(STAFF, NOW);
        order.contractSigned(false, false, STAFF, NOW);
        order.chooseShippingWarehouse(HCM, true, STAFF, NOW);
        order.release(HCM, "HCM", STAFF, NOW);
        order.startPicking(STAFF, NOW);
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("a DRAFT order (14: edited freely) and a PICKING order take the new address; the same again changes nothing")
    void changedBeforeTheFirstLabel() {
        Order draft = draft();
        assertThat(draft.changeShippingAddress(CORRECTED)).isTrue();
        assertThat(draft.shippingAddress()).isEqualTo(CORRECTED);

        Order picking = picking();
        assertThat(picking.status()).isEqualTo(OrderStatus.PICKING);
        assertThat(picking.changeShippingAddress(CORRECTED)).isTrue();
        assertThat(picking.shippingAddress()).isEqualTo(CORRECTED);
        assertThat(picking.changeShippingAddress(address("9 Hai Bà Trưng", "0907654321"))).isFalse();
        assertThat(picking.status()).isEqualTo(OrderStatus.PICKING);
    }

    @Test
    @DisplayName("08 §6: a wrong address held ADDRESS is corrected while the order is on hold, then the hold is released")
    void correctedWhileHeldForTheAddress() {
        Order order = picking();
        order.putOnHold(HoldReason.ADDRESS, "sai phường", STAFF, NOW);

        assertThat(order.changeShippingAddress(CORRECTED)).isTrue();
        order.resolveHold("khách đã xác nhận địa chỉ mới", STAFF, NOW);

        assertThat(order.status()).isEqualTo(OrderStatus.PICKING);
        assertThat(order.shippingAddress()).isEqualTo(CORRECTED);
    }

    @Test
    @DisplayName("08 BR-04: once a carton is labelled the address no longer changes - ORDER_ADDRESS_LOCKED, the old one kept")
    void lockedByTheFirstLabel() {
        Order order = picking();
        order.recordFirstLabel(NOW);

        assertThat(code(catchThrowable(() -> order.changeShippingAddress(CORRECTED))))
                .isEqualTo(ErrorCode.ORDER_ADDRESS_LOCKED);
        assertThat(order.shippingAddress()).isEqualTo(FIRST);
    }

    @Test
    @DisplayName("a sent contract carries the address (cancel it first), and a cancelled order changes nothing")
    void refusedOutsideTheWindow() {
        Order pendingContract = draft();
        pendingContract.sendContract(STAFF, NOW);
        assertThat(code(catchThrowable(() -> pendingContract.changeShippingAddress(CORRECTED))))
                .isEqualTo(ErrorCode.ORDER_ADDRESS_LOCKED);

        Order cancelled = picking();
        cancelled.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, null, STAFF, NOW);
        assertThat(code(catchThrowable(() -> cancelled.changeShippingAddress(CORRECTED))))
                .isEqualTo(ErrorCode.ORDER_CANCELLED);
        assertThat(cancelled.shippingAddress()).isEqualTo(FIRST);
    }
}
