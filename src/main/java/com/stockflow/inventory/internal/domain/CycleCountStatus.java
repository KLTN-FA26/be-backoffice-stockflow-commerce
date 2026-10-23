package com.stockflow.inventory.internal.domain;

/** Lifecycle of a cycle count; mirrors the {@code cycle_count.status} CHECK. */
public enum CycleCountStatus {
    PLANNED, IN_PROGRESS, COMPLETED, CANCELLED;

    /** Open counts still take counts and hold their slots; completed and cancelled ones are history. */
    public boolean isOpen() {
        return this == PLANNED || this == IN_PROGRESS;
    }
}
