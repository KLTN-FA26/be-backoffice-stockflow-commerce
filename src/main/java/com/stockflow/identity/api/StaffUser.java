package com.stockflow.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One account as user management sees it (SCRUM-454). No credentials.
 *
 * @param warehouseIds the warehouses a warehouse-bound user may act on (SCRUM-457)
 * @param lockedUntil set while a lock after too many wrong passwords runs; null otherwise
 * @param latestStatusChange the last administrator lock / unlock / disable / enable with its reason; null when
 *                           none was recorded (the account never had one, or it predates SCRUM-474)
 * @param version     send it back with an edit, so two administrators cannot overwrite each other
 */
public record StaffUser(UUID userId, String username, String email, String fullName, String status,
                        List<String> roles, List<UUID> warehouseIds, boolean mustChangePassword, Instant lockedUntil,
                        Instant lastLoginAt, Instant createdAt, String createdBy,
                        UserStatusChangeEntry latestStatusChange, long version) {
}
