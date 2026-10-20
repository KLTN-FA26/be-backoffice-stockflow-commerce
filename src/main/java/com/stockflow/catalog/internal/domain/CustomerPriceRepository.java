package com.stockflow.catalog.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link CustomerPrice}. Joins the caller's transaction. */
public interface CustomerPriceRepository {

    Optional<CustomerPrice> find(UUID customerId, String sku);

    /** Under a row lock held to the end of the transaction; nothing read before it in the transaction. */
    Optional<CustomerPrice> findForUpdate(UUID customerId, String sku);

    /** The customer's prices for these SKUs (all of them for an empty set), by SKU. */
    List<CustomerPrice> ofCustomer(UUID customerId, java.util.Collection<String> skus);

    /** Writes the price and its tiers, replacing the stored tiers; returns it as stored (new version). */
    CustomerPrice save(CustomerPrice price);

    void delete(UUID priceId);
}
