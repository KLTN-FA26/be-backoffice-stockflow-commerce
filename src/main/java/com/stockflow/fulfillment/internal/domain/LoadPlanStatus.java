package com.stockflow.fulfillment.internal.domain;

/**
 * Lifecycle of a {@link LoadPlan} (kltn-docs 09 §5 "Trạng thái Kế hoạch chuyến xe"). Mapped {@code EnumType.STRING}.
 *
 * <pre>
 *   DRAFT ─calculate─▶ PLANNED ─confirm─▶ CONFIRMED ─every vehicle handed over (PR E)─▶ DISPATCHED
 *     ▲                  │  ▲ edit / recalculate a vehicle    │
 *     └─change inputs────┘  └──────reopen (no vehicle loading yet, BR-14)
 *   DRAFT, PLANNED, CONFIRMED ─▶ CANCELLED (no vehicle loading yet)
 * </pre>
 */
public enum LoadPlanStatus {
    DRAFT,
    PLANNED,
    CONFIRMED,
    DISPATCHED,
    CANCELLED;

    /** Holds its parcels (BR-09): every status but the two final ones. DISPATCHED parcels left on their vehicles. */
    public boolean isLive() {
        return this == DRAFT || this == PLANNED || this == CONFIRMED;
    }
}
