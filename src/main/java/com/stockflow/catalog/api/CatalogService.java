package com.stockflow.catalog.api;

/**
 * THE public API of the catalog module - the only package other modules may import.
 *
 * <p>Quotes read their reference prices through this port: the customer's own price for a SKU, and the standard price
 * of a variant of the catalogue the Sales Manager keeps (kltn-docs 13 BR-04 as changed on 2026-11-26, D14). A quote line
 * starts from the first, else the second, and the quote waits for the Sales Manager when it is priced too far below it
 * (14 BR-10). There is no group price. Nothing is priced here for a checkout: the customer does not place orders
 * (kltn-docs 14 BR-01, BR-02; team plan 5-2). Two rules from {@code docs/adding-a-module.md} §1:</p>
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
     * The customer's own price row for {@code sku}, if it has one: what a quote line starts from (14 step 2). For one
     * of the customer's own SKUs it counts cartons (4B-2; kltn-docs 13 BR-05); the caller checks the SKU is one - a row
     * kept on a stock variant counts units. Nothing is thrown when there is none - a caller in a transaction would
     * otherwise find it marked rollback-only by the refusal.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CUSTOMER_NOT_FOUND}
     */
    java.util.Optional<PriceSchedule> customerPrice(java.util.UUID customerId, String sku);

    /**
     * The standard price of the variant {@code sku} of the catalogue, if it has one (13 BR-04, 2026-11-26): what a quote
     * line of a customer's SKU drawn on it starts from when the customer has no price of its own, its breaks counted in
     * cartons. Nothing is thrown when there is none.
     */
    java.util.Optional<PriceSchedule> standardPrice(String sku);
}
