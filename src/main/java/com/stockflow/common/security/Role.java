package com.stockflow.common.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * The roles of kltn-docs 00 §2.1 and the customer's, as a real type: the system roles, which the database seeds and
 * nobody deletes or renames (00 §2.5.3).
 *
 * <p>ORDER_COORDINATOR and INVENTORY_PLANNER were retired by V20261115000200 (00 §2, decision 2026-11-05; team plan
 * 5-7): Sales took the customer's side of their work, the warehouse manager the warehouse's, and their holders were
 * given those roles. PROCUREMENT_STAFF was retired by V20261115000300 (00 §2, decision 2026-11-04; team plan 5-8):
 * purchasing is gone, and its holders with no other role were disabled.</p>
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
    WAREHOUSE_MANAGER(Roles.WAREHOUSE_MANAGER, "Assigned warehouses: set-up, receiving decisions, order release, "
            + "pick tasks and staging, delivery outcomes, counts and adjustment approval"),
    /** Orders nothing itself (team plan 5-2, kltn-docs 14 BR-01): Sales draws an order up from a quote it accepted. */
    CUSTOMER(Roles.CUSTOMER, "Browse, design, request and accept quotes, track orders"),
    SALES_STAFF(Roles.SALES_STAFF, "Customers, quotes, orders and contracts; shipping warehouse, holds, "
            + "cancellations, partial delivery and returns of a confirmed order; chat"),
    /** Was ECOMMERCE_ADMIN; renamed by V20261107000100 (kltn-docs 00 §2, 2026-11-05) with its id and grants. */
    SALES_MANAGER(Roles.SALES_MANAGER, "Product data and approval, internal prices, print setup, design review, staff accounts"),
    /** Added by V20261108002600 (kltn-docs 00 §2.1, decision 2026-11-07): production orders and the producers. */
    PRODUCTION_COORDINATOR(Roles.PRODUCTION_COORDINATOR,
            "Production orders for what a confirmed order lacks: producers, lead times, print samples, progress"),
    ACCOUNTANT(Roles.ACCOUNTANT,
            "Receipts against contract instalments, receivables and refunds, credit approval, e-invoices"),
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
