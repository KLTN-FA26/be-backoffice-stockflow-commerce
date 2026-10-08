package com.stockflow.production.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.ConflictException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.SampleApproved;
import com.stockflow.production.api.SampleStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer's sample approval journey. Only drafts can change specifications; each decision
 * retains the exact design, checksum and blank the customer saw. Methods validate before mutation.
 */
public final class SampleRequest extends AggregateRoot {
    private final UUID id;
    private final UUID customerId;
    private final UUID quoteId;
    private final UUID createdBy;
    private final List<SampleReview> reviews = new ArrayList<>();
    private SampleSpecification specification;
    private SampleStatus status = SampleStatus.DRAFT;
    private int round = 1;
    private long version;
    private UUID warehouseId;

    private SampleRequest(UUID id, UUID customerId, UUID quoteId, UUID createdBy,
                          SampleSpecification specification) {
        this.id = Objects.requireNonNull(id, "id");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.quoteId = quoteId;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.specification = Objects.requireNonNull(specification, "specification");
    }

    public static SampleRequest draft(UUID customerId, UUID quoteId, UUID createdBy,
                                      SampleSpecification specification) {
        return new SampleRequest(Identifiers.newId(), customerId, quoteId, createdBy, specification);
    }

    /** Rehydrate under the repository lock without replaying decisions or raising new events. */
    public static SampleRequest restore(UUID id, UUID customerId, UUID quoteId, UUID createdBy,
                                        SampleSpecification specification, SampleStatus status,
                                        int round, long version, UUID warehouseId, List<SampleReview> reviews) {
        Objects.requireNonNull(status, "status");
        List<SampleReview> history = List.copyOf(reviews);
        if (round < 1 || version < 0) {
            throw new IllegalArgumentException("Invalid sample round or version");
        }
        boolean reviewed = status == SampleStatus.APPROVED || status == SampleStatus.CHANGES_REQUESTED;
        int expectedReviews = reviewed ? round : round - 1;
        if (history.size() != expectedReviews) {
            throw new IllegalArgumentException("Review history must cover every previous round");
        }
        for (int index = 0; index < history.size(); index++) {
            var review = history.get(index);
            var expectedDecision = status == SampleStatus.APPROVED && index == round - 1
                    ? SampleReview.Decision.APPROVED : SampleReview.Decision.CHANGES_REQUESTED;
            if (review.round() != index + 1 || review.decision() != expectedDecision) {
                throw new IllegalArgumentException("Review history is out of order or has a premature approval");
            }
        }
        if (reviewed && !history.getLast().specification().equals(specification)) {
            throw new IllegalArgumentException("Reviewed specification cannot change");
        }
        boolean needsWarehouse = status != SampleStatus.DRAFT && status != SampleStatus.CANCELLED;
        if (needsWarehouse != (warehouseId != null)) {
            throw new IllegalArgumentException("Only a submitted round carries a selected warehouse");
        }
        var sample = new SampleRequest(id, customerId, quoteId, createdBy, specification);
        sample.status = status;
        sample.round = round;
        sample.version = version;
        sample.warehouseId = warehouseId;
        sample.reviews.addAll(history);
        return sample;
    }

    public void requireRevision(int expectedRound, long expectedVersion) {
        if (expectedRound != round || expectedVersion != version) {
            throw new ConflictException(ErrorCode.OPTIMISTIC_LOCK, "The sample round or version has changed");
        }
    }

    public void edit(SampleSpecification replacement) {
        requireStatus(SampleStatus.DRAFT);
        specification = Objects.requireNonNull(replacement, "replacement");
        version++;
    }

    public void submit(UUID selectedWarehouseId) {
        requireTransition(SampleStatus.SUBMITTED);
        warehouseId = Objects.requireNonNull(selectedWarehouseId, "warehouseId");
        status = SampleStatus.SUBMITTED;
        version++;
    }

    /** Called by the production workflow after work on this exact round begins. */
    public void startProduction(int productionRound) {
        requireRound(productionRound);
        transition(SampleStatus.IN_PRODUCTION);
    }

    /** Called only after the sample production order has completed QC (SCRUM-400). */
    public void markReady(int productionRound) {
        requireRound(productionRound);
        transition(SampleStatus.READY);
    }

    public void sendToCustomer() {
        transition(SampleStatus.SENT_TO_CUSTOMER);
    }

    public void requestChanges(String feedback, UUID actorId, Instant now) {
        requireTransition(SampleStatus.CHANGES_REQUESTED);
        SampleReview review = new SampleReview(round, specification,
                SampleReview.Decision.CHANGES_REQUESTED, feedback, actorId, now);
        reviews.add(review);
        status = SampleStatus.CHANGES_REQUESTED;
        version++;
    }

    public void revise() {
        requireTransition(SampleStatus.DRAFT);
        int nextRound = Math.incrementExact(round);
        status = SampleStatus.DRAFT;
        round = nextRound;
        warehouseId = null;
        version++;
    }

    public void approve(UUID actorId, Instant now) {
        requireTransition(SampleStatus.APPROVED);
        SampleReview review = new SampleReview(round, specification,
                SampleReview.Decision.APPROVED, null, actorId, now);
        reviews.add(review);
        status = SampleStatus.APPROVED;
        version++;
        registerEvent(new SampleEvent(Identifiers.newId(), now, new SampleApproved(
                id, customerId, specification.designSnapshotId(), specification.blankSku().code())));
    }

    public void cancel() {
        transition(SampleStatus.CANCELLED);
    }

    private void transition(SampleStatus target) {
        requireTransition(target);
        status = target;
        version++;
    }

    private void requireTransition(SampleStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new ConflictException("Sample cannot move from " + status + " to " + target);
        }
    }

    private void requireStatus(SampleStatus expected) {
        if (status != expected) {
            throw new ConflictException("Sample must be " + expected);
        }
    }

    private void requireRound(int productionRound) {
        if (productionRound != round) {
            throw new ConflictException("Production callback belongs to a different sample round");
        }
    }

    public UUID id() { return id; }
    public UUID customerId() { return customerId; }
    public UUID quoteId() { return quoteId; }
    public UUID createdBy() { return createdBy; }
    public SampleSpecification specification() { return specification; }
    public SampleStatus status() { return status; }
    public int round() { return round; }
    public long version() { return version; }
    public UUID warehouseId() { return warehouseId; }
    public List<SampleReview> reviews() { return List.copyOf(reviews); }
}
