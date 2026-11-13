package com.stockflow.inventory.internal.domain;

import com.stockflow.common.id.Identifiers;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One carton of a {@link CycleCount}: whether the books had it at its bin, and whether it was found there (kltn-docs
 * 20 §4.2 b4; team plan 4C-3).
 *
 * <p>Counted by scanning, never in units (BR-14: the carton is not opened, its count is its standard count). The line
 * still records units - the books' and the found ones, each 0 or the carton's standard count - because the variance
 * becomes an adjustment of units ({@code tg_stock_adjustment_whole_carton} wants exactly a carton) and the stock
 * screens read units (20 §1: base units are kept internally for the trace). {@code tg_cycle_count_line_carton}
 * refuses any other figure. A loose line of the history keeps whatever it recorded, and is never counted again.</p>
 *
 * <p>Mutators are package-private on purpose: a line is only ever changed through its count, which checks that the
 * count is still open. A public setter would let a caller count a line of a closed count and skip that check.</p>
 */
public final class CycleCountLine {

    private final UUID id;
    /** Fixed, except that the bin follows the carton when it is moved whole before it is counted ({@link #relocate}). */
    private CountSlot slot;
    private int expectedQty;
    private Integer countedQty;
    private UUID countedBy;
    private Instant countedAt;
    private boolean recountRequired;

    /** A new, uncounted line: {@code expectedQty} is what the books held when it was planned. */
    CycleCountLine(CountSlot slot, int expectedQty) {
        this(Identifiers.newId(), slot, expectedQty, null, null, null, false);
        if (!slot.isCarton()) {
            throw new IllegalArgumentException("A count line names a carton: " + slot.lockKey());
        }
    }

    /** Rehydration from a stored row. */
    public CycleCountLine(UUID id, CountSlot slot, int expectedQty, Integer countedQty, UUID countedBy,
                          Instant countedAt, boolean recountRequired) {
        this.id = Objects.requireNonNull(id, "id");
        this.slot = Objects.requireNonNull(slot, "slot");
        if (expectedQty < 0) {
            throw new IllegalArgumentException("expectedQty must not be negative");
        }
        this.expectedQty = expectedQty;
        this.countedQty = countedQty;
        this.countedBy = countedBy;
        this.countedAt = countedAt;
        this.recountRequired = recountRequired;
    }

    /**
     * The carton was scanned at its bin ({@code found}) or not, while the books did or did not have it there
     * ({@code booked}, read now, BR-09). Repeating it before submission overwrites the earlier answer.
     *
     * @param units the carton's standard count: what one carton stands for in units
     */
    void count(boolean found, boolean booked, int units, UUID by, Instant at) {
        if (!slot.isCarton()) {
            throw new IllegalStateException("Line %s is a loose line of the history; a count states cartons".formatted(id));
        }
        if (units <= 0) {
            throw new IllegalArgumentException("A carton holds at least one unit");
        }
        this.expectedQty = booked ? units : 0;
        this.countedQty = found ? units : 0;
        this.countedBy = Objects.requireNonNull(by, "by");
        this.countedAt = Objects.requireNonNull(at, "at");
        this.recountRequired = false;
    }

    /**
     * The carton was moved whole since the line was planned, before it was counted (20 §4.2 b6). The line follows it:
     * the carton is the identity being counted, the bin only where it happens to be.
     */
    void relocate(LocationId to) {
        if (!slot.isCarton()) {
            throw new IllegalStateException("Only a carton line follows its carton");
        }
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

    /** Units the books held (0 or the carton's standard count); internal record, see the class comment. */
    public int expectedQty() {
        return expectedQty;
    }

    /** Units found (0 or the carton's standard count), null until counted; internal record, see the class comment. */
    public Integer countedQty() {
        return countedQty;
    }

    /** Whether the books had the carton at its bin when the line was counted (or planned, until then). */
    public boolean booked() {
        return expectedQty > 0;
    }

    /** Whether the carton was found; null until counted. */
    public Boolean found() {
        return countedQty == null ? null : countedQty > 0;
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
        return countedQty != null;
    }

    /**
     * Found minus booked, in units: one carton's standard count either way, or 0; null until the line is counted. A
     * shortage (the carton not found) is negative, a surplus (a LOST carton found) positive.
     */
    public Integer delta() {
        return countedQty == null ? null : countedQty - expectedQty;
    }
}
