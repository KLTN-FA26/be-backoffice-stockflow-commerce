package com.stockflow.invoicing.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A notice that an issued invoice was drawn up wrongly - Mẫu 04/SS-HĐĐT, "Thông báo hóa đơn điện tử đã lập sai"
 * (WF-05 E4; TT 91/2026/TT-BTC Điều 10 khoản 1 điểm a, as read in plans/E0-legal.md row 1).
 *
 * <p>When the buyer's <b>name or address</b> is wrong and the <b>tax code is right</b>, the law asks for no new
 * invoice: the buyer is told, and the tax authority is notified on this form. So the notice changes nothing on the
 * invoice - it is the record, kept beside it, of what should have been written and of the tax authority's answer
 * (Mẫu 01/TB-SSĐT, khoản 4). No agreement of the parties is asked for.</p>
 *
 * <pre>
 * DRAFT ──sent──▶ SENT ──answer──▶ ACCEPTED | REJECTED
 *   └────────────answer at once────────────▶
 * </pre>
 *
 * <p>A notice stays DRAFT while the provider could not be reached, or has no call for notices, and is sent again; one
 * that was answered is final. One notice of an invoice is open at a time. The same rules are the constraints and
 * triggers of {@code invoicing.einvoice_error_notice}.</p>
 */
public final class ErrorNotice {

    /**
     * The form the notice is made on, as this system identifies it: in ASCII, which is what is stored
     * ({@code ck_einvoice_error_notice_form}), what the API answers as {@code formCode} and what a provider is handed
     * (owner's decision 13/11/2026 on E0 L12). TT 91 writes the form's name with the Vietnamese letter Đ; that is a
     * name to read, {@link #FORM_NAME}, not an identifier to compare. A provider whose notice call wants another
     * spelling translates this code in its own adapter - the outgoing string is the adapter's to decide.
     */
    public static final String FORM_CODE = "04/SS-HDDT";

    /**
     * The form's name as TT 91/2026/TT-BTC prints it, for what people read: a screen ({@code formName} in the API), a
     * message to the buyer, a printed document. Never stored and never compared.
     */
    public static final String FORM_NAME = "04/SS-HĐĐT";

    /** What the notice is about. */
    public enum Subject {
        /** The buyer's name or address is wrong, the tax code right (khoản 1 điểm a): the notice says the right ones. */
        BUYER_NAME_ADDRESS,
        /**
         * A notice sent beside an adjustment or a replacement. Điểm b does not ask for one, and whether a provider
         * sends it by itself is not verified (E0 row 2): recorded only where the deployment turns it on.
         */
        WITH_CORRECTION
    }

    public enum Status { DRAFT, SENT, ACCEPTED, REJECTED }

    /** A notice as it is stored. */
    public record Snapshot(UUID id, UUID einvoiceId, String formCode, Subject subject, String reason,
                           String correctBuyerName, String correctBuyerAddress, Status status, Instant sentAt,
                           String responseCode, String responseMessage, Instant respondedAt, UUID recordedBy,
                           Instant createdAt) {
    }

    private final UUID id;
    private final UUID einvoiceId;
    private final Subject subject;
    private final String reason;
    private final String correctBuyerName;
    private final String correctBuyerAddress;
    private Status status;
    private Instant sentAt;
    private String responseCode;
    private String responseMessage;
    private Instant respondedAt;
    private final UUID recordedBy;
    private final Instant createdAt;

    private ErrorNotice(Snapshot s) {
        this.id = Objects.requireNonNull(s.id(), "id");
        this.einvoiceId = Objects.requireNonNull(s.einvoiceId(), "einvoiceId");
        this.subject = Objects.requireNonNull(s.subject(), "subject");
        this.reason = Objects.requireNonNull(s.reason(), "reason");
        this.correctBuyerName = s.correctBuyerName();
        this.correctBuyerAddress = s.correctBuyerAddress();
        this.status = Objects.requireNonNull(s.status(), "status");
        this.sentAt = s.sentAt();
        this.responseCode = s.responseCode();
        this.responseMessage = s.responseMessage();
        this.respondedAt = s.respondedAt();
        this.recordedBy = s.recordedBy();
        this.createdAt = s.createdAt();
    }

