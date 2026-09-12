package com.stockflow.identity.api;

/** One row of the role list: the fields {@code identity.app_role} actually carries. */
public record RoleSummary(String code, String name, String description) {
}
