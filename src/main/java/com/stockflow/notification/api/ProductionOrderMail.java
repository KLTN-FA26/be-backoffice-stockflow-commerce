package com.stockflow.notification.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A production order as its producer reads it (4A-3, kltn-docs 17 §4.3): what {@link ProductionOrderDirectory} hands
 * notification when a message to the producer is written. The files are named by the route that serves them; the
 * message signs each into a link that works until {@code filesAvailableUntil}, and none is ever stored.
 *
 * @param channel          {@code EMAIL} (the system sends it to {@code producerEmail}) or {@code PDF} (the
 *                         coordinator hands the slip over; the system sends nothing)
 * @param coordinatorId    who drew it up: the producer's reply goes to them
 * @param status           the production order's status now ({@code CANCELLED}, {@code CLOSED} for a stopped one)
 * @param stopReason       why it was cancelled or closed, as the coordinator wrote it; null while it goes on
 * @param slip             the slip it was sent with; null for one sent before slips were kept
 * @param filesAvailableUntil until when the links to the slip and the print files work
 */
public record ProductionOrderMail(UUID productionOrderId, String code, String producerName, String channel,
                                  String producerEmail, UUID coordinatorId, String status, LocalDate needByDate,
                                  String warehouseName, String warehouseAddress, boolean sampleApprovalRequired,
                                  BigDecimal unitPrice, String currency, String note, String stopReason,
                                  List<Line> lines, File slip, Instant filesAvailableUntil) {

    public ProductionOrderMail {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * One line: what is made and the print file it is printed from.
     *
     * @param cartonSize the carton's outer size, "L x W x H cm", or null when the SKU has none recorded
     */
    public record Line(String sku, String name, int cartons, int unitsPerCarton, int totalUnits, String cartonSize,
                       String packingRequirements, File printFile) {
    }

    /** A file the producer downloads, by the route of this application that serves it. */
    public record File(String name, String downloadPath) {
    }
}
