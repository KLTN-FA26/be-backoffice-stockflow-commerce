package com.stockflow.inventory.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of {@code inventory.stock_package} (V20261020000100). <b>Not</b> the domain model. What
 * identifies a carton - its LPN, item, unit, nominal quantity and receipt line - is not updatable, as
 * {@code tg_stock_package_immutable} says.
 */
@Entity
@Table(name = "stock_package", schema = "inventory")
public class StockPackageJpaEntity extends BaseEntity {

    @Column(name = "lpn", nullable = false, updatable = false, length = 30)
    private String lpn;

    @Column(name = "inventory_item_id", nullable = false, updatable = false)
    private UUID inventoryItemId;

    @Column(name = "uom_conversion_id", nullable = false, updatable = false)
    private UUID uomConversionId;

    @Column(name = "nominal_qty", nullable = false, updatable = false)
    private int nominalQty;

    @Column(name = "received_qty")
    private Integer receivedQty;

    @Column(name = "location_code", nullable = false, length = 64)
    private String locationCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private StockPackageStatus status;

    @Column(name = "unopened", nullable = false)
    private boolean unopened;

    @Column(name = "goods_receipt_line_id", updatable = false)
    private UUID goodsReceiptLineId;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** Required by JPA. */
    protected StockPackageJpaEntity() {
    }

    public StockPackageJpaEntity(UUID id, String lpn, UUID inventoryItemId, UUID uomConversionId, int nominalQty,
                                 Integer receivedQty, String locationCode, StockPackageStatus status, boolean unopened,
                                 UUID goodsReceiptLineId, Instant closedAt) {
        super(id);
        this.lpn = lpn;
        this.inventoryItemId = inventoryItemId;
        this.uomConversionId = uomConversionId;
        this.nominalQty = nominalQty;
        this.goodsReceiptLineId = goodsReceiptLineId;
        apply(receivedQty, locationCode, status, unopened, closedAt);
    }

    public void apply(Integer receivedQty, String locationCode, StockPackageStatus status, boolean unopened,
                      Instant closedAt) {
        this.receivedQty = receivedQty;
        this.locationCode = locationCode;
        this.status = status;
        this.unopened = unopened;
        this.closedAt = closedAt;
    }

    public String getLpn() { return lpn; }
    public UUID getInventoryItemId() { return inventoryItemId; }
    public UUID getUomConversionId() { return uomConversionId; }
    public int getNominalQty() { return nominalQty; }
    public Integer getReceivedQty() { return receivedQty; }
    public String getLocationCode() { return locationCode; }
    public StockPackageStatus getStatus() { return status; }
    public boolean isUnopened() { return unopened; }
    public UUID getGoodsReceiptLineId() { return goodsReceiptLineId; }
    public Instant getClosedAt() { return closedAt; }
}
