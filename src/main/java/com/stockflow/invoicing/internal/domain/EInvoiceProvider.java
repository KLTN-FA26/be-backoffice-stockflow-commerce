package com.stockflow.invoicing.internal.domain;

import java.math.BigDecimal;
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
 * <p>A provider must treat a second {@code submit} of a transaction id it already holds as the first one again, never
 * as a second invoice. Every implementation is held to that; it is what lets the caller retry.</p>
 *
 * <p>Implementations: {@code SandboxProvider} (the default; no legal value). A real one (Viettel SInvoice, package E3)
 * is added beside it and chosen by configuration - nothing about an invoice's rules names a provider.</p>
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
     * An invoice as it is handed to a provider: everything it says, and nothing about how the system stores it.
     *
     * @param referenceCode the system's own invoice code, for people matching the two systems
     */
    record InvoiceDocument(String transactionId, String referenceCode, String templateCode, String symbol,
                           LocalDate issueDate, InvoiceParty seller, InvoiceParty buyer, String currency,
                           String paymentMethod, String orderNumber, String contractNumber, List<EInvoiceLine> lines,
                           List<TaxRateTotal> totalsByRate, BigDecimal subtotal, BigDecimal taxTotal,
                           BigDecimal grandTotal, String amountInWords) {
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
