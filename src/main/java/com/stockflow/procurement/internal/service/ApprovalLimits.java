package com.stockflow.procurement.internal.service;

import com.stockflow.identity.api.IdentityService;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.domain.Money;
import com.stockflow.procurement.internal.domain.ApprovalLimit;
import com.stockflow.procurement.internal.domain.PurchaseOrderId;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;
import com.stockflow.procurement.internal.repository.ApprovalLimitRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

/**
 * What a user may approve (kltn-docs 02 §4 step 7, owner decision POLIMIT2).
 *
 * <p>{@code procurement.approval_limits} is keyed by role, and a user may hold several: the limit is the highest of
 * the roles' limits in force today in the order's currency. Roles are reached by id through
 * {@link IdentityService#roleIdsOf}; no role is named here, so who may approve how much stays data, not code.</p>
 *
 * <p>No limit configured is <em>no limit</em>, not a limit of zero (owner decision PO-NOLIMIT, 28/10): an order is
 * approved at submission only within a limit that is configured, so an unconfigured submitter's order always waits;
 * an unconfigured approver is bounded only by holding the approve permission. A configured limit always caps.</p>
 */
@Component
class ApprovalLimits {

    private final IdentityService identity;
    private final ApprovalLimitRepository limits;
    private final PurchaseOrderRepository purchaseOrders;

    ApprovalLimits(IdentityService identity, ApprovalLimitRepository limits, PurchaseOrderRepository purchaseOrders) {
        this.identity = identity;
        this.limits = limits;
        this.purchaseOrders = purchaseOrders;
    }

    /**
     * The anti-splitting window (SCRUM-124 rule 4): the automatic approvals of one holder to one supplier in the 7 days
     * ending at the moment of the decision are added up with the order being decided.
     */
    static final Duration AUTO_APPROVAL_WINDOW = Duration.ofDays(7);

    /**
     * The limit that caps {@code userId} in {@code currency} on {@code on}: the highest among their roles, with the row
     * and role it comes from, or empty when none of their roles has a limit in force in that currency.
     */
    Optional<ApprovalLimit> limitOf(UUID userId, Currency currency, LocalDate on) {
        return limits.highestLimit(identity.roleIdsOf(userId), currency, on);
    }

    /**
     * The limit that lets a purchase order be approved without a person (kltn-docs 02 §4 step 7, SCRUM-124 rules 1,
     * 3 and 4), or empty when it needs one. All of these must hold:
     * <ol>
     *   <li>the order was never rejected: whoever rejected it sees it again, however small it became (rule 3);</li>
     *   <li>{@code limitHolder} has a limit configured in force in the order's currency - none is no limit, not zero
     *       (PO-NOLIMIT) - and {@code total}, after tax, is within it (rule 1);</li>
     *   <li>adding the automatic approvals of the same holder to the same supplier in the last 7 days it still is
     *       (rule 4: a purchase cut into small orders is one purchase).</li>
     * </ol>
     * The checks run cheapest first; the sum of rule 4 is read last, under a lock, and only for an order that would
     * be approved otherwise.
     *
     * @param order       the order being decided
     * @param limitHolder whose limit counts: the submitter, or the requester of an amendment, or for a split-generated
     *                    subcontract order the person who split (rule 6)
     * @param total       what the order would total once decided, after tax
     */
    Optional<ApprovalLimit> autoApprovalLimit(PurchaseOrderId order, UUID limitHolder, UUID supplierId, Money total,
                                              Instant now) {
        if (purchaseOrders.hasBeenRejected(order)) {
            return Optional.empty();
        }
        Optional<ApprovalLimit> limit = limitOf(limitHolder, total.currency(), BusinessCalendar.date(now));
        if (limit.isEmpty() || limit.get().amount().amount().signum() <= 0
                || total.compareTo(limit.get().amount()) > 0) {
            return Optional.empty();
        }
        Money alreadyApproved = purchaseOrders.autoApprovedTotal(limitHolder, supplierId, total.currency(),
                now.minus(AUTO_APPROVAL_WINDOW), order);
        return alreadyApproved.plus(total).compareTo(limit.get().amount()) <= 0 ? limit : Optional.empty();
    }
}
