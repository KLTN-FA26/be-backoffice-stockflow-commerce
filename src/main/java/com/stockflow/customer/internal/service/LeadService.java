package com.stockflow.customer.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.validation.VietnamPhoneValidator;
import com.stockflow.contracts.LeadFormFromExistingCustomer;
import com.stockflow.contracts.SalesLeadAssigned;
import com.stockflow.contracts.SalesLeadSubmitted;
import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.customer.internal.domain.Lead;
import com.stockflow.customer.internal.domain.LeadChange;
import com.stockflow.customer.internal.domain.LeadRepository;
import com.stockflow.customer.internal.domain.LeadStatus;
import com.stockflow.customer.internal.domain.TaxCode;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.SalesStaff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Leads from the public form (sales-owner plan §10.3 channel B, §10.7): the form, the managers' queue, the Sales'
 * steps, and the hand-over to onboarding when a lead becomes a customer.
 *
 * <h2>The public form tells nobody anything</h2>
 * <p>Whatever happens to a submission, the sender gets the same answer: a lead made, a repeat of one from the last
 * {@link #DUPLICATE_WINDOW} (same tax code or e-mail) kept on that lead's history, a honeypot caught, or the tax code of
 * an existing customer - whose responsible salesperson is told instead ({@link LeadFormFromExistingCustomer}), so a
 * customer cannot pose as a new business to reach another salesperson. The endpoint is rate-limited by address.</p>
 *
 * <h2>Who does what</h2>
 * <p>A manager (a holder of {@code customer-sales-owners:UPDATE}, the FE's "manager" too) sees every lead, assigns and
 * disqualifies; a salesperson sees and works the leads given to them: contacts, disqualifies, converts. Every step
 * locks the lead row and writes its history row in the same transaction.</p>
 */
@Service
@Transactional
public class LeadService {

    private static final Logger log = LoggerFactory.getLogger(LeadService.class);

    /** Plan §10.7: a second submission with the same tax code or e-mail within this window makes no new lead. */
    static final Duration DUPLICATE_WINDOW = Duration.ofHours(24);

    /**
     * P5: how many repeats of the form one lead keeps per window. Enough for a business and whoever posed as it to be
     * seen side by side; a flood beyond it (the form is limited per address, not per tax code) is dropped.
     */
    static final int RESUBMISSIONS_KEPT = 10;

    /** {@code customer.lead_history.note}: what a history entry holds. */
    private static final int NOTE_MAX = 1000;

    private final LeadRepository leads;
    private final CustomerRepository customers;
    private final SalesStaff salesStaff;
    private final IdentityService identity;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    LeadService(LeadRepository leads, CustomerRepository customers, SalesStaff salesStaff, IdentityService identity,
                ApplicationEventPublisher events, Clock clock) {
        this.leads = leads;
        this.customers = customers;
        this.salesStaff = salesStaff;
        this.identity = identity;
        this.events = events;
        this.clock = clock;
    }

    /** A lead as the screens show it (BO-1 row fields). */
    public record LeadRow(UUID id, String companyName, String taxCode, String contactName, String contactTitle,
                          String phone, String email, String province, List<Lead.Interest> interests,
                          String monthlyVolume, String note, LeadStatus status, UUID assignedTo,
                          String assignedToName, Instant assignedAt, Instant contactedAt, String contactNote,
                          String disqualifyReason, UUID customerId, Instant createdAt, long version) {
    }

    /**
     * One row of a lead's history. {@code kind} RESUBMITTED: the same business sent the form again (P5), what it said
     * is the note, and the status and Sales did not change.
     */
    public record HistoryRow(LeadStatus fromStatus, LeadStatus toStatus, UUID fromUserId, String fromName,
                             UUID toUserId, String toName, String note, UUID changedById, String changedByName,
                             Instant changedAt, LeadChange.Kind kind) {
    }

    /** Why a submission made no lead; for the log and the tests, never for the sender. */
    public enum Outcome { CREATED, HONEYPOT, DUPLICATE, EXISTING_CUSTOMER }

    /**
     * The public form.
     *
     * <p>Finding S1: every step that reads the form - normalising it, building the lead and its checks - runs before
     * anything that depends on who the business is, so a body that is refused is refused the same way whether its tax
     * code is a customer's or not; and the controller answers the same {@code 202} for whatever happens after the body
     * was accepted. The branches then are:</p>
     * <ul>
     *   <li>the honeypot filled in: nothing;</li>
     *   <li>the tax code of a customer: its salesperson is told ({@link LeadFormFromExistingCustomer}) at most once per
     *       {@link #DUPLICATE_WINDOW} per customer (finding S4: the window that limits leads limits these notices
     *       too);</li>
     *   <li>the same tax code or e-mail as a lead still open from the last {@link #DUPLICATE_WINDOW}: no second lead,
     *       but the later form is kept on that lead's history (finding P5), at most {@link #RESUBMISSIONS_KEPT}
     *       times;</li>
     *   <li>otherwise a new lead.</li>
     * </ul>
     *
     * @param honeypot the hidden {@code website} field: a person never fills it in
     */
    public Outcome submit(Lead.Form form, String honeypot) {
        Instant now = clock.instant();
        Lead.Form stored = normalised(form);
        Lead.Submitted submitted = Lead.submit(stored, now);
        String resubmission = resubmissionNote(stored);
        if (honeypot != null && !honeypot.isBlank()) {
            log.info("Lead form dropped: the honeypot was filled in");
            return Outcome.HONEYPOT;
        }
        Instant since = now.minus(DUPLICATE_WINDOW);
        Optional<UUID> existing = customers.idByTaxCode(stored.taxCode());
        if (existing.isPresent()) {
            if (leads.claimExistingCustomerNotice(existing.get(), now, since)) {
                events.publishEvent(new LeadFormFromExistingCustomer(existing.get(), now));
                log.info("Lead form named the tax code of customer {}: its salesperson is told, no lead",
                        existing.get());
            } else {
                log.info("Lead form named the tax code of customer {} again within 24 hours: nobody told again",
                        existing.get());
            }
            return Outcome.EXISTING_CUSTOMER;
        }
        Optional<Lead> earlier = leads.latestSubmittedSince(stored.taxCode(), stored.email(), since);
        if (earlier.isPresent() && earlier.get().isOpen()) {
            Lead lead = earlier.get();
            if (leads.countResubmissionsSince(lead.id(), since) < RESUBMISSIONS_KEPT) {
                leads.append(lead.resubmitted(resubmission, now));
                log.info("Lead form repeated lead {} within 24 hours: kept on its history", lead.id());
            } else {
                log.info("Lead form repeated lead {} within 24 hours past {} repeats: dropped", lead.id(),
                        RESUBMISSIONS_KEPT);
            }
            return Outcome.DUPLICATE;
        }
        leads.insert(submitted.lead(), submitted.change());
        events.publishEvent(new SalesLeadSubmitted(submitted.lead().id(), now));
        return Outcome.CREATED;
    }

    /** The form as stored: tax code, e-mail and phone in their canonical forms. */
    private static Lead.Form normalised(Lead.Form form) {
        String taxCode = form.taxCode() == null ? null : TaxCode.normalise(form.taxCode());
        String email = form.email() == null ? null : form.email().strip().toLowerCase(Locale.ROOT);
        String phone = form.phone() == null ? null : VietnamPhoneValidator.normalise(form.phone());
        return new Lead.Form(form.companyName().strip(), taxCode, form.contactName().strip(), form.contactTitle(),
                phone, email, form.province(), form.interests(), form.monthlyVolume(), form.note());
    }

    /**
     * What a repeated form said, for the lead's history (P5). A stored note, so its labels are English like every
     * system note of a history; the values are the sender's, as on the lead itself.
     */
    static String resubmissionNote(Lead.Form form) {
        StringBuilder note = new StringBuilder("The form was sent again: company ").append(form.companyName());
        appendIf(note, "tax code", form.taxCode());
        note.append("; contact ").append(form.contactName());
        if (form.contactTitle() != null && !form.contactTitle().isBlank()) {
            note.append(" (").append(form.contactTitle().strip()).append(')');
        }
        appendIf(note, "phone", form.phone());
        appendIf(note, "e-mail", form.email());
        appendIf(note, "province", form.province());
        if (!form.interests().isEmpty()) {
            appendIf(note, "interests", form.interests().stream().map(Enum::name).collect(Collectors.joining(", ")));
        }
        appendIf(note, "monthly volume", form.monthlyVolume());
        appendIf(note, "note", form.note());
        return note.length() <= NOTE_MAX ? note.toString() : note.substring(0, NOTE_MAX);
    }

    private static void appendIf(StringBuilder note, String label, String value) {
        if (value != null && !value.isBlank()) {
            note.append("; ").append(label).append(' ').append(value.strip());
        }
    }

    /** A manager sees every lead; anyone else, the leads given to them ({@code assignedTo} is ignored for them). */
    @Transactional(readOnly = true)
    public PageResponse<LeadRow> list(LeadStatus status, UUID assignedTo, int page, int size, UUID viewer) {
        UUID whose = managesLeads(viewer) ? assignedTo : Objects.requireNonNull(viewer, "viewer");
        int bounded = Math.max(1, Math.min(size, 100));
        int from = Math.max(0, page);
        List<Lead> found = leads.list(status, whose, from, bounded);
        return PageResponse.of(rows(found), from, bounded, leads.count(status, whose));
    }

    /** @throws BusinessException {@code LEAD_NOT_FOUND}; {@code FORBIDDEN} for another salesperson's lead */
    @Transactional(readOnly = true)
    public LeadRow get(UUID leadId, UUID viewer) {
        Lead lead = leads.findById(leadId).orElseThrow(() -> new BusinessException(ErrorCode.LEAD_NOT_FOUND));
        requireSees(lead, viewer);
        return rows(List.of(lead)).get(0);
    }

    /** The lead's history, oldest first. Same access as {@link #get}. */
    @Transactional(readOnly = true)
    public List<HistoryRow> history(UUID leadId, UUID viewer) {
        Lead lead = leads.findById(leadId).orElseThrow(() -> new BusinessException(ErrorCode.LEAD_NOT_FOUND));
        requireSees(lead, viewer);
        List<LeadChange> changes = leads.history(leadId);
        Map<UUID, String> names = names(changes.stream().flatMap(change ->
                java.util.stream.Stream.of(change.fromUserId(), change.toUserId(), change.changedBy())).toList());
        return changes.stream().map(change -> new HistoryRow(change.fromStatus(), change.toStatus(),
                change.fromUserId(), names.get(change.fromUserId()), change.toUserId(), names.get(change.toUserId()),
                change.note(), change.changedBy(), names.get(change.changedBy()), change.changedAt(), change.kind()))
                .toList();
    }

    /**
     * A manager gives the lead to a salesperson (§10.7, §10.4).
     *
     * @throws BusinessException {@code FORBIDDEN} for anyone but a manager; {@code LEAD_NOT_FOUND};
     *         {@code SALES_OWNER_NOT_ASSIGNABLE} when the one named is not an active Sales agent (D42);
     *         {@code LEAD_ALREADY_HANDLED} once converted or disqualified
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "sales-lead", resourceId = "#leadId")
    public LeadRow assign(UUID leadId, UUID salesUserId, UUID actor) {
        if (!managesLeads(actor)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "A Sales Manager assigns leads");
        }
        Lead lead = lock(leadId);
        if (salesUserId == null || !salesStaff.isActiveSalesAgent(salesUserId)) {
            throw new BusinessException(ErrorCode.SALES_OWNER_NOT_ASSIGNABLE,
                    "Account %s is not an active salesperson".formatted(salesUserId));
        }
        Instant now = clock.instant();
        Optional<LeadChange> change = lead.assign(salesUserId, actor, now);
        if (change.isPresent()) {
            leads.update(lead, change.get());
            events.publishEvent(new SalesLeadAssigned(lead.id(), salesUserId, now));
        }
        return reread(leadId);
    }

    /**
     * The lead's salesperson reached the business.
     *
     * @throws BusinessException {@code FORBIDDEN} for anyone but the lead's salesperson; {@code LEAD_NOT_FOUND};
     *         {@code LEAD_NOT_ASSIGNED}; {@code LEAD_ALREADY_HANDLED}
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "sales-lead", resourceId = "#leadId")
    public LeadRow contact(UUID leadId, String note, UUID actor) {
        Lead lead = lock(leadId);
        if (lead.assignedTo() != null && !lead.assignedTo().equals(actor)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Only the lead's salesperson records the contact");
        }
        leads.update(lead, lead.markContacted(note, actor, clock.instant()));
        return reread(leadId);
    }

    /**
     * A manager, or the lead's salesperson, closes the lead with a reason.
     *
     * @throws BusinessException {@code FORBIDDEN}; {@code LEAD_NOT_FOUND}; {@code VALIDATION_FAILED} without a reason;
     *         {@code LEAD_ALREADY_HANDLED}
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "sales-lead", resourceId = "#leadId")
    public LeadRow disqualify(UUID leadId, String reason, UUID actor) {
        Lead lead = lock(leadId);
        if (!managesLeads(actor) && (lead.assignedTo() == null || !lead.assignedTo().equals(actor))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "A Sales Manager or the lead's salesperson disqualifies it");
        }
        leads.update(lead, lead.disqualify(reason, actor, clock.instant()));
        return reread(leadId);
    }

    /**
     * Onboarding, about to make a customer from the lead: locks it and checks it may be converted by {@code actor} (its
     * salesperson, or a manager). Joins onboarding's transaction; {@link #converted} closes it there.
     *
     * @return the lead, its salesperson to be the customer's first owner
     * @throws BusinessException {@code LEAD_NOT_FOUND}; {@code LEAD_ALREADY_HANDLED}; {@code LEAD_NOT_ASSIGNED};
     *         {@code FORBIDDEN} for another salesperson
     */
    public Lead lockForConversion(UUID leadId, UUID actor) {
        Lead lead = lock(leadId);
        if (!lead.isOpen()) {
            throw new BusinessException(ErrorCode.LEAD_ALREADY_HANDLED);
        }
        if (lead.assignedTo() == null) {
            throw new BusinessException(ErrorCode.LEAD_NOT_ASSIGNED);
        }
        if (!lead.assignedTo().equals(actor) && !managesLeads(actor)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Only the lead's salesperson or a manager converts it");
        }
        return lead;
    }

    /** The lead became {@code customerId}: CONVERTED, with its history row, in onboarding's transaction. */
    public void converted(Lead lead, UUID customerId, UUID actor) {
        leads.update(lead, lead.convert(customerId, actor, clock.instant()));
    }

    private Lead lock(UUID leadId) {
        if (leadId == null) {
            throw new BusinessException(ErrorCode.LEAD_NOT_FOUND);
        }
        return leads.findForUpdate(leadId).orElseThrow(() -> new BusinessException(ErrorCode.LEAD_NOT_FOUND));
    }

    /** The lead as written, with the version the write gave it. */
    private LeadRow reread(UUID leadId) {
        return rows(List.of(leads.findById(leadId).orElseThrow())).get(0);
    }

    private void requireSees(Lead lead, UUID viewer) {
        if (!managesLeads(viewer) && (lead.assignedTo() == null || !lead.assignedTo().equals(viewer))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "A salesperson sees the leads given to them");
        }
    }

    private boolean managesLeads(UUID userId) {
        return userId != null && salesStaff.holdsPermission(userId, SalesOwnershipService.MANAGES_OWNERS);
    }

    private List<LeadRow> rows(List<Lead> found) {
        Map<UUID, String> names = names(found.stream().map(Lead::assignedTo).toList());
        return found.stream().map(lead -> {
            Lead.Form form = lead.form();
            return new LeadRow(lead.id(), form.companyName(), form.taxCode(), form.contactName(), form.contactTitle(),
                    form.phone(), form.email(), form.province(), form.interests(), form.monthlyVolume(), form.note(),
                    lead.status(), lead.assignedTo(), names.get(lead.assignedTo()), lead.assignedAt(),
                    lead.contactedAt(), lead.contactNote(), lead.disqualifiedReason(), lead.convertedCustomerId(),
                    lead.submittedAt(), lead.version());
        }).toList();
    }

    private Map<UUID, String> names(Collection<UUID> userIds) {
        Set<UUID> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? new java.util.HashMap<>() : new java.util.HashMap<>(identity.displayNames(ids));
    }
}
