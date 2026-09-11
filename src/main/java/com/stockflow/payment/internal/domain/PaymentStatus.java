package com.stockflow.payment.internal.domain;

/** Lifecycle of a payment. Mapped {@code EnumType.STRING}. */
public enum PaymentStatus { PENDING, CAPTURED, FAILED, REFUNDED }
