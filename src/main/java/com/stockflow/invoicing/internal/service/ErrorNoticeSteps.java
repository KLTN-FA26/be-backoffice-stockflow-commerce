package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.ErrorNotice;
import com.stockflow.invoicing.internal.domain.ErrorNoticeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * The database steps of a 04/SS-HĐĐT notice, each its own short transaction; {@link EInvoiceErrorNotices} strings them
 * together around the call to the provider, made with no transaction open - as issuing an invoice is
 * ({@link EInvoiceIssuanceSteps}), and for the same reason: "recorded" is committed before anything is sent.
 */
@Component
class ErrorNoticeSteps {

    /** A notice with the invoice it is about. */
    record Loaded(ErrorNotice.Snapshot notice, EInvoice.Snapshot invoice) {
    }

    private final ErrorNoticeRepository notices;
    private final EInvoiceRepository invoices;
    private final InvoicingProperties properties;
    private final Clock clock;

    ErrorNoticeSteps(ErrorNoticeRepository notices, EInvoiceRepository invoices, InvoicingProperties properties,
                     Clock clock) {
        this.notices = notices;
        this.invoices = invoices;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Records the notice, under the invoice's lock: two people notifying one invoice are decided one after the other,
     * and the second finds the first one's notice open.
     */
    @Transactional
    public UUID record(UUID invoiceId, ErrorNotice.Subject subject, String reason, String correctBuyerName,
                       String correctBuyerAddress, UUID by) {
        EInvoice invoice = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND));
        EInvoice.Corrections corrections = invoices.correctionsOf(invoiceId);
        ErrorNotice notice = ErrorNotice.record(Identifiers.newId(), invoice.snapshot(), subject, reason,
                correctBuyerName, correctBuyerAddress, by, clock.instant(),
                properties.corrections().errorNoticeWithCorrection(),
                invoice.adjusted() || invoice.status() == EInvoiceStatus.REPLACED || corrections.liveAdjustment()
                        || corrections.liveReplacement(),
                notices.hasOpen(invoiceId));
        notices.insert(notice);
        return notice.id();
    }

    /**
     * @throws BusinessException {@code EINVOICE_ERROR_NOTICE_NOT_FOUND} for no such notice of this invoice;
     *         {@code EINVOICE_ERROR_NOTICE_NOT_SENDABLE} once it was answered
     */
    @Transactional(readOnly = true)
    public Loaded load(UUID invoiceId, UUID noticeId) {
        ErrorNotice notice = notices.findById(noticeId)
                .filter(found -> found.einvoiceId().equals(invoiceId))
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_ERROR_NOTICE_NOT_FOUND));
        notice.requireOpen();
        return new Loaded(notice.snapshot(), invoices.findById(invoiceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND)).snapshot());
    }

    /** The provider's answer, if the notice still waits for one: a second sending that arrives after it changes nothing. */
    @Transactional
    public ErrorNotice.Snapshot answered(UUID noticeId, EInvoiceProvider.NoticeReceipt receipt) {
        ErrorNotice notice = notices.findByIdForUpdate(noticeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_ERROR_NOTICE_NOT_FOUND));
        if (notice.status() == ErrorNotice.Status.DRAFT || notice.status() == ErrorNotice.Status.SENT) {
            notice.answered(receipt, clock.instant());
            notices.update(notice);
        }
        return notice.snapshot();
    }

    @Transactional(readOnly = true)
    public ErrorNotice.Snapshot get(UUID noticeId) {
        return notices.findById(noticeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_ERROR_NOTICE_NOT_FOUND)).snapshot();
    }
}
