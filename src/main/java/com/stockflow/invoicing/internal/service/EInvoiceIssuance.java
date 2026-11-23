package com.stockflow.invoicing.internal.service;

import com.stockflow.common.api.FieldError;
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
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
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
import java.time.LocalDate;
import java.util.List;
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
 * came, never the content - and the provider's own handle on a submission it took in, where it gives one (SePay's
 * tracking code, which is how its refusal after taking an invoice in is learnt: HĐ-1).</p>
 *
 * <h2>The way out of SUBMITTING (HĐ-1)</h2>
 *
 * <p>An invoice the provider refused, issued or never received settles on its own: the reconciliation asks and records
 * it. One the provider says it has no record of, yet that never settles - its sends are answered "already taken" while
 * the look-up finds nothing, or it was sent before its provider's handle was kept - is taken back by the accountant
 * ({@link #withdraw}): REJECTED with {@link EInvoice#WITHDRAWN_CODE} and the accountant's reason, reopened and issued
 * again under a new transaction like any refused invoice.</p>
 *
 * <p>For that way out to open, the invoice's last send has to age past {@code stockflow.invoicing.withdraw-after}. A
 * transaction the provider has no record of was sent again every quiet period (SePay: ten minutes) for as long as it
 * stayed so, which kept its last send forever recent: no withdrawal, and every later-dated invoice of its series held
 * back behind it ({@code EINVOICE_OLDER_DRAFT_PENDING}). So the reconciliation sends a transaction again by itself only
 * within {@code stockflow.invoicing.auto-resend-for} of its submission (an hour); afterwards it only asks. A provider
 * that took the request in has answered by then - issued, refused or still working - and one that has not is the
 * accountant's: told after {@code stuck-after} (half an hour, before the resending stops), able to withdraw it
 * {@code withdraw-after} after its last send. The price is an outage longer than that hour: an invoice nobody could
 * send is not sent again on its own once the provider is back, and waits for the accountant's withdrawal.</p>
 *
 * <p>An invoice the provider keeps calling "still working" ({@code PENDING}) is never taken back: the provider holds
 * it, and may yet issue it - SePay's "issued, not numbered yet" and its tracking {@code Success} are that too (WF-05
 * BE-07, decision Q-5: "issued, no number" is waiting, never withdrawn). Of the answers that say nothing
 * ({@code UNKNOWN}) only one may be: the provider saying in so many words that it cannot confirm the result
 * ({@link EInvoiceProvider#resultUnconfirmed}), once nothing was sent under it for {@code withdraw-unknown-after} (a
 * day), and only with the reference of the provider's support's written answer, kept as the refusal's message under
 * {@link EInvoice#WITHDRAWN_RESULT_UNCONFIRMED_CODE}. A transaction let go that way, or as "no record", is asked about
 * daily for {@code withdraw-watch-for} (30 days) afterwards ({@link #watchLetGoTransactions}): one the provider shows
 * issued after all is recorded and the accountants told ({@code DUPLICATE_RISK}). Every other waiting state that never
 * settles is left to the accountant's record of what the provider's portal shows ({@link #recordPortalResult},
 * decision Q-8), and they are told once its result is overdue ({@code PROVIDER_RESULT_OVERDUE}).</p>
 *
 * <h2>A refusal nobody confirmed is recorded only once it is settled (HĐ-1)</h2>
 *
 * <p>Some refusals are the provider's report of its processing, with its look-up finding nothing either way (SePay's
 * tracking {@code Failed}: {@link EInvoiceProvider#refusalUnconfirmedByLookup}). Telling such a refusal from "the
 * result is not known yet" rests on the provider's words, and words change; a provider still settling the result with
 * the body that numbers invoices may yet issue that attempt. So the reconciliation does not record it while the
 * provider could still be working on the transaction - {@code withdraw-after} since its last send, as for a withdrawal:
 * until then the invoice stays SUBMITTING and is only asked about (nothing is sent: a "refused" transaction is not
 * resent), and an attempt the provider issues meanwhile is recorded ISSUED as any other. Only the refusal the provider
 * still reports after that is recorded REJECTED.</p>
 *
 * <p>The guard sits where the refusal is recorded rather than where an invoice is sent again, because a REJECTED
 * invoice has several ways forward - reopened and issued again, voided and drawn up afresh (then issued automatically
 * as attempt 0), voided by a failed delivery - and every one of them must be able to trust that the old attempt will
 * not become an invoice. Recorded only when settled, REJECTED means the same thing wherever it is read. The price is a
 * refusal that is real waiting up to {@code withdraw-after} before the accountant sees it; the stuck notice
 * ({@code stuck-after}) tells them meanwhile, and a withdrawal is refused during the wait (the provider does not say it
 * has no record of the transaction), which is right: it is the same question the wait answers.</p>
 */
@Service
public class EInvoiceIssuance {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceIssuance.class);

    private final EInvoiceIssuanceSteps steps;
    private final EInvoiceProviders providers;
    private final ProviderCallRecorder calls;
    private final InvoicingProperties properties;
    private final Clock clock;
    private final AsyncTaskExecutor executor;
    private final EInvoiceDrafts drafts;
    private final EInvoiceQuotaGate quota;
    private final EInvoicePortalReductions portalReductions;

    /**
     * How long a lease on talking to the provider about an invoice lasts unless renewed. Longer than the longest single
     * call to a provider (Viettel's is checked against it at startup, {@link ViettelSInvoiceClient}), so nobody takes
     * the lease over while its holder waits for an answer.
     */
    static final Duration PROVIDER_LEASE = Duration.ofMinutes(15);

    EInvoiceIssuance(EInvoiceIssuanceSteps steps, EInvoiceProviders providers, ProviderCallRecorder calls,
                     InvoicingProperties properties, Clock clock,
                     @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor, EInvoiceDrafts drafts,
                     EInvoiceQuotaGate quota, EInvoicePortalReductions portalReductions) {
        this.portalReductions = portalReductions;
        this.drafts = drafts;
        this.quota = quota;
        this.steps = steps;
        this.providers = providers;
        this.calls = calls;
        this.properties = properties;
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
        String provider = steps.providerFor(id);
        if (quota.exhausted(provider)) {
            throw quotaExhausted(id);
        }
        EInvoice.Snapshot submitting = steps.submit(id, expectedVersion, by);
        quota.sent(provider);
        sendSoon(id);
        return submitting;
    }

    /**
     * The accountant issues a draft (F-16): every check the automatic issuing makes is made again first (WF-05 BE-25,
     * invariant V8), then the draft is submitted as {@link #issue(UUID, Long, UUID)} does.
     * <ol>
     *   <li><b>The provider's plan</b> (BE-54, decision Q-22 row BR-09): known to have no invoice left, nothing is sent -
     *       the draft stays a draft ({@code EINVOICE_QUOTA_EXHAUSTED}); sent anyway, SePay would refuse it for good and
     *       it would have to be reopened by hand.</li>
     *   <li><b>The draft against the rules as they are now</b> ({@link EInvoiceDrafts#recheckForIssue}): the shipment
     *       still delivered ({@code EINVOICE_NOT_INVOICEABLE}), the rates and tax classes of today, the buyer as the
     *       customer's legal profile names it. Whatever is new is stored on the draft; what the accountant did not
     *       acknowledge refuses the issue ({@code EINVOICE_DRAFT_OUT_OF_DATE}, {@code details.warnings}) - the screen
     *       they decided on was not the draft that would have gone out.</li>
     *   <li><b>Late</b> (decision Q-10): an original issued after its delivery day under a provider that takes only
     *       today's date carries today's date and the label "issued late"; the accountant issuing it says why
     *       ({@code EINVOICE_LATE_ISSUE_REASON_REQUIRED} without it), and the reason is kept in the audit log with the
     *       acknowledged warnings. The automatic issuing is never held back for lateness: each hour waited makes it
     *       later.</li>
     * </ol>
     *
     * @param acknowledged the warnings the accountant read and accepts; may be null
     * @param lateReason   why an original is issued after its delivery day; required only then, at most 500
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "einvoice", resourceId = "#id",
            details = "{acknowledgedWarnings: #acknowledged, lateReason: #lateReason}")
    public EInvoice.Snapshot issue(UUID id, long expectedVersion, java.util.Set<String> acknowledged,
                                   String lateReason, UUID by) {
        if (lateReason != null && lateReason.strip().length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "lateReason: at most 500 characters",
                    List.of(FieldError.of("lateReason", "at most 500 characters")));
        }
        String provider = steps.providerFor(id);
        if (quota.exhausted(provider)) {
            throw quotaExhausted(id);
        }
        EInvoiceDrafts.IssueCheck check = drafts.recheckForIssue(id, expectedVersion, acknowledged);
        if (!check.unacknowledged().isEmpty()) {
            List<String> warnings = check.unacknowledged().stream().sorted().toList();
            throw new BusinessException(ErrorCode.EINVOICE_DRAFT_OUT_OF_DATE, ("The draft is not what was read: %s "
                    + "to be acknowledged before it is issued").formatted(warnings), List.of(),
                    java.util.Map.of("warnings", warnings, "version", check.version()));
        }
        EInvoice.Snapshot submitting = steps.submit(id, check.version(), by, true,
                lateReason == null || lateReason.isBlank() ? null : lateReason.strip());
        quota.sent(provider);
        sendSoon(id);
        return submitting;
    }

    private static BusinessException quotaExhausted(UUID id) {
        return new BusinessException(ErrorCode.EINVOICE_QUOTA_EXHAUSTED, ("The provider's plan has no invoice left; "
                + "%s stays a draft and is issued once the plan is topped up").formatted(id));
    }

    /** Whether the provider's plan is known to be used up (BE-54); the automatic issuing skips its batch then. */
    boolean quotaExhausted(String provider) {
        return quota.exhausted(provider);
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
            // BE-54: sent again as a new transaction it would only be refused for good while the plan is used up.
            if (quota.exhausted(invoice.provider())) {
                throw quotaExhausted(id);
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
            quota.sent(invoice.provider());
        } finally {
            release(id, holder);
        }
        // After this thread let go of the invoice: the sender takes hold of it itself.
        sendSoon(id);
        return submitting;
    }

    /** As {@link #withdraw(UUID, long, String, String, UUID)} without a reference to the provider's support. */
    public EInvoice.Snapshot withdraw(UUID id, long expectedVersion, String reason, UUID by) {
        return withdraw(id, expectedVersion, reason, null, by);
    }

    /**
     * Takes back an invoice stuck at the provider (HĐ-1): SUBMITTING → REJECTED, so it is reopened, corrected if need be,
     * and issued again under a new transaction like any refused invoice. Not announced as a refusal: the accountant did
     * it. The old transaction is asked about daily for {@code withdraw-watch-for} (30 days) afterwards
     * ({@link EInvoiceIssuanceSteps#rejected}, {@link #watchLetGoTransactions}).
     *
     * <h2>Why this cannot issue the invoice twice</h2>
     *
     * <p>A second invoice would come from the provider issuing the old transaction after this one let it go, while the
     * new transaction is issued too. Each of the conditions below closes a way for that to happen; all of them hold, or
     * nothing changes:</p>
     * <ol>
     *   <li><b>The provider, asked now, has no record of the transaction</b> ({@code NOT_FOUND}; code
     *       {@link EInvoice#WITHDRAWN_CODE}) - or, the one exception (WF-05 BE-07, decision Q-5), says in so many words
     *       that it cannot confirm what became of it ({@link EInvoiceProvider#resultUnconfirmed}, SePay's "chưa xác nhận
     *       được kết quả"), nothing was sent under it for {@code withdraw-unknown-after} (a day), and the accountant gives
     *       the reference of the provider's support's written answer ({@code supportReference}, kept as the refusal's
     *       message under {@link EInvoice#WITHDRAWN_RESULT_UNCONFIRMED_CODE}; {@code EINVOICE_WITHDRAWAL_EVIDENCE_REQUIRED}
     *       without it). Never anything else: it holds it or is still working on it ({@code PENDING} - SePay's
     *       "issued, not numbered yet" and its tracking {@code Success} included), an answer that says nothing
     *       ({@code UNKNOWN} for any other reason: unread, a 4xx, an error with no entry), no answer. Those wait, and
     *       one that never settles is recorded from the provider's portal ({@link #recordPortalResult}). It used to be
     *       taken back after a day on any {@code UNKNOWN} - which an invoice SePay had issued without its number yet
     *       was - and then issued a second time.</li>
     *   <li><b>The provider never once said it holds the transaction</b> ({@link ProviderCallLog#providerHeld}): a send
     *       answered "already there", a look-up that found it issued. That proof outlives the answer of the moment.</li>
     *   <li><b>Nothing was sent under it for longer than the provider can still be working on it</b>
     *       ({@code stockflow.invoicing.withdraw-after}, three hours by default): longer than SePay's own retries of a
     *       request it took in ("up to 2 hours", its tracking documentation), than every provider's quiet period
     *       ({@link EInvoiceProvider.Resubmission}), and than the provider lease. A send still on its way could be taken
     *       in after the look-up said "not found"; no send is that old. That time does come: the reconciliation stops
     *       sending the transaction again by itself {@code stockflow.invoicing.auto-resend-for} after it was
     *       submitted, so the last send stops moving.</li>
     *   <li><b>Under the invoice's provider lease</b>: no reconciliation sends the transaction between the look-up and the
     *       refusal being recorded, and once REJECTED nothing is sent under it again
     *       ({@link EInvoiceIssuanceSteps#aboutToSend} checks the invoice still waits for that transaction).</li>
     *   <li><b>The old transaction id is never used again</b>: the next attempt is a new transaction, a new reference at
     *       the provider. A stray send of the old one is the provider's duplicate of itself (SePay:
     *       {@code EINVOICE_DOCUMENT_EXISTED}), not a second invoice of ours.</li>
     * </ol>
     *
     * <p>A refusal tells the screen when the invoice may be taken back and what the provider said (BE-59, F-12):
     * {@code details.withdrawableFrom} (absent when never, or not known yet) and {@code details.providerState}
     * ({@code PENDING}, {@code UNKNOWN}, {@code NOT_FOUND}, {@code UNCONFIRMED}, or {@code ISSUED} / {@code REJECTED} for
     * an answer "check again" records; absent when the provider was not asked).</p>
     *
     * @param reason           why, as the accountant states it after checking the provider's portal; required, at most
     *                         500; kept in the audit log, and as the refusal's message for a "no record" withdrawal
     * @param supportReference the provider's support ticket, or the words of its written answer, at most 200; required
     *                         only to take back a result the provider cannot confirm
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}; {@code OPTIMISTIC_LOCK}; {@code VALIDATION_FAILED} without a
     *         reason; 409 {@code EINVOICE_INVALID_TRANSITION} when it is not at the provider; 409
     *         {@code EINVOICE_WITHDRAWAL_REFUSED} when it was sent too recently, the provider holds it or may, or
     *         somebody is asking the provider about it right now; 409 {@code EINVOICE_WITHDRAWAL_EVIDENCE_REQUIRED}; 503
     *         {@code EINVOICE_PROVIDER_UNAVAILABLE} when the provider does not answer
     */
    @Auditable(action = AuditAction.REJECT, resourceType = "einvoice", resourceId = "#id",
            details = "{reason: #reason, supportReference: #supportReference}")
    public EInvoice.Snapshot withdraw(UUID id, long expectedVersion, String reason, String supportReference, UUID by) {
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "reason: required, at most 500 characters",
                    List.of(FieldError.of("reason", "required, at most 500 characters")));
        }
        if (supportReference != null && supportReference.strip().length() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "supportReference: at most 200 characters",
                    List.of(FieldError.of("supportReference", "at most 200 characters")));
        }
        steps.get(id);
        UUID holder = Identifiers.newId();
        if (!steps.claim(id, holder, PROVIDER_LEASE)) {
            throw new BusinessException(ErrorCode.EINVOICE_WITHDRAWAL_REFUSED,
                    "The provider is being asked about this invoice right now");
        }
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
            Instant lastSent = steps.lastSent(id, invoice.transactionId()).orElse(invoice.submittedAt());
            Instant now = clock.instant();
            // Audit r3 EINV-4: an invoice the provider last said it cannot confirm waits the longer wait from the start,
            // as the screen shows it (withdrawability, the same last answer on record) - not three hours first and then
            // a day.
            boolean unconfirmedOnRecord = calls.lastQuery(id, invoice.transactionId())
                    .map(call -> onRecord(call, provider)).orElse(WithdrawalRule.ProviderState.NOT_ASKED)
                    == WithdrawalRule.ProviderState.UNCONFIRMED;
            Instant earliest = unconfirmedOnRecord ? WithdrawalRule.earliestUnconfirmed(lastSent, properties)
                    : WithdrawalRule.earliest(lastSent, properties);
            if (earliest != null && now.isBefore(earliest)) {
                // Not asked: whatever the provider says, it is too early (WithdrawalRule, before any answer).
                throw refused(("Invoice %s was last sent at %s; the provider may still be working on it until %s")
                        .formatted(invoice.code(), lastSent, earliest), earliest, null);
            }
            Result result;
            try {
                result = query(provider, invoice);
            } catch (ProviderUnavailableException unavailable) {
                throw new BusinessException(ErrorCode.EINVOICE_PROVIDER_UNAVAILABLE,
                        "The provider did not say whether it holds invoice %s".formatted(invoice.code()));
            }
            WithdrawalRule.ProviderState asked = stateOf(result, provider);
            boolean held = calls.providerHeld(id, invoice.transactionId());
            WithdrawalRule.Decision decision = WithdrawalRule.decide(held, asked, lastSent, now, properties);
            String state = asked.name();
            if (!decision.allowed()) {
                throw switch (decision.blocked()) {
                    case TOO_RECENT -> refused(("Invoice %s: the provider cannot confirm what became of it; it may be "
                            + "taken back from %s, on its support's written answer").formatted(invoice.code(),
                            decision.from()), decision.from(), state);
                    case PROVIDER_HOLDS -> refused(held
                            ? "Invoice %s: the provider once said it holds this transaction; it is never taken back"
                            .formatted(invoice.code())
                            : notNoRecord(invoice, result), null, state);
                    default -> refused(notNoRecord(invoice, result), null, state);
                };
            }
            boolean unconfirmed = decision.evidenceRequired();
            if (unconfirmed) {
                if (supportReference == null || supportReference.isBlank()) {
                    throw new BusinessException(ErrorCode.EINVOICE_WITHDRAWAL_EVIDENCE_REQUIRED, ("Invoice %s: the "
                            + "provider cannot confirm its result; it is taken back only on the provider's support's "
                            + "written answer (supportReference)").formatted(invoice.code()),
                            List.of(FieldError.of("supportReference", "required")),
                            java.util.Map.of("providerState", state));
                }
                steps.rejected(id, invoice.transactionId(), EInvoice.WITHDRAWN_RESULT_UNCONFIRMED_CODE,
                        supportReference.strip());
            } else {
                steps.rejected(id, invoice.transactionId(), EInvoice.WITHDRAWN_CODE, reason.strip());
            }
            log.warn("E-invoice {} (transaction {}) was taken back from {} by {}: the provider {}",
                    LogSafe.text(invoice.code()), LogSafe.text(invoice.transactionId()), invoice.provider(), by,
                    unconfirmed ? "cannot confirm its result (its support's answer recorded)" : "has no record of it");
        } finally {
            release(id, holder);
        }
        return steps.get(id);
    }

    private static String notNoRecord(EInvoice.Snapshot invoice, Result result) {
        return ("Invoice %s: the provider does not say it has no record of it (%s%s); it is waited for, or its result "
                + "recorded from the provider's portal").formatted(invoice.code(), result.state(),
                result.errorCode() == null ? "" : ", " + result.errorCode());
    }

    /** The provider's answer, asked now, as the withdrawal rule reads it: "cannot confirm" is its own state. */
    private static WithdrawalRule.ProviderState stateOf(Result result, EInvoiceProvider provider) {
        return switch (result.state()) {
            case NOT_FOUND -> WithdrawalRule.ProviderState.NOT_FOUND;
            case PENDING -> WithdrawalRule.ProviderState.PENDING;
            case ISSUED -> WithdrawalRule.ProviderState.ISSUED;
            case REJECTED -> WithdrawalRule.ProviderState.REJECTED;
            case UNKNOWN -> provider.resultUnconfirmed(result.errorCode()) ? WithdrawalRule.ProviderState.UNCONFIRMED
                    : WithdrawalRule.ProviderState.UNKNOWN;
        };
    }

    /**
     * Whether the invoice would be taken back now, by the rule {@link #withdraw} applies ({@link WithdrawalRule}),
     * judged on the provider's last answer on record instead of a call (audit r2 H-2: the screen showed "Rút" on every
     * invoice at the provider, and an invoice SePay holds "issued, no number" got a 409 - Q-5 says it is never taken
     * back, demo HĐ-1 step 2 that the button is not there). The reconciliation asks the provider every half minute, so
     * the last answer is recent; {@link #withdraw} still asks again and may refuse what changed in between.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public WithdrawalRule.Decision withdrawability(EInvoice.Snapshot invoice) {
        if (invoice.status() != EInvoiceStatus.SUBMITTING || invoice.provider() == null
                || invoice.transactionId() == null) {
            return new WithdrawalRule.Decision(WithdrawalRule.Blocked.NOT_AT_PROVIDER, null, false);
        }
        java.util.Optional<EInvoiceProvider> provider = providers.find(invoice.provider());
        if (provider.isEmpty()) {
            return new WithdrawalRule.Decision(WithdrawalRule.Blocked.PROVIDER_STATE_UNCLEAR, null, false);
        }
        Instant lastSent = steps.lastSent(invoice.id(), invoice.transactionId()).orElse(invoice.submittedAt());
        WithdrawalRule.ProviderState last = calls.lastQuery(invoice.id(), invoice.transactionId())
                .map(call -> onRecord(call, provider.get())).orElse(WithdrawalRule.ProviderState.NOT_ASKED);
        return WithdrawalRule.decide(calls.providerHeld(invoice.id(), invoice.transactionId()), last, lastSent,
                clock.instant(), properties);
    }

    /** A look-up as the call log keeps it ({@link #query}): an answer that does not say is logged as its error. */
    private static WithdrawalRule.ProviderState onRecord(ProviderCallLog.Call call, EInvoiceProvider provider) {
        return switch (call.outcome()) {
            case NOT_FOUND -> WithdrawalRule.ProviderState.NOT_FOUND;
            case PENDING -> WithdrawalRule.ProviderState.PENDING;
            case ISSUED -> WithdrawalRule.ProviderState.ISSUED;
            case REJECTED -> WithdrawalRule.ProviderState.REJECTED;
            case ERROR -> provider.resultUnconfirmed(call.errorCode()) ? WithdrawalRule.ProviderState.UNCONFIRMED
                    : WithdrawalRule.ProviderState.UNKNOWN;
            default -> WithdrawalRule.ProviderState.UNKNOWN;
        };
    }

    /**
     * Which reductions made on the provider's portal may be recorded against the invoice now (audit r2 M-10), by the
     * rule {@code /external-reductions} applies ({@link EInvoicePortalReductions#applicability}).
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ExternalReductionOptions externalReductions(EInvoice.Snapshot invoice) {
        return portalReductions.applicability(invoice);
    }

    /**
     * What a reduction made on the provider's portal may be recorded for against one invoice now (audit r2 M-10).
     *
     * @param deliveryFailed whether one of its failed delivery may be (basis DELIVERY_FAILED)
     * @param returns        the returns whose refunded goods wait on it, each recordable once (basis RETURN with that
     *                       return's id); empty when none
     * @param errors         the voided corrections of an error of it that its provider could not send, each recordable
     *                       once (basis ERROR with that draft's id, audit r3 EINV-2); empty when none
     */
    public record ExternalReductionOptions(boolean deliveryFailed, List<ReturnOption> returns,
                                           List<ErrorOption> errors) {

        public static final ExternalReductionOptions NONE = new ExternalReductionOptions(false, List.of(), List.of());

        public ExternalReductionOptions {
            returns = List.copyOf(returns);
            errors = List.copyOf(errors);
        }

        public ExternalReductionOptions(boolean deliveryFailed, List<ReturnOption> returns) {
            this(deliveryFailed, returns, List.of());
        }

        /** Whether anything may be recorded. */
        public boolean any() {
            return deliveryFailed || !returns.isEmpty() || !errors.isEmpty();
        }
    }

    /** A return whose refunded goods wait on a replacement, by its id - what {@code returnRequestId} takes - and RMA. */
    public record ReturnOption(UUID returnRequestId, String rmaNumber) {
    }

    /**
     * A voided correction of an error that may be recorded from the portal (audit r3 EINV-2), by its id - what {@code
     * voidedAdjustmentId} takes - its code, what it corrects, and what it takes off in all (below zero).
     */
    public record ErrorOption(UUID voidedAdjustmentId, String code, String reason, java.math.BigDecimal grandTotal) {
    }

    /** {@code EINVOICE_WITHDRAWAL_REFUSED} with what the screen shows (BE-59): from when, and what the provider said. */
    private static BusinessException refused(String message, Instant withdrawableFrom, String providerState) {
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        if (withdrawableFrom != null) {
            details.put("withdrawableFrom", withdrawableFrom.toString());
        }
        if (providerState != null) {
            details.put("providerState", providerState);
        }
        return new BusinessException(ErrorCode.EINVOICE_WITHDRAWAL_REFUSED, message, List.of(), details);
    }

    /** What the provider's portal shows for an invoice stuck at the provider (F-13). */
    public enum PortalOutcome {
        /** Issued, with a number, the tax authority's code and the files. */
        ISSUED,
        /** Refused by the tax authority: no invoice in force under the transaction. */
        REFUSED_BY_TAX_AUTHORITY
    }

    /** What the accountant records from the provider's portal; for an issued invoice its number, codes and files. */
    public record PortalRecord(PortalOutcome outcome, Integer invoiceNumber, String taxAuthorityCode,
                               String lookupCode, byte[] xml, byte[] pdf, String note) {
    }

    /**
     * Records the result of an invoice stuck at the provider as the provider's portal shows it (WF-05 BE-08, decision
     * Q-8; F-13): the way out of every SUBMITTING state that never settles on its own - SePay holding an invoice with a
     * number and no tax authority code, an entry the tax authority refused, an answer that keeps saying nothing, a
     * provider that can no longer be called. The accountants are told when such an invoice is overdue
     * ({@code PROVIDER_RESULT_OVERDUE}).
     *
     * <ul>
     *   <li><b>Issued</b>: the XML handed in must be this invoice's - its template, symbol, number and seller's tax
     *       code, and the tax authority's code where it carries one ({@code EINVOICE_PORTAL_FILE_INVALID}) - and the
     *       provider, asked now, must not say otherwise: issued under another number or series, no record, refused
     *       ({@code EINVOICE_PORTAL_RECORD_MISMATCH}). A provider that cannot be asked any more is not asked; the XML is
     *       the invoice in law. Recorded ISSUED, {@code issued_via} PORTAL_RECORD, the files stored and locked, the
     *       signature checked and the corrected invoice marked - as the provider's own answer would be
     *       ({@link EInvoiceIssuanceSteps#issuedFromPortal}).</li>
     *   <li><b>Refused by the tax authority</b>: never while the provider says it is issued, nor sooner than
     *       {@code withdraw-after} after the last send; REJECTED under {@link EInvoice#REFUSED_BY_TAX_AUTHORITY_CODE}
     *       with the note, and its transaction watched like a withdrawn one.</li>
     * </ul>
     *
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}; {@code OPTIMISTIC_LOCK}; {@code VALIDATION_FAILED};
     *         {@code EINVOICE_INVALID_TRANSITION} unless SUBMITTING; {@code EINVOICE_PORTAL_FILE_INVALID};
     *         {@code EINVOICE_PORTAL_RECORD_MISMATCH}; {@code EINVOICE_WITHDRAWAL_REFUSED} for a refusal recorded too
     *         early; {@code EINVOICE_PROVIDER_UNAVAILABLE} when a callable provider does not answer
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "einvoice", resourceId = "#id",
            details = "{portalOutcome: #record.outcome(), invoiceNumber: #record.invoiceNumber(), note: #record.note()}")
    public EInvoice.Snapshot recordPortalResult(UUID id, long expectedVersion, PortalRecord record, UUID by) {
        java.util.Objects.requireNonNull(record, "record");
        if (record.outcome() == null || blank(record.note()) || record.note().strip().length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "outcome and a note (at most 500) are required",
                    List.of(FieldError.of("note", "required, at most 500 characters")));
        }
        PortalInvoiceXml xml = null;
        if (record.outcome() == PortalOutcome.ISSUED) {
            // Each missing field named (audit r2 contract M-2): the screen marks the box, not a sentence about five.
            List<FieldError> missing = new java.util.ArrayList<>();
            if (record.invoiceNumber() == null || record.invoiceNumber() < 1 || record.invoiceNumber() > 99_999_999) {
                missing.add(FieldError.of("invoiceNumber", "required, 1 to 99999999"));
            }
            if (blank(record.taxAuthorityCode())) {
                missing.add(FieldError.of("taxAuthorityCode", "required"));
            }
            if (blank(record.lookupCode())) {
                missing.add(FieldError.of("lookupCode", "required"));
            }
            if (empty(record.xml())) {
                missing.add(FieldError.of("xml", "required"));
            }
            if (empty(record.pdf())) {
                missing.add(FieldError.of("pdf", "required"));
            }
            if (!missing.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("An invoice recorded as issued names its "
                        + "number, the tax authority's code and the look-up code, with its XML and PDF"), missing);
            }
            xml = PortalInvoiceXml.read(record.xml());
        }
        steps.get(id);
        UUID holder = Identifiers.newId();
        if (!steps.claim(id, holder, PROVIDER_LEASE)) {
            throw new BusinessException(ErrorCode.EINVOICE_WITHDRAWAL_REFUSED,
                    "The provider is being asked about this invoice right now");
        }
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
            Result lookedUp = lookUpIfCallable(invoice);
            if (record.outcome() == PortalOutcome.ISSUED) {
                // BE-30: a correction under a provider that chooses its series carries the provider's series - as the
                // look-up names it, else as its XML does; every other invoice the series it was sent under.
                String symbol = !steps.takesProviderSeries(invoice) ? invoice.symbol()
                        : lookedUp != null && lookedUp.symbol() != null ? lookedUp.symbol() : xml.symbol();
                requireSameInvoice(invoice, symbol, record, xml);
                boolean contradicts = lookedUp != null && (lookedUp.state() == ResultState.NOT_FOUND
                        || lookedUp.state() == ResultState.REJECTED
                        || lookedUp.state() == ResultState.ISSUED
                        && (!record.invoiceNumber().equals(lookedUp.invoiceNumber())
                        || lookedUp.symbol() != null && !lookedUp.symbol().equals(symbol)));
                if (contradicts) {
                    throw new BusinessException(ErrorCode.EINVOICE_PORTAL_RECORD_MISMATCH, ("Invoice %s: the provider, "
                            + "asked now, says %s%s - not invoice %d of %s").formatted(invoice.code(),
                            lookedUp.state(), lookedUp.invoiceNumber() == null ? "" : " " + lookedUp.invoiceNumber(),
                            record.invoiceNumber(), symbol));
                }
                Result recorded = new Result(ResultState.ISSUED, record.invoiceNumber(),
                        record.taxAuthorityCode().strip(), record.lookupCode().strip(),
                        xml.signedAt() != null ? xml.signedAt() : clock.instant(), null, null)
                        .numberedUnder(null, symbol);
                if (!steps.issuedFromPortal(id, invoice.transactionId(), recorded,
                        new EInvoiceProvider.Documents(record.xml(), record.pdf()), by)) {
                    throw new BusinessException(ErrorCode.EINVOICE_INVALID_TRANSITION,
                            "Invoice %s was settled by the provider meanwhile".formatted(invoice.code()));
                }
            } else {
                // Q-5: an invoice the provider holds - issued, or "issued, no number yet" / still processing (PENDING) -
                // is never let go on the accountant's word: recorded refused, it would be sent again under a new
                // transaction while the old one may still become an invoice. A refusal the provider itself reports is
                // recorded by the reconciliation.
                if (lookedUp != null && (lookedUp.state() == ResultState.ISSUED
                        || lookedUp.state() == ResultState.PENDING
                        && !providers.require(invoice.provider()).pendingMayBeRefusedByTaxAuthority())) {
                    throw new BusinessException(ErrorCode.EINVOICE_PORTAL_RECORD_MISMATCH, ("Invoice %s: the provider, "
                            + "asked now, shows it %s; check it again instead").formatted(invoice.code(),
                            lookedUp.state()));
                }
                Instant lastSent = steps.lastSent(id, invoice.transactionId()).orElse(invoice.submittedAt());
                if (lastSent != null && clock.instant().isBefore(lastSent.plus(properties.withdrawAfter()))) {
                    throw refused(("Invoice %s was last sent at %s; the provider may still be working on it")
                            .formatted(invoice.code(), lastSent), lastSent.plus(properties.withdrawAfter()),
                            lookedUp == null ? null : lookedUp.state().name());
                }
                steps.rejected(id, invoice.transactionId(), EInvoice.REFUSED_BY_TAX_AUTHORITY_CODE,
                        record.note().strip());
            }
        } finally {
            release(id, holder);
        }
        return steps.get(id);
    }

    /** The provider's answer now, or null for a provider that cannot be asked any more (not configured, not usable). */
    private Result lookUpIfCallable(EInvoice.Snapshot invoice) {
        if (!providers.usable(invoice.provider()) || providers.find(invoice.provider()).isEmpty()) {
            return null;
        }
        try {
            return query(providers.require(invoice.provider()), invoice);
        } catch (ProviderUnavailableException unavailable) {
            throw new BusinessException(ErrorCode.EINVOICE_PROVIDER_UNAVAILABLE,
                    "The provider did not say what it holds for invoice %s".formatted(invoice.code()));
        }
    }

    /** The XML handed in is this invoice's, as entered ({@code EINVOICE_PORTAL_FILE_INVALID} otherwise). */
    private static void requireSameInvoice(EInvoice.Snapshot invoice, String symbol, PortalRecord record,
                                           PortalInvoiceXml xml) {
        boolean same = java.util.Objects.equals(xml.templateCode(), invoice.templateCode())
                && java.util.Objects.equals(xml.symbol(), symbol)
                && record.invoiceNumber().equals(xml.number())
                && (xml.sellerTaxCode() == null || xml.sellerTaxCode().equals(invoice.seller().taxCode()))
                && (xml.taxAuthorityCode() == null
                || xml.taxAuthorityCode().equals(record.taxAuthorityCode().strip()));
        if (!same) {
            throw new BusinessException(ErrorCode.EINVOICE_PORTAL_FILE_INVALID, ("The XML is invoice %s%s-%s of seller "
                    + "%s, not invoice %d of %s%s (%s)").formatted(xml.templateCode(), xml.symbol(), xml.number(),
                    xml.sellerTaxCode(), record.invoiceNumber(), invoice.templateCode(), symbol, invoice.code()));
        }
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static boolean empty(byte[] content) {
        return content == null || content.length == 0;
    }

    /** A reduction made on the provider's portal, as the accountant records it (F-13 external-reductions). */
    public record ExternalReduction(String templateCode, String symbol, Integer invoiceNumber, LocalDate issueDate,
                                    String taxAuthorityCode, String lookupCode, byte[] xml, byte[] pdf, String note,
                                    com.stockflow.invoicing.internal.domain.CorrectionBasis basis,
                                    UUID returnRequestId, UUID voidedAdjustmentId) {

        /** A reduction of a failed delivery. */
        public ExternalReduction(String templateCode, String symbol, Integer invoiceNumber, LocalDate issueDate,
                                 String taxAuthorityCode, String lookupCode, byte[] xml, byte[] pdf, String note) {
            this(templateCode, symbol, invoiceNumber, issueDate, taxAuthorityCode, lookupCode, xml, pdf, note,
                    com.stockflow.invoicing.internal.domain.CorrectionBasis.DELIVERY_FAILED, null, null);
        }

        /** A reduction of a failed delivery, or of a return it names. */
        public ExternalReduction(String templateCode, String symbol, Integer invoiceNumber, LocalDate issueDate,
                                 String taxAuthorityCode, String lookupCode, byte[] xml, byte[] pdf, String note,
                                 com.stockflow.invoicing.internal.domain.CorrectionBasis basis, UUID returnRequestId) {
            this(templateCode, symbol, invoiceNumber, issueDate, taxAuthorityCode, lookupCode, xml, pdf, note, basis,
                    returnRequestId, null);
        }
    }

    /**
     * Records a reduction the accountant made on the provider's portal (WF-05 BE-10, Q-9; decision Q-8; F-13): the
     * failed delivery of an original whose own reduction no provider could send (voided {@code UNSENDABLE_REVERSAL}),
     * or of a replacement, which no provider adjusts. It is recorded as an issued adjustment taking off what the
     * invoice still invoices, {@code issued_via} PORTAL_RECORD ({@link EInvoicePortalReductions#record}).
     *
     * <p>The XML is required: it is the invoice in law (Q-8: "number and files"), and what was typed is held against it
     * - template, symbol, number, date, the tax authority's code ({@code EINVOICE_PORTAL_FILE_INVALID}). Made on the
     * portal, such a reduction has no transaction of ours at the provider, so it is not looked up there.</p>
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "einvoice", resourceId = "#originalId",
            details = "{externalReduction: #reduction.invoiceNumber(), note: #reduction.note()}")
    public EInvoice.Snapshot recordExternalReduction(UUID originalId, ExternalReduction reduction, UUID by) {
        java.util.Objects.requireNonNull(reduction, "reduction");
        if (blank(reduction.templateCode()) || blank(reduction.symbol()) || reduction.invoiceNumber() == null
                || reduction.invoiceNumber() < 1 || reduction.invoiceNumber() > 99_999_999
                || reduction.issueDate() == null || empty(reduction.xml()) || empty(reduction.pdf())
                || blank(reduction.note()) || reduction.note().strip().length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("A reduction recorded from the portal names its "
                    + "template, symbol, number and date, with its XML, PDF and a note (at most 500)"),
                    List.of(FieldError.of("invoiceNumber",
                            "required with templateCode, symbol, issueDate, xml, pdf, note")));
        }
        if (reduction.issueDate().isAfter(com.stockflow.common.domain.BusinessCalendar.date(clock.instant()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "issueDate: not in the future",
                    List.of(FieldError.of("issueDate", "not in the future")));
        }
        PortalInvoiceXml xml = PortalInvoiceXml.read(reduction.xml());
        String taxAuthorityCode = !blank(reduction.taxAuthorityCode()) ? reduction.taxAuthorityCode().strip()
                : xml.taxAuthorityCode();
        boolean same = xml.templateCode().equals(reduction.templateCode().strip())
                && xml.symbol().equals(reduction.symbol().strip()) && reduction.invoiceNumber().equals(xml.number())
                && (xml.issueDate() == null || xml.issueDate().equals(reduction.issueDate()))
                && taxAuthorityCode != null
                && (xml.taxAuthorityCode() == null || xml.taxAuthorityCode().equals(taxAuthorityCode));
        if (!same) {
            throw new BusinessException(ErrorCode.EINVOICE_PORTAL_FILE_INVALID, ("The XML is invoice %s%s-%s of %s with "
                    + "the tax authority's code %s, not what was entered").formatted(xml.templateCode(), xml.symbol(),
                    xml.number(), xml.issueDate(), xml.taxAuthorityCode()));
        }
        return portalReductions.record(originalId, new EInvoicePortalReductions.PortalReduction(
                reduction.templateCode().strip(), reduction.symbol().strip(), reduction.invoiceNumber(),
                reduction.issueDate(), taxAuthorityCode,
                blank(reduction.lookupCode()) ? taxAuthorityCode : reduction.lookupCode().strip(),
                xml.signedAt() != null ? xml.signedAt() : clock.instant(), xml.sellerTaxCode(),
                new EInvoiceProvider.Documents(reduction.xml(), reduction.pdf()), reduction.note().strip(),
                reduction.basis() == null ? com.stockflow.invoicing.internal.domain.CorrectionBasis.DELIVERY_FAILED
                        : reduction.basis(), reduction.returnRequestId(), reduction.voidedAdjustmentId()), by);
    }

    /**
     * Asks the provider once more about each transaction let go without its word (WF-05 BE-07, decision Q-5): taken
     * back, or recorded refused on its processing report or on the tax authority's refusal alone. For
     * {@code withdraw-watch-for} (30 days), at most once a day each: one the provider shows issued after all is recorded
     * on its watch and the accountants are told ({@code DUPLICATE_RISK}) - the invoice may stand twice for one delivery,
     * and only they can settle that with the provider. A provider that does not answer is asked again next time.
     *
     * @return how many were found issued now
     */
    public int watchLetGoTransactions(int limit) {
        int found = 0;
        for (EInvoiceRepository.WatchedTransaction watched : steps.watchedToAsk(limit)) {
            java.util.Optional<EInvoiceProvider> provider = providers.find(watched.provider());
            if (provider.isEmpty()) {
                continue;
            }
            try {
                Result result = provider.get().query(watched.transactionId());
                if (result.state() == ResultState.ISSUED && result.invoiceNumber() != null) {
                    if (steps.watchedTransactionIssued(watched.einvoiceId(), watched.transactionId(), result)) {
                        found++;
                        log.warn("E-invoice {}: transaction {}, let go as {}, is issued at {} after all (number {})",
                                watched.einvoiceId(), LogSafe.text(watched.transactionId()),
                                LogSafe.text(watched.letGoAs()), watched.provider(), result.invoiceNumber());
                    }
                } else {
                    steps.watchedTransactionAsked(watched.einvoiceId(), watched.transactionId());
                }
            } catch (ProviderUnavailableException unavailable) {
                log.info("E-invoice {}: the provider did not answer about let-go transaction {}; asked again next time",
                        watched.einvoiceId(), LogSafe.text(watched.transactionId()));
            } catch (RuntimeException failure) {
                log.warn("E-invoice {}: let-go transaction {} was not asked about: {}", watched.einvoiceId(),
                        LogSafe.text(watched.transactionId()), failure.getClass().getSimpleName());
            }
        }
        return found;
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

    /** Whether the reconciliation has sent this transaction again by itself for as long as it may. */
    private boolean autoResendOver(EInvoice.Snapshot invoice, Instant now) {
        return invoice.submittedAt() != null
                && !now.isBefore(invoice.submittedAt().plus(properties.autoResendFor()));
    }

    /**
     * Whether a refusal the provider reports may be recorded now: always, unless it is one its look-up did not stand
     * behind and the transaction was last sent less than {@code withdraw-after} ago (class javadoc, "A refusal nobody
     * confirmed is recorded only once it is settled"). Then it is asked about again on the next reconciliation.
     */
    private boolean refusalSettled(EInvoiceProvider provider, EInvoice.Snapshot invoice, Result result) {
        if (!provider.refusalUnconfirmedByLookup(result.errorCode())) {
            return true;
        }
        Instant lastSent = steps.lastSent(invoice.id(), invoice.transactionId()).orElse(invoice.submittedAt());
        if (lastSent == null || !clock.instant().isBefore(lastSent.plus(properties.withdrawAfter()))) {
            return true;
        }
        log.info("E-invoice {} (transaction {}): refused on the provider's processing report alone ({}); not recorded "
                + "before {}, the provider may still issue it", LogSafe.text(invoice.code()),
                LogSafe.text(invoice.transactionId()), LogSafe.text(result.errorCode()),
                lastSent.plus(properties.withdrawAfter()));
        return false;
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
                if (steps.markedDeliveryFailed(id)) {
                    // Its delivery was corrected to a failure (WF-05 HĐ-3): sending it now could issue an invoice for
                    // goods nobody received. Only asked about; unknown to the provider, the accountant withdraws it,
                    // and the refusal that records is settled by voiding it (EInvoiceDrafts.settleCorrectedDelivery).
                    log.debug("E-invoice {}: unknown to the provider, and its delivery failed; asked, not sent",
                            LogSafe.text(invoice.code()));
                    return invoice;
                }
                if (lastSent != null && autoResendOver(invoice, now)) {
                    // Sent again for long enough: from here on only asked about, so that its last send ages and the
                    // accountant can take it back (class javadoc, "The way out of SUBMITTING").
                    log.debug("E-invoice {}: unknown to the provider past the automatic resending; asked, not sent",
                            LogSafe.text(invoice.code()));
                    return invoice;
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
                case REJECTED -> {
                    if (refusalSettled(provider, invoice, result)) {
                        steps.rejected(id, transaction, result.errorCode(), result.message());
                    }
                }
                // Nothing to record yet for either: asked again on the next reconciliation.
                case PENDING -> { }
                case NOT_FOUND -> { }
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
                    started, answer instanceof Submission submission ? submission.providerReference() : null);
            return answer;
        } catch (ProviderUnavailableException unavailable) {
            record(invoice, operation, Outcome.TIMEOUT, null, requestHash, null, started, null);
            throw unavailable;
        } catch (RuntimeException failure) {
            record(invoice, operation, Outcome.ERROR, failure.getClass().getSimpleName(), requestHash, null, started,
                    null);
            throw new ProviderUnavailableException("The e-invoice provider failed", failure);
        }
    }

    /**
     * @param providerReference the provider's handle on a submission it took in: the only way some providers tell a
     *                          refusal after that (SePay's tracking code), so it is kept with the call
     */
    private void record(EInvoice.Snapshot invoice, Operation operation, Outcome outcome, String errorCode,
                        String requestHash, String responseHash, Instant started, String providerReference) {
        try {
            calls.record(new ProviderCallLog.Call(invoice.id(), invoice.provider(), invoice.transactionId(), operation,
                    outcome, null, errorCode, requestHash, responseHash, started,
                    Math.max(0, Duration.between(started, clock.instant()).toMillis()), providerReference));
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
     * and date, which it has because only an issued invoice is corrected, and the transaction it was issued under.
     * Null for an original.
     */
    private EInvoiceProvider.Correction correctionOf(EInvoice.Snapshot invoice) {
        if (invoice.originalEInvoiceId() == null) {
            return null;
        }
        EInvoice.Snapshot original = steps.get(invoice.originalEInvoiceId());
        return new EInvoiceProvider.Correction(invoice.type(), original.templateCode(), original.symbol(),
                original.invoiceNumber(), original.issueDate(), invoice.adjustmentReason(), original.transactionId());
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
