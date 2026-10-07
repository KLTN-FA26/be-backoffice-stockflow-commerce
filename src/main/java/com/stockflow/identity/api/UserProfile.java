package com.stockflow.identity.api;

import java.time.Instant;
import java.util.UUID;

/** Who a signed-in user is: what the frontend shows in its header after login. No credentials. */
public record UserProfile(UUID userId, String username, String email, String fullName, String status,
                          Instant lastLoginAt) {
}
