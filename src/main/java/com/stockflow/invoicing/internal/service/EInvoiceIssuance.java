package com.stockflow.invoicing.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.common.storage.Checksums;
import com.stockflow.common.storage.StorageException;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.InvoiceDocument;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ProviderUnavailableException;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Result;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ResultState;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Submission;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.SubmissionOutcome;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.ProviderCallLog;
import com.stockflow.invoicing.internal.domain.ProviderCallLog.Operation;
import com.stockflow.invoicing.internal.domain.ProviderCallLog.Outcome;
import com.stockflow.invoicing.internal.domain.TaxRateTotal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Issues an invoice through the provider and finds out what became of it (plan §1.4; kltn-docs 21 §4.2, BR-04).
 *
 * <h2>Never twice</h2>
 *
 * <p>A delivery gets one invoice. The danger is the reply that never arrives: the provider issued the invoice, the
 * answer was lost, and whoever sends it again issues a second one. So nothing here is ever sent "again" on a guess:</p>
 * <ol>
 *   <li>The invoice becomes SUBMITTING with a transaction id <b>before</b> the provider is called, and that is
 *       committed. From then on there is always something to ask about.</li>
 *   <li>{@link #reconcile} <b>asks first</b> ({@code query} by transaction id). Issued: take the result. Refused: record
 *       it. Still working: wait. Only when the provider has never heard of the transaction is the invoice submitted -
 *       under the same id, which the provider itself refuses to turn into a second invoice.</li>
 *   <li>A call that fails changes nothing. The invoice stays SUBMITTING and the next reconciliation - the job's, or the
 *       accountant's "check again" - starts at step 2.</li>
 * </ol>
 *
 * <p>No transaction is open while the provider is called: the steps before and after are short transactions of their
 * own ({@link EInvoiceIssuanceSteps}). Every call is logged ({@link ProviderCallLog}) with hashes of what went and
 * came, never the content.</p>
 */
@Service
public class EInvoiceIssuance {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceIssuance.class);

    private final EInvoiceIssuanceSteps steps;
    private final EInvoiceProviders providers;
    private final ProviderCallRecorder calls;
    private final Clock clock;

    EInvoiceIssuance(EInvoiceIssuanceSteps steps, EInvoiceProviders providers, ProviderCallRecorder calls,
                     Clock clock) {
        this.steps = steps;
        this.providers = providers;
        this.calls = calls;
        this.clock = clock;
    }

    /**
     * Issues a draft: sends it to the provider and takes the answer if it is already there. The invoice comes back
     * SUBMITTING while the provider works on it, ISSUED or REJECTED once it answered.
     *
     * @param expectedVersion the version the accountant read; null when the system issues
     * @param by              the accountant; null when the system issues
     * @throws com.stockflow.common.error.BusinessException see {@link EInvoiceIssuanceSteps#submit}
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "einvoice", resourceId = "#id")
    public EInvoice.Snapshot issue(UUID id, Long expectedVersion, UUID by) {
        steps.submit(id, expectedVersion, by);
        return reconcile(id);
    }

    /**
     * Finds out what became of an invoice that is SUBMITTING, and submits it if the provider has never received it.
     * Does nothing to an invoice in any other status. Safe to call at any time, by anyone, any number of times.
     */
    public EInvoice.Snapshot reconcile(UUID id) {
        EInvoice.Snapshot invoice = steps.get(id);
        if (invoice.status() != EInvoiceStatus.SUBMITTING) {
            return invoice;
        }
        EInvoiceProvider provider = providers.require(invoice.provider());
        String transaction = invoice.transactionId();
        try {
            Result result = query(provider, invoice);
            if (result.state() == ResultState.NOT_FOUND) {
                InvoiceDocument document = document(invoice);
                Submission submission = call(invoice, Operation.SUBMIT, hash(document.toString()),
                        () -> provider.submit(document), answer -> switch (answer.outcome()) {
                            case ACCEPTED -> Outcome.ACCEPTED;
                            case DUPLICATE -> Outcome.DUPLICATE;
                            case REJECTED -> Outcome.REJECTED;
                        }, Submission::errorCode);
                if (submission.outcome() == SubmissionOutcome.REJECTED) {
                    steps.rejected(id, transaction, submission.errorCode(), submission.message());
                    return steps.get(id);
                }
                // A provider that works at once has the result already; one that does not says PENDING.
                result = query(provider, invoice);
            }
            switch (result.state()) {
                case ISSUED -> {
                    EInvoiceProvider.Documents documents = call(invoice, Operation.DOWNLOAD, null,
                            () -> provider.download(transaction), answer -> Outcome.DOWNLOADED, answer -> null);
                    steps.issued(id, transaction, result, documents);
                }
                case REJECTED -> steps.rejected(id, transaction, result.errorCode(), result.message());
                case PENDING, NOT_FOUND -> { /* nothing to record yet: asked again on the next reconciliation */ }
            }
        } catch (StorageException unstored) {
            // The provider issued it, but its files could not be stored or locked for their ten years. It is not
            // marked issued without them: it stays SUBMITTING, and the next reconciliation fetches them again.
            log.error("E-invoice {} (transaction {}) is issued at the provider but its files were not stored and "
                    + "locked; it will be reconciled again", LogSafe.text(invoice.code()), LogSafe.text(transaction),
                    unstored);
        } catch (ProviderUnavailableException unavailable) {
            // Logged with the call. The invoice stays SUBMITTING under the same transaction id; nothing is sent again
            // here - the next reconciliation asks the provider first.
            log.warn("E-invoice {} (transaction {}): the provider did not answer; it will be asked again",
                    LogSafe.text(invoice.code()), LogSafe.text(transaction));
        }
        return steps.get(id);
    }

    private Result query(EInvoiceProvider provider, EInvoice.Snapshot invoice) {
        return call(invoice, Operation.QUERY, null, () -> provider.query(invoice.transactionId()),
                answer -> switch (answer.state()) {
                    case NOT_FOUND -> Outcome.NOT_FOUND;
                    case PENDING -> Outcome.PENDING;
                    case ISSUED -> Outcome.ISSUED;
                    case REJECTED -> Outcome.REJECTED;
                }, Result::errorCode);
    }

    /** Makes one call and logs it, however it ends. */
    private <T> T call(EInvoice.Snapshot invoice, Operation operation, String requestHash, Supplier<T> action,
                       Function<T, Outcome> outcome, Function<T, String> errorCode) {
        Instant started = clock.instant();
        try {
            T answer = action.get();
            record(invoice, operation, outcome.apply(answer), errorCode.apply(answer), requestHash,
                    hash(String.valueOf(answer instanceof EInvoiceProvider.Documents documents
                            ? Checksums.sha256Hex(documents.xml()) + Checksums.sha256Hex(documents.pdf()) : answer)),
                    started);
            return answer;
        } catch (ProviderUnavailableException unavailable) {
            record(invoice, operation, Outcome.TIMEOUT, null, requestHash, null, started);
            throw unavailable;
        } catch (RuntimeException failure) {
            record(invoice, operation, Outcome.ERROR, failure.getClass().getSimpleName(), requestHash, null, started);
            throw new ProviderUnavailableException("The e-invoice provider failed", failure);
        }
    }

    private void record(EInvoice.Snapshot invoice, Operation operation, Outcome outcome, String errorCode,
                        String requestHash, String responseHash, Instant started) {
        try {
            calls.record(new ProviderCallLog.Call(invoice.id(), invoice.provider(), invoice.transactionId(), operation,
                    outcome, null, errorCode, requestHash, responseHash, started,
                    Math.max(0, Duration.between(started, clock.instant()).toMillis())));
        } catch (RuntimeException logFailure) {
            // The log is evidence, not a step of issuing: an invoice is not left unissued because a log row failed.
            log.error("Could not log the {} call for e-invoice {}", operation, LogSafe.text(invoice.code()),
                    logFailure);
        }
    }

    private static String hash(String text) {
        return Checksums.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The invoice as the provider is handed it. */
    static InvoiceDocument document(EInvoice.Snapshot invoice) {
        return new InvoiceDocument(invoice.transactionId(), invoice.code(), invoice.templateCode(), invoice.symbol(),
                invoice.issueDate(), invoice.seller(), invoice.buyer(), invoice.currency(), invoice.paymentMethod(),
                invoice.orderNumber(), invoice.contractNumber(), invoice.lines(), TaxRateTotal.of(invoice.lines()),
                invoice.subtotal(), invoice.taxTotal(), invoice.grandTotal(), invoice.amountInWords());
    }
}
