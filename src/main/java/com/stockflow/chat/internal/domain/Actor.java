package com.stockflow.chat.internal.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Who acts on a conversation: a customer's portal account, a staff member, or the system.
 *
 * @param supervisor whether the staff member holds the chat supervision (16 §2: assigns conversations, takes escalated
 *                   ones). Decided by the caller from the permission ({@code chat-supervision:UPDATE}), never from a
 *                   role name, so ticking the permission for another role widens it with no code change.
 */
public record Actor(UUID userId, SenderType type, boolean supervisor) {

    private static final Actor SYSTEM = new Actor(null, SenderType.SYSTEM, false);

    public Actor {
        Objects.requireNonNull(type, "type");
        if (type != SenderType.SYSTEM && userId == null) {
            throw new IllegalArgumentException("A person acting names their account");
        }
        if (type != SenderType.AGENT && supervisor) {
            throw new IllegalArgumentException("Only staff supervise the chat");
        }
    }

    public static Actor customer(UUID userId) {
        return new Actor(userId, SenderType.CUSTOMER, false);
    }

    public static Actor agent(UUID userId, boolean supervisor) {
        return new Actor(userId, SenderType.AGENT, supervisor);
    }

    public static Actor system() {
        return SYSTEM;
    }

    public boolean isCustomer() {
        return type == SenderType.CUSTOMER;
    }

    public boolean isStaff() {
        return type == SenderType.AGENT;
    }
}
