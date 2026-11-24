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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 BE-15 (audit L-6): a carrier report about one shipment is held against the reports about that shipment and the
 * outcomes reported for the whole load - never the vehicle's milestones, never other shipments' reports. An outdated
 * outcome about a shipment that has none yet goes to the error list (NEEDS_REVIEW), not silently STALE.
 */
class CarrierEventStalenessTest {

    private static final Instant T14 = Instant.parse("2026-11-20T07:00:00Z");
    private static final Instant T15 = Instant.parse("2026-11-20T08:00:00Z");

    private final CarrierApplierHarness h = new CarrierApplierHarness();
    private final CarrierJpaEntity truck = CarrierApplierHarness.carrier(CarrierConnectionType.WEBHOOK_LOAD);

    @Test
    @DisplayName("L-6: drop 3's late 'delivered' (14:00) after the vehicle's 'returning' milestone (15:00) is applied")
    void milestoneDoesNotOutdateADelivery() {
        UUID drop3 = h.shipment("S3", ShipmentStatus.HANDED_OVER);
        when(h.events.lastAppliedToLoad(h.loadId)).thenReturn(T15);

        CarrierEventResult result = h.apply(truck, h.matched(CarrierEventKind.DELIVERED, drop3, T14));

        assertThat(result).isEqualTo(CarrierEventResult.APPLIED);
        assertThat(h.applied).containsExactly(Map.entry(drop3, ShipmentStatus.DELIVERED));
    }

    @Test
    @DisplayName("a report about another shipment does not outdate this one's")
    void otherShipmentsDoNotOutdate() {
        UUID drop1 = h.shipment("S1", ShipmentStatus.DELIVERED);
        UUID drop2 = h.shipment("S2", ShipmentStatus.HANDED_OVER);
        when(h.events.lastAppliedToShipment(drop1)).thenReturn(T15);

        assertThat(h.apply(truck, h.matched(CarrierEventKind.DELIVERED, drop2, T14)))
                .isEqualTo(CarrierEventResult.APPLIED);
        assertThat(h.applied).containsExactly(Map.entry(drop2, ShipmentStatus.DELIVERED));
    }

    @Test
    @DisplayName("older than this shipment's own last report: STALE - it has an outcome already")
    void olderThanItsOwnReport() {
        UUID drop = h.shipment("S1", ShipmentStatus.DELIVERY_FAILED);
        when(h.events.lastAppliedToShipment(drop)).thenReturn(T15);

        assertThat(h.apply(truck, h.matched(CarrierEventKind.DELIVERED, drop, T14)))
                .isEqualTo(CarrierEventResult.STALE);
        verify(h.outcomes, never()).carrierOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("older than an outcome reported for the whole load, about a shipment with no outcome yet: NEEDS_REVIEW "
            + "naming it, nothing applied")
    void outdatedButNoOutcomeYet() {
        UUID drop = h.shipment("S7", ShipmentStatus.HANDED_OVER);
        when(h.events.lastOutcomeAppliedToLoad(h.loadId)).thenReturn(T15);

        assertThat(h.apply(truck, h.matched(CarrierEventKind.DELIVERED, drop, T14)))
                .isEqualTo(CarrierEventResult.NEEDS_REVIEW);
        assertThat(h.lastEvent.getResultNote()).contains("S7").contains("no outcome yet");
        verify(h.outcomes, never()).carrierOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a milestone is still outdated by any later report of the load, as before")
    void milestonesKeepTheLoadClock() {
        h.shipment("S1", ShipmentStatus.HANDED_OVER);
        when(h.events.lastAppliedToLoad(h.loadId)).thenReturn(T15);

        assertThat(h.apply(truck, h.matched(CarrierEventKind.IN_TRANSIT, null, T14)))
                .isEqualTo(CarrierEventResult.STALE);
    }

    @Test
    @DisplayName("a load-level outcome leaves a shipment with a later report of its own as that report has it")
    void loadOutcomeSkipsShipmentsWithANewerOwnReport() {
        UUID failedLater = h.shipment("S1", ShipmentStatus.DELIVERY_FAILED);
        UUID silent = h.shipment("S2", ShipmentStatus.HANDED_OVER);
        when(h.events.lastAppliedToShipment(failedLater)).thenReturn(T15);

        assertThat(h.apply(truck, h.matched(CarrierEventKind.DELIVERED, null, T14)))
                .isEqualTo(CarrierEventResult.APPLIED);
        assertThat(h.applied).containsExactly(Map.entry(silent, ShipmentStatus.DELIVERED));
        assertThat(h.lastEvent.getResultNote()).contains("a later report of their own").contains("S1");
    }
}
