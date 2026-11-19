package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
    private static final UUID LOAD = UUID.randomUUID();
    private static final UUID CARRIER = UUID.randomUUID();

    private static Shipment ready() {
        return Shipment.ready(UUID.randomUUID(), "SO-1-S1", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, 2, null, PACKER, NOW);
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
                null, 1, "stock lines first", PACKER, NOW);
        assertThat(partial.partial()).isTrue();
        assertThatThrownBy(() -> Shipment.ready(UUID.randomUUID(), "X", UUID.randomUUID(), null, UUID.randomUUID(),
                null, 0, null, PACKER, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("handed over, failed, delivered on a retry; the failure reason stays; repeats change nothing")
    void happyPathAndRetry() {
        Shipment shipment = ready();
        assertThat(shipment.handOver(LOAD, CARRIER, false, PACKER, NOW)).isTrue();
        assertThat(shipment.handOver(LOAD, CARRIER, false, PACKER, NOW)).isFalse();
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
    @DisplayName("09B G12: the failure reason of a failed or returned shipment is corrected - not its status or timeline; "
            + "OTHER needs a note; the same again changes nothing; a shipment with no failure has nothing to correct")
    void failureReasonCorrected() {
        Shipment shipment = ready();
        shipment.handOver(LOAD, CARRIER, false, PACKER, NOW);
        assertThat(((BusinessException) catchThrowable(() -> shipment.correctFailureReason(
                DeliveryFailureReason.REFUSED, null, COORDINATOR))).errorCode())
                .isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);
        shipment.carrierFailed(DeliveryFailureReason.OTHER, "hãng báo hoàn hàng", NOW);
        shipment.carrierReturned(NOW);
        shipment.pullRecorded();

        assertThat(((BusinessException) catchThrowable(() -> shipment.correctFailureReason(
                DeliveryFailureReason.OTHER, " ", COORDINATOR))).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> shipment.correctFailureReason(DeliveryFailureReason.REFUSED, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(shipment.correctFailureReason(DeliveryFailureReason.REFUSED, null, COORDINATOR)).isTrue();
        assertThat(shipment.correctFailureReason(DeliveryFailureReason.REFUSED, null, COORDINATOR)).isFalse();
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.RETURNED);
        assertThat(shipment.failureReason()).isEqualTo(DeliveryFailureReason.REFUSED);
        assertThat(shipment.failureNote()).isNull();
        assertThat(shipment.pullRecorded()).isEmpty();
    }

    @Test
    @DisplayName("failed then returned; OTHER needs a note; an outcome needs who recorded it")
    void returnedAndRules() {
        Shipment shipment = ready();
        shipment.handOver(LOAD, CARRIER, false, PACKER, NOW);
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
        gone.handOver(LOAD, CARRIER, false, PACKER, NOW);
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
        delivered.handOver(LOAD, CARRIER, false, PACKER, NOW);
        delivered.recordDelivered(COORDINATOR, NOW);
        assertThat(((BusinessException) catchThrowable(() -> delivered.recordFailed(DeliveryFailureReason.LOST, null,
                COORDINATOR, NOW))).errorCode()).isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);
    }

    @Test
    @DisplayName("handed over on a load's manifest to the carrier its vehicle was booked with; the whole-order flag "
            + "stays; one timeline step; again changes nothing")
    void handedOverWithItsLoadAndCarrier() {
        Shipment shipment = ready();
        shipment.pullRecorded();
        assertThat(shipment.handOver(LOAD, CARRIER, true, PACKER, NOW)).isTrue();
        assertThat(shipment.handOver(LOAD, CARRIER, true, PACKER, NOW)).isFalse();
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.HANDED_OVER);
        assertThat(shipment.loadId()).isEqualTo(LOAD);
        assertThat(shipment.carrierId()).isEqualTo(CARRIER);
        assertThat(shipment.wholeOrderHandover()).isTrue();
        assertThat(shipment.snapshot().handedOverBy()).isEqualTo(PACKER);
        assertThat(shipment.pullRecorded()).singleElement()
                .satisfies(step -> assertThat(step.status()).isEqualTo(ShipmentStatus.HANDED_OVER));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("SCRUM-392: a shipment waiting for dispatch splits its left-behind parcels off "
            + "into a new partial shipment of the same order and pack; one handed over does not split")
    void splitOff() {
        UUID order = UUID.randomUUID();
        UUID pack = UUID.randomUUID();
        Shipment ftl = Shipment.ready(UUID.randomUUID(), "SO-9-S1", order, pack, UUID.randomUUID(), UUID.randomUUID(),
                3, null, PACKER, NOW);
        ftl.pullRecorded();
        Shipment rest = ftl.splitOff(UUID.randomUUID(), "SO-9-S2", UUID.randomUUID(), 1, 2, "left behind", PACKER, NOW);
        assertThat(ftl.packageCount()).isEqualTo(2);
        assertThat(rest.packageCount()).isEqualTo(1);
        assertThat(rest.status()).isEqualTo(ShipmentStatus.READY_TO_DISPATCH);
        assertThat(rest.orderId()).isEqualTo(order);
        assertThat(rest.partial()).isTrue();
        assertThat(rest.snapshot().packId()).isEqualTo(pack);
        assertThat(rest.pullRecorded()).singleElement().satisfies(step -> assertThat(step.status())
                .isEqualTo(ShipmentStatus.READY_TO_DISPATCH));
        Shipment gone = ready();
        gone.handOver(LOAD, CARRIER, false, PACKER, NOW);
        Throwable left = catchThrowable(() -> gone.splitOff(UUID.randomUUID(), "X", UUID.randomUUID(), 1, 1, null,
                PACKER, NOW));
        assertThat(((BusinessException) left).errorCode()).isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);
        assertThatThrownBy(() -> ftl.splitOff(UUID.randomUUID(), "X", UUID.randomUUID(), 1, 0, null, PACKER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ErrorCode refusedWith(Runnable step) {
        return ((BusinessException) catchThrowable(step::run)).errorCode();
    }

    @Test
    @DisplayName("a delivery time a person gives is not in the future and not before the handover (09 §4.3 step 2)")
    void deliveryTimeBounds() {
        Instant now = NOW.plusSeconds(7200);
        Shipment shipment = ready();
        shipment.handOver(LOAD, CARRIER, false, PACKER, NOW);

        assertThat(refusedWith(() -> shipment.recordDelivered(COORDINATOR, now.plusSeconds(1), now)))
                .isEqualTo(ErrorCode.SHIPMENT_DELIVERY_TIME_IN_FUTURE);
        assertThat(refusedWith(() -> shipment.recordDelivered(COORDINATOR, NOW.minusSeconds(1), now)))
                .isEqualTo(ErrorCode.SHIPMENT_DELIVERY_TIME_BEFORE_HANDOVER);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.HANDED_OVER);
        assertThat(shipment.recordDelivered(COORDINATOR, NOW, now)).isTrue();
        assertThat(shipment.snapshot().outcomeAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a retry that delivers is dated after the failure it follows, not before it")
    void aRetryComesAfterTheFailure() {
        Instant now = NOW.plusSeconds(7200);
        Shipment shipment = ready();
        shipment.handOver(LOAD, CARRIER, false, PACKER, NOW);
        shipment.recordFailed(DeliveryFailureReason.CUSTOMER_ABSENT, null, COORDINATOR, NOW.plusSeconds(3600));

        assertThat(refusedWith(() -> shipment.recordDelivered(COORDINATOR, NOW.plusSeconds(3599), now)))
                .isEqualTo(ErrorCode.SHIPMENT_DELIVERY_TIME_BEFORE_FAILURE);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.DELIVERY_FAILED);
        assertThat(shipment.recordDelivered(COORDINATOR, NOW.plusSeconds(3600), now)).isTrue();
    }

    @Test
    @DisplayName("a correction to delivered may predate the failure it undoes but not the handover")
    void aCorrectionIsBoundedByTheHandoverOnly() {
        Instant now = NOW.plusSeconds(7200);
        Shipment shipment = ready();
        shipment.handOver(LOAD, CARRIER, false, PACKER, NOW);
        shipment.recordFailed(DeliveryFailureReason.CUSTOMER_ABSENT, null, COORDINATOR, NOW.plusSeconds(3600));

        assertThat(refusedWith(() -> shipment.correctOutcome(ShipmentStatus.DELIVERED, null, null, COORDINATOR,
                NOW.minusSeconds(1), now))).isEqualTo(ErrorCode.SHIPMENT_DELIVERY_TIME_BEFORE_HANDOVER);
        assertThat(shipment.correctOutcome(ShipmentStatus.DELIVERED, null, null, COORDINATOR,
                NOW.plusSeconds(60), now)).isTrue();
    }
}
