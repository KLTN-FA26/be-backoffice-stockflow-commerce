package com.stockflow.identity.api;

/**
 * @param temporaryPassword the generated password, shown once and never stored in clear; null when
 *                          the administrator chose it
 */
public record CreatedStaffUser(StaffUser user, String temporaryPassword) {
}
