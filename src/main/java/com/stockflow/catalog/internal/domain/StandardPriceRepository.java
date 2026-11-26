package com.stockflow.catalog.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link StandardPrice}. Joins the caller's transaction. */
public interface StandardPriceRepository {

    Optional<StandardPrice> find(String sku);

    /** Under a row lock held to the end of the transaction; nothing read before it in the transaction. */
    Optional<StandardPrice> findForUpdate(String sku);

    /** The standard prices of these variants (every one for an empty collection), by SKU. */
    List<StandardPrice> of(Collection<String> skus);

    /** Writes the price and its breaks, replacing the stored breaks; returns it as stored (new version). */
    StandardPrice save(StandardPrice price);

    void delete(UUID priceId);
}
