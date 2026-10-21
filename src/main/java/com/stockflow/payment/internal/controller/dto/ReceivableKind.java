package com.stockflow.payment.internal.controller.dto;

/**
 * The kinds of receivable, as a list filter: an enum so a typo is a 400 rather than an empty page. A delivered credit
 * order, an agreed cancellation fee, or (G2-3) a deposit order's balance.
 */
public enum ReceivableKind {
    CREDIT_ORDER,
    CANCELLATION_FEE,
    DEPOSIT_BALANCE
}
