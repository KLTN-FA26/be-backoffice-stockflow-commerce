package com.stockflow.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One of a customer's sales documents - a quote, an order, a sales contract or a production order - as another module
 * names it: whose it is, its number and where it stands. What the chat needs to check that a conversation's context is
 * its customer's (kltn-docs 16 BR-02) and to show the customer's documents beside the conversation (16 §4.1 step 3).
 *
 * @param number            the document's own number (quote, order, contract number; production order code)
 * @param status            the order module's name of its status, e.g. {@code SENT}, {@code CONFIRMED}
 * @param responsibleUserId the Sales account that answers for it: whoever looks after its customer today, the owner or
 *                          the owner's cover (sales-owner plan 2026-11-28, 18 BR-20) - not who sent it; null when the
 *                          customer has no active owner
 * @param amount            the order's total; null for the other kinds, whose amounts are per line or per instalment
 * @param currency          the currency of {@code amount}; null with it
 */
public record CustomerDocument(
        UUID id,
        UUID customerId,
        String number,
        String status,
        UUID responsibleUserId,
        BigDecimal amount,
        String currency,
        Instant createdAt
) {
}
