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
    /** The carton the hold is in (SCRUM-473), null for loose stock: the line is confirmed by scanning it. */
    private String lpn;
    /** The hold was the carton's whole sealed content when planned: a hint for the picker, not a rule. */
    private boolean wholeCarton;
    /** Units the picker found damaged at the bin (SCRUM-277); positive only on the line a damage report superseded. */
    private int damagedQty;
    /** The damage report that superseded this line, or that created it; null otherwise. */
    private UUID damageReportId;
    /** Units of a picked line found damaged at the packing bench (R8): back in quarantine, no longer this line's. */
    private int packingDamagedQty;

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

    /** Rehydrates a stored line, drawn from a carton when {@code lpn} is set. */
    public PickLine(UUID id, UUID orderLineId, UUID reservationId, String sku, String locationCode,
                    String lotNumber, int sequenceNo, int allocatedQty, int pickedQty, PickLineStatus status,
                    UUID pickedBy, Instant pickedAt, Instant consumedAt, String scannedLocationCode,
                    String scannedCode, int putBackQty, String putBackLocationCode, UUID putBackBy,
                    Instant putBackAt, int shortQty, String lpn, boolean wholeCarton) {
        this(id, orderLineId, reservationId, sku, locationCode, lotNumber, sequenceNo, allocatedQty, pickedQty, status,
                pickedBy, pickedAt, consumedAt, scannedLocationCode, scannedCode, putBackQty, putBackLocationCode,
                putBackBy, putBackAt, shortQty);
        if (wholeCarton && lpn == null) {
            throw new IllegalArgumentException("Only a line drawn from a carton takes the whole carton");
        }
        this.lpn = lpn;
        this.wholeCarton = wholeCarton;
    }

    /**
     * Rehydrates a stored line with what a damage report recorded on it ({@code ck_pick_line_damaged}) and the units
     * found damaged at packing ({@code ck_pick_line_packing_damaged}).
     */
    public PickLine(UUID id, UUID orderLineId, UUID reservationId, String sku, String locationCode,
                    String lotNumber, int sequenceNo, int allocatedQty, int pickedQty, PickLineStatus status,
                    UUID pickedBy, Instant pickedAt, Instant consumedAt, String scannedLocationCode,
                    String scannedCode, int putBackQty, String putBackLocationCode, UUID putBackBy,
                    Instant putBackAt, int shortQty, String lpn, boolean wholeCarton, int damagedQty,
                    UUID damageReportId, int packingDamagedQty) {
        this(id, orderLineId, reservationId, sku, locationCode, lotNumber, sequenceNo, allocatedQty, pickedQty, status,
                pickedBy, pickedAt, consumedAt, scannedLocationCode, scannedCode, putBackQty, putBackLocationCode,
                putBackBy, putBackAt, shortQty, lpn, wholeCarton);
        if (damagedQty < 0 || pickedQty + shortQty + damagedQty > allocatedQty
                || damagedQty > 0 && (status != PickLineStatus.CANCELLED || damageReportId == null)) {
            throw new IllegalArgumentException("Only a line a damage report superseded carries damaged units");
        }
        if (packingDamagedQty < 0 || packingDamagedQty > pickedQty || packingDamagedQty > 0
                && status != PickLineStatus.PICKED && status != PickLineStatus.PUT_BACK) {
            throw new IllegalArgumentException("Only units a line picked are found damaged at packing");
        }
        this.damagedQty = damagedQty;
        this.damageReportId = damageReportId;
        this.packingDamagedQty = packingDamagedQty;
    }

    /**
     * A line a damage report made (SCRUM-277): the good units still to pick at the bin, a replacement allocation
     * elsewhere (OPEN, {@code shortQty} 0), or the damaged share nothing could replace (SHORT by all of it, waiting for
     * the coordinator's reallocation like any short line). {@code lpn} is the carton its hold is in, null for loose
     * stock (SCRUM-473; {@code tg_pick_line_carton}). Package-private: only {@link PickList} plans lines.
     */
    static PickLine fromDamage(UUID id, UUID orderLineId, UUID reservationId, String sku, String locationCode,
                               String lotNumber, int sequenceNo, int allocatedQty, boolean shortOfAll,
                               UUID damageReportId, String lpn, boolean wholeCarton) {
        return new PickLine(id, orderLineId, reservationId, sku, locationCode, lotNumber, sequenceNo, allocatedQty, 0,
                shortOfAll ? PickLineStatus.SHORT : PickLineStatus.OPEN, null, null, null, null, null, 0, null, null,
                null, shortOfAll ? allocatedQty : 0, lpn, wholeCarton, 0,
                Objects.requireNonNull(damageReportId, "damageReportId"), 0);
    }

    /** A new line, open, nothing picked. Package-private: only {@link PickList} plans lines. */
    static PickLine open(UUID id, UUID orderLineId, UUID reservationId, String sku, String locationCode,
                         String lotNumber, int sequenceNo, int allocatedQty, String lpn, boolean wholeCarton) {
        return new PickLine(id, orderLineId, reservationId, sku, locationCode, lotNumber, sequenceNo, allocatedQty,
                0, PickLineStatus.OPEN, null, null, null, null, null, 0, null, null, null, 0, lpn, wholeCarton);
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
        this.putBackQty = goodQty();
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

    /**
     * The picker found {@code damaged} of the allocated units damaged (SCRUM-277): the line gives way to the lines
     * the damage report made, and keeps the damaged units and the report on record.
     */
    void supersedeForDamage(int damaged, UUID reportId) {
        if (damaged < 1 || damaged > allocatedQty) {
            throw new IllegalArgumentException("A line is damaged by 1.." + allocatedQty + " units");
        }
        this.status = PickLineStatus.CANCELLED;
        this.damagedQty = damaged;
        this.damageReportId = Objects.requireNonNull(reportId, "reportId");
    }

    /**
     * The packer found {@code damaged} more of the units this line picked damaged (R8): they went back into stock, in
     * quarantine, and are no longer the line's to pack or to put back.
     */
    void damagedAfterPick(int damaged) {
        if (status != PickLineStatus.PICKED || damaged < 1 || damaged > goodQty()) {
            throw new IllegalArgumentException("A picked line is found damaged by 1.." + goodQty() + " units");
        }
        this.packingDamagedQty += damaged;
    }

    /** What the line picked and still has: picked, less what was found damaged at packing. */
    public int goodQty() {
        return pickedQty - packingDamagedQty;
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
    public String lpn() { return lpn; }
    public boolean wholeCarton() { return wholeCarton; }
    public int damagedQty() { return damagedQty; }
    public UUID damageReportId() { return damageReportId; }
    public int packingDamagedQty() { return packingDamagedQty; }
}
