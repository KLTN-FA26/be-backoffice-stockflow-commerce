package com.stockflow.notification.api;

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
 * @param slip             the slip it was sent with; null for one sent before slips were kept. The order's processing
 *                         price is not here: prices are agreed by other departments, outside the system, and neither the
 *                         slip nor the e-mail names one (owner, 2026-11-19)
 * @param filesAvailableUntil until when the links to the slip, the print files and the original images work
 */
public record ProductionOrderMail(UUID productionOrderId, String code, String producerName, String channel,
                                  String producerEmail, UUID coordinatorId, String status, LocalDate needByDate,
                                  String warehouseName, String warehouseAddress, boolean sampleApprovalRequired,
                                  String note, String stopReason,
                                  List<Line> lines, File slip, Instant filesAvailableUntil) {

    public ProductionOrderMail {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * One line: what is made, the print file it is printed from and the customer's original images that file was laid
     * out from.
     *
     * @param cartonSize the carton's outer size, "L x W x H cm", or null when the SKU has none recorded
     * @param originals  the customer's own images the approved design places (kltn-docs 12 §3, 17 §4.3 step 7; FE
     *                   request 2026-11-18 BE-6) - what a press separating spot colours works from - one link each;
     *                   empty for a design approved before images were frozen with it
     */
    public record Line(String sku, String name, int cartons, int unitsPerCarton, int totalUnits, String cartonSize,
                       String packingRequirements, File printFile, List<File> originals) {

        public Line {
            originals = originals == null ? List.of() : List.copyOf(originals);
        }

        /** A line with no original images. */
        public Line(String sku, String name, int cartons, int unitsPerCarton, int totalUnits, String cartonSize,
                    String packingRequirements, File printFile) {
            this(sku, name, cartons, unitsPerCarton, totalUnits, cartonSize, packingRequirements, printFile, List.of());
        }
    }

    /** A file the producer downloads, by the route of this application that serves it. */
    public record File(String name, String downloadPath) {
    }
}
