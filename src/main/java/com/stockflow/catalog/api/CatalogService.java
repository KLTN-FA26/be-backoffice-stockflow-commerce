package com.stockflow.catalog.api;

/**
 * THE public API of the catalog module - the only package other modules may import.
 *
 * <p>A customer's own price list is the one source of a price (kltn-docs 13 BR-04, D14): Sales starts a quote from
 * it, and checkout - until it goes (team plan 5-2) - reads a row of it kept on a stock variant. There is no base or
 * group price. Two rules from {@code docs/adding-a-module.md} §1:</p>
 * <ul>
 *   <li>declare only what other modules actually call - the shortest surface that works;</li>
 *   <li>every parameter and return type is a record or enum declared in THIS package, never a
 *       domain object or JPA entity. {@code ArchitectureTest.theApiPackageLeaksNothingInternal}
 *       and {@code theApiPublishesNoEntities} enforce it.</li>
 * </ul>
 *
 */
public interface CatalogService {

    /**
     * The unit prices of an account order, locked for the caller's checkout transaction: for each SKU the customer's
     * own tier for the order's total quantity of that SKU (locked into the order by the caller, 14 BR-03). Stock
     * variants only, and only by a price the customer was given on one before 4B-2: a customer's own SKU is quoted,
     * never bought at checkout (14 BR-01, BR-08), and there is no base or group price to fall back on (13 BR-04, 5-3).
     *
     * @param quantities units per SKU, summed over the order's lines
     * @throws com.stockflow.common.error.BusinessException {@code PRODUCT_NOT_PURCHASABLE} (a customer's own SKU
     *         included), {@code PRICE_NOT_AVAILABLE} without such a price, {@code CUSTOMER_PRICE_BELOW_MOQ},
     *         {@code CUSTOMER_NOT_FOUND}
     */
    java.util.Map<String, com.stockflow.common.domain.Money> checkoutPrices(
            java.util.UUID customerId, java.util.Map<String, Integer> quantities);

    /**
     * The customer's own price row for {@code sku}, if it has one: what a quote line starts from (14 step 2). For one
     * of the customer's own SKUs it counts cartons (4B-2; kltn-docs 13 BR-05); the caller checks the SKU is one - a row
     * kept on a stock variant counts units. Nothing is thrown when there is none - a caller in a transaction would
     * otherwise find it marked rollback-only by the refusal.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CUSTOMER_NOT_FOUND}
     */
    java.util.Optional<PriceSchedule> customerPrice(java.util.UUID customerId, String sku);

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
