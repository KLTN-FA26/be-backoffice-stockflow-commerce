package com.stockflow.fulfillment.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One stop of a pick list: take {@code allocatedQty} of {@code sku} (from {@code lotNumber}) off
 * {@code locationCode}, against one hold (docs 07 step 2: "allocation at a bin and lot").
 *
 * <p>An entity of the {@link PickList} aggregate: it changes only through the list, which checks the
 * task's status and the scan before it lets a line move. Mutable, because its status genuinely
 * changes over its life.</p>
 */
public final class PickLine {

    private final UUID id;
    private final UUID orderLineId;
    private final UUID reservationId;
    private final String sku;
    private final String locationCode;
    private final String lotNumber;
    private final int sequenceNo;
    private final int allocatedQty;
    private int pickedQty;
    private PickLineStatus status;
    private UUID pickedBy;
    private Instant pickedAt;
    private Instant consumedAt;
    private String scannedLocationCode;
    private String scannedCode;
    private int putBackQty;
    private String putBackLocationCode;
    private UUID putBackBy;
    private Instant putBackAt;
    /** Units the picker did not find at the bin (X-SHORT); positive only on a SHORT line or one reallocated since. */
    private int shortQty;

    /** Rehydrates a stored line that was never short. */
    public PickLine(UUID id, UUID orderLineId, UUID reservationId, String sku, String locationCode,
                    String lotNumber, int sequenceNo, int allocatedQty, int pickedQty, PickLineStatus status,
                    UUID pickedBy, Instant pickedAt, Instant consumedAt, String scannedLocationCode,
                    String scannedCode, int putBackQty, String putBackLocationCode, UUID putBackBy,
                    Instant putBackAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.orderLineId = Objects.requireNonNull(orderLineId, "orderLineId");
        this.reservationId = reservationId;
        this.sku = Objects.requireNonNull(sku, "sku");
        this.locationCode = Objects.requireNonNull(locationCode, "locationCode");
        this.lotNumber = lotNumber;
        this.sequenceNo = sequenceNo;
        if (allocatedQty <= 0) {
            throw new IllegalArgumentException("A pick line takes a positive quantity");
        }
        this.allocatedQty = allocatedQty;
        this.pickedQty = pickedQty;
        this.status = Objects.requireNonNull(status, "status");
        this.pickedBy = pickedBy;
        this.pickedAt = pickedAt;
        this.consumedAt = consumedAt;
        this.scannedLocationCode = scannedLocationCode;
        this.scannedCode = scannedCode;
        this.putBackQty = putBackQty;
        this.putBackLocationCode = putBackLocationCode;
        this.putBackBy = putBackBy;
        this.putBackAt = putBackAt;
    }

    /** Rehydrates a stored line. */
    public PickLine(UUID id, UUID orderLineId, UUID reservationId, String sku, String locationCode,
                    String lotNumber, int sequenceNo, int allocatedQty, int pickedQty, PickLineStatus status,
                    UUID pickedBy, Instant pickedAt, Instant consumedAt, String scannedLocationCode,
                    String scannedCode, int putBackQty, String putBackLocationCode, UUID putBackBy,
                    Instant putBackAt, int shortQty) {
        this(id, orderLineId, reservationId, sku, locationCode, lotNumber, sequenceNo, allocatedQty, pickedQty, status,
                pickedBy, pickedAt, consumedAt, scannedLocationCode, scannedCode, putBackQty, putBackLocationCode,
                putBackBy, putBackAt);
        if (shortQty < 0 || shortQty > allocatedQty) {
            throw new IllegalArgumentException("A line is short by 0.." + allocatedQty + " units");
        }
        this.shortQty = shortQty;
    }

    /** A new line, open, nothing picked. Package-private: only {@link PickList} plans lines. */
    static PickLine open(UUID id, UUID orderLineId, UUID reservationId, String sku, String locationCode,
                         String lotNumber, int sequenceNo, int allocatedQty) {
        return new PickLine(id, orderLineId, reservationId, sku, locationCode, lotNumber, sequenceNo, allocatedQty,
                0, PickLineStatus.OPEN, null, null, null, null, null, 0, null, null, null);
    }

    void pick(String scannedLocation, String scannedItem, UUID actor, Instant now) {
        this.status = PickLineStatus.PICKED;
        this.pickedQty = allocatedQty;
        this.pickedBy = Objects.requireNonNull(actor, "actor");
        this.pickedAt = Objects.requireNonNull(now, "now");
        this.scannedLocationCode = scannedLocation;
        this.scannedCode = scannedItem;
    }

    /** The hold behind the line was deducted, in the same transaction as {@link #pick}. */
    public void recordConsumed(Instant now) {
        if (status != PickLineStatus.PICKED || reservationId == null) {
            throw new IllegalStateException("Only a picked line with a hold is deducted");
        }
        if (consumedAt == null) {
            this.consumedAt = Objects.requireNonNull(now, "now");
        }
    }

    void putBack(String location, UUID actor, Instant now) {
        this.status = PickLineStatus.PUT_BACK;
        this.putBackQty = pickedQty;
        this.putBackLocationCode = Objects.requireNonNull(location, "location");
        this.putBackBy = Objects.requireNonNull(actor, "actor");
        this.putBackAt = Objects.requireNonNull(now, "now");
    }

    void cancel() {
        this.status = PickLineStatus.CANCELLED;
    }

    /**
     * The picker found {@code found} of the allocated units at the bin (X-SHORT): nothing is taken and nothing is
     * deducted - the line records the shortfall and waits for the coordinator.
     */
    void reportShort(int found, String scannedLocation) {
        this.status = PickLineStatus.SHORT;
        this.shortQty = allocatedQty - found;
        this.scannedLocationCode = scannedLocation;
    }

    /** Its allocation was moved to other bins; the new lines pick it. The shortfall stays on record. */
    void supersede() {
        this.status = PickLineStatus.CANCELLED;
    }

    public UUID id() { return id; }
    public UUID orderLineId() { return orderLineId; }
    public UUID reservationId() { return reservationId; }
    public String sku() { return sku; }
    public String locationCode() { return locationCode; }
    public String lotNumber() { return lotNumber; }
    public int sequenceNo() { return sequenceNo; }
    public int allocatedQty() { return allocatedQty; }
    public int pickedQty() { return pickedQty; }
    public PickLineStatus status() { return status; }
    public UUID pickedBy() { return pickedBy; }
    public Instant pickedAt() { return pickedAt; }
    public Instant consumedAt() { return consumedAt; }
    public String scannedLocationCode() { return scannedLocationCode; }
    public String scannedCode() { return scannedCode; }
    public int putBackQty() { return putBackQty; }
    public String putBackLocationCode() { return putBackLocationCode; }
    public UUID putBackBy() { return putBackBy; }
    public Instant putBackAt() { return putBackAt; }
    public int shortQty() { return shortQty; }
}
