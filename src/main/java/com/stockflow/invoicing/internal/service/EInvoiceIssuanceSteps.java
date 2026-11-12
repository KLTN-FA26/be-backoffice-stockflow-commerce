package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.Checksums;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.RecordRetention;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.InvoiceSeries;
import com.stockflow.invoicing.internal.domain.InvoiceSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.Clock;
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
    private final InvoiceSettings settings;
    private final EInvoiceProviders providers;
    private final FileTransfers files;
    private final Clock clock;

    EInvoiceIssuanceSteps(EInvoiceRepository invoices, InvoiceSettings settings, EInvoiceProviders providers,
                          FileTransfers files, Clock clock) {
        this.invoices = invoices;
        this.settings = settings;
        this.providers = providers;
        this.files = files;
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
     * @param expectedVersion the version the caller read; null when the system issues
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}, {@code OPTIMISTIC_LOCK}, {@code EINVOICE_INVALID_TRANSITION},
     *         {@code EINVOICE_BUYER_PROFILE_INCOMPLETE}, {@code EINVOICE_SERIES_NOT_CONFIGURED},
     *         {@code EINVOICE_PROVIDER_UNAVAILABLE}
     */
    @Transactional
    public EInvoice.Snapshot submit(UUID id, Long expectedVersion, UUID by) {
        EInvoice invoice = invoices.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND));
        if (expectedVersion != null && expectedVersion != invoice.version()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Invoice %s changed since it was read".formatted(invoice.code()));
        }
        InvoiceSettings.Provider provider = settings.active();
        // Refused here, not after the status moved: an invoice must not wait on a provider nobody can call.
        providers.require(provider.provider());
        InvoiceSeries series = settings.seriesInUse(provider.provider(), invoice.issueDate().getYear())
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_SERIES_NOT_CONFIGURED,
                        "No series is in use at %s for %d".formatted(provider.provider(),
                                invoice.issueDate().getYear())));
        invoice.submit(series, by, clock.instant());
        return invoices.save(invoice).snapshot();
    }

    /** SUBMITTING → REJECTED, if the invoice still waits for {@code transactionId}. */
    @Transactional
    public void rejected(UUID id, String transactionId, String errorCode, String message) {
        EInvoice invoice = waitingFor(id, transactionId);
        if (invoice == null) {
            return;
        }
        invoice.rejected(errorCode == null || errorCode.isBlank() ? "REJECTED" : errorCode, message);
        invoices.save(invoice);
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
     * @throws com.stockflow.common.storage.StorageException when a file cannot be stored or locked
     */
    @Transactional
    public void issued(UUID id, String transactionId, EInvoiceProvider.Result result,
                       EInvoiceProvider.Documents documents) {
        EInvoice invoice = waitingFor(id, transactionId);
        if (invoice == null) {
            return;
        }
        String name = "%s%s-%08d".formatted(invoice.templateCode(), invoice.symbol(), result.invoiceNumber());
        StoredFile xml = store(name + ".xml", "application/xml", documents.xml());
        StoredFile pdf = store(name + ".pdf", "application/pdf", documents.pdf());
        files.retainNow(xml.key(), RecordRetention.EINVOICE);
        files.retainNow(pdf.key(), RecordRetention.EINVOICE);
        invoice.issued(result.invoiceNumber(), result.taxAuthorityCode(), result.lookupCode(), result.signedAt(),
                xml.key(), pdf.key(), clock.instant());
        invoices.save(invoice);
    }

    /**
     * Marks an invoice still waiting for {@code transactionId} as unknown to the provider and no longer sent on its
     * own (package E3).
     *
     * @return whether it was marked now, as opposed to already marked or no longer waiting
     */
    @Transactional
    public boolean flagUnknown(UUID id, String transactionId) {
        EInvoice invoice = waitingFor(id, transactionId);
        if (invoice == null || !invoice.flagUnknownToProvider()) {
            return false;
        }
        invoices.save(invoice);
        return true;
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
