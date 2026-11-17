package com.stockflow.payment.internal.domain;

/**
 * kltn-docs 15 §5.1: an installment is {@code NOT_DUE} until its milestone comes, then {@code DUE}; receipts make it
 * {@code PARTIALLY_PAID} or {@code PAID} - before its milestone too, when the customer pays early. The unpaid
 * installments of a cancelled order are {@code CANCELLED}. A voided receipt puts an installment back to what its money
 * and milestone say ({@code ck_installment_status_facts}). Mapped {@code EnumType.STRING}.
 *
 * <p><b>Overdue is not a status</b> (WF-05 fix plan, section 8). It is worked out - past its due day and still owing,
 * {@link Installment#overdue} - because as a status it hid the money: an overdue installment paid in part stayed
 * {@code OVERDUE} and never read {@code PARTIALLY_PAID}, and it was only as true as the last nightly sweep. The status
 * now says the money and the milestone, the flag says the calendar, and neither waits for a job.
 * {@link #OVERDUE} stays as a constant for the history that names it; no installment is in it
 * ({@code ck_installment_status}, V20261121000100 moved those that were).</p>
 */
public enum InstallmentStatus {
    NOT_DUE,
    DUE,
    PARTIALLY_PAID,
    PAID,
    /** Historical: no installment enters it any more - see the class comment and {@link Installment#overdue}. */
    OVERDUE,
    CANCELLED;

    /** Whether a receipt may still pay it (15 BR-05): neither paid in full nor cancelled. */
    public boolean takesMoney() {
        return this != PAID && this != CANCELLED;
    }
}
