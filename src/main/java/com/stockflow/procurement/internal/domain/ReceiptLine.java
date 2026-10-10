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

    private UUID qcLocationId;
    private Instant movedToQcAt;
    private UUID movedToQcBy;
    private final List<QcInspection> inspections;

    public ReceiptLine(UUID id, UUID poLineId, UUID inventoryItemId, UUID locationId, int receivedQuantity,
                       String lotNumber, LocalDate expiryDate, String note, boolean qcRequired,
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
        this.qcLocationId = qcLocationId;
        this.movedToQcAt = movedToQcAt;
        this.movedToQcBy = movedToQcBy;
        this.inspections = new ArrayList<>(inspections == null ? List.of() : inspections);
    }

    public static ReceiptLine counted(UUID id, UUID poLineId, UUID inventoryItemId, UUID locationId, int quantity,
                                      String lotNumber, LocalDate expiryDate, String note, boolean qcRequired) {
        return new ReceiptLine(id, poLineId, inventoryItemId, locationId, quantity, lotNumber, expiryDate, note,
                qcRequired, null, null, null, List.of());
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
    public UUID qcLocationId() { return qcLocationId; }
    public Instant movedToQcAt() { return movedToQcAt; }
    public UUID movedToQcBy() { return movedToQcBy; }
    public List<QcInspection> inspections() { return Collections.unmodifiableList(inspections); }
}
