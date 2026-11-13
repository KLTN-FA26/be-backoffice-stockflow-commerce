package com.stockflow.design.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A customer's quote requests as the chat's quote request card shows them (kltn-docs 16 §4.2 step 5, BR-11): what was
 * asked and the design as it is now - its status read here, never decided in the chat (BR-03). Closing a request
 * answered by a quote is this module's operation too.
 */
public interface QuoteRequests {

    /**
     * A request on one design.
     *
     * @param designStatus DRAFT (returned, with {@code returnReason}), PENDING_REVIEW, APPROVED or ARCHIVED
     * @param sku          the variant the design is drawn on
     * @param customerSku  the customer's own SKU, once the design is approved; null before
     * @param status       OPEN or CLOSED
     */
    record DesignRequest(UUID id, UUID designId, String designName, String designStatus, UUID customerId, String sku,
                         String customerSku, String returnReason, int cartons, LocalDate desiredDeliveryDate,
                         String status, Instant requestedAt, Instant closedAt) {
    }

    /** One approved design of a project request. */
    record ProjectLine(UUID designId, String designName, String designStatus, String customerSku, int cartons) {
    }

    /** A request over several approved designs of a project, one delivery date for all. */
    record ProjectRequest(UUID id, UUID projectId, String projectName, UUID customerId, LocalDate desiredDeliveryDate,
                          Instant requestedAt, List<ProjectLine> lines) {
    }

    Optional<DesignRequest> designRequest(UUID requestId);

    Optional<ProjectRequest> projectRequest(UUID requestId);

    /**
     * Sales answered the request with a quote: closed, so the design asks again for its next order with a new one.
     * Closing a closed request changes nothing.
     */
    void close(UUID requestId, UUID salesUserId);
}
