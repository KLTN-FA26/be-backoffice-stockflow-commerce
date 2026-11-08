package com.stockflow.design.internal.domain;

/**
 * Lifecycle of a design (kltn-docs 12 §5.1). Mapped {@code EnumType.STRING}; {@code ck_design_draft_status} lists the
 * same four.
 *
 * <ul>
 *   <li>{@code DRAFT} - the customer is designing; only they see it (BR-03);</li>
 *   <li>{@code PENDING_REVIEW} - waits for a decision, and the customer cannot change it meanwhile (BR-07). A design
 *       the customer started gets here by passing preflight (2F-5); one staff started, through the staff workflow's
 *       confirmation request, which can also send it back until that workflow goes (2F-6);</li>
 *   <li>{@code APPROVED} - files, snapshot and the customer's SKU issued; final (BR-08);</li>
 *   <li>{@code ARCHIVED} - a draft the customer deleted (BR-16); final, and absent for everyone.</li>
 * </ul>
 */
public enum DesignStatus { DRAFT, PENDING_REVIEW, APPROVED, ARCHIVED }
