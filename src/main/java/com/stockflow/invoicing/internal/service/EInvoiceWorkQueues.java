package com.stockflow.invoicing.internal.service;

import com.stockflow.contracts.EInvoiceIssueDue;
import com.stockflow.contracts.EInvoiceNeedsAttention;
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
        // Still to be issued: a draft, a rejected one, or one the provider has not answered for.
        if (before.status().wasIssued() || before.status() == EInvoiceStatus.VOIDED_DRAFT
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

    /** Invoices that have waited for the provider's answer for longer than the configured time, not yet announced. */
    @Transactional(readOnly = true)
    public List<UUID> stuckUnannounced(int limit) {
        return invoices.findStuckUnannounced(clock.instant().minus(properties.stuckAfter()), limit);
    }

    /**
     * Tells the accountants once that a submission is stuck: the note that they were told and the event are written in
     * one transaction, so the two go together or not at all. The invoice itself is not touched - it stays SUBMITTING
     * under the same transaction id, and the reconciliation goes on asking about it.
     *
     * @return the announcement, or empty when the invoice has moved on or this submission was announced meanwhile
     */
    @Transactional
    public Optional<EInvoiceNeedsAttention> announceStuck(UUID id) {
        Optional<EInvoice> found = invoices.findByIdForUpdate(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        EInvoice.Snapshot invoice = found.get().snapshot();
        Instant now = clock.instant();
        if (invoice.status() != EInvoiceStatus.SUBMITTING
                || !invoice.submittedAt().isBefore(now.minus(properties.stuckAfter()))
                || !invoices.markStuckAnnounced(id, invoice.transactionId(), now)) {
            return Optional.empty();
        }
        EInvoiceNeedsAttention stuck = new EInvoiceNeedsAttention(invoice.id(), invoice.code(), invoice.orderId(),
                invoice.orderNumber(), EInvoiceNeedsAttention.Cause.STUCK_SUBMITTING, null, invoice.submittedAt(),
                invoice.issueDueAt());
        events.publishEvent(stuck);
        return Optional.of(stuck);
    }
}
