package com.stockflow.identity.api;

import java.time.Instant;

/** One row of the role list: the fields {@code identity.app_role} actually carries. */
public record RoleSummary(String code, String name, String description,
                           Instant createdAt, String createdBy,
                           Instant lastModifiedAt, String lastModifiedBy) {
}
