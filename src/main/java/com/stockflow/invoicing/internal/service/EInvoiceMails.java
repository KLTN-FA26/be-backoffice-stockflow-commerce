package com.stockflow.invoicing.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.EInvoiceMailRequested;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends an issued e-invoice to its buyer again, to the customer's invoice e-mail as it is now (WF-05 audit r2 M-1).
 *
 * <p>Why it exists. An invoice is issued on its delivery day even when the customer has no invoice e-mail (decision
 * Q-22 BR-06; the e-mail is not invoice content, NĐ 254/2026 Điều 10 khoản 1), so its mail is skipped and Sales is told
 * to record the address (HĐ-8). The skipped mail is settled for good - the delivery log never reopens a message - and
 * the invoice keeps no address of its own. Without this the buyer never got the invoice by e-mail, while the seller
 * owes it to them (Điều 12 khoản 3; decision §1.5 step 3: "báo Sale bổ sung e-mail rồi gửi lại"). The same command
 * sends it on after a mail went to an address since corrected, or was lost.</p>
 *
 * <p>The request is recorded (audited) and published in one transaction; notification sends it afterwards as a message
 * of its own, retried like the first ({@code EInvoiceMailRequested}). Asked twice within a second, it is one message.
 * Only an invoice its buyer is sent at all ({@link EInvoice.Snapshot#sendableToBuyer}): never a draft, never one whose
 * seller's signature does not hold.</p>
 */
@Service
public class EInvoiceMails {

    private final EInvoiceRepository invoices;
    private final EInvoiceDrafts drafts;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    EInvoiceMails(EInvoiceRepository invoices, EInvoiceDrafts drafts, ApplicationEventPublisher events, Clock clock) {
        this.invoices = invoices;
        this.drafts = drafts;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @param recipient   the address it goes to as things stand now; read again when the mail is sent
     * @param requestedAt when it was asked for
     */
    public record Resend(UUID einvoiceId, String recipient, Instant requestedAt) {
    }

    /**
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}; {@code EINVOICE_NOT_ISSUED} for an invoice never issued;
     *         {@code EINVOICE_INVALID_TRANSITION} (details.reason SIGNATURE_INVALID) for one whose signature does not
     *         hold - it is replaced, not sent; {@code EINVOICE_BUYER_PROFILE_INCOMPLETE} (details.missing [EMAIL])
     *         while neither the customer nor the invoice has an e-mail to send it to
     */
    @Transactional
    @Auditable(action = AuditAction.CREATE, resourceType = "einvoice-mail", resourceId = "#id")
    public Resend resend(UUID id, UUID by) {
        EInvoice.Snapshot invoice = invoices.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND)).snapshot();
        if (!invoice.status().wasIssued()) {
            throw new BusinessException(ErrorCode.EINVOICE_NOT_ISSUED,
                    "Invoice %s is %s: only an issued invoice is sent to the buyer".formatted(invoice.code(),
                            invoice.status()));
        }
        if (!invoice.sendableToBuyer()) {
            throw new BusinessException(ErrorCode.EINVOICE_INVALID_TRANSITION, ("Invoice %s: its seller's signature "
                    + "does not hold; it is replaced, never sent to the buyer").formatted(invoice.code()), List.of(),
                    Map.of("reason", EInvoice.Warning.SIGNATURE_INVALID.name()));
        }
        String current = drafts.currentInvoiceEmail(invoice.customerId());
        String recipient = current != null ? current
                : invoice.buyer().email() == null || invoice.buyer().email().isBlank() ? null
                : invoice.buyer().email();
        if (recipient == null) {
            throw new BusinessException(ErrorCode.EINVOICE_BUYER_PROFILE_INCOMPLETE, ("Invoice %s: the customer has no "
                    + "invoice e-mail to send it to; record it on the customer's legal profile first")
                    .formatted(invoice.code()), List.of(), Map.of("missing", List.of("EMAIL")));
        }
        Instant now = clock.instant();
        events.publishEvent(new EInvoiceMailRequested(invoice.id(), invoice.code(), invoice.orderId(),
                invoice.orderNumber(), invoice.customerId(), now, by));
        return new Resend(invoice.id(), recipient, now);
    }
}
