package com.stockflow.procurement.internal.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One counted line of a goods receipt: so many units of one PO line (of the item itself on a receipt without a PO,
 * where {@code poLineId} is null), from one lot, set down at one
 * receiving location. Part of the {@link GoodsReceipt} aggregate; changed only through it.
 *
 * <p>{@code uomConversionId} is the packing unit the line's labelled cartons are in (SCRUM-473); null when the
 * line is counted loose, in the item's base unit. {@code receivedQuantity} is the total in base units either way:
 * the cartons hold part or all of it, and the rest is loose.</p>
 *
 * <p>There is no quality check at the warehouse (kltn-docs 03 BR-07): every unit counted goes on to putaway.</p>
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
    private final UUID uomConversionId;

    public ReceiptLine(UUID id, UUID poLineId, UUID inventoryItemId, UUID locationId, int receivedQuantity,
                       String lotNumber, LocalDate expiryDate, String note, UUID uomConversionId) {
        this.id = Objects.requireNonNull(id, "id");
        this.poLineId = poLineId;
        this.inventoryItemId = Objects.requireNonNull(inventoryItemId, "inventoryItemId");
        this.locationId = Objects.requireNonNull(locationId, "locationId");
        if (receivedQuantity <= 0) {
            throw new IllegalArgumentException("A receipt line receives a positive quantity");
        }
        this.receivedQuantity = receivedQuantity;
        this.lotNumber = lotNumber;
        this.expiryDate = expiryDate;
        this.note = note;
        this.uomConversionId = uomConversionId;
    }

    public static ReceiptLine counted(UUID id, UUID poLineId, UUID inventoryItemId, UUID locationId, int quantity,
                                      String lotNumber, LocalDate expiryDate, String note, UUID uomConversionId) {
        return new ReceiptLine(id, poLineId, inventoryItemId, locationId, quantity, lotNumber, expiryDate, note,
                uomConversionId);
    }

    /**
     * The same line counting {@code quantity} units: the cut the manager's rejection of an excess makes (SCRUM-477).
     * The aggregate cuts only receipts that are waiting for approval, whose goods are not counted in yet.
     */
    ReceiptLine withReceivedQuantity(int quantity) {
        return new ReceiptLine(id, poLineId, inventoryItemId, locationId, quantity, lotNumber, expiryDate, note,
                uomConversionId);
    }

    public UUID id() { return id; }
    public UUID poLineId() { return poLineId; }
    public UUID inventoryItemId() { return inventoryItemId; }
    public UUID locationId() { return locationId; }
    public int receivedQuantity() { return receivedQuantity; }
    public String lotNumber() { return lotNumber; }
    public LocalDate expiryDate() { return expiryDate; }
    public String note() { return note; }
    public UUID uomConversionId() { return uomConversionId; }
}
