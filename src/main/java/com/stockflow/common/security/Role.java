package com.stockflow.common.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * The roles from the BRD (section 6.1), plus {@link #SYSTEM_ADMIN}, as a real type.
 *
 * <p>Use this enum everywhere in ordinary code: it cannot be misspelled, it can be switched over
 * exhaustively, and adding a role makes the compiler point at every place that must be updated.</p>
 *
 * <p>{@link Roles} still exists as plain String constants for one reason only: a Java annotation
 * argument must be a compile-time constant, so {@code @PreAuthorize} cannot take
 * {@code Role.WAREHOUSE_STAFF.name()}. {@code RoleConsistencyTest} asserts the two stay in sync.</p>
 */
public enum Role {

    WAREHOUSE_STAFF(Roles.WAREHOUSE_STAFF, "Receive, put away, pick, pack and hand over to carriers"),
    WAREHOUSE_MANAGER(Roles.WAREHOUSE_MANAGER, "Approve adjustments, override slotting"),
    INVENTORY_PLANNER(Roles.INVENTORY_PLANNER, "Monitor stock levels"),
    CUSTOMER(Roles.CUSTOMER, "Browse, design, order and track"),
    SALES_STAFF(Roles.SALES_STAFF, "Advise customers, edit designs, create assisted orders"),
    ORDER_COORDINATOR(Roles.ORDER_COORDINATOR, "Release orders to the warehouse and handle holds"),
    /** Was ECOMMERCE_ADMIN; renamed by V20261107000100 (kltn-docs 00 §2, 2026-11-05) with its id and grants. */
    SALES_MANAGER(Roles.SALES_MANAGER, "Product data and approval, internal prices, print setup, design review, staff accounts"),
    PROCUREMENT_STAFF(Roles.PROCUREMENT_STAFF, "Purchase orders and supplier coordination"),
    /** Added by V20261108002600 (kltn-docs 00 §2.1, decision 2026-11-07): production orders and the producers. */
    PRODUCTION_COORDINATOR(Roles.PRODUCTION_COORDINATOR,
            "Production orders for what a confirmed order lacks: producers, lead times, print samples, progress"),
    ACCOUNTANT(Roles.ACCOUNTANT, "Supplier invoices, three-way matching, reconciliation"),
    /** Holds every permission, and its grants cannot be edited at runtime (V20260929000200). */
    SYSTEM_ADMIN(Roles.SYSTEM_ADMIN, "Every permission: users, roles, permissions and all modules");

    private final String authority;
    private final String description;

    Role(String authority, String description) {
        this.authority = authority;
        this.description = description;
    }

    /** The exact string carried in the JWT and matched by {@code @PreAuthorize}. */
    public String authority() {
        return authority;
    }

    public String description() {
        return description;
    }

    public static Optional<Role> fromAuthority(String authority) {
        return Arrays.stream(values()).filter(r -> r.authority.equals(authority)).findFirst();
    }
}
