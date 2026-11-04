package com.stockflow.inventory.internal.domain;

/** Why a cycle count exists; mirrors the {@code cycle_count.trigger} CHECK. */
public enum CycleCountTrigger {
    SCHEDULED, SHORT_PICK, MOVE_DISCREPANCY, TRANSFER_DISCREPANCY, MANUAL
}
