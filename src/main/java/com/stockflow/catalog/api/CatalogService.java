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

    /**
     * Where the storefront shows each of these SKUs today, so a client can rebuild a basket from SKUs it only
     * remembers (reorder, SCRUM-190). A SKU that is not on sale now - unpublished, suspended, discontinued or
     * unknown - has no entry; its absence is the answer. Reads only; never a price or a stock figure.
     *
     * @param skus upper-case SKU codes
     * @return the entry of each listed SKU, keyed by the SKU code
     */
    java.util.Map<String, SkuListing> listings(java.util.Collection<String> skus);

    /**
     * One SKU's storefront entry.
     *
     * @param slug     the product page: {@code /catalog/{slug}}
     * @param title    the published product title
     * @param imageUrl the product's primary published image, a public URL of the kind the gallery returns; null
     *                 without one
     */
    record SkuListing(String slug, String title, String imageUrl) {
    }
}
