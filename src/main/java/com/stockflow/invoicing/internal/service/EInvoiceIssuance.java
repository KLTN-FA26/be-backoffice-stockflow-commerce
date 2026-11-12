package com.stockflow.invoicing.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>Submitting <b>again</b> a transaction the provider does not know obeys the provider's own rule
 *       ({@link EInvoiceProvider#resubmission()}): a real provider may still be working on a submission a look-up does
 *       not show yet (Viettel: 90 seconds), and remembers a transaction id only so long (Viettel: three days). Inside
 *       the first the invoice waits; past the second nothing is sent automatically: the invoice is marked
 *       {@code UNKNOWN_TO_PROVIDER} and the accountant sends it as a new transaction ({@link #resubmitAsNew}), which
 *       is done only when the provider, asked at that moment, confirms it holds no such transaction.</li>
 * </ol>
 *
 * <h2>Nobody waits for the provider</h2>
 *
 * <p>{@link #issue} commits SUBMITTING and returns; the provider is called afterwards on another thread, for every
 * provider alike - a real one may take a minute and a half to answer, and a screen that behaved differently per
 * provider would be learnt wrongly on the sandbox. If that thread never runs (the application stops), nothing is lost:
 * the invoice is SUBMITTING with its transaction id, and the reconciliation job takes it up under the same rules.
 * One invoice is worked on by one thread of this instance at a time ({@code inFlight}); across instances the quiet
 * period does the same job.</p>
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
    private final AsyncTaskExecutor executor;
    /** Invoices a thread of this instance is talking to the provider about right now. */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    EInvoiceIssuance(EInvoiceIssuanceSteps steps, EInvoiceProviders providers, ProviderCallRecorder calls,
                     Clock clock, @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor) {
        this.steps = steps;
        this.providers = providers;
        this.calls = calls;
        this.clock = clock;
        this.executor = executor;
    }

    /**
     * Issues a draft: it becomes SUBMITTING, committed, and comes back so; the provider is called right afterwards on
     * another thread. What the provider answered is read from the invoice later (ISSUED or REJECTED), or asked for
     * with {@link #reconcile}.
     *
     * @param expectedVersion the version the accountant read; null when the system issues
     * @param by              the accountant; null when the system issues
     * @throws com.stockflow.common.error.BusinessException see {@link EInvoiceIssuanceSteps#submit}
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "einvoice", resourceId = "#id")
    public EInvoice.Snapshot issue(UUID id, Long expectedVersion, UUID by) {
        EInvoice.Snapshot submitting = steps.submit(id, expectedVersion, by);
        sendSoon(id);
        return submitting;
    }

    /**
     * Sends as a <b>new transaction</b> an invoice the provider does not know and no longer remembers the id of
     * (marked {@code UNKNOWN_TO_PROVIDER}, or simply older than the provider's memory). Only when the provider, asked
     * now, confirms it holds no such transaction: anything else - it holds it, it is working on it, it does not answer
     * - is refused and changes nothing.
     *
     * <p>A transaction id does not change while an invoice is at the provider ({@code tg_einvoice_transaction}), so the
     * invoice goes the way every other one does: taken back as refused with the code {@code UNKNOWN_TO_PROVIDER},
     * reopened, and issued again as its next attempt - three short steps. Stopped in between, it is a refused invoice
     * or a draft, which the accountant issues as usual.</p>
     *
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}; {@code OPTIMISTIC_LOCK}; 409
     *         {@code EINVOICE_INVALID_TRANSITION} when it is not at the provider; 409
     *         {@code EINVOICE_RESUBMISSION_REFUSED} when the provider may still remember the transaction, or does not
     *         confirm that it holds none; 503 {@code EINVOICE_PROVIDER_UNAVAILABLE} when it does not answer
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "einvoice", resourceId = "#id")
    public EInvoice.Snapshot resubmitAsNew(UUID id, long expectedVersion, UUID by) {
        if (!inFlight.add(id)) {
            throw new BusinessException(ErrorCode.EINVOICE_RESUBMISSION_REFUSED,
                    "The provider is being asked about this invoice right now");
        }
        EInvoice.Snapshot submitting;
        try {
            EInvoice.Snapshot invoice = steps.get(id);
            if (invoice.version() != expectedVersion) {
                throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                        "Invoice %s changed since it was read".formatted(invoice.code()));
            }
            if (invoice.status() != EInvoiceStatus.SUBMITTING) {
                throw new BusinessException(ErrorCode.EINVOICE_INVALID_TRANSITION,
                        "Invoice %s is %s, not at the provider".formatted(invoice.code(), invoice.status()));
            }
            EInvoiceProvider provider = providers.require(invoice.provider());
            if (!provider.resubmission().forgottenAt(invoice.submittedAt(), clock.instant())) {
                throw new BusinessException(ErrorCode.EINVOICE_RESUBMISSION_REFUSED,
                        "Invoice %s: the provider may still hold its transaction; it is asked about again".formatted(
                                invoice.code()));
            }
            Result result;
            try {
                result = query(provider, invoice);
            } catch (ProviderUnavailableException unavailable) {
                throw new BusinessException(ErrorCode.EINVOICE_PROVIDER_UNAVAILABLE,
                        "The provider did not say whether it holds invoice %s".formatted(invoice.code()));
            }
            if (result.state() != ResultState.NOT_FOUND) {
                throw new BusinessException(ErrorCode.EINVOICE_RESUBMISSION_REFUSED,
                        "Invoice %s: the provider holds its transaction (%s)".formatted(invoice.code(),
                                result.state()));
            }
            steps.rejected(id, invoice.transactionId(), EInvoice.UNKNOWN_TO_PROVIDER_CODE,
                    "The provider confirmed it holds no invoice for this transaction; sent again as a new one");
            steps.reopenUnknown(id);
            submitting = steps.submit(id, null, by);
        } finally {
            inFlight.remove(id);
        }
        // After this thread let go of the invoice: the sender takes hold of it itself.
        sendSoon(id);
        return submitting;
    }

    /**
     * The first call to the provider for a transaction just committed, off the caller's thread. Nothing it fails with
     * reaches anyone: the invoice is SUBMITTING and the job asks about it.
     */
    private void sendSoon(UUID id) {
        try {
            executor.execute(() -> {
                try {
                    reconcile(id, true);
                } catch (RuntimeException failure) {
                    log.warn("E-invoice {} was not sent right away and is left to the job: {}", id,
                            failure.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException notAccepted) {
            log.warn("E-invoice {} could not be queued for sending and is left to the job", id);
        }
    }

    /**
     * Finds out what became of an invoice that is SUBMITTING, and submits it if the provider has never received it.
     * Does nothing to an invoice in any other status. Safe to call at any time, by anyone, any number of times.
     */
    public EInvoice.Snapshot reconcile(UUID id) {
        return reconcile(id, false);
    }

    /** @param firstSubmission whether this follows DRAFT → SUBMITTING directly, so nothing was sent before */
    private EInvoice.Snapshot reconcile(UUID id, boolean firstSubmission) {
        if (!inFlight.add(id)) {
            // Another thread is at the provider for this invoice; two at once is how one transaction is sent twice.
            return steps.get(id);
        }
        try {
            return reconcileAlone(id, firstSubmission);
        } finally {
            inFlight.remove(id);
        }
    }

    private EInvoice.Snapshot reconcileAlone(UUID id, boolean firstSubmission) {
        EInvoice.Snapshot invoice = steps.get(id);
        if (invoice.status() != EInvoiceStatus.SUBMITTING) {
            return invoice;
        }
        EInvoiceProvider provider = providers.require(invoice.provider());
        String transaction = invoice.transactionId();
        try {
            Result result = query(provider, invoice);
            if (result.state() == ResultState.NOT_FOUND) {
                Instant now = clock.instant();
                if (!firstSubmission && provider.resubmission().forgottenAt(invoice.submittedAt(), now)) {
                    // The provider no longer remembers the transaction id: the same id could become a second invoice.
                    // Not sent again on its own - marked for the accountant, who sends it as a new transaction.
                    if (steps.flagUnknown(id, transaction)) {
                        log.warn("E-invoice {} (transaction {}) is unknown to the provider past the time it remembers "
                                + "a transaction: it needs the accountant", LogSafe.text(invoice.code()),
                                LogSafe.text(transaction));
                    }
                    return steps.get(id);
                }
                if (!firstSubmission && !provider.resubmission().allowedAt(invoice.submittedAt(), now)) {
                    // The provider may still be working on what was sent: sending now races it. Asked again later.
                    log.debug("E-invoice {}: unknown to the provider inside its quiet period; not sent again yet",
                            LogSafe.text(invoice.code()));
                    return invoice;
                }
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
