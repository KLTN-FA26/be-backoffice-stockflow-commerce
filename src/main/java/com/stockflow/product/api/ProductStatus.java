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
 * <p><b>Scope note:</b> publication is separate from master approval. SCRUM-53 requires an approved
 * managed gallery before the product becomes publicly visible. Selling-price validation remains
 * owned by the catalog/pricing story because price does not belong to this aggregate.</p>
 */
public enum ProductStatus {

    /** Being set up; not submitted for approval. */
    DRAFT,

    /** Submitted for approval (BR-PRD-003 applies to the next transition). */
    PENDING_APPROVAL,

    /** Approved. Can be discontinued directly or published after the media gate passes. */
    APPROVED,

    /** Published and visible through the anonymous product-media projection. */
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
            case APPROVED -> target == PUBLISHED || target == DISCONTINUED;
            case PUBLISHED -> target == APPROVED || target == DISCONTINUED;
            case DISCONTINUED -> false;
        };
    }
}
