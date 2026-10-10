package com.stockflow.identity.api;

/** Rename a custom role. System roles cannot be renamed. */
public record UpdateRoleCommand(String code, long expectedVersion, String name, String description) {
}
