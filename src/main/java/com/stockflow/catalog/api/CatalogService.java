package com.stockflow.catalog.api;

/**
 * THE public API of the catalog module - the only package other modules may import.
 *
 * <p>Quotes read the customer's own prices through this port: the one source of a price, with no base or group price
 * behind it (kltn-docs 13 BR-04, D14; team plan 5-3). Nothing is priced here for a checkout: the customer does not
 * place orders (kltn-docs 14 BR-01, BR-02; team plan 5-2). Two rules from {@code docs/adding-a-module.md} §1:</p>
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
}
