package com.stockflow.invoicing.internal.service;

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

    EInvoiceIssuanceSteps(EInvoiceRepository invoices, EInvoiceSending sending, InvoiceSettings settings,
                          EInvoiceProviders providers, FileTransfers files, ApplicationEventPublisher events,
                          Clock clock) {
        this.invoices = invoices;
        this.sending = sending;
        this.settings = settings;
        this.providers = providers;
        this.files = files;
        this.events = events;
        this.clock = clock;
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
     * <p>What this does not prevent: an invoice dated before invoices already issued - a delivery recorded late, a
     * draft issued after a later one - is still issued, with the next number and its own date (the delivery's). A rule
     * "no date before the series' last issued one" would leave such an invoice unissuable for good, which the law
     * forbids more plainly than it forbids the order of numbers; it is the exception the accountant can explain.</p>
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
        EInvoice invoice = invoices.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND));
        if (expectedVersion != null && expectedVersion != invoice.version()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Invoice %s changed since it was read".formatted(invoice.code()));
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
        return invoices.save(invoice).snapshot();
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
     */
    @Transactional
    public void rejected(UUID id, String transactionId, String errorCode, String message) {
        EInvoice invoice = waitingFor(id, transactionId);
        if (invoice == null) {
            return;
        }
        invoice.rejected(errorCode == null || errorCode.isBlank() ? "REJECTED" : errorCode, message);
        EInvoice.Snapshot refused = invoices.save(invoice).snapshot();
        if (EInvoice.UNKNOWN_TO_PROVIDER_CODE.equals(refused.rejectionCode())
                || EInvoice.WITHDRAWN_CODE.equals(refused.rejectionCode())) {
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
        events.publishEvent(new EInvoiceNeedsAttention(refused.id(), refused.code(), refused.orderId(),
                refused.orderNumber(), EInvoiceNeedsAttention.Cause.REJECTED, refused.rejectionCode(),
                refused.submittedAt(), refused.issueDueAt()));
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
     * @throws com.stockflow.common.storage.StorageException when a file cannot be stored or locked
     */
    @Transactional
    public void issued(UUID id, String transactionId, EInvoiceProvider.Result result,
                       EInvoiceProvider.Documents documents) {
        EInvoice unlocked = invoices.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND));
        java.util.List<EInvoice> corrected = new java.util.ArrayList<>();
        if (unlocked.originalEInvoiceId() != null) {
            java.util.TreeSet<UUID> ids = new java.util.TreeSet<>(unlocked.listedInvoiceIds());
            ids.add(unlocked.originalEInvoiceId());
            for (UUID correctedId : ids) {
                corrected.add(invoices.findByIdForUpdate(correctedId).orElseThrow());
            }
        }
        EInvoice invoice = waitingFor(id, transactionId);
        if (invoice == null) {
            return;
        }
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
        String name = "%s%s-%08d".formatted(invoice.templateCode(), invoice.symbol(), result.invoiceNumber());
        StoredFile xml = store(name + ".xml", "application/xml", documents.xml());
        StoredFile pdf = store(name + ".pdf", "application/pdf", documents.pdf());
        files.retainNow(xml.key(), RecordRetention.EINVOICE);
        files.retainNow(pdf.key(), RecordRetention.EINVOICE);
        if (invoice.agreementFileKey() != null) {
            files.retainNow(invoice.agreementFileKey(), RecordRetention.EINVOICE);
        }
        invoice.issued(result.invoiceNumber(), result.taxAuthorityCode(), result.lookupCode(), result.signedAt(),
                xml.key(), pdf.key(), clock.instant());
        boolean signed = providers.find(invoice.provider()).map(EInvoiceProvider::signsDocuments).orElse(false);
        if (signed && !InvoiceXmlSignature.check(documents.xml()).valid()) {
            invoice.signatureInvalid();
        }
        EInvoice.Snapshot issued = invoices.save(invoice).snapshot();
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
        if (waitingFor(id, transactionId) == null || !sending.renew(id, holder, lease)) {
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
