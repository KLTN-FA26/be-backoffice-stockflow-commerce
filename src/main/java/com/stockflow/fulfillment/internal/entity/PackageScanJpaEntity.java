package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One scan at the packing table (table {@code fulfillment.package_scan}, append-only): goods put into a parcel, or
 * taken out again (negative). The id is the scanner's {@code scanId}, so a retried scan is one row.
 */
@Entity
@Table(name = "package_scan", schema = "fulfillment")
public class PackageScanJpaEntity extends BaseEntity {

    @Column(name = "package_id", nullable = false, updatable = false) private UUID packageId;
    @Column(name = "order_line_id", updatable = false) private UUID orderLineId;
    /** SCRUM-287 PR T: the transfer line scanned, at a transfer's packing (order_line_id null then). */
    @Column(name = "transfer_line_id", updatable = false) private UUID transferLineId;
    @Column(name = "scanned_code", nullable = false, updatable = false, length = 64) private String scannedCode;
    @Column(name = "lot_number", updatable = false, length = 64) private String lotNumber;
    @Column(name = "serial_number", updatable = false, length = 100) private String serialNumber;
    @Column(name = "quantity", nullable = false, updatable = false) private int quantity;
    @Column(name = "scanned_by", nullable = false, updatable = false) private UUID scannedBy;
    @Column(name = "scanned_at", nullable = false, updatable = false) private Instant scannedAt;

    protected PackageScanJpaEntity() {
    }

    @SuppressWarnings("java:S107")
    public PackageScanJpaEntity(UUID id, UUID packageId, UUID orderLineId, String scannedCode, String lotNumber,
                                String serialNumber, int quantity, UUID scannedBy, Instant scannedAt) {
        super(id);
        this.packageId = packageId;
        this.orderLineId = orderLineId;
        this.scannedCode = scannedCode;
        this.lotNumber = lotNumber;
        this.serialNumber = serialNumber;
        this.quantity = quantity;
        this.scannedBy = scannedBy;
        this.scannedAt = scannedAt;
    }

    /** A scan at a transfer's packing (SCRUM-287 PR T): {@code lineId} is a transfer line. */
    @SuppressWarnings("java:S107")
    public static PackageScanJpaEntity ofTransfer(UUID id, UUID packageId, UUID transferLineId, String scannedCode,
                                                  String lotNumber, int quantity, UUID scannedBy, Instant scannedAt) {
        PackageScanJpaEntity scan = new PackageScanJpaEntity(id, packageId, null, scannedCode, lotNumber, null,
                quantity, scannedBy, scannedAt);
        scan.transferLineId = transferLineId;
        return scan;
    }

    public UUID getPackageId() { return packageId; }
    public UUID getTransferLineId() { return transferLineId; }

    /** The line scanned: the order line, or the transfer line at a transfer's packing. */
    public UUID getLineId() { return orderLineId != null ? orderLineId : transferLineId; }
    public UUID getOrderLineId() { return orderLineId; }
    public String getScannedCode() { return scannedCode; }
    public String getLotNumber() { return lotNumber; }
    public String getSerialNumber() { return serialNumber; }
    public int getQuantity() { return quantity; }
    public UUID getScannedBy() { return scannedBy; }
    public Instant getScannedAt() { return scannedAt; }
}
