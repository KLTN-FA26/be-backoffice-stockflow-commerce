package com.stockflow.procurement.internal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One counted line of a goods receipt: so many units of one PO line, from one lot, set down at one
 * receiving location. Part of the {@link GoodsReceipt} aggregate; changed only through it.
 *
 * <p>{@code qcRequired} is a snapshot of the item's flag at the time of counting: changing the flag
 * on the item later does not move an existing receipt from the 3-step flow to the 2-step one.</p>
 *
 * <p>{@code uomConversionId} is the packing unit the line's labelled cartons are in (SCRUM-473); null when the
 * line is counted loose, in the item's base unit. {@code receivedQuantity} is the total in base units either way:
 * the cartons hold part or all of it, and the rest is loose.</p>
 */
public final class ReceiptLine {

    private final UUID id;
    private final UUID poLineId;
    private final UUID inventoryItemId;
    private final UUID locationId;
    private final int receivedQuantity;
    private final String lotNumber;
    private final LocalDate expiryDate;
    private final String note;
    private final boolean qcRequired;
    private final UUID uomConversionId;

    private UUID qcLocationId;
    private Instant movedToQcAt;
    private UUID movedToQcBy;
    private final List<QcInspection> inspections;

    public ReceiptLine(UUID id, UUID poLineId, UUID inventoryItemId, UUID locationId, int receivedQuantity,
                       String lotNumber, LocalDate expiryDate, String note, boolean qcRequired, UUID uomConversionId,
                       UUID qcLocationId, Instant movedToQcAt, UUID movedToQcBy, List<QcInspection> inspections) {
        this.id = Objects.requireNonNull(id, "id");
        this.poLineId = Objects.requireNonNull(poLineId, "poLineId");
        this.inventoryItemId = Objects.requireNonNull(inventoryItemId, "inventoryItemId");
        this.locationId = Objects.requireNonNull(locationId, "locationId");
        if (receivedQuantity <= 0) {
            throw new IllegalArgumentException("A receipt line receives a positive quantity");
        }
        this.receivedQuantity = receivedQuantity;
        this.lotNumber = lotNumber;
        this.expiryDate = expiryDate;
        this.note = note;
        this.qcRequired = qcRequired;
        this.uomConversionId = uomConversionId;
        this.qcLocationId = qcLocationId;
        this.movedToQcAt = movedToQcAt;
        this.movedToQcBy = movedToQcBy;
        this.inspections = new ArrayList<>(inspections == null ? List.of() : inspections);
    }

    public static ReceiptLine counted(UUID id, UUID poLineId, UUID inventoryItemId, UUID locationId, int quantity,
                                      String lotNumber, LocalDate expiryDate, String note, boolean qcRequired,
                                      UUID uomConversionId) {
        return new ReceiptLine(id, poLineId, inventoryItemId, locationId, quantity, lotNumber, expiryDate, note,
                qcRequired, uomConversionId, null, null, null, List.of());
    }

    /**
     * The same line counting {@code quantity} units: the cut the manager's rejection of an excess makes (SCRUM-477).
     * Only a line nothing has happened to yet - not moved to QC, not inspected - is cut; the aggregate cuts only
     * receipts that are waiting for approval, which have none of either.
     */
    ReceiptLine withReceivedQuantity(int quantity) {
        return new ReceiptLine(id, poLineId, inventoryItemId, locationId, quantity, lotNumber, expiryDate, note,
                qcRequired, uomConversionId, qcLocationId, movedToQcAt, movedToQcBy, inspections);
    }

    public boolean movedToQc() {
        return movedToQcAt != null;
    }

    public boolean inspected() {
        return !inspections.isEmpty();
    }

    /** Units that end up waiting for putaway: everything on a 2-step line, the accepted part on a 3-step one. */
    public int quantityForPutaway() {
        if (!qcRequired) {
            return receivedQuantity;
        }
        return inspections.stream().filter(i -> i.outcome() == QcOutcome.ACCEPTED)
                .mapToInt(QcInspection::quantity).sum();
    }

    void markMovedToQc(UUID qcLocation, UUID userId, Instant now) {
        this.qcLocationId = Objects.requireNonNull(qcLocation, "qcLocation");
        this.movedToQcBy = Objects.requireNonNull(userId, "userId");
        this.movedToQcAt = Objects.requireNonNull(now, "now");
    }

    void record(List<QcInspection> decided) {
        inspections.addAll(decided);
    }

    public UUID id() { return id; }
    public UUID poLineId() { return poLineId; }
    public UUID inventoryItemId() { return inventoryItemId; }
    public UUID locationId() { return locationId; }
    public int receivedQuantity() { return receivedQuantity; }
    public String lotNumber() { return lotNumber; }
    public LocalDate expiryDate() { return expiryDate; }
    public String note() { return note; }
    public boolean qcRequired() { return qcRequired; }
    public UUID uomConversionId() { return uomConversionId; }
    public UUID qcLocationId() { return qcLocationId; }
    public Instant movedToQcAt() { return movedToQcAt; }
    public UUID movedToQcBy() { return movedToQcBy; }
    public List<QcInspection> inspections() { return Collections.unmodifiableList(inspections); }
}
