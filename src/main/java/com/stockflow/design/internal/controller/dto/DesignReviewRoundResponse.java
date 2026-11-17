package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A project's review round as the Sales Manager reads it (kltn-docs 12 §4.10): the project's name as context and
 * nothing else of it (BR-17), the customer, and each design with the decision on it - {@code outcome} absent while it
 * waits. {@code closedAt} is set once every design is decided (BR-20).
 */
public record DesignReviewRoundResponse(UUID id, UUID projectId, String projectName, UUID customerId,
                                        String customerName, UUID submittedBy, Instant submittedAt, Instant closedAt,
                                        List<Design> designs) {

    /**
     * One design of the round and the decision on it. {@code design} - the design as it is now - only while it waits
     * or once approved: returned or deleted, it is its owner's alone (kltn-docs 12 BR-03).
     */
    public record Design(UUID designId, DesignDraftResponse design, String outcome, Instant decidedAt) {
    }
}
