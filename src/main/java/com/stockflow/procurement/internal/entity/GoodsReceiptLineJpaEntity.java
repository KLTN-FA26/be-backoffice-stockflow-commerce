package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code procurement.goods_receipt_lines}. {@code received_qty} is NUMERIC(18,3) in the schema; the
 * stock it becomes is counted in whole units, so the domain carries an int and this maps the column
 * as it is.
 *
 * <p>The production order line, item and lot are what the line IS and are never written again. What was counted —
 * where, how many, note — is recounted while the receipt is a draft
 * ({@link #recount}); {@code tg_goods_receipt_lines_frozen} refuses it once the receipt is past DRAFT.</p>
 *
 * <p>{@code uom_conversion_id}: the packing unit the line's cartons are in - a production line counts whole cartons
 * of its standard count (kltn-docs 03 BR-12, BR-13). Recounted with the rest while DRAFT;
 * {@code tg_goods_receipt_lines_unit} checks it is a unit of the line's item. It and {@code lot_number} are NOT NULL
 * since {@code V20261113000200}: no line is counted loose or without a lot any more, and an old purchase line was
 * given its item's base unit and its receipt's number.</p>
 */
@Entity
@Table(name = "goods_receipt_lines", schema = "procurement")
public class GoodsReceiptLineJpaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false, updatable = false)
    private GoodsReceiptJpaEntity receipt;

    /**
     * 4A-5: the production order line a line of a production receipt counts; null on a historical purchase receipt,
     * whose {@code po_line_id} went with purchasing ({@code V20261113000200} archived and dropped it).
     */
    @Column(name = "production_order_line_id", updatable = false)
    private UUID productionOrderLineId;

    @Column(name = "inventory_item_id", nullable = false, updatable = false)
    private UUID inventoryItemId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "received_qty", nullable = false, precision = 18, scale = 3)
    private BigDecimal receivedQty;

    @Column(name = "lot_number", nullable = false, length = 64, updatable = false)
    private String lotNumber;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "uom_conversion_id", nullable = false)
    private UUID uomConversionId;

    protected GoodsReceiptLineJpaEntity() {
    }

    public GoodsReceiptLineJpaEntity(UUID id, UUID productionOrderLineId, UUID inventoryItemId, UUID locationId,
                                     BigDecimal receivedQty, String lotNumber, String note, UUID uomConversionId) {
        super(id);
        this.productionOrderLineId = productionOrderLineId;
        this.inventoryItemId = inventoryItemId;
        this.locationId = locationId;
        this.receivedQty = receivedQty;
        this.lotNumber = lotNumber;
        this.note = note;
        this.uomConversionId = uomConversionId;
    }

    void attachTo(GoodsReceiptJpaEntity owner) {
        this.receipt = owner;
    }

    /** The draft's line counted again. Unchanged values leave the row clean, so a confirmed line is not written. */
    public void recount(UUID locationId, BigDecimal receivedQty, String note, UUID uomConversionId) {
        this.locationId = locationId;
        if (this.receivedQty == null || this.receivedQty.compareTo(receivedQty) != 0) {
            this.receivedQty = receivedQty;
        }
        this.note = note;
        this.uomConversionId = uomConversionId;
    }

    public UUID getProductionOrderLineId() { return productionOrderLineId; }
    public UUID getInventoryItemId() { return inventoryItemId; }
    public UUID getLocationId() { return locationId; }
    public BigDecimal getReceivedQty() { return receivedQty; }
    public String getLotNumber() { return lotNumber; }
    public String getNote() { return note; }
    public UUID getUomConversionId() { return uomConversionId; }
}
