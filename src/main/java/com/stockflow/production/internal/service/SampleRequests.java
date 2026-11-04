package com.stockflow.production.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.SampleStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Sample requests and the customer's answer (SCRUM-394/403, 03-state-machines §17).
 *
 * <p>Every call names its caller. The back office drafts, submits, records sending and cancels; the
 * customer — on the portal, or Sales recording it on their behalf — approves or asks for changes. A
 * portal user is held to their own customer's rounds here, in the service, because the CUSTOMER role's
 * data scope is ALL ({@code ScopeGuards}): another customer's round answers 404, so its existence is not
 * revealed. A warehouse-bound workshop user sees only the rounds made in their warehouses.</p>
 */
public interface SampleRequests {

    record Draft(UUID designSnapshotId, String blankSku, int quantity, LocalDate dueDate) {
    }

    record Create(UUID customerId, UUID quoteId, Draft draft) {
    }

    record Criteria(UUID customerId, List<SampleStatus> statuses, UUID designSnapshotId, String number, int page,
                    int size, String sort) {
    }

    /** The SAMPLE production order a submitted round became. */
    record ProductionOrderRef(UUID id, String orderNumber, ProductionOrder.Status status, UUID warehouseId) {
    }

    /** One round as the history of a request shows it: what was made and what the customer said. */
    record Round(UUID id, String requestNumber, int round, SampleStatus status, UUID designSnapshotId,
                 String blankSku, int quantity, LocalDate dueDate, Instant sentAt, Instant respondedAt,
                 UUID respondedBy, String customerFeedback, String cancellationReason) {
    }

    record Row(UUID id, String requestNumber, UUID customerId, int round, SampleStatus status, UUID designSnapshotId,
               String blankSku, int quantity, LocalDate dueDate, Instant sentAt, Instant respondedAt,
               ProductionOrderRef productionOrder, Instant createdAt) {
    }

    /**
     * @param nextRoundId the round that followed this one, null while there is none
     * @param rounds      every round of the request, first round first, this one included
     */
    record Sample(UUID id, String requestNumber, UUID customerId, UUID quoteId, UUID previousRoundId,
                  UUID nextRoundId, int round, SampleStatus status, Draft specification, Instant sentAt,
                  Instant respondedAt, UUID respondedBy, String customerFeedback, String cancellationReason,
                  ProductionOrderRef productionOrder, List<Round> rounds, Instant createdAt, String createdBy,
                  Instant lastModifiedAt, String lastModifiedBy, long version) {
    }

    Sample create(CurrentUser user, Create command);

    PageResponse<Row> list(CurrentUser user, Criteria criteria);

    Sample get(CurrentUser user, UUID sampleRequestId);

    Sample edit(CurrentUser user, UUID sampleRequestId, Draft draft);

    /** DRAFT → SUBMITTED and its SAMPLE production order in the chosen workshop, in one transaction. */
    Sample submit(CurrentUser user, UUID sampleRequestId, UUID warehouseId);

    Sample markSent(CurrentUser user, UUID sampleRequestId);

    Sample approve(CurrentUser user, UUID sampleRequestId);

    Sample requestChanges(CurrentUser user, UUID sampleRequestId, String feedback);

    /** A new DRAFT round after CHANGES_REQUESTED; {@code draft} null repeats this round's specification. */
    Sample startNextRound(CurrentUser user, UUID sampleRequestId, Draft draft);

    Sample cancel(CurrentUser user, UUID sampleRequestId, String reason);
}
