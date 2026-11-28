package com.stockflow.order.api;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The amount Sales agreed with the customer to keep when an order is cancelled after its printing started
 * (decision Q2, 2026-10-12; BR-PRD-10, kltn-docs 17 §4.4). Entered, never computed: there is no formula. It is
 * kept out of what the customer paid — the rest is asked back as a refund by {@code payment} (Q6b) — and what
 * the payments do not cover becomes a receivable (Q6a). Recording it collects nothing and marks nothing paid. What the
 * printing cost the business is between it and its producer, not this. It is the customer's side only unless Sales say
 * otherwise: since D50 (2026-11-28) they choose who bears it, and when the {@link CancellationFeeBearer#COMPANY
 * company} does, the customer is charged nothing and the amount, reason and detail are kept as a cost of the company.
 *
 * @param amount in whole units of the order's currency; the money received pays it first, the rest of that
 *               money is refunded (Q6b), a shortfall is owed (Q6a)
 * @param reason why the customer pays it
 * @param detail what was agreed, with whom, how it was counted
 * @param bearer who bears it (D46, D50): required, no default - Sales choose on every fee
 */
public record CancellationFee(BigDecimal amount, String reason, String detail, CancellationFeeBearer bearer) {

    public CancellationFee {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(bearer, "bearer");
        reason = reason == null ? null : reason.strip();
        detail = detail == null ? null : detail.strip();
    }
}
