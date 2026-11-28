package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.ClaimOutcome;
import com.stockflow.customer.api.CustomerSalesOwner;
import com.stockflow.customer.api.Responsible;
import com.stockflow.customer.api.ResponsibleSales;
import com.stockflow.customer.api.SalesOwnership;
import com.stockflow.identity.api.SalesStaff;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Sales owner's rules where the staff act in the chat (sales-owner plan BR-S05, BR-S12, strict mode D1; kltn-docs
 * 16 BR-12, BR-13), asked of {@code customer :: api} - the chat keeps no owner of its own.
 *
 * <ul>
 *   <li><b>Claiming</b> a conversation from the queue: a salesperson (an active SALES_STAFF account) who claims one of
 *       a customer with no active owner becomes the owner, and the managers are told; of a customer another
 *       salesperson owns, the claim is refused {@code CUSTOMER_UNASSIGNED_CLAIM_ONLY} unless the claimer is the owner
 *       or their cover today (case 18). A Sales Manager or an admin claiming is never made the owner (owner decision
 *       D42: they coordinate, 18 BR-26): they take the conversation as before, where they may act for the customer,
 *       and assign the owner with {@code PUT /customers/{id}/sales-owner}. With
 *       {@link ChatProperties#claimMakesOwner} off, nobody becomes an owner by claiming.</li>
 *   <li><b>Opening</b> a conversation with a customer: the owner, the cover in force, a manager (who may act for any
 *       customer) and a chat supervisor; anyone when the customer has no active owner. Another salesperson is refused
 *       {@code CUSTOMER_OWNED_BY_OTHER_SALES}, naming the owner; they still read the customer's conversations (BR-S11).
 *       </li>
 * </ul>
 */
@Component
public class ChatOwnership {

    private final SalesOwnership ownership;
    private final ResponsibleSales responsible;
    private final SalesStaff salesStaff;
    private final AgentDirectory agents;
    private final SalesOwnerNotices notices;
    private final ChatProperties properties;
    private final Clock clock;

    public ChatOwnership(SalesOwnership ownership, ResponsibleSales responsible, SalesStaff salesStaff,
                         AgentDirectory agents, SalesOwnerNotices notices, ChatProperties properties, Clock clock) {
        this.ownership = ownership;
        this.responsible = responsible;
        this.salesStaff = salesStaff;
        this.agents = agents;
        this.notices = notices;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * {@code staff} just took {@code c} from the queue (in the caller's transaction, under the conversation's lock):
     * what it means for the customer's owner. Joins the transaction, so a refusal takes the claim back with it.
     *
     * @throws BusinessException {@code CUSTOMER_UNASSIGNED_CLAIM_ONLY} (409, details {@code salesOwnerId},
     *                           {@code salesOwnerName}) when another salesperson owns the customer
     */
    public void claimed(Actor staff, Conversation c) {
        LocalDate today = today();
        UUID customerId = c.customerId();
        if (properties.claimMakesOwner() && salesStaff.isActiveSalesAgent(staff.userId())) {
            ClaimOutcome outcome;
            try {
                outcome = ownership.claimIfUnowned(customerId, staff.userId(), today);
            } catch (BusinessException refused) {
                if (refused.errorCode() == ErrorCode.CUSTOMER_UNASSIGNED_CLAIM_ONLY) {
                    throw claimOnly(customerId);
                }
                throw refused;
            }
            if (outcome == ClaimOutcome.CLAIMED) {
                notices.claimed(c, staff.userId());
            }
            return;
        }
        if (!ownership.mayActFor(staff.userId(), customerId, today)) {
            throw claimOnly(customerId);
        }
    }

    /**
     * Who gets a conversation {@code staff} opens with {@code customerId}, once they may open it (16 §4.1 table): the
     * opener when they are the owner or the cover; else whoever acts for the customer today, when they answer
     * customers - one customer, one contact, even when a manager opens it; else the opener when they answer customers.
     *
     * @return the agent to assign; null to route it (the queue, as nobody acts for the customer)
     * @throws BusinessException {@code CUSTOMER_OWNED_BY_OTHER_SALES} (409) for a salesperson who may not act for the
     *                           customer
     */
    public UUID agentForOpening(Actor staff, UUID customerId) {
        LocalDate today = today();
        if (!staff.supervisor()) {
            ownership.requireMayActFor(staff.userId(), customerId, today);
        }
        Optional<Responsible> now = responsible.responsibleFor(customerId, today);
        UUID opener = staff.userId();
        boolean openerResponsible = now.map(r -> opener.equals(r.ownerId()) || opener.equals(r.actingUserId()))
                .orElse(false);
        if (!openerResponsible) {
            Optional<UUID> acting = now.map(Responsible::actingUserId).filter(agents::answersCustomers);
            if (acting.isPresent()) {
                return acting.get();
            }
        }
        return agents.answersCustomers(opener) ? opener : null;
    }

    /** The business day covers and ownership are judged on. */
    public LocalDate today() {
        return BusinessCalendar.date(clock.instant());
    }

    /** Case 18: the customer is someone else's; the refusal names the owner, as BR-S12's does. */
    private BusinessException claimOnly(UUID customerId) {
        UUID ownerId = ownership.ownerOf(customerId).map(CustomerSalesOwner::salesOwnerId).orElse(null);
        String name = ownerId == null ? "?" : agents.names(List.of(ownerId)).getOrDefault(ownerId, "?");
        Map<String, Object> details = ownerId == null ? Map.of()
                : Map.of("salesOwnerId", ownerId, "salesOwnerName", name);
        return new BusinessException(ErrorCode.CUSTOMER_UNASSIGNED_CLAIM_ONLY,
                "Customer %s is looked after by %s: only a customer with no salesperson is claimed".formatted(customerId,
                        ownerId), List.of(), details).localized("refusal.chat.claimOwned", name);
    }
}
