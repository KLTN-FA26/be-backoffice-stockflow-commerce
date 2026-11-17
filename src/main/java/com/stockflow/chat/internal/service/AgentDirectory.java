package com.stockflow.chat.internal.service;

import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Who answers customers in the chat, and what people are called - through {@code identity :: api} only.
 *
 * <p>An agent is an active staff member holding {@code chat-conversations:UPDATE}: asked of the role matrix, never of a
 * role name, so granting the chat to another role makes its holders agents with no code change. Portal accounts hold
 * the same permission and are never returned ({@link IdentityService#notificationRecipientsHolding} leaves out anyone
 * with the CUSTOMER role).</p>
 */
@Component
public class AgentDirectory {

    private static final String ANSWERS_CUSTOMERS = ChatCallers.CONVERSATIONS + ":UPDATE";

    private final IdentityService identity;

    public AgentDirectory(IdentityService identity) {
        this.identity = identity;
    }

    /** The accounts that answer customers. One query, bounded by the staff of the business. */
    public Set<UUID> agents() {
        return identity.notificationRecipientsHolding(ANSWERS_CUSTOMERS, null).stream().map(StaffContact::userId)
                .collect(Collectors.toSet());
    }

    public boolean answersCustomers(UUID userId) {
        return userId != null && agents().contains(userId);
    }

    /** Display names (full name, or the username) of the accounts that exist among {@code userIds}. */
    public Map<UUID, String> names(Collection<UUID> userIds) {
        List<UUID> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : identity.displayNames(ids);
    }
}
