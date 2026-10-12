package com.stockflow.order.api;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The amount Sales agreed with the customer to keep when an order is cancelled after its printing started
 * (decision Q2, 2026-10-12; BR-PRD-10, kltn-docs 17 §4.4). Entered, never computed: there is no formula. It is
 * kept out of what the customer paid — the rest is asked back as a refund by {@code payment} — and recording it
 * collects nothing and charges nothing. It is the customer's side only; what the job cost the business inside
 * (the units printed and written off) stays on the production order.
 *
 * @param amount at most what the customer has paid (15 BR-05), in whole units of the order's currency
 * @param reason why the customer pays it
 * @param detail what was agreed, with whom, how it was counted
 */
public record CancellationFee(BigDecimal amount, String reason, String detail) {

    public CancellationFee {
        Objects.requireNonNull(amount, "amount");
        reason = reason == null ? null : reason.strip();
        detail = detail == null ? null : detail.strip();
    }
}
