package com.stockflow.fulfillment.internal.domain;

/**
 * Lifecycle of one pick line - one carton (kltn-docs 07 §5.2, BR-04). Mapped {@code EnumType.STRING}.
 *
 * <pre>
 *   OPEN ──confirm (scan location + LPN), DISPATCH task──▶ PICKED ──put back the carton (order cancelled)──▶ PUT_BACK
 *     ├──confirm, STAGING task──▶ STAGED ──task moved to dispatch──▶ PICKED
 *     │                             └──put back from the staging area (order cancelled)──▶ PUT_BACK
 *     ├──short pick, with a reason──▶ SHORT ──reallocated──▶ CANCELLED
 *     └──order cancelled before it was picked──▶ CANCELLED
 * </pre>
 *
 * <p>{@link #STAGED} (team plan 4C-5): the carton was moved to the task's staging area and is still held for the order,
 * not deducted - a line's state, not a stock status (07 §5.2 note).</p>
 */
public enum PickLineStatus { OPEN, STAGED, PICKED, SHORT, CANCELLED, PUT_BACK }
