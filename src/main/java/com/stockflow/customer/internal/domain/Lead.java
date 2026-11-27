package com.stockflow.customer.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A business that asked, through the public form, to open an account (sales-owner plan §10.3 channel B, §10.7):
 * {@code NEW → ASSIGNED → CONTACTED → CONVERTED}, or {@code DISQUALIFIED} with a reason from any open status.
 *
 * <p>Every change returns the history row to keep ({@code customer.lead_history}, append-only) or nothing when nothing
 * changed. The same rules are the CHECKs of {@code customer.lead} (V20270128000120): a Sales from ASSIGNED on and none
 * while NEW, CONVERTED names its customer, DISQUALIFIED says why. Who may take each step (a manager assigns, the lead's
 * Sales contacts it) is the service's: it needs the caller's permissions.</p>
 */
public final class Lead extends AggregateRoot {

    /** What the form offers as interests (the storefront's values, API contract "Additions from the storefront"). */
    public enum Interest { CUP, BOX, BAG, BOWL }

    /**
     * The form as submitted; every text already within the form's limits (the endpoint validates them). A null among
     * the interests is dropped here rather than left for the database to refuse: the public form must answer the same
     * for every well-formed body (finding S1), and a null that reached the insert failed only for a new business.
     */
    public record Form(String companyName, String taxCode, String contactName, String contactTitle, String phone,
                       String email, String province, List<Interest> interests, String monthlyVolume, String note) {

        public Form {
            Objects.requireNonNull(companyName, "companyName");
            Objects.requireNonNull(contactName, "contactName");
            interests = interests == null ? List.of() : interests.stream().filter(Objects::nonNull).distinct().toList();
            if (phone == null && email == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Give a phone number or an e-mail address");
            }
        }
    }

    private final UUID id;
    private final Form form;
    private final Instant submittedAt;
    private LeadStatus status;
    private UUID assignedTo;
    private UUID assignedBy;
    private Instant assignedAt;
    private Instant contactedAt;
    private String contactNote;
    private UUID convertedCustomerId;
    private Instant convertedAt;
    private String disqualifiedReason;
    private UUID disqualifiedBy;
    private Instant disqualifiedAt;
    private final long version;

    public Lead(UUID id, Form form, Instant submittedAt, LeadStatus status, UUID assignedTo, UUID assignedBy,
                Instant assignedAt, Instant contactedAt, String contactNote, UUID convertedCustomerId,
                Instant convertedAt, String disqualifiedReason, UUID disqualifiedBy, Instant disqualifiedAt,
                long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.form = Objects.requireNonNull(form, "form");
        this.submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
        this.status = Objects.requireNonNull(status, "status");
        this.assignedTo = assignedTo;
        this.assignedBy = assignedBy;
        this.assignedAt = assignedAt;
        this.contactedAt = contactedAt;
        this.contactNote = contactNote;
        this.convertedCustomerId = convertedCustomerId;
        this.convertedAt = convertedAt;
        this.disqualifiedReason = disqualifiedReason;
        this.disqualifiedBy = disqualifiedBy;
        this.disqualifiedAt = disqualifiedAt;
        this.version = version;
    }

    /** A lead as the form makes it: NEW, nobody's yet; the history's first row has no actor (the public form). */
    public static Submitted submit(Form form, Instant at) {
        Lead lead = new Lead(Identifiers.newId(), form, at, LeadStatus.NEW, null, null, null, null, null, null, null,
                null, null, null, 0L);
        return new Submitted(lead, new LeadChange(Identifiers.newId(), lead.id, null, LeadStatus.NEW, null, null, null,
                null, at));
    }

    /** A new lead and the history row of its submission. */
    public record Submitted(Lead lead, LeadChange change) {
    }

    /**
     * A manager gives the lead to a salesperson, or to another one (whether that one may take leads is the service's
     * check: it needs the accounts). A lead already contacted keeps that status with its new Sales.
     *
     * @return the change, or empty when {@code salesUserId} already has it
     * @throws BusinessException {@code LEAD_ALREADY_HANDLED} once CONVERTED or DISQUALIFIED
     */
    public Optional<LeadChange> assign(UUID salesUserId, UUID by, Instant at) {
        Objects.requireNonNull(salesUserId, "salesUserId");
        requireOpen();
        if (salesUserId.equals(assignedTo)) {
            return Optional.empty();
        }
        LeadStatus before = status;
        UUID previous = assignedTo;
        status = status == LeadStatus.NEW ? LeadStatus.ASSIGNED : status;
        assignedTo = salesUserId;
        assignedBy = by;
        assignedAt = at;
        return Optional.of(new LeadChange(Identifiers.newId(), id, before, status, previous, salesUserId, null, by, at));
    }

