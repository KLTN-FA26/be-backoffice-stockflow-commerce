package com.stockflow.payment.internal.domain;

/**
 * kltn-docs 15 §5.1: an installment is {@code NOT_DUE} until its milestone comes, then {@code DUE}; receipts make it
 * {@code PARTIALLY_PAID} or {@code PAID} - before its milestone too, when the customer pays early - and the nightly
 * sweep makes one past its due day {@code OVERDUE}, which it stays until paid in full. The unpaid installments of a
 * cancelled order are {@code CANCELLED}. A voided receipt puts an installment back to what its money and milestone say
 * ({@code ck_installment_status_facts}). Mapped {@code EnumType.STRING}.
 */
public enum InstallmentStatus {
    NOT_DUE,
    DUE,
    PARTIALLY_PAID,
    PAID,
    OVERDUE,
    CANCELLED;

    /** Whether a receipt may still pay it (15 BR-05): neither paid in full nor cancelled. */
    public boolean takesMoney() {
        return this != PAID && this != CANCELLED;
    }
}
