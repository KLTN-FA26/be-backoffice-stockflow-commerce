package com.stockflow.payment.internal.domain;

import java.time.LocalDate;
import java.util.UUID;

/** Reads {@link PaymentHistory}. Joins the caller's transaction. */
public interface PaymentHistories {

    /** The customer's history as of {@code today}, the business day; all zero for a customer who never signed. */
    PaymentHistory of(UUID customerId, LocalDate today);
}
