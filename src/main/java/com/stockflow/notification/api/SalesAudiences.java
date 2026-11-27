package com.stockflow.notification.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Who on Sales' side is told about a customer today (sales-owner plan §2.2; kltn-docs 00 §6, 18 BR-20): the customer's
 * owner, the owner's cover while one is in force, and - when the customer has no active owner - the active Sales
 * Managers (else the System Admins), with the notice marked "cần phân công". Never every salesperson (owner decision
 * D6, 2026-11-28). Asked by notification when it delivers an event, <b>implemented by the order module</b>, which reads
 * the rule from {@code customer :: api} and knows whose an order is.
 *
 * <p>The dependency is inverted as {@link ProductionOrderDirectory}'s is: customer and order both depend on this
 * module (the portal invitation, the inbox), so notification cannot call them. It is asked when the notice is written,
 * not carried in the stored event: an owner changed or a cover begun since the event still decides. A module test that
 * boots notification without order mocks this interface.</p>
 */
public interface SalesAudiences {

    /** The audience for the customer; empty for an unknown customer. */
    Optional<SalesAudience> ofCustomer(UUID customerId);

    /** The audience for the order's customer; empty for an unknown order. */
    Optional<SalesAudience> ofOrder(UUID orderId);

    /** The audience for the customer whose portal login this is; empty when it is no customer's. */
    Optional<SalesAudience> ofCustomerAccount(UUID portalUserId);

    /**
     * @param userIds         whom to tell: the owner or the cover (one), or the managers; empty only when nobody of
     *                        either is active
     * @param needsAssignment the customer has no active owner: the notice says "cần phân công"
     */
    record SalesAudience(List<UUID> userIds, boolean needsAssignment) {

        public SalesAudience {
            userIds = List.copyOf(Objects.requireNonNull(userIds, "userIds"));
        }
    }
}
