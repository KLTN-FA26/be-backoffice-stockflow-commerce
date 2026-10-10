package com.stockflow.identity.api;

import java.time.Instant;

/**
 * One row of the role list.
 *
 * @param system      a role the code knows by name: never renamed or deleted
 * @param version     the version of its grants; send it back when editing them
 * @param holderCount accounts holding the role
 */
public record RoleSummary(String code, String name, String description, boolean system, long version,
                          long holderCount, Instant createdAt, String createdBy,
                          Instant lastModifiedAt, String lastModifiedBy) {
}
