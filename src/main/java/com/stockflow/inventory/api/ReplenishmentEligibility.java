package com.stockflow.inventory.api;

import java.util.Collection;
import java.util.Set;

/**
 * What the inventory module asks the module that owns SKUs before it raises a low-stock alert: "may this SKU be
 * bought again?" A REORDER or SAFETY alert asks somebody to put the SKU on a purchase order (kltn-docs 02 step 1,
 * BR-08), and only a SKU that a new purchase order line accepts is worth that (kltn-docs 01 BR-04, 02 BR-01): a
 * draft, a product under review, suspended or discontinued, or a variant that is not {@code ACTIVE}, is not.
 *
 * <p>An interface in {@code inventory :: api} implemented <b>elsewhere</b> ({@code product}), and not a call from
 * inventory into {@code product :: api}: product already depends on inventory (it creates and edits inventory items),
 * so the reverse call would close a cycle that {@code ModularityTest} rejects. Same shape as
 * {@code product.api.SkuReferenceProbe}.</p>
 *
 * <p>Expiry alerts do not ask: they concern stock that is physically on a shelf, whatever its product's status.</p>
 */
public interface ReplenishmentEligibility {

    /**
     * The SKUs among {@code skus} that may go on a new purchase order line. A SKU nobody knows is not in the answer.
     *
     * @param skus SKU codes as stored (upper case)
     */
    Set<String> replenishable(Collection<String> skus);
}
