package com.stockflow.product.api;

/**
 * Product master lifecycle (WBS 3.1.1, {@code docs/business-design/05-business-rules.md} BR-PRD-*).
 *
 * <p>Public because {@code ProductSummary} carries it. The transitions stay private to the module —
 * only {@code product} may actually move a row.</p>
 *
 * <p><b>Scope note (SCRUM-44/WBS 3.1.1):</b> this is the SCRUM-56 slice — product master
 * <i>fields</i>, no approval workflow yet, so every product created here stays {@code DRAFT}.
 * {@code PENDING_APPROVAL}/{@code APPROVED} and the {@code submit}/{@code approve}/{@code reject}
 * methods land on the stacked SCRUM-57 branch, together with the migration that widens
 * {@code ck_product_status} to match — the two must move together. {@code ACTIVE}/
 * {@code DISCONTINUED} are carried over from the starter stub and stay inert until WBS 3.1.6/3.1.8
 * (needs a price and an image, BR-PRD-004/BR-PRD-005).</p>
 */
public enum ProductStatus {

    /** Being set up; not submitted for approval. */
    DRAFT,

    /** Published and orderable. Unreachable today; carried over from the starter stub. */
    ACTIVE,

    /** No longer sold; kept for the order history that references it. */
    DISCONTINUED
}
