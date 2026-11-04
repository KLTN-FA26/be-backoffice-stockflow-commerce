package com.stockflow.identity.api;

import java.util.UUID;

/** Edit an account's profile. A null field is left as it is; the username never changes. */
public record UpdateUserCommand(UUID userId, long expectedVersion, String email, String fullName) {
}
