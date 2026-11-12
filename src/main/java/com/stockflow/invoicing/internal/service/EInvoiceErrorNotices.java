package com.stockflow.invoicing.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.common.storage.Checksums;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ErrorNoticeDocument;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.NoticeReceipt;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ProviderUnavailableException;
import com.stockflow.invoicing.internal.domain.ErrorNotice;
import com.stockflow.invoicing.internal.domain.ErrorNoticeRepository;
import com.stockflow.invoicing.internal.domain.ProviderCallLog;
import com.stockflow.invoicing.internal.domain.ProviderCallLog.Operation;
import com.stockflow.invoicing.internal.domain.ProviderCallLog.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Notices that an issued invoice was drawn up wrongly - Mẫu 04/SS-HĐĐT (WF-05 E4; TT 91/2026/TT-BTC Điều 10 khoản 1
 * điểm a). For a wrong buyer name or address with the right tax code the law asks for <b>no new invoice</b>: the
 * accountant records what should have been written, and the notice goes to the tax authority through the provider.
 * The invoice itself is not touched.
 *
 * <p>Recorded first, committed, then sent - with no transaction open while the provider is called. A provider that
 * does not answer leaves the notice a draft, to be sent again ({@link #send}); the notice's own id is its reference at
 * the provider, so a second sending is the first again. A provider with no call for notices leaves it a draft too: it
 * is then filed with the tax authority by hand, and the record of what was notified is here either way.</p>
 */
@Service
public class EInvoiceErrorNotices {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceErrorNotices.class);

    /** What the accountant notifies: the buyer's name and address as they should have been, and why. */
    public record Record(UUID invoiceId, ErrorNotice.Subject subject, String reason, String correctBuyerName,
                         String correctBuyerAddress) {
    }

    private final ErrorNoticeSteps steps;
    private final ErrorNoticeRepository notices;
    private final EInvoiceProviders providers;
    private final ProviderCallRecorder calls;
    private final Clock clock;

    EInvoiceErrorNotices(ErrorNoticeSteps steps, ErrorNoticeRepository notices, EInvoiceProviders providers,
                         ProviderCallRecorder calls, Clock clock) {
        this.steps = steps;
        this.notices = notices;
        this.providers = providers;
        this.calls = calls;
        this.clock = clock;
    }

    /**
     * Records a notice of an issued invoice and sends it.
     *
     * @throws com.stockflow.common.error.BusinessException {@code EINVOICE_NOT_FOUND}, and what {@link ErrorNotice#record} refuses
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "einvoice-error-notice", resourceId = "#command.invoiceId()")
    public ErrorNotice.Snapshot record(Record command, UUID by) {
        UUID noticeId = steps.record(command.invoiceId(), command.subject() == null
                        ? ErrorNotice.Subject.BUYER_NAME_ADDRESS : command.subject(), command.reason(),
                command.correctBuyerName(), command.correctBuyerAddress(), by);
        return send(command.invoiceId(), noticeId);
    }

    /**
     * Sends a notice that is still open - a draft the provider never got, or one waiting for its answer - and records
     * what the provider says.
     *
     * @throws com.stockflow.common.error.BusinessException {@code EINVOICE_ERROR_NOTICE_NOT_FOUND}; {@code EINVOICE_ERROR_NOTICE_NOT_SENDABLE}
     *         once it was answered; {@code EINVOICE_PROVIDER_UNAVAILABLE} when the provider the invoice was issued
     *         through is not in this application
     */
    public ErrorNotice.Snapshot send(UUID invoiceId, UUID noticeId) {
        ErrorNoticeSteps.Loaded loaded = steps.load(invoiceId, noticeId);
        EInvoice.Snapshot invoice = loaded.invoice();
        ErrorNotice.Snapshot notice = loaded.notice();
        EInvoiceProvider provider = providers.require(invoice.provider());
        ErrorNoticeDocument document = new ErrorNoticeDocument(notice.id().toString(), notice.formCode(),
                invoice.taxAuthorityCode(), invoice.templateCode(), invoice.symbol(), invoice.invoiceNumber(),
                invoice.issueDate(), invoice.seller(), invoice.buyer().taxCode(), notice.reason(),
                notice.correctBuyerName(), notice.correctBuyerAddress());
        Instant started = clock.instant();
        NoticeReceipt receipt;
        try {
            receipt = provider.notifyError(document);
        } catch (RuntimeException failure) {
            log(invoice, notice, failure instanceof ProviderUnavailableException ? Outcome.TIMEOUT : Outcome.ERROR,
                    failure instanceof ProviderUnavailableException ? null : failure.getClass().getSimpleName(),
                    document, null, started);
            log.warn("Notice {} of e-invoice {}: the provider did not answer; it stays a draft to be sent again",
                    notice.id(), LogSafe.text(invoice.code()));
            return steps.get(noticeId);
        }
        log(invoice, notice, switch (receipt.outcome()) {
            case PENDING -> Outcome.PENDING;
            case ACCEPTED -> Outcome.ACCEPTED;
            case REJECTED -> Outcome.REJECTED;
            case NOT_SUPPORTED -> Outcome.NOT_SUPPORTED;
        }, receipt.outcome() == EInvoiceProvider.NoticeOutcome.REJECTED ? receipt.code() : null, document, receipt,
                started);
        return steps.answered(noticeId, receipt);
    }

    /** An invoice's notices, oldest first. */
    @Transactional(readOnly = true)
    public List<ErrorNotice.Snapshot> of(UUID invoiceId) {
        return notices.findByInvoice(invoiceId);
    }

    /**
     * The notices of a wrong buyer name or address of an invoice issued to {@code customerId}: what the portal shows the
     * buyer beside the invoice, the law having it told of them.
     *
     * @throws BusinessException {@code EINVOICE_NOT_FOUND} for an invoice that is not the customer's own and issued
     */
    public List<ErrorNotice.Snapshot> toBuyer(UUID customerId, UUID invoiceId) {
        return steps.toBuyer(customerId, invoiceId);
    }

    /** Hashes only, as for every call to the provider: a notice names a buyer. */
    private void log(EInvoice.Snapshot invoice, ErrorNotice.Snapshot notice, Outcome outcome, String errorCode,
                     ErrorNoticeDocument document, NoticeReceipt receipt, Instant started) {
        try {
            calls.record(new ProviderCallLog.Call(invoice.id(), invoice.provider(), notice.id().toString(),
                    Operation.NOTICE, outcome, null, errorCode, hash(document.toString()),
                    receipt == null ? null : hash(receipt.toString()), started,
                    Math.max(0, Duration.between(started, clock.instant()).toMillis())));
        } catch (RuntimeException logFailure) {
            log.error("Could not log the NOTICE call for e-invoice {}", LogSafe.text(invoice.code()), logFailure);
        }
    }

    private static String hash(String text) {
        return Checksums.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }
}
