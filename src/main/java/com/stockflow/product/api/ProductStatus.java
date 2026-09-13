package com.stockflow.product.api;

/**
 * Product master lifecycle (WBS 3.1.1, {@code docs/business-design/05-business-rules.md} BR-PRD-*).
 *
 * <p>Public because {@code ProductSummary} carries it. The transitions stay private to the module —
 * {@link #canTransitionTo} is exposed so a caller can ask, but only {@code product} may actually
 * move a row.</p>
 *
 * <pre>
 *   DRAFT ──submit──▶ PENDING_APPROVAL ──approve──▶ APPROVED
 *     ▲                     │
 *     └─────────reject──────┘
 * </pre>
 *
 * <p><b>Scope note (SCRUM-57/WBS 3.1.1.3):</b> only the edges drawn above have a transition method
 * ({@code Product.submit()}/{@code approve()}/{@code reject()}). {@code ACTIVE} and
 * {@code DISCONTINUED} are carried over from the starter stub and stay inert:
 * {@code activate()}/{@code publish()}/{@code deactivate()} and BR-PRD-004 (needs a price and an
 * image) are WBS 3.1.6, a later story — {@link #canTransitionTo} answers {@code false} for every
 * edge this module does not yet implement, not because the business rule forbids it.</p>
 */
public enum ProductStatus {

    /** Being set up; not submitted for approval. */
    DRAFT,

    /** Submitted for approval (BR-PRD-003 applies to the next transition). */
    PENDING_APPROVAL,

    /** Approved. No method moves a product past this state yet — see the class javadoc. */
    APPROVED,

    /** Published and orderable. Unreachable today; carried over from the starter stub. */
    ACTIVE,

    /** No longer sold; kept for the order history that references it. */
    DISCONTINUED;

    /**
     * The transition table, as one exhaustive switch — same pattern as {@code order.api.OrderStatus}.
     * Adding a status forces every branch here to be revisited.
     */
    public boolean canTransitionTo(ProductStatus target) {
        return switch (this) {
            case DRAFT -> target == PENDING_APPROVAL;
            case PENDING_APPROVAL -> target == APPROVED || target == DRAFT;
            case APPROVED, ACTIVE, DISCONTINUED -> false;
        };
    }
}
