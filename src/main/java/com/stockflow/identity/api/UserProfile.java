package com.stockflow.identity.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Who a signed-in user is: what the frontend shows in its header after login. No credentials.
 *
 * @param mustChangePassword the password was set by an administrator; the client should ask for a
 *                           new one before anything else
 */
public record UserProfile(UUID userId, String username, String email, String fullName, String status,
                          Instant lastLoginAt, boolean mustChangePassword) {
}