    /**
     * The lead's Sales reached the business: ASSIGNED → CONTACTED.
     *
     * @throws BusinessException {@code LEAD_NOT_ASSIGNED} while NEW; {@code LEAD_ALREADY_HANDLED} once contacted,
     *         converted or disqualified
     */
    public LeadChange markContacted(String note, UUID by, Instant at) {
        if (status == LeadStatus.NEW) {
            throw new BusinessException(ErrorCode.LEAD_NOT_ASSIGNED);
        }
        if (status != LeadStatus.ASSIGNED) {
            throw new BusinessException(ErrorCode.LEAD_ALREADY_HANDLED);
        }
        status = LeadStatus.CONTACTED;
        contactedAt = at;
        contactNote = blankToNull(note);
        return new LeadChange(Identifiers.newId(), id, LeadStatus.ASSIGNED, LeadStatus.CONTACTED, assignedTo,
                assignedTo, contactNote, by, at);
    }

    /**
     * The lead is not a business to serve (spam, not B2B, unreachable...): from any open status, with a reason.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} without a reason; {@code LEAD_ALREADY_HANDLED} once closed
     */
    public LeadChange disqualify(String reason, UUID by, Instant at) {
        String why = blankToNull(reason);
        if (why == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Say why the lead is disqualified");
        }
        requireOpen();
        LeadStatus before = status;
        status = LeadStatus.DISQUALIFIED;
        disqualifiedReason = why;
        disqualifiedBy = by;
        disqualifiedAt = at;
        return new LeadChange(Identifiers.newId(), id, before, LeadStatus.DISQUALIFIED, assignedTo, assignedTo, why, by,
                at);
    }

    /**
     * The lead became {@code customerId}; its Sales is the customer's first owner (the service assigns it).
     *
     * @throws BusinessException {@code LEAD_NOT_ASSIGNED} while NEW; {@code LEAD_ALREADY_HANDLED} once closed
     */
    public LeadChange convert(UUID customerId, UUID by, Instant at) {
        Objects.requireNonNull(customerId, "customerId");
        requireOpen();
        if (status == LeadStatus.NEW) {
            throw new BusinessException(ErrorCode.LEAD_NOT_ASSIGNED);
        }
        LeadStatus before = status;
        status = LeadStatus.CONVERTED;
        convertedCustomerId = customerId;
        convertedAt = at;
        return new LeadChange(Identifiers.newId(), id, before, LeadStatus.CONVERTED, assignedTo, assignedTo, null, by,
                at);
    }

    /**
     * The same business sent the form again within the duplicate window (plan §10.7, finding P5): nothing about the
     * lead changes, but the later submission is kept as an entry of its history, so a first submission by someone
     * posing as the business cannot silently drop the real one - its Sales and the managers see both.
     *
     * @param note what the later form said (the service writes it)
     */
    public LeadChange resubmitted(String note, Instant at) {
        Objects.requireNonNull(note, "note");
        return new LeadChange(Identifiers.newId(), id, status, status, assignedTo, assignedTo, note, null, at,
                LeadChange.Kind.RESUBMITTED);
    }

    /** Whether the lead is still being worked on: NEW, ASSIGNED or CONTACTED. */
    public boolean isOpen() {
        return status == LeadStatus.NEW || status == LeadStatus.ASSIGNED || status == LeadStatus.CONTACTED;
    }

    private void requireOpen() {
        if (!isOpen()) {
            throw new BusinessException(ErrorCode.LEAD_ALREADY_HANDLED);
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    public UUID id() { return id; }
    public Form form() { return form; }
    public Instant submittedAt() { return submittedAt; }
    public LeadStatus status() { return status; }
    public UUID assignedTo() { return assignedTo; }
    public UUID assignedBy() { return assignedBy; }
    public Instant assignedAt() { return assignedAt; }
    public Instant contactedAt() { return contactedAt; }
    public String contactNote() { return contactNote; }
    public UUID convertedCustomerId() { return convertedCustomerId; }
    public Instant convertedAt() { return convertedAt; }
    public String disqualifiedReason() { return disqualifiedReason; }
    public UUID disqualifiedBy() { return disqualifiedBy; }
    public Instant disqualifiedAt() { return disqualifiedAt; }
    public long version() { return version; }
}
