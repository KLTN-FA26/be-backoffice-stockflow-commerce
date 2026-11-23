package com.stockflow.invoicing.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.Checksums;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.RecordRetention;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.contracts.EInvoiceIssued;
import com.stockflow.contracts.EInvoiceNeedsAttention;
import com.stockflow.contracts.EInvoiceProviderAccountAlert;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceSending;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import com.stockflow.invoicing.internal.domain.InvoiceSeries;
import com.stockflow.invoicing.internal.domain.InvoiceSettings;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The database steps of issuing an invoice, each its own short transaction. {@link EInvoiceIssuance} strings them
 * together around the calls to the provider, which are made with no transaction open: a provider that takes its time
 * must not hold a row lock, and "sent" has to be committed before anything is sent.
 *
 * <p>Every step that records the provider's answer locks the invoice and checks it is still waiting for <i>this</i>
 * transaction. The accountant's click and the reconciliation job can both arrive with the same answer; the second one
 * finds the invoice already settled and does nothing - no second set of files, no second number.</p>
 */
@Component
class EInvoiceIssuanceSteps {

    private final EInvoiceRepository invoices;
    private final EInvoiceSending sending;
    private final InvoiceSettings settings;
    private final EInvoiceProviders providers;
    private final FileTransfers files;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final InvoicingProperties properties;

