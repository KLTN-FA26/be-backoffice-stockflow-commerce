package com.stockflow.procurement.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.procurement.internal.repository.ApprovalLimitRepository;
import org.springframework.stereotype.Component;

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

    ApprovalLimits(IdentityService identity, ApprovalLimitRepository limits) {
        this.identity = identity;
        this.limits = limits;
    }

    /**
     * The most {@code userId} may approve in {@code currency} on {@code on}: the highest limit among their roles, or
     * empty when none of their roles has a limit in force in that currency.
     */
    Optional<Money> limitOf(UUID userId, Currency currency, LocalDate on) {
        return limits.highestLimit(identity.roleIdsOf(userId), currency.getCurrencyCode(), on)
                .map(amount -> new Money(amount, currency));
    }
}
