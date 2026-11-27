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

    /**
     * The customer's account the refund was paid to, as it stood when the refund was recorded (audit round 3, RAD-6):
     * the refund confirmation prints it. Read from the customer's profile at that moment and never again - a profile
     * changed later (after a bounced transfer, say) is the account of later refunds, not of this one.
     * {@code ck_refund_record_payee_account} says the same: a number and a bank, or nothing.
     *
     * @param accountHolder the name on the account; null when the profile names none
     */
    public record PayeeAccount(String accountNumber, String bankName, String accountHolder) {

        public PayeeAccount {
            if (accountNumber == null || accountNumber.isBlank() || bankName == null || bankName.isBlank()) {
                throw new IllegalArgumentException("A payee account names its number and its bank");
            }
            accountNumber = accountNumber.strip();
            bankName = bankName.strip();
            accountHolder = accountHolder == null || accountHolder.isBlank() ? null : accountHolder.strip();
        }

        /** The account a profile names, when it names a number and a bank; null otherwise. */
        public static PayeeAccount of(String accountNumber, String bankName, String accountHolder) {
            return accountNumber == null || accountNumber.isBlank() || bankName == null || bankName.isBlank() ? null
                    : new PayeeAccount(accountNumber, bankName, accountHolder);
        }
    }

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
    private final PayeeAccount payeeAccount;

    /** Restores one as stored, kept before the payee's account was recorded (null). */
    public RefundRecord(UUID id, UUID orderId, UUID customerId, String orderNumber, BigDecimal amount, String currency,
                        LocalDate refundedOn, String reference, String note, Status status, UUID recordedBy,
                        Instant recordedAt, UUID voidedBy, Instant voidedAt, String voidReason) {
        this(id, orderId, customerId, orderNumber, amount, currency, refundedOn, reference, note, status, recordedBy,
                recordedAt, voidedBy, voidedAt, voidReason, null);
    }

    /** Restores one as stored; {@code payeeAccount} null for a record kept before it was recorded. */
    public RefundRecord(UUID id, UUID orderId, UUID customerId, String orderNumber, BigDecimal amount, String currency,
                        LocalDate refundedOn, String reference, String note, Status status, UUID recordedBy,
                        Instant recordedAt, UUID voidedBy, Instant voidedAt, String voidReason,
                        PayeeAccount payeeAccount) {
        this.payeeAccount = payeeAccount;
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
        return record(orderId, customerId, orderNumber, amount, currency, refundedOn, reference, note, recordedBy, now,
                today, null);
    }

    /**
     * As above, with the customer's account the money went to as it stands now ({@code payee}; null when the profile
     * names none): what the refund confirmation prints for good (audit round 3, RAD-6).
     */
    public static RefundRecord record(UUID orderId, UUID customerId, String orderNumber, BigDecimal amount,
                                      String currency, LocalDate refundedOn, String reference, String note,
                                      UUID recordedBy, Instant now, LocalDate today, PayeeAccount payee) {
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0) {
            throw invalid("A refund is a positive amount in whole dong", "refusal.refund.amount");
        }
        if (refundedOn == null || refundedOn.isAfter(today)) {
            throw invalid("The day the refund was made is required and not in the future",
                    "refusal.refund.refundedOn");
        }
        String trimmed = reference == null ? "" : reference.strip();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw invalid("The bank statement reference of the refund is required, at most 100 characters",
                    "refusal.refund.reference", 100);
        }
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > 500) {
            throw invalid("The note is at most 500 characters", "refusal.refund.note", 500);
        }
        return new RefundRecord(Identifiers.newId(), orderId, customerId, orderNumber, amount.setScale(0),
                currency, refundedOn, trimmed, cleanNote, Status.RECORDED, Objects.requireNonNull(recordedBy,
                "recordedBy"), now, null, null, null, payee);
    }

    /**
     * Recorded wrong (15 §4.5 step 16): voided with who, when and why; kept. What it recorded is to refund again.
     *
     * @throws BusinessException {@code REFUND_RECORD_ALREADY_VOIDED}; {@code VALIDATION_FAILED} without a reason
     */
    public void voidRecord(String reason, UUID by, Instant at) {
        if (status == Status.VOIDED) {
            throw new BusinessException(ErrorCode.REFUND_RECORD_ALREADY_VOIDED,
                    "Refund %s of order %s is already voided".formatted(reference, orderNumber))
                    .localized("refusal.refund.alreadyVoided", reference, orderNumber);
        }
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.isEmpty() || trimmed.length() > 500) {
            throw invalid("Voiding a refund record needs a reason, at most 500 characters",
                    "refusal.refund.voidReason", 500);
        }
        this.status = Status.VOIDED;
        this.voidedBy = Objects.requireNonNull(by, "by");
        this.voidedAt = Objects.requireNonNull(at, "at");
        this.voidReason = trimmed;
    }

    /** A refusal the accountant reads: {@code message} for the log, {@code key} in their language. */
    private static BusinessException invalid(String message, String key, Object... arguments) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message).localized(key, arguments);
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
    /** The account the refund went to, as recorded with it; null for a record kept before it was recorded. */
    public PayeeAccount payeeAccount() { return payeeAccount; }
}
