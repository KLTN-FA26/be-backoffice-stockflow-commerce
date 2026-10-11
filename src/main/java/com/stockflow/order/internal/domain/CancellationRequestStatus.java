package com.stockflow.order.internal.domain;

/** {@code PENDING → APPROVED | REJECTED}. Mapped {@code EnumType.STRING}. */
public enum CancellationRequestStatus {
    PENDING,
    APPROVED,
    REJECTED
}
