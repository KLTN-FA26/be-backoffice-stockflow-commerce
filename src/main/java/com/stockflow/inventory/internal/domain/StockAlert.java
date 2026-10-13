package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One episode of a stock condition (SCRUM-147): a SKU at or below its reorder point, below its safety
 * stock, a lot about to expire, or a lot past its expiry date.
 *
 * <p>An episode is OPEN until somebody acknowledges it (ACKNOWLEDGED) and RESOLVED when the condition
 * ends by itself. Acknowledging does not close it: the condition is still true, a person has only said
 * they have seen it. The same condition is never open twice ({@code uk_stock_alert_open}); when it clears
 * and later returns, a new episode is opened, so the history of how often a SKU ran low is kept.</p>
 *
 * <p>Every invariant here is a {@code CHECK} in {@code V20261014000900} too: the aggregate says it with
 * a good message, the database is what a manual UPDATE or a bug cannot get past. No Spring, no JPA.</p>
 */
public final class StockAlert {

    /** What the alert is about. REORDER and SAFETY are per SKU system-wide; the expiry kinds per lot and warehouse. */
    public enum Kind {
        REORDER, SAFETY, EXPIRING, EXPIRED;

        public boolean isStockLevel() {
            return this == REORDER || this == SAFETY;
        }
    }

    public enum Status { OPEN, ACKNOWLEDGED, RESOLVED }

    /** How an episode ended; which ones a kind can have is checked in {@link #resolve}. */
    public enum Resolution { RECOVERED, UNCONFIGURED, STOCK_GONE, EXPIRED_OUT }

    /** What makes two alerts "the same condition": the columns of the unique index. */
    public record Key(Kind kind, String sku, UUID warehouseId, String lotNumber, LocalDate expiryDate) {
        public Key {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(sku, "sku");
            lotNumber = lotNumber == null || lotNumber.isBlank() ? null : lotNumber;
        }

        public static Key stockLevel(Kind kind, String sku) {
            if (!kind.isStockLevel()) {
                throw new IllegalArgumentException(kind + " is not a stock level alert");
            }
            return new Key(kind, sku, null, null, null);
        }

        public static Key expiry(Kind kind, String sku, UUID warehouseId, String lotNumber, LocalDate expiryDate) {
            if (kind.isStockLevel()) {
                throw new IllegalArgumentException(kind + " is not an expiry alert");
            }
            return new Key(kind, sku, Objects.requireNonNull(warehouseId, "warehouseId"), lotNumber,
                    Objects.requireNonNull(expiryDate, "expiryDate"));
        }
    }

    public static final int NOTE_MAX = 500;

    private final UUID id;
    private final Key key;
    private int observedQty;
    private Integer thresholdQty;
    private boolean reservationImpaired;
    private Status status;
    private final Instant openedAt;
    private Instant lastObservedAt;
    private UUID acknowledgedBy;
    private Instant acknowledgedAt;
    private String ackNote;
    private Instant resolvedAt;
    private Resolution resolution;
    private final long version;

    /** Rehydrates a stored alert; refuses a combination the table would refuse. */
    public StockAlert(UUID id, Key key, int observedQty, Integer thresholdQty, boolean reservationImpaired,
                      Status status, Instant openedAt, Instant lastObservedAt, UUID acknowledgedBy,
                      Instant acknowledgedAt, String ackNote, Instant resolvedAt, Resolution resolution,
                      long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.key = Objects.requireNonNull(key, "key");
        this.observedQty = observedQty;
        this.thresholdQty = thresholdQty;
        this.reservationImpaired = reservationImpaired;
        this.status = Objects.requireNonNull(status, "status");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
        this.lastObservedAt = Objects.requireNonNull(lastObservedAt, "lastObservedAt");
        this.acknowledgedBy = acknowledgedBy;
        this.acknowledgedAt = acknowledgedAt;
        this.ackNote = ackNote;
        this.resolvedAt = resolvedAt;
        this.resolution = resolution;
        this.version = version;
        checkInvariants();
    }

    /** A new episode, OPEN. {@code thresholdQty} is required for the stock level kinds and absent for expiry. */
    public static StockAlert open(UUID id, Key key, int observedQty, Integer thresholdQty,
                                  boolean reservationImpaired, Instant now) {
        return new StockAlert(id, key, observedQty, thresholdQty, reservationImpaired, Status.OPEN, now, now,
                null, null, null, null, null, 0L);
    }

    // ------------------------------------------------------------------ behaviour

    /**
     * The condition still holds: refresh the numbers. Says nothing to anybody - a changed quantity of an
     * open alert is not news (the first layer against spam).
     */
    public void observe(int observedQty, Integer thresholdQty, boolean reservationImpaired, Instant now) {
        if (status == Status.RESOLVED) {
            throw new IllegalStateException("A resolved alert is history; open a new episode instead");
        }
        this.observedQty = observedQty;
        this.thresholdQty = thresholdQty;
        this.reservationImpaired = reservationImpaired;
        if (now.isAfter(lastObservedAt)) {
            this.lastObservedAt = now;
        }
        checkInvariants();
    }

