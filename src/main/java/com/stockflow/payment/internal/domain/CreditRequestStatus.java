package com.stockflow.payment.internal.domain;

/**
 * Where a credit request stands (QĐ-A, 2026-11-26; kltn-docs 15 §4.3): {@code SUBMITTED} - the customer asked from the
 * portal and Sales have not given their opinion; {@code PENDING_APPROVAL} - Sales' opinion is in, the accountant
 * decides; then {@code APPROVED}, {@code REJECTED} or {@code WITHDRAWN} by whoever filed it, for good.
 */
public enum CreditRequestStatus {
    SUBMITTED,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    WITHDRAWN;

    /** Still waiting for someone: at most one such request per customer ({@code uk_credit_request_open}). */
    public boolean open() {
        return this == SUBMITTED || this == PENDING_APPROVAL;
    }
}
