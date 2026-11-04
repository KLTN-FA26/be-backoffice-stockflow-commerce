package com.stockflow.procurement.internal.domain;

/**
 * Receiving state of one purchase order line ({@code ck_purchase_order_lines_status}).
 *
 * <p>{@code WITHDRAWN} is not receiving: it is a line taken off a draft after a submission froze it in
 * a revision (SCRUM-462). The row stays so the rejected revision keeps its lines
 * ({@code ck_purchase_order_lines_withdrawn}); it is no longer part of the order, so the aggregate
 * and the receiving view never load it.</p>
 */
public enum PoLineStatus {
    OPEN, PARTIALLY_RECEIVED, RECEIVED, CLOSED, CANCELLED, WITHDRAWN
}
