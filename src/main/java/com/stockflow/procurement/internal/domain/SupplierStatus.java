package com.stockflow.procurement.internal.domain;

/**
 * Whether new purchase orders may go to a supplier ({@code ck_suppliers_status}). Only
 * {@link #ACTIVE} takes new orders. {@link #BLACKLISTED} is {@link #INACTIVE} with a reason not to
 * come back: both are refused while the supplier still has open orders.
 */
public enum SupplierStatus {
    ACTIVE,
    INACTIVE,
    BLACKLISTED
}
