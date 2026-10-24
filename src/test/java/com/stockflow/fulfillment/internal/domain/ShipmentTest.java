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
    private static final UUID LOAD = UUID.randomUUID();
    private static final UUID CARRIER = UUID.randomUUID();

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
        assertThat(shipment.handOver(LOAD, CARRIER, null, false, PACKER, NOW)).isTrue();
        assertThat(shipment.handOver(LOAD, CARRIER, null, false, PACKER, NOW)).isFalse();
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
        shipment.handOver(LOAD, CARRIER, null, false, PACKER, NOW);
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
        gone.handOver(LOAD, CARRIER, null, false, PACKER, NOW);
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
        delivered.handOver(LOAD, CARRIER, null, false, PACKER, NOW);
        delivered.recordDelivered(COORDINATOR, NOW);
        assertThat(((BusinessException) catchThrowable(() -> delivered.recordFailed(DeliveryFailureReason.LOST, null,
                COORDINATOR, NOW))).errorCode()).isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);
    }

    @Test
    @DisplayName("G4-2a: handed over on a load with its carrier and the COD the order fixed - refused (LABEL_OUTDATED) "
            + "while its label shows another figure, accepted once the label is reprinted (09 BR-04)")
    void handedOverWithItsLoadCarrierAndCod() {
        Shipment shipment = ready();
        shipment.pullRecorded();
        Throwable outdated = catchThrowable(() -> shipment.handOver(LOAD, CARRIER, new BigDecimal("900000"), false,
                PACKER, NOW));
        assertThat(((BusinessException) outdated).errorCode()).isEqualTo(ErrorCode.LABEL_OUTDATED);
        assertThat(outdated.getMessage()).contains("SO-1-S1", "1500000", "900000");
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.READY_TO_DISPATCH);
        assertThat(shipment.labelPrinted(new BigDecimal("900000"), NOW)).isTrue();
        assertThat(shipment.labelPrinted(new BigDecimal("900000"), NOW)).isFalse();
        assertThat(shipment.handOver(LOAD, CARRIER, new BigDecimal("900000"), true, PACKER, NOW)).isTrue();
        assertThat(shipment.codAmount()).isEqualByComparingTo("900000");
        assertThat(shipment.loadId()).isEqualTo(LOAD);
        assertThat(shipment.carrierId()).isEqualTo(CARRIER);
        assertThat(shipment.wholeOrderHandover()).isTrue();
        assertThat(shipment.labelPrinted(BigDecimal.ONE, NOW)).as("a label printed after it left changes nothing")
                .isFalse();
        assertThat(shipment.pullRecorded()).singleElement()
                .satisfies(step -> assertThat(step.status()).isEqualTo(ShipmentStatus.HANDED_OVER));
        assertThatThrownBy(() -> shipment.assignCarrier(UUID.randomUUID()))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);
    }

    @Test
    @DisplayName("G4-2a: a shipment chosen for one carrier is not handed to another; a full truckload carries no COD")
    void carrierAndFullTruckloadRules() {
        Shipment shipment = ready();
        UUID chosen = UUID.randomUUID();
        assertThat(shipment.assignCarrier(chosen)).isTrue();
        assertThat(shipment.assignCarrier(chosen)).isFalse();
        assertThat(((BusinessException) catchThrowable(() -> shipment.handOver(LOAD, CARRIER, null, false, PACKER, NOW)))
                .errorCode()).isEqualTo(ErrorCode.SHIPMENT_OTHER_CARRIER);
        assertThatThrownBy(() -> Shipment.ready(UUID.randomUUID(), "SO-1-S3", UUID.randomUUID(), null,
                UUID.randomUUID(), null, DeliveryMode.FULL_TRUCKLOAD, 1, BigDecimal.TEN, null, PACKER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        Shipment truck = Shipment.ready(UUID.randomUUID(), "SO-1-S4", UUID.randomUUID(), null, UUID.randomUUID(),
                null, DeliveryMode.FULL_TRUCKLOAD, 1, null, null, PACKER, NOW);
        assertThat(((BusinessException) catchThrowable(() -> truck.handOver(LOAD, CARRIER, BigDecimal.ONE, false,
                PACKER, NOW))).errorCode()).isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);
        assertThat(truck.handOver(LOAD, CARRIER, BigDecimal.ZERO, false, PACKER, NOW)).isTrue();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("SCRUM-392: a full truckload waiting for dispatch splits its left-behind parcels "
            + "off into a new partial shipment of the same order and pack; a parcel shipment or one handed over does "
            + "not split")
    void splitOff() {
        UUID order = UUID.randomUUID();
        UUID pack = UUID.randomUUID();
        Shipment ftl = Shipment.ready(UUID.randomUUID(), "SO-9-S1", order, pack, UUID.randomUUID(), UUID.randomUUID(),
                DeliveryMode.FULL_TRUCKLOAD, 3, java.math.BigDecimal.ZERO, null, PACKER, NOW);
        ftl.pullRecorded();
        Shipment rest = ftl.splitOff(UUID.randomUUID(), "SO-9-S2", UUID.randomUUID(), 1, 2, "left behind", PACKER, NOW);
        assertThat(ftl.packageCount()).isEqualTo(2);
        assertThat(rest.packageCount()).isEqualTo(1);
        assertThat(rest.status()).isEqualTo(ShipmentStatus.READY_TO_DISPATCH);
        assertThat(rest.orderId()).isEqualTo(order);
        assertThat(rest.partial()).isTrue();
        assertThat(rest.deliveryMode()).isEqualTo(DeliveryMode.FULL_TRUCKLOAD);
        assertThat(rest.snapshot().packId()).isEqualTo(pack);
        assertThat(rest.pullRecorded()).singleElement().satisfies(step -> assertThat(step.status())
                .isEqualTo(ShipmentStatus.READY_TO_DISPATCH));
        Throwable parcel = catchThrowable(() -> ready().splitOff(UUID.randomUUID(), "X", UUID.randomUUID(), 1, 1, null,
                PACKER, NOW));
        assertThat(parcel).isInstanceOf(com.stockflow.common.error.BusinessException.class);
        assertThatThrownBy(() -> ftl.splitOff(UUID.randomUUID(), "X", UUID.randomUUID(), 1, 0, null, PACKER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
