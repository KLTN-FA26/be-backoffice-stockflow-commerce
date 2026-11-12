package com.stockflow.invoicing.internal.service;

import com.stockflow.contracts.EInvoiceIssueDue;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.InvoiceSettings;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the invoicing jobs read and write, each in a transaction of its own; {@link EInvoiceJobs} decides when. A
 * separate bean because the jobs call the provider between these steps, with no transaction open.
 */
@Component
class EInvoiceWorkQueues {

    private final EInvoiceRepository invoices;
    private final InvoiceSettings settings;
    private final InvoicingProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    EInvoiceWorkQueues(EInvoiceRepository invoices, InvoiceSettings settings, InvoicingProperties properties,
                       ApplicationEventPublisher events, Clock clock) {
        this.invoices = invoices;
        this.settings = settings;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UUID> submitting(int limit) {
        return invoices.findSubmitting(limit);
    }

    /** Drafts to issue without the accountant; none while automatic issuing is off. */
    @Transactional(readOnly = true)
    public List<UUID> autoIssuable(int limit) {
        return settings.active().autoIssue() ? invoices.findAutoIssuable(limit) : List.of();
    }

    /** Invoices still to issue whose deadline comes within the warning lead, and whose accountant was not told. */
    @Transactional(readOnly = true)
    public List<UUID> dueUnwarned(int limit) {
        return invoices.findDueUnwarned(clock.instant().plus(properties.dueWarningLead()), limit);
    }

    /**
     * Tells the accountants once that an invoice's deadline is near: the invoice is marked and the event published in
     * one transaction, so the mark and the message go together or not at all.
     *
     * @return the announcement, or empty when the invoice was issued, voided or told about meanwhile
     */
    @Transactional
    public Optional<EInvoiceIssueDue> warnOfDeadline(UUID id) {
        Optional<EInvoice> found = invoices.findByIdForUpdate(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        EInvoice invoice = found.get();
        EInvoice.Snapshot before = invoice.snapshot();
        if ((before.status() != EInvoiceStatus.DRAFT && before.status() != EInvoiceStatus.REJECTED)
                || before.dueWarnedAt() != null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        invoice.warnedOfDeadline(now);
        invoices.save(invoice);
        EInvoiceIssueDue due = new EInvoiceIssueDue(before.id(), before.code(), before.orderId(), before.orderNumber(),
                before.issueDate(), before.issueDueAt());
        events.publishEvent(due);
        return Optional.of(due);
    }
}