    /**
     * Somebody has seen it. Only an OPEN alert can be acknowledged; the condition stays true, so the episode
     * stays open for the dedupe index.
     *
     * @throws BusinessException {@code INVALID_ALERT_TRANSITION} when it is already acknowledged or resolved;
     *         {@code VALIDATION_FAILED} for a note over {@value #NOTE_MAX} characters
     */
    public void acknowledge(UUID userId, String note, Instant now) {
        Objects.requireNonNull(userId, "userId");
        if (status != Status.OPEN) {
            throw new BusinessException(ErrorCode.INVALID_ALERT_TRANSITION,
                    "Alert %s is %s and cannot be acknowledged".formatted(id, status));
        }
        String trimmed = note == null || note.isBlank() ? null : note.strip();
        if (trimmed != null && trimmed.length() > NOTE_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The note is longer than " + NOTE_MAX + " characters");
        }
        this.status = Status.ACKNOWLEDGED;
        this.acknowledgedBy = userId;
        this.acknowledgedAt = now;
        this.ackNote = trimmed;
        checkInvariants();
    }

    /**
     * The condition ended. OPEN and ACKNOWLEDGED both resolve; a resolved alert resolves no further.
     *
     * @return false when it was resolved already (nothing changed)
     */
    public boolean resolve(Resolution resolution, Instant now) {
        Objects.requireNonNull(resolution, "resolution");
        if (status == Status.RESOLVED) {
            return false;
        }
        if (!allows(key.kind(), resolution)) {
            throw new IllegalArgumentException(key.kind() + " alert cannot be resolved as " + resolution);
        }
        this.status = Status.RESOLVED;
        this.resolution = resolution;
        this.resolvedAt = now.isBefore(openedAt) ? openedAt : now;
        checkInvariants();
        return true;
    }

    /** Which endings make sense for which kind; mirrors {@code ck_stock_alert_resolution_kind}. */
    public static boolean allows(Kind kind, Resolution resolution) {
        return switch (kind) {
            case REORDER, SAFETY -> resolution == Resolution.RECOVERED || resolution == Resolution.UNCONFIGURED;
            case EXPIRING -> resolution == Resolution.STOCK_GONE || resolution == Resolution.EXPIRED_OUT;
            case EXPIRED -> resolution == Resolution.STOCK_GONE;
        };
    }

    public boolean isOpenEpisode() {
        return status != Status.RESOLVED;
    }

    // ------------------------------------------------------------------ invariants

    private void checkInvariants() {
        if (observedQty < 0 || thresholdQty != null && thresholdQty < 0) {
            throw new IllegalStateException("Quantities of an alert are never negative");
        }
        if (key.kind().isStockLevel()) {
            if (thresholdQty == null || key.warehouseId() != null || key.lotNumber() != null || key.expiryDate() != null) {
                throw new IllegalStateException(key.kind() + " alert is per SKU and names its threshold");
            }
        } else if (key.warehouseId() == null || key.expiryDate() == null) {
            throw new IllegalStateException(key.kind() + " alert names a warehouse and an expiry date");
        }
        if (reservationImpaired && key.kind() != Kind.EXPIRED) {
            throw new IllegalStateException("Only an EXPIRED alert can have an impaired reservation");
        }
        boolean resolved = status == Status.RESOLVED;
        if (resolved != (resolvedAt != null && resolution != null) || !resolved && (resolvedAt != null || resolution != null)) {
            throw new IllegalStateException("A resolved alert says when and how; any other says neither");
        }
        if (resolution != null && !allows(key.kind(), resolution)) {
            throw new IllegalStateException(key.kind() + " alert cannot be resolved as " + resolution);
        }
        if (status == Status.ACKNOWLEDGED && (acknowledgedBy == null || acknowledgedAt == null)) {
            throw new IllegalStateException("An acknowledged alert says who and when");
        }
        if (status == Status.OPEN && (acknowledgedBy != null || acknowledgedAt != null)) {
            throw new IllegalStateException("An open alert has not been acknowledged");
        }
    }

    // ------------------------------------------------------------------ accessors

    public UUID id() { return id; }
    public Key key() { return key; }
    public Kind kind() { return key.kind(); }
    public String sku() { return key.sku(); }
    public UUID warehouseId() { return key.warehouseId(); }
    public String lotNumber() { return key.lotNumber(); }
    public LocalDate expiryDate() { return key.expiryDate(); }
    public int observedQty() { return observedQty; }
    public Integer thresholdQty() { return thresholdQty; }
    public boolean reservationImpaired() { return reservationImpaired; }
    public Status status() { return status; }
    public Instant openedAt() { return openedAt; }
    public Instant lastObservedAt() { return lastObservedAt; }
    public UUID acknowledgedBy() { return acknowledgedBy; }
    public Instant acknowledgedAt() { return acknowledgedAt; }
    public String ackNote() { return ackNote; }
    public Instant resolvedAt() { return resolvedAt; }
    public Resolution resolution() { return resolution; }
    public long version() { return version; }
}
