package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One quote asked over designs of a project (kltn-docs 12 §4.11, BR-21, BR-25): the project's name as the context the
 * sales staff see (BR-17), one delivery date, and a line per design - its status now, its own SKU once approved, the
 * cartons asked (a suggestion) and, for a design returned for changes, why. {@code status} is OPEN until a quote answers
 * it, the customer empties it, or the sales staff close it with {@code closeReason}; one is open per project at a time.
 * Sales quote it once {@code approvedCount} reaches {@code lineCount}.
 *
 * <p>R5-5: {@code closedOutcome}, {@code quoteId}, {@code quoteNumber} as on a design's request
 * ({@code DesignQuoteRequestResponse}).</p>
 */
public record DesignProjectQuoteRequestResponse(UUID id, UUID projectId, String projectName, UUID customerId,
                                                String customerName, LocalDate desiredDeliveryDate, UUID requestedBy,
                                                Instant requestedAt, String status, Instant closedAt, UUID closedBy,
                                                List<Line> lines, String closedOutcome, UUID quoteId,
                                                String quoteNumber, String closeReason, int approvedCount,
                                                int lineCount) {

    /**
     * One design of the request.
     *
     * @param designStatus DRAFT (returned, with {@code returnReason}), PENDING_REVIEW, APPROVED or ARCHIVED
     */
    public record Line(UUID designId, String designName, String designStatus, String customerSku, int cartons,
                       String returnReason) {
    }
}
