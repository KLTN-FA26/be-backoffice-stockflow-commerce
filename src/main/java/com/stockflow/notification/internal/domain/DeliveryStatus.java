package com.stockflow.notification.internal.domain;

/**
 * Outcome of a delivery attempt. Mapped {@code EnumType.STRING}.
 *
 * <p>{@link #SKIPPED} is a message deliberately not sent - no customer, no usable address, customer mail switched off -
 * and is always terminal: a redelivered event finds it and does not try again. It is not a failure, so it never counts
 * towards a retry limit and never shows as one.</p>
 */
public enum DeliveryStatus { PENDING, SENT, FAILED, SKIPPED }
