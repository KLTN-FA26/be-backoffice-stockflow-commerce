package com.stockflow.identity.api;

/**
 * Filters of the user list. All optional.
 *
 * @param q                text matched against username, e-mail and full name
 * @param status           ACTIVE, LOCKED or DISABLED
 * @param roleCode         only holders of this role
 * @param includeCustomers customer accounts are left out unless asked for
 * @param sort             {@code field,dir} over username, fullName, status, lastLoginAt, createdAt
 */
public record ListUsersQuery(String q, String status, String roleCode, boolean includeCustomers,
                             int page, int size, String sort) {
}
