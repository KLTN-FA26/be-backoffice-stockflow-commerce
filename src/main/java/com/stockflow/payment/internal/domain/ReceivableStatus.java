package com.stockflow.payment.internal.domain;

/**
 * kltn-docs 15 §5.3: {@code OPEN → PARTIALLY_PAID → PAID}, and {@code OVERDUE} once the due date has
 * passed unpaid. An overdue receivable stays OVERDUE until it is paid in full: being overdue is a
 * fact about the customer the next credit decision needs. Mapped {@code EnumType.STRING}.
 */
public enum ReceivableStatus {
    OPEN,
    PARTIALLY_PAID,
    OVERDUE,
    PAID
}