    EInvoiceIssuanceSteps(EInvoiceRepository invoices, EInvoiceSending sending, InvoiceSettings settings,
                          EInvoiceProviders providers, FileTransfers files, ApplicationEventPublisher events,
                          Clock clock, InvoicingProperties properties) {
        this.invoices = invoices;
        this.sending = sending;
        this.settings = settings;
        this.providers = providers;
        this.files = files;
        this.events = events;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public EInvoice.Snapshot get(UUID id) {
        return invoices.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND)).snapshot();
    }

    /**
     * DRAFT → SUBMITTING, committed before the provider hears of it: from here on the invoice has a transaction id,
     * and whatever happens to the call, the answer is found again under it.
     *
     * <p><b>Not while an earlier-dated invoice of the same series is at the provider.</b> The provider numbers invoices
     * in the order they reach it, and the numbers of a symbol should run on in the order of the invoices' dates (NĐ 254
     * Phụ lục điểm 2; TT 91 Điều 10 khoản 6a). So an invoice is refused while one of the same provider, template and
     * symbol, dated before it, is SUBMITTING - flagged unknown to the provider or not, since either may already hold a
     * number ({@code EINVOICE_OLDER_DRAFT_PENDING}, naming it): its number comes first, or it is refused and leaves the
     * way free, and this one may be sent once it is settled. A draft or a rejected invoice keeps nothing back (owner's
     * decision, HĐ-2): it holds no number and may stay unissued for days - a buyer's tax code missing, a provider's
     * refusal to correct - and holding every later invoice of the business behind it would leave the whole series
     * overdue for one stuck invoice. The draft's screen still names such an invoice
     * ({@code EInvoiceQueryService.earlierPending}), and the automatic issuing takes drafts earliest dated first.</p>
     *
     * <p><b>A correction goes through the provider that issued the invoice it corrects</b> (HĐ-5), whichever provider
     * is in use now: the adjustment or replacement names that invoice in the provider's own terms (SePay: by the
     * reference it was issued under), and the provider in use since a switch has never heard of it - SePay refuses
     * such a correction ({@code INVALID_ORIGIN_INVOICE}) or, worse, it would wait at a provider that cannot answer for
     * it. The original's provider must still be usable here ({@link EInvoiceProviders#usable}) and have a series in use
     * for the correction's year; otherwise the correction is refused ({@code EINVOICE_ORIGINAL_PROVIDER_UNAVAILABLE})
     * and never sent to another provider.</p>
     *
     * <p><b>Dated the day it is issued, where it must be</b> (WF-05 Q-1, BE-01; {@link EInvoice#dateForIssue}): a
     * correction always, an original under a provider that takes only today's date (SePay: anything else is refused
     * for good, sandbox T-1, T-6) - flagged {@code ISSUED_LATE} when that is after its delivery day, and the accountants
     * told ({@link EInvoiceNeedsAttention.Cause#ISSUED_LATE}). Done first, in this transaction, so the series is the
     * one of the day it is issued (a delivery of 31/12 issued on 2/1 goes under the new year's series) and a refusal
     * below leaves the draft as it was. Under such a provider numbers and dates of a series rise together by
     * themselves (sandbox T-2), so the date order the law asks for holds; under one that takes a past date an original
     * keeps its delivery's date and may still come after invoices already issued - the exception the accountant can
     * explain, since one that could never be issued would be worse (decision Q-10: no series comparison).</p>
     *
     * <p>The check runs under the series' submission lock ({@link EInvoiceRepository#lockSeries}), taken after the
     * invoice's own row lock and held until this transaction commits: two invoices of one series submitted at the same
     * moment are decided one after the other, and the second reads the first's SUBMITTING. Without it each would read
     * the other as the draft it still was in committed rows, and both would go.</p>
     *
     * @param expectedVersion the version the caller read; null when the system issues
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}, {@code OPTIMISTIC_LOCK}, {@code EINVOICE_INVALID_TRANSITION},
     *         {@code EINVOICE_BUYER_PROFILE_INCOMPLETE}, {@code EINVOICE_AGREEMENT_REQUIRED} (the correction of an
     *         error without the agreement of both parties), {@code EINVOICE_SERIES_NOT_CONFIGURED},
     *         {@code EINVOICE_OLDER_DRAFT_PENDING} (an invoice of the same series dated before it is at the provider),
     *         {@code EINVOICE_TAX_EXEMPT_NOT_SUPPORTED} (a line at 0%, which here can only be an EXEMPT one),
     *         {@code EINVOICE_PROVIDER_UNAVAILABLE}, {@code EINVOICE_PROVIDER_CANNOT_CORRECT} (a correction, to a
     *         provider that would issue it as an original), {@code EINVOICE_ORIGINAL_PROVIDER_UNAVAILABLE} (a
     *         correction, when the provider that issued the original is no longer usable)
     */
    @Transactional
    public EInvoice.Snapshot submit(UUID id, Long expectedVersion, UUID by) {
        return submit(id, expectedVersion, by, false, null);
    }

    /**
     * The provider an invoice is sent through when it is issued now: the original's for a correction (HĐ-5), the one
     * in use otherwise. For the quota asked before it is sent (BE-54), outside any transaction that holds a lock.
     */
    @Transactional(readOnly = true)
    public String providerFor(UUID id) {
        EInvoice invoice = invoices.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND));
        if (invoice.originalEInvoiceId() == null) {
            return settings.active().provider();
        }
        return invoices.findById(invoice.originalEInvoiceId()).map(original -> original.snapshot().provider())
                .orElse(settings.active().provider());
    }

    /**
     * {@link #submit(UUID, Long, UUID)}, and for the accountant's issue ({@code manual}) the reason an original goes out
     * after its delivery day (WF-05 Q-10, F-16): required then ({@code EINVOICE_LATE_ISSUE_REASON_REQUIRED}), decided
     * under the invoice's lock on the day it is sent, and kept in the audit log by the caller. The automatic issuing is
     * never refused for lateness.
     */
    @Transactional
    public EInvoice.Snapshot submit(UUID id, Long expectedVersion, UUID by, boolean manual, String lateReason) {
        EInvoice invoice = invoices.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND));
        if (expectedVersion != null && expectedVersion != invoice.version()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Invoice %s changed since it was read".formatted(invoice.code()));
        }
        if (invoices.deliveryFailed(id)) {
            // WF-05 HĐ-3: its delivery was corrected to a failure while it was at the provider. Refused there, it is
            // voided (DeliveryCorrectionListener), not sent again for goods nobody received; tg_einvoice_delivery_failed
            // says the same.
            throw new BusinessException(ErrorCode.EINVOICE_NOT_INVOICEABLE, ("Invoice %s invoices a delivery that was "
                    + "corrected to a failure: it is voided, not sent again").formatted(invoice.code()));
        }
        if (invoices.watchFound(id)) {
            // BE-07 (Q-5, invariant V1): a transaction let go earlier was issued by the provider after all - the
            // delivery has its invoice in law under the old transaction (DUPLICATE_RISK was told). Another attempt
            // would be a second invoice for it: the accountant settles it with the provider's support first.
            throw new BusinessException(ErrorCode.EINVOICE_RESUBMISSION_REFUSED, ("Invoice %s: an earlier transaction of "
                    + "it was issued by the provider after all; it is not sent again").formatted(invoice.code()));
        }
        String active = settings.active().provider();
        String provider = active;
        if (invoice.originalEInvoiceId() != null) {
            provider = invoices.findById(invoice.originalEInvoiceId()).map(original -> original.snapshot().provider())
                    .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND));
            if (!provider.equals(active) && !providers.usable(provider)) {
                throw new BusinessException(ErrorCode.EINVOICE_ORIGINAL_PROVIDER_UNAVAILABLE, ("The invoice %s corrects "
                        + "was issued through %s, which is not usable here any more; %s is not sent to %s").formatted(
                        invoice.code(), provider, invoice.code(), active));
            }
        }
        // Refused here, not after the status moved: an invoice must not wait on a provider nobody can call.
        EInvoiceProvider port = providers.require(provider);
        if (invoice.originalEInvoiceId() != null && !port.issuesCorrections()) {
            // Sent anyway, it would come back an original: a second invoice for goods already invoiced.
            throw new BusinessException(ErrorCode.EINVOICE_PROVIDER_CANNOT_CORRECT,
                    "%s issues no adjustment or replacement; %s is not sent".formatted(provider, invoice.code()));
        }
        LocalDate today = BusinessCalendar.date(clock.instant());
        if (manual && (lateReason == null || lateReason.isBlank())
                && invoice.lateIfIssuedOn(today, port.datesOnlyToday())) {
            throw new BusinessException(ErrorCode.EINVOICE_LATE_ISSUE_REASON_REQUIRED, ("Invoice %s is issued after the "
                    + "day of its delivery (%s): say why (lateReason)").formatted(invoice.code(),
                    invoice.deliveredOn()), List.of(FieldError.of("lateReason", "required")));
        }
        boolean late = invoice.dateForIssue(today, port.datesOnlyToday());
        String at = provider;
        InvoiceSeries series = settings.seriesInUse(at, invoice.issueDate().getYear())
                .orElseThrow(() -> new BusinessException(at.equals(active) ? ErrorCode.EINVOICE_SERIES_NOT_CONFIGURED
                        : ErrorCode.EINVOICE_ORIGINAL_PROVIDER_UNAVAILABLE, "No series is in use at %s for %d".formatted(
                        at, invoice.issueDate().getYear())));
        invoices.lockSeries(series);
        invoices.findEarlierSubmitting(invoice.id(), series, invoice.issueDate()).ifPresent(earlier -> {
            throw new BusinessException(ErrorCode.EINVOICE_OLDER_DRAFT_PENDING, ("%s is dated before %s and is at the "
                    + "provider under %s%s; wait for its result, or check it again, then issue %s").formatted(earlier,
                    invoice.code(), series.templateCode(), series.symbol(), invoice.code()));
        });
        invoice.submit(series, by, clock.instant());
        EInvoice.Snapshot submitted = invoices.save(invoice).snapshot();
        if (late) {
            events.publishEvent(new EInvoiceNeedsAttention(submitted.id(), submitted.code(), submitted.orderId(),
                    submitted.orderNumber(), EInvoiceNeedsAttention.Cause.ISSUED_LATE, null, submitted.submittedAt(),
                    submitted.issueDueAt(), submitted.deliveredOn(), submitted.issueDate()));
        }
        return submitted;
    }

    /**
     * SUBMITTING → REJECTED, if the invoice still waits for {@code transactionId}. The accountants are told in the same
     * transaction ({@link EInvoiceNeedsAttention}): a refusal nobody hears of leaves the invoice unissued while its
     * deadline runs, and the refusal and its announcement are recorded together or not at all.
     *
     * <p>Except for {@link EInvoice#UNKNOWN_TO_PROVIDER_CODE} and {@link EInvoice#WITHDRAWN_CODE}: neither is a
     * refusal, both are done at the accountant's own request - the first half of sending an invoice again as a new
     * transaction ({@link EInvoiceIssuance#resubmitAsNew}), and taking an invoice stuck at the provider back
     * ({@link EInvoiceIssuance#withdraw}). Announcing them would tell the accountant of what they just did.</p>
     *
     * <p>And a refusal of <b>the account</b> rather than the invoice ({@link EInvoiceProvider#accountProblem}: the
     * plan's quota used up, its bill unpaid, the registration not ready - HĐ-1) is announced as that, once per cause
     * and day ({@link EInvoiceProviderAccountAlert}), not once per invoice: every invoice sent meanwhile meets the same
     * refusal, and what the accountant does about it is on the provider's portal.</p>
     *
     * <p>An invoice whose delivery was corrected to a failure while it was at the provider
     * ({@link EInvoiceRepository#markDeliveryFailed}; WF-05 HĐ-3) is handed on to be voided instead
     * ({@link DeliveryFailedInvoiceAnswered}, in this transaction), whatever the refusal: there is nothing to correct
     * and send again, so the refusal itself is not announced as one to put right.</p>
     */
    @Transactional
    public void rejected(UUID id, String transactionId, String errorCode, String message) {
        EInvoice invoice = waitingFor(id, transactionId);
        if (invoice == null) {
            return;
        }
        invoice.rejected(errorCode == null || errorCode.isBlank() ? "REJECTED" : errorCode, message);
        EInvoice.Snapshot refused = invoices.save(invoice).snapshot();
        boolean deliveryFailed = invoices.deliveryFailed(id);
        if (deliveryFailed) {
            events.publishEvent(new DeliveryFailedInvoiceAnswered(refused.id()));
        }
        if (letGoWithoutTheProvidersWord(refused)) {
            // BE-07 (Q-5): the provider never confirmed that this transaction holds nothing. Its next attempt is a new
            // transaction, so the old one is asked about daily for a while: issued after all, it is a second invoice.
            invoices.watchTransaction(refused.id(), refused.transactionId(), refused.provider(),
                    refused.rejectionCode(), clock.instant().plus(properties.withdrawWatchFor()));
        }
        if (EInvoice.UNKNOWN_TO_PROVIDER_CODE.equals(refused.rejectionCode())
                || EInvoice.WITHDRAWN_CODE.equals(refused.rejectionCode())
                || EInvoice.WITHDRAWN_RESULT_UNCONFIRMED_CODE.equals(refused.rejectionCode())
                || EInvoice.REFUSED_BY_TAX_AUTHORITY_CODE.equals(refused.rejectionCode())) {
            return;
        }
        Optional<EInvoiceProvider.AccountProblem> account = providers.find(refused.provider())
                .flatMap(provider -> provider.accountProblem(refused.rejectionCode()));
        if (account.isPresent()) {
            events.publishEvent(new EInvoiceProviderAccountAlert(refused.provider(),
                    EInvoiceProviderAccountAlert.Problem.valueOf(account.get().name()), refused.rejectionCode(), null,
                    null, clock.instant()));
            return;
        }
        if (deliveryFailed) {
            return;
        }
        events.publishEvent(new EInvoiceNeedsAttention(refused.id(), refused.code(), refused.orderId(),
                refused.orderNumber(), EInvoiceNeedsAttention.Cause.REJECTED, refused.rejectionCode(),
                refused.submittedAt(), refused.issueDueAt()));
    }

    /**
     * Whether a refusal was recorded without the provider's look-up standing behind it (WF-05 BE-07, Q-5): a withdrawal
     * - the provider had no record, or its support wrote that the result cannot be confirmed - an accountant's record of
     * the tax authority's refusal from the portal, or a refusal read from the provider's processing report alone
     * ({@link EInvoiceProvider#refusalUnconfirmedByLookup}).
     */
    private boolean letGoWithoutTheProvidersWord(EInvoice.Snapshot refused) {
        String code = refused.rejectionCode();
        return EInvoice.WITHDRAWN_CODE.equals(code) || EInvoice.WITHDRAWN_RESULT_UNCONFIRMED_CODE.equals(code)
                || EInvoice.REFUSED_BY_TAX_AUTHORITY_CODE.equals(code)
                || providers.find(refused.provider()).map(p -> p.refusalUnconfirmedByLookup(code)).orElse(false);
    }

    /**
     * SUBMITTING → ISSUED, if the invoice still waits for {@code transactionId}: the two files are stored and locked
     * for ten years, and the invoice takes the provider's number and codes.
     *
     * <p><b>The lock is part of issuing, not an afterthought.</b> Elsewhere a record is locked after its transaction
     * commits, and a lock that fails is logged. An invoice's files must be kept ten years (Luật Kế toán 2015 Điều 41):
     * an invoice marked issued whose files nobody locked would look fine until the day they are gone. So both files
     * are locked here, before the invoice is marked issued, and a lock that fails fails this step - the invoice stays
     * SUBMITTING and the next reconciliation fetches and stores the files again. The provider has issued the invoice
     * by then whatever happens here; a locked file left behind by a step that failed is a copy of a document that
     * exists in law, not a record of something that did not happen.</p>
     *
     * <p>A correction marks the invoice it changes in the same transaction (TT 91 Điều 10): a replacement makes it
     * REPLACED, an adjustment flags it - and every invoice a trade-discount adjustment lists - as adjusted. The
     * corrected invoices are locked <b>before</b> the correction, in id order, the order drawing a correction up takes
     * them in; and written before it, so the shipment never has two invoices in force
     * ({@code uk_einvoice_in_force_per_shipment}). The agreement's file is locked with the invoice's own.</p>
     *
     * <p>{@link EInvoiceIssued} is published here, in the transaction that makes the invoice ISSUED, so the buyer is
     * told of exactly the invoices that were issued: the second arrival of the same answer returns above and publishes
     * nothing.</p>
     *
     * <p><b>The seller's signature is checked first</b> (HĐ-8), for a provider whose documents are signed
     * ({@link EInvoiceProvider#signsDocuments()}): an XML whose signature does not hold - its content changed, a
     * signature its certificate did not make, none at all - is still recorded, since the provider issued it and it
     * exists in law, but flagged {@code SIGNATURE_INVALID}, and the accountants are told
     * ({@link EInvoiceNeedsAttention.Cause#SIGNATURE_INVALID}) instead of the buyer being sent it: a buyer cannot
     * declare an invoice whose signature fails, and the way out is a replacement, which is checked and sent on its own.
     * Checked here, on the bytes about to be stored, because the stored XML never changes and always checks the same.</p>
     *
     * <p>An invoice whose delivery was corrected to a failure while it was at the provider
     * ({@link EInvoiceRepository#markDeliveryFailed}; WF-05 HĐ-3) exists in law from here and invoices goods nobody
     * received: it is handed on, in this transaction, to be reduced in full ({@link DeliveryFailedInvoiceAnswered}).</p>
     *
     * <p><b>Under the series it was sent under, or not at all</b> (WF-05 BE-30). The provider says which series it
     * numbered the invoice under (SePay's look-up: {@code invoice_series}); one that names another symbol is not
     * recorded - the stored reference would be wrong, and the number could collide with ours under the symbol the
     * system thinks it has. It stays SUBMITTING and the accountants are told once per submission
     * ({@link EInvoiceNeedsAttention.Cause#SERIES_MISMATCH}): the series declared here differs from the provider's.</p>
     *
     * <p>Both files are stored before either is locked (BE-57): a storing that fails rolls back and the stored copies
     * are deleted with it; only a lock that fails half way leaves a locked copy behind, which the retry cannot reuse.</p>
     *
     * @throws com.stockflow.common.storage.StorageException when a file cannot be stored or locked
     */
    @Transactional
    public void issued(UUID id, String transactionId, EInvoiceProvider.Result result,
                       EInvoiceProvider.Documents documents) {
        EInvoice unlocked = invoices.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND));
        List<EInvoice> corrected = lockCorrected(unlocked);
        EInvoice invoice = waitingFor(id, transactionId);
        if (invoice == null) {
            return;
        }
        if (result.symbol() != null && !result.symbol().equals(invoice.symbol()) && takesProviderSeries(invoice)) {
            // BE-30: a correction the provider numbered under a series it chose (SePay picks it, sandbox T-4).
            invoice.numberedUnder(result.symbol());
        }
        if (result.symbol() != null && !result.symbol().equals(invoice.symbol())) {
            if (invoices.markSeriesMismatchAnnounced(id, transactionId, clock.instant())) {
                EInvoice.Snapshot waiting = invoice.snapshot();
                events.publishEvent(new EInvoiceNeedsAttention(waiting.id(), waiting.code(), waiting.orderId(),
                        waiting.orderNumber(), EInvoiceNeedsAttention.Cause.SERIES_MISMATCH, result.symbol(),
                        waiting.submittedAt(), waiting.issueDueAt()));
            }
            return;
        }
        markCorrected(invoice, corrected);
        StoredFile[] stored = storeAndRetain(invoice, result.invoiceNumber(), documents);
        invoice.issued(result.invoiceNumber(), result.taxAuthorityCode(), result.lookupCode(), result.signedAt(),
                stored[0].key(), stored[1].key(), clock.instant());
        boolean signed = providers.find(invoice.provider()).map(EInvoiceProvider::signsDocuments).orElse(false);
        if (signed && InvoiceXmlSignature.check(documents.xml()).invalid()) {
            invoice.signatureInvalid();
        }
        EInvoice.Snapshot issued = invoices.save(invoice).snapshot();
        announceIssued(issued);
    }

    /** Whether the invoice is a correction whose provider numbers corrections under a series it chooses (BE-30). */
    boolean takesProviderSeries(EInvoice invoice) {
        return takesProviderSeries(invoice.type(), invoice.provider());
    }

    /** As {@link #takesProviderSeries(EInvoice)} for a snapshot. */
    boolean takesProviderSeries(EInvoice.Snapshot invoice) {
        return takesProviderSeries(invoice.type(), invoice.provider());
    }

    private boolean takesProviderSeries(com.stockflow.invoicing.internal.domain.EInvoiceType type, String provider) {
        return type != com.stockflow.invoicing.internal.domain.EInvoiceType.ORIGINAL && provider != null
                && providers.find(provider).map(EInvoiceProvider::picksCorrectionSeries).orElse(false);
    }

    /**
     * The XML and the PDF stored under the invoice's number, then locked for ten years with the agreement, if any. All
     * stored before any is locked (BE-57).
     */
    private StoredFile[] storeAndRetain(EInvoice invoice, int number, EInvoiceProvider.Documents documents) {
        String name = "%s%s-%08d".formatted(invoice.templateCode(), invoice.symbol(), number);
        StoredFile xml = store(name + ".xml", "application/xml", documents.xml());
        StoredFile pdf = store(name + ".pdf", "application/pdf", documents.pdf());
        files.retainNow(xml.key(), RecordRetention.EINVOICE);
        files.retainNow(pdf.key(), RecordRetention.EINVOICE);
        if (invoice.agreementFileKey() != null) {
            files.retainNow(invoice.agreementFileKey(), RecordRetention.EINVOICE);
        }
        return new StoredFile[] {xml, pdf};
    }

    /** What issuing tells, in the transaction that issued it. */
    private void announceIssued(EInvoice.Snapshot issued) {
        if (invoices.deliveryFailed(issued.id())) {
            events.publishEvent(new DeliveryFailedInvoiceAnswered(issued.id()));
        }
        if (issued.warnings().contains(EInvoice.Warning.SIGNATURE_INVALID)) {
            events.publishEvent(new EInvoiceNeedsAttention(issued.id(), issued.code(), issued.orderId(),
                    issued.orderNumber(), EInvoiceNeedsAttention.Cause.SIGNATURE_INVALID, null, issued.submittedAt(),
                    issued.issueDueAt()));
            return;
        }
        events.publishEvent(new EInvoiceIssued(issued.id(), issued.code(), issued.orderId(), issued.orderNumber(),
                issued.shipmentId(), issued.customerId(), issued.issuedAt()));
    }

    /**
     * Records an invoice stuck at the provider as issued from what the provider's portal shows (WF-05 BE-08, decision
     * Q-8; {@link EInvoiceIssuance#recordPortalResult}): SUBMITTING → ISSUED with the number, codes and files the
     * accountant gives, {@code issued_via} PORTAL_RECORD, the corrected invoice marked as {@link #issued} marks it.
     * The caller has checked them against a fresh look-up and the XML itself, outside this transaction; here the
     * invoice must still wait for {@code transactionId} - a provider's answer that came in meanwhile wins.
     *
     * @return false when it no longer waits for that transaction
     */
    @Transactional
    public boolean issuedFromPortal(UUID id, String transactionId, EInvoiceProvider.Result result,
                                    EInvoiceProvider.Documents documents, UUID by) {
        EInvoice unlocked = invoices.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND));
        java.util.List<EInvoice> corrected = lockCorrected(unlocked);
        EInvoice invoice = waitingFor(id, transactionId);
        if (invoice == null) {
            return false;
        }
        if (result.symbol() != null && !result.symbol().equals(invoice.symbol())) {
            // The caller checked it against the provider's look-up and the XML: only a correction under a provider that
            // chooses its series itself gets here with another symbol (BE-30).
            invoice.numberedUnder(result.symbol());
        }
        markCorrected(invoice, corrected);
        StoredFile[] stored = storeAndRetain(invoice, result.invoiceNumber(), documents);
        invoice.issuedFromPortal(result.invoiceNumber(), result.taxAuthorityCode(), result.lookupCode(),
                result.signedAt(), stored[0].key(), stored[1].key(), by, clock.instant());
        if (InvoiceXmlSignature.check(documents.xml()).invalid()) {
            invoice.signatureInvalid();
        }
        EInvoice.Snapshot issued = invoices.save(invoice).snapshot();
        announceIssued(issued);
        return true;
    }

    /**
     * Records a reduction the accountant made on the provider's portal (WF-05 BE-10, Q-9; decision Q-8): drawn up as a
     * draft marked PORTAL_RECORD, submitted under the series the portal gave it and recorded issued with its files, in
     * this one transaction - it is never sent anywhere, and no job ever finds it waiting. A reduction of a failed
     * delivery marks the invoice it reduces reversed, as the system's own reduction does (when it is not already).
     */
    @Transactional
    public EInvoice.Snapshot recordExternalReduction(EInvoice reduction, InvoiceSeries series, int number,
                                                     String taxAuthorityCode, String lookupCode, Instant signedAt,
                                                     EInvoiceProvider.Documents documents, UUID by) {
        EInvoice original = invoices.findByIdForUpdate(reduction.originalEInvoiceId()).orElseThrow();
        EInvoice draft = invoices.save(reduction);
        draft.submit(series, by, clock.instant());
        draft = invoices.save(draft);
        if (!original.adjusted()) {
            original.markAdjusted();
            invoices.save(original);
        }
        StoredFile[] stored = storeAndRetain(draft, number, documents);
        draft.issuedFromPortal(number, taxAuthorityCode, lookupCode, signedAt, stored[0].key(), stored[1].key(), by,
                clock.instant());
        if (InvoiceXmlSignature.check(documents.xml()).invalid()) {
            draft.signatureInvalid();
        }
        EInvoice.Snapshot recorded = invoices.save(draft).snapshot();
        if (reduction.correctionBasis() == com.stockflow.invoicing.internal.domain.CorrectionBasis.DELIVERY_FAILED
                && original.reversedAt() == null) {
            invoices.markReversed(original.id(), clock.instant());
        }
        announceIssued(recorded);
        return recorded;
    }

    /**
     * What the provider holds under a transaction let go and watched (WF-05 BE-07): issued after all. Recorded on the
     * watch - the number, series and tax authority's code - and the accountants told once
     * ({@link EInvoiceNeedsAttention.Cause#DUPLICATE_RISK}): the invoice may stand twice for one delivery, and only they
     * can settle it with the provider.
     */
    @Transactional
    public boolean watchedTransactionIssued(UUID id, String transactionId, EInvoiceProvider.Result result) {
        if (!invoices.markWatchFound(id, transactionId, clock.instant(), result.invoiceNumber(), result.templateCode(),
                result.symbol(), result.taxAuthorityCode())) {
            return false;
        }
        EInvoice.Snapshot invoice = invoices.findById(id).orElseThrow().snapshot();
        events.publishEvent(new EInvoiceNeedsAttention(invoice.id(), invoice.code(), invoice.orderId(),
                invoice.orderNumber(), EInvoiceNeedsAttention.Cause.DUPLICATE_RISK, transactionId,
                invoice.submittedAt(), invoice.issueDueAt()));
        return true;
    }

    /**
     * Up to {@code limit} transactions let go and still watched, not asked about for most of a day (BE-07): asked at
     * most once a day each, whichever instance runs the job.
     */
    @Transactional(readOnly = true)
    public List<EInvoiceRepository.WatchedTransaction> watchedToAsk(int limit) {
        Instant now = clock.instant();
        return invoices.findWatchedToAsk(now, now.minus(WATCH_ASKED_EVERY), limit);
    }

    /** How often a let-go transaction is asked about again: daily, give or take the job's own run. */
    static final Duration WATCH_ASKED_EVERY = Duration.ofHours(20);

    /** A watched transaction was asked about now, and is not issued. */
    @Transactional
    public void watchedTransactionAsked(UUID id, String transactionId) {
        invoices.markWatchAsked(id, transactionId, clock.instant());
    }

    private java.util.List<EInvoice> lockCorrected(EInvoice unlocked) {
        java.util.List<EInvoice> corrected = new java.util.ArrayList<>();
        if (unlocked.originalEInvoiceId() != null) {
            java.util.TreeSet<UUID> ids = new java.util.TreeSet<>(unlocked.listedInvoiceIds());
            ids.add(unlocked.originalEInvoiceId());
            for (UUID correctedId : ids) {
                corrected.add(invoices.findByIdForUpdate(correctedId).orElseThrow());
            }
        }
        return corrected;
    }

    private void markCorrected(EInvoice invoice, java.util.List<EInvoice> corrected) {
        for (EInvoice target : corrected) {
            if (invoice.type() == EInvoiceType.REPLACEMENT) {
                target.replaced();
            } else if (!target.adjusted()) {
                target.markAdjusted();
            } else {
                continue;
            }
            invoices.save(target);
        }
    }

    /**
     * Marks an invoice still waiting for {@code transactionId} as unknown to the provider and no longer sent on its
     * own (package E3). The accountants are told in the same transaction ({@link EInvoiceNeedsAttention}, cause
     * {@code UNKNOWN_TO_PROVIDER}): from here on nothing sends it, so an invoice marked in silence would sit SUBMITTING
     * while its deadline runs. Marked once, so announced once; the stuck-submission notice may also have gone out
     * earlier, and says something else (still waiting, not given up on).
     *
     * @return whether it was marked now, as opposed to already marked or no longer waiting
     */
    @Transactional
    public boolean flagUnknown(UUID id, String transactionId) {
        EInvoice invoice = waitingFor(id, transactionId);
        if (invoice == null || !invoice.flagUnknownToProvider()) {
            return false;
        }
        EInvoice.Snapshot unknown = invoices.save(invoice).snapshot();
        events.publishEvent(new EInvoiceNeedsAttention(unknown.id(), unknown.code(), unknown.orderId(),
                unknown.orderNumber(), EInvoiceNeedsAttention.Cause.UNKNOWN_TO_PROVIDER, null, unknown.submittedAt(),
                unknown.issueDueAt()));
        return true;
    }

    /**
     * Whether the invoice was marked because its delivery was corrected to a failure while it was at the provider
     * ({@link EInvoiceRepository#markDeliveryFailed}).
     */
    @Transactional(readOnly = true)
    public boolean markedDeliveryFailed(UUID id) {
        return invoices.deliveryFailed(id);
    }

    /** When the invoice was last sent to the provider under {@code transactionId}; empty when it never was. */
    @Transactional(readOnly = true)
    public Optional<Instant> lastSent(UUID id, String transactionId) {
        return sending.lastSent(id, transactionId);
    }

    /**
     * Records, and commits, that the invoice is being sent now - before it is: a send the application does not live to
     * see the end of still starts the quiet period ({@link EInvoiceProvider.Resubmission}). Under the invoice's lock,
     * so nothing is recorded, and nothing is to be sent, once the invoice has moved on from this transaction; and only
     * while {@code holder} still holds the lease, which is renewed so that it outlasts the send.
     *
     * @return whether the invoice is still at the provider under {@code transactionId}, and may be sent by
     *         {@code holder}
     */
    @Transactional
    public boolean aboutToSend(UUID id, String transactionId, UUID holder, Duration lease) {
        // A marked invoice invoices a delivery that failed: it is asked about, never sent (second review, finding 3).
        if (waitingFor(id, transactionId) == null || invoices.deliveryFailed(id) || !sending.renew(id, holder, lease)) {
            return false;
        }
        sending.sent(id, transactionId, clock.instant());
        return true;
    }

    /**
     * Takes the lease on talking to the provider about the invoice ({@link EInvoiceSending}).
     *
     * @return whether {@code holder} holds it; false while somebody else does
     */
    @Transactional
    public boolean claim(UUID id, UUID holder, Duration lease) {
        return sending.claim(id, holder, lease);
    }

    /** Gives the lease up. */
    @Transactional
    public void release(UUID id, UUID holder) {
        sending.release(id, holder);
    }

    /**
     * REJECTED → DRAFT for an invoice taken back from a provider that holds no such transaction, so it can be sent
     * under a new one. Nothing else is reopened here: an invoice the provider refused is the accountant's to correct.
     */
    @Transactional
    public void reopenUnknown(UUID id) {
        EInvoice invoice = invoices.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND));
        if (invoice.status() == EInvoiceStatus.REJECTED
                && EInvoice.UNKNOWN_TO_PROVIDER_CODE.equals(invoice.snapshot().rejectionCode())) {
            invoice.reopen();
            invoices.save(invoice);
        }
    }

    private StoredFile store(String name, String contentType, byte[] bytes) {
        return files.store(FileCategory.EINVOICE, new FileUpload(name, contentType, bytes.length,
                new ByteArrayInputStream(bytes), Checksums.sha256Hex(bytes)));
    }

    /** The invoice, locked, when it is SUBMITTING under {@code transactionId}; null when it has moved on. */
    private EInvoice waitingFor(UUID id, String transactionId) {
        EInvoice invoice = invoices.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND));
        return invoice.status() == EInvoiceStatus.SUBMITTING && transactionId.equals(invoice.transactionId())
                ? invoice : null;
    }
}
