package com.stockflow.production.internal.service;

import java.util.UUID;

/**
 * Missing public reference APIs, not permission to read other modules' tables. The future adapter
 * validates the selected warehouse and (when present) the quote's customer before any write.
 */
public interface SampleReferenceChecks {
    void requireWarehouse(UUID warehouseId);
    void requireQuoteForCustomer(UUID quoteId, UUID customerId);
}