    /**
     * Records a notice of {@code invoice}, not yet sent.
     *
     * @param correctBuyerName    the buyer's name as it should have been; null when the name was right
     * @param correctBuyerAddress the buyer's address as it should have been; null when the address was right
     * @param withCorrectionOn    whether this deployment records notices beside a correction
     * @param hasCorrection       whether an adjustment or replacement of the invoice was issued
     * @param openNotice          whether another notice of the invoice is still on its way
     * @throws BusinessException {@code EINVOICE_NOT_IN_FORCE} unless the invoice is issued and in force (a notice
     *         beside a correction is also for one since replaced); {@code EINVOICE_ERROR_NOTICE_NOT_SENDABLE} while
     *         another is open; {@code EINVOICE_CORRECTION_NOT_APPLICABLE} for a notice beside a correction where that
     *         is off or the invoice has none; {@code VALIDATION_FAILED} without a reason, or - for a wrong name or
     *         address - without a name or address that differs from the invoice's
     */
    public static ErrorNotice record(UUID id, EInvoice.Snapshot invoice, Subject subject, String reason,
                                     String correctBuyerName, String correctBuyerAddress, UUID recordedBy,
                                     Instant now, boolean withCorrectionOn, boolean hasCorrection,
                                     boolean openNotice) {
        Objects.requireNonNull(subject, "subject");
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "reason: required, at most 500 characters");
        }
        String name = strip(correctBuyerName);
        String address = strip(correctBuyerAddress);
        if (subject == Subject.BUYER_NAME_ADDRESS) {
            if (invoice.status() != EInvoiceStatus.ISSUED) {
                throw new BusinessException(ErrorCode.EINVOICE_NOT_IN_FORCE,
                        "Invoice %s is %s: only an issued invoice in force is notified as drawn up wrongly"
                                .formatted(invoice.code(), invoice.status()));
            }
            boolean nameDiffers = name != null && !name.equals(invoice.buyer().name());
            boolean addressDiffers = address != null && !address.equals(invoice.buyer().address());
            if ((name != null && name.length() > 200) || (address != null && address.length() > 500)
                    || !(nameDiffers || addressDiffers)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "correctBuyerName, correctBuyerAddress: the "
                        + "buyer's name or address as it should have been, different from the invoice's. A wrong tax "
                        + "code is not notified: it is adjusted or replaced");
            }
        } else if (!withCorrectionOn || !hasCorrection || !invoice.status().wasIssued() || name != null
                || address != null) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE, ("Invoice %s: a notice beside "
                    + "an adjustment or replacement is recorded only where it is turned on, for an invoice that has "
                    + "one").formatted(invoice.code()));
        }
        if (openNotice) {
            throw new BusinessException(ErrorCode.EINVOICE_ERROR_NOTICE_NOT_SENDABLE,
                    "Invoice %s already has a notice waiting for its answer".formatted(invoice.code()));
        }
        return new ErrorNotice(new Snapshot(id, invoice.id(), FORM_CODE, subject, reason.strip(), name, address,
                Status.DRAFT, null, null, null, null, recordedBy, Objects.requireNonNull(now, "now")));
    }

    public static ErrorNotice restore(Snapshot snapshot) {
        return new ErrorNotice(snapshot);
    }

    /** @throws BusinessException {@code EINVOICE_ERROR_NOTICE_NOT_SENDABLE} once it was answered */
    public void requireOpen() {
        if (status == Status.ACCEPTED || status == Status.REJECTED) {
            throw new BusinessException(ErrorCode.EINVOICE_ERROR_NOTICE_NOT_SENDABLE,
                    "The notice is %s and final".formatted(status));
        }
    }

    /**
     * What the provider said when the notice was sent. PENDING: it is on its way (SENT). ACCEPTED or REJECTED: the tax
     * authority's answer, final. NOT_SUPPORTED: nothing was sent - the notice stays as it is, to be filed by hand.
     */
    public void answered(EInvoiceProvider.NoticeReceipt receipt, Instant now) {
        requireOpen();
        switch (receipt.outcome()) {
            case NOT_SUPPORTED -> { /* the provider sends no notices: still a draft */ }
            case PENDING -> {
                this.status = Status.SENT;
                this.sentAt = sentAt == null ? now : sentAt;
            }
            case ACCEPTED, REJECTED -> {
                this.status = receipt.outcome() == EInvoiceProvider.NoticeOutcome.ACCEPTED ? Status.ACCEPTED
                        : Status.REJECTED;
                this.sentAt = sentAt == null ? now : sentAt;
                this.respondedAt = now;
                this.responseCode = limit(receipt.code(), 64);
                this.responseMessage = limit(receipt.message(), 500);
            }
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(id, einvoiceId, FORM_CODE, subject, reason, correctBuyerName, correctBuyerAddress, status,
                sentAt, responseCode, responseMessage, respondedAt, recordedBy, createdAt);
    }

    public UUID id() { return id; }
    public UUID einvoiceId() { return einvoiceId; }
    public Status status() { return status; }

    private static String strip(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String limit(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(max, value.length()));
    }
}
