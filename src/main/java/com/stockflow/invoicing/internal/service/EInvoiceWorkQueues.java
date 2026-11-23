package com.stockflow.invoicing.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
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
import java.time.Duration;
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

    /**
     * How often an invoice past its deadline and still not issued is announced again (HĐ-2): once a day. Counted from
     * the last time the accountants were told - the warning ahead of the deadline included - so a reminder never comes
     * a moment after that warning.
     */
    static final Duration OVERDUE_REMINDER_EVERY = Duration.ofDays(1);

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
     * <p>The mark is written beside the invoice's version, not through it (WF-05 BE-57): it says nothing new about the
     * invoice, and an accountant who had the invoice open - to withdraw it, issue it, send it again - was refused with
     * {@code OPTIMISTIC_LOCK} because a warning came in between. Only a draft, a refused invoice or one at the provider
     * is marked ({@link EInvoiceRepository#markDueWarned}).</p>
     *
     * @return the announcement, or empty when the invoice was issued, voided or told about meanwhile
     */
    @Transactional
    public Optional<EInvoiceIssueDue> warnOfDeadline(UUID id) {
        Optional<EInvoice> found = invoices.findById(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        EInvoice.Snapshot before = found.get().snapshot();
        // Still to be issued: a draft, a rejected one, or one the provider has not answered for.
        if (before.status().wasIssued() || before.status() == EInvoiceStatus.VOIDED_DRAFT
                || before.dueWarnedAt() != null || !invoices.markDueWarned(id, clock.instant())) {
            return Optional.empty();
        }
        EInvoiceIssueDue due = new EInvoiceIssueDue(before.id(), before.code(), before.orderId(), before.orderNumber(),
                before.issueDate(), before.issueDueAt());
        events.publishEvent(due);
        return Optional.of(due);
    }

    /** Invoices past their deadline, still to be issued, whose accountants were last told a day or more ago. */
    @Transactional(readOnly = true)
    public List<UUID> overdueToRemind(int limit) {
        Instant now = clock.instant();
        return invoices.findOverdueToRemind(now, now.minus(OVERDUE_REMINDER_EVERY), limit);
    }

    /**
     * Reminds the accountants, once a day, of an invoice still not issued past its deadline - a draft nobody issued, a
     * rejected one nobody corrected, one stuck at the provider (HĐ-2). The same announcement as the warning ahead of
     * the deadline, carrying the day it is for ({@link EInvoiceIssueDue#overdueOn}), so notification tells each person
     * once a day and not once ever. The mark and the event are written in one transaction.
     *
     * @return the reminder, or empty when the invoice was issued, voided or reminded meanwhile
     */
    @Transactional
    public Optional<EInvoiceIssueDue> remindOverdue(UUID id) {
        Instant now = clock.instant();
        if (!invoices.markOverdueReminded(id, now, now.minus(OVERDUE_REMINDER_EVERY))) {
            return Optional.empty();
        }
        EInvoice.Snapshot invoice = invoices.findById(id).orElseThrow().snapshot();
        EInvoiceIssueDue reminder = new EInvoiceIssueDue(invoice.id(), invoice.code(), invoice.orderId(),
                invoice.orderNumber(), invoice.issueDate(), invoice.issueDueAt(), BusinessCalendar.date(now));
        events.publishEvent(reminder);
        return Optional.of(reminder);
    }

    /**
     * Invoices at the provider for longer than {@code result-overdue-after} (four hours: past every retry SePay
     * documents and the withdrawal's own wait), whose accountants were not told yet (WF-05 BE-08).
     */
    @Transactional(readOnly = true)
    public List<UUID> resultOverdueUnannounced(int limit) {
        return invoices.findResultOverdueUnannounced(clock.instant().minus(properties.resultOverdueAfter()), limit);
    }

    /**
     * Tells the accountants once per submission that the provider's result is overdue (WF-05 BE-08, decision Q-8;
     * {@link EInvoiceNeedsAttention.Cause#PROVIDER_RESULT_OVERDUE}): from here the invoice may be recorded from what the
     * provider's portal shows ({@code EInvoiceIssuance.recordPortalResult}), which is the way out of every waiting
     * state that never settles by itself - "issued" without the tax authority's code, an answer that says nothing, a
     * provider that can no longer be called. The note and the event are written together.
     *
     * @return the announcement, or empty when it moved on or was announced meanwhile
     */
    @Transactional
    public Optional<EInvoiceNeedsAttention> announceResultOverdue(UUID id) {
        Optional<EInvoice> found = invoices.findById(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        EInvoice.Snapshot invoice = found.get().snapshot();
        Instant now = clock.instant();
        if (invoice.status() != EInvoiceStatus.SUBMITTING
                || !invoice.submittedAt().isBefore(now.minus(properties.resultOverdueAfter()))
                || !invoices.markResultOverdueAnnounced(id, invoice.transactionId(), now)) {
            return Optional.empty();
        }
        EInvoiceNeedsAttention overdue = new EInvoiceNeedsAttention(invoice.id(), invoice.code(), invoice.orderId(),
                invoice.orderNumber(), EInvoiceNeedsAttention.Cause.PROVIDER_RESULT_OVERDUE, null,
                invoice.submittedAt(), invoice.issueDueAt());
        events.publishEvent(overdue);
        return Optional.of(overdue);
    }

    /** The provider drafts are issued through now: the one the automatic issuing would send them to. */
    @Transactional(readOnly = true)
    public String activeProvider() {
        return settings.active().provider();
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
