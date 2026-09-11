package com.stockflow.common.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * The ten roles from the BRD (section 6.1), as a real type.
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
    WAREHOUSE_MANAGER(Roles.WAREHOUSE_MANAGER, "Approve adjustments and transfers, override slotting"),
    INVENTORY_PLANNER(Roles.INVENTORY_PLANNER, "Monitor stock levels and raise transfer orders"),
    QC_STAFF(Roles.QC_STAFF, "Inspect inbound goods and printed output"),
    CUSTOMER(Roles.CUSTOMER, "Browse, design, order and track"),
    SALES_STAFF(Roles.SALES_STAFF, "Advise customers, edit designs, create assisted orders"),
    ORDER_COORDINATOR(Roles.ORDER_COORDINATOR, "Release orders to the warehouse and handle holds"),
    ECOMMERCE_ADMIN(Roles.ECOMMERCE_ADMIN, "Catalog, pricing, promotions, design templates"),
    PROCUREMENT_STAFF(Roles.PROCUREMENT_STAFF, "Purchase orders and supplier coordination"),
    ACCOUNTANT(Roles.ACCOUNTANT, "Supplier invoices, three-way matching, reconciliation");

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
