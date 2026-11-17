package com.stockflow.fulfillment.internal.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One webhook call, read in the system's terms (kltn-docs 09 §4.5 steps 1, 4, 5): what happened, which load and
 * shipment it is about as the carrier names them, and when the carrier says it happened.
 *
 * @param kind           what happened
 * @param reference      how the carrier names the load: the internal load code (WEBHOOK_LOAD), or its own order id,
 *                       which is the load's waybill number (API_BOOKING, BR-22)
 * @param stop           the drop-off the report is about, numbered as the order was created (1 = the first shipment);
 *                       null for a report about the order or one naming a shipment by code
 * @param shipmentCode   the shipment it names, or null
 * @param reportedAt     when the carrier says it happened; null when it gave no time for this kind of report (the
 *                       report is then never judged older than another)
 * @param waybillNo      the waybill number it gives, or null
 * @param vehiclePlate   the plate it gives, or null
 * @param driverName     the driver's name it gives, or null
 * @param driverPhone    the driver's phone it gives, or null
 * @param failureWording the carrier's own words for a failed delivery or a cancel, or null
 */
public record CarrierReport(CarrierEventKind kind, String reference, Integer stop, String shipmentCode,
                            Instant reportedAt, String waybillNo, String vehiclePlate, String driverName,
                            String driverPhone, String failureWording) {

    public CarrierReport {
        Objects.requireNonNull(kind, "kind");
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("A carrier report names its load or order");
        }
        if (stop != null && stop < 1) {
            throw new IllegalArgumentException("Drop-offs are numbered from 1");
        }
    }

    /** A report about the order or the load as a whole. */
    public static CarrierReport of(CarrierEventKind kind, String reference, Instant reportedAt) {
        return new CarrierReport(kind, reference, null, null, reportedAt, null, null, null, null, null);
    }

    /** Whether it names one shipment, by its drop-off or its code. */
    public boolean namesShipment() {
        return stop != null || shipmentCode != null;
    }
}
