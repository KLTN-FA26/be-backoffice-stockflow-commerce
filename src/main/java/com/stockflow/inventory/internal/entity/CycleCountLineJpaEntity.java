package com.stockflow.inventory.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code inventory.cycle_count_line}. The slot (bin, SKU, lot, carton) is fixed when the line is created, except that a
 * carton line's bin follows its carton when it is moved whole; otherwise only what the count found changes.
 */
@Entity
@Table(name = "cycle_count_line", schema = "inventory")
public class CycleCountLineJpaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_count_id", nullable = false, updatable = false)
    private CycleCountJpaEntity cycleCount;

    @Column(name = "location_code", nullable = false, length = 64)
    private String locationCode;

    @Column(name = "sku", nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "lot_number", length = 64, updatable = false)
    private String lotNumber;

    @Column(name = "stock_package_id", updatable = false)
    private UUID stockPackageId;

    @Column(name = "expected_qty", nullable = false)
    private int expectedQty;

    @Column(name = "counted_qty")
    private Integer countedQty;

    @Column(name = "counted_by")
    private UUID countedBy;

    @Column(name = "counted_at")
    private Instant countedAt;

    @Column(name = "recount_required", nullable = false)
    private boolean recountRequired;

    protected CycleCountLineJpaEntity() {
    }

    public CycleCountLineJpaEntity(UUID id, String locationCode, String sku, String lotNumber, UUID stockPackageId,
                                   int expectedQty, Integer countedQty, UUID countedBy, Instant countedAt,
                                   boolean recountRequired) {
        super(id);
        this.locationCode = locationCode;
        this.sku = sku;
        this.lotNumber = lotNumber;
        this.stockPackageId = stockPackageId;
        apply(expectedQty, countedQty, countedBy, countedAt, recountRequired);
    }

    /** A carton line following its carton; the line trigger re-checks the carton is at this bin. */
    public void relocate(String locationCode) {
        this.locationCode = locationCode;
    }

    void attachTo(CycleCountJpaEntity count) {
        this.cycleCount = count;
    }

    public void apply(int expectedQty, Integer countedQty, UUID countedBy, Instant countedAt,
                      boolean recountRequired) {
        this.expectedQty = expectedQty;
        this.countedQty = countedQty;
        this.countedBy = countedBy;
        this.countedAt = countedAt;
        this.recountRequired = recountRequired;
    }

    public String getLocationCode() { return locationCode; }
    public String getSku() { return sku; }
    public String getLotNumber() { return lotNumber; }
    public UUID getStockPackageId() { return stockPackageId; }
    public int getExpectedQty() { return expectedQty; }
    public Integer getCountedQty() { return countedQty; }
    public UUID getCountedBy() { return countedBy; }
    public Instant getCountedAt() { return countedAt; }
    public boolean isRecountRequired() { return recountRequired; }
}
