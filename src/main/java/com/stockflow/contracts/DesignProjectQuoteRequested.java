package com.stockflow.contracts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>design</b> module when a customer asks one quote over approved designs of a design project
 * (kltn-docs 12 §4.11, BR-21; 16 §4.2): one line per design, one delivery date. {@code notification} tells the sales
 * staff, until the chat module carries the request as a card of several lines. Facts only: no project or design name,
 * which the customer typed.
 *
 * @param requestId           the request
 * @param projectId           the project
 * @param customerId          whose project it is
 * @param lines               each approved design, its own SKU and the cartons asked - a suggestion (14 BR-22)
 * @param desiredDeliveryDate the delivery date they would like for all of it, or null
 * @param requestedAt         when they asked
 */
public record DesignProjectQuoteRequested(UUID requestId, UUID projectId, UUID customerId, List<Line> lines,
                                          LocalDate desiredDeliveryDate, Instant requestedAt) {

    public DesignProjectQuoteRequested {
        lines = List.copyOf(lines);
    }

    /** One approved design of the request. */
    public record Line(UUID designId, String customerSku, int cartons) {
    }
}
