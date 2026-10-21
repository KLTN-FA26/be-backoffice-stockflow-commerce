package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** The shipment lifecycle of kltn-docs 09 §5: every allowed step, every refused one, and who recorded it (BR-02). */
class ShipmentTest {

    private static final Instant NOW = Instant.parse("2026-10-21T08:00:00Z");
    private static final UUID PACKER = UUID.randomUUID();
    private static final UUID COORDINATOR = UUID.randomUUID();

    private static Shipment ready() {
        return Shipment.ready(UUID.randomUUID(), "SO-1-S1", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, DeliveryMode.PARCEL, 2, new BigDecimal("1500000"), null, PACKER, NOW);
    }

    @Test
    @DisplayName("born READY_TO_DISPATCH, one timeline step naming the packer")
    void born() {
        Shipment shipment = ready();
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.READY_TO_DISPATCH);
        assertThat(shipment.partial()).isFalse();
        assertThat(shipment.pullRecorded()).singleElement().satisfies(step -> {
            assertThat(step.status()).isEqualTo(ShipmentStatus.READY_TO_DISPATCH);
            assertThat(step.by()).isEqualTo(PACKER);
        });
        Shipment partial = Shipment.ready(UUID.randomUUID(), "SO-1-S2", UUID.randomUUID(), null, UUID.randomUUID(),
                null, null, 1, null, "stock lines first", PACKER, NOW);
        assertThat(partial.partial()).isTrue();
        assertThat(partial.codAmount()).isZero();
        assertThatThrownBy(() -> Shipment.ready(UUID.randomUUID(), "X", UUID.randomUUID(), null, UUID.randomUUID(),
                null, null, 0, null, null, PACKER, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("handed over, failed, delivered on a retry; the failure reason stays; repeats change nothing")
    void happyPathAndRetry() {
        Shipment shipment = ready();
        assertThat(shipment.handOver(PACKER, NOW)).isTrue();
        assertThat(shipment.handOver(PACKER, NOW)).isFalse();
        assertThat(shipment.recordFailed(DeliveryFailureReason.CUSTOMER_ABSENT, null, COORDINATOR, NOW)).isTrue();
        assertThat(shipment.recordDelivered(COORDINATOR, NOW.plusSeconds(3600))).isTrue();
        Shipment.Snapshot s = shipment.snapshot();
        assertThat(s.status()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(s.failureReason()).isEqualTo(DeliveryFailureReason.CUSTOMER_ABSENT);
        assertThat(s.outcomeBy()).isEqualTo(COORDINATOR);
        assertThat(shipment.pullRecorded()).extracting(Shipment.Step::status).containsExactly(
                ShipmentStatus.READY_TO_DISPATCH, ShipmentStatus.HANDED_OVER, ShipmentStatus.DELIVERY_FAILED,
                ShipmentStatus.DELIVERED);
    }

    @Test
    @DisplayName("failed then returned; OTHER needs a note; an outcome needs who recorded it")
    void returnedAndRules() {
        Shipment shipment = ready();
        shipment.handOver(PACKER, NOW);
        assertThat(((BusinessException) catchThrowable(() -> shipment.recordFailed(DeliveryFailureReason.OTHER, " ",
                COORDINATOR, NOW))).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> shipment.recordDelivered(null, NOW)).isInstanceOf(NullPointerException.class);
        shipment.recordFailed(DeliveryFailureReason.REFUSED, null, COORDINATOR, NOW);
        assertThat(shipment.recordReturned(COORDINATOR, NOW)).isTrue();
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.RETURNED);
        assertThat(ShipmentStatus.RETURNED.isLive()).isFalse();
    }

    @Test
    @DisplayName("cancelled only before the handover")
    void cancelling() {
        Shipment waiting = ready();
        assertThat(waiting.cancel("order cancelled", null, NOW)).isTrue();
        assertThat(waiting.cancel("again", null, NOW)).isFalse();
        assertThat(waiting.snapshot().cancelledAt()).isEqualTo(NOW);
        Shipment gone = ready();
        gone.handOver(PACKER, NOW);
        assertThat(((BusinessException) catchThrowable(() -> gone.cancel("too late", null, NOW))).errorCode())
                .isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);
    }

    @ParameterizedTest
    @EnumSource(ShipmentStatus.class)
    @DisplayName("the 09 §5 table: exactly these edges")
    void table(ShipmentStatus from) {
        EnumSet<ShipmentStatus> allowed = switch (from) {
            case READY_TO_DISPATCH -> EnumSet.of(ShipmentStatus.HANDED_OVER, ShipmentStatus.CANCELLED);
            case HANDED_OVER -> EnumSet.of(ShipmentStatus.DELIVERED, ShipmentStatus.DELIVERY_FAILED);
            case DELIVERY_FAILED -> EnumSet.of(ShipmentStatus.DELIVERED, ShipmentStatus.RETURNED);
            case DELIVERED, RETURNED, CANCELLED -> EnumSet.noneOf(ShipmentStatus.class);
        };
        for (ShipmentStatus to : ShipmentStatus.values()) {
            assertThat(from.canTransitionTo(to)).as(from + " > " + to).isEqualTo(allowed.contains(to));
        }
    }

    @Test
    @DisplayName("a delivered shipment cannot be reported failed; a waiting one cannot be delivered")
    void refusedSteps() {
        Shipment waiting = ready();
        assertThat(((BusinessException) catchThrowable(() -> waiting.recordDelivered(COORDINATOR, NOW))).errorCode())
                .isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);
        Shipment delivered = ready();
        delivered.handOver(PACKER, NOW);
        delivered.recordDelivered(COORDINATOR, NOW);
        assertThat(((BusinessException) catchThrowable(() -> delivered.recordFailed(DeliveryFailureReason.LOST, null,
                COORDINATOR, NOW))).errorCode()).isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);
    }
}
