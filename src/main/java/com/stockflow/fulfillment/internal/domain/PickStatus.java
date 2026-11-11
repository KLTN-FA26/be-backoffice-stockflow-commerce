package com.stockflow.fulfillment.internal.domain;

/**
 * Lifecycle of a pick (kltn-docs 07 §5.1). CANCELLED = its order was cancelled (SCRUM-460). A short pick is a line's
 * state ({@link PickLineStatus#SHORT}), never the task's: the task stays PICKING while the coordinator reallocates, so
 * the former SHORT, which nothing wrote, is gone (V20261113000400). Mapped {@code EnumType.STRING}.
 *
 * <pre>
 *   PENDING ──start──▶ PICKING ──every line picked (DISPATCH)──▶ PICKED
 *                         └──every line staged (STAGING)──▶ STAGED ──moved to dispatch──▶ PICKED
 *   STAGED / PICKED ──lines added (a top-up, 07 §4.3 step 7)──▶ PICKING
 *   any ──order cancelled──▶ CANCELLED
 * </pre>
 *
 * <p>{@link #STAGED} (team plan 4C-5): every line of a STAGING task is in the staging area, held for the order and not
 * deducted; the warehouse manager moves the whole task to dispatch, which deducts them (07 §4.3 step 8).</p>
 */
public enum PickStatus { PENDING, PICKING, STAGED, PICKED, CANCELLED }
