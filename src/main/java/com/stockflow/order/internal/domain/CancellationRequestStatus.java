package com.stockflow.order.internal.domain;

/** {@code PENDING → APPROVED | REJECTED}. Mapped {@code EnumType.STRING}. */
public enum CancellationRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    /**
     * Closed by the system: the order was packed, handed over, delivered or cancelled before anyone decided, so the
     * request can no longer be approved (E2E QA 2026-10-21). No decider; the decision note says why, for the customer.
     */
    EXPIRED
}
