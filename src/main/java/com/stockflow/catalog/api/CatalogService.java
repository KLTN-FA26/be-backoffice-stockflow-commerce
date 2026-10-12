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

    /** Current published base prices, locked for the caller's checkout transaction. No customer. */
    java.util.Map<String, com.stockflow.common.domain.Money> checkoutPrices(java.util.Set<String> skus);

    /**
     * The unit prices of an account order, locked for the caller's checkout transaction like
     * {@link #checkoutPrices(java.util.Set)}: for each SKU the customer's own tier for the order's total
     * quantity of that SKU, else its group's price, else the base price (kltn-docs 13 §3; locked into
     * the order by the caller, 14 BR-03).
     *
     * @param quantities units per SKU, summed over the order's lines
     * @throws com.stockflow.common.error.BusinessException {@code PRODUCT_NOT_PURCHASABLE},
     *         {@code PRICE_NOT_AVAILABLE}, {@code CUSTOMER_PRICE_BELOW_MOQ}, {@code CUSTOMER_NOT_FOUND}
     */
    java.util.Map<String, com.stockflow.common.domain.Money> checkoutPrices(
            java.util.UUID customerId, java.util.Map<String, Integer> quantities);

    /**
     * The prices this customer gets for a SKU, as tiers - what a quote line starts from (14 step 2).
     * Reads only; takes no lock.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PRICE_NOT_AVAILABLE},
     *         {@code CUSTOMER_NOT_FOUND}
     */
    PriceSchedule priceSchedule(java.util.UUID customerId, String sku);
}
