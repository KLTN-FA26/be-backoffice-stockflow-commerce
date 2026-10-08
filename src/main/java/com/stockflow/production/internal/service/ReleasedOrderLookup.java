package com.stockflow.production.internal.service;

import java.util.UUID;

/**
 * Integration port awaiting SCRUM-423's public order API. Resolve the persisted order's customer
 * and require its selected release warehouse to match. Payment/deposit/release authorization
 * remains in order; never infer customer ownership from an HTTP body or ambient async principal.
 */
public interface ReleasedOrderLookup {
    UUID requireCustomer(UUID orderId, UUID warehouseId);
}
