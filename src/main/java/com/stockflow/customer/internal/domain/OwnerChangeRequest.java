package com.stockflow.customer.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer's request to be served by another salesperson (sales-owner plan §10.5, BR-S15): the customer asks, a Sales
 * Manager decides. {@code PENDING → APPROVED} (a new owner named) or {@code REJECTED} (with a note); {@code WITHDRAWN}
 * exists in the schema for a later step and is never reached here.
 *
 * <p>The rules that span requests - one PENDING per customer, {@link #COOLDOWN} after a decision before the next (D12)
 * - are {@link #requireMayAskAfter}'s, given the customer's latest request; the partial unique index
 * {@code uk_sales_owner_change_request_pending} decides two submissions at once. The decision's shape is
 * {@code ck_sales_owner_change_request_decision} in the database too.</p>
 */
public final class OwnerChangeRequest extends AggregateRoot {

    /** D12: how long after a decision a customer waits before asking again. */
    public static final Duration COOLDOWN = Duration.ofDays(30);

    public enum Status { PENDING, APPROVED, REJECTED, WITHDRAWN }

    public enum Reason { SLOW_RESPONSE, NOT_SATISFIED, PREFER_KNOWN_CONTACT, OTHER }

    private final UUID id;
    private final UUID customerId;
    private final UUID requestedBy;
    private final Instant requestedAt;
    private final UUID currentOwnerId;
    private final Reason reason;
    private final String note;
    private final UUID preferredSalesUserId;
    private Status status;
    private UUID decidedBy;
    private Instant decidedAt;
    private String decisionNote;
    private UUID newSalesOwnerId;
    private final long version;

    public OwnerChangeRequest(UUID id, UUID customerId, UUID requestedBy, Instant requestedAt, UUID currentOwnerId,
                              Reason reason, String note, UUID preferredSalesUserId, Status status, UUID decidedBy,
                              Instant decidedAt, String decisionNote, UUID newSalesOwnerId, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.requestedBy = requestedBy;
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        this.currentOwnerId = currentOwnerId;
        this.reason = Objects.requireNonNull(reason, "reason");
        this.note = note;
        this.preferredSalesUserId = preferredSalesUserId;
        this.status = Objects.requireNonNull(status, "status");
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.decisionNote = decisionNote;
        this.newSalesOwnerId = newSalesOwnerId;
        this.version = version;
    }

    /**
     * A new PENDING request.
     *
     * @param currentOwnerId the customer's owner as it asks (null when it has none)
     * @throws BusinessException {@code VALIDATION_FAILED} without a reason, or naming its current owner as preferred
     */
    public static OwnerChangeRequest ask(UUID customerId, UUID requestedBy, UUID currentOwnerId, Reason reason,
                                         String note, UUID preferredSalesUserId, Instant at) {
        if (reason == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Say why you want another salesperson");
        }
        if (preferredSalesUserId != null && preferredSalesUserId.equals(currentOwnerId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "That salesperson already looks after you");
        }
        return new OwnerChangeRequest(Identifiers.newId(), customerId, requestedBy, at, currentOwnerId, reason,
                blankToNull(note), preferredSalesUserId, Status.PENDING, null, null, null, null, 0L);
    }

    /**
     * Whether the customer may ask now, given its latest request (or null): not while one is PENDING, not within
     * {@link #COOLDOWN} of the last decision (D12).
     *
     * @throws BusinessException {@code OWNER_CHANGE_REQUEST_PENDING}; {@code OWNER_CHANGE_REQUEST_COOLDOWN}
     */
    public static void requireMayAskAfter(OwnerChangeRequest latest, Instant now) {
        if (latest == null) {
            return;
        }
        if (latest.status == Status.PENDING) {
            throw new BusinessException(ErrorCode.OWNER_CHANGE_REQUEST_PENDING);
        }
        if (latest.decidedAt != null && now.isBefore(latest.decidedAt.plus(COOLDOWN))) {
            throw new BusinessException(ErrorCode.OWNER_CHANGE_REQUEST_COOLDOWN);
        }
    }

    /**
     * A manager agrees and names the new owner (the change itself is {@code SalesOwnership}'s, reason CUSTOMER_REQUEST).
     *
     * @throws BusinessException {@code OWNER_CHANGE_REQUEST_ALREADY_DECIDED}; {@code VALIDATION_FAILED} without an owner
     */
    public void approve(UUID newOwner, String note, UUID by, Instant at) {
        requirePending();
        if (newOwner == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Name the new salesperson");
        }
        status = Status.APPROVED;
        newSalesOwnerId = newOwner;
        decisionNote = blankToNull(note);
        decidedBy = Objects.requireNonNull(by, "by");
        decidedAt = at;
    }

    /**
     * A manager refuses, saying why: the customer reads the note.
     *
     * @throws BusinessException {@code OWNER_CHANGE_REQUEST_ALREADY_DECIDED}; {@code VALIDATION_FAILED} without a note
     */
    public void reject(String note, UUID by, Instant at) {
        requirePending();
        String why = blankToNull(note);
        if (why == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Tell the customer why");
        }
        status = Status.REJECTED;
        decisionNote = why;
        decidedBy = Objects.requireNonNull(by, "by");
        decidedAt = at;
    }

    private void requirePending() {
        if (status != Status.PENDING) {
            throw new BusinessException(ErrorCode.OWNER_CHANGE_REQUEST_ALREADY_DECIDED);
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    public UUID id() { return id; }
    public UUID customerId() { return customerId; }
    public UUID requestedBy() { return requestedBy; }
    public Instant requestedAt() { return requestedAt; }
    public UUID currentOwnerId() { return currentOwnerId; }
    public Reason reason() { return reason; }
    public String note() { return note; }
    public UUID preferredSalesUserId() { return preferredSalesUserId; }
    public Status status() { return status; }
    public UUID decidedBy() { return decidedBy; }
    public Instant decidedAt() { return decidedAt; }
    public String decisionNote() { return decisionNote; }
    public UUID newSalesOwnerId() { return newSalesOwnerId; }
    public long version() { return version; }
}
