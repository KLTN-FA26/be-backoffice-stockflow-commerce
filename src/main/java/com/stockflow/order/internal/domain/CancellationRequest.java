package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.order.api.CancellationReasonCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer asking to cancel an order that has gone too far to cancel on their own (SCRUM-460):
 * released to production or the warehouse. kltn-docs 17 §2 has Sales handle such requests; the
 * decision may keep part of the money for work already done (17 §4.4, 15 §4.4).
 *
 * <p>One pending request per order at a time ({@code uk_order_cancellation_request_pending} says it
 * again in the table). Approving cancels the order, in the same transaction; rejecting needs a reason
 * the customer can be told.</p>
 */
public final class CancellationRequest extends AggregateRoot {

    private final UUID id;
    private final UUID orderId;
    private final UUID requestedBy;
    private final CancellationReasonCode reasonCode;
    private final String note;
    private final Instant requestedAt;

    private CancellationRequestStatus status;
    private UUID decidedBy;
    private Instant decidedAt;
    private String decisionNote;
    private BigDecimal retainedPercent;
    private final long version;

    public CancellationRequest(UUID id, UUID orderId, UUID requestedBy, CancellationReasonCode reasonCode,
                               String note, Instant requestedAt, CancellationRequestStatus status, UUID decidedBy,
                               Instant decidedAt, String decisionNote, BigDecimal retainedPercent, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.requestedBy = requestedBy;
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        this.note = note == null || note.isBlank() ? null : note.trim();
        this.requestedAt = requestedAt;
        this.status = Objects.requireNonNull(status, "status");
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.decisionNote = decisionNote;
        this.retainedPercent = retainedPercent;
        this.version = version;
        if (reasonCode == CancellationReasonCode.OTHER && this.note == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A cancellation for OTHER must say why in the note");
        }
    }

    public static CancellationRequest open(UUID orderId, UUID requestedBy, CancellationReasonCode reasonCode,
                                           String note, Instant now) {
        return new CancellationRequest(Identifiers.newId(), orderId, requestedBy, reasonCode, note, now,
                CancellationRequestStatus.PENDING, null, null, null, null, 0L);
    }

    /** The order is cancelled by the caller, in the same transaction, with {@code retainedPercent}. */
    public void approve(UUID by, BigDecimal retainedPercent, String note, Instant now) {
        requirePending();
        this.status = CancellationRequestStatus.APPROVED;
        this.decidedBy = Objects.requireNonNull(by, "by");
        this.decidedAt = now;
        this.decisionNote = note == null || note.isBlank() ? null : note.trim();
        this.retainedPercent = retainedPercent;
    }

    public void reject(UUID by, String note, Instant now) {
        requirePending();
        if (note == null || note.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A rejected cancellation must say why");
        }
        this.status = CancellationRequestStatus.REJECTED;
        this.decidedBy = Objects.requireNonNull(by, "by");
        this.decidedAt = now;
        this.decisionNote = note.trim();
    }

    private void requirePending() {
        if (status != CancellationRequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.ORDER_CANCELLATION_REQUEST_NOT_PENDING,
                    "Cancellation request %s is already %s".formatted(id, status));
        }
    }

    public UUID id() { return id; }
    public UUID orderId() { return orderId; }
    public UUID requestedBy() { return requestedBy; }
    public CancellationReasonCode reasonCode() { return reasonCode; }
    public String note() { return note; }
    public Instant requestedAt() { return requestedAt; }
    public CancellationRequestStatus status() { return status; }
    public UUID decidedBy() { return decidedBy; }
    public Instant decidedAt() { return decidedAt; }
    public String decisionNote() { return decisionNote; }
    public BigDecimal retainedPercent() { return retainedPercent; }
    public long version() { return version; }
}
