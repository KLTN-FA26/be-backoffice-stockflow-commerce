package com.stockflow.fulfillment.internal.domain;

/**
 * Lifecycle of one pick line - one carton (kltn-docs 07 §5.2, BR-04). Mapped {@code EnumType.STRING}.
 *
 * <pre>
 *   OPEN ──confirm (scan location + LPN)──▶ PICKED ──put back the carton (order cancelled)──▶ PUT_BACK
 *     ├──short pick, with a reason──▶ SHORT ──reallocated──▶ CANCELLED
 *     └──order cancelled before it was picked──▶ CANCELLED
 * </pre>
 */
public enum PickLineStatus { OPEN, PICKED, SHORT, CANCELLED, PUT_BACK }
