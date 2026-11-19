package com.stockflow.fulfillment.internal.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A carrier's webhook reported shipments delivered with a proof of delivery at {@code podUrl} (kltn-docs 09 §4.3 step
 * 2, §4.5 step 7: "Giao thành công (kèm POD nếu có) ... POD đính kèm"). Published in the transaction that applied the
 * event; {@link CarrierPodFetcher} fetches the file after commit and keeps it with each shipment, as an uploaded POD is.
 * Internal to fulfillment: no other module listens.
 *
 * @param carrierEventId the event that reported it, which the kept file names instead of a person
 * @param podUrl         the https address the carrier gave
 * @param shipmentIds    the shipments the event delivered
 */
public record CarrierPodReported(UUID carrierEventId, String podUrl, List<UUID> shipmentIds) {

    public CarrierPodReported {
        Objects.requireNonNull(carrierEventId, "carrierEventId");
        Objects.requireNonNull(podUrl, "podUrl");
        shipmentIds = List.copyOf(shipmentIds);
    }
}
