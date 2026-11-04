package com.stockflow.fulfillment.internal.domain;

/**
 * Lifecycle of one pick line (docs 07 step 6, BR-05). Mapped {@code EnumType.STRING}.
 *
 * <pre>
 *   OPEN ──confirm (scan)──▶ PICKED ──put back (order cancelled)──▶ PUT_BACK
 *     ├──short pick (SCRUM-268)──▶ SHORT
 *     └──order cancelled before it was picked──▶ CANCELLED
 * </pre>
 */
public enum PickLineStatus { OPEN, PICKED, SHORT, CANCELLED, PUT_BACK }
