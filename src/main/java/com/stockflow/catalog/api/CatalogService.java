package com.stockflow.catalog.api;

/**
 * THE public API of the catalog module — the only package other modules may import.
 *
 * <p>Checkout reads authoritative public prices through this port. Two rules from
 * {@code docs/adding-a-module.md} §1:</p>
 * <ul>
 *   <li>declare only what other modules actually call — the shortest surface that works;</li>
 *   <li>every parameter and return type is a record or enum declared in THIS package, never a
 *       domain object or JPA entity. {@code ArchitectureTest.theApiPackageLeaksNothingInternal}
 *       and {@code theApiPublishesNoEntities} enforce it.</li>
 * </ul>
 *
 */
public interface CatalogService {

    /** Current published prices, locked for the caller's checkout transaction. */
    java.util.Map<String, com.stockflow.common.domain.Money> checkoutPrices(java.util.Set<String> skus);
}
