package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A file of waybill numbers, checked or imported (kltn-docs 09 §4.4 step 2-3, BR-25).
 *
 * @param importId    absent on a preview: nothing was written
 * @param rowsApplied on a preview, the lines that would apply now
 */
public record WaybillImportResponse(UUID importId, String fileName, UUID uploadedBy, Instant uploadedAt, int rowsTotal,
                                    int rowsApplied, int rowsRejected, List<Line> lines) {

    /**
     * One line of the file and what it comes to.
     *
     * @param outcome    NEW, OVERWRITE (the load had another number: "sẽ ghi đè"), SAME or ERROR
     * @param problem    why it is not applied: MISSING_FIELDS, INVALID_WAYBILL, INVALID_VEHICLE_DETAILS,
     *                   LOAD_NOT_FOUND, LOAD_OUT_OF_SCOPE, LOAD_REPEATED, LOAD_NOT_BOOKED, LOAD_CANCELLED, LOAD_LEFT,
     *                   WAYBILL_FROM_CARRIER_API, DUPLICATE_IN_FILE, DUPLICATE_IN_CARRIER
     * @param holderCode for DUPLICATE_IN_CARRIER on a preview or an import's answer, the load holding the number
     */
    public record Line(int line, String loadCode, String waybillNo, String vehiclePlate, String driverName,
                       String driverPhone, UUID loadId, String outcome, String problem, String holderCode) {
    }

    /** One import in the list. */
    public record Row(UUID importId, String fileName, UUID uploadedBy, Instant uploadedAt, int rowsTotal,
                      int rowsApplied, int rowsRejected) {
    }
}
