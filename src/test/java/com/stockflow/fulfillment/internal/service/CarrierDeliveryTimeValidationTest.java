package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.fulfillment.internal.domain.CarrierEventKind;
import com.stockflow.fulfillment.internal.domain.CarrierEventResult;
import com.stockflow.fulfillment.internal.domain.DeliveryFailureReason;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * WF-05 BE-38 (audit L-12): a delivery time a carrier reports is held to the rules a person's is - not in the future,
 * not before the handover, a delivery after a failure not before that failure - and refused, the report goes to the
 * error list (NEEDS_REVIEW). A carrier's clock a few minutes ahead is dated now. Every path that makes a shipment
 * DELIVERED (by hand, corrected, by the carrier) now passes the same check.
 */
class CarrierDeliveryTimeValidationTest {

    private static final Instant HANDED_OVER = Instant.parse("2026-11-20T01:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private static Shipment handedOver() {
        Shipment shipment = Shipment.ready(UUID.randomUUID(), "SO-1-S1", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, 1, null, UUID.randomUUID(), HANDED_OVER.minusSeconds(3600));
        shipment.handOver(UUID.randomUUID(), UUID.randomUUID(), false, UUID.randomUUID(), HANDED_OVER);
        return shipment;
    }

    private static ErrorCode refusal(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("in the future beyond a clock's drift: refused, the shipment unchanged")
    void future() {
        Shipment shipment = handedOver();
        assertThat(refusal(() -> shipment.carrierDelivered(NOW.plus(Duration.ofHours(7)), NOW)))
                .as("a time in another zone read as UTC").isEqualTo(ErrorCode.SHIPMENT_DELIVERY_TIME_IN_FUTURE);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.HANDED_OVER);
    }

    @Test
    @DisplayName("a few minutes ahead: a clock, not the future - dated now")
    void clockDrift() {
        Shipment shipment = handedOver();
        assertThat(shipment.carrierDelivered(NOW.plusSeconds(120), NOW)).isTrue();
        assertThat(shipment.snapshot().outcomeAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("before the handover: refused")
    void beforeHandover() {
        Shipment shipment = handedOver();
        assertThat(refusal(() -> shipment.carrierDelivered(HANDED_OVER.minusSeconds(60), NOW)))
                .isEqualTo(ErrorCode.SHIPMENT_DELIVERY_TIME_BEFORE_HANDOVER);
    }

    @Test
    @DisplayName("a delivery after a failure, dated before that failure: refused; after it: taken as the carrier dated it")
    void afterFailure() {
        Shipment shipment = handedOver();
        Instant failedAt = HANDED_OVER.plus(Duration.ofHours(3));
        shipment.carrierFailed(DeliveryFailureReason.CUSTOMER_ABSENT, null, failedAt);
        assertThat(refusal(() -> shipment.carrierDelivered(failedAt.minusSeconds(60), NOW)))
                .isEqualTo(ErrorCode.SHIPMENT_DELIVERY_TIME_BEFORE_FAILURE);
        assertThat(shipment.carrierDelivered(failedAt.plusSeconds(60), NOW)).isTrue();
        assertThat(shipment.snapshot().outcomeAt()).isEqualTo(failedAt.plusSeconds(60));
    }

    @Test
    @DisplayName("the same delivery reported again changes nothing and is not checked again")
    void repeat() {
        Shipment shipment = handedOver();
        assertThat(shipment.carrierDelivered(HANDED_OVER.plusSeconds(60), NOW)).isTrue();
        assertThat(shipment.carrierDelivered(HANDED_OVER.minusSeconds(60), NOW)).isFalse();
    }

    @Test
    @DisplayName("refused, the carrier's report goes to the error list for the warehouse manager: NEEDS_REVIEW")
    void refusedIsReviewed() {
        CarrierApplierHarness h = new CarrierApplierHarness();
        h.matched(CarrierEventKind.DELIVERED, h.shipment("S1", ShipmentStatus.HANDED_OVER), NOW.plusSeconds(86_400));
        CarrierEventResult result = h.applier.failed(h.lastEvent.getId(), new BusinessException(
                ErrorCode.SHIPMENT_DELIVERY_TIME_IN_FUTURE, "Delivery time is after now"));

        assertThat(result).isEqualTo(CarrierEventResult.NEEDS_REVIEW);
        assertThat(h.lastEvent.getResultNote()).contains("after now");
    }
}
