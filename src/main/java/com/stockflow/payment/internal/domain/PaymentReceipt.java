package com.stockflow.payment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Money the accountant matched to one bank statement line and recorded against installments of one customer (kltn-docs
 * 15 §4.2 step 6, §5.3; BR-02..BR-05): one line per installment paid, the receipt the sum of its lines. Never edited
 * (BR-04): a wrong one is {@link #voidReceipt voided} - kept as it was, with who voided it, when and why - and the
 * right one recorded, under the same statement reference if need be (BR-03 holds for live receipts only).
 *
 * <p>Whether each line fits its installment (not paid, not cancelled, the same customer, no more than it owes) is the
 * installments' to say ({@link Installment#pay}); this class checks what a receipt is on its own.</p>
 */
public final class PaymentReceipt {

    /** Every whitespace character, which a bank never puts inside a statement reference but a person typing one does. */
    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");

    /** The longest statement reference kept (15 §3: ≤ 100). */
    public static final int REFERENCE_MAX = 100;
    /** The longest note and void reason kept (15 §3: ≤ 500). */
    public static final int TEXT_MAX = 500;
    /** More lines than a statement line could ever pay - twenty installments a contract, a few contracts at once. */
    public static final int LINES_MAX = 100;

    private final UUID id;
    private final UUID customerId;
    private final String reference;
    private final LocalDate receivedOn;
    private final BigDecimal amount;
    private final String currency;
    private final String note;
    private PaymentReceiptStatus status;
    private final UUID recordedBy;
    private final Instant recordedAt;
    private UUID voidedBy;
    private Instant voidedAt;
    private String voidReason;
    private final List<Line> lines;
    private final long version;

    /** One installment the receipt pays, and how much of it. */
    public record Line(UUID id, UUID installmentId, BigDecimal amount) {

        public Line {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(installmentId, "installmentId");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /** What the accountant asks one line to pay. */
    public record Payment(UUID installmentId, BigDecimal amount) {
    }

    /** Restores one as stored. */
    public PaymentReceipt(UUID id, UUID customerId, String reference, LocalDate receivedOn, BigDecimal amount,
                          String currency, String note, PaymentReceiptStatus status, UUID recordedBy,
                          Instant recordedAt, UUID voidedBy, Instant voidedAt, String voidReason, List<Line> lines,
                          long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.reference = Objects.requireNonNull(reference, "reference");
        this.receivedOn = Objects.requireNonNull(receivedOn, "receivedOn");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.note = note;
        this.status = Objects.requireNonNull(status, "status");
        this.recordedBy = Objects.requireNonNull(recordedBy, "recordedBy");
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
        this.voidedBy = voidedBy;
        this.voidedAt = voidedAt;
        this.voidReason = voidReason;
        this.lines = List.copyOf(lines);
        this.version = version;
    }

    /**
     * A receipt for {@code payments}, received on {@code receivedOn} - not after {@code today} (15 §3) - under the
     * statement line {@code reference}.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} for a blank or too long reference, note, a day in the future,
     *         no line, a line paying nothing, a fraction of a dong, or the same installment twice
     */
    public static PaymentReceipt record(UUID customerId, String reference, LocalDate receivedOn, String currency,
                                        String note, List<Payment> payments, UUID by, Instant at, LocalDate today) {
        String ref = reference == null ? "" : reference.strip();
        if (ref.isEmpty() || ref.length() > REFERENCE_MAX) {
            throw invalid("The statement reference is 1 to %d characters".formatted(REFERENCE_MAX));
        }
        if (receivedOn == null || receivedOn.isAfter(today)) {
            throw invalid("The money was received on a day not after today");
        }
        String text = note == null || note.isBlank() ? null : note.strip();
        if (text != null && text.length() > TEXT_MAX) {
            throw invalid("A note is at most %d characters".formatted(TEXT_MAX));
        }
        if (payments == null || payments.isEmpty() || payments.size() > LINES_MAX) {
            throw invalid("A receipt pays 1 to %d installments".formatted(LINES_MAX));
        }
        Set<UUID> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        List<Line> lines = new java.util.ArrayList<>();
        for (Payment payment : payments) {
            if (payment == null || payment.installmentId() == null || payment.amount() == null) {
                throw invalid("Each line names an installment and an amount");
            }
            if (!seen.add(payment.installmentId())) {
                throw invalid("Installment %s is paid twice in one receipt".formatted(payment.installmentId()));
            }
            if (payment.amount().signum() <= 0 || payment.amount().stripTrailingZeros().scale() > 0) {
                throw invalid("Each line pays a whole number of dong above zero");
            }
            total = total.add(payment.amount());
            lines.add(new Line(Identifiers.newId(), payment.installmentId(), payment.amount()));
        }
        return new PaymentReceipt(Identifiers.newId(), Objects.requireNonNull(customerId, "customerId"), ref,
                receivedOn, total, currency, text, PaymentReceiptStatus.RECORDED, by, at, null, null, null, lines, 0L);
    }

    /**
     * Voided because it was wrong (15 §4.4 step 12, BR-04): kept as it was, with who, when and why. Its lines no longer
     * pay their installments - the caller takes the money back off each ({@link Installment#unpay}).
     *
     * @throws BusinessException {@code PAYMENT_RECEIPT_ALREADY_VOIDED}; {@code VALIDATION_FAILED} without a reason
     */
    public void voidReceipt(String reason, UUID by, Instant at) {
        if (status == PaymentReceiptStatus.VOIDED) {
            throw new BusinessException(ErrorCode.PAYMENT_RECEIPT_ALREADY_VOIDED,
                    "Receipt %s was voided on %s".formatted(reference, voidedAt));
        }
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > TEXT_MAX) {
            throw invalid("Voiding a receipt says why, in at most %d characters".formatted(TEXT_MAX));
        }
        this.status = PaymentReceiptStatus.VOIDED;
        this.voidedBy = Objects.requireNonNull(by, "by");
        this.voidedAt = Objects.requireNonNull(at, "at");
        this.voidReason = why;
    }

    /**
     * What two statement references are compared by (15 BR-03): upper case, every whitespace character removed. The bank
     * prints one line as {@code FT26111ABC}; an accountant types {@code ft26111abc} or {@code FT 26111 ABC} - one sum of
     * money, which recorded twice would be counted twice. The reference is kept as typed, for the receipt to show; the
     * database generates the same value ({@code payment_receipt.reference_normalized}) and holds it unique among live
     * receipts.
     *
     * @return the normalised reference; empty for null or blank
     */
    public static String normalize(String reference) {
        return reference == null ? "" : WHITESPACE.matcher(reference).replaceAll("").toUpperCase(java.util.Locale.ROOT);
    }

    /** {@link #normalize} of this receipt's reference: what another live receipt may not share. */
    public String normalizedReference() {
        return normalize(reference);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    public UUID id() { return id; }
    public UUID customerId() { return customerId; }
    public String reference() { return reference; }
    public LocalDate receivedOn() { return receivedOn; }
    public BigDecimal amount() { return amount; }
    public String currency() { return currency; }
    public String note() { return note; }
    public PaymentReceiptStatus status() { return status; }
    public UUID recordedBy() { return recordedBy; }
    public Instant recordedAt() { return recordedAt; }
    public UUID voidedBy() { return voidedBy; }
    public Instant voidedAt() { return voidedAt; }
    public String voidReason() { return voidReason; }
    public List<Line> lines() { return lines; }
    public long version() { return version; }
}
