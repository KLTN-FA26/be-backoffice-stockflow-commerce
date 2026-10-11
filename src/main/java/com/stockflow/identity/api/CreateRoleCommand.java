package com.stockflow.identity.api;

/**
 * @param copyPermissionsFrom optional: start from the grants of this role instead of from nothing
 * @param dataScope           OWN, WAREHOUSE or ALL; null means ALL
 */
public record CreateRoleCommand(String code, String name, String description, String copyPermissionsFrom,
                                String dataScope) {
}
