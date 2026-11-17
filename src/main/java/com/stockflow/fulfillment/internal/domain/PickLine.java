package com.stockflow.fulfillment.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One stop of a pick list: take carton {@code lpn} - {@code allocatedQty} of {@code sku}, its whole standard count -
 * off {@code locationCode}, against the one hold on it (kltn-docs 07 §4.1 step 2: "mỗi thùng đã phân bổ một dòng";
 * BR-04).
 *
 * <p>A line planned now always names its carton ({@link #open}). A stored line is rehydrated as it was, loose history
 * included ({@code ck_pick_line_lpn} is NOT VALID where rows from before 4C-1 exist).</p>
 *
 * <p>A line of a STAGING task is staged rather than picked (team plan 4C-5, 07 §4.2 step 5): the whole carton taken
 * ({@code pickedQty}), moved to the staging area ({@code stagedAt}, {@code stagedLocationCode}), not deducted
 * ({@code consumedAt} null) until the task moves to dispatch and the line turns PICKED ({@code ck_pick_line_staged},
 * {@code ck_pick_line_picked}).</p>
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
    /**
     * Units the picker could not take (X-SHORT); positive only on a SHORT line or one reallocated since. A short reported
     * per carton is the whole line; a smaller figure is history from before cartons.
     */
    private int shortQty;
    /** Why the carton could not be taken (07 §4.4 step 9); null on a line never reported short, and on old ones. */
    private ShortPickReason shortReason;
    /** The carton the hold is in (SCRUM-473): the line is confirmed, and put back, by scanning it. Null on history. */
    private String lpn;
    /** The hold was the carton's whole sealed content when planned - always, since 4C-2 allocates whole cartons only. */
    private boolean wholeCarton;
    /** When the carton was moved to the staging area (4C-5); kept once the line moved on to PICKED or PUT_BACK. */
    private Instant stagedAt;
    /** The staging area the carton was moved to; set with {@link #stagedAt}. */
    private String stagedLocationCode;

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
     * Rehydrates a stored line with why it was short ({@code ck_pick_line_short_reason}: the whole line, SHORT or
     * reallocated since).
     */
    public PickLine(UUID id, UUID orderLineId, UUID reservationId, String sku, String locationCode,
                    String lotNumber, int sequenceNo, int allocatedQty, int pickedQty, PickLineStatus status,
                    UUID pickedBy, Instant pickedAt, Instant consumedAt, String scannedLocationCode,
                    String scannedCode, int putBackQty, String putBackLocationCode, UUID putBackBy,
                    Instant putBackAt, int shortQty, ShortPickReason shortReason, String lpn, boolean wholeCarton) {
        this(id, orderLineId, reservationId, sku, locationCode, lotNumber, sequenceNo, allocatedQty, pickedQty, status,
                pickedBy, pickedAt, consumedAt, scannedLocationCode, scannedCode, putBackQty, putBackLocationCode,
                putBackBy, putBackAt, shortQty, lpn, wholeCarton);
        // BR-04 ck_pick_line_within_allocation: picked and short together account for at most the allocation.
        if (pickedQty + shortQty > allocatedQty) {
            throw new IllegalArgumentException("A line picks and falls short of at most its allocation");
        }
        if (shortReason != null && (shortQty != allocatedQty
                || status != PickLineStatus.SHORT && status != PickLineStatus.CANCELLED)) {
            throw new IllegalArgumentException("Only a line short of its whole carton carries a short reason");
        }
        this.shortReason = shortReason;
    }

    /**
     * Rehydrates a stored line with when and where its carton was staged ({@code ck_pick_line_staged}: both or
     * neither; a STAGED line not deducted).
     */
    public PickLine(UUID id, UUID orderLineId, UUID reservationId, String sku, String locationCode,
                    String lotNumber, int sequenceNo, int allocatedQty, int pickedQty, PickLineStatus status,
                    UUID pickedBy, Instant pickedAt, Instant consumedAt, String scannedLocationCode,
                    String scannedCode, int putBackQty, String putBackLocationCode, UUID putBackBy,
                    Instant putBackAt, int shortQty, ShortPickReason shortReason, String lpn, boolean wholeCarton,
                    Instant stagedAt, String stagedLocationCode) {
        this(id, orderLineId, reservationId, sku, locationCode, lotNumber, sequenceNo, allocatedQty, pickedQty, status,
                pickedBy, pickedAt, consumedAt, scannedLocationCode, scannedCode, putBackQty, putBackLocationCode,
                putBackBy, putBackAt, shortQty, shortReason, lpn, wholeCarton);
        if ((stagedAt == null) != (stagedLocationCode == null)
                || status == PickLineStatus.STAGED && (stagedAt == null || consumedAt != null)) {
            throw new IllegalArgumentException("A staged line says when and where, and is not deducted");
        }
        this.stagedAt = stagedAt;
        this.stagedLocationCode = stagedLocationCode;
    }

    /**
     * A new line, open, nothing picked: one carton, named by its LPN (07 BR-04, BR-10; {@code ck_pick_line_lpn}).
     * Package-private: only {@link PickList} plans lines.
     */
    static PickLine open(UUID id, UUID orderLineId, UUID reservationId, String sku, String locationCode,
                         String lotNumber, int sequenceNo, int allocatedQty, String lpn, boolean wholeCarton) {
        return new PickLine(id, orderLineId, Objects.requireNonNull(reservationId, "reservationId"), sku, locationCode,
                lotNumber, sequenceNo, allocatedQty, 0, PickLineStatus.OPEN, null, null, null, null, null, 0, null, null,
                null, 0, Objects.requireNonNull(lpn, "lpn: a pick line is a carton"), wholeCarton);
    }

    /** The carton was taken whole: the line is PICKED with its whole allocation (BR-04: no quantity is entered). */
    void pick(String scannedLocation, String scannedLpn, UUID actor, Instant now) {
        this.status = PickLineStatus.PICKED;
        this.pickedQty = allocatedQty;
        this.pickedBy = Objects.requireNonNull(actor, "actor");
        this.pickedAt = Objects.requireNonNull(now, "now");
        this.scannedLocationCode = scannedLocation;
        this.scannedCode = scannedLpn;
    }

    /**
     * The carton was taken whole, for a STAGING task (07 §4.2 step 5): the line is STAGED - the carton moved to
     * {@code stagingLocationCode} by the caller in the same transaction, still held for the order, nothing deducted.
     */
    void stage(String scannedLocation, String scannedLpn, String stagingLocationCode, UUID actor, Instant now) {
        this.status = PickLineStatus.STAGED;
        this.pickedQty = allocatedQty;
        this.pickedBy = Objects.requireNonNull(actor, "actor");
        this.pickedAt = Objects.requireNonNull(now, "now");
        this.scannedLocationCode = scannedLocation;
        this.scannedCode = scannedLpn;
        this.stagedAt = now;
        this.stagedLocationCode = Objects.requireNonNull(stagingLocationCode, "stagingLocationCode");
    }

    /**
     * The task moved to dispatch (07 §4.3 step 8): the staged carton is PICKED where it stands; the caller deducts it in
     * the same transaction and records it with {@link #recordConsumed}. Who picked it and when stay the picker's scan.
     */
    void dispatchStaged() {
        if (status != PickLineStatus.STAGED) {
            throw new IllegalStateException("Only a STAGED line moves to dispatch; it is " + status);
        }
        this.status = PickLineStatus.PICKED;
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

    /** The carton the line picked went back whole (07 BR-05): what it picked, {@code ck_pick_line_put_back}. */
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
     * The picker could not take the carton, for {@code reason} (07 §4.4 step 9): nothing is taken and nothing is
     * deducted - the line is short of the whole carton and waits for the warehouse manager.
     */
    void reportShort(ShortPickReason reason, String scannedLocation) {
        this.status = PickLineStatus.SHORT;
        // A staged line had taken its carton (pickedQty); short now, it holds none of it (ck_pick_line_within_allocation).
        this.pickedQty = 0;
        this.shortQty = allocatedQty;
        this.shortReason = Objects.requireNonNull(reason, "reason");
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
    public ShortPickReason shortReason() { return shortReason; }
    public String lpn() { return lpn; }
    public boolean wholeCarton() { return wholeCarton; }
    public Instant stagedAt() { return stagedAt; }
    public String stagedLocationCode() { return stagedLocationCode; }
}
