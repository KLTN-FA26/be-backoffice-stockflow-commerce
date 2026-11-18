package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.EInvoiceErrorNoticeRecorded;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.ErrorNotice;
import com.stockflow.invoicing.internal.domain.ErrorNoticeRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
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
    private final EInvoiceProviders providers;
    private final InvoicingProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ErrorNoticeSteps(ErrorNoticeRepository notices, EInvoiceRepository invoices, EInvoiceProviders providers,
                     InvoicingProperties properties, ApplicationEventPublisher events, Clock clock) {
        this.notices = notices;
        this.invoices = invoices;
        this.providers = providers;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @throws BusinessException {@code EINVOICE_ERROR_NOTICE_NOT_SUPPORTED} when the provider the invoice was issued
     *         through cannot send a notice (HĐ-6): it is filed on a portal by hand, and nothing here records it as sent
     */
    void requireSendable(EInvoice.Snapshot invoice) {
        if (!providers.find(invoice.provider()).map(EInvoiceProvider::sendsErrorNotices).orElse(false)) {
            throw new BusinessException(ErrorCode.EINVOICE_ERROR_NOTICE_NOT_SUPPORTED, ("Invoice %s was issued through "
                    + "%s, which cannot send form %s; file it on the provider's or the tax authority's portal").formatted(
                    invoice.code(), invoice.provider(), ErrorNotice.FORM_NAME));
        }
    }

    /**
     * Records the notice, under the invoice's lock: two people notifying one invoice are decided one after the other,
     * and the second finds the first one's notice open.
     *
     * <p>Only for an invoice whose provider can send it ({@link #requireSendable}, HĐ-6). One that cannot used to be
     * recorded anyway, stay a draft for good, hold back every later notice of the invoice
     * ({@code uk_einvoice_error_notice_open}), and tell the buyer the tax authority had been notified.</p>
     *
     * <p>The buyer is told of a notice of a wrong buyer name or address once it was <b>sent</b> ({@link #answered}),
     * not when it is recorded: what the buyer reads is that the tax authority was notified, and that is true only
     * then.</p>
     *
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}; {@code EINVOICE_ERROR_NOTICE_NOT_SUPPORTED}; and what
     *         {@link ErrorNotice#record} refuses
     */
    @Transactional
    public UUID record(UUID invoiceId, ErrorNotice.Subject subject, String reason, String correctBuyerName,
                       String correctBuyerAddress, UUID by) {
        EInvoice invoice = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND));
        requireSendable(invoice.snapshot());
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

    /**
     * The provider's answer, if the notice still waits for one: a second sending that arrives after it changes nothing.
     *
     * <p>A notice of a wrong buyer name or address that the provider took in now - a draft become SENT or ACCEPTED -
     * publishes {@link EInvoiceErrorNoticeRecorded} in the same transaction: the law asks that the buyer be told and the
     * tax authority notified, side by side (TT 91 Điều 10 khoản 1 điểm a), and the buyer is told the tax authority was
     * notified, so only once it was (HĐ-6). Not once the tax authority answers, which may take days and changes nothing
     * about the invoice being wrong; and not for a notice refused outright, which notified nobody. A notice beside an
     * adjustment or a replacement publishes nothing: that correction's own issued invoice tells the buyer.</p>
     */
    @Transactional
    public ErrorNotice.Snapshot answered(UUID noticeId, EInvoiceProvider.NoticeReceipt receipt) {
        ErrorNotice notice = notices.findByIdForUpdate(noticeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_ERROR_NOTICE_NOT_FOUND));
        ErrorNotice.Status before = notice.status();
        if (before == ErrorNotice.Status.DRAFT || before == ErrorNotice.Status.SENT) {
            notice.answered(receipt, clock.instant());
            notices.update(notice);
        }
        ErrorNotice.Snapshot answered = notice.snapshot();
        if (before == ErrorNotice.Status.DRAFT && (answered.status() == ErrorNotice.Status.SENT
                || answered.status() == ErrorNotice.Status.ACCEPTED)
                && answered.subject() == ErrorNotice.Subject.BUYER_NAME_ADDRESS) {
            EInvoice.Snapshot issued = invoices.findById(answered.einvoiceId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND)).snapshot();
            events.publishEvent(new EInvoiceErrorNoticeRecorded(answered.id(), issued.id(), issued.code(),
                    issued.orderId(), issued.orderNumber(), issued.customerId(), answered.createdAt()));
        }
        return answered;
    }

    /**
     * The notices a buyer is told of - wrong name or address - of one invoice issued to it, oldest first: those the
     * provider took in. A draft notified nobody yet, and is not shown as if it had (HĐ-6).
     *
     * @throws BusinessException {@code EINVOICE_NOT_FOUND} for another customer's invoice, one never issued, and no
     *         invoice alike, as on every portal read of an invoice: the customer learns nothing of invoices that are not
     *         its own
     */
    @Transactional(readOnly = true)
    public List<ErrorNotice.Snapshot> toBuyer(UUID customerId, UUID invoiceId) {
        invoices.findById(invoiceId).map(EInvoice::snapshot)
                .filter(invoice -> invoice.customerId() != null && invoice.customerId().equals(customerId))
                .filter(invoice -> invoice.status().wasIssued())
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND));
        return notices.findByInvoice(invoiceId).stream()
                .filter(notice -> notice.subject() == ErrorNotice.Subject.BUYER_NAME_ADDRESS)
                .filter(notice -> notice.status() != ErrorNotice.Status.DRAFT)
                .toList();
    }

    @Transactional(readOnly = true)
    public ErrorNotice.Snapshot get(UUID noticeId) {
        return notices.findById(noticeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_ERROR_NOTICE_NOT_FOUND)).snapshot();
    }
}
