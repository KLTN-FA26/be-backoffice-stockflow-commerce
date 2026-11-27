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

    /**
     * One design of a project request: approved, waiting for review, or returned for changes (12 BR-25).
     *
     * @param designStatus DRAFT (returned, with {@code returnReason}), PENDING_REVIEW, APPROVED or ARCHIVED
     * @param customerSku  the customer's own SKU, once the design is approved; null before
     * @param returnReason the reviewer's notes of a design returned for changes; null otherwise
     */
    record ProjectLine(UUID designId, String designName, String designStatus, String customerSku, int cartons,
                       String returnReason) {

        /** A line with no return reason, as read before BR-25. */
        public ProjectLine(UUID designId, String designName, String designStatus, String customerSku, int cartons) {
            this(designId, designName, designStatus, customerSku, cartons, null);
        }

        public boolean approved() {
            return "APPROVED".equals(designStatus);
        }
    }

    /**
     * A request over several designs of a project, one delivery date for all; quoted once every line is approved.
     *
     * @param status      OPEN or CLOSED (answered by Sales, emptied by the customer, or closed by Sales with a reason)
     * @param closeReason why a salesperson closed it without a quote (12 BR-25.3); null otherwise
     */
    record ProjectRequest(UUID id, UUID projectId, String projectName, UUID customerId, LocalDate desiredDeliveryDate,
                          Instant requestedAt, String status, List<ProjectLine> lines, String closeReason) {

        /** A request with no close reason, as read before BR-25. */
        public ProjectRequest(UUID id, UUID projectId, String projectName, UUID customerId,
                              LocalDate desiredDeliveryDate, Instant requestedAt, String status,
                              List<ProjectLine> lines) {
            this(id, projectId, projectName, customerId, desiredDeliveryDate, requestedAt, status, lines, null);
        }

        /** How many of its lines are approved: the card's "approved x/y". */
        public int approvedCount() {
            return (int) lines.stream().filter(ProjectLine::approved).count();
        }
    }

    Optional<DesignRequest> designRequest(UUID requestId);

    Optional<ProjectRequest> projectRequest(UUID requestId);

    /**
     * The project request locked after its project, read as it is now: what Sales draw a quote from, in their own
     * transaction, so that no line is added, changed or dropped - and no design leaves it - while they do (BR-25.2).
     * Empty for no such request.
     */
    Optional<ProjectRequest> lockProjectRequest(UUID requestId);

    /** The open project request naming the design, if any: its card follows the design's review (16 §4.2). */
    Optional<UUID> openProjectRequestOf(UUID designId);

    /**
     * Sales answered the request with a quote: closed, so the design asks again for its next order with a new one.
     * Closing a closed request changes nothing.
     */
    void close(UUID requestId, UUID salesUserId);

    /**
     * Sales answered a project's request with one quote of every design on it (14 BR-22): closed like a design's
     * request, so the project asks again for its next order with a new one, and the request no longer reads as waiting
     * in the request lists. Closing a closed request changes nothing.
     */
    void closeProject(UUID requestId, UUID salesUserId);
}
