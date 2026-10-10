package com.stockflow.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One account as user management sees it (SCRUM-454). No credentials.
 *
 * @param lockedUntil set while a lock after too many wrong passwords runs; null otherwise
 * @param version     send it back with an edit, so two administrators cannot overwrite each other
 */
public record StaffUser(UUID userId, String username, String email, String fullName, String status,
                        List<String> roles, boolean mustChangePassword, Instant lockedUntil,
                        Instant lastLoginAt, Instant createdAt, String createdBy, long version) {
}
