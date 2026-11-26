package com.stockflow.inventory.internal.domain;

import com.stockflow.common.id.Identifiers;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One carton of a {@link CycleCount}: whether the books had it at its bin, and whether it was found there (kltn-docs
 * 20 §4.2 b4; team plan 4C-3).
 *
 * <p>Counted by scanning, never in units (BR-14: the carton is not opened, its count is its standard count). So the
 * line records two answers and no figure: {@link #booked()} and {@link #found()}, the columns of the same names
 * (ADR-0011). How many units the carton stands for is the carton's to say, read by whoever posts the variance.</p>
 *
 * <p>Mutators are package-private on purpose: a line is only ever changed through its count, which checks that the
 * count is still open. A public setter would let a caller count a line of a closed count and skip that check.</p>
 */
public final class CycleCountLine {

    private final UUID id;
    /** Fixed, except that the bin follows the carton when it is moved whole before it is counted ({@link #relocate}). */
    private CountSlot slot;
    private boolean booked;
    /** Null until the line is counted. */
    private Boolean found;
    private UUID countedBy;
    private Instant countedAt;
    private boolean recountRequired;

    /** A new, uncounted line: {@code booked} is whether the books had the carton at its bin when it was planned. */
    CycleCountLine(CountSlot slot, boolean booked) {
        this(Identifiers.newId(), slot, booked, null, null, null, false);
    }

    /** Rehydration from a stored row. */
    public CycleCountLine(UUID id, CountSlot slot, boolean booked, Boolean found, UUID countedBy,
                          Instant countedAt, boolean recountRequired) {
        this.id = Objects.requireNonNull(id, "id");
        this.slot = Objects.requireNonNull(slot, "slot");
        if (!slot.isCarton()) {
            throw new IllegalArgumentException("A count line names a carton: " + slot.lockKey());
        }
        this.booked = booked;
        this.found = found;
        this.countedBy = countedBy;
        this.countedAt = countedAt;
        this.recountRequired = recountRequired;
    }

    /**
     * The carton was scanned at its bin ({@code found}) or not, while the books did or did not have it there
     * ({@code booked}, read now, BR-09). Repeating it before submission overwrites the earlier answer.
     */
    void count(boolean found, boolean booked, UUID by, Instant at) {
        this.booked = booked;
        this.found = found;
        this.countedBy = Objects.requireNonNull(by, "by");
        this.countedAt = Objects.requireNonNull(at, "at");
        this.recountRequired = false;
    }

    /**
     * The carton was moved whole since the line was planned, before it was counted (20 §4.2 b6). The line follows it:
     * the carton is the identity being counted, the bin only where it happens to be.
     */
    void relocate(LocationId to) {
        this.slot = CountSlot.carton(to, slot.sku(), slot.lotNumber(), slot.stockPackageId());
    }

    void requireRecount() {
        this.recountRequired = true;
    }

    public UUID id() {
        return id;
    }

    public CountSlot slot() {
        return slot;
    }

    /** Whether the books had the carton at its bin when the line was counted (or planned, until then). */
    public boolean booked() {
        return booked;
    }

    /** Whether the carton was found; null until counted. */
    public Boolean found() {
        return found;
    }

    public UUID countedBy() {
        return countedBy;
    }

    public Instant countedAt() {
        return countedAt;
    }

    public boolean recountRequired() {
        return recountRequired;
    }

    public boolean isCounted() {
        return found != null;
    }

    /**
     * What the count found against the books: a carton booked and not found is a shortage to write off, a carton found
     * that the books did not have a surplus to restock; empty when they agree, or until the line is counted.
     */
    public Optional<AdjustmentDirection> variance() {
        if (found == null || found == booked) {
            return Optional.empty();
        }
        return Optional.of(found ? AdjustmentDirection.RESTOCK : AdjustmentDirection.WRITE_OFF);
    }
}
