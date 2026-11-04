package com.stockflow.identity.api;

import java.util.UUID;

/**
 * How to reach an active staff member with a notification: the id of their inbox and their e-mail address. Nothing
 * else about the account - no name, status or roles - so handing it to another module reveals no more than sending
 * them a message does.
 */
public record StaffContact(UUID userId, String email) {
}
