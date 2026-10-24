package com.stockflow.order.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** {@code ordering.return_request_discrepancy}: what a returned parcel held that the approval did not say. Append-only. */
@Entity
@Table(name = "return_request_discrepancy", schema = "ordering")
public class ReturnRequestDiscrepancyJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "return_request_id", nullable = false, updatable = false)
    private UUID returnRequestId;

    @Column(name = "return_request_line_id", updatable = false)
    private UUID returnRequestLineId;

    @Column(name = "kind", nullable = false, length = 16, updatable = false)
    private String kind;

    @Column(name = "sku", length = 64, updatable = false)
    private String sku;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "note", length = 500, updatable = false)
    private String note;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected ReturnRequestDiscrepancyJpaEntity() {
    }

    public ReturnRequestDiscrepancyJpaEntity(UUID id, UUID returnRequestId, UUID returnRequestLineId, String kind,
                                             String sku, int quantity, String note, UUID recordedBy,
                                             Instant recordedAt) {
        this.id = id;
        this.returnRequestId = returnRequestId;
        this.returnRequestLineId = returnRequestLineId;
        this.kind = kind;
        this.sku = sku;
        this.quantity = quantity;
        this.note = note;
        this.recordedBy = recordedBy;
        this.recordedAt = recordedAt;
    }

    public UUID getId() { return id; }
    public UUID getReturnRequestId() { return returnRequestId; }
    public UUID getReturnRequestLineId() { return returnRequestLineId; }
    public String getKind() { return kind; }
    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
    public String getNote() { return note; }
    public UUID getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
}
