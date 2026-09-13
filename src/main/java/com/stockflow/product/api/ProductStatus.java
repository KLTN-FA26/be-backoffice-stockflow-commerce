package com.stockflow.product.api;

/**
 * Product master lifecycle (WBS 3.1.1, {@code docs/business-design/05-business-rules.md} BR-PRD-*).
 *
 * <p>Public because {@code ProductSummary} carries it. The transitions stay private to the module —
 * {@link #canTransitionTo} is exposed so a caller can ask, but only {@code product} may actually
 * move a row.</p>
 *
 * <p>The full lifecycle, per {@code docs/business-design/03-state-machines.md} §1:</p>
 * <pre>
 *   DRAFT ──submit──▶ PENDING_APPROVAL ──approve──▶ APPROVED ──publish──▶ PUBLISHED
 *     ▲                     │                          │                    │
 *     └─────────reject──────┘                          └───discontinue──────┴───▶ DISCONTINUED
 * </pre>
 *
 * <p><b>Scope note (SCRUM-57/WBS 3.1.1.3, SCRUM-85/WBS 3.1.8.1):</b> {@code submit}/{@code approve}/
 * {@code reject} (SCRUM-57) and {@code discontinue} (SCRUM-85) are the edges this module
 * implements. {@code publish}/{@code unpublish} are not: BR-PRD-004 gates them on having a price
 * and an image, neither of which exists on this aggregate yet — that is WBS 3.1.6, a later story.
 * {@code PUBLISHED} was named {@code ACTIVE} before this change; the rename brings the enum in
 * line with the state-machine doc before any code could depend on the old name (it was never
 * reachable — {@link #canTransitionTo} always answered {@code false} for it).</p>
 */
public enum ProductStatus {

    /** Being set up; not submitted for approval. */
    DRAFT,

    /** Submitted for approval (BR-PRD-003 applies to the next transition). */
    PENDING_APPROVAL,

    /** Approved. Can be discontinued directly, or (WBS 3.1.6, not yet implemented) published. */
    APPROVED,

    /** Published and orderable (WBS 3.1.6). Unreachable today — no method sets it yet. */
    PUBLISHED,

    /** Terminal. Kept for the order/PO history that references it — nothing leaves this state. */
    DISCONTINUED;

    /**
     * The transition table, as one exhaustive switch — same pattern as {@code order.api.OrderStatus}.
     * Adding a status forces every branch here to be revisited.
     */
    public boolean canTransitionTo(ProductStatus target) {
        return switch (this) {
            case DRAFT -> target == PENDING_APPROVAL;
            case PENDING_APPROVAL -> target == APPROVED || target == DRAFT;
            // APPROVED -> PUBLISHED and PUBLISHED -> APPROVED (publish/unpublish) are WBS 3.1.6,
            // not yet implemented - only the discontinuation edge exists so far (SCRUM-85).
            case APPROVED, PUBLISHED -> target == DISCONTINUED;
            case DISCONTINUED -> false;
        };
    }
}
