package com.stockflow.procurement.internal.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One counted line of a goods receipt: so many units of one production order line, in whole cartons of the line's
 * standard count, in the production order's lot, set down at one receiving location (4A-5, kltn-docs 03 BR-03, BR-12,
 * BR-13). Part of the {@link GoodsReceipt} aggregate; changed only through it.
 *
 * <p>{@code uomConversionId} is the packing unit the line's labelled cartons are in (SCRUM-473).
 * {@code receivedQuantity} is the total in base units.</p>
 *
 * <p>A line of a historical purchase receipt has no production order line ({@code productionOrderLineId} null); it
 * may carry no carton unit (counted loose) and, for an expiry-tracked item, an expiry date. Its purchase order line
 * is not read any more (4A-8).</p>
 *
 * <p>There is no quality check at the warehouse (kltn-docs 03 BR-07): every unit counted goes on to putaway.</p>
 */
public final class ReceiptLine {

    private final UUID id;
    private final UUID productionOrderLineId;
    private final UUID inventoryItemId;
    private final UUID locationId;
    private final int receivedQuantity;
    private final String lotNumber;
    private final LocalDate expiryDate;
    private final String note;
    private final UUID uomConversionId;

    public ReceiptLine(UUID id, UUID productionOrderLineId, UUID inventoryItemId, UUID locationId,
                       int receivedQuantity, String lotNumber, LocalDate expiryDate, String note,
                       UUID uomConversionId) {
        this.id = Objects.requireNonNull(id, "id");
        this.productionOrderLineId = productionOrderLineId;
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

    /**
     * 4A-5: {@code quantity} units - whole cartons of {@code uomConversionId} - of a production order line, in the
     * production order's lot {@code lotNumber}, with no expiry date (kltn-docs 03 BR-03, BR-06).
     */
    public static ReceiptLine countedForProductionOrder(UUID id, UUID productionOrderLineId, UUID inventoryItemId,
                                                        UUID locationId, int quantity, String lotNumber, String note,
                                                        UUID uomConversionId) {
        return new ReceiptLine(id, Objects.requireNonNull(productionOrderLineId, "productionOrderLineId"),
                inventoryItemId, locationId, quantity, Objects.requireNonNull(lotNumber, "lotNumber"), null, note,
                Objects.requireNonNull(uomConversionId, "uomConversionId"));
    }

    public UUID id() { return id; }
    /** The production order line counted (4A-5); null on a historical purchase receipt. */
    public UUID productionOrderLineId() { return productionOrderLineId; }
    public UUID inventoryItemId() { return inventoryItemId; }
    public UUID locationId() { return locationId; }
    public int receivedQuantity() { return receivedQuantity; }
    public String lotNumber() { return lotNumber; }
    public LocalDate expiryDate() { return expiryDate; }
    public String note() { return note; }
    public UUID uomConversionId() { return uomConversionId; }
}
