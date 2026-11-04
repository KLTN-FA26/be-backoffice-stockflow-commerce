package com.stockflow.identity.api;

/**
 * Rename a custom role or change its data scope. System roles cannot be changed.
 *
 * @param dataScope OWN, WAREHOUSE or ALL; null leaves it as it is
 */
public record UpdateRoleCommand(String code, long expectedVersion, String name, String description,
                                String dataScope) {
}
