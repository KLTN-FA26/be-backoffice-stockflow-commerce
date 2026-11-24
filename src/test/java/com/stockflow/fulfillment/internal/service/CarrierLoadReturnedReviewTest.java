package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.domain.CarrierConnectionType;
import com.stockflow.fulfillment.internal.domain.CarrierEventKind;
import com.stockflow.fulfillment.internal.domain.CarrierEventResult;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-05 BE-14 (decision Q-7, audit L-5): a report that the goods are back, naming no shipment, never forces a shipment
 * with no report of its own - one still HANDED_OVER may be at its customer - through failed to RETURNED, which is final
 * and closes its units. It goes to the error list for the warehouse manager. A shipment the report names, or one
 * already reported failed, is returned as before.
 */
class CarrierLoadReturnedReviewTest {

    private static final Instant AT = Instant.parse("2026-11-20T08:00:00Z");

    private final CarrierApplierHarness h = new CarrierApplierHarness();
    private final CarrierJpaEntity truck = CarrierApplierHarness.carrier(CarrierConnectionType.WEBHOOK_LOAD);

    @Test
    @DisplayName("L-5: load-level RETURNED - X (failed) is returned, Y (no report yet) is not touched: NEEDS_REVIEW "
            + "naming Y")
    void loadLevelLeavesSilentShipmentsToReview() {
        UUID x = h.shipment("X", ShipmentStatus.DELIVERY_FAILED);
        h.shipment("Y", ShipmentStatus.HANDED_OVER);
        h.shipment("Z", ShipmentStatus.DELIVERED);

        CarrierEventResult result = h.apply(truck, h.matched(CarrierEventKind.RETURNED, null, AT));

        assertThat(result).isEqualTo(CarrierEventResult.NEEDS_REVIEW);
        assertThat(h.applied).containsExactly(Map.entry(x, ShipmentStatus.RETURNED));
        assertThat(h.lastEvent.getResultNote()).contains("Y").doesNotContain("Z");
    }

    @Test
    @DisplayName("every undelivered shipment already failed: all returned, APPLIED")
    void allFailedAllReturned() {
        UUID x = h.shipment("X", ShipmentStatus.DELIVERY_FAILED);
        UUID y = h.shipment("Y", ShipmentStatus.DELIVERY_FAILED);

        assertThat(h.apply(truck, h.matched(CarrierEventKind.RETURNED, null, AT)))
                .isEqualTo(CarrierEventResult.APPLIED);
        assertThat(h.applied).containsExactly(Map.entry(x, ShipmentStatus.RETURNED),
                Map.entry(y, ShipmentStatus.RETURNED));
    }

    @Test
    @DisplayName("a report naming a shipment still handed over is its own report: failed (OTHER) then returned (E21)")
    void namedShipmentKeepsE21() {
        UUID y = h.shipment("Y", ShipmentStatus.HANDED_OVER);

        assertThat(h.apply(truck, h.matched(CarrierEventKind.RETURNED, y, AT)))
                .isEqualTo(CarrierEventResult.APPLIED);
        assertThat(h.applied).containsExactly(Map.entry(y, ShipmentStatus.DELIVERY_FAILED),
                Map.entry(y, ShipmentStatus.RETURNED));
    }

    @Test
    @DisplayName("an API carrier's order-level RETURNED: as before, a drop-off with no outcome is not guessed")
    void apiCarrierUnchanged() {
        CarrierJpaEntity ahamove = CarrierApplierHarness.carrier(CarrierConnectionType.API_BOOKING);
        UUID failed = h.shipment("A", ShipmentStatus.DELIVERY_FAILED);
        h.shipment("B", ShipmentStatus.HANDED_OVER);

        assertThat(h.apply(ahamove, h.matched(CarrierEventKind.RETURNED, null, AT)))
                .isEqualTo(CarrierEventResult.NEEDS_REVIEW);
        assertThat(h.applied).containsExactly(Map.entry(failed, ShipmentStatus.RETURNED));
    }
}
