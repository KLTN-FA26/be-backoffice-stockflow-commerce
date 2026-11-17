package com.stockflow.order.api;

import com.stockflow.common.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A quote as the chat's cards show it, and the quote's own operations a card takes (kltn-docs 16 §4.2 step 5, §4.3
 * step 7, BR-03, BR-11): the chat changes no quote itself - accepting, asking for changes and drawing up a draft from a
 * quote request are this module's operations, with this module's checks, called on behalf of whoever pressed the card.
 */
public interface QuoteCards {

    /**
     * One line of a quote.
     *
     * @param cartons         cartons proposed
     * @param unitPrice       the price of one piece at the proposed cartons; null while the line is not priced
     * @param acceptedCartons cartons the customer accepted; null until then
     */
    record Line(UUID id, int lineNo, String sku, Integer cartons, Integer unitsPerCarton, int units,
                BigDecimal unitPrice, BigDecimal lineTotal, Integer acceptedCartons) {
    }

    /**
     * A quote as of now: a sent quote past its validity reads {@code EXPIRED}.
     *
     * @param status        the order module's name: DRAFT, SENT, ACCEPTED, CHANGES_REQUESTED, EXPIRED, CANCELLED,
     *                      CONVERTED
     * @param proposedTotal the total with tax at the proposed cartons; null while a part of it is unknown
     * @param version       what an answer to it names (the quote refuses one to a version the customer did not see)
     */
    record Card(UUID id, String number, UUID customerId, String status, int revision, Instant validUntil,
                LocalDate requestedDeliveryDate, List<Line> lines, BigDecimal proposedTotal, BigDecimal acceptedTotal,
                UUID convertedOrderId, String convertedOrderNumber, UUID sentBy, Instant sentAt, long version) {
    }

    /**
     * A draft quote prefilled from a customer's quote request (14 BR-22): their own SKUs at the cartons they asked - a
     * suggestion - and the delivery date they would like.
     *
     * @param requestId what makes the draft once only: drawing it up again with the same id answers the first draft
     */
    record Draft(UUID requestId, UUID customerId, List<DraftLine> lines, LocalDate requestedDeliveryDate,
                 Instant validUntil) {
    }

    record DraftLine(String sku, int cartons) {
    }

    Optional<Card> quote(UUID quoteId);

    /**
     * The customer accepts on the card, as on the quote's page.
     *
     * @param cartons per line id; a line not named is accepted at its proposed cartons
     * @throws com.stockflow.common.error.BusinessException the quote's own refusals: {@code QUOTE_EXPIRED},
     *         {@code INVALID_QUOTE_TRANSITION}, {@code OPTIMISTIC_LOCK}...
     */
    Card accept(CurrentUser customer, UUID quoteId, long version, Map<UUID, Integer> cartons);

    /** The customer asks for changes on the card, as on the quote's page. */
    Card requestChanges(CurrentUser customer, UUID quoteId, long version, String feedback);

    /**
     * Sales draw up a DRAFT quote from a quote request: lines priced from the customer's own price where there is one,
     * left for Sales to price otherwise.
     */
    Card draft(CurrentUser staff, Draft draft);
}
