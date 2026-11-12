package com.stockflow.invoicing.internal.service;

import com.stockflow.common.storage.Checksums;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.repository.SandboxLedger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The sandbox e-invoice provider (plan §1.3): the default at capstone scale. It behaves as a real provider does, so
 * everything around it - issuing, waiting, reconciling, storing the files - is exercised for real; what it issues has
 * <b>no legal value</b>, and says so on every document. Switching to a real provider is a row of configuration, not a
 * change to an invoice's rules.
 *
 * <h2>What it does as a provider</h2>
 * <ul>
 *   <li><b>Numbers by symbol</b>: from 1, one after the other, at most 8 digits, under the template and symbol it is
 *       given; a symbol names its year, so the next year's starts at 1 again (NĐ 254 Phụ lục điểm 2).</li>
 *   <li><b>Gives a tax authority code and a look-up code</b>, both marked {@code SBX} so they are never taken for real
 *       ones.</li>
 *   <li><b>Answers later</b>: a submission is accepted at once and its result exists only after the processing delay
 *       ({@code stockflow.invoicing.sandbox.processing-delay}); until then a query says PENDING.</li>
 *   <li><b>Knows one invoice per transaction id</b>: the same invoice submitted again under an id it holds is answered
 *       DUPLICATE and creates nothing; a different invoice under that id is refused.</li>
 *   <li><b>Returns the XML and the PDF</b> of an issued transaction ({@link TaxAuthorityInvoiceXml},
 *       {@link SandboxInvoicePdf}).</li>
 * </ul>
 *
 * <h2>How to rehearse a failure</h2>
 *
 * <p>By what the invoice says, so a test or a demo needs no switch:</p>
 * <ul>
 *   <li>a buyer tax code listed in {@code stockflow.invoicing.sandbox.rejected-tax-codes} (default
 *       {@code 0000000001}) - refused at once, {@value #INVALID_BUYER_TAX_CODE}: the "wrong tax code" of a real
 *       provider. Nothing is kept for the transaction;</li>
 *   <li>a buyer e-mail whose name ends {@value #REPLY_LOST} ({@code ke.toan+sandbox-reply-lost@...}) - the first
 *       submission is <i>taken in</i> and then the reply is lost (a timeout): the dangerous case, where sending again
 *       blindly would issue twice;</li>
 *   <li>one ending {@value #UNREACHABLE} - the first submission never arrives (a timeout, nothing taken in);</li>
 *   <li>one ending {@value #AUTHORITY_REJECTS} - accepted, then refused by the simulated tax authority when its
 *       processing is done, {@value #TAX_AUTHORITY_REJECTED}.</li>
 * </ul>
 *
 * <p>Its books are in the database ({@link SandboxLedger}) and each call commits on its own, whatever transaction the
 * caller is in: a provider is another system, and what it took in stays taken in when the caller rolls back.</p>
 */
@Component
class SandboxProvider implements EInvoiceProvider {

    static final String CODE = "SANDBOX";
    static final String INVALID_BUYER_TAX_CODE = "INVALID_BUYER_TAX_CODE";
    static final String INVALID_SERIES = "INVALID_SERIES";
    static final String DUPLICATE_TRANSACTION = "DUPLICATE_TRANSACTION";
    static final String NUMBERS_EXHAUSTED = "NUMBERS_EXHAUSTED";
    static final String TAX_AUTHORITY_REJECTED = "TAX_AUTHORITY_REJECTED";
    static final String REPLY_LOST = "+sandbox-reply-lost";
    static final String UNREACHABLE = "+sandbox-unreachable";
    static final String AUTHORITY_REJECTS = "+sandbox-authority-rejects";
    /** What every document of the sandbox says first, in the XML; the PDF says it in Vietnamese. */
    static final String NOTICE = "SANDBOX E-INVOICE - TEST DOCUMENT - NO LEGAL VALUE - not signed, not sent to the tax "
            + "authority";

    private static final Pattern SYMBOL = Pattern.compile("[CK]\\d{2}[TDLMNBGHXF][A-Z]{2}");

    private final SandboxLedger ledger;
    private final SandboxInvoicePdf pdf;
    private final InvoicingProperties properties;
    private final Clock clock;
    private final TransactionTemplate ownTransaction;
    /** Transactions whose rehearsed timeout already happened: a timeout is rehearsed once, then the provider answers. */
    private final Set<String> timedOut = ConcurrentHashMap.newKeySet();

    SandboxProvider(SandboxLedger ledger, SandboxInvoicePdf pdf, InvoicingProperties properties, Clock clock,
                    PlatformTransactionManager transactions) {
        this.ledger = ledger;
        this.pdf = pdf;
        this.properties = properties;
        this.clock = clock;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Submission submit(InvoiceDocument invoice) {
        String mailbox = mailbox(invoice);
        // Remembered only for a transaction that rehearses a timeout: nothing is kept for the others.
        boolean firstTime = (mailbox.endsWith(UNREACHABLE) || mailbox.endsWith(REPLY_LOST))
                && timedOut.add(invoice.transactionId());
        if (firstTime && mailbox.endsWith(UNREACHABLE)) {
            throw new ProviderUnavailableException("Sandbox: the provider could not be reached (rehearsed)");
        }
        Submission submission = ownTransaction.execute(status -> takeIn(invoice, mailbox));
        if (firstTime && mailbox.endsWith(REPLY_LOST)) {
            // Taken in and committed above; only the answer never reaches the caller.
            throw new ProviderUnavailableException("Sandbox: the provider's reply was lost (rehearsed)");
        }
        return submission;
    }

    private Submission takeIn(InvoiceDocument invoice, String mailbox) {
        String hash = Checksums.sha256Hex(invoice.toString().getBytes(StandardCharsets.UTF_8));
        Optional<SandboxLedger.Issuance> held = ledger.find(invoice.transactionId());
        if (held.isPresent()) {
            return held.get().requestHash().equals(hash) ? new Submission(SubmissionOutcome.DUPLICATE, null, null)
                    : new Submission(SubmissionOutcome.REJECTED, DUPLICATE_TRANSACTION,
                            "Another invoice was already submitted under this transaction id");
        }
        if (invoice.buyer().taxCode() == null
                || properties.sandbox().rejectedTaxCodes().contains(invoice.buyer().taxCode())) {
            return new Submission(SubmissionOutcome.REJECTED, INVALID_BUYER_TAX_CODE,
                    "The buyer's tax code is not a registered tax code");
        }
        if (invoice.symbol() == null || !SYMBOL.matcher(invoice.symbol()).matches()
                || !invoice.symbol().substring(1, 3).equals("%02d".formatted(invoice.issueDate().getYear() % 100))) {
            return new Submission(SubmissionOutcome.REJECTED, INVALID_SERIES,
                    "The symbol is not one of the invoice's year");
        }
        Instant now = clock.instant();
        Instant ready = now.plus(properties.sandbox().processingDelay());
        if (mailbox.endsWith(AUTHORITY_REJECTS)) {
            ledger.insert(new SandboxLedger.Issuance(invoice.transactionId(), false, invoice.templateCode(),
                    invoice.symbol(), null, null, null, TAX_AUTHORITY_REJECTED,
                    "The tax authority did not give the invoice a code (rehearsed)", null, null, hash, now, ready));
            return new Submission(SubmissionOutcome.ACCEPTED, null, null);
        }
        Optional<Integer> number = ledger.nextNumber(invoice.templateCode(), invoice.symbol());
        if (number.isEmpty()) {
            return new Submission(SubmissionOutcome.REJECTED, NUMBERS_EXHAUSTED,
                    "Every number of this symbol is used");
        }
        String digest = Checksums.sha256Hex(invoice.transactionId().getBytes(StandardCharsets.UTF_8))
                .toUpperCase(Locale.ROOT);
        var issued = new TaxAuthorityInvoiceXml.Issued(number.get(), "SBX" + digest.substring(0, 31),
                "SBX" + digest.substring(31, 41), ready);
        boolean taken = ledger.insert(new SandboxLedger.Issuance(invoice.transactionId(), true,
                invoice.templateCode(), invoice.symbol(), issued.invoiceNumber(), issued.taxAuthorityCode(),
                issued.lookupCode(), null, null, TaxAuthorityInvoiceXml.write(invoice, issued, NOTICE),
                pdf.render(invoice, issued), hash, now, ready));
        if (!taken) {
            // The same transaction arrived twice at once and the other one won: this one creates nothing. Rolling back
            // gives its number back, so the symbol's numbers stay gapless.
            throw new ConcurrentSubmission();
        }
        return new Submission(SubmissionOutcome.ACCEPTED, null, null);
    }

    @Override
    public Result query(String transactionId) {
        Optional<SandboxLedger.Issuance> held = ownTransaction.execute(status -> ledger.find(transactionId));
        if (held == null || held.isEmpty()) {
            return Result.of(ResultState.NOT_FOUND);
        }
        SandboxLedger.Issuance issuance = held.get();
        if (clock.instant().isBefore(issuance.readyAt())) {
            return Result.of(ResultState.PENDING);
        }
        return issuance.issued()
                ? new Result(ResultState.ISSUED, issuance.invoiceNumber(), issuance.taxAuthorityCode(),
                        issuance.lookupCode(), issuance.readyAt(), null, null)
                : new Result(ResultState.REJECTED, null, null, null, null, issuance.rejectionCode(),
                        issuance.rejectionMessage());
    }

    @Override
    public Documents download(String transactionId) {
        Optional<SandboxLedger.Issuance> held = ownTransaction.execute(status -> ledger.find(transactionId));
        if (held == null || held.isEmpty() || !held.get().issued() || clock.instant().isBefore(held.get().readyAt())) {
            throw new ProviderUnavailableException("Sandbox: transaction " + transactionId + " is not issued");
        }
        return new Documents(held.get().xml().getBytes(StandardCharsets.UTF_8), held.get().pdf());
    }

    /** The part of the buyer's e-mail address before the {@code @}: where a rehearsal is asked for. */
    private static String mailbox(InvoiceDocument invoice) {
        String email = invoice.buyer().email();
        if (email == null) {
            return "";
        }
        int at = email.indexOf('@');
        return (at < 0 ? email : email.substring(0, at)).toLowerCase(Locale.ROOT);
    }

    /** Rolls a losing concurrent submission back; the caller sees the provider as not having answered, and asks again. */
    private static final class ConcurrentSubmission extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ConcurrentSubmission() {
            super("The same transaction is being submitted elsewhere");
        }
    }
}
