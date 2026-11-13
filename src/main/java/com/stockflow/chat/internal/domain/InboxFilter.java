package com.stockflow.chat.internal.domain;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * What the staff's queue shows (16 §4.1, BR-09). Every condition given must hold.
 *
 * @param statuses   the statuses shown; empty means every status but the ended ones
 * @param assignedTo only this agent's conversations; null for anyone's
 * @param unassigned only those nobody has
 * @param overdue    only those whose first answer is late (BR-09)
 * @param customerId only this customer's; null for every customer's
 * @param about      only those about this document; null for any
 */
public record InboxFilter(Set<ConversationStatus> statuses, UUID assignedTo, boolean unassigned, boolean overdue,
                          UUID customerId, ContextRef about) {

    public InboxFilter {
        if (statuses == null || statuses.isEmpty()) {
            Set<ConversationStatus> open = EnumSet.noneOf(ConversationStatus.class);
            for (ConversationStatus status : ConversationStatus.values()) {
                if (!status.isEnded()) {
                    open.add(status);
                }
            }
            statuses = Set.copyOf(open);
        } else {
            statuses = Set.copyOf(statuses);
        }
    }
}
