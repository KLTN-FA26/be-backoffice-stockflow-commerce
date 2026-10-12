package com.stockflow.production.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.SampleApproved;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One round of making a sample of a new design for a customer (SCRUM-394, 03-state-machines §17).
 *
 * <p><b>One row per round.</b> When the customer asks for changes this round ends in
 * {@code CHANGES_REQUESTED} and stays so; the next round is a new request with {@code round + 1}
 * pointing back at it ({@link #nextRound}). The schema states the same thing twice:
 * {@code ck_sample_request_round_chain} and the unique {@code uk_sample_request_next_round}. Keeping the
 * old row intact is what preserves the history the customer reviewed, and is why the SAMPLE production
 * order can be "one per sample request" (BR-PRD-01) without ever being reopened.</p>
 *
 * <p>Only a draft's specification can change. From submission on the design and blank are the ones the
 * workshop prints, the customer holds and, once approved, the QC reference for order production
 * (BR-PRD-05/08) — approval locks them by making the row terminal.</p>
 *
 * <p>Every method checks before it changes anything, so a refused call leaves the aggregate as it was.
 * The CHECK constraints of {@code production.sample_request} mirror each invariant here.</p>
 */
public final class SampleRequest extends AggregateRoot {

    /** {@code customer_feedback VARCHAR(2000)}. */
    public static final int MAX_FEEDBACK = 2000;
    /** {@code cancellation_reason VARCHAR(500)}. */
    public static final int MAX_CANCELLATION_REASON = 500;

    private final UUID id;
    private final String requestNumber;
    private final UUID customerId;
    private final UUID quoteId;
    private final UUID previousRoundId;
    private final int round;
    private SampleSpecification specification;
    private SampleStatus status;
    private Instant sentAt;
    private Instant respondedAt;
    private UUID respondedBy;
    private String customerFeedback;
    private String cancellationReason;

    private SampleRequest(UUID id, String requestNumber, UUID customerId, UUID quoteId, UUID previousRoundId,
                          int round, SampleSpecification specification, SampleStatus status) {
        this.id = Objects.requireNonNull(id, "id");
        if (requestNumber == null || requestNumber.isBlank()) {
            throw new IllegalArgumentException("A sample request needs a number");
        }
        this.requestNumber = requestNumber;
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.quoteId = quoteId;
        if (round < 1 || (round == 1) != (previousRoundId == null)) {
            throw new IllegalArgumentException("Only the first round has no previous round");
        }
        this.previousRoundId = previousRoundId;
        this.round = round;
        this.specification = Objects.requireNonNull(specification, "specification");
        this.status = Objects.requireNonNull(status, "status");
    }

    /** The first round, as Sales drafts it. */
    public static SampleRequest draft(UUID id, String requestNumber, UUID customerId, UUID quoteId,
                                      SampleSpecification specification) {
        return new SampleRequest(id, requestNumber, customerId, quoteId, null, 1, specification, SampleStatus.DRAFT);
    }

    /** Rehydration from the row: no transition is replayed and no event is raised. */
    public static SampleRequest restore(UUID id, String requestNumber, UUID customerId, UUID quoteId,
                                        UUID previousRoundId, int round, SampleSpecification specification,
                                        SampleStatus status, Instant sentAt, Instant respondedAt, UUID respondedBy,
                                        String customerFeedback, String cancellationReason) {
        SampleRequest sample = new SampleRequest(id, requestNumber, customerId, quoteId, previousRoundId, round,
                specification, status);
        sample.sentAt = sentAt;
        sample.respondedAt = respondedAt;
        sample.respondedBy = respondedBy;
        sample.customerFeedback = customerFeedback;
        sample.cancellationReason = cancellationReason;
        return sample;
    }

    /**
     * The round after this one, a new DRAFT request for the same customer and quote. The caller
     * supplies the specification: usually a new design snapshot made from the customer's feedback,
     * otherwise this round's own.
     */
    public SampleRequest nextRound(UUID nextId, String nextNumber, SampleSpecification nextSpecification) {
        if (status != SampleStatus.CHANGES_REQUESTED) {
            throw transition("start the next round of", SampleStatus.DRAFT);
        }
        return new SampleRequest(nextId, nextNumber, customerId, quoteId, id, Math.incrementExact(round),
                nextSpecification, SampleStatus.DRAFT);
    }

    public void edit(SampleSpecification replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (status != SampleStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION,
                    "Sample request %s is %s; only a draft can be edited".formatted(requestNumber, status));
        }
        specification = replacement;
    }

    /** Sent to the workshop; the SAMPLE production order is created in the same transaction. */
    public void submit() {
        move(SampleStatus.SUBMITTED);
    }

    /**
     * The workshop started this round's SAMPLE production order. Reported by the production order's own
     * workflow (SCRUM-396 onwards), which may report it again on a retry: a round already past
     * SUBMITTED is left as it is.
     *
     * @return whether the round moved
     */
    public boolean productionStarted() {
        requireMadeRound(SampleStatus.IN_PRODUCTION);
        if (status != SampleStatus.SUBMITTED) {
            return false;
        }
        move(SampleStatus.IN_PRODUCTION);
        return true;
    }

    /**
     * The SAMPLE production order completed, QC passed (SCRUM-400): the round is READY to be sent. A
     * completion implies a start, so a round still SUBMITTED passes through IN_PRODUCTION; a round
     * already READY or beyond is left as it is.
     *
     * @return whether the round moved
     */
    public boolean productionCompleted() {
        requireMadeRound(SampleStatus.READY);
        if (status == SampleStatus.SUBMITTED) {
            move(SampleStatus.IN_PRODUCTION);
        }
        if (status != SampleStatus.IN_PRODUCTION) {
            return false;
        }
        move(SampleStatus.READY);
        return true;
    }

    /** Only a submitted round has a SAMPLE production order to report on. */
    private void requireMadeRound(SampleStatus target) {
        if (status == SampleStatus.DRAFT || status == SampleStatus.CANCELLED) {
            throw transition("report production of", target);
        }
    }

    /** Handed over or couriered outside the system; the system only records that it happened. */
    public void markSent(Instant now) {
        Objects.requireNonNull(now, "now");
        move(SampleStatus.SENT_TO_CUSTOMER);
        sentAt = now;
    }

    /**
     * The customer approved what they hold. Locks the design on this blank as the QC reference for
     * order production and announces it, so the order module can release lines of this design
     * (BR-PRD-08).
     */
    public void approve(UUID by, Instant now) {
        Objects.requireNonNull(by, "by");
        Objects.requireNonNull(now, "now");
        move(SampleStatus.APPROVED);
        respondedAt = now;
        respondedBy = by;
        registerEvent(new SampleEvent(Identifiers.newId(), now, new SampleApproved(id, customerId,
                specification.designSnapshotId(), specification.blankSku().code())));
    }

    /** The customer asked for changes; what they said is kept on this round for good. */
    public void requestChanges(String feedback, UUID by, Instant now) {
        Objects.requireNonNull(by, "by");
        Objects.requireNonNull(now, "now");
        String text = feedback == null ? "" : feedback.strip();
        if (text.isEmpty() || text.length() > MAX_FEEDBACK) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Asking for changes must say what to change, in at most " + MAX_FEEDBACK + " characters");
        }
        if (!status.canTransitionTo(SampleStatus.CHANGES_REQUESTED)) {
            throw transition("ask for changes on", SampleStatus.CHANGES_REQUESTED);
        }
        status = SampleStatus.CHANGES_REQUESTED;
        respondedAt = now;
        respondedBy = by;
        customerFeedback = text;
    }

    /** Only a draft can be cancelled: once submitted the workshop has a production order for it. */
    public void cancel(String reason) {
        String text = reason == null ? "" : reason.strip();
        if (text.isEmpty() || text.length() > MAX_CANCELLATION_REASON) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A cancellation must say why, in at most " + MAX_CANCELLATION_REASON + " characters");
        }
        move(SampleStatus.CANCELLED);
        cancellationReason = text;
    }

    private void move(SampleStatus target) {
        if (!status.canTransitionTo(target)) {
            throw transition("move", target);
        }
        status = target;
    }

    private BusinessException transition(String verb, SampleStatus target) {
        return new BusinessException(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION,
                "Cannot %s sample request %s: it is %s, not a state that leads to %s"
                        .formatted(verb, requestNumber, status, target));
    }

    public UUID id() { return id; }
    public String requestNumber() { return requestNumber; }
    public UUID customerId() { return customerId; }
    public UUID quoteId() { return quoteId; }
    public UUID previousRoundId() { return previousRoundId; }
    public int round() { return round; }
    public SampleSpecification specification() { return specification; }
    public SampleStatus status() { return status; }
    public Instant sentAt() { return sentAt; }
    public Instant respondedAt() { return respondedAt; }
    public UUID respondedBy() { return respondedBy; }
    public String customerFeedback() { return customerFeedback; }
    public String cancellationReason() { return cancellationReason; }
}
