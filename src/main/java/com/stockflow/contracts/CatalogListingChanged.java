package com.stockflow.contracts;
import java.util.UUID;
/** Wake-up only: the consumer re-reads the latest source revision under the product lock. */
public record CatalogListingChanged(UUID productId) {}
