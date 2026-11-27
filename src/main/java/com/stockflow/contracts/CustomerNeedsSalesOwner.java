package com.stockflow.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by <b>customer</b> when a customer lost its active owner and waits for a Sales Manager to assign one
 * (sales-owner plan BR-S08: the owner's account was locked or disabled). The former owner is kept for the history.
 * Until someone is assigned, {@code ResponsibleSales} answers MANAGERS for it. One event per customer, after commit.
 *
 * @param formerUserId the owner who left
 */
public record CustomerNeedsSalesOwner(UUID customerId, UUID formerUserId, Instant at) {

    public CustomerNeedsSalesOwner {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(at, "at");
    }
}
