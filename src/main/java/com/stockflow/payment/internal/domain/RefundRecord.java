package com.stockflow.payment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A refund the accountant made outside the system and recorded (4B-9, kltn-docs 15 §4.5 step 16, BR-14; D15): the
 * amount, the day the money left and the outgoing statement line. There is no refund order with a lifecycle any more
 * (D15, decided 2026-11-06): a refund that failed is simply not recorded until it is made again. Never edited; one
 * recorded wrong is {@link #voidRecord voided} with a reason and kept, like a receipt (BR-04).
 *
 * <p>Whether it fits what is still to refund is the service's to check against the order's settlements, under the
 * order's lock - and {@code tg_refund_record_total} checks it again at commit.</p>
 */
public final class RefundRecord {

    /** Recorded, or voided because it was recorded wrong (15 §4.5 step 16). Mapped as a string. */
    public enum Status { RECORDED, VOIDED }

    private final UUID id;
    private final UUID orderId;
    private final UUID customerId;
    private final String orderNumber;
    private final BigDecimal amount;
    private final String currency;
    private final LocalDate refundedOn;
    private final String reference;
    private final String note;
    private Status status;
    private final UUID recordedBy;
    private final Instant recordedAt;
    private UUID voidedBy;
    private Instant voidedAt;
    private String voidReason;

    /** Restores one as stored. */
    public RefundRecord(UUID id, UUID orderId, UUID customerId, String orderNumber, BigDecimal amount, String currency,
                        LocalDate refundedOn, String reference, String note, Status status, UUID recordedBy,
                        Instant recordedAt, UUID voidedBy, Instant voidedAt, String voidReason) {
        this.id = Objects.requireNonNull(id, "id");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.orderNumber = Objects.requireNonNull(orderNumber, "orderNumber");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.refundedOn = Objects.requireNonNull(refundedOn, "refundedOn");
        this.reference = Objects.requireNonNull(reference, "reference");
        this.note = note;
        this.status = Objects.requireNonNull(status, "status");
        this.recordedBy = Objects.requireNonNull(recordedBy, "recordedBy");
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
        this.voidedBy = voidedBy;
        this.voidedAt = voidedAt;
        this.voidReason = voidReason;
    }

    /**
     * A refund just made (15 §4.5 step 16): a positive whole amount, the day it left - never after today - and the
     * statement line, trimmed, at most 100 characters; a note of at most 500.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} for what the record is on its own
     */
    public static RefundRecord record(UUID orderId, UUID customerId, String orderNumber, BigDecimal amount,
                                      String currency, LocalDate refundedOn, String reference, String note,
                                      UUID recordedBy, Instant now, LocalDate today) {
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0) {
            throw invalid("A refund is a positive amount in whole dong");
        }
        if (refundedOn == null || refundedOn.isAfter(today)) {
            throw invalid("The day the refund was made is required and not in the future");
        }
        String trimmed = reference == null ? "" : reference.strip();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw invalid("The bank statement reference of the refund is required, at most 100 characters");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > 500) {
            throw invalid("The note is at most 500 characters");
        }
        return new RefundRecord(Identifiers.newId(), orderId, customerId, orderNumber, amount.setScale(0),
                currency, refundedOn, trimmed, cleanNote, Status.RECORDED, Objects.requireNonNull(recordedBy,
                "recordedBy"), now, null, null, null);
    }

    /**
     * Recorded wrong (15 §4.5 step 16): voided with who, when and why; kept. What it recorded is to refund again.
     *
     * @throws BusinessException {@code REFUND_RECORD_ALREADY_VOIDED}; {@code VALIDATION_FAILED} without a reason
     */
    public void voidRecord(String reason, UUID by, Instant at) {
        if (status == Status.VOIDED) {
            throw new BusinessException(ErrorCode.REFUND_RECORD_ALREADY_VOIDED,
                    "Refund %s of order %s is already voided".formatted(reference, orderNumber));
        }
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.isEmpty() || trimmed.length() > 500) {
            throw invalid("Voiding a refund record needs a reason, at most 500 characters");
        }
        this.status = Status.VOIDED;
        this.voidedBy = Objects.requireNonNull(by, "by");
        this.voidedAt = Objects.requireNonNull(at, "at");
        this.voidReason = trimmed;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    public UUID id() { return id; }
    public UUID orderId() { return orderId; }
    public UUID customerId() { return customerId; }
    public String orderNumber() { return orderNumber; }
    public BigDecimal amount() { return amount; }
    public String currency() { return currency; }
    public LocalDate refundedOn() { return refundedOn; }
    public String reference() { return reference; }
    public String note() { return note; }
    public Status status() { return status; }
    public UUID recordedBy() { return recordedBy; }
    public Instant recordedAt() { return recordedAt; }
    public UUID voidedBy() { return voidedBy; }
    public Instant voidedAt() { return voidedAt; }
    public String voidReason() { return voidReason; }
}
