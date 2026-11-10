package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.CancellationReasonCode;
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
 * The first shipping label locks the order (team plan 4C-6; kltn-docs 08 BR-04, 17 BR-12): from then it is not cancelled
 * on any path, and a cancelled order is never labelled. {@code V20261113000700} says the same in the database.
 */
class OrderFirstLabelTest {

    private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();

    private static Order picking() {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 13), 7), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(100_000), null)), NOW);
        order.applyTerms(com.stockflow.order.api.PaymentTerm.PREPAID, null, null, null);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.submit();
        order.recordPayment(order.total(), NOW);
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
    @DisplayName("an order nobody labelled is cancelled by staff as before")
    void unlabelledOrderCancels() {
        Order order = picking();
        assertThat(order.firstLabeledAt()).isNull();
        assertThat(order.packedForGood()).isFalse();
        order.requireNotPacked();
        order.cancel(CancellationReasonCode.OUT_OF_STOCK, null, null, STAFF, NOW);
        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("the first label is recorded once: true the first time, then false and the first time is kept")
    void firstLabelOnce() {
        Order order = picking();
        assertThat(order.recordFirstLabel(NOW)).isTrue();
        assertThat(order.firstLabeledAt()).isEqualTo(NOW);
        assertThat(order.recordFirstLabel(NOW.plusSeconds(600))).isFalse();
        assertThat(order.firstLabeledAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a labelled order is packed for good: requireNotPacked and every cancellation refuse it (ORDER_NOT_CANCELLABLE)")
    void labelledOrderIsNotCancelled() {
        Order order = picking();
        order.recordFirstLabel(NOW);
        assertThat(order.packedForGood()).isTrue();
        assertThat(code(catchThrowable(order::requireNotPacked))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        assertThat(code(catchThrowable(() -> order.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, null, STAFF,
                NOW)))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        assertThat(order.status()).isEqualTo(OrderStatus.PICKING);
    }

    @Test
    @DisplayName("an order the repack station packed before the check station existed is locked too (packedAt)")
    void packedByTheRepackStationIsLocked() {
        Order order = picking();
        order.markPacked(NOW, STAFF);
        assertThat(order.firstLabeledAt()).isNull();
        assertThat(order.packedForGood()).isTrue();
        assertThat(code(catchThrowable(order::requireNotPacked))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
    }

    @Test
    @DisplayName("a cancelled order is never labelled: ORDER_CANCELLED, and nothing is recorded")
    void cancelledOrderIsNotLabelled() {
        Order order = picking();
        order.cancel(CancellationReasonCode.OUT_OF_STOCK, null, null, STAFF, NOW);
        assertThat(code(catchThrowable(() -> order.recordFirstLabel(NOW)))).isEqualTo(ErrorCode.ORDER_CANCELLED);
        assertThat(order.firstLabeledAt()).isNull();
    }

    @Test
    @DisplayName("an order put on hold (a wrong address) is not labelled: the check runs under the order's lock, so a hold "
            + "placed after the station's own read still stops the label that would lock the address (review L3)")
    void heldOrderIsNotLabelled() {
        Order order = picking();
        order.putOnHold(com.stockflow.order.api.HoldReason.ADDRESS, "street number missing", STAFF, NOW);
        assertThat(code(catchThrowable(() -> order.recordFirstLabel(NOW)))).isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
        assertThat(order.firstLabeledAt()).isNull();
        assertThat(order.packedForGood()).isFalse();

        order.resolveHold("address corrected", STAFF, NOW.plusSeconds(60));
        assertThat(order.status()).isEqualTo(OrderStatus.PICKING);
        assertThat(order.recordFirstLabel(NOW.plusSeconds(120))).isTrue();
    }

    @Test
    @DisplayName("only a released, picking or partly shipped order is labelled: one not released yet, or packed already, "
            + "is PACK_STATE_CONFLICT under the lock and nothing is recorded")
    void onlyWhileTheWarehouseWorksOnIt() {
        Order confirmed = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 13), 8), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(100_000), null)), NOW);
        confirmed.applyTerms(com.stockflow.order.api.PaymentTerm.PREPAID, null, null, null);
        confirmed.lines().forEach(line -> confirmed.attachReservations(line.id(), List.of(UUID.randomUUID())));
        confirmed.submit();
        confirmed.recordPayment(confirmed.total(), NOW);
        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(code(catchThrowable(() -> confirmed.recordFirstLabel(NOW)))).isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
        assertThat(confirmed.firstLabeledAt()).isNull();

        Order packed = picking();
        packed.markPacked(NOW, STAFF);
        assertThat(code(catchThrowable(() -> packed.recordFirstLabel(NOW)))).isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
        assertThat(packed.firstLabeledAt()).isNull();
    }

    @Test
    @DisplayName("rehydration restores the lock without recording it again")
    void rehydration() {
        Order order = picking();
        order.restoreFirstLabeled(NOW.minusSeconds(60));
        assertThat(order.firstLabeledAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(order.recordFirstLabel(NOW)).isFalse();
        assertThat(order.packedForGood()).isTrue();
    }

    @Test
    @DisplayName("a null time is a programming error")
    void nullTime() {
        assertThat(catchThrowable(() -> picking().recordFirstLabel(null))).isInstanceOf(NullPointerException.class);
    }
}
