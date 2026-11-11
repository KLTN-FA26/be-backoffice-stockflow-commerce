package com.stockflow.customer.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.api.PaymentMilestone;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer's default payment schedule (kltn-docs 18 §3, §4 steps 2 and 5, BR-03, BR-04; 14 BR-13; 15 BR-12): the
 * installments a new sales contract starts from, replaced whole.
 *
 * <ul>
 *   <li>one to {@value #MAX_INSTALLMENTS} installments, each a share of the total with tax above 0 with two decimals at
 *       most, the shares adding up to exactly 100 (BR-04);</li>
 *   <li>each due at a milestone plus 0-365 days;</li>
 *   <li>an AFTER_DELIVERY installment - credit - only for a customer whose credit accounting approved with a limit
 *       above 0 (BR-03). Sales may propose one; it then awaits accounting's approval (15 BR-12), and a contract is not
 *       drafted on it until approved. An accountant's own schedule is approved as it is saved. Changing the
 *       installments again - by anyone without approval - clears the approval; changing only the note keeps it.</li>
 * </ul>
 * Mirrored by {@code customer.check_default_payment_schedule()} at commit: the shares, the credit, and no approval on a
 * schedule with nothing to approve.
 */
public final class PaymentSchedule extends AggregateRoot {

    public static final int MAX_INSTALLMENTS = 12;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** One installment, in order. */
    public record Installment(String name, BigDecimal percent, PaymentMilestone milestone, int dueDays) {

        public Installment {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(percent, "percent");
            Objects.requireNonNull(milestone, "milestone");
        }
    }

    private final UUID id;
    private final UUID customerId;
    private List<Installment> installments;
    private UUID setBy;
    private Instant setAt;
    private UUID approvedBy;
    private Instant approvedAt;
    private String note;
    private final long version;

    public PaymentSchedule(UUID id, UUID customerId, List<Installment> installments, UUID setBy, Instant setAt,
                           UUID approvedBy, Instant approvedAt, String note, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.installments = List.copyOf(installments);
        this.setBy = setBy;
        this.setAt = setAt;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.note = note;
        this.version = version;
    }

    /**
     * A first schedule for the customer.
     *
     * @param approver whether the one saving approves credit (accounting): their schedule is approved as saved
     * @param credit   whether the customer's credit is approved with a limit above 0
     */
    public static PaymentSchedule create(UUID customerId, List<Installment> installments, String note, UUID by,
                                         boolean approver, boolean credit, Instant at) {
        PaymentSchedule schedule = new PaymentSchedule(Identifiers.newId(), customerId, List.of(), null, null, null,
                null, null, 0L);
        schedule.replace(installments, note, by, approver, credit, at);
        return schedule;
    }

    /**
     * Replaces the schedule whole.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} naming each wrong field; {@code PAYMENT_SCHEDULE_NEEDS_CREDIT}
     *         for an AFTER_DELIVERY installment when the customer has no approved credit
     */
    public void replace(List<Installment> wanted, String note, UUID by, boolean approver, boolean credit, Instant at) {
        Objects.requireNonNull(by, "by");
        List<Installment> given = wanted == null ? List.of() : List.copyOf(wanted);
        List<FieldError> errors = new ArrayList<>();
        if (given.isEmpty() || given.size() > MAX_INSTALLMENTS) {
            errors.add(FieldError.of("installments", "1 to " + MAX_INSTALLMENTS + " installments"));
        }
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < given.size(); i++) {
            Installment line = given.get(i);
            String field = "installments[" + i + "].";
            if (line.name().isBlank() || line.name().strip().length() > 100) {
                errors.add(FieldError.of(field + "name", "required, at most 100 characters"));
            }
            BigDecimal percent = line.percent().stripTrailingZeros();
            if (line.percent().signum() <= 0 || line.percent().compareTo(HUNDRED) > 0 || percent.scale() > 2) {
                errors.add(FieldError.of(field + "percent", "above 0, at most 100, two decimals at most"));
            }
            if (line.dueDays() < 0 || line.dueDays() > 365) {
                errors.add(FieldError.of(field + "dueDays", "0 to 365"));
            }
            total = total.add(line.percent());
        }
        if (errors.isEmpty() && total.compareTo(HUNDRED) != 0) {
            errors.add(FieldError.of("installments", "the shares add up to " + total.stripTrailingZeros()
                    .toPlainString() + ", not 100"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid payment schedule", errors);
        }
        List<Installment> normalized = given.stream().map(l -> new Installment(l.name().strip(),
                l.percent().setScale(2), l.milestone(), l.dueDays())).toList();
        boolean afterDelivery = normalized.stream().anyMatch(l -> l.milestone() == PaymentMilestone.AFTER_DELIVERY);
        if (afterDelivery && !credit) {
            throw new BusinessException(ErrorCode.PAYMENT_SCHEDULE_NEEDS_CREDIT, ("Customer %s has no credit approved "
                    + "with a limit above 0: an installment after delivery is not allowed").formatted(customerId));
        }
        boolean unchanged = normalized.equals(installments);
        this.installments = normalized;
        this.note = note == null || note.isBlank() ? null : note.strip();
        this.setBy = by;
        this.setAt = at;
        if (!afterDelivery) {
            this.approvedBy = null;
            this.approvedAt = null;
        } else if (approver) {
            this.approvedBy = by;
            this.approvedAt = at;
        } else if (!unchanged) {
            // Proposed by Sales, or changed since it was approved: it awaits accounting again (15 BR-12).
            this.approvedBy = null;
            this.approvedAt = null;
        }
    }

    /**
     * Accounting approves the installments after delivery Sales proposed (15 BR-12, 18 BR-03). Approving one already
     * approved changes nothing.
     *
     * @param credit whether the customer's credit is approved with a limit above 0, now
     * @return whether it changed
     * @throws BusinessException {@code VALIDATION_FAILED} when nothing in it pays after delivery;
     *         {@code PAYMENT_SCHEDULE_NEEDS_CREDIT} when the customer has no approved credit any more
     */
    public boolean approve(UUID by, boolean credit, Instant at) {
        Objects.requireNonNull(by, "by");
        if (!paysAfterDelivery()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("The default payment schedule of customer %s pays "
                    + "nothing after delivery: there is nothing to approve").formatted(customerId));
        }
        if (!credit) {
            throw new BusinessException(ErrorCode.PAYMENT_SCHEDULE_NEEDS_CREDIT, ("Customer %s has no credit approved "
                    + "with a limit above 0: an installment after delivery is not allowed").formatted(customerId));
        }
        if (approvedBy != null) {
            return false;
        }
        this.approvedBy = by;
        this.approvedAt = at;
        return true;
    }

    public boolean paysAfterDelivery() {
        return installments.stream().anyMatch(l -> l.milestone() == PaymentMilestone.AFTER_DELIVERY);
    }

    /** Pays after delivery and accounting has not approved it yet: no contract is drafted on it. */
    public boolean awaitingApproval() {
        return paysAfterDelivery() && approvedBy == null;
    }

    public UUID id() { return id; }
    public UUID customerId() { return customerId; }
    public List<Installment> installments() { return installments; }
    public UUID setBy() { return setBy; }
    public Instant setAt() { return setAt; }
    public UUID approvedBy() { return approvedBy; }
    public Instant approvedAt() { return approvedAt; }
    public String note() { return note; }
    public long version() { return version; }
}
