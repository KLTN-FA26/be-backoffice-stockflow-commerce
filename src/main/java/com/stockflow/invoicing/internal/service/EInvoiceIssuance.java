package com.stockflow.invoicing.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
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
 *       it. Still working: wait. Only when the provider says clearly that it has never heard of the transaction is the
 *       invoice submitted - under the same id, which the provider itself refuses to turn into a second invoice. An
 *       answer that does not say ({@code UNKNOWN}: an error, a shape not recognised) is waited out like "still
 *       working", unless nothing was ever sent under the transaction, when there is nothing it could be hiding. A send
 *       <b>again</b> that the provider refuses is recorded as refused only once a fresh look-up confirms it holds
 *       nothing: the refusal may be of a duplicate of what it already holds.</li>
 *   <li>A call that fails changes nothing. The invoice stays SUBMITTING and the next reconciliation - the job's, or the
 *       accountant's "check again" - starts at step 2.</li>
 *   <li>Submitting <b>again</b> a transaction the provider does not know obeys the provider's own rule
 *       ({@link EInvoiceProvider#resubmission()}): a real provider may still be working on a submission a look-up does
 *       not show yet (Viettel asks for 90 seconds; longer here than one call can take), and remembers a transaction
 *       id only so long (Viettel: three days). The first is counted from the <b>last</b> time the transaction was
 *       sent, which is recorded, and committed, before each send ({@link EInvoiceIssuanceSteps#aboutToSend}); the
 *       second from the first submission. Inside the first the invoice waits; past the second nothing is sent
 *       automatically: the invoice is marked {@code UNKNOWN_TO_PROVIDER} and the accountant sends it as a new
 *       transaction ({@link #resubmitAsNew}), which is done only when the provider, asked at that moment, confirms it
 *       holds no such transaction.</li>
 * </ol>
 *
 * <h2>Nobody waits for the provider</h2>
 *
 * <p>{@link #issue} commits SUBMITTING and returns; the provider is called afterwards on another thread, for every
 * provider alike - a real one may take a minute and a half to answer, and a screen that behaved differently per
 * provider would be learnt wrongly on the sandbox. If that thread never runs (the application stops), nothing is lost:
 * the invoice is SUBMITTING with its transaction id, and the reconciliation job takes it up under the same rules.
 * One thread at a time, on any instance, talks to the provider about an invoice: the one holding its lease
 * ({@link com.stockflow.invoicing.internal.domain.EInvoiceSending}). The lease is in the database because the senders
 * are on different instances - the job, the accountant's "check again", the send after "issue" - and it is renewed
 * before every send, so that it outlasts the call. It expires on its own; an instance that dies holding it holds the invoice up for {@link #PROVIDER_LEASE}
 * at most. The quiet period is the second line: a lease that ran out in the middle of a call cannot let a second send
 * overlap the first, because the quiet period is longer than one whole call.</p>
 *
 * <p>An adjustment and a replacement are issued the same way (WF-05 E4): the document names the invoice they change,
 * and the step that records "issued" marks that invoice in the same transaction ({@link EInvoiceIssuanceSteps#issued}).</p>
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

    /**
     * How long a lease on talking to the provider about an invoice lasts unless renewed. Longer than the longest single
     * call to a provider (Viettel's is checked against it at startup, {@link ViettelSInvoiceClient}), so nobody takes
     * the lease over while its holder waits for an answer.
     */
    static final Duration PROVIDER_LEASE = Duration.ofMinutes(15);

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
     *         {@code EINVOICE_RESUBMISSION_REFUSED} when the provider may still remember the transaction, does not
     *         confirm that it holds none, or is being asked about the invoice by somebody else right now; 503
     *         {@code EINVOICE_PROVIDER_UNAVAILABLE} when it does not answer
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "einvoice", resourceId = "#id")
    public EInvoice.Snapshot resubmitAsNew(UUID id, long expectedVersion, UUID by) {
        steps.get(id);
        UUID holder = Identifiers.newId();
        if (!steps.claim(id, holder, PROVIDER_LEASE)) {
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
            release(id, holder);
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
                    reconcile(id);
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
        EInvoice.Snapshot invoice = steps.get(id);
        if (invoice.status() != EInvoiceStatus.SUBMITTING) {
            return invoice;
        }
        UUID holder = Identifiers.newId();
        if (!steps.claim(id, holder, PROVIDER_LEASE)) {
            // Somebody, here or on another instance, is at the provider for this invoice; two at once is how one
            // transaction is sent twice.
            return invoice;
        }
        try {
            return reconcileAlone(id, holder);
        } finally {
            release(id, holder);
        }
    }

    /** Gives the lease up; when that fails, it runs out on its own. */
    private void release(UUID id, UUID holder) {
        try {
            steps.release(id, holder);
        } catch (RuntimeException failure) {
            log.warn("The provider lease on e-invoice {} was not given up and runs out on its own: {}", id,
                    failure.getClass().getSimpleName());
        }
    }

    private EInvoice.Snapshot reconcileAlone(UUID id, UUID holder) {
        EInvoice.Snapshot invoice = steps.get(id);
        if (invoice.status() != EInvoiceStatus.SUBMITTING) {
            return invoice;
        }
        EInvoiceProvider provider = providers.require(invoice.provider());
        String transaction = invoice.transactionId();
        try {
            Result result = query(provider, invoice);
            // Null when nothing was ever sent under this transaction: then there is nothing to race or to forget, and
            // an unclear answer cannot hide a send the provider is holding.
            Instant lastSent = result.state() == ResultState.NOT_FOUND || result.state() == ResultState.UNKNOWN
                    ? steps.lastSent(id, transaction).orElse(null) : null;
            if (result.state() == ResultState.NOT_FOUND
                    || result.state() == ResultState.UNKNOWN && lastSent == null) {
                Instant now = clock.instant();
                if (lastSent != null && provider.resubmission().forgottenAt(invoice.submittedAt(), now)) {
                    // The provider no longer remembers the transaction id: the same id could become a second invoice.
                    // Not sent again on its own - marked for the accountant, who sends it as a new transaction.
                    if (steps.flagUnknown(id, transaction)) {
                        log.warn("E-invoice {} (transaction {}) is unknown to the provider past the time it remembers "
                                + "a transaction: it needs the accountant", LogSafe.text(invoice.code()),
                                LogSafe.text(transaction));
                    }
                    return steps.get(id);
                }
                if (!provider.resubmission().allowedAt(invoice.submittedAt(), lastSent, now)) {
                    // The provider may still be working on what was last sent: sending now races it. Asked again later.
                    log.debug("E-invoice {}: unknown to the provider inside its quiet period; not sent again yet",
                            LogSafe.text(invoice.code()));
                    return invoice;
                }
                if (!steps.aboutToSend(id, transaction, holder, PROVIDER_LEASE)) {
                    return steps.get(id);
                }
                InvoiceDocument document = document(invoice, correctionOf(invoice));
                Submission submission = call(invoice, Operation.SUBMIT, hash(document.toString()),
                        () -> provider.submit(document), answer -> switch (answer.outcome()) {
                            case ACCEPTED -> Outcome.ACCEPTED;
                            case DUPLICATE -> Outcome.DUPLICATE;
                            case REJECTED -> Outcome.REJECTED;
                        }, Submission::errorCode);
                // A provider that works at once has the result already; one that does not says PENDING.
                if (submission.outcome() == SubmissionOutcome.REJECTED && lastSent == null) {
                    // The first send refused: nothing else was ever sent under the transaction, so nothing exists.
                    steps.rejected(id, transaction, submission.errorCode(), submission.message());
                    return steps.get(id);
                }
                result = query(provider, invoice);
                if (submission.outcome() == SubmissionOutcome.REJECTED) {
                    // A send again refused is not proof that no invoice exists: a provider may refuse a transaction it
                    // already holds from the earlier send ("duplicate") instead of answering with its invoice, and
                    // recording REJECTED would let the accountant issue a second one. Refused only when the provider,
                    // asked now, confirms it holds nothing; otherwise what it holds is taken below, or asked again.
                    if (result.state() == ResultState.NOT_FOUND) {
                        steps.rejected(id, transaction, submission.errorCode(), submission.message());
                        return steps.get(id);
                    }
                    log.warn("E-invoice {} (transaction {}): sending again was refused ({}) but the provider does not "
                            + "confirm it holds nothing ({})", LogSafe.text(invoice.code()), LogSafe.text(transaction),
                            LogSafe.text(submission.errorCode()), result.state());
                }
            }
            switch (result.state()) {
                case ISSUED -> {
                    EInvoiceProvider.Documents documents = call(invoice, Operation.DOWNLOAD, null,
                            () -> provider.download(transaction), answer -> Outcome.DOWNLOADED, answer -> null);
                    steps.issued(id, transaction, result, documents);
                }
                case REJECTED -> steps.rejected(id, transaction, result.errorCode(), result.message());
                case PENDING, NOT_FOUND -> { /* nothing to record yet: asked again on the next reconciliation */ }
                case UNKNOWN -> log.warn("E-invoice {} (transaction {}): the provider's answer does not say whether it "
                        + "holds the transaction; it will be asked again", LogSafe.text(invoice.code()),
                        LogSafe.text(transaction));
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
                    // The call log has no "unknown": an answer that does not say is logged as the error it carries.
                    case UNKNOWN -> Outcome.ERROR;
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

    /**
     * The invoice an adjustment or replacement changes, as the provider is to be told: its template, symbol, number
     * and date, which it has because only an issued invoice is corrected. Null for an original.
     */
    private EInvoiceProvider.Correction correctionOf(EInvoice.Snapshot invoice) {
        if (invoice.originalEInvoiceId() == null) {
            return null;
        }
        EInvoice.Snapshot original = steps.get(invoice.originalEInvoiceId());
        return new EInvoiceProvider.Correction(invoice.type(), original.templateCode(), original.symbol(),
                original.invoiceNumber(), original.issueDate(), invoice.adjustmentReason());
    }

    /** An original invoice as the provider is handed it. */
    static InvoiceDocument document(EInvoice.Snapshot invoice) {
        return document(invoice, null);
    }

    /** The invoice as the provider is handed it; {@code correction} names the invoice it changes, null on an original. */
    static InvoiceDocument document(EInvoice.Snapshot invoice, EInvoiceProvider.Correction correction) {
        return new InvoiceDocument(invoice.transactionId(), invoice.code(), invoice.templateCode(), invoice.symbol(),
                invoice.issueDate(), invoice.seller(), invoice.buyer(), invoice.currency(), invoice.paymentMethod(),
                invoice.orderNumber(), invoice.contractNumber(), invoice.lines(), TaxRateTotal.of(invoice.lines()),
                invoice.subtotal(), invoice.taxTotal(), invoice.grandTotal(), invoice.amountInWords(), correction);
    }
}
