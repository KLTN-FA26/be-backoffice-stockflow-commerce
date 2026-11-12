package com.stockflow.invoicing.internal.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Port: an e-invoice provider - the company outside the system that signs an invoice, sends it to the tax authority
 * for its code, numbers it and hands back the XML and the PDF (plan §1.3; kltn-docs 21 §2).
 *
 * <p>Three calls, all about one <b>transaction id</b> the system chooses and the provider remembers:</p>
 * <ul>
 *   <li>{@link #submit} hands the invoice over. The provider works on it afterwards - an answer of {@code ACCEPTED} is
 *       not an issued invoice.</li>
 *   <li>{@link #query} asks what became of a transaction. It is how every result is learnt, and how a submission whose
 *       reply was lost is recovered: ask first, and only a transaction the provider has never heard of is sent again.</li>
 *   <li>{@link #download} fetches the XML and the PDF of an issued transaction.</li>
 * </ul>
 *
 * <p>An adjustment and a replacement go the same way as an original: {@link InvoiceDocument#correction} names the
 * invoice they change, and the provider refuses one that names an invoice it never issued or one already replaced.
 * {@link #notifyError} is the fourth call, for the notice that changes no invoice (Mẫu 04/SS-HĐĐT).</p>
 *
 * <p>A provider must treat a second {@code submit} of a transaction id it already holds as the first one again, never
 * as a second invoice. Every implementation is held to that; it is what lets the caller retry.</p>
 *
 * <p>Implementations: {@code SandboxProvider} (the default; no legal value) and {@code ViettelSInvoiceProvider}
 * (package E3), chosen by configuration - nothing about an invoice's rules names a provider.</p>
 */
public interface EInvoiceProvider {

    /** The provider's code as {@code invoicing.einvoice_provider_config} names it. */
    String code();

    /**
     * Hands an invoice over under its transaction id.
     *
     * @throws ProviderUnavailableException when the provider did not answer - it may or may not have the invoice;
     *                                      {@link #query} tells
     */
    Submission submit(InvoiceDocument invoice);

    /**
     * What became of a transaction.
     *
     * @throws ProviderUnavailableException when the provider did not answer
     */
    Result query(String transactionId);

    /**
     * The XML and the PDF of an issued transaction.
     *
     * @throws ProviderUnavailableException when the provider did not answer, or the transaction is not issued
     */
    Documents download(String transactionId);

    /**
     * When a transaction the provider says it has never received may be submitted <b>again</b>. The first submission of
     * a transaction is not bound by it. A provider that takes a submission in atomically has nothing to say here.
     */
    default Resubmission resubmission() {
        return Resubmission.FREELY;
    }

    /**
     * Whether this provider issues an adjustment or a replacement as one ({@link InvoiceDocument#correction}). An
     * implementation that writes every document as an original must say no: a correction it took in would be issued as
     * a second original invoice for the same goods - an invoice in law, which no correction of ours undoes. Corrections
     * are refused before they are sent to such a provider ({@code EINVOICE_PROVIDER_CANNOT_CORRECT}).
     */
    default boolean issuesCorrections() {
        return false;
    }

    /**
     * A real provider's promise "the same transaction id is never a second invoice" has edges, and this states them so
     * the caller stays inside.
     *
     * @param quietPeriod how long after a submission the provider may still be working on it without a look-up finding
     *                    it. Submitting again inside it races the first submission, and the provider may then issue
     *                    two invoices
     * @param window      how long the provider remembers a transaction id as already used; past it the same id is a new
     *                    invoice, so nothing is submitted again automatically. Null: for ever
     */
    record Resubmission(Duration quietPeriod, Duration window) {

        public static final Resubmission FREELY = new Resubmission(Duration.ZERO, null);

        /** Whether a transaction first submitted at {@code submittedAt} and unknown to the provider may be sent again. */
        public boolean allowedAt(Instant submittedAt, Instant now) {
            if (submittedAt == null) {
                return true;
            }
            Duration age = Duration.between(submittedAt, now);
            return age.compareTo(quietPeriod) >= 0 && (window == null || age.compareTo(window) < 0);
        }

        /** Whether the provider no longer remembers the id of a transaction first submitted at {@code submittedAt}. */
        public boolean forgottenAt(Instant submittedAt, Instant now) {
            return window != null && submittedAt != null
                    && Duration.between(submittedAt, now).compareTo(window) >= 0;
        }
    }

    /**
     * Sends the notice "invoice drawn up wrongly" (Mẫu 04/SS-HĐĐT, TT 91/2026/TT-BTC Điều 10 khoản 1 điểm a) to the tax
     * authority through the provider, and returns its answer (Mẫu 01/TB-SSĐT, khoản 4) if it is already there. The
     * notice's reference is the system's own and the provider treats a second sending of it as the first again.
     *
     * <p>A provider that has no such call answers {@link NoticeOutcome#NOT_SUPPORTED}: the notice stays recorded and
     * is filed with the tax authority by hand. That is the default, so an implementation written before notices
     * existed needs no change.</p>
     *
     * @throws ProviderUnavailableException when the provider did not answer
     */
    default NoticeReceipt notifyError(ErrorNoticeDocument notice) {
        return new NoticeReceipt(NoticeOutcome.NOT_SUPPORTED, null, null);
    }

    /** How a notice was received. */
    enum NoticeOutcome {
        /** Taken in; the tax authority's answer is not there yet. */
        PENDING,
        /** The tax authority received and accepted it. */
        ACCEPTED,
        /** Refused by the provider or the tax authority. */
        REJECTED,
        /** This provider sends no notices. */
        NOT_SUPPORTED
    }

    /** @param code and {@code message}: the answer's reference, or why it was refused */
    record NoticeReceipt(NoticeOutcome outcome, String code, String message) {
    }

    /**
     * A 04/SS-HĐĐT notice as it is handed to a provider - the columns of the form: the invoice's tax authority code,
     * template, symbol, number and date, and the reason. {@code correctBuyerName} / {@code correctBuyerAddress}: what
     * the invoice should have said, where the notice is about the buyer's name or address.
     */
    record ErrorNoticeDocument(String reference, String formCode, String taxAuthorityCode, String templateCode,
                               String symbol, int invoiceNumber, LocalDate issueDate, InvoiceParty seller,
                               String buyerTaxCode, String reason, String correctBuyerName,
                               String correctBuyerAddress) {
    }

    /** How a submission was received. */
    enum SubmissionOutcome {
        /** Taken in; the result comes later through {@link #query}. */
        ACCEPTED,
        /** The provider already held this transaction: nothing new was created. Its result comes through {@link #query}. */
        DUPLICATE,
        /** Refused outright (a wrong tax code ...): no invoice exists for the transaction. */
        REJECTED
    }

    /** @param errorCode and {@code message}: why it was refused; null unless REJECTED */
    record Submission(SubmissionOutcome outcome, String errorCode, String message) {
    }

    /** Where a transaction stands at the provider. */
    enum ResultState {
        /** The provider has never received it. */
        NOT_FOUND,
        /** Received; signing and the tax authority's answer are not done. */
        PENDING,
        ISSUED,
        /** Refused by the provider or the tax authority: no invoice exists for the transaction. */
        REJECTED
    }

    /**
     * @param invoiceNumber    1 to 99 999 999, the provider's, within the symbol; null unless ISSUED
     * @param taxAuthorityCode the tax authority's code ("mã của cơ quan thuế"); null unless ISSUED
     * @param lookupCode       what the buyer looks the invoice up by on the provider's portal; null unless ISSUED
     * @param signedAt         when the seller's signature was put on it; null unless ISSUED
     * @param errorCode        and {@code message}: why it was refused; null unless REJECTED
     */
    record Result(ResultState state, Integer invoiceNumber, String taxAuthorityCode, String lookupCode,
                  Instant signedAt, String errorCode, String message) {

        public static Result of(ResultState state) {
            return new Result(state, null, null, null, null, null, null);
        }
    }

    /** The invoice as issued: the XML, which is the invoice in law, and the PDF people read. */
    record Documents(byte[] xml, byte[] pdf) {
    }

    /**
     * The invoice an adjustment or a replacement changes, as the new invoice must name it (TT 91 Điều 10 khoản 1 điểm
     * b.1, b.2: "Điều chỉnh / Thay thế cho hóa đơn Mẫu số... ký hiệu... số... ngày... tháng... năm").
     *
     * @param type   ADJUSTMENT or REPLACEMENT
     * @param reason what was wrong, or what the adjustment is for
     */
    record Correction(EInvoiceType type, String templateCode, String symbol, int invoiceNumber, LocalDate issueDate,
                      String reason) {
    }

    /**
     * An invoice as it is handed to a provider: everything it says, and nothing about how the system stores it.
     *
     * @param referenceCode the system's own invoice code, for people matching the two systems
     * @param correction    the invoice this one adjusts or replaces; null on an original
     */
    record InvoiceDocument(String transactionId, String referenceCode, String templateCode, String symbol,
                           LocalDate issueDate, InvoiceParty seller, InvoiceParty buyer, String currency,
                           String paymentMethod, String orderNumber, String contractNumber, List<EInvoiceLine> lines,
                           List<TaxRateTotal> totalsByRate, BigDecimal subtotal, BigDecimal taxTotal,
                           BigDecimal grandTotal, String amountInWords, Correction correction) {

        /** An original invoice. */
        public InvoiceDocument(String transactionId, String referenceCode, String templateCode, String symbol,
                               LocalDate issueDate, InvoiceParty seller, InvoiceParty buyer, String currency,
                               String paymentMethod, String orderNumber, String contractNumber,
                               List<EInvoiceLine> lines, List<TaxRateTotal> totalsByRate, BigDecimal subtotal,
                               BigDecimal taxTotal, BigDecimal grandTotal, String amountInWords) {
            this(transactionId, referenceCode, templateCode, symbol, issueDate, seller, buyer, currency, paymentMethod,
                    orderNumber, contractNumber, lines, totalsByRate, subtotal, taxTotal, grandTotal, amountInWords,
                    null);
        }
    }

    /** The provider could not be reached or did not answer in time. Nothing is known about the transaction. */
    final class ProviderUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ProviderUnavailableException(String message) {
            super(message);
        }

        public ProviderUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
