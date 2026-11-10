package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code procurement.goods_receipt_lines}. {@code received_qty} is NUMERIC(18,3) in the schema; the
 * stock it becomes is counted in whole units, so the domain carries an int and this maps the column
 * as it is.
 *
 * <p>The PO line, item and lot are what the line IS and are never written again. What was counted —
 * where, how many, expiry, note — is recounted while the receipt is a draft
 * ({@link #recount}); {@code tg_goods_receipt_lines_frozen} refuses it once the receipt is past DRAFT.</p>
 *
 * <p>{@code uom_conversion_id}: the packing unit the line's cartons are in, NULL for a line counted loose in the
 * base unit (V20261020000400). Recounted with the rest while DRAFT; {@code tg_goods_receipt_lines_unit} checks it
 * is a unit of the line's item.</p>
 */
@Entity
@Table(name = "goods_receipt_lines", schema = "procurement")
public class GoodsReceiptLineJpaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false, updatable = false)
    private GoodsReceiptJpaEntity receipt;

    @Column(name = "po_line_id", updatable = false)
    private UUID poLineId;

    /** 4A-5: the production order line a line of a production receipt counts; null on a purchase receipt. */
    @Column(name = "production_order_line_id", updatable = false)
    private UUID productionOrderLineId;

    @Column(name = "inventory_item_id", nullable = false, updatable = false)
    private UUID inventoryItemId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "received_qty", nullable = false, precision = 18, scale = 3)
    private BigDecimal receivedQty;

    @Column(name = "lot_number", length = 64, updatable = false)
    private String lotNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "uom_conversion_id")
    private UUID uomConversionId;

    protected GoodsReceiptLineJpaEntity() {
    }

    public GoodsReceiptLineJpaEntity(UUID id, UUID poLineId, UUID inventoryItemId, UUID locationId,
                                     BigDecimal receivedQty, String lotNumber, LocalDate expiryDate, String note,
                                     UUID uomConversionId) {
        this(id, poLineId, null, inventoryItemId, locationId, receivedQty, lotNumber, expiryDate, note,
                uomConversionId);
    }

    public GoodsReceiptLineJpaEntity(UUID id, UUID poLineId, UUID productionOrderLineId, UUID inventoryItemId,
                                     UUID locationId, BigDecimal receivedQty, String lotNumber, LocalDate expiryDate,
                                     String note, UUID uomConversionId) {
        super(id);
        this.poLineId = poLineId;
        this.productionOrderLineId = productionOrderLineId;
        this.inventoryItemId = inventoryItemId;
        this.locationId = locationId;
        this.receivedQty = receivedQty;
        this.lotNumber = lotNumber;
        this.expiryDate = expiryDate;
        this.note = note;
        this.uomConversionId = uomConversionId;
    }

    void attachTo(GoodsReceiptJpaEntity owner) {
        this.receipt = owner;
    }

    /** The draft's line counted again. Unchanged values leave the row clean, so a confirmed line is not written. */
    public void recount(UUID locationId, BigDecimal receivedQty, LocalDate expiryDate, String note,
                        UUID uomConversionId) {
        this.locationId = locationId;
        if (this.receivedQty == null || this.receivedQty.compareTo(receivedQty) != 0) {
            this.receivedQty = receivedQty;
        }
        this.expiryDate = expiryDate;
        this.note = note;
        this.uomConversionId = uomConversionId;
    }

    public UUID getPoLineId() { return poLineId; }
    public UUID getProductionOrderLineId() { return productionOrderLineId; }
    public UUID getInventoryItemId() { return inventoryItemId; }
    public UUID getLocationId() { return locationId; }
    public BigDecimal getReceivedQty() { return receivedQty; }
    public String getLotNumber() { return lotNumber; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public String getNote() { return note; }
    public UUID getUomConversionId() { return uomConversionId; }
}
