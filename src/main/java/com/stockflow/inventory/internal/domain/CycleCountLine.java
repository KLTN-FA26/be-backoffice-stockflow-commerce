package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One slot of a {@link CycleCount}: what the books said and what was found.
 *
 * <p>Mutators are package-private on purpose: a line is only ever changed through its count, which
 * checks that the count is still open. A public setter would let a caller count a line of a closed
 * count and skip that check.</p>
 */
public final class CycleCountLine {

    private final UUID id;
    private final CountSlot slot;
    private int expectedQty;
    private Integer countedQty;
    private UUID countedBy;
    private Instant countedAt;
    private boolean recountRequired;

    /** A new, uncounted line. */
    CycleCountLine(CountSlot slot, int expectedQty) {
        this(Identifiers.newId(), slot, expectedQty, null, null, null, false);
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

    void count(int countedQty, int bookQty, UUID by, Instant at) {
        if (countedQty < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The counted quantity must not be negative");
        }
        this.expectedQty = bookQty;
        this.countedQty = countedQty;
        this.countedBy = Objects.requireNonNull(by, "by");
        this.countedAt = Objects.requireNonNull(at, "at");
        this.recountRequired = false;
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

    public int expectedQty() {
        return expectedQty;
    }

    public Integer countedQty() {
        return countedQty;
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

    /** Found minus booked; null until the line is counted. */
    public Integer delta() {
        return countedQty == null ? null : countedQty - expectedQty;
    }
}
